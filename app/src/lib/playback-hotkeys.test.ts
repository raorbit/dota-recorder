import { describe, expect, it } from 'vitest';
import { shouldSpaceTogglePlayback, type SpaceToggleContext } from './playback-hotkeys';

// A press that SHOULD toggle: windowed, playback already started, nothing else owns the key.
function ctx(overrides: Partial<SpaceToggleContext> = {}): SpaceToggleContext {
  return {
    shiftKey: false,
    repeat: false,
    defaultPrevented: false,
    typingTarget: false,
    interactiveTarget: false,
    menuOverlayTarget: false,
    videoLoaded: true,
    playbackEngaged: true,
    inFullscreen: false,
    ...overrides,
  };
}

describe('shouldSpaceTogglePlayback', () => {
  it('toggles windowed once playback has been engaged', () => {
    expect(shouldSpaceTogglePlayback(ctx())).toBe(true);
  });

  it('does NOT toggle windowed before playback has ever started (fresh selection)', () => {
    // The nervous case: a match is selected but the user is just browsing — a stray Space
    // must not surprise-start the VOD.
    expect(shouldSpaceTogglePlayback(ctx({ playbackEngaged: false }))).toBe(false);
  });

  it('toggles in fullscreen even before playback has started', () => {
    expect(shouldSpaceTogglePlayback(ctx({ playbackEngaged: false, inFullscreen: true }))).toBe(
      true,
    );
  });

  it('never toggles without a loaded video, even fullscreen/engaged', () => {
    expect(shouldSpaceTogglePlayback(ctx({ videoLoaded: false }))).toBe(false);
    expect(shouldSpaceTogglePlayback(ctx({ videoLoaded: false, inFullscreen: true }))).toBe(false);
  });

  it('stands down when another handler already owned the press', () => {
    // Table rows select on Space, glyphs/markers activate on Space — all preventDefault.
    expect(shouldSpaceTogglePlayback(ctx({ defaultPrevented: true }))).toBe(false);
    expect(shouldSpaceTogglePlayback(ctx({ defaultPrevented: true, inFullscreen: true }))).toBe(
      false,
    );
  });

  it('stands down on a focused Space-owning control (native buttons do not cancel keydown)', () => {
    expect(shouldSpaceTogglePlayback(ctx({ interactiveTarget: true }))).toBe(false);
    expect(shouldSpaceTogglePlayback(ctx({ interactiveTarget: true, inFullscreen: true }))).toBe(
      false,
    );
  });

  it('stands down while typing, even in fullscreen', () => {
    expect(shouldSpaceTogglePlayback(ctx({ typingTarget: true }))).toBe(false);
    expect(shouldSpaceTogglePlayback(ctx({ typingTarget: true, inFullscreen: true }))).toBe(false);
  });

  it('stands down while a menu/dialog overlay owns focus', () => {
    expect(shouldSpaceTogglePlayback(ctx({ menuOverlayTarget: true }))).toBe(false);
    expect(shouldSpaceTogglePlayback(ctx({ menuOverlayTarget: true, inFullscreen: true }))).toBe(
      false,
    );
  });

  it('leaves Shift+Space to its native scroll-up meaning', () => {
    expect(shouldSpaceTogglePlayback(ctx({ shiftKey: true }))).toBe(false);
  });

  it('ignores key auto-repeat so a held Space cannot machine-gun toggles', () => {
    expect(shouldSpaceTogglePlayback(ctx({ repeat: true }))).toBe(false);
    expect(shouldSpaceTogglePlayback(ctx({ repeat: true, inFullscreen: true }))).toBe(false);
  });
});
