import { describe, expect, it, vi } from 'vitest';
import { readFileSync } from 'node:fs';
import * as path from 'node:path';
import { load } from 'js-yaml';

// Regression guard for the PR #22 bug class: the electron-builder.yml extraResources
// layout drifting away from what paths.ts reads at runtime. In #22 a flat `to: obs`
// dropped the obs-portable/ subdir and orphaned the `.obs-*.ok` marker, so packaged
// obsSourceDir() pointed one level too deep and obsVersion() always read "0" — the
// packaged build silently skipped OBS setup and could not record. Dev worked; only the
// packaged layout was broken, and it shipped with ZERO coverage. This test wires the two
// together: for every packaged path a reader in paths.ts derives, assert the yml ships a
// matching `to:` target. paths.ts is the source of truth here — we do NOT hardcode
// 'obs/obs-portable' as a second constant; we read it back out of the reader.

// Mock electron so paths.ts thinks it is a packaged build. process.resourcesPath is set to
// a fixed sentinel below; every packaged reader roots its output at that prefix, which we
// then strip to recover the layout-relative suffix. Same harness shape as paths.test.ts.
const { fsState } = vi.hoisted(() => ({
  fsState: {
    // obsVersion() scans a dir via existsSync + readdirSync; we record the path it probes
    // rather than assert on its (version-string) return value. existsSync stays true so the
    // scan proceeds to readdirSync; readdirSync returns [] (no marker) — the return value is
    // irrelevant, we only need the directory it was handed.
    scannedDirs: [] as string[],
  },
}));

vi.mock('electron', () => ({
  app: {
    get isPackaged() {
      return true;
    },
    // Not reached on the packaged branches under test, but kept defined so any incidental
    // call does not throw.
    getAppPath: () => 'C:/repo/app',
    getPath: (_name: string) => 'C:/Users/x/AppData/Roaming',
  },
}));

vi.mock('node:fs', async (importOriginal) => {
  const actual = await importOriginal<typeof import('node:fs')>();
  return {
    ...actual,
    // Record every dir obsVersion() scans; keep existsSync truthy so the scan runs.
    existsSync: (p: string) => {
      fsState.scannedDirs.push(p);
      return true;
    },
    readdirSync: () => [] as string[],
  };
});

import { bundledJavawPath, ffmpegPath, obsSourceDir, obsVersion, resolveCoreJar } from './paths';

const RESOURCES = 'C:/res';

// Fixed sentinel for process.resourcesPath, matched to how paths.ts reads it.
(process as { resourcesPath?: string }).resourcesPath = RESOURCES;

// Normalize any host path (Windows backslashes on win32) to forward slashes so yml
// targets — always written with '/' — compare apples to apples.
function slash(p: string): string {
  return p.split(path.sep).join('/');
}

// Strip the resourcesPath prefix from a packaged reader's absolute output to recover the
// layout-relative suffix (e.g. 'core/core.jar', 'jre/bin/javaw.exe'). The suffix is what
// must be shippable under some yml `to:` target.
function suffixUnderResources(absolute: string): string {
  const rel = path.relative(RESOURCES, absolute);
  return slash(rel);
}

// Parse the SHIPPED electron-builder.yml (repo root, two levels up from app/electron/) and
// collect every extraResources `to:` value, normalized to forward slashes. Two entries
// legitimately map under obs (obs/obs-portable AND obs itself, with the .obs-*.ok marker
// filter), so this is a plain list — do NOT assume one entry per target.
interface ExtraResource {
  readonly from: string;
  readonly to: string;
  readonly filter?: readonly string[];
}

interface BuilderConfig {
  readonly extraResources?: readonly ExtraResource[];
}

function loadExtraResources(): ExtraResource[] {
  const ymlPath = path.resolve(__dirname, '..', '..', 'electron-builder.yml');
  const raw = readFileSync(ymlPath, 'utf8');
  const cfg = load(raw) as BuilderConfig;
  const entries = cfg.extraResources ?? [];
  return entries.map((e) => ({ ...e, to: slash(e.to) }));
}

