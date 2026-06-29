// Preload: exposes only the loopback bridge endpoints to the renderer over a
// locked-down contextBridge. No Node APIs are leaked into the renderer.
//
// This runs under `sandbox: true`, where a preload's `require` is a polyfill that
// resolves ONLY the `electron` module and a few Node builtins -- NOT local files.
// Importing from './paths' compiles to `require('./paths')`, which throws in the
// sandbox and silently aborts the preload before exposeInMainWorld runs, so
// `window.dotarec` is never defined: the renderer then sends no bridge token and
// every core call 401s ("could not load settings"). This file therefore MUST stay
// self-contained -- the constants below mirror the canonical copies in paths.ts
// (the loopback host/port and token arg prefix are frozen by the runtime contract),
// and DotaRecBridge is a type-only import (elided at compile time, no require).
import { contextBridge, ipcRenderer } from 'electron';
import type { DotaRecBridge } from './bridge-contract';

const BRIDGE_HOST = '127.0.0.1';
const BRIDGE_PORT = 3224;
const BRIDGE_BASE = `http://${BRIDGE_HOST}:${BRIDGE_PORT}`;
const HEALTH_URL = `${BRIDGE_BASE}/health`;
const WS_URL = `ws://${BRIDGE_HOST}:${BRIDGE_PORT}/ws`;
const BRIDGE_TOKEN_ARG_PREFIX = '--dotarec-bridge-token=';

// The main process passes the per-launch bridge token via the window's
// additionalArguments; a sandboxed preload reads it from process.argv (no Node
// APIs required). Empty when launched outside Electron, where auth is disabled.
const tokenArg = process.argv.find((arg) => arg.startsWith(BRIDGE_TOKEN_ARG_PREFIX));
const bridgeToken = tokenArg ? tokenArg.slice(BRIDGE_TOKEN_ARG_PREFIX.length) : '';

const bridge: DotaRecBridge = {
  bridgeBase: BRIDGE_BASE,
  healthUrl: HEALTH_URL,
  wsUrl: WS_URL,
  bridgeToken,
  selectFolder: () => ipcRenderer.invoke('dialog:selectFolder') as Promise<string | null>,
  getLaunchAtLogin: () => ipcRenderer.invoke('prefs:getLaunchAtLogin') as Promise<boolean>,
  setLaunchAtLogin: (value: boolean) =>
    ipcRenderer.invoke('prefs:setLaunchAtLogin', value) as Promise<boolean>,
  revealPath: (path: string) => ipcRenderer.invoke('shell:revealPath', path) as Promise<void>,
};

contextBridge.exposeInMainWorld('dotarec', bridge);
