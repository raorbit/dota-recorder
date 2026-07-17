// Build-time fetch of a pinned static ffmpeg.exe for bundling (zero-config encoding).
//
// Downloads a static Windows ffmpeg build, verifies its sha256, and extracts ONLY
// bin/ffmpeg.exe (plus the build's LICENSE — it's a GPL binary, so the license text
// must ship next to it) to build-resources/ffmpeg/. electron-builder ships the
// binary as <resourcesPath>/ffmpeg/ffmpeg.exe (see electron-builder.yml), and at
// runtime the Electron supervisor passes its path to the core (app.ffmpeg.path /
// DOTAREC_FFMPEG_PATH) so the core never depends on ffmpeg being on PATH.
//
// Usage: node scripts/fetch-ffmpeg.mjs   (wired into `npm run fetch:ffmpeg` and `dist`).
//
// Pinning (supply-chain): set FFMPEG_VERSION + FFMPEG_SHA256 below. On the FIRST run
// with an empty FFMPEG_SHA256 the script prints the computed hash to paste in; once
// pinned it verifies strictly and aborts on mismatch. Override either via env at build
// time. The default build is a version-tagged gyan.dev "essentials" release asset (immutable, so
// the pin reproduces byte-for-byte); swap FFMPEG_URL for a BtbN release if a full build is needed.
// The unpacked ffmpeg.exe + .ok marker are gitignored, so a fresh clone / CI has no
// cache and re-downloads + re-verifies the zip against FFMPEG_SHA256 every time — the
// supply-chain guarantee rides on that hash, not on the (untracked) cached binary.

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
import { createHash } from 'node:crypto';
import { Readable } from 'node:stream';
import { pipeline } from 'node:stream/promises';
import { execFileSync } from 'node:child_process';
import * as path from 'node:path';

const FFMPEG_VERSION = process.env.FFMPEG_VERSION ?? '8.1.2';
// Immutable, version-tagged gyan.dev build shipped as a GitHub release asset (GyanD/codexffmpeg).
// Unlike the old rolling ffmpeg-release-essentials.zip — whose bytes (and therefore sha) drift every
// time upstream rebuilds, which would abort a clean-checkout release on the pinned-hash check — a
// tagged release asset is permanent, so this pin reproduces byte-for-byte across builds. (Verified:
// this asset is byte-identical to the release-essentials.zip pinned before — same sha256 below.)
const FFMPEG_SHA256 =
  process.env.FFMPEG_SHA256 ?? 'db580001caa24ac104c8cb856cd113a87b0a443f7bdf47d8c12b1d740584a2ec';
const FFMPEG_URL =
  process.env.FFMPEG_URL ??
  `https://github.com/GyanD/codexffmpeg/releases/download/${FFMPEG_VERSION}/ffmpeg-${FFMPEG_VERSION}-essentials_build.zip`;

const repoRoot = path.resolve(import.meta.dirname, '..');
const outDir = path.join(repoRoot, 'build-resources', 'ffmpeg');
const cacheDir = path.join(outDir, 'cache');
const zipName = `ffmpeg-${FFMPEG_VERSION}.zip`;
const zipPath = path.join(cacheDir, zipName);
const extractDir = path.join(cacheDir, 'extract');
const okMarker = path.join(repoRoot, 'build-resources', `.ffmpeg-${FFMPEG_VERSION}.ok`);
const ffmpegExe = path.join(outDir, 'ffmpeg.exe');
const licenseFile = path.join(outDir, 'LICENSE');

