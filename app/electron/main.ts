// Electron main process: single-instance lock, JVM supervisor lifecycle, and the
// browser window. The window only opens after the core reports healthy.
//
// TODO(plan Step 1+): surface a loud, actionable error window when the core fails
// to start or crashes mid-session ("core stopped - recordings paused") instead of
// the bare dialog used here.
import { app, BrowserWindow, dialog, ipcMain, Menu, nativeImage, shell, Tray } from 'electron';
import { randomBytes } from 'node:crypto';
import * as fs from 'node:fs';
import * as path from 'node:path';
import { JvmSupervisor } from './jvm-supervisor';
import { ObsSupervisor } from './obs-supervisor';
import { SupervisionController } from './supervision';
import { revealablePath } from './reveal-path-guard';
import {
  applyLaunchAtLogin,
  getLaunchAtLogin,
  HIDDEN_LAUNCH_ARG,
  setLaunchAtLogin,
} from './app-prefs';
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

let obsSupervisor: ObsSupervisor | null = null;
let mainWindow: BrowserWindow | null = null;
let tray: Tray | null = null;
let shuttingDown = false;
// True once a REAL quit is underway (tray Quit / menu Quit / before-quit), so the
// window 'close' handler knows to actually close instead of hiding to the tray.
let isQuitting = false;
// One-time "we're still in the tray" hint, shown the first time the window is hidden so
// closing the window doesn't look like the app vanished.
let trayHintShown = false;
// Auto-start launches (login item) carry --hidden so the app boots straight to the tray.
const startHidden = process.argv.includes(HIDDEN_LAUNCH_ARG);

// Crash-supervision policy — bounded restart, re-entrancy / launch guards, OBS teardown on a core
// crash — lives in SupervisionController so it can be unit-tested without Electron. Here we just wire
// the real side effects (start/stop the supervisors, relaunch OBS, tray notice, log).
const supervision = new SupervisionController({
  startCore: () => supervisor.start(),
  stopCore: () => supervisor.stop(),
  startObs: startObsSupervisor,
  stopObs: stopObsSupervisor,
  notifyDown: notifyRecorderDown,
  log: logLine,
  isShuttingDown: () => shuttingDown || isQuitting,
});

const supervisor = new JvmSupervisor({
  bridgeToken,
  onLog: (line) => logLine(`[core] ${line}`),
  // A core crash while tray-hidden would otherwise silently stop all recording (and leave OBS running
  // unmanaged). The controller stops the orphaned OBS, then bounded-restarts the core.
  onUnexpectedExit: (info) => void supervision.handleCoreCrash(info),
});

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
    // Mark a real quit so the window 'close' handler stops hiding to the tray, then
    // defer the actual exit until the JVM/OBS are stopped so we never orphan them.
    isQuitting = true;
    event.preventDefault();
    void shutdown();
  });

  app.on('window-all-closed', () => {
    // Do NOT quit here: closing the window hides it to the tray, where the app keeps
    // auto-recording. The only real exit is the tray menu (or File -> Quit), which
    // sets isQuitting and goes through before-quit -> shutdown.
  });
}

async function bootstrap(): Promise<void> {
  fs.mkdirSync(logDir(), { recursive: true });
  logLine('app starting; launching core');
  registerPrefsIpc();
  // Reconcile the OS login item with the stored pref on every launch (packaged only),
  // so a pref set in a prior session still applies if it ever drifts.
  applyLaunchAtLogin();
  await supervisor.start();
  logLine('core healthy; opening window');
  createWindow();
  createTray();
  if (startHidden) logLine('started hidden in tray (launched at login)');
  // Launch OBS in the background so a slow or failed OBS never blocks the UI; the
  // status card reflects OBS connectivity from /status as the core connects to it.
  void supervision.launchObs();
}

