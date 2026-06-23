// Preload: exposes only the loopback bridge endpoints to the renderer over a
// locked-down contextBridge. No Node APIs are leaked into the renderer.
import { contextBridge } from 'electron';
import { BRIDGE_BASE, BRIDGE_TOKEN_ARG_PREFIX, HEALTH_URL, WS_URL } from './paths';
import type { DotaRecBridge } from './bridge-contract';

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
};

contextBridge.exposeInMainWorld('dotarec', bridge);
