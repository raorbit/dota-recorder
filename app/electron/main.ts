// Electron main process: single-instance lock, JVM supervisor lifecycle, and the
// browser window. The window only opens after the core reports healthy.
//
// TODO(plan Step 1+): surface a loud, actionable error window when the core fails
// to start or crashes mid-session ("core stopped - recordings paused") instead of
// the bare dialog used here.
import { app, BrowserWindow, dialog, ipcMain } from 'electron';
import { randomBytes } from 'node:crypto';
import * as fs from 'node:fs';
import * as path from 'node:path';
import { JvmSupervisor } from './jvm-supervisor';
import { ObsSupervisor } from './obs-supervisor';
import {
  BRIDGE_BASE,
  BRIDGE_TOKEN_ARG_PREFIX,
  BRIDGE_TOKEN_HEADER,
  electronLogPath,
  logDir,
  packagedIndexHtml,
} from './paths';

const isDev = process.env.DOTAREC_DEV === '1' || !app.isPackaged;
const DEV_SERVER_URL = 'http://localhost:5173';

// Per-launch shared secret gating the bridge. Generated once here, handed to the core
// (env), the renderer (preload arg), and every fetch the main process makes to the
// bridge, so a web page in the user's browser can't read/mutate the loopback API.
const bridgeToken = randomBytes(32).toString('hex');

/** Append a line to electron.log (best-effort) and mirror to the console. */
function logLine(line: string): void {
  try {
    fs.appendFileSync(electronLogPath(), `${new Date().toISOString()} ${line}\n`);
  } catch {
    /* logging must never break the app. */
  }
  console.log(line);
}

const supervisor = new JvmSupervisor({ bridgeToken, onLog: (line) => logLine(`[core] ${line}`) });
let obsSupervisor: ObsSupervisor | null = null;
let mainWindow: BrowserWindow | null = null;
let shuttingDown = false;

if (!app.requestSingleInstanceLock()) {
  app.quit();
} else {
  app.on('second-instance', () => {
    if (mainWindow) {
      if (mainWindow.isMinimized()) mainWindow.restore();
      mainWindow.focus();
    }
  });

  app.whenReady().then(bootstrap).catch(fatal);

  app.on('before-quit', (event) => {
    // Defer quit until the JVM is stopped so we never orphan the core.
    event.preventDefault();
    void shutdown();
  });

  app.on('window-all-closed', () => {
    // On Windows the app quits when the window closes; before-quit handles teardown.
    app.quit();
  });
}

async function bootstrap(): Promise<void> {
  fs.mkdirSync(logDir(), { recursive: true });
  logLine('app starting; launching core');
  await supervisor.start();
  logLine('core healthy; opening window');
  createWindow();
  // Launch OBS in the background so a slow or failed OBS never blocks the UI; the
  // status card reflects OBS connectivity from /status as the core connects to it.
  void launchObs();
}

/**
 * Spawn + supervise OBS once the core's config bootstrap has finished. Detached from
 * window startup; failure is non-fatal — the status card surfaces "recorder not ready".
 */
async function launchObs(): Promise<void> {
  try {
    // The core generates the OBS port/password and writes its config during its
    // bootstrap; GET /obs/launch-args returns 409 until that completes, so poll.
    const launchArgs = await pollLaunchArgs();
    if (shuttingDown) return;
    obsSupervisor = new ObsSupervisor({
      obsDir: launchArgs.obsDir,
      port: launchArgs.port,
      password: launchArgs.password,
      bridgeToken,
      onLog: (line) => logLine(`[obs] ${line}`),
    });
    await obsSupervisor.start();
    logLine('OBS connected');
  } catch (err) {
    logLine(`OBS startup failed: ${err instanceof Error ? err.message : String(err)}`);
  }
}

/**
 * Poll GET /obs/launch-args until the core's config bootstrap completes (200).
 * Returns the OBS dir + websocket port/password the core generated. Throws if the
 * core never becomes ready within the retry budget.
 */
