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
