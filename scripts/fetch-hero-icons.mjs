// Build-time fetch of the full Dota 2 hero portrait set for local bundling, so hero icons render
// offline with no runtime dependency on Valve's CDN.
//
// The renderer (app/src/data/heroes.ts) imports these via import.meta.glob and Vite hashes them
// into the bundle; a hero missing from the local set (e.g. released after the last build) falls
// back to the Valve CDN at render time. This script writes each <slug>.png into
// app/src/data/hero-portraits/ so the glob picks it up.
//
// Source of truth for the hero list is OpenDota's public hero stats endpoint (self-updating for
// new heroes); the portrait bytes come from the same Valve dota_react CDN the app used to hotlink.
// Filenames are the GSI slug (hero name minus the "npc_dota_hero_" prefix) == the CDN basename ==
// the slug app/src/data/heroes.ts derives, so the renderer's lookup matches 1:1.
//
// Usage: node scripts/fetch-hero-icons.mjs   (wired into `npm run fetch:hero-icons` and build:all).
//
// The ~7 MB of PNGs + a version marker are gitignored; a fresh clone re-downloads them (a tracked
// .gitkeep keeps the dir present so the glob always resolves). Unlike the OBS/ffmpeg fetch there is
// no single pinned sha256 (127 rolling CDN files); integrity rests on the known Valve origin plus a
// completeness gate (abort if more than a handful fail). Bump HERO_ICONS_VERSION (or delete the
// .hero-icons-*.ok marker) to force a refresh when Valve adds a hero.

import {
  createWriteStream,
  existsSync,
  mkdirSync,
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
// If more than this many portraits fail to download, treat it as systemic (bad CDN path / network)
// and abort rather than silently ship a gutted bundle. A few failing (e.g. a brand-new hero not yet
// on the CDN) is tolerated — those fall back to the CDN at render time.
const MAX_TOLERATED_FAILURES = 5;
// Cap concurrent downloads so ~127 small requests finish fast without hammering the CDN.
const CONCURRENCY = 8;
const HERO_PREFIX = 'npc_dota_hero_';

const repoRoot = path.resolve(import.meta.dirname, '..');
const outDir = path.join(repoRoot, 'app', 'src', 'data', 'hero-portraits');
const okMarker = path.join(outDir, `.hero-icons-${HERO_ICONS_VERSION}.ok`);

async function fetchHeroSlugs() {
  const res = await fetch(HERO_LIST_URL, {
    redirect: 'follow',
    headers: { 'user-agent': UA, accept: 'application/json' },
  });
  if (!res.ok) {
    throw new Error(
      `Hero list fetch failed: ${res.status} ${res.statusText} for ${HERO_LIST_URL}\n` +
        `(OpenDota may be rate-limiting/blocking — retry, or override HERO_LIST_URL)`,
    );
  }
  const heroes = await res.json();
  if (!Array.isArray(heroes) || heroes.length === 0) {
    throw new Error(`Hero list was empty or not an array (got ${typeof heroes}).`);
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
    throw new Error(`No hero slugs derived — the OpenDota response shape may have changed.`);
  }
  return slugs;
}

// Download one portrait to <outDir>/<slug>.png. Returns true on success (or an already-present
// file), false on a tolerated per-hero failure. Writes through a .tmp file so an interrupted run
// never leaves a half-written PNG that a later run would treat as complete.
async function downloadPortrait(slug) {
  const dest = path.join(outDir, `${slug}.png`);
  if (existsSync(dest) && statSync(dest).size > 0) return true;
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
  if (existsSync(okMarker)) {
    console.log(`Hero portraits ${HERO_ICONS_VERSION} already bundled in ${outDir} — skipping.`);
    return;
  }
  mkdirSync(outDir, { recursive: true });

  console.log(`Fetching hero list from ${HERO_LIST_URL}`);
  const slugs = await fetchHeroSlugs();
  console.log(`Downloading ${slugs.length} hero portraits (concurrency ${CONCURRENCY})…`);

  // Bounded-concurrency pool: N workers pull from a shared cursor until the list is drained.
  let cursor = 0;
  const failures = [];
  async function worker() {
    while (cursor < slugs.length) {
      const slug = slugs[cursor++];
      if (!(await downloadPortrait(slug))) failures.push(slug);
    }
  }
  await Promise.all(Array.from({ length: Math.min(CONCURRENCY, slugs.length) }, worker));

  const got = slugs.length - failures.length;
  if (failures.length > MAX_TOLERATED_FAILURES) {
    throw new Error(
      `Only ${got}/${slugs.length} portraits downloaded (${failures.length} failed): ` +
        `${failures.join(', ')}\n` +
        `Refusing to write the completion marker — re-run once the CDN/network is reachable.`,
    );
  }
  writeFileSync(okMarker, `${HERO_ICONS_VERSION}\n${got} portraits\n`);
  const note = failures.length
    ? ` (${failures.length} unavailable, CDN fallback: ${failures.join(', ')})`
    : '';
  console.log(`Bundled ${got} hero portraits in ${outDir}${note}`);
}

main().catch((e) => {
  console.error(e.message ?? e);
  process.exit(1);
});
