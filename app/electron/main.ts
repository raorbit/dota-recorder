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
import { pathToFileURL } from 'node:url';
import { JvmSupervisor } from './jvm-supervisor';
import { isAllowedNavigation, type AllowedNavigation } from './navigation-guard';
import { ObsSupervisor } from './obs-supervisor';
import { SupervisionController } from './supervision';
import { pathIsAccessible, revealablePath } from './reveal-path-guard';
import {
  applyLaunchAtLogin,
  getAutoUpdate,
  getLaunchAtLogin,
  HIDDEN_LAUNCH_ARG,
  setAutoUpdate,
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
import { UpdateController } from './update-controller';
import { initUpdater, releaseNotesUrl, type UpdaterHandle } from './updater';
import type { UpdateState } from './bridge-contract';

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
// Set once the supervisors have been torn down for an in-progress quitAndInstall, so the
// before-quit handler lets Electron actually quit (rather than preventDefault + re-teardown)
// and the pending NSIS installer — already spawned, waiting for our process to exit — can
// replace the files. Without this, quitAndInstall's internal app.quit() deadlocks against
// before-quit's preventDefault (shutdown() early-returns on shuttingDown, so nothing ever
// calls app.exit()).
let supervisorsStopped = false;
// True once a REAL quit is underway (tray Quit / menu Quit / before-quit), so the
// window 'close' handler knows to actually close instead of hiding to the tray.
let isQuitting = false;
// Auto-update policy + electron-updater handle, wired in initAutoUpdate() (packaged only).
// null in dev / before init. latestUpdateState is the last pushed snapshot, served to the
// renderer's one-shot updates:getState poll.
let updateController: UpdateController | null = null;
let updaterHandle: UpdaterHandle | null = null;
let latestUpdateState: UpdateState = { status: 'idle' };
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
    if (supervisorsStopped) {
      // An update install is underway: installUpdateNow() already tore the supervisors down
      // and called quitAndInstall, whose internal app.quit() lands here. Let the quit proceed
      // so the pending installer can replace the files — do NOT preventDefault (that would
      // deadlock the install).
      return;
    }
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
  // electron-updater only works in a packaged build (needs app-update.yml); in dev the
  // whole update stack stays dormant and the renderer's update UI degrades to "unavailable".
  if (!isDev) initAutoUpdate();
}

/**
 * Wire the electron-updater adapter to the (testable) UpdateController and arm the check
 * cadence. Packaged builds only. The controller decides WHEN to download/install (gated on
 * "not recording" via the core's /status); the adapter performs the electron-updater calls.
 */
function initAutoUpdate(): void {
  updaterHandle = initUpdater(
    {
      onChecking: () => updateController?.onCheckingForUpdate(),
      onAvailable: (version, notesUrl) => updateController?.onUpdateAvailable(version, notesUrl),
      onNotAvailable: () => updateController?.onUpdateNotAvailable(),
      onProgress: (percent) => updateController?.onDownloadProgress(percent),
      onDownloaded: (version) => updateController?.onUpdateDownloaded(version),
      onError: (message) => updateController?.onError(message),
    },
    logLine,
  );
  updateController = new UpdateController({
    triggerCheck: () => updaterHandle?.check(),
    triggerDownload: () => updaterHandle?.download(),
    isBusy: isRecordingBusy,
    doInstall: () => void installUpdateNow(),
    emit: (state) => {
      latestUpdateState = state;
      mainWindow?.webContents.send('updates:state', state);
    },
    log: logLine,
    isEnabled: () => getAutoUpdate(),
    schedule: (fn, ms) => {
      const timer = setTimeout(fn, ms);
      return () => clearTimeout(timer);
    },
  });
  updateController.start();
}

