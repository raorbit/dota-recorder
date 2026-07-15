// Navigation allow-list for the main window, extracted from main.ts so the policy is unit-testable
// without Electron. Dev allows anything on the Vite dev server's ORIGIN (any path — HMR and deep
// links share it). Packaged allows ONLY the bundled index.html itself: the old bare `file://`
// prefix check let a navigation to ANY local file page inherit the preload bridge + token.

export interface AllowedNavigation {
  /** Dev-server URL prefix (dev builds only). */
  readonly devServerUrl?: string;
  /** Exact file:// URL of the bundled index.html (packaged builds only). */
  readonly packagedIndexUrl?: string;
}

/** True when the main window may navigate to {@code url} under the given allow-list. */
export function isAllowedNavigation(url: string, allowed: AllowedNavigation): boolean {
  if (allowed.devServerUrl) {
    // Compare parsed ORIGINS, not a string prefix. `url.startsWith('http://localhost:5173')` also
    // admitted look-alikes: a longer port sharing the prefix (http://localhost:51730/…) and a
    // userinfo bypass (http://localhost:5173@evil.tld/, whose real host is evil.tld). An origin match
    // pins scheme+host+port exactly while allowing any pathname (HMR and deep links share the origin).
    let target: URL;
    let dev: URL;
    try {
      target = new URL(url);
      dev = new URL(allowed.devServerUrl);
    } catch {
      return false; // unparseable -> deny
    }
    return target.origin === dev.origin;
  }
  if (!allowed.packagedIndexUrl) {
    return false;
  }
  let target: URL;
  let index: URL;
  try {
    target = new URL(url);
    index = new URL(allowed.packagedIndexUrl);
  } catch {
    return false; // unparseable -> deny
  }
  if (target.protocol !== 'file:' || target.host !== index.host) {
    return false;
  }
  // Compare decoded pathnames so encoding variants of the same file ("Dota%202" vs "Dota 2") can't
  // dodge the check in either direction; query/hash may differ (they can't change the document).
  const targetPath = decodedPathname(target);
  const indexPath = decodedPathname(index);
  if (targetPath === null || indexPath === null) {
    return false;
  }
  // Windows paths are case-insensitive (drive-letter casing especially varies), so a case-insensitive
  // match is required to not block the app's own reload — it can only widen to case variants of the
  // one allowed file, never to a different file.
  return targetPath.toLowerCase() === indexPath.toLowerCase();
}

function decodedPathname(url: URL): string | null {
  try {
    return decodeURIComponent(url.pathname);
  } catch {
    return null; // malformed percent-encoding -> deny
  }
}
