# dota-recorder

A local-only Windows desktop app that auto-records every Dota 2 match, tags your own
deaths and kills on a timeline, and lets you click a marker to jump straight to that
moment in the recorded video. Modeled on [Warcraft Recorder](https://github.com/aza547/wow-recorder),
adapted to Dota's data reality — there's no on-disk combat log, so live
[Game State Integration](https://developer.valvesoftware.com/wiki/Counter-Strike:_Global_Offensive_Game_State_Integration)
(GSI) is the feed that drives recording and tagging.

It bundles and auto-configures its own OBS instance, so there's no manual OBS setup —
point it at your Dota install once and it records in the background.

## Status

v0.1.1 — first tagged release. The full feature surface is built, the core
**detect → record → tag → store → seek** loop is validated end-to-end against a real Dota
match, and the packaged Windows installer is confirmed installing and running.

- [x] Application framework chosen / scaffolded
- [x] Core implemented (recording brain, GSI, OBS control, storage, browse UI)
- [x] Live record → tag → seek proven end-to-end
- [x] Packaged installer end-to-end + tagged release
- [ ] Real-Dota *bot-match* capture validated at the keyboard (only a demo match proven so far)

## Install

Grab the latest `Dota 2 Recorder-Setup-*.exe` from the
[Releases](https://github.com/raorbit/dota-recorder/releases) page and run it. The installer
bundles everything it needs — OBS and a trimmed JRE — so there's no separate setup; just point
it at your Dota install on first run.

## How it works

```
Renderer (React / Vite)
   │  REST + WebSocket over 127.0.0.1
Electron main  ── supervises ──►  JVM core (Spring Boot)
   │  spawns + reaps                 │
   └──────────────► OBS  ◄───────────┘   (bundled, auto-configured, obs-websocket)
   Dota 2 ── GSI HTTP POST ──►  core
```

Electron is the sole supervisor: it spawns and reaps the JVM core and OBS, and the
renderer talks to the core over loopback only — nothing binds beyond `127.0.0.1`. The
core parses GSI frames into match state, drives an OBS recording, diffs your kills/deaths
into timeline markers, and stores VODs + markers in SQLite. Seek offsets are anchored to
wall-clock (not the game clock), so clicking a marker lands on the right frame.

## Requirements

- Windows 10/11
- Dota 2 with `-gamestateintegration` in its Steam launch options (the app writes the GSI
  config and surfaces the launch option for you)
- A GPU/CPU that can record (the app probes for a hardware encoder, falling back to x264)

## Building from source

Two build pipelines converge in electron-builder: **Gradle** for the JVM core and
**npm/Vite** for the Electron renderer + main.

```sh
# one-time: download the pinned portable OBS used in dev (~hundreds of MB)
npm run fetch:obs

# dev (hot UI + Electron; build the core jar first)
cd core && ./gradlew bootJar && cd ..
npm run dev

# typecheck (renderer + electron main)
npm run typecheck

# core unit tests
cd core && ./gradlew test

# full Windows installer (NSIS): core jar + trimmed JRE + renderer + electron
npm run dist
```

## License

[MIT](LICENSE)