/** Renderer-driven get/set for the app-level prefs (currently launch-at-login). */
function registerPrefsIpc(): void {
  ipcMain.removeHandler('prefs:getLaunchAtLogin');
  ipcMain.handle('prefs:getLaunchAtLogin', () => getLaunchAtLogin());
  ipcMain.removeHandler('prefs:setLaunchAtLogin');
  ipcMain.handle('prefs:setLaunchAtLogin', (_event, value: unknown) =>
    setLaunchAtLogin(value === true),
  );
  // Reveal a recording in Explorer (right-click "Reveal in folder"): selects the file in its folder.
  // shell.showItemInFolder only opens the OS file manager — it never reads, writes, or executes the
  // target — but the path is renderer-supplied (ultimately a DB video_path), so revealablePath()
  // gates it (non-blank, absolute, no `..`) rather than trusting it blindly. See reveal-path-guard.ts.
  ipcMain.removeHandler('shell:revealPath');
  ipcMain.handle('shell:revealPath', (_event, p: unknown) => {
    const target = revealablePath(p);
    // Also require the file to still exist: a retention-swept row can keep its path in the UI until
    // the next reload, and revealing a missing file just opens an empty folder — so no-op instead.
    if (target !== null && fs.existsSync(target)) shell.showItemInFolder(target);
  });
}

/**
 * System-tray icon + menu. The app lives in the tray so closing the window keeps it
 * recording in the background; the tray is the way back to the window and the only real
 * way to quit. Built once, after the window exists.
 */
function createTray(): void {
  if (tray) return;
  tray = new Tray(createTrayIcon());
  tray.setToolTip('Dota 2 Recorder');
  const menu = Menu.buildFromTemplate([
    { label: 'Show Dota 2 Recorder', click: showWindow },
    { type: 'separator' },
    { label: 'Quit Dota 2 Recorder', click: quitApp },
  ]);
  tray.setContextMenu(menu);
  // Left-click (and double-click) the tray icon to bring the window back.
  tray.on('click', showWindow);
  tray.on('double-click', showWindow);
}

/**
 * The tray/app icon: a brand-red diamond drawn straight into a raw RGBA bitmap, so
 * there's no .ico/.png asset to ship or resolve (works identically in dev and packaged).
 * Diamond = points where the Manhattan distance to the center is within the radius.
 */
function createTrayIcon(): Electron.NativeImage {
  const size = 16;
  // --accent #e23c2e
  const r = 0xe2;
  const g = 0x3c;
  const b = 0x2e;
  const center = (size - 1) / 2;
  const radius = size / 2 - 0.5;
  const buf = Buffer.alloc(size * size * 4);
  for (let y = 0; y < size; y++) {
    for (let x = 0; x < size; x++) {
      const inside = Math.abs(x - center) + Math.abs(y - center) <= radius;
      const i = (y * size + x) * 4;
      // createFromBitmap takes raw pixels in BGRA order. Binary alpha (0/255) so
      // premultiplication is moot.
      buf[i] = inside ? b : 0;
      buf[i + 1] = inside ? g : 0;
      buf[i + 2] = inside ? r : 0;
      buf[i + 3] = inside ? 0xff : 0;
    }
  }
  return nativeImage.createFromBitmap(buf, { width: size, height: size });
}

/** Bring the window back from the tray (re-creating it if it was destroyed). */
function showWindow(): void {
  if (!mainWindow) {
    createWindow();
    return;
  }
  if (mainWindow.isMinimized()) mainWindow.restore();
  mainWindow.show();
  mainWindow.focus();
}

/** Begin a real quit: flag it so the close handler doesn't intercept, then tear down. */
function quitApp(): void {
  isQuitting = true;
  app.quit();
}

/** One-time Windows tray balloon so the first window-close doesn't look like a crash. */
function showTrayHint(): void {
  if (trayHintShown || !tray) return;
  trayHintShown = true;
  try {
    tray.displayBalloon({
      icon: createTrayIcon(),
      title: 'Still recording',
      content:
        'Dota 2 Recorder is running in the tray and will keep recording your matches. ' +
        'Right-click the tray icon to quit.',
    });
  } catch {
    /* balloons are best-effort cosmetic; never let one break window close. */
  }
}

