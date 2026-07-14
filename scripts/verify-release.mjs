// Preflight for a GitHub release upload (`npm run verify:release`, run after `npm run dist`).
//
// electron-updater resolves updates through latest.yml, which records electron-builder's
// *safeArtifactName* — if the on-disk installer name ever diverges from it (e.g. a spaced
// productName leaking into artifactName), the updater 404s against the release asset. This
// script turns that class of mistake into a hard failure before anything is uploaded:
//   - latest.yml, the installer .exe it names, and the .exe.blockmap all exist in release/
//   - the artifact name is space-free and carries the release version
//   - the sha512 + size in latest.yml match the actual installer bytes (same-build guarantee)
//   - the version is in lockstep with root + app package.json
//   - (best-effort, needs gh + network) the version is newer than the latest published release
// On success it prints the exact `gh release create` command with all three artifacts.
import { createHash } from 'node:crypto';
import { createReadStream, existsSync, readFileSync, statSync } from 'node:fs';
import { spawnSync } from 'node:child_process';
import * as path from 'node:path';

const repoRoot = path.resolve(import.meta.dirname, '..');
// Optional argument: verify artifacts in a different directory (default: release/).
const releaseDir = path.resolve(repoRoot, process.argv[2] ?? 'release');
const latestYmlPath = path.join(releaseDir, 'latest.yml');

const failures = [];
const fail = (message) => failures.push(message);

function parseLatestYml(text) {
  const version = text.match(/^version:\s*(\S+)/m)?.[1];
  const topPath = text.match(/^path:\s*(.+?)\s*$/m)?.[1];
  const topSha512 = text.match(/^sha512:\s*(\S+)/m)?.[1];
  const files = [];
  let current = null;
  for (const line of text.split(/\r?\n/)) {
    const url = line.match(/^\s+-\s+url:\s*(.+?)\s*$/)?.[1];
    if (url) {
      current = { url, sha512: undefined, size: undefined };
      files.push(current);
      continue;
    }
    if (!current) continue;
    const sha = line.match(/^\s+sha512:\s*(\S+)/)?.[1];
    if (sha) current.sha512 = sha;
    const size = line.match(/^\s+size:\s*(\d+)/)?.[1];
    if (size) current.size = Number(size);
  }
  return { version, topPath, topSha512, files };
}

function sha512Base64(file) {
  return new Promise((resolve, reject) => {
    const hash = createHash('sha512');
    createReadStream(file)
      .on('error', reject)
      .on('data', (chunk) => hash.update(chunk))
      .on('end', () => resolve(hash.digest('base64')));
  });
}

function packageVersion(pkgPath) {
  return JSON.parse(readFileSync(pkgPath, 'utf8')).version;
}

// Same source of truth as the updater feed: the publish block in electron-builder.yml.
function publishRepo() {
  const yml = readFileSync(path.join(repoRoot, 'electron-builder.yml'), 'utf8');
  const owner = yml.match(/^\s*owner:\s*(\S+)/m)?.[1];
  const repo = yml.match(/^\s*repo:\s*(\S+)/m)?.[1];
  if (!owner || !repo) throw new Error('electron-builder.yml is missing the publish owner/repo');
  return `${owner}/${repo}`;
}

function semverParts(v) {
  return v.replace(/^v/, '').split('.').map(Number);
}

function semverGreater(a, b) {
  const [pa, pb] = [semverParts(a), semverParts(b)];
  for (let i = 0; i < Math.max(pa.length, pb.length); i++) {
    const [x, y] = [pa[i] ?? 0, pb[i] ?? 0];
    if (x !== y) return x > y;
  }
  return false;
}

if (!existsSync(latestYmlPath)) {
  throw new Error(`Missing ${latestYmlPath} — run \`npm run dist\` first`);
}

const { version, topPath, topSha512, files } = parseLatestYml(readFileSync(latestYmlPath, 'utf8'));
if (!version || !/^\d+\.\d+\.\d+$/.test(version))
  fail(`latest.yml has no parseable version (got: ${version})`);
if (files.length === 0) fail('latest.yml lists no files');

const exe = files.find((f) => f.url.endsWith('.exe'));
if (!exe) {
  fail('latest.yml lists no .exe artifact');
} else {
  if (/\s/.test(exe.url))
    fail(`artifact name contains whitespace (breaks GitHub asset matching): '${exe.url}'`);
  if (version && !exe.url.includes(version))
    fail(`artifact name '${exe.url}' does not carry version ${version}`);
  if (topPath !== exe.url) fail(`latest.yml top-level path '${topPath}' != files url '${exe.url}'`);
  if (topSha512 !== exe.sha512) fail('latest.yml top-level sha512 != files sha512');

  const exePath = path.join(releaseDir, exe.url);
  const blockmapPath = `${exePath}.blockmap`;
  if (!existsSync(exePath)) {
    fail(`installer named by latest.yml is missing on disk: ${exePath}`);
  } else {
    const actualSize = statSync(exePath).size;
    if (actualSize !== exe.size) {
      fail(
        `size mismatch for ${exe.url}: latest.yml says ${exe.size}, disk has ${actualSize} — artifacts are from different builds`,
      );
    }
    const actualSha = await sha512Base64(exePath);
    if (actualSha !== exe.sha512) {
      fail(
        `sha512 mismatch for ${exe.url} — latest.yml and the installer are from different builds`,
      );
    }
  }
  if (!existsSync(blockmapPath) || statSync(blockmapPath).size <= 0) {
    fail(
      `missing or empty blockmap (differential updates degrade to full downloads): ${blockmapPath}`,
    );
  }
}

for (const pkg of ['package.json', 'app/package.json']) {
  const v = packageVersion(path.join(repoRoot, pkg));
  if (version && v !== version)
    fail(
      `${pkg} version ${v} != latest.yml version ${version} — bump both package.json files together`,
    );
}

const repoSlug = publishRepo();
const gh = spawnSync('gh', ['api', `repos/${repoSlug}/releases/latest`, '--jq', '.tag_name'], {
  encoding: 'utf8',
  shell: process.platform === 'win32',
});
if (gh.status === 0 && gh.stdout.trim()) {
  const publishedTag = gh.stdout.trim();
  if (version && !semverGreater(version, publishedTag)) {
    fail(`version ${version} is not newer than the latest published release ${publishedTag}`);
  } else {
    console.log(`Published latest is ${publishedTag}; ${version} is newer. OK`);
  }
} else {
  console.warn(
    'WARN: could not query the latest published release (gh missing/offline?) — skipping the version-ordering check',
  );
}

if (failures.length > 0) {
  console.error(`\nRelease verification FAILED (${failures.length}):`);
  for (const f of failures) console.error(`  - ${f}`);
  process.exit(1);
}

console.log(`\nRelease ${version} verified. Upload all three artifacts from this build:\n`);
console.log(
  `  gh release create v${version} ${['', '.blockmap']
    .map((suffix) => `"release/${exe.url}${suffix}"`)
    .join(' ')} "release/latest.yml" --title "v${version}" --notes "..."`,
);
