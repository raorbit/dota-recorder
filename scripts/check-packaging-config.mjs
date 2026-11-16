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

// --- installer hooks: every program they start named by its full path, every cmd given /d --------
// Windows can find a program named without a path in the installer's own folder (Downloads, for a
// downloaded installer) or the folder it works in (the install folder, which the user can write to)
// before the system's. Anything the user ran could leave a cmd.exe or a netsh.exe there for the
// installer to start instead, and the firewall step starts both elevated. So everything the hooks
// in build-resources start, and everything a cmd line they start runs, has to be named by its full
// path. This ports the Go remake's guard (build/windows/installer_windows_test.go); this reader
// also reads the switches cmd is given before its /c, NSIS's comments, LogicLib's one-liners, and
// cmd's quotes, escapes, redirections, groups and own commands.
//
// Every cmd the hooks start also has to be given /d before its /c. Without it cmd first runs the
// AutoRun commands in the registry, and anything the user runs can set HKCU's. The firewall step's
// cmd runs elevated ("runas"), and in an install or uninstall for every user of the PC the hooks
// run elevated themselves, so any cmd they start could run those commands with administrator
// rights.
//
// The check reads Exec, ExecWait, ExecShell, ExecShellWait and nsExec::Exec, ExecToLog and
// ExecToStack, each on a line of its own or as the statement of a LogicLib one-liner (${IfThen},
// ${IfNotThen} or ${IfCmd}), with or without labels in front, and every command of a cmd /c (or
// /k) line. For ExecShell it checks the path of the file to open, not the program its type opens
// with, and lets a URL through, since Windows doesn't look a URL up on disk. It doesn't read a
// program started any other way: through a plugin, a !define, System::Call or a macro from
// outside build-resources (StdUtils' or UAC's). Nor does it read what a program other than cmd
// runs (a PowerShell command, say). A command run through cmd's call, start, if or for fails the
// check, since the check doesn't read it. So does a batch file (.cmd or .bat), however it is
// started: the check doesn't read the commands in it, and Windows runs one that an exec
// instruction starts in a cmd of its own, without /d.
//
// Anything the user runs can also set the user's environment variables, which the cmds the hooks
// start get. So a cmd line with a % in it fails, since cmd puts a %variable%'s value into the line
// before it reads it, and a & in that value adds a command, even inside quotes. So does an NSIS
// variable in a cmd line other than $SYSDIR and $WINDIR: NSIS fills it in as the installer runs,
// from places anything the user runs can set ($PLUGINSDIR and $TEMP from TEMP, $APPDATA and the
// like from the user's shell folders, $INSTDIR from the install folder), so it can bring a % into
// the line, and a register ($0, $R0) holds whatever the script put in it. So does a pipe
// into or out of one of cmd's own commands or a group, or into a command with an @ in front: cmd
// runs each of those in a cmd of its own, the program %COMSPEC% names.
//
// And it reads only the scripts in build-resources, where the hooks are, not the templates
// electron-builder makes the rest of the installer from, which aren't ours to change. Their check
// for a running app, for one, starts PowerShell without -NoProfile, or cmd without /d running
// tasklist and taskkill by bare names, and in an install or uninstall for every user it can run
// elevated.
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

// Windows runs a .cmd or .bat through cmd, even with dots or spaces after the name, which it drops.
function isBatchFile(program) {
  return /\.(bat|cmd)[. ]*$/i.test(program);
}

// The script's lines, numbered. NSIS carries a line that ends in a backslash (spaces after it
// don't count) on to the next one, without the next one's indent.
function scriptLines(text) {
  const lines = [];
  let start = null;
  let joined = '';
  text.split(/\r?\n/).forEach((line, i) => {
    let part = line.replace(/[ \t]+$/, '');
    if (start == null) start = i + 1;
    else part = part.replace(/^[ \t]+/, '');
    if (part.endsWith('\\')) {
      joined += part.slice(0, -1);
      return;
    }
    lines.push({ number: start, text: joined + part });
    start = null;
    joined = '';
  });
  if (start != null) lines.push({ number: start, text: joined });
  return lines;
}

