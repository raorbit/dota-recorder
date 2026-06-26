// GSI replay harness — smoke-test the heartbeat + FSM (arm → record → tag → finalize)
// WITHOUT launching Dota, by POSTing synthetic Game State Integration frames to the
// core's ingest endpoint exactly as Dota would.
//
// The core must be running first:  npm run dev   (or  java -jar core/build/libs/core.jar)
//
// Usage:
//   node scripts/gsi-replay.mjs                         # heartbeat scenario (default) — safe, no OBS needed
//   node scripts/gsi-replay.mjs --scenario=match        # full match: arm → record → 2 kills + 1 death → finalize (needs OBS up)
//   node scripts/gsi-replay.mjs --scenario=match --match-id=7891234567
//   node scripts/gsi-replay.mjs --hz=10 --host=127.0.0.1 --port=3223
//   node scripts/gsi-replay.mjs --token=abc123          # send auth.token=abc123 (override)
//   node scripts/gsi-replay.mjs --no-token              # force NO token even if settings.json has one
//
// Auth: by default the token is auto-read from %APPDATA%/dota-recorder/settings.json
// (gsiAuthToken). A blank/absent token is fine — the core accepts unauthenticated frames
// when no token is configured (migration safety). If you've run GSI setup, the configured
// token is picked up automatically so frames aren't dropped.
//
// Scenarios:
//   heartbeat  ~15 idle 'menu' frames in a non-arming state. Flips /status gsi.connected
//              true and proves the ingest+auth path. The FSM no-ops, so NO recording — this
//              works even with OBS closed.
//   match      A full lifecycle that drives the recording brain: HERO_SELECTION (arm +
//              StartRecord) → PRE_GAME → GAME_IN_PROGRESS with 2 kills and 1 death/respawn
//              → POST_GAME (stop + write match row + markers + thumbnail). Needs OBS running
//              (bundled OBS up via the app); without it the core surfaces a loud OBS error,
//              which is itself a useful negative test.

import { readFileSync } from 'node:fs';
import * as path from 'node:path';

// ── args ──────────────────────────────────────────────────────────────────────
const args = Object.fromEntries(
  process.argv.slice(2).map((a) => {
    const m = a.match(/^--([^=]+)(?:=(.*))?$/);
    return m ? [m[1], m[2] ?? true] : [a, true];
  }),
);
const HOST = args.host ?? '127.0.0.1';
const PORT = Number(args.port ?? 3223);
const HZ = Math.max(1, Number(args.hz ?? 10));
const SCENARIO = String(args.scenario ?? 'heartbeat');
const MATCH_ID = String(args['match-id'] ?? '0');
const URL = `http://${HOST}:${PORT}/gsi`;

if (typeof fetch !== 'function') {
  console.error('This script needs Node 18+ (global fetch). Your Node is too old.');
  process.exit(1);
}

// ── auth token: explicit --token / --no-token, else auto-read from settings.json ─
function resolveToken() {
  if (args['no-token']) return '';
  if (typeof args.token === 'string') return args.token;
  try {
    const appData = process.env.APPDATA || path.join(process.env.USERPROFILE || '.', 'AppData', 'Roaming');
    const file = path.join(appData, 'dota-recorder', 'settings.json');
    const json = JSON.parse(readFileSync(file, 'utf8'));
    if (json && typeof json.gsiAuthToken === 'string' && json.gsiAuthToken.trim()) {
      console.log('Using gsiAuthToken from settings.json');
      return json.gsiAuthToken.trim();
    }
  } catch {
    /* no settings.json / blank token → send none; core accepts when unconfigured */
  }
  return '';
}
const TOKEN = resolveToken();

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

// ── frame builder (shaped like a real Dota Hero-Demo payload) ───────────────────
let providerTs = 1781827370; // provider.timestamp seconds; advance per frame (no Date.now — keep deterministic-ish)
function frame({ gameState, activity = 'playing', kills = 0, deaths = 0, assists = 0, alive = true, paused = false, clock = 0 }) {
  providerTs += 1;
  const body = {
    provider: { name: 'Dota 2', appid: 570, version: 48, timestamp: providerTs },
    map: {
      name: 'start',
      matchid: MATCH_ID,
      game_time: Math.max(0, clock + 90),
      clock_time: clock,
      daytime: true,
      radiant_score: kills,
      dire_score: deaths,
      game_state: gameState,
      paused,
      win_team: 'none',
    },
    player: {
      steamid: '76561190000000000',
      accountid: '123456789',
      name: 'replay-bot',
      activity,
      kills,
      deaths,
      assists,
      last_hits: 10 + kills * 3,
      denies: 0,
      gpm: 500,
      xpm: 540,
      net_worth: 2000 + kills * 300,
      player_slot: 0,
      team_name: 'radiant',
    },
    hero: {
      id: 6,
      name: 'npc_dota_hero_drow_ranger',
      level: 1 + Math.floor(clock / 60),
      alive,
      respawn_seconds: alive ? 0 : 5,
      health: alive ? 1000 : 0,
      max_health: 1000,
      health_percent: alive ? 100 : 0,
    },
  };
  if (TOKEN) body.auth = { token: TOKEN };
  return body;
}

