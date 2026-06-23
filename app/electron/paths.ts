// Resolves filesystem locations and the loopback bridge endpoints used by the
// Electron supervisor and renderer.
//
// Plan (design_handoff_dota2_recorder/README.md - Stack): the UI talks to the
// local Java core over REST + WebSocket. Packaged builds run the bundled JRE +
// jar from process.resourcesPath; dev builds target a configurable jar path.
import { app } from 'electron';
import * as fs from 'node:fs';
import * as path from 'node:path';

// Loopback only (127.0.0.1) - never 0.0.0.0. Binding the core to loopback is the
// correct local-only security posture AND avoids the Windows Defender Firewall
// "Allow access?" prompt entirely (loopback bypasses inbound filtering).
export const BRIDGE_HOST = '127.0.0.1';
export const BRIDGE_PORT = 3224;
export const BRIDGE_BASE = `http://${BRIDGE_HOST}:${BRIDGE_PORT}`;
export const HEALTH_URL = `${BRIDGE_BASE}/health`;
export const WS_URL = `ws://${BRIDGE_HOST}:${BRIDGE_PORT}/ws`;

// Per-launch shared secret for the bridge connector. The main process generates it,
// hands it to the core (env DOTAREC_BRIDGE_TOKEN) and to the renderer (preload), and
// every bridge request must echo it (REST header / WS query param). See the core's
// BridgeAuthFilter. Loopback binding alone does not stop a web page in the user's
// browser from reaching 127.0.0.1, so the token is what actually gates access.
export const BRIDGE_TOKEN_ENV = 'DOTAREC_BRIDGE_TOKEN';
export const BRIDGE_TOKEN_HEADER = 'X-Dotarec-Token';
// Prefix of the additionalArguments switch the main process passes to the BrowserWindow
// so a sandboxed preload can read the token from process.argv.
export const BRIDGE_TOKEN_ARG_PREFIX = '--dotarec-bridge-token=';

function isPackaged(): boolean {
  return app.isPackaged;
}

/** Absolute path to the bundled javaw.exe in a packaged build, or `null` in dev. */
export function bundledJavawPath(): string | null {
  if (!isPackaged()) return null;
  return path.join(process.resourcesPath, 'jre', 'bin', 'javaw.exe');
}

/**
 * Resolve the core runnable jar.
 *  - packaged: <resourcesPath>/core/core.jar
 *  - dev:      $DOTAREC_CORE_JAR if set, else the first *.jar in core/build/libs
 * Returns null if no jar can be located (caller surfaces an actionable error).
 */
export function resolveCoreJar(): string | null {
  if (isPackaged()) {
    return path.join(process.resourcesPath, 'core', 'core.jar');
  }

  const fromEnv = process.env.DOTAREC_CORE_JAR;
  if (fromEnv && fromEnv.trim().length > 0) {
    return path.resolve(fromEnv.trim());
  }

  // Default dev location: <repoRoot>/core/build/libs/*.jar. app.getAppPath() in
  // dev points at the app/ dir; the repo root is one level up.
  const libsDir = path.resolve(app.getAppPath(), '..', 'core', 'build', 'libs');
  if (!fs.existsSync(libsDir)) return null;

  const jar = fs
    .readdirSync(libsDir)
    .filter((f) => f.endsWith('.jar') && !f.endsWith('-plain.jar'))
    .sort()[0];

  return jar ? path.join(libsDir, jar) : null;
}

/** Absolute path to the renderer entry HTML in a packaged build. */
export function packagedIndexHtml(): string {
  return path.join(app.getAppPath(), 'dist', 'index.html');
}

/**
 * App log directory, shared with the core. Electron's `appData` path is %APPDATA%
 * (Roaming) on Windows, so this matches the core's AppPaths.logDir()
 * (%APPDATA%/dota-recorder/log) — both logs land in one findable place.
 */
export function logDir(): string {
  return path.join(app.getPath('appData'), 'dota-recorder', 'log');
}

/** Absolute path to the Electron-side log file (core stdout/stderr + main process). */
export function electronLogPath(): string {
  return path.join(logDir(), 'electron.log');
}

/**
 * Writable OBS directory (%LOCALAPPDATA%/dota-recorder/obs on Windows). Electron
 * launches obs64.exe from here, and the core materializes/configures it on first
 * run. Falls back to appData when LOCALAPPDATA is unset (non-Windows / odd envs).
 */
export function obsDir(): string {
  const localAppData = process.env.LOCALAPPDATA;
  if (localAppData) {
    return path.join(localAppData, 'dota-recorder', 'obs');
  }
  return path.join(app.getPath('appData'), 'dota-recorder', 'obs');
}

/**
 * Source (bundled) OBS directory the core copies from on first run.
 *  - packaged: <resourcesPath>/obs/obs-portable
 *  - dev:      <repoRoot>/build-resources/obs/obs-portable (null if absent)
 */
export function obsSourceDir(): string | null {
  if (isPackaged()) {
    return path.join(process.resourcesPath, 'obs', 'obs-portable');
  }
  // Dev mode: app.getAppPath() points at app/; the repo root is one level up.
  const devPath = path.resolve(app.getAppPath(), '..', 'build-resources', 'obs', 'obs-portable');
  return fs.existsSync(devPath) ? devPath : null;
}

/**
 * OBS version string read from the `.obs-<version>.ok` marker filename (e.g.
 * "32.1.2"). Used to detect when a bundled OBS upgrade needs re-materializing.
 *  - packaged: <resourcesPath>/obs/.obs-<version>.ok
 *  - dev:      <repoRoot>/build-resources/obs/.obs-<version>.ok
 * Returns "0" when no marker is present.
 */
export function obsVersion(): string {
  const dir = isPackaged()
    ? path.join(process.resourcesPath, 'obs')
    : path.resolve(app.getAppPath(), '..', 'build-resources', 'obs');

  return readObsVersionMarker(dir);
}

/** Find the first `.obs-<version>.ok` marker in `dir` and return its version, else "0". */
function readObsVersionMarker(dir: string): string {
  if (!fs.existsSync(dir)) return '0';
  const marker = fs
    .readdirSync(dir)
    .filter((f) => f.startsWith('.obs-') && f.endsWith('.ok'))
    .sort()[0];
  if (!marker) return '0';
  return marker.replace(/^\.obs-/, '').replace(/\.ok$/, '');
}
