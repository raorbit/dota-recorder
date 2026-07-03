# Architecture

This document explains the design decisions behind dota-recorder — the parts that aren't obvious
from the README's process diagram. It's a map, not a spec: each section names the classes that
implement it, and the class-level Javadoc carries the fine detail.

```
Renderer (React 19 / Vite / zustand)
   │  REST + WS over 127.0.0.1:3224 (per-launch bearer token)
Electron main  ── supervises ──►  JVM core (Spring Boot, Java 21)
   │  spawns + Job Object reaps       │
   └──────────────► OBS ◄─────────────┘  obs-websocket v5 on 127.0.0.1:4466
                    (bundled, auto-configured)
   Dota 2 ── GSI HTTP POST (token-authenticated) ──►  core :3223
```

## Why three runtimes

The stack is deliberately modeled on [Warcraft Recorder](https://github.com/aza547/wow-recorder)
(Electron + bundled OBS), adapted to Dota's data reality: there is no on-disk combat log, so the
only live feed is Game State Integration — an HTTP POST stream Dota pushes at ~10Hz. That makes an
embedded HTTP server the natural ingest surface, and the JVM core brings a mature obs-websocket v5
client, a real scheduler, and strong typing for the recording state machine. The cost is real — a
trimmed JRE and Spring cold-start shipped in the installer for a single loopback client — and it's
accepted, not free: v0.1 optimizes for correctness of the record path over footprint. Everything
binds to `127.0.0.1` only, which is both the correct local-only posture and what avoids the Windows
firewall prompt.

## Process lifecycle: three independent reaping layers

Orphaned processes are the classic failure mode of a supervisor app — a crashed Electron leaving a
headless OBS recording forever, or a zombie JVM squatting on the ports. Three non-overlapping
mechanisms cover it:

1. **Windows Job Object** (`app/electron/job-object.ts`): the JVM and OBS are assigned to a job
   with `JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE` via a direct kernel32 FFI (koffi), so the OS itself
   kills the tree when Electron dies — including on a hard crash where no cleanup code runs.
2. **`taskkill /T /F` fallback** (`app/electron/jvm-supervisor.ts`, `obs-supervisor.ts`): used on
   orderly shutdown and whenever koffi is unavailable, escalating from a polite SIGTERM.
3. **`ParentProcessWatchdog`** (core-side): the core polls its supervisor's pid and self-exits when
   it disappears — the backstop for the case where both Electron-side layers failed, which matters
   because a zombie core holds :3223/:3224 and would break the next launch.

The same defense-in-depth shape recurs at finalize time: `ForceStopWatchdog` force-finalizes a
recording after 30s of GSI silence (a demo or abandon never sends `POST_GAME`), keyed to the last
*authenticated* frame so spoofed posts can't keep a runaway recording alive.

## The recording brain and its anchor

`MatchFsm` (`fsm/`) interprets normalized GSI frames and owns the record path. The load-bearing
decision is the **recording anchor**: recording is not considered started when OBS is *asked* to
record, but when obs-websocket confirms `OUTPUT_STARTED`. The `System.nanoTime()` stamp captured at
that confirmation is the anchor every marker offset and the final duration are computed from. All
FSM entry points that mutate recording state are synchronized on the FSM, so the ~10Hz GSI thread,
the watchdog's scheduler thread, and the manual stop endpoint resolve to exactly one OBS stop and
one persisted row.

## Clock domains

Marker seek offsets must land on the right video frame years after the fact, so the clock rules are
strict (`tagger/VideoOffsetCalculator`, `gsi/GsiFrame`):

- **Monotonic (`System.nanoTime`)** — the only clock used for offset math and duration: offset =
  frame's monotonic stamp minus the `OUTPUT_STARTED` anchor. An OS/NTP wall-clock step mid-match
  cannot shift or clamp a marker, because both instants come from the same monotonic source.
- **Wall clock** — storage and display stamps only (played-at, journal rows, pause spans).
- **`game_clock`** — display label only, never offset math: it pauses, and its relation to video
  time drifts with pauses and the pre-game phase.

There is a dedicated test that steps the wall clock *backward* mid-recording and asserts offsets
and duration don't follow it (`MatchFsmTest`).

## Durability: journal, crash recovery, transactions

A recording that dies mid-match (crash, power cut) must not lose the VOD or its markers:

- **Recording journal** (`recording_session`/`recording_event` tables): the in-flight session is
  journaled from record start — markers and pause edges appended as they happen — and the journal
  row is deleted inside the same transaction that persists the finished match.
- **`CrashRecoveryRunner`** replays unfinished journal sessions at boot, adopts orphaned `.mp4`s,
  and reconciles residue from interrupted cross-drive archive moves. It refuses to touch anything
  it can't positively attribute: a file is only adopted after an mtime quiescence window proves no
  live recorder is still writing it, and move-residue is only deleted after matching both the
  archiver's naming scheme and the byte size.
- **Finalize is one transaction on one connection** (`MatchFsm.persistFinalized`): match, markers,
  and pause spans commit or roll back together, so a child-write failure can't orphan a parent row.
  A failed finalize still returns the FSM to IDLE — one bad persist must not kill recording for the
  rest of the session.

## SQLite decisions

Plain JDBC (`data/`), deliberately unglamorous, with the sharp edges handled (`DataSourceConfig`):

- WAL, `busy_timeout`, `foreign_keys=ON` set per physical connection; Hikari pool capped at 2 for a
  single-writer database.
- Transactions open `BEGIN IMMEDIATE`: under WAL, a DEFERRED transaction that upgrades from read to
  write fails *instantly* with `SQLITE_BUSY_SNAPSHOT` — bypassing `busy_timeout` entirely — which
  would roll back a whole finalize. Taking the write lock up front makes the busy handler actually
  engage.
- Forward-only migrations keyed on `PRAGMA user_version`, each in its own transaction, preceded by
  a WAL-checkpoint and a timestamped file backup — SQLite has no transactional DDL, so the file
  copy is the real safety net (`MigrationRunner`).

## Retention and tiered storage

`RetentionSweeper` reclaims disk by deleting the oldest VOD *files* while keeping every match's
metadata and markers (the row's `video_path` is NULLed, never the row). `RecordingArchiver`
relocates older VODs from the active recording drive onto capped archive drives. Both are
deliberately conservative: offline/unplugged drives are excluded rather than treated as free space,
files are deleted before rows so a failure can't leak an invisible file, and per-item failures are
logged and skipped rather than aborting the pass.

## Security model — and its limits

The threat model is scoped honestly (`bridge/BridgeAuthFilter` Javadoc): loopback binding does not
stop a *browser page* from fetching `127.0.0.1`, so that's the vector the design defends. A hostile
process running as the same user is explicitly out of scope — no local sidecar can defend that.

- Per-launch 256-bit bridge token, compared constant-time; the renderer gets it via the preload
  bridge, never persisted.
- GSI frames carry their own installed token, also compared constant-time; unauthenticated frames
  are dropped without impacting the feed's liveness signal.
- `GsiConnectorScopeFilter` confines the GSI port to `/gsi` only, so bridge endpoints are never
  reachable on the unauthenticated connector; `BridgeOrigins` centralizes the CORS/WS origin
  allow-list (including Chromium's `file://`-page quirk: `fetch` sends `Origin: null`, the
  WebSocket handshake sends a bare `file://`).
- The Electron renderer runs with `contextIsolation: true`, `sandbox: true`,
  `nodeIntegration: false`, a deny-all window-open handler, and a navigation allow-list.
- File serving and deletion are containment-checked against the configured storage roots — a
  tampered DB row can't be used to read or unlink an arbitrary path.

## Testing approach

The suite (JUnit 5 + AssertJ core-side, vitest app-side) targets the failure modes above rather
than line coverage: FSM transitions against a real temp SQLite through the real migrations, the
new-match-while-recording split, a two-thread race asserting exactly-once finalize, backward
wall-clock steps, crash-recovery against real files, and multi-drive retention budgets. Renderer
logic that needs tests lives as pure functions in `app/src/lib`; the supervisors are tested around
a mocked child process. CI runs the core suite, both typechecks, lint, and the app suite — it
deliberately skips the heavy packaging steps, which is why the packaged *layout* is instead guarded
by a test that cross-checks `electron-builder.yml` against what `paths.ts` reads.
