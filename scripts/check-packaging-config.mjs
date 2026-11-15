// Lightweight packaging-config guard for CI. Full electron-builder packaging (fetch:obs +
// build:all + electron-builder) is far too heavy to run per-PR, so the packaging layer — the one
// that historically produced the worst regressions (the PR #22 flat `to: obs` that broke first-run
// OBS, and a koffi asarUnpack that missed the @koromix scoped package) — is otherwise validated only
// when a human manually builds and installs. This asserts the invariants that regressed, from the
// electron-builder.yml text, the NSIS installer script, and (when present) the installed koffi
// layout, so a config regression fails in CI in seconds instead of at release time. It deliberately
// does NOT assert the existence of build outputs (core.jar / jre-image / obs / ffmpeg): those are
// produced by the heavy build this check exists to avoid, and are absent on a fresh CI checkout.
//
// Usage: node scripts/check-packaging-config.mjs   (wired into CI as its own step).

import { existsSync, readFileSync, readdirSync } from 'node:fs';
import * as path from 'node:path';

const repoRoot = path.resolve(import.meta.dirname, '..');
const ebPath = path.join(repoRoot, 'electron-builder.yml');

const failures = [];
function check(cond, message) {
  if (!cond) failures.push(message);
}

// --- electron-builder.yml invariants (text-level, so no YAML dependency is needed) ---------------
if (!existsSync(ebPath)) {
  console.error(`electron-builder.yml not found at ${ebPath}`);
  process.exit(1);
}
const eb = readFileSync(ebPath, 'utf8');

// PR #22 regression: the OBS binaries MUST ship under obs/obs-portable (a flat `to: obs` drops the
// subdir and leaves paths.ts pointing one level too deep, so first-run OBS copy silently fails).
check(
  /from:\s*build-resources\/obs\/obs-portable/.test(eb) && /to:\s*obs\/obs-portable/.test(eb),
  'electron-builder.yml: OBS binaries must map `from: build-resources/obs/obs-portable` -> `to: obs/obs-portable` (a flat `to: obs` was the PR #22 packaged-OBS blocker).',
);
// The `.obs-<version>.ok` version marker must be shipped as a sibling of obs-portable, else
// obsVersion() reads "0" and the first-run copy is skipped.
check(
  /\.obs-\*\.ok/.test(eb),
  'electron-builder.yml: the `.obs-*.ok` OBS version marker filter is missing (obsVersion() would read "0" and skip the first-run copy).',
);

// The core jar + trimmed JRE image the supervisor launches, and the bundled ffmpeg, must be mapped.
check(/to:\s*core\/core\.jar/.test(eb), 'electron-builder.yml: missing `to: core/core.jar` extraResources mapping.');
check(/to:\s*jre\b/.test(eb), 'electron-builder.yml: missing `to: jre` (trimmed JRE image) extraResources mapping.');
check(/to:\s*ffmpeg\/ffmpeg\.exe/.test(eb), 'electron-builder.yml: missing `to: ffmpeg/ffmpeg.exe` extraResources mapping.');

// koffi 3.x native binary lives in a scoped @koromix package; BOTH koffi/** and @koromix/** must be
// unpacked from the asar or the Job Object silently falls back to taskkill in the packaged build.
check(
  /node_modules\/koffi\/\*\*/.test(eb),
  'electron-builder.yml: asarUnpack must include `**/node_modules/koffi/**`.',
);
check(
  /node_modules\/@koromix\/\*\*/.test(eb),
  'electron-builder.yml: asarUnpack must include `**/node_modules/@koromix/**` (koffi 3.x ships koffi.node in the scoped @koromix package).',
);