async function send(label, payload) {
  try {
    const res = await fetch(URL, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload),
    });
    const p = payload.player;
    console.log(
      `→ ${label.padEnd(22)} state=${payload.map.game_state.replace('DOTA_GAMERULES_STATE_', '').padEnd(16)} ` +
        `k/d/a=${p.kills}/${p.deaths}/${p.assists} alive=${payload.hero.alive} → HTTP ${res.status}`,
    );
  } catch (e) {
    console.error(`✗ POST failed (${URL}): ${e.message}\n  Is the core running? (npm run dev)`);
    process.exit(1);
  }
}

async function repeat(label, n, opts) {
  for (let i = 0; i < n; i++) {
    await send(label, frame(opts));
    await sleep(1000 / HZ);
  }
}

// ── scenarios ───────────────────────────────────────────────────────────────────
async function heartbeat() {
  console.log(`\nHEARTBEAT scenario → ${URL} @ ${HZ}Hz${TOKEN ? ' (authenticated)' : ' (no token)'}`);
  console.log('Idle menu frames — the FSM no-ops, so NO recording. Proves ingest + auth + heartbeat.\n');
  // Non-arming state + activity 'menu' → never arms (isArmState=false, not GAME_IN_PROGRESS+playing).
  await repeat('idle', 15, { gameState: 'DOTA_GAMERULES_STATE_INIT', activity: 'menu' });
  console.log('\nDone. Check: GET http://%s:%d/status → gsi.connected should be true', HOST, 3224);
  console.log('(give it a few seconds; the heartbeat goes stale after the configured GSI-silence grace).');
}

async function match() {
  console.log(`\nMATCH scenario → ${URL} @ ${HZ}Hz${TOKEN ? ' (authenticated)' : ' (no token)'}`);
  console.log('Drives the full recording brain. Needs OBS running (via the app) or you get a loud OBS error.\n');

  // 1) Draft → arm + StartRecord early (so OBS warms up before the action).
  await repeat('hero-selection', 10, { gameState: 'DOTA_GAMERULES_STATE_HERO_SELECTION', clock: -75 });
  await repeat('pre-game', 6, { gameState: 'DOTA_GAMERULES_STATE_PRE_GAME', clock: -15 });

  // 2) Game in progress — accumulate clock; tag 2 kills and 1 death.
  const GIP = 'DOTA_GAMERULES_STATE_GAME_IN_PROGRESS';
  await repeat('playing', 20, { gameState: GIP, clock: 30 });            // settle
  await repeat('first-kill', 6, { gameState: GIP, kills: 1, clock: 120 }); // kills 0→1 → kill marker
  await repeat('second-kill', 6, { gameState: GIP, kills: 2, clock: 210 });// kills 1→2 → kill marker
  // death: alive true → false (falling edge → death marker), deaths 0→1, then respawn.
  await repeat('alive', 4, { gameState: GIP, kills: 2, deaths: 0, alive: true, clock: 300 });
  await repeat('DEATH', 4, { gameState: GIP, kills: 2, deaths: 1, alive: false, clock: 320 });
  await repeat('respawn', 6, { gameState: GIP, kills: 2, deaths: 1, alive: true, clock: 340 });

  // 3) POST_GAME → stop + finalize (match row + markers + thumbnail written).
  await repeat('post-game', 4, { gameState: 'DOTA_GAMERULES_STATE_POST_GAME', kills: 2, deaths: 1, clock: 420 });

  console.log('\nDone. Expect in core.log:');
  console.log('  "Recording started (surrogate N), anchor=..."  then  "OBS recording confirmed started (OUTPUT_STARTED)"');
  console.log('Then a finalized match row + ~2 kill markers + ~1 death marker.');
  console.log('Verify with plans/validation-queries.sql, or open the match in the browse UI and click a marker.');
}

const scenarios = { heartbeat, match };
const run = scenarios[SCENARIO];
if (!run) {
  console.error(`Unknown --scenario=${SCENARIO}. Use: ${Object.keys(scenarios).join(' | ')}`);
  process.exit(1);
}
await run();