// LogicLib's one-liners: ${IfThen} a == b ${|} statement ${|}, ${IfNotThen} the same, and
// ${IfCmd} statement ${||} statement ${|}. ${|} stands for ' and ${||} for ' ', and ${IfCmd}
// ends in a ${|} of its own, so each statement is a word in quotes, which NSIS later runs as a
// line. NSIS doesn't mind the case of a ${name}.
const oneLiner = /^\$\{(IfThen|IfNotThen|IfCmd)\}$/i;

function withOneLinersQuoted(line) {
  return line
    .replace(/\$\{IfCmd\}/gi, (ifCmd) => ifCmd + " '")
    .replaceAll('${||}', "' '")
    .replaceAll('${|}', "'");
}

// One line's words, without their quotes or comments. NSIS quotes a word with double quotes,
// single quotes or backticks, and $\" (or $\' or $\`) is a quote that doesn't end it; the word
// keeps it as written, since a one-liner's statement is only unescaped when it runs. A word that
// starts with ; or # comments out the rest of the line, and one that starts with /* everything up
// to the next */, even on a later line: `comment` carries that on from one line to the next.
function nsisWords(line, comment = { open: false }) {
  const words = [];
  let i = 0;
  while (i < line.length) {
    if (comment.open) {
      const end = line.indexOf('*/', i);
      if (end < 0) break;
      comment.open = false;
      i = end + 2;
    } else if (line[i] === ' ' || line[i] === '\t') {
      i++;
    } else if (line[i] === ';' || line[i] === '#') {
      break;
    } else if (line.startsWith('/*', i)) {
      comment.open = true;
      i += 2;
    } else {
      const quote = ['"', "'", '`'].includes(line[i]) ? line[i++] : null;
      let word = '';
      while (i < line.length && (quote == null ? !' \t'.includes(line[i]) : line[i] !== quote)) {
        const escape = /^\$\\["'`]/.test(line.slice(i, i + 3)) ? 3 : 1;
        word += line.slice(i, i + escape);
        i += escape;
      }
      if (quote != null) i++; // past the closing quote
      words.push(word);
    }
  }
  return words;
}

// A word as NSIS hands it to an instruction, each $\" (or $\' or $\`) a plain quote.
function unescaped(word) {
  return word.replace(/\$\\(["'`])/g, '$1');
}

// The script's instructions, each as its words and the number of the line it starts on.
function scriptInstructions(text) {
  const comment = { open: false };
  return scriptLines(text)
    .map(({ number, text: line }) => ({
      number,
      words: nsisWords(withOneLinersQuoted(line), comment),
    }))
    .filter(({ words }) => words.length > 0);
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

// A URL (https:, mailto:, ms-settings: ...) opens in whatever handles it, so Windows doesn't look
// it up on disk. Its scheme has two letters or more, which keeps C:\ a path.
function isUrl(target) {
  return /^[a-z][a-z\d+.-]+:/i.test(target);
}

// The programs one instruction starts: the one an exec instruction names and, when that is cmd,
// each one in the command it runs. A LogicLib one-liner starts what its statements start.
function programsStarted(line) {
  const words = withoutLabels(line);
  if (words.length === 0) return [];
  if (oneLiner.test(words[0])) {
    return words.slice(1).flatMap((statement) => programsStarted(nsisWords(statement)));
  }
  const rest = withoutFlags(words.slice(1).map(unescaped));
  switch (words[0].toLowerCase()) {
    case 'exec':
    case 'execwait':
    case 'nsexec::exec':
    case 'nsexec::exectolog':
    case 'nsexec::exectostack':
      return rest.length === 0 ? [] : withCmdCommands(...splitCommandLine(rest[0]));
    case 'execshell':
    case 'execshellwait':
      // The verb, then the file, then its parameters.
      return rest.length < 2 || isUrl(rest[1]) ? [] : withCmdCommands(rest[1], rest[2] ?? '');
    default:
      return [];
  }
}

// A line's words after its labels. NSIS takes each word at the start of a line that ends in a
// colon for a label (or refuses the line, if the word starts with ! $ - + or a digit), and reads
// the instruction after them.
function withoutLabels(words) {
  const first = words.findIndex((word) => !word.endsWith(':'));
  return first < 0 ? [] : words.slice(first);
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
  for (const { text, pipedIn, pipedOut, group } of cmdCommands(command)) {
    const starts = cmdCommandStarts(text);
    // cmd runs a side of a pipe that is a group or one of its own commands, and the right side
    // when it starts with an @, in a cmd of its own.
    if (
      (pipedIn || pipedOut) &&
      (group || starts.length === 0 || (pipedIn && /^[\s,;=]*@/.test(text)))
    ) {
      started.push({ piped: group ? '(...)' : text.trim() });
    }
    started.push(...starts);
  }
  return started;
}

// The commands in a cmd command line. cmd splits it at each &, &&, | and || and around a group in
// parentheses, but not at one in quotes, after a ^ (an escape, which cmd drops), or in a
// redirection to a handle such as 2>&1. Each command comes back without its escapes, which is how
// a cmd it starts would get it, and with whether a pipe (a lone |) comes before it or after it. A
// group on a side of a pipe comes back as a command of its own as well, marked as the group, with
// nothing in it but the group's redirections.
function cmdCommands(line) {
  const commands = [];
  let command = '';
  let quoted = false;
  let groups = 0; // parentheses open around the command
  let redirect = false; // the last character was a > or <, so a & names a handle
  let pipedIn = false; // a pipe comes before the command
  let group = false; // a group ended just before the command
  const end = (pipedOut) => {
    commands.push({ text: command, pipedIn, pipedOut, group });
    command = '';
    pipedIn = pipedOut;
    group = false;
  };
  for (let i = 0; i < line.length; i++) {
    const c = line[i];
    const handle = redirect && c === '&';
    redirect = false;
    if (c === '"' || quoted) {
      if (c === '"') quoted = !quoted;
      command += c;
    } else if (c === '^') {
      command += line[++i] ?? '';
    } else if ((c === '&' && !handle) || c === '|') {
      const pipe = c === '|' && line[i + 1] !== '|';
      if (line[i + 1] === c) i++;
      end(pipe);
    } else if (c === '(' && /^[\s@,;=]*$/.test(command)) {
      // Where a command's name would go, a ( opens a group.
      if (pipedIn) {
        group = true;
        end(false);
      }
      groups++;
      command = '';
    } else if (c === ')' && groups > 0) {
      groups--;
      end(false);
      group = true;
    } else {
      redirect = c === '>' || c === '<';
      command += c;
    }
  }
  end(false);
  return commands;
}

// cmd's own commands. cmd runs these itself rather than looking them up, so they need no path,
// but call, start, if and for run another command, which this check doesn't read.
const cmdOwnCommands = new Set(
  [
    'assoc break cd chdir cls color copy date del dir dpath echo endlocal erase exit ftype goto',
    'keys md mkdir mklink move path pause popd prompt pushd rd rem ren rename rmdir set setlocal',
    'shift time title type ver verify vol',
  ]
    .join(' ')
    .split(' '),
);
const cmdCommandRunners = new Set(['call', 'start', 'if', 'for']);

// What one command of a cmd line starts. cmd skips an @, and the separators , ; and =, before a
// command, and a redirection can come first too. It takes a name for one of its own commands even
// with one of ( / : + [ ] < > straight after it (echo( prints a blank line), but not in quotes.
// With a . or \ after the name it first looks for a file of that whole name in the folder it works
// in, and runs that if there is one: ver.exe, echo\x.exe, even echo. when that folder holds files
// named echo and echo..exe. So this check reads such a word as a program.
function cmdCommandStarts(command) {
  let rest = command;
  for (;;) {
    rest = rest.replace(/^[\s@,;=]+/, '');
    const redirection = /^\d?(>>?|<)(\s*&\s*\d|\s*"[^"]*"?|\s*[^\s"<>]*)/.exec(rest);
    if (redirection == null) break;
    rest = rest.slice(redirection[0].length);
  }
  if (rest === '') return [];
  const own = /^[a-z]+(?=$|[\s(;,=+[\]:/<>])/i.exec(rest)?.[0].toLowerCase();
  if (cmdCommandRunners.has(own)) return [{ runs: own }];
  if (cmdOwnCommands.has(own)) return [];
  return withCmdCommands(...splitCmdCommand(rest));
}

// A cmd command's program, unquoted, and the rest of the command. cmd ends a name that isn't in
// quotes at a space or at one of ; , = ( < > / after its first character.
function splitCmdCommand(command) {
  if (command.startsWith('"')) return splitCommandLine(command);
  const end = command.slice(1).search(/[\s;,=(<>/]/) + 1;
  return end > 0 ? [command.slice(0, end), command.slice(end)] : [command, ''];
}

// Every program a script starts, and every command cmd's call, start, if or for runs.
function programsIn(text) {
  return scriptInstructions(text).flatMap(({ words }) => programsStarted(words));
}

// The NSIS variables a cmd line holds that the installer fills in as it runs, each once, less the
// two the system sets. $$ is a $, $\ starts an escape, ${...} is filled in when the script is
// compiled and $(...) from the script's own language strings.
const fixedVariables = new Set(['SYSDIR', 'WINDIR']);

function nsisVariablesIn(command) {
  const variables = new Set();
  for (const [, name] of command.matchAll(/\$(\$|\\.|\{[^}]*\}|\([^)]*\)|[A-Za-z0-9_]+)/g)) {
    if (/^[A-Za-z0-9_]+$/.test(name) && !fixedVariables.has(name.toUpperCase())) {
      variables.add(`$${name}`);
    }
  }
  return [...variables];
}

// What breaks the rules in a script, each problem with its line's number, what it is, and the
// program (or cmd's own command) it is about. `programs` counts the programs the script starts.
function installerProblems(text) {
  const problems = [];
  let programs = 0;
  for (const { number, words } of scriptInstructions(text)) {
    for (const { program, switches, command, runs, piped } of programsStarted(words)) {
      if (runs != null) {
        problems.push({ number, problem: 'unread', name: runs });
        continue;
      }
      if (piped != null) {
        problems.push({ number, problem: 'pipe', name: piped });
        continue;
      }
      programs++;
      if (!hasFullPath(program)) problems.push({ number, problem: 'bare', name: program });
      if (isBatchFile(program)) problems.push({ number, problem: 'batch', name: program });
      if (switches != null && command == null) {
        problems.push({ number, problem: 'no /c', name: program });
      }
      if (switches != null && !switches.includes('/d')) {
        problems.push({ number, problem: 'no /d', name: program });
      }
      if (command?.includes('%')) problems.push({ number, problem: 'variable', name: program });
      for (const variable of command == null ? [] : nsisVariablesIn(command)) {
        problems.push({ number, problem: 'nsis variable', name: variable });
      }
    }
  }
  return { programs, problems };
}

const problemMessages = {
  bare: (program) =>
    `starts "${program}" without its full path, so Windows could start a copy left in the installer's folder or the install folder instead. Name it from ${programRoots.join(', ')}.`,
  batch: (file) =>
    `starts the batch file "${file}", whose commands this check doesn't read, and which Windows runs in a cmd without /d when an exec instruction starts it. Run those commands through "$SYSDIR\\cmd.exe" /d /c instead.`,
  'no /c': () =>
    `starts cmd with no /c command this check can read, so it can't vouch for the programs that cmd runs.`,
  'no /d': () =>
    `starts cmd without /d before its /c, so that cmd would first run the AutoRun commands in the registry, which anything the user runs can set, with its own rights (an administrator's, when elevated). Add /d.`,
  variable: () =>
    `starts cmd with a % in its command line. cmd puts a %variable%'s value, which anything the user runs can set, into the line before it reads it, so this check can't vouch for the programs the line runs. Leave % out of cmd lines.`,
  'nsis variable': (variable) =>
    `puts ${variable} into a cmd line. NSIS fills it in as the installer runs, from a place anything the user runs can set, so it can bring a % into the line, which cmd fills in before it reads it. Use only $SYSDIR and $WINDIR in cmd lines.`,
  pipe: (side) =>
    `pipes into or out of "${side}", which cmd runs in a cmd of its own: the program %COMSPEC% names, which anything the user runs can set. Pipe only between programs named by their full path, with no @ in front of one on the right of a pipe.`,
  unread: (command) =>
    `runs a command through cmd's ${command}, which this check doesn't read, so it can't vouch for the program that command starts. Start that program as a command of its own.`,
};

// The reader and the rules are checked first, on scripts whose programs and problems are known, so
// a change that stops the reader seeing a program, or makes it see one that isn't there, fails here
// before it can pass or fail the real scripts.
//
// The programs each script starts. The Go table's cases come first.
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
  // A comment can start after an instruction's first word too, with a ; or a #.
  [`ExecShell "open" ;"netsh.exe"`, []],
  [`ExecShell "open" #"netsh.exe"`, []],
  // cmd's escapes, redirections, groups and own commands (a quoted "ver" isn't one), where cmd ends
  // a name, and LogicLib's one-liners, whose statement can follow its ${|} with no space.
  [
    String.raw`ExecWait 'cmd /c echo a 2>&1 ^& b & (>nul netsh c) & @netsh d || "ver"'`,
    ['cmd', 'netsh', 'netsh', 'ver'],
  ],
  [
    String.raw`ExecWait 'cmd /c $SYSDIR\cmd.exe/d /c netsh>nul a ^& netsh,b'`,
    ['cmd', '$SYSDIR\\cmd.exe', 'netsh', 'netsh'],
  ],
  [
    '${IfThen} ${isUpdated} ${|} ExecShellWait "runas" "cmd.exe" "/c netsh a" SW_HIDE ${|}',
    ['cmd.exe', 'netsh'],
  ],
  ['${IfCmd} MessageBox MB_YESNO "Stop?" IDYES ${||}Exec "taskkill x"${|}', ['taskkill']],
];
for (const [script, want] of readerCases) {
  const got = programsIn(script).map((started) => started.program);
  check(
    JSON.stringify(got) === JSON.stringify(want),
    `the installer check reads ${JSON.stringify(script)} as starting ${JSON.stringify(got)}, not ${JSON.stringify(want)}.`,
  );
}
// A bare name has to stay bare.
for (const bare of ['cmd', 'cmd.exe', '.\\cmd.exe', '$EXEDIR\\cmd.exe', '$CmdPath']) {
  check(!hasFullPath(bare), `the installer check takes "${bare}" for a full path.`);
}
// cmd reads a /d after its /c as part of the command, so only one before it counts.
const autoRunCases = [
  [String.raw`ExecShellWait "runas" "$SYSDIR\cmd.exe" '/d /c "x"' SW_HIDE`, true],
  [String.raw`ExecShellWait "runas" "$SYSDIR\cmd.exe" '/S /D /C "x"' SW_HIDE`, true],
  [String.raw`ExecShellWait "runas" "$SYSDIR\cmd.exe" '/c "x"' SW_HIDE`, false],
  [String.raw`ExecShellWait "runas" "$SYSDIR\cmd.exe" '/c /d "x"' SW_HIDE`, false],
];
for (const [line, givenD] of autoRunCases) {
  check(
    programsIn(line)[0].switches.includes('/d') === givenD,
    `the installer check reads ${JSON.stringify(line)} as ${givenD ? 'not ' : ''}giving cmd /d.`,
  );
}
// Scripts the check must pass or fail, each with its problems as "line problem name".
const problemCases = [
  // cmd syntax that starts nothing more: a redirection, a & in quotes or after a ^, cmd's own
  // commands, and a group with a redirection in it.
  [
    String.raw`ExecShellWait "runas" "$SYSDIR\cmd.exe" '/d /c ""$SYSDIR\netsh.exe" a name="x & y" b=c^&d >>"$WINDIR\Temp\fw.log" 2>&1"' SW_HIDE`,
    [],
  ],
  [
    String.raw`ExecWait '"$SYSDIR\cmd.exe" /d /c ("$SYSDIR\netsh.exe" a & 2>"$WINDIR\Temp\fw log.txt" "$SYSDIR\netsh.exe" b & ver) 2>nul & exit /b 0 || ver >nul & @echo done'`,
    [],
  ],
  // An NSIS variable in a cmd line that the installer fills in from a place the user can set, or a
  // register, but not $SYSDIR or $WINDIR, a $$, a compile-time ${...} or a language string.
  [
    String.raw`ExecWait '"$SYSDIR\cmd.exe" /d /c ""$SYSDIR\netsh.exe" a >>"$PLUGINSDIR\fw.log" & "$INSTDIR\x.exe" b $0 $$c ${'${PRODUCT}'} $(Note) "$PLUGINSDIR\d""'`,
    ['1 nsis variable $PLUGINSDIR', '1 nsis variable $INSTDIR', '1 nsis variable $0'],
  ],
  // The characters after one of cmd's own names that still leave it cmd's own command.
  [
    String.raw`ExecWait '"$SYSDIR\cmd.exe" /d /c echo( & echo:a & echo+a & echo[a & echo]a & echo/a & echo;a & echo,a & echo=a & ver>nul & ver<nul'`,
    [],
  ],
  // A URL isn't looked up on disk, though a path on a drive is still a path. A comment isn't run,
  // nor is the line after a comment that ends in a backslash.
  [`ExecShell "open" "https://example.com/welcome"`, []],
  [String.raw`ExecShell "open" "C:\Tools\x.exe"`, ['1 bare C:\\Tools\\x.exe']],
  [
    [
      '/*',
      `  ExecWait 'cmd /c netsh a'`,
      '*/',
      String.raw`Exec /* "cmd" */ '"$INSTDIR\app.exe"' ; or cmd /c netsh`,
      '; a note \\',
      `ExecShellWait "runas" "cmd.exe" '/c netsh a' SW_HIDE`,
    ].join('\n'),
    [],
  ],
  // The firewall step's line before full paths and /d, and a bare name where one can hide: after a
  // ^, in quotes, behind a comment, and in a cmd the first one starts, which gets a & the first one
  // was given escaped.
  [
    `ExecShellWait "runas" "cmd.exe" '/c netsh a' SW_HIDE`,
    ['1 bare cmd.exe', '1 no /d cmd.exe', '1 bare netsh'],
  ],
  [
    String.raw`ExecWait '"$SYSDIR\cmd.exe" /d /c ne^tsh a & "ver" & "$SYSDIR\cmd.exe" /d /c "$SYSDIR\netsh.exe" b ^& netsh c'`,
    ['1 bare netsh', '1 bare ver', '1 bare netsh'],
  ],
  [String.raw`/* was: cmd */ ExecWait '"$SYSDIR\cmd.exe" /d /c netsh a'`, ['1 bare netsh']],
  // A /* in a # comment opens no comment of its own.
  [
    ['# was /* here', `ExecShellWait "runas" "cmd.exe" '/c netsh a' SW_HIDE`].join('\n'),
    ['2 bare cmd.exe', '2 no /d cmd.exe', '2 bare netsh'],
  ],
  // cmd /k (or /K) runs its command as /c does.
  [
    [
      String.raw`ExecWait '"$SYSDIR\cmd.exe" /d /k netsh a'`,
      String.raw`ExecWait '"$SYSDIR\cmd.exe" /S /D /K ""$SYSDIR\netsh.exe" b"'`,
    ].join('\n'),
    ['1 bare netsh'],
  ],
  // One of cmd's own names with a . or \ after it, which cmd looks up as a file first.
  [
    String.raw`ExecWait '"$SYSDIR\cmd.exe" /d /c ver.exe & echo. done & echo\tools\fw.exe & cd..'`,
    ['1 bare ver.exe', '1 bare echo.', '1 bare echo\\tools\\fw.exe', '1 bare cd..'],
  ],
  // A batch file, by any exec instruction or from a cmd line, in any case and with a dot after it.
  [
    [
      String.raw`ExecShellWait "runas" "$PLUGINSDIR\fw.cmd" "" SW_HIDE`,
      String.raw`ExecWait '"$INSTDIR\fw.BAT" a'`,
      String.raw`nsExec::ExecToLog '"$PLUGINSDIR\fw.cmd."'`,
      String.raw`ExecWait '"$SYSDIR\cmd.exe" /d /c ""$PLUGINSDIR\fw.cmd" & "$SYSDIR\netsh.exe" a"'`,
    ].join('\n'),
    [
      '1 batch $PLUGINSDIR\\fw.cmd',
      '2 batch $INSTDIR\\fw.BAT',
      '3 batch $PLUGINSDIR\\fw.cmd.',
      '4 nsis variable $PLUGINSDIR',
      '4 batch $PLUGINSDIR\\fw.cmd',
    ],
  ],
  // cmd's call, start, if and for run a command this check doesn't read.
  [
    String.raw`ExecWait '"$SYSDIR\cmd.exe" /d /c call x.cmd & start "" y & if exist z "$SYSDIR\netsh.exe" a & for %i in (a) do w'`,
    [
      '1 variable $SYSDIR\\cmd.exe',
      '1 unread call',
      '1 unread start',
      '1 unread if',
      '1 unread for',
    ],
  ],
  // A % in a cmd line, where cmd puts in a variable's value first, even in quotes, and a %% is no
  // escape on a /c line. A nested cmd gets the line with the % in it too.
  [
    [
      String.raw`ExecWait '"$SYSDIR\cmd.exe" /d /c ""$SYSDIR\netsh.exe" a 2>"%TEMP%\fw log.txt""'`,
      String.raw`ExecWait '"$SYSDIR\cmd.exe" /d /c "$SYSDIR\cmd.exe" /d /c echo 100%%'`,
    ].join('\n'),
    ['1 variable $SYSDIR\\cmd.exe', '2 variable $SYSDIR\\cmd.exe', '2 variable $SYSDIR\\cmd.exe'],
  ],
  // A pipe into or out of one of cmd's own commands or a group, or into a command with an @ in
  // front, but not one between programs (with an @ on the left, or redirections), nor a ||.
  [
    String.raw`ExecWait '"$SYSDIR\cmd.exe" /d /c echo y| "$SYSDIR\netsh.exe" a & "$SYSDIR\netsh.exe" b | ver & ("$SYSDIR\netsh.exe" c) 2>nul | "$SYSDIR\find.exe" d & "$SYSDIR\netsh.exe" e |("$SYSDIR\find.exe" f) & "$SYSDIR\netsh.exe" g | ,@"$SYSDIR\find.exe" h & @"$SYSDIR\netsh.exe" i 2>nul | 2>nul "$SYSDIR\find.exe" j || ver'`,
    [
      '1 pipe echo y',
      '1 pipe ver',
      '1 pipe (...)',
      '1 pipe (...)',
      '1 pipe ,@"$SYSDIR\\find.exe" h',
    ],
  ],
  // LogicLib's one-liners. A $\" stays escaped until the statement runs.
  [
    '${IfThen} ${isUpdated} ${|} ExecShellWait "runas" "cmd.exe" "/c netsh a" SW_HIDE ${|}',
    ['1 bare cmd.exe', '1 no /d cmd.exe', '1 bare netsh'],
  ],
  ['${IfNotThen} ${Silent} ${|}nsExec::Exec "taskkill /F /IM x.exe"${|}', ['1 bare taskkill']],
  // A one-liner's name in any case.
  [
    [
      '${ifThen} $0 == 1 ${|} Exec "cmd /c netsh a" ${|}',
      '${ifnotthen} $0 == 1 ${|} Exec "netsh b" ${|}',
      '${ifCmd} MessageBox MB_YESNO "Stop?" IDYES ${||}Exec "taskkill x"${|}',
    ].join('\n'),
    ['1 bare cmd', '1 no /d cmd', '1 bare netsh', '2 bare netsh', '3 bare taskkill'],
  ],
  // Labels in front of an instruction, of a one-liner and of a one-liner's statement.
  [
    [
      `open_fw: ExecShellWait "runas" "cmd.exe" '/c netsh a' SW_HIDE`,
      'a:\tb: Exec "netsh b"',
      'again: ${IfThen} $0 == 1 ${|} Exec "netsh c" ${|}',
      '${IfThen} $0 == 1 ${|} again: Exec "netsh d" ${|}',
      'done:',
    ].join('\n'),
    [
      '1 bare cmd.exe',
      '1 no /d cmd.exe',
      '1 bare netsh',
      '2 bare netsh',
      '3 bare netsh',
      '4 bare netsh',
    ],
  ],
  [
    '${IfThen} $0 == 1 ${|} ExecWait "$\\"$SYSDIR\\cmd.exe$\\" /c $\\"$SYSDIR\\netsh.exe$\\" a" ${|}',
    ['1 no /d $SYSDIR\\cmd.exe'],
  ],
  [
    '${IfCmd} MessageBox MB_YESNO "Stop it?" IDYES ${||} ExecWait `"$SYSDIR\\cmd.exe" /d /c netsh a` ${|}',
    ['1 bare netsh'],
  ],
  // A /* in quotes opens no comment. A line carried on past a backslash (with spaces after it, the
  // first time here) loses the next line's indent, even inside a name, and keeps its first line's
  // number. A document is looked up like a program.
  [
    [
      String.raw`ExecWait '"$SYSDIR\netsh.exe" /*'`,
      String.raw`ExecShellWait "runas" "$SYSDIR\cmd.exe" \  `,
      '    \'/c "$SYSDIR\\netsh.exe" a & net\\',
      String.raw`    sh b' SW_HIDE`,
      `ExecShell "open" "readme.txt"`,
    ].join('\n'),
    ['2 no /d $SYSDIR\\cmd.exe', '2 bare netsh', '5 bare readme.txt'],
  ],
];
for (const [script, want] of problemCases) {
  const got = installerProblems(script).problems.map((p) => `${p.number} ${p.problem} ${p.name}`);
  check(
    JSON.stringify(got) === JSON.stringify(want),
    `the installer check finds ${JSON.stringify(got)} in ${JSON.stringify(script)}, not ${JSON.stringify(want)}.`,
  );
}

let programsFound = 0;
for (const file of readdirSync(buildResources).filter((n) => /\.ns[ih]$/i.test(n))) {
  const { programs, problems } = installerProblems(
    readFileSync(path.join(buildResources, file), 'utf8'),
  );
  programsFound += programs;
  for (const { number, problem, name } of problems) {
    failures.push(`build-resources/${file}:${number} ${problemMessages[problem](name)}`);
  }
}
// The hooks edit the firewall on install and on uninstall, each time through cmd and netsh, so a
// check that found fewer than four programs isn't reading the script.
check(
  programsFound >= 4,
  `the installer check found only ${programsFound} programs started by the NSIS scripts in build-resources; is programsStarted still reading them?`,
);

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