// The installer artifact name must EXPAND to a space-free name so the on-disk .exe, latest.yml url,
// and the uploaded GitHub asset all match (a spaced name 404s the electron-updater feed). Two vectors:
// literal whitespace typed into the template, and — the documented one (electron-builder.yml warns
// about it) — ${productName}, which is space-free as a token but expands to "Dota 2 Recorder"; ${name}
// likewise expands to the scoped "@dota-recorder/app". Reject both explicitly (stripping all ${...}
// placeholders to 'x' before the whitespace test, as before, would let ${productName} slip through).
const artifact = /artifactName:\s*(.+)/.exec(eb);
const artifactName = artifact ? artifact[1].trim() : '';
const expandsToSpaced = /\$\{(productName|name)\}/.test(artifactName);
const hasLiteralWhitespace = /\s/.test(artifactName); // ${version}/${ext}/${arch} carry no whitespace
check(
  artifact != null && !expandsToSpaced && !hasLiteralWhitespace,
  'electron-builder.yml: nsis.artifactName must expand to a space-free name — avoid ${productName}/${name} (they expand to a spaced/scoped value) and literal whitespace, or the on-disk .exe diverges from latest.yml and 404s the updater.',
);

// --- installer script: every program it starts named by its full path ----------------------------
// Windows can find a program named without a path in the installer's own folder (Downloads, for a
// downloaded installer) or the folder it works in (the install folder, which the user can write to)
// before the system's. Anything the user ran could leave a cmd.exe or a netsh.exe there for the
// installer to start instead, and the firewall step starts both elevated. So everything the
// installer starts, and everything a cmd line it starts runs, has to be named by its full path.
// This ports the Go remake's guard (build/windows/installer_windows_test.go); this reader also
// reads the switches cmd is given before its /c.
const buildResources = path.join(repoRoot, 'build-resources');

// The folders a program may be named from: the system's, and the ones the installer fills itself.
// $EXEDIR, the folder the installer was started from, is the one to keep away from.
const programRoots = ['$SYSDIR\\', '$WINDIR\\', '$INSTDIR\\', '$PLUGINSDIR\\'];

function hasFullPath(program) {
  return programRoots.some(
    (root) =>
      program.length > root.length &&
      program.slice(0, root.length).toLowerCase() === root.toLowerCase(),
  );
}

// The script's lines, numbered. NSIS carries a line that ends in a backslash on to the next one.
function scriptLines(text) {
  const lines = [];
  let start = null;
  let joined = '';
  text.split(/\r?\n/).forEach((line, i) => {
    start ??= i + 1;
    if (line.endsWith('\\')) {
      joined += line.slice(0, -1);
      return;
    }
    lines.push({ number: start, text: joined + line });
    start = null;
    joined = '';
  });
  if (start != null) lines.push({ number: start, text: joined });
  return lines;
}

