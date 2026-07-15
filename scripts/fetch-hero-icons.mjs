// Build-time fetch of the full Dota 2 hero portrait set for local bundling, so hero icons render
// offline with no runtime dependency on Valve's CDN.
//
// The renderer (app/src/data/heroes.ts) imports these via import.meta.glob and Vite hashes them
// into the bundle; a hero missing from the local set (e.g. released after the last build) falls
// back to the Valve CDN at render time. This script writes each <slug>.png into
// app/src/data/hero-portraits/ so the glob picks it up.
//
// Source of truth for the hero list is OpenDota's public hero stats endpoint; the portrait bytes
// come from the same Valve dota_react CDN the app used to hotlink. Filenames are the GSI slug (hero
// name minus the "npc_dota_hero_" prefix) == the CDN basename == the slug app/src/data/heroes.ts
// derives, so the renderer's lookup matches 1:1.
//
// Usage: node scripts/fetch-hero-icons.mjs   (wired into `npm run fetch:hero-icons` and build:all).
//
// Design (differs from the OBS/ffmpeg fetch, whose binaries have NO runtime fallback):
//   - Best-effort, never blocks the build on OpenDota. Bundled icons are an optimization; if the
//     hero LIST can't be fetched, we warn and skip — the app just uses the CDN fallback at runtime.
//   - A systemic DOWNLOAD failure (list OK but >MAX_TOLERATED_FAILURES portraits fail, e.g. a Valve
//     CDN-path change) still aborts, so we don't silently ship a gutted bundle.
//   - The .ok marker is written ONLY on a complete (zero-failure) download, and the skip guard
//     verifies the PNGs are actually on disk — so a partial run self-heals on the next build and a
//     bare marker with the PNGs deleted re-fetches instead of shipping nothing.
//   - The ~7 MB of PNGs + the marker are gitignored; a fresh clone re-downloads them (a tracked
//     .gitkeep keeps the dir present so the glob always resolves).
//   - Bump HERO_ICONS_VERSION to refresh: a marker for a different version is treated as a stale
//     bundle and the existing PNGs are cleared so updated art is re-pulled, not just new heroes.

import {
  createWriteStream,
  existsSync,
  mkdirSync,
  readFileSync,
  readdirSync,
  renameSync,
  rmSync,
  statSync,
  writeFileSync,
} from 'node:fs';
import { Readable } from 'node:stream';
import { pipeline } from 'node:stream/promises';
import * as path from 'node:path';

const HERO_ICONS_VERSION = process.env.HERO_ICONS_VERSION ?? '2026-07-14';
const HERO_LIST_URL = process.env.HERO_LIST_URL ?? 'https://api.opendota.com/api/heroStats';
const CDN_BASE =
  process.env.HERO_CDN_BASE ??
  'https://cdn.cloudflare.steamstatic.com/apps/dota2/images/dota_react/heroes/';
// OpenDota's API and the Steam CDN sit behind Cloudflare, which 403s bare programmatic clients;
// send a browser-like UA so the build script isn't blocked.
const UA =
  'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) ' +
  'Chrome/130.0.0.0 Safari/537.36';
// If more than this many portraits fail to download (list already in hand), treat it as systemic —
// a bad CDN path or a dead network — and abort rather than ship a gutted bundle. A few failing
// (e.g. a brand-new hero not yet on the CDN) is tolerated; those fall back to the CDN at runtime,
// and because the marker is withheld unless ALL succeed, the next build retries them.
const MAX_TOLERATED_FAILURES = 5;
// Cap concurrent downloads so ~127 small requests finish fast without hammering the CDN.
const CONCURRENCY = 8;
const HERO_PREFIX = 'npc_dota_hero_';

const repoRoot = path.resolve(import.meta.dirname, '..');
const outDir = path.join(repoRoot, 'app', 'src', 'data', 'hero-portraits');
const markerName = `.hero-icons-${HERO_ICONS_VERSION}.ok`;
const okMarker = path.join(outDir, markerName);

/** Names of the *.png portraits currently on disk (empty if the dir doesn't exist yet). */
function listPortraits() {
  if (!existsSync(outDir)) return [];
  return readdirSync(outDir).filter((f) => f.endsWith('.png'));
}

/** Names of every `.hero-icons-<version>.ok` marker on disk (across versions). */
function listMarkers() {
  if (!existsSync(outDir)) return [];
  return readdirSync(outDir).filter((f) => f.startsWith('.hero-icons-') && f.endsWith('.ok'));
}

/** Portrait count recorded in the current marker ("<version>\n<N> portraits\n"), or 0. */
function markerPortraitCount() {
  try {
    const m = readFileSync(okMarker, 'utf8').match(/(\d+)\s+portraits/);
    return m ? Number(m[1]) : 0;
  } catch {
    return 0;
  }
}

/**
 * Fetch the hero slug list from OpenDota. Returns a string[] on success, or `null` on ANY failure
 * (logged as a warning) — a failed list fetch must NOT abort the build, since hero icons degrade to
 * the runtime CDN fallback. Only a systemic *download* failure (in main) aborts.
 */