/** Renderer-driven get/set for the app-level prefs (currently launch-at-login). */
function registerPrefsIpc(): void {
  ipcMain.removeHandler('prefs:getLaunchAtLogin');
  ipcMain.handle('prefs:getLaunchAtLogin', () => getLaunchAtLogin());
  ipcMain.removeHandler('prefs:setLaunchAtLogin');
  ipcMain.handle('prefs:setLaunchAtLogin', (_event, value: unknown) =>
    setLaunchAtLogin(value === true),
  );
  // Auto-update pref + lifecycle. getAutoUpdate/setAutoUpdate own the background-download
  // gate; enabling it kicks a check so an already-available update starts downloading.
  ipcMain.removeHandler('prefs:getAutoUpdate');
  ipcMain.handle('prefs:getAutoUpdate', () => getAutoUpdate());
  ipcMain.removeHandler('prefs:setAutoUpdate');
  ipcMain.handle('prefs:setAutoUpdate', (_event, value: unknown) => {
    const applied = setAutoUpdate(value === true);
    if (applied) updateController?.check();
    return applied;
  });
  ipcMain.removeHandler('updates:getState');
  ipcMain.handle('updates:getState', () => latestUpdateState);
  ipcMain.removeHandler('updates:check');
  ipcMain.handle('updates:check', () => updateController?.check() ?? latestUpdateState);
  ipcMain.removeHandler('updates:installNow');
  // Refusal while recording is surfaced via a pushed {recording:true} state, not the return.
  ipcMain.handle('updates:installNow', async () => {
    await updateController?.installNow();
  });
  // Release notes open in the OS browser; the main process builds the URL from the version so
  // the renderer can't point shell.openExternal at an arbitrary target.
  ipcMain.removeHandler('shell:openReleaseNotes');
  ipcMain.handle('shell:openReleaseNotes', async (_event, version: unknown) => {
    await shell.openExternal(releaseNotesUrl(typeof version === 'string' ? version : undefined));
  });
  // Reveal a recording in Explorer (right-click "Reveal in folder"): selects the file in its folder.
  // shell.showItemInFolder only opens the OS file manager — it never reads, writes, or executes the
  // target — but the path is renderer-supplied (ultimately a DB video_path), so revealablePath()
  // gates it (non-blank, absolute, no `..`) rather than trusting it blindly. See reveal-path-guard.ts.
  ipcMain.removeHandler('shell:revealPath');
  ipcMain.handle('shell:revealPath', async (_event, p: unknown) => {
    const target = revealablePath(p);
    if (target === null) return;
    // Also require the file to still exist: a retention-swept row can keep its path in the UI until
    // the next reload, and revealing a missing file just opens an empty folder — so no-op instead.
    // The check is async + timeout-bounded (never a synchronous fs.existsSync on a possibly-UNC path):
    // an offline network-share videoPath would otherwise block the main event loop for the full SMB
    // timeout, freezing the UI, tray, and crash-supervision. See pathIsAccessible in reveal-path-guard.
    if (await pathIsAccessible(target)) shell.showItemInFolder(target);
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
    // The core mints this password, so a core stack trace or settings dump could echo it into
    // electron.log; scrub it from the core's log stream too (OBS already scrubs its own copy).
    supervisor.addScrubSecret(launchArgs.password);
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
    // Frameless: the renderer draws its own title bar (WindowFrame) and drives
    // minimize/maximize/close over IPC. Resize borders stay native (thickFrame).
    frame: false,
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
  // Window controls for the frameless window's custom title bar. Close routes
  // through window.close() so the hide-to-tray close handler above still applies.
  ipcMain.removeHandler('window:minimize');
  ipcMain.handle('window:minimize', () => {
    mainWindow?.minimize();
  });
  ipcMain.removeHandler('window:maximizeToggle');
  ipcMain.handle('window:maximizeToggle', () => {
    if (!mainWindow) return;
    if (mainWindow.isMaximized()) mainWindow.unmaximize();
    else mainWindow.maximize();
  });
  ipcMain.removeHandler('window:close');
  ipcMain.handle('window:close', () => {
    mainWindow?.close();
  });

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
  // expected page, so an injected/accidental external page can never inherit the bridge token.
  // Packaged builds allow ONLY the bundled index.html — a bare `file://` prefix would admit any
  // local file page (policy + tests live in navigation-guard.ts).
  const allowedNav: AllowedNavigation = isDev
    ? { devServerUrl: DEV_SERVER_URL }
    : { packagedIndexUrl: pathToFileURL(packagedIndexHtml()).href };
  mainWindow.webContents.setWindowOpenHandler(() => ({ action: 'deny' }));
  mainWindow.webContents.on('will-navigate', (event, url) => {
    if (!isAllowedNavigation(url, allowedNav)) {
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

/**
 * Tear down OBS then the core (best-effort). Shared by the normal quit path ({@link shutdown})
 * and the update-install path ({@link installUpdateNow}) so both reap the children before the
 * process exits — neither may orphan obs64.exe / javaw.exe holding locks under the install dir
 * (which would make the NSIS update fail its atomic-rename and roll back).
 */
async function stopSupervisors(): Promise<void> {
  // Stop OBS first so the core can observe a clean disconnect, then the core.
  await stopObsSupervisor();
  try {
    await supervisor.stop();
  } catch {
    /* best-effort - we are exiting regardless. */
  }
}

async function shutdown(): Promise<void> {
  if (shuttingDown) return;
  shuttingDown = true;
  try {
    await stopSupervisors();
  } finally {
    app.exit(0);
  }
}

/**
 * The recording gate for auto-update: true when a match is recording, arming, or stopping, so an
 * update download/install never interrupts a match. Reads the core's GET /status (idle ⇔
 * !obs.recording && fsm.state === 'IDLE'; ARMED/RECORDING/STOPPING all count as busy — OBS may
 * already be rolling in ARMED). FAIL-SAFE TO BUSY: any timeout / non-200 / unparseable body
 * returns true, so an unreachable or 401ing core never green-lights an install.
 */
async function isRecordingBusy(): Promise<boolean> {
  try {
    const res = await fetch(`${BRIDGE_BASE}/status`, {
      signal: AbortSignal.timeout(1_500),
      headers: { [BRIDGE_TOKEN_HEADER]: bridgeToken },
    });
    if (!res.ok) return true;
    const body = (await res.json()) as {
      obs?: { recording?: boolean };
      fsm?: { state?: string };
    };
    const recording = body.obs?.recording === true;
    const idleFsm = body.fsm?.state === 'IDLE';
    return recording || !idleFsm;
  } catch {
    return true;
  }
}

/**
 * Install a downloaded update: tear the supervisors down ourselves, then quitAndInstall. The
 * recording gate was already checked by {@link UpdateController.installNow}. supervisorsStopped is
 * set BEFORE quitAndInstall so the before-quit handler (which quitAndInstall's internal app.quit()
 * triggers) lets the quit proceed rather than re-running shutdown() — otherwise the two deadlock.
 * shuttingDown is set too so crash supervision treats the teardown as intentional, not a crash.
 */
async function installUpdateNow(): Promise<void> {
  logLine('[updater] installing update: stopping supervisors then quitAndInstall');
  isQuitting = true;
  shuttingDown = true;
  supervisorsStopped = true;
  await stopSupervisors();
  updaterHandle?.quitAndInstall();
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