/**
 * Launch + adopt a fresh OBS supervisor once the core's config bootstrap has finished: poll the core
 * for OBS launch args, spawn OBS, and wait for the core to report it connected. Detached from window
 * startup; failure is non-fatal — the status card surfaces "recorder not ready". The
 * {@link SupervisionController} serializes calls to this (see its launchObs).
 */
async function startObsSupervisor(): Promise<void> {
  try {
    // The core generates the OBS port/password and writes its config during its
    // bootstrap; GET /obs/launch-args returns 409 until that completes, so poll.
    const launchArgs = await pollLaunchArgs();
    if (shuttingDown) return;
    obsSupervisor = new ObsSupervisor({
      obsDir: launchArgs.obsDir,
      port: launchArgs.port,
      password: launchArgs.password,
      collection: launchArgs.collection,
      profile: launchArgs.profile,
      scene: launchArgs.scene,
      bridgeToken,
      onLog: (line) => logLine(`[obs] ${line}`),
      onUnexpectedExit: (info) => supervision.handleObsCrash(info),
    });
    await obsSupervisor.start();
    logLine('OBS connected');
  } catch (err) {
    logLine(`OBS startup failed: ${err instanceof Error ? err.message : String(err)}`);
  }
}

/** Stop + clear the current OBS supervisor (no-op if none); the controller calls this to reap the
 * orphaned OBS on a core crash. */
async function stopObsSupervisor(): Promise<void> {
  if (obsSupervisor) {
    try {
      await obsSupervisor.stop();
    } catch {
      /* best-effort. */
    }
    obsSupervisor = null;
  }
}

/**
 * Poll GET /obs/launch-args until the core's config bootstrap completes (200).
 * Returns the OBS dir + websocket port/password the core generated. Throws if the
 * core never becomes ready within the retry budget.
 */
async function pollLaunchArgs(maxRetries = 30): Promise<{
  obsDir: string;
  port: number;
  password: string;
  collection: string;
  profile: string;
  scene: string;
}> {
  for (let i = 0; i < maxRetries; i++) {
    if (shuttingDown) break;
    try {
      const res = await fetch(`${BRIDGE_BASE}/obs/launch-args`, {
        signal: AbortSignal.timeout(1_500),
        headers: { [BRIDGE_TOKEN_HEADER]: bridgeToken },
      });
      if (res.ok) {
        const a = (await res.json()) as {
          obsDir?: string;
          port?: number;
          password?: string;
          collection?: string;
          profile?: string;
          scene?: string;
        };
        // 200 only once config is fully written; still guard against a blank password
        // so we never launch OBS with auth effectively disabled. The collection/profile/scene
        // names come from the core (its single source of truth), not re-hardcoded here.
        if (
          a.obsDir &&
          typeof a.port === 'number' &&
          a.password &&
          a.collection &&
          a.profile &&
          a.scene
        ) {
          return {
            obsDir: a.obsDir,
            port: a.port,
            password: a.password,
            collection: a.collection,
            profile: a.profile,
            scene: a.scene,
          };
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

  // Stay hidden when auto-started at login (the app sits in the tray); otherwise show.
  mainWindow.once('ready-to-show', () => {
    if (!startHidden) mainWindow?.show();
  });
  // Closing the window hides it to the tray (keeps recording) unless a real quit is
  // underway. The first hide shows a one-time tray hint so the app doesn't seem to vanish.
  mainWindow.on('close', (event) => {
    if (!isQuitting) {
      event.preventDefault();
      mainWindow?.hide();
      showTrayHint();
    }
  });
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

/** Surface a recorder-down condition to a possibly tray-hidden user (balloon) and the log. */
function notifyRecorderDown(message: string): void {
  logLine(`[core] ${message}`);
  try {
    tray?.displayBalloon({ icon: createTrayIcon(), title: 'Recorder stopped', content: message });
  } catch {
    /* balloons are best-effort cosmetic. */
  }
}