describe('packaged layout ↔ electron-builder.yml (PR #22 regression)', () => {
  const resources = loadExtraResources();

  // A reader-derived suffix is "shipped" if:
  //   (a) some `to:` target matches it EXACTLY (a file target like core/core.jar, or a
  //       directory target like obs/obs-portable that is copied wholesale), OR
  //   (b) it nests under a target whose `from` is copied WITHOUT a filter (a whole-tree
  //       copy — e.g. jre/bin/javaw.exe under the `jre` target).
  //
  // Nesting under a FILTERED target does NOT count: that target only ships the files its
  // filter matches, not arbitrary nested paths. This is exactly what makes the #22 mutation
  // fail — collapsing `to: obs/obs-portable` into the filtered `to: obs` (.obs-*.ok only)
  // no longer ships obs-portable, so obsSourceDir()'s suffix has no valid target and this
  // test goes red.
  const isShipped = (suffix: string): boolean =>
    resources.some((e) => {
      if (e.to === suffix) return true;
      const nests = suffix === `${e.to}/` || suffix.startsWith(`${e.to}/`);
      return nests && (e.filter === undefined || e.filter.length === 0);
    });

  it('ships the obs-portable dir obsSourceDir() copies from', () => {
    const src = obsSourceDir();
    expect(src).not.toBeNull();
    const suffix = suffixUnderResources(src!);
    // Reader is the source of truth: whatever obsSourceDir() resolves to (currently
    // obs/obs-portable) must have a matching yml target — no second hardcoded constant.
    expect(isShipped(suffix), `${suffix} not shipped by any extraResources to:`).toBe(true);
  });

  it('ships the core.jar resolveCoreJar() launches', () => {
    const jar = resolveCoreJar();
    expect(jar).not.toBeNull();
    const suffix = suffixUnderResources(jar!);
    expect(isShipped(suffix), `${suffix} not shipped by any extraResources to:`).toBe(true);
  });

  it('ships the JRE bundledJavawPath() launches from', () => {
    const javaw = bundledJavawPath();
    expect(javaw).not.toBeNull();
    const suffix = suffixUnderResources(javaw!);
    expect(isShipped(suffix), `${suffix} not shipped by any extraResources to:`).toBe(true);
  });

  it('ships the ffmpeg.exe ffmpegPath() hands the core', () => {
    const ffmpeg = ffmpegPath();
    expect(ffmpeg).not.toBeNull();
    const suffix = suffixUnderResources(ffmpeg!);
    expect(isShipped(suffix), `${suffix} not shipped by any extraResources to:`).toBe(true);
  });

  it('ships the dir obsVersion() scans for the .obs-*.ok marker', () => {
    fsState.scannedDirs.length = 0;
    obsVersion();
    // obsVersion() probes exactly one dir before reading the marker; recover it and strip
    // the resources prefix. This is the dir that must carry the .obs-*.ok filter mapping.
    expect(fsState.scannedDirs.length).toBeGreaterThan(0);
    const scanned = suffixUnderResources(fsState.scannedDirs[0]);
    expect(isShipped(scanned), `${scanned} not shipped by any extraResources to:`).toBe(true);
  });

  it('maps the .obs-*.ok marker under the obs target via a filter', () => {
    // The version marker ships as a SIBLING of obs-portable/ under the `obs` target, gated by
    // a `.obs-*.ok` filter (a flat copy would drop it — the exact #22 failure). obsVersion()
    // scans that same `obs` dir, so tie the two together: the scanned dir's target must carry
    // the marker filter.
    fsState.scannedDirs.length = 0;
    obsVersion();
    const scanned = suffixUnderResources(fsState.scannedDirs[0]);
    const markerEntry = resources.find(
      (e) =>
        e.to === scanned &&
        (e.filter ?? []).some((f) => f.startsWith('.obs-') && f.endsWith('.ok')),
    );
    expect(
      markerEntry,
      `no extraResources entry maps ${scanned} with a .obs-*.ok filter`,
    ).toBeDefined();
  });
});