async function main() {
  // The LICENSE check re-runs extraction on caches that predate license bundling.
  if (existsSync(okMarker) && existsSync(ffmpegExe) && existsSync(licenseFile)) {
    console.log(`ffmpeg ${FFMPEG_VERSION} already bundled at ${ffmpegExe} — skipping.`);
    return;
  }
  mkdirSync(cacheDir, { recursive: true });

  if (!existsSync(zipPath)) {
    console.log(`Downloading ${FFMPEG_URL}`);
    const res = await fetch(FFMPEG_URL, { redirect: 'follow' });
    if (!res.ok || !res.body) {
      throw new Error(
        `Download failed: ${res.status} ${res.statusText} for ${FFMPEG_URL}\n` +
          `(check FFMPEG_URL — the release asset URL must exist)`,
      );
    }
    await pipeline(Readable.fromWeb(res.body), createWriteStream(zipPath));
    console.log(`Downloaded ${(statSync(zipPath).size / 1e6).toFixed(1)} MB`);
  } else {
    console.log(`Using cached zip ${zipPath}`);
  }

  const sha = createHash('sha256').update(readFileSync(zipPath)).digest('hex');
  if (!FFMPEG_SHA256) {
    console.warn(
      `\n[!] FFMPEG_SHA256 is not pinned. Computed sha256:\n    ${sha}\n` +
        `    Paste it into FFMPEG_SHA256 in scripts/fetch-ffmpeg.mjs to enable strict verification.\n`,
    );
  } else if (sha.toLowerCase() !== FFMPEG_SHA256.toLowerCase()) {
    rmSync(zipPath, { force: true });
    throw new Error(
      `sha256 mismatch for ${zipName}\n  expected ${FFMPEG_SHA256}\n  got      ${sha}\n` +
        `(deleted the bad download; re-run)`,
    );
  } else {
    console.log(`sha256 verified.`);
  }

  console.log(`Extracting -> ${ffmpegExe}`);
  rmSync(extractDir, { recursive: true, force: true });
  mkdirSync(extractDir, { recursive: true });
  // Windows-only target: use PowerShell Expand-Archive (no extra npm dependency).
  // Pass the paths via env vars and read them back with $env: inside the command — never
  // interpolate them into the PowerShell string, so paths containing apostrophes, spaces
  // or other special characters can't produce an unbalanced/invalid command.
  execFileSync(
    'powershell',
    [
      '-NoProfile',
      '-NonInteractive',
      '-Command',
      'Expand-Archive -LiteralPath $env:DOTAREC_ZIP -DestinationPath $env:DOTAREC_DEST -Force',
    ],
    { stdio: 'inherit', env: { ...process.env, DOTAREC_ZIP: zipPath, DOTAREC_DEST: extractDir } },
  );

  // These static zips wrap everything in a versioned top-level dir (e.g.
  // ffmpeg-7.1-essentials_build/bin/ffmpeg.exe). Locate bin/ffmpeg.exe wherever it lands
  // and copy ONLY that one binary out — plus the build's LICENSE, which ships next to
  // the exe (GPL binary, so its license text must travel with the bundle).
  const found = findFfmpegExe(extractDir);
  if (!found) {
    throw new Error(
      `Extraction did not produce a bin/ffmpeg.exe under ${extractDir} — the ffmpeg zip layout may have changed.`,
    );
  }
  const foundLicense = findLicense(extractDir);
  if (!foundLicense) {
    throw new Error(
      `Extraction did not produce a LICENSE under ${extractDir} — the ffmpeg zip layout may have changed.`,
    );
  }
  rmSync(ffmpegExe, { force: true });
  renameSync(found, ffmpegExe);
  rmSync(licenseFile, { force: true });
  renameSync(foundLicense, licenseFile);
  rmSync(extractDir, { recursive: true, force: true });
  if (!existsSync(ffmpegExe) || !existsSync(licenseFile)) {
    throw new Error(`Failed to place ${ffmpegExe} + ${licenseFile}.`);
  }

  writeFileSync(okMarker, FFMPEG_VERSION);
  console.log(`ffmpeg ${FFMPEG_VERSION} bundled at ${ffmpegExe}`);
}

// Recursively locate the build's LICENSE file (gyan.dev builds carry it at the top of the
// versioned dir; accept a .txt suffix too in case the layout ever changes).
function findLicense(dir) {
  for (const entry of readdirSync(dir, { withFileTypes: true })) {
    const p = path.join(dir, entry.name);
    if (entry.isDirectory()) {
      const hit = findLicense(p);
      if (hit) return hit;
    } else if (/^licen[sc]e(\.txt)?$/i.test(entry.name)) {
      return p;
    }
  }
  return null;
}

// Recursively locate the first `bin/ffmpeg.exe` under `dir` (static zips nest it under a
// versioned top-level directory whose exact name we don't want to hardcode).
function findFfmpegExe(dir) {
  for (const entry of readdirSync(dir, { withFileTypes: true })) {
    const p = path.join(dir, entry.name);
    if (entry.isDirectory()) {
      const hit = findFfmpegExe(p);
      if (hit) return hit;
    } else if (
      entry.name.toLowerCase() === 'ffmpeg.exe' &&
      path.basename(dir).toLowerCase() === 'bin'
    ) {
      return p;
    }
  }
  return null;
}

main().catch((e) => {
  console.error(e.message ?? e);
  process.exit(1);
});
