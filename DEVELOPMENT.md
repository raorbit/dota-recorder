# Development notes

## How this was built

This project was built AI-assisted, and the git history shows it if you look: clusters of atomic
commits landing minutes apart, a comment voice more uniform than any solo human writes, and a pace
(the v0.1 surface in about two weeks) that isn't hand-typed. I directed Claude-based coding agents
through an implement → review → fix loop: I owned the product design and the plan, agents wrote
most of the code and the first-pass reviews, and I drove the parts a model can't do — running live
matches against the real OBS and Dota, debugging the packaged build on real hardware, deciding what
shipped, and validating every release at the keyboard.

The review-fix waves visible in the history (e.g. PRs #47, #50) are that loop running: adversarial
review passes over the merged code, each verified finding turned into a fix commit with a
regression test. Treat the tests as the source of truth for what actually works — they were the
contract I held the generated code to, and the hard ones (the concurrency race in finalize, the
backward wall-clock step, crash-recovery against real files) encode bugs that were real before they
were tests.

## What live validation caught (and code review didn't)

The two bugs worth writing up both shipped past a green CI and a clean review, and were only found
by running the packaged app for real. They're why the validation loop matters more than any static
signal in this repo.

### The packaged app that couldn't connect to itself (fixed in PR #40)

Symptom: the installed build's status card sat on "connecting…" forever, while the exact same code
worked in dev. REST calls succeeded; only the status WebSocket failed, with the bridge returning
403 on the handshake.

The cause is a Chromium quirk worth knowing: a page loaded over `file://` (the packaged renderer)
sends `Origin: null` on `fetch` requests — which the bridge's origin allow-list included — but the
*WebSocket handshake* sends a bare `Origin: file://` instead. Dev never sees this because Vite
serves the renderer over `http://localhost`, so the allow-list matched in every environment except
the one users run. The fix centralized the allow-list in `BridgeOrigins` with both spellings and a
regression test, so the two frameworks (Spring MVC CORS and the WebSocket handshake) can never
drift apart again.

### The 4K recording that was three-quarters missing (fixed in PR #28)

Symptom: on a high-DPI machine rendering Dota at 4K, recordings contained only the top-left quarter
of the game.

OBS's canvas was the configured 1920×1080, but a Game Capture source comes in at the game's native
render size, and OBS places a new scene item unscaled at (0,0) — so a 4K game hung off the canvas
and got cropped. Dev machines at 1080p never showed it. The fix
(`ObsSceneConfigurer.fitGameCaptureToCanvas`) sets inner-fit bounds equal to the canvas on the Game
Capture scene item on *every* connect — resolution-agnostic, and it repairs pre-existing broken
scene items rather than only newly created ones.

## Validation timeline

- **2026-06-28** — full `detect → record → tag → store → seek` loop proven live against OBS in a
  demo match: GSI setup minted its token, recording armed on `OUTPUT_STARTED`, kill/death markers
  tagged, and clicking a death marker seeked the VOD to the death. (A demo sends no `POST_GAME`, so
  finalize correctly fell to the 30s GSI-silence watchdog.)
- **2026-06-29** — packaged NSIS install confirmed end-to-end on a clean run; v0.1.1 tagged as the
  first release.
- **2026-07-01** — real Dota match captured at the keyboard, closing the last v0.1 validation item.

## Working on the repo

- Two build pipelines converge in electron-builder: Gradle (`core/`) and npm/Vite (`app/`). See the
  README's build section for the commands.
- CI runs the core JUnit suite, both typechecks, lint, and the app vitest suite on every push/PR.
  It deliberately skips packaging; the packaged layout is guarded by
  `app/electron/packaged-layout.test.ts` instead.
- Releases: bump the root `package.json` version (gradle and `/health` read it from there), tag
  from the merged main commit, `npm run dist`, attach the installer to the GitHub Release.