async function fetchHeroSlugs() {
  let heroes;
  try {
    const res = await fetch(HERO_LIST_URL, {
      redirect: 'follow',
      headers: { 'user-agent': UA, accept: 'application/json' },
    });
    if (!res.ok) {
      console.warn(
        `[!] hero list fetch failed: ${res.status} ${res.statusText} for ${HERO_LIST_URL}`,
      );
      return null;
    }
    heroes = await res.json();
  } catch (e) {
    console.warn(`[!] hero list fetch failed: ${e.message ?? e}`);
    return null;
  }
  if (!Array.isArray(heroes) || heroes.length === 0) {
    console.warn(`[!] hero list was empty or not an array (got ${typeof heroes}).`);
    return null;
  }
  const slugs = [
    ...new Set(
      heroes
        .map((h) => (typeof h?.name === 'string' ? h.name : ''))
        .map((name) => (name.startsWith(HERO_PREFIX) ? name.slice(HERO_PREFIX.length) : name))
        .map((s) => s.trim().toLowerCase())
        .filter((s) => s !== ''),
    ),
  ];
  if (slugs.length === 0) {
    console.warn(`[!] no hero slugs derived — the OpenDota response shape may have changed.`);
    return null;
  }
  return slugs;
}

// Download one portrait to <outDir>/<slug>.png. Returns true on success (or an already-present
// file), false on a tolerated per-hero failure. Writes through a .tmp file so an interrupted run
// never leaves a half-written PNG that a later run would treat as complete.
async function downloadPortrait(slug, force = false) {
  const dest = path.join(outDir, `${slug}.png`);
  // Skip a portrait already on disk (resumes a partial run) — unless forcing a refresh, where we
  // re-pull and atomically overwrite the existing art (renameSync replaces dest).
  if (!force && existsSync(dest) && statSync(dest).size > 0) return true;
  const url = `${CDN_BASE}${slug}.png`;
  const tmp = `${dest}.tmp`;
  try {
    const res = await fetch(url, { redirect: 'follow', headers: { 'user-agent': UA } });
    if (!res.ok || !res.body) {
      console.warn(
        `[!] ${slug}: ${res.status} ${res.statusText} — skipping (CDN fallback at runtime)`,
      );
      return false;
    }
    await pipeline(Readable.fromWeb(res.body), createWriteStream(tmp));
    if (statSync(tmp).size === 0) {
      rmSync(tmp, { force: true });
      console.warn(`[!] ${slug}: empty download — skipping`);
      return false;
    }
    renameSync(tmp, dest);
    return true;
  } catch (e) {
    rmSync(tmp, { force: true });
    console.warn(`[!] ${slug}: ${e.message ?? e} — skipping`);
    return false;
  }
}

async function main() {
  // Already bundled for this version AND the portraits are actually on disk — a bare marker with the
  // PNGs deleted (they're gitignored) must NOT count as done.
  if (existsSync(okMarker)) {
    const have = listPortraits().length;
    const want = markerPortraitCount();
    if (have > 0 && have >= want) {
      console.log(
        `Hero portraits ${HERO_ICONS_VERSION} already bundled (${have} on disk) — skipping.`,
      );
      return;
    }
    console.warn(
      `[!] marker present but only ${have}/${want || '?'} portraits on disk — re-fetching.`,
    );
  }
  mkdirSync(outDir, { recursive: true });

  // Version bump = refresh: a marker for a DIFFERENT version means the on-disk PNGs are an older
  // bundle, so re-pull every portrait (not just newly-added heroes) to pick up updated art. This is
  // a NON-destructive force — downloadPortrait re-fetches and atomically overwrites each file. We do
  // NOT purge up-front: if the list fetch below fails, the existing bundle must survive intact.
  const staleMarkers = listMarkers().filter((m) => m !== markerName);
  const refresh = staleMarkers.length > 0;
  if (refresh) {
    console.log(`HERO_ICONS_VERSION is now ${HERO_ICONS_VERSION} — refreshing every portrait.`);
  }

  console.log(`Fetching hero list from ${HERO_LIST_URL}`);
  const slugs = await fetchHeroSlugs();
  if (slugs === null) {
    // Best-effort: never block the build on OpenDota. Whatever PNGs are already on disk still ship;
    // anything missing falls back to the CDN at runtime. No marker written, so a later build retries.
    console.warn(
      `[!] Skipping the hero-icon bundle — the app will use the CDN fallback at runtime. Build continues.`,
    );
    return;
  }
  console.log(`Downloading ${slugs.length} hero portraits (concurrency ${CONCURRENCY})…`);

  // Bounded-concurrency pool: N workers pull from a shared cursor until the list is drained.
  let cursor = 0;
  const failures = [];
  async function worker() {
    while (cursor < slugs.length) {
      const slug = slugs[cursor++];
      if (!(await downloadPortrait(slug, refresh))) failures.push(slug);
    }
  }
  await Promise.all(Array.from({ length: Math.min(CONCURRENCY, slugs.length) }, worker));

  const got = slugs.length - failures.length;
  if (failures.length > MAX_TOLERATED_FAILURES) {
    throw new Error(
      `Only ${got}/${slugs.length} portraits downloaded (${failures.length} failed): ` +
        `${failures.join(', ')}\n` +
        `This looks systemic (bad CDN path / network) — aborting rather than shipping a gutted bundle.`,
    );
  }
  if (failures.length === 0) {
    // Fresh bundle complete — retire any older-version markers, then stamp this version's marker.
    for (const m of staleMarkers) rmSync(path.join(outDir, m), { force: true });
    writeFileSync(okMarker, `${HERO_ICONS_VERSION}\n${got} portraits\n`);
    console.log(`Bundled all ${got} hero portraits in ${outDir}`);
    return;
  }
  // A handful failed: withhold the marker so the next build retries them (per-file skip makes the
  // successful ones instant) instead of baking the gap in permanently. Meanwhile they use the CDN.
  console.warn(
    `[!] Bundled ${got}/${slugs.length}; ${failures.length} will be retried next build ` +
      `(CDN fallback meanwhile): ${failures.join(', ')}`,
  );
}

main().catch((e) => {
  console.error(e.message ?? e);
  process.exit(1);
});
