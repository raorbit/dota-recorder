// Electron main process: single-instance lock, JVM supervisor lifecycle, and the
// browser window. The window only opens after the core reports healthy.
//
// TODO(plan Step 1+): surface a loud, actionable error window when the core fails
// to start or crashes mid-session ("core stopped - recordings paused") instead of
// the bare dialog used here.
import { app, BrowserWindow, dialog } from 'electron';
import * as fs from 'node:fs';
import * as path from 'node:path';
import { JvmSupervisor } from './jvm-supervisor';
import { electronLogPath, logDir, packagedIndexHtml } from './paths';

const isDev = process.env.DOTAREC_DEV === '1' || !app.isPackaged;
const DEV_SERVER_URL = 'http://localhost:5173';

/** Append a line to electron.log (best-effort) and mirror to the console. */
function logLine(line: string): void {
  try {
    fs.appendFileSync(electronLogPath(), `${new Date().toISOString()} ${line}\n`);
  } catch {
    /* logging must never break the app. */
  }
  console.log(line);
}

const supervisor = new JvmSupervisor({ onLog: (line) => logLine(`[core] ${line}`) });
let mainWindow: BrowserWindow | null = null;

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
    },
  });

  mainWindow.once('ready-to-show', () => mainWindow?.show());
  mainWindow.on('closed', () => {
    mainWindow = null;
  });

  if (isDev) {
    void mainWindow.loadURL(DEV_SERVER_URL);
  } else {
    void mainWindow.loadFile(packagedIndexHtml());
  }
}

let shuttingDown = false;
async function shutdown(): Promise<void> {
  if (shuttingDown) return;
  shuttingDown = true;
  try {
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
