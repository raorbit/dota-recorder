// Builds the Java core artifacts that electron-builder packages:
//   core/build/libs/core.jar
//   core/build/jre-image/
//
// Keep this as the single root-level entry point so `npm run dist` cannot silently
// package stale/missing core resources.
import { existsSync, statSync } from 'node:fs';
import { spawnSync } from 'node:child_process';
import * as path from 'node:path';

const repoRoot = path.resolve(import.meta.dirname, '..');
const coreDir = path.join(repoRoot, 'core');

function runGradle(args) {
  const command = process.platform === 'win32' ? 'cmd.exe' : path.join(coreDir, 'gradlew');
  const commandArgs = process.platform === 'win32'
    ? ['/d', '/s', '/c', path.join(coreDir, 'gradlew.bat'), ...args]
    : args;
  const result = spawnSync(command, commandArgs, {
    cwd: coreDir,
    stdio: 'inherit',
  });
  if (result.status !== 0) {
    throw new Error(`Gradle ${args.join(' ')} failed with exit code ${result.status}`);
  }
}

function assertFile(file) {
  if (!existsSync(file) || statSync(file).size <= 0) {
    throw new Error(`Expected build output is missing or empty: ${file}`);
  }
}

function assertDir(dir) {
  if (!existsSync(dir) || !statSync(dir).isDirectory()) {
    throw new Error(`Expected build output directory is missing: ${dir}`);
  }
}

runGradle(['bootJar', 'jlinkImage']);

const jar = path.join(coreDir, 'build', 'libs', 'core.jar');
const jre = path.join(coreDir, 'build', 'jre-image');
const javaBin = path.join(jre, 'bin', process.platform === 'win32' ? 'javaw.exe' : 'java');

assertFile(jar);
assertDir(jre);
assertFile(javaBin);

console.log(`Core artifacts ready:\n  ${jar}\n  ${jre}`);
