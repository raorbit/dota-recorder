// Preload: exposes only the loopback bridge endpoints to the renderer over a
// locked-down contextBridge. No Node APIs are leaked into the renderer.
import { contextBridge } from 'electron';
import { BRIDGE_BASE, HEALTH_URL, WS_URL } from './paths';
import type { DotaRecBridge } from '../src/api/bridge';

const bridge: DotaRecBridge = {
  bridgeBase: BRIDGE_BASE,
  healthUrl: HEALTH_URL,
  wsUrl: WS_URL,
};

contextBridge.exposeInMainWorld('dotarec', bridge);