// One line split into its instruction and arguments, each unquoted. NSIS quotes with double quotes,
// single quotes or backticks; inside one, $\" (or $\' or $\`) is a quote and $$ a dollar sign. A
// comment line has none.
function nsisArgs(line) {
  const args = [];
  let rest = line.trim();
  while (rest !== '') {
    if (args.length === 0 && (rest[0] === ';' || rest[0] === '#')) return [];
    const quote = rest[0];
    if (quote === '"' || quote === "'" || quote === '`') {
      let arg = '';
      let i = 1;
      for (; i < rest.length && rest[i] !== quote; i++) {
        const next = rest.slice(i, i + 3);
        if (next.startsWith('$$')) {
          arg += '$$';
          i += 1;
        } else if (/^\$\\["'`]$/.test(next)) {
          arg += next[2];
          i += 2;
        } else {
          arg += rest[i];
        }
      }
      args.push(arg);
      rest = rest.slice(i + 1).trim();
    } else {
      const end = rest.search(/\s/);
      args.push(end < 0 ? rest : rest.slice(0, end));
      rest = end < 0 ? '' : rest.slice(end).trim();
    }
  }
  return args;
}

// The options an instruction takes before its arguments, such as /TIMEOUT= or /INVOKEIDLIST.
function withoutFlags(args) {
  const first = args.findIndex((arg) => !arg.startsWith('/'));
  return first < 0 ? [] : args.slice(first);
}

// A Windows command line's program, unquoted, and the rest of the line.
function splitCommandLine(line) {
  const trimmed = line.trim();
  if (trimmed.startsWith('"')) {
    const end = trimmed.indexOf('"', 1);
    return end < 0 ? [trimmed.slice(1), ''] : [trimmed.slice(1, end), trimmed.slice(end + 1)];
  }
  const end = trimmed.search(/\s/);
  return end < 0 ? [trimmed, ''] : [trimmed.slice(0, end), trimmed.slice(end)];
}

// The programs one line of script starts: the one an exec instruction names and, when that is cmd,
// each one in the command it runs.
function programsStarted(line) {
  const args = nsisArgs(line);
  if (args.length === 0) return [];
  const rest = withoutFlags(args.slice(1));
  switch (args[0].toLowerCase()) {
    case 'exec':
    case 'execwait':
    case 'nsexec::exec':
    case 'nsexec::exectolog':
    case 'nsexec::exectostack':
      return rest.length === 0 ? [] : withCmdCommands(...splitCommandLine(rest[0]));
    case 'execshell':
    case 'execshellwait':
      // The verb, then the file, then its parameters.
      return rest.length < 2 ? [] : withCmdCommands(rest[1], rest[2] ?? '');
    default:
      return [];
  }
}

// The program and, when it is cmd, the switches cmd is given up to its /c (or /k), the command
// after that, and every program in it. A cmd with no /c or /k leaves the command null.
function withCmdCommands(program, commandLine) {
  const name = program.slice(Math.max(program.lastIndexOf('\\'), program.lastIndexOf('/')) + 1);
  if (!/^cmd(\.exe)?$/i.test(name)) return [{ program }];
  const switches = [];
  let command = null;
  let rest = commandLine.trim();
  while (command == null && rest.startsWith('/')) {
    const [option, after] = splitCommandLine(rest);
    switches.push(option.toLowerCase());
    rest = after.trim();
    if (/^\/[ck]$/i.test(option)) command = rest;
  }
  const started = [{ program, switches, command }];
  if (command == null) return started;
  // cmd drops the first and last quote of a command that starts with one.
  const last = command.lastIndexOf('"');
  if (command.startsWith('"') && last > 0) {
    command = command.slice(1, last) + command.slice(last + 1);
  }
  for (const part of command.split(/[&|]/)) {
    if (part.trim() !== '') started.push(...withCmdCommands(...splitCommandLine(part)));
  }
  return started;
}

let programsFound = 0;
for (const name of readdirSync(buildResources).filter((n) => /\.ns[ih]$/i.test(n))) {
  for (const line of scriptLines(readFileSync(path.join(buildResources, name), 'utf8'))) {
    const where = `build-resources/${name}:${line.number}`;
    for (const { program, switches, command } of programsStarted(line.text)) {
      programsFound++;
      check(
        hasFullPath(program),
        `${where} starts "${program}" without its full path, so Windows could start a copy left in the installer's folder or the install folder instead. Name it from ${programRoots.join(', ')}.`,
      );
      check(
        switches == null || command != null,
        `${where} starts cmd with no /c command this check can read, so it can't vouch for the programs that cmd runs.`,
      );
    }
  }
}
// The installer edits the firewall on install and on uninstall, each time through cmd and netsh, so
// a check that found fewer than four programs isn't reading the script.
check(
  programsFound >= 4,
  `the installer check found only ${programsFound} programs started by the NSIS scripts in build-resources; is programsStarted still reading them?`,
);

// The reader has to keep seeing every way of starting a program, and a bare name has to stay bare.
const readerCases = [
  [
    String.raw`  nsExec::Exec 'cmd /c tasklist /FI "IMAGENAME eq x.exe" /NH | find /I "x.exe" >nul'`,
    ['cmd', 'tasklist', 'find'],
  ],
  [
    String.raw`nsExec::Exec '"$SYSDIR\cmd.exe" /d /c ""$SYSDIR\tasklist.exe" /NH | "$SYSDIR\find.exe" /I "x.exe" >nul"'`,
    ['$SYSDIR\\cmd.exe', '$SYSDIR\\tasklist.exe', '$SYSDIR\\find.exe'],
  ],
  [`nsExec::ExecToStack /TIMEOUT=5000 'taskkill /F /IM x.exe'`, ['taskkill']],
  [`ExecShellWait "runas" "cmd.exe" '/c netsh a & netsh b' SW_HIDE`, ['cmd.exe', 'netsh', 'netsh']],
  [
    String.raw`ExecShellWait "runas" "$SYSDIR\cmd.exe" '/d /c ""$SYSDIR\netsh.exe" a name="b c" && netsh d"' SW_HIDE`,
    ['$SYSDIR\\cmd.exe', '$SYSDIR\\netsh.exe', 'netsh'],
  ],
  [
    String.raw`ExecWait "$\"$SYSDIR\cmd.exe$\" /d /c $\"$SYSDIR\netsh.exe$\" a & netsh b"`,
    ['$SYSDIR\\cmd.exe', '$SYSDIR\\netsh.exe', 'netsh'],
  ],
  [
    String.raw`ExecShellWait "runas" "$SYSDIR\cmd.exe" \
      '/d /c netsh a' SW_HIDE`,
    ['$SYSDIR\\cmd.exe', 'netsh'],
  ],
  [`ExecShell /INVOKEIDLIST open "notepad.exe"`, ['notepad.exe']],
  [String.raw`ExecWait '"$PLUGINSDIR\setup.exe" /silent'`, ['$PLUGINSDIR\\setup.exe']],
  [String.raw`Exec '"$INSTDIR\app.exe"'`, ['$INSTDIR\\app.exe']],
  [`Exec powershell.exe`, ['powershell.exe']],
  [`; nsExec::Exec 'taskkill /F'`, []],
  [`DetailPrint "Exec cmd"`, []],
];
for (const [script, want] of readerCases) {
  const got = scriptLines(script).flatMap((l) => programsStarted(l.text).map((p) => p.program));
  check(
    JSON.stringify(got) === JSON.stringify(want),
    `the installer check reads ${JSON.stringify(script)} as starting ${JSON.stringify(got)}, not ${JSON.stringify(want)}.`,
  );
}
for (const bare of ['cmd', 'cmd.exe', '.\\cmd.exe', '$EXEDIR\\cmd.exe', '$CmdPath']) {
  check(!hasFullPath(bare), `the installer check takes "${bare}" for a full path.`);
}

// --- version-source parity -----------------------------------------------------------------------
// The app version has TWO independent readers: core/build.gradle.kts reads the ROOT package.json for
// the jar's Implementation-Version (and thus /health), while electron-builder reads app/package.json
// (the appDir in this two-package layout) for the installer name + latest.yml/updater feed. If they
// drift, the shipped build's /health and its updater identity disagree and a later real release can't
// cleanly supersede the mislabeled one. Assert they match so a one-sided bump fails CI, not release.
function pkgVersion(rel) {
  const p = path.join(repoRoot, rel);
  try {
    return JSON.parse(readFileSync(p, 'utf8')).version;
  } catch (e) {
    failures.push(`Could not read version from ${rel}: ${e.message}`);
    return null;
  }
}
const rootVersion = pkgVersion('package.json');
const appVersion = pkgVersion('app/package.json');
check(
  rootVersion != null && appVersion != null && rootVersion === appVersion,
  `version drift: root package.json is ${rootVersion} but app/package.json is ${appVersion} — bump BOTH (root drives the jar/health version, app/ drives the installer + updater feed).`,
);

// --- koffi layout cross-check (only when installed; CI installs app deps on windows) --------------
// If koffi resolved on this platform, assert the scoped @koromix package the asarUnpack glob targets
// actually exists — so a koffi upgrade that relocated koffi.node fails here rather than at runtime.
const appModules = path.join(repoRoot, 'app', 'node_modules');
const koffiDir = path.join(appModules, 'koffi');
if (existsSync(koffiDir)) {
  const koromixDir = path.join(appModules, '@koromix');
  const hasNativePkg =
    existsSync(koromixDir) &&
    readdirSync(koromixDir).some((d) => d.startsWith('koffi-'));
  check(
    hasNativePkg,
    'koffi is installed but app/node_modules/@koromix/koffi-* is missing — the asarUnpack `@koromix/**` glob would match nothing and the packaged Job Object would fall back to taskkill. A koffi upgrade may have relocated koffi.node.',
  );
}

if (failures.length > 0) {
  console.error('Packaging config check FAILED:\n' + failures.map((f) => `  - ${f}`).join('\n'));
  process.exit(1);
}
console.log('Packaging config check passed.');
