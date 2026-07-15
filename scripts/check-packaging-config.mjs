// Lightweight packaging-config guard for CI. Full electron-builder packaging (fetch:obs +
// build:all + electron-builder) is far too heavy to run per-PR, so the packaging layer — the one
// that historically produced the worst regressions (the PR #22 flat `to: obs` that broke first-run
// OBS, and a koffi asarUnpack that missed the @koromix scoped package) — is otherwise validated only
// when a human manually builds and installs. This asserts the invariants that regressed, from the
// electron-builder.yml text plus (when present) the installed koffi layout, so a config regression
// fails in CI in seconds instead of at release time. It deliberately does NOT assert the existence of
// build outputs (core.jar / jre-image / obs / ffmpeg): those are produced by the heavy build this
// check exists to avoid, and are absent on a fresh CI checkout.
//
// Usage: node scripts/check-packaging-config.mjs   (wired into CI as its own step).

import { existsSync, readFileSync, readdirSync } from 'node:fs';
import * as path from 'node:path';

const repoRoot = path.resolve(import.meta.dirname, '..');
const ebPath = path.join(repoRoot, 'electron-builder.yml');

const failures = [];
function check(cond, message) {
  if (!cond) failures.push(message);
}

// --- electron-builder.yml invariants (text-level, so no YAML dependency is needed) ---------------
if (!existsSync(ebPath)) {
  console.error(`electron-builder.yml not found at ${ebPath}`);
  process.exit(1);
}
const eb = readFileSync(ebPath, 'utf8');

// PR #22 regression: the OBS binaries MUST ship under obs/obs-portable (a flat `to: obs` drops the
// subdir and leaves paths.ts pointing one level too deep, so first-run OBS copy silently fails).
check(
  /from:\s*build-resources\/obs\/obs-portable/.test(eb) && /to:\s*obs\/obs-portable/.test(eb),
  'electron-builder.yml: OBS binaries must map `from: build-resources/obs/obs-portable` -> `to: obs/obs-portable` (a flat `to: obs` was the PR #22 packaged-OBS blocker).',
);
// The `.obs-<version>.ok` version marker must be shipped as a sibling of obs-portable, else
// obsVersion() reads "0" and the first-run copy is skipped.
check(
  /\.obs-\*\.ok/.test(eb),
  'electron-builder.yml: the `.obs-*.ok` OBS version marker filter is missing (obsVersion() would read "0" and skip the first-run copy).',
);

// The core jar + trimmed JRE image the supervisor launches, and the bundled ffmpeg, must be mapped.
check(/to:\s*core\/core\.jar/.test(eb), 'electron-builder.yml: missing `to: core/core.jar` extraResources mapping.');
check(/to:\s*jre\b/.test(eb), 'electron-builder.yml: missing `to: jre` (trimmed JRE image) extraResources mapping.');
check(/to:\s*ffmpeg\/ffmpeg\.exe/.test(eb), 'electron-builder.yml: missing `to: ffmpeg/ffmpeg.exe` extraResources mapping.');

// koffi 3.x native binary lives in a scoped @koromix package; BOTH koffi/** and @koromix/** must be
// unpacked from the asar or the Job Object silently falls back to taskkill in the packaged build.
check(
  /node_modules\/koffi\/\*\*/.test(eb),
  'electron-builder.yml: asarUnpack must include `**/node_modules/koffi/**`.',
);
check(
  /node_modules\/@koromix\/\*\*/.test(eb),
  'electron-builder.yml: asarUnpack must include `**/node_modules/@koromix/**` (koffi 3.x ships koffi.node in the scoped @koromix package).',
);

// The installer artifact name must be space-free so the on-disk .exe, latest.yml url, and the
// uploaded GitHub asset all match (a spaced name 404s the electron-updater feed).
const artifact = /artifactName:\s*(.+)/.exec(eb);
check(
  artifact != null && !/\s/.test(artifact[1].trim().replace(/\$\{[^}]+\}/g, 'x')),
  'electron-builder.yml: nsis.artifactName must be space-free (a spaced name diverges from latest.yml and 404s the updater).',
);

// --- koffi layout cross-check (only when installed; CI installs app deps on windows) --------------
// If koffi resolved on this platform, assert the scoped @koromix package the asarUnpack glob targets
// actually exists — so a koffi upgrade that relocated koffi.node fails here rather than at runtime.
const appModules = path.join(repoRoot, 'app', 'node_modules');
const koffiDir = path.join(appModules, 'koffi');
if (existsSync(koffiDir)) {
  const koromixDir = path.join(appModules, '@koromix');
  const hasNativePkg =
    existsSync(koromixDir) &&
    readdirSync(koromixDir).some((d) => d.startsWith('koffi-'));
  check(
    hasNativePkg,
    'koffi is installed but app/node_modules/@koromix/koffi-* is missing — the asarUnpack `@koromix/**` glob would match nothing and the packaged Job Object would fall back to taskkill. A koffi upgrade may have relocated koffi.node.',
  );
}

if (failures.length > 0) {
  console.error('Packaging config check FAILED:\n' + failures.map((f) => `  - ${f}`).join('\n'));
  process.exit(1);
}
console.log('Packaging config check passed.');