async function pollLaunchArgs(
  maxRetries = 30,
): Promise<{ obsDir: string; port: number; password: string }> {
  for (let i = 0; i < maxRetries; i++) {
    if (shuttingDown) break;
    try {
      const res = await fetch(`${BRIDGE_BASE}/obs/launch-args`, {
        signal: AbortSignal.timeout(1_500),
        headers: { [BRIDGE_TOKEN_HEADER]: bridgeToken },
      });
      if (res.ok) {
        const a = (await res.json()) as { obsDir?: string; port?: number; password?: string };
        // 200 only once config is fully written; still guard against a blank password
        // so we never launch OBS with auth effectively disabled.
        if (a.obsDir && typeof a.port === 'number' && a.password) {
          return { obsDir: a.obsDir, port: a.port, password: a.password };
        }
      }
      // 409 (not ready) or an incomplete body — fall through and retry.
    } catch {
      /* core not reachable yet; retry. */
    }
    await new Promise((r) => setTimeout(r, 500));
  }
  throw new Error('Core did not provide OBS launch args within timeout');
}

function createWindow(): void {
  mainWindow = new BrowserWindow({
    width: 1280,
    height: 820,
    minWidth: 1024,
    minHeight: 640,
    backgroundColor: '#0e0f12',
    show: false,
    webPreferences: {
      preload: path.join(__dirname, 'preload.js'),
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: true,
      // Hand the per-launch bridge token to the (sandboxed) preload via process.argv.
      additionalArguments: [`${BRIDGE_TOKEN_ARG_PREFIX}${bridgeToken}`],
    },
  });

  mainWindow.once('ready-to-show', () => mainWindow?.show());
  mainWindow.on('closed', () => {
    mainWindow = null;
  });

  // Native folder picker for the recording output-folder Browse button. Renderer-driven over
  // IPC because the renderer is sandboxed and can't open dialogs itself. Registered with handle()
  // (idempotent re-register on window re-create) and parented to the window so it's modal.
  ipcMain.removeHandler('dialog:selectFolder');
  ipcMain.handle('dialog:selectFolder', async (): Promise<string | null> => {
    if (!mainWindow) return null;
    const result = await dialog.showOpenDialog(mainWindow, {
      title: 'Choose recording folder',
      properties: ['openDirectory', 'createDirectory'],
    });
    return result.canceled || result.filePaths.length === 0 ? null : result.filePaths[0];
  });

  // Lock the window to its own bundled content: deny popups and block any navigation away from the
  // expected origin, so an injected/accidental external page can never inherit the bridge token.
  mainWindow.webContents.setWindowOpenHandler(() => ({ action: 'deny' }));
  mainWindow.webContents.on('will-navigate', (event, url) => {
    const allowedPrefix = isDev ? DEV_SERVER_URL : 'file://';
    if (!url.startsWith(allowedPrefix)) {
      event.preventDefault();
      logLine(`blocked navigation to ${url}`);
    }
  });

  if (isDev) {
    void mainWindow.loadURL(DEV_SERVER_URL);
  } else {
    void mainWindow.loadFile(packagedIndexHtml());
  }
}

async function shutdown(): Promise<void> {
  if (shuttingDown) return;
  shuttingDown = true;
  try {
    // Stop OBS first so the core can observe a clean disconnect, then the core.
    if (obsSupervisor) {
      await obsSupervisor.stop();
    }
    await supervisor.stop();
  } catch {
    /* best-effort - we are exiting regardless. */
  } finally {
    app.exit(0);
  }
}

function fatal(err: unknown): void {
  const message = err instanceof Error ? err.message : String(err);
  logLine(`fatal: ${message}`);
  dialog.showErrorBox('Dota 2 Recorder', `Failed to start the recorder core.\n\n${message}`);
  void supervisor.stop().finally(() => app.exit(1));
}
