import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

// Mutable mock state for the electron `app` shim. Tests flip these per-case.
const { appState, fsState } = vi.hoisted(() => ({
  appState: {
    isPackaged: false,
    appPath: 'C:/repo/app',
    getPathReturns: { appData: 'C:/Users/x/AppData/Roaming' } as Record<string, string>,
  },
  fsState: {
    existsSync: vi.fn<(p: string) => boolean>(() => true),
    readdirSync: vi.fn<(p: string) => string[]>(() => []),
  },
}));

vi.mock('electron', () => ({
  app: {
    get isPackaged() {
      return appState.isPackaged;
    },
    getAppPath: () => appState.appPath,
    getPath: (name: string) => appState.getPathReturns[name],
  },
}));

vi.mock('node:fs', async (importOriginal) => {
  const actual = await importOriginal<typeof import('node:fs')>();
  return {
    ...actual,
    existsSync: (p: string) => fsState.existsSync(p),
    readdirSync: (p: string) => fsState.readdirSync(p),
  };
});

import * as path from 'node:path';
import {
  bundledJavawPath,
  obsDir,
  obsSourceDir,
  obsVersion,
  resolveCoreJar,
} from './paths';

// path.resolve / path.join on Windows-style inputs run through the host's path module.
// On a non-Windows host the separators normalize to '/', so compare via path helpers
// rather than hard-coded backslash strings to keep the tests platform-agnostic.
const RESOURCES = 'C:/res';

let savedResourcesPath: string | undefined;
let savedCoreJarEnv: string | undefined;
let savedLocalAppData: string | undefined;

beforeEach(() => {
  appState.isPackaged = false;
  appState.appPath = 'C:/repo/app';
  appState.getPathReturns = { appData: 'C:/Users/x/AppData/Roaming' };
  fsState.existsSync.mockReset().mockReturnValue(true);
  fsState.readdirSync.mockReset().mockReturnValue([]);

  savedResourcesPath = (process as { resourcesPath?: string }).resourcesPath;
  savedCoreJarEnv = process.env.DOTAREC_CORE_JAR;
  savedLocalAppData = process.env.LOCALAPPDATA;
  (process as { resourcesPath?: string }).resourcesPath = RESOURCES;
  delete process.env.DOTAREC_CORE_JAR;
});

afterEach(() => {
  (process as { resourcesPath?: string | undefined }).resourcesPath = savedResourcesPath;
  if (savedCoreJarEnv === undefined) delete process.env.DOTAREC_CORE_JAR;
  else process.env.DOTAREC_CORE_JAR = savedCoreJarEnv;
  if (savedLocalAppData === undefined) delete process.env.LOCALAPPDATA;
  else process.env.LOCALAPPDATA = savedLocalAppData;
  vi.restoreAllMocks();
});

describe('resolveCoreJar', () => {
  it('honors DOTAREC_CORE_JAR over the libs dir', () => {
    process.env.DOTAREC_CORE_JAR = '  C:/custom/path/core.jar  ';

    expect(resolveCoreJar()).toBe(path.resolve('C:/custom/path/core.jar'));
    // The env override short-circuits before any filesystem scan of the libs dir.
    expect(fsState.readdirSync).not.toHaveBeenCalled();
  });

  it('returns the packaged core.jar under resourcesPath when packaged', () => {
    appState.isPackaged = true;

    expect(resolveCoreJar()).toBe(path.join(RESOURCES, 'core', 'core.jar'));
    // Packaged path never touches the dev libs dir.
    expect(fsState.readdirSync).not.toHaveBeenCalled();
  });

  it('picks the runnable jar over the -plain.jar in dev', () => {
    fsState.existsSync.mockReturnValue(true);
    fsState.readdirSync.mockReturnValue(['core-plain.jar', 'core.jar']);

    const libsDir = path.resolve('C:/repo/app', '..', 'core', 'build', 'libs');
    // Shipping core-plain.jar would yield a non-runnable jar and the core would never boot.
    expect(resolveCoreJar()).toBe(path.join(libsDir, 'core.jar'));
  });

  it('returns null when the libs dir is missing', () => {
    fsState.existsSync.mockReturnValue(false);

    expect(resolveCoreJar()).toBeNull();
    expect(fsState.readdirSync).not.toHaveBeenCalled();
  });

  it('returns null when the libs dir holds no jars', () => {
    fsState.existsSync.mockReturnValue(true);
    fsState.readdirSync.mockReturnValue(['notes.txt']);

    expect(resolveCoreJar()).toBeNull();
  });

  it('ignores a blank/whitespace-only DOTAREC_CORE_JAR and falls through to the libs dir', () => {
    process.env.DOTAREC_CORE_JAR = '   ';
    fsState.readdirSync.mockReturnValue(['core.jar']);

    const libsDir = path.resolve('C:/repo/app', '..', 'core', 'build', 'libs');
    expect(resolveCoreJar()).toBe(path.join(libsDir, 'core.jar'));
  });
});

describe('obsVersion', () => {
  it('parses the .obs-<version>.ok marker filename', () => {
    fsState.existsSync.mockReturnValue(true);
    fsState.readdirSync.mockReturnValue(['.obs-32.1.2.ok', 'noise.txt']);

    expect(obsVersion()).toBe('32.1.2');
  });

  it('returns "0" when no marker file is present', () => {
    fsState.existsSync.mockReturnValue(true);
    fsState.readdirSync.mockReturnValue(['noise.txt', 'obs64.exe']);

    expect(obsVersion()).toBe('0');
  });

  it('returns "0" when the obs dir does not exist', () => {
    fsState.existsSync.mockReturnValue(false);

    expect(obsVersion()).toBe('0');
    expect(fsState.readdirSync).not.toHaveBeenCalled();
  });
});

describe('obsDir', () => {
  it('uses LOCALAPPDATA when set', () => {
    process.env.LOCALAPPDATA = 'C:/Users/x/AppData/Local';

    expect(obsDir()).toBe(path.join('C:/Users/x/AppData/Local', 'dota-recorder', 'obs'));
  });

  it('falls back to appData when LOCALAPPDATA is unset', () => {
    delete process.env.LOCALAPPDATA;
    appState.getPathReturns = { appData: 'C:/Users/x/AppData/Roaming' };

    expect(obsDir()).toBe(
      path.join('C:/Users/x/AppData/Roaming', 'dota-recorder', 'obs'),
    );
  });
});

describe('bundledJavawPath', () => {
  it('returns null in dev', () => {
    appState.isPackaged = false;

    expect(bundledJavawPath()).toBeNull();
  });

  it('returns <resourcesPath>/jre/bin/javaw.exe when packaged', () => {
    appState.isPackaged = true;

    expect(bundledJavawPath()).toBe(path.join(RESOURCES, 'jre', 'bin', 'javaw.exe'));
  });
});

describe('obsSourceDir', () => {
  it('returns <resourcesPath>/obs/obs-portable when packaged', () => {
    appState.isPackaged = true;

    expect(obsSourceDir()).toBe(path.join(RESOURCES, 'obs', 'obs-portable'));
  });

  it('returns the repo-root bundled OBS path in dev when it exists', () => {
    appState.isPackaged = false;
    fsState.existsSync.mockReturnValue(true);

    const devPath = path.resolve('C:/repo/app', '..', 'build-resources', 'obs', 'obs-portable');
    expect(obsSourceDir()).toBe(devPath);
  });

  it('returns null in dev when the bundled OBS path is absent', () => {
    appState.isPackaged = false;
    fsState.existsSync.mockReturnValue(false);

    expect(obsSourceDir()).toBeNull();
  });
});
