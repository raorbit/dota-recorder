// Pure decision logic for the global Space play/pause hotkey. Kept React/DOM-free so it unit-tests
// in plain Node (see playback-hotkeys.test.ts); VideoPlayer.tsx maps the real KeyboardEvent + DOM
// state onto this context.

// Focused controls that own Space themselves, where the global hotkey must stand down so one press
// never does two things. A native <button> activates on Space but does NOT cancel the keydown, so
// the defaultPrevented check alone can't catch it; the player's role="button"/"slider" chrome does
// preventDefault in its own onKeyDown, but is listed anyway so the standdown doesn't depend on that.
export const SPACE_OWNING_TARGETS =
  'button,[role="button"],[role="menuitem"],[role="slider"],[role="checkbox"],[role="switch"]';

export interface SpaceToggleContext {
  // Shift held (Alt/Ctrl/Meta are shared-guarded with the arrow keys before this is consulted;
  // Shift+Space keeps its native scroll-up meaning).
  readonly shiftKey: boolean;
  // OS key auto-repeat frame — a held Space must not machine-gun play/pause toggles.
  readonly repeat: boolean;
  // Another handler already owned this press (a table row's Space-select, a glyph's keyActivate, a
  // marker's Space-seek — all preventDefault, which is visible here because React's root handlers
  // run before a window-level listener in the bubble phase).
  readonly defaultPrevented: boolean;
  // Focus is in an input/textarea/select/contenteditable — the user is typing.
  readonly typingTarget: boolean;
  // Focus is on/inside a control matching SPACE_OWNING_TARGETS.
  readonly interactiveTarget: boolean;
  // A menu/dialog overlay owns focus (e.g. the row-actions PopupMenu).
  readonly menuOverlayTarget: boolean;
  // The media element has a src loaded.
  readonly videoLoaded: boolean;
  // Playback has started at least once for the current selection. This is the windowed safety
  // gate: merely selecting a match must not turn a stray Space into a surprise play — the hotkey
  // arms only after the user has actually started playback (every start path — play button, marker
  // seek, scrub click, clip auto-play — goes through play()).
  readonly playbackEngaged: boolean;
  // The player stage is fullscreen — unambiguous watch mode, so the engagement gate is skipped
  // (Space should start a paused-at-0 video there). All other standdowns still apply.
  readonly inFullscreen: boolean;
}

export function shouldSpaceTogglePlayback(ctx: SpaceToggleContext): boolean {
  if (ctx.shiftKey || ctx.repeat || ctx.defaultPrevented) return false;
  if (ctx.typingTarget || ctx.interactiveTarget || ctx.menuOverlayTarget) return false;
  if (!ctx.videoLoaded) return false;
  return ctx.inFullscreen || ctx.playbackEngaged;
}
