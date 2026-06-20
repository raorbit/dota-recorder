// Build-time fetch of a pinned portable OBS for bundling (zero-config recording).
//
// Downloads the official OBS Studio Windows portable zip, verifies its sha256,
// and extracts it to build-resources/obs/obs-portable/ (bin/ data/ obs-plugins/).
// electron-builder ships that dir as <resourcesPath>/obs (see electron-builder.yml),
// and at runtime the app copies it to a writable per-user dir, marks it portable,
// auto-configures it, and drives it over obs-websocket — the user installs nothing.
//
// Usage: node scripts/fetch-obs.mjs   (wired into `npm run fetch:obs` and `dist`).
//
// Pinning (supply-chain): set OBS_VERSION + OBS_SHA256 below. On the FIRST run with
// an empty OBS_SHA256 the script prints the computed hash to paste in; once pinned
// it verifies strictly and aborts on mismatch. Override either via env at build time.
//
// NOTE (review fix): CEF/browser-source trimming is OPT-IN (DOTAREC_OBS_TRIM=1) and
// OFF by default — trimming must be verified to not break OBS startup / game capture
// before it is made the default, so v1 ships the full OBS.

import { createWriteStream, existsSync, mkdirSync, readFileSync, rmSync, statSync, writeFileSync } from 'node:fs';
import { createHash } from 'node:crypto';
import { Readable } from 'node:stream';
import { pipeline } from 'node:stream/promises';
import { execFileSync } from 'node:child_process';
import * as path from 'node:path';

const OBS_VERSION = process.env.OBS_VERSION ?? '32.1.2';
const OBS_SHA256 = process.env.OBS_SHA256 ?? '8d97e4563bd8d22d03e63042aa7dccede1d555c9bd35ce8a9e5019b0d0201bf6'; // OBS-Studio-32.1.2-Windows-x64.zip
const TRIM_CEF = process.env.DOTAREC_OBS_TRIM === '1'; // off by default until verified

const repoRoot = path.resolve(import.meta.dirname, '..');
const outDir = path.join(repoRoot, 'build-resources', 'obs');
const cacheDir = path.join(outDir, 'cache');
const portableDir = path.join(outDir, 'obs-portable');
const zipName = `OBS-Studio-${OBS_VERSION}-Windows-x64.zip`;
const zipPath = path.join(cacheDir, zipName);
const url = `https://github.com/obsproject/obs-studio/releases/download/${OBS_VERSION}/${zipName}`;
const okMarker = path.join(outDir, `.obs-${OBS_VERSION}.ok`);
const obs64 = path.join(portableDir, 'bin', '64bit', 'obs64.exe');

async function main() {
  if (existsSync(okMarker) && existsSync(obs64)) {
    console.log(`OBS ${OBS_VERSION} already bundled at ${portableDir} — skipping.`);
    return;
  }
  mkdirSync(cacheDir, { recursive: true });

  if (!existsSync(zipPath)) {
    console.log(`Downloading ${url}`);
    const res = await fetch(url, { redirect: 'follow' });
    if (!res.ok || !res.body) {
      throw new Error(`Download failed: ${res.status} ${res.statusText} for ${url}\n` +
        `(check OBS_VERSION — the release asset URL must exist)`);
    }
    await pipeline(Readable.fromWeb(res.body), createWriteStream(zipPath));
    console.log(`Downloaded ${(statSync(zipPath).size / 1e6).toFixed(1)} MB`);
  } else {
    console.log(`Using cached zip ${zipPath}`);
  }

  const sha = createHash('sha256').update(readFileSync(zipPath)).digest('hex');
  if (!OBS_SHA256) {
    console.warn(`\n[!] OBS_SHA256 is not pinned. Computed sha256:\n    ${sha}\n` +
      `    Paste it into OBS_SHA256 in scripts/fetch-obs.mjs to enable strict verification.\n`);
  } else if (sha.toLowerCase() !== OBS_SHA256.toLowerCase()) {
    rmSync(zipPath, { force: true });
    throw new Error(`sha256 mismatch for ${zipName}\n  expected ${OBS_SHA256}\n  got      ${sha}\n` +
      `(deleted the bad download; re-run)`);
  } else {
    console.log(`sha256 verified.`);
  }

  console.log(`Extracting -> ${portableDir}`);
  rmSync(portableDir, { recursive: true, force: true });
  mkdirSync(portableDir, { recursive: true });
  // Windows-only target: use PowerShell Expand-Archive (no extra npm dependency).
  execFileSync('powershell', ['-NoProfile', '-NonInteractive', '-Command',
    `Expand-Archive -Path '${zipPath}' -DestinationPath '${portableDir}' -Force`], { stdio: 'inherit' });
  if (!existsSync(obs64)) {
    throw new Error(`Extraction did not produce ${obs64} — the OBS zip layout may have changed.`);
  }

  if (TRIM_CEF) trimBrowserSource();

  writeFileSync(okMarker, OBS_VERSION);
  console.log(`OBS ${OBS_VERSION} bundled at ${portableDir}${TRIM_CEF ? ' (CEF trimmed)' : ''}`);
}

// Removes the browser-source (CEF) payload — unused by this app, ~100 MB.
// Conservative: only browser-specific files. KEEP ANGLE/Vulkan/Qt libs (libEGL,
// libGLESv2, vulkan-1) which OBS itself may use. Verify OBS still starts after.
function trimBrowserSource() {
  const plug = path.join(portableDir, 'obs-plugins', '64bit');
  const targets = [
    'obs-browser.dll', 'obs-browser.pdb', 'obs-browser-page.exe',
    'libcef.dll', 'chrome_elf.dll', 'snapshot_blob.bin', 'v8_context_snapshot.bin',
  ];
  let freed = 0;
  for (const t of targets) {
    const p = path.join(plug, t);
    if (existsSync(p)) { freed += statSync(p).size; rmSync(p, { force: true }); }
  }
  for (const d of ['locales', 'swiftshader']) {
    const p = path.join(plug, d);
    if (existsSync(p)) { rmSync(p, { recursive: true, force: true }); }
  }
  // *.pak CEF resource bundles
  console.log(`Trimmed browser source (~${(freed / 1e6).toFixed(0)} MB+ of named files; verify OBS still starts).`);
}

main().catch((e) => { console.error(e.message ?? e); process.exit(1); });
