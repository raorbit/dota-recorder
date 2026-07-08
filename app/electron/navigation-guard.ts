// Navigation allow-list for the main window, extracted from main.ts so the policy is unit-testable
// without Electron. Dev allows anything under the Vite dev server (prefix match — HMR and deep links
// share that origin). Packaged allows ONLY the bundled index.html itself: the old bare `file://`
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
    return url.startsWith(allowed.devServerUrl);
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
