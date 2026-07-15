import { describe, expect, it } from 'vitest';

import { isAllowedNavigation } from './navigation-guard';

const DEV = { devServerUrl: 'http://localhost:5173' };
const PACKAGED = {
  packagedIndexUrl: 'file:///C:/Users/u/AppData/Local/Programs/Dota%202%20Recorder/resources/app.asar/dist/index.html',
};

describe('isAllowedNavigation — dev', () => {
  it('allows the dev-server origin on any path (HMR + deep links share it)', () => {
    expect(isAllowedNavigation('http://localhost:5173', DEV)).toBe(true);
    expect(isAllowedNavigation('http://localhost:5173/settings', DEV)).toBe(true);
    expect(isAllowedNavigation('http://localhost:5173/#/library?x=1', DEV)).toBe(true);
  });

  it('blocks other origins and local files', () => {
    expect(isAllowedNavigation('http://localhost:9999/', DEV)).toBe(false);
    expect(isAllowedNavigation('https://example.com/', DEV)).toBe(false);
    expect(isAllowedNavigation('file:///C:/anything.html', DEV)).toBe(false);
  });

  it('blocks port-prefix collisions and userinfo-bypass lookalikes (origin match, not prefix)', () => {
    // The old `url.startsWith('http://localhost:5173')` admitted both of these. :51730 shares the
    // 5173 prefix but is a different port; the second smuggles evil.tld past the check as the real
    // host (localhost:5173 is only userinfo). An origin comparison rejects both.
    expect(isAllowedNavigation('http://localhost:51730/', DEV)).toBe(false);
    expect(isAllowedNavigation('http://localhost:5173@evil.tld/', DEV)).toBe(false);
  });

  it('denies an unparseable url on the dev branch', () => {
    expect(isAllowedNavigation('not a url', DEV)).toBe(false);
  });
});

describe('isAllowedNavigation — packaged', () => {
  it('allows the bundled index.html exactly', () => {
    expect(isAllowedNavigation(PACKAGED.packagedIndexUrl!, PACKAGED)).toBe(true);
  });

  it('allows query/hash on the bundled index.html (same document)', () => {
    expect(isAllowedNavigation(`${PACKAGED.packagedIndexUrl}#/library`, PACKAGED)).toBe(true);
    expect(isAllowedNavigation(`${PACKAGED.packagedIndexUrl}?x=1`, PACKAGED)).toBe(true);
  });

  it('allows case variants of the same file (Windows paths are case-insensitive)', () => {
    const upperDrive = PACKAGED.packagedIndexUrl!.replace('/C:/', '/c:/');
    expect(isAllowedNavigation(upperDrive, PACKAGED)).toBe(true);
  });

  it('allows encoding variants of the same path', () => {
    const decodedSpaces = PACKAGED.packagedIndexUrl!.replace(/%20/g, ' ');
    expect(isAllowedNavigation(decodedSpaces, PACKAGED)).toBe(true);
  });

  it('blocks OTHER local file pages — the core of the fix (bare file:// prefix let any local page inherit the bridge token)', () => {
    expect(isAllowedNavigation('file:///C:/Users/u/Downloads/evil.html', PACKAGED)).toBe(false);
    // Even a sibling file inside the app's own dist directory is not the bundled index.
    expect(
      isAllowedNavigation(
        'file:///C:/Users/u/AppData/Local/Programs/Dota%202%20Recorder/resources/app.asar/dist/other.html',
        PACKAGED,
      ),
    ).toBe(false);
  });

  it('blocks path-confusion lookalikes (prefix/suffix of the allowed path)', () => {
    expect(isAllowedNavigation(`${PACKAGED.packagedIndexUrl}.evil.html`, PACKAGED)).toBe(false);
    expect(isAllowedNavigation('file:///C:/index.html', PACKAGED)).toBe(false);
  });

  it('blocks non-file protocols and UNC hosts', () => {
    expect(isAllowedNavigation('https://example.com/index.html', PACKAGED)).toBe(false);
    // Same pathname but on a remote UNC host is a different file.
    const unc = PACKAGED.packagedIndexUrl!.replace('file:///', 'file://attacker/');
    expect(isAllowedNavigation(unc, PACKAGED)).toBe(false);
  });

  it('blocks unparseable URLs and malformed percent-encoding', () => {
    expect(isAllowedNavigation('not a url', PACKAGED)).toBe(false);
    expect(isAllowedNavigation('file:///C:/%E0%A4%A.html', PACKAGED)).toBe(false);
  });

  it('blocks everything when no allow-list entry is configured', () => {
    expect(isAllowedNavigation('file:///C:/anything.html', {})).toBe(false);
  });
});
