import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import os from 'node:os';
import { pathToFileURL, fileURLToPath } from 'node:url';
import ts from 'typescript';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);
const SRC_DIR = path.resolve(__dirname, '../src');

const tmpOutDir = fs.mkdtempSync(path.join(os.tmpdir(), 'pmp-release-routing-tests-'));

function transpileRelative(relPath, outRelPath, transformSource = (s) => s) {
  const fullPath = path.join(SRC_DIR, relPath);
  let source = fs.readFileSync(fullPath, 'utf8');
  source = transformSource(source);
  const transpiled = ts.transpileModule(source, {
    compilerOptions: {
      module: ts.ModuleKind.ESNext,
      target: ts.ScriptTarget.ES2022,
      moduleResolution: ts.ModuleResolutionKind.Bundler
    }
  });
  const outPath = path.join(tmpOutDir, outRelPath);
  fs.mkdirSync(path.dirname(outPath), { recursive: true });
  fs.writeFileSync(outPath, transpiled.outputText, 'utf8');
  return outPath;
}

transpileRelative('config/appVersionContract.ts', 'config/appVersionContract.mjs');
const releaseServicePath = transpileRelative('services/ReleaseService.ts', 'services/ReleaseService.mjs', (s) =>
  s.replace("../config/appVersionContract", "../config/appVersionContract.mjs")
);
const routingPath = transpileRelative('utils/routing.ts', 'utils/routing.mjs', (s) =>
  s.replace("../config/appVersionContract", "../config/appVersionContract.mjs")
);

const ReleaseService = await import(pathToFileURL(releaseServicePath).href);
const Routing = await import(pathToFileURL(routingPath).href);

test.after(() => {
  fs.rmSync(tmpOutDir, { recursive: true, force: true });
});

test('ReleaseService: parses stable GitHub release with asset.digest and skips drafts/prereleases', () => {
  const draftRelease = {
    draft: true,
    prerelease: false,
    tag_name: 'v99999.0',
    assets: [{ name: 'pmp-paints-latest.apk', browser_download_url: 'https://github.com/ow7ab/ow7ab/releases/download/v99999.0/pmp-paints-latest.apk', size: 123456 }]
  };
  assert.equal(ReleaseService.parseGitHubRelease(draftRelease), null);

  const prerelease = {
    draft: false,
    prerelease: true,
    tag_name: 'v99998.0-beta',
    assets: [{ name: 'pmp-paints-latest.apk', browser_download_url: 'https://github.com/ow7ab/ow7ab/releases/download/v99998.0/pmp-paints-latest.apk', size: 123456 }]
  };
  assert.equal(ReleaseService.parseGitHubRelease(prerelease), null);

  const stableRelease = {
    draft: false,
    prerelease: false,
    tag_name: 'v95791.0',
    name: 'PMP PAINTS v95791.0 (Build 95791 • 624a52b9)',
    published_at: '2026-11-12T10:15:00Z',
    body: '### Release notes',
    assets: [
      {
        name: 'pmp-paints-latest.apk',
        browser_download_url: 'https://github.com/ow7ab/ow7ab/releases/download/v95791.0/pmp-paints-latest.apk',
        size: 32663762,
        digest: 'sha256:5445d7d226aeb1480f6b75499254cb2019d8d46daa2f2cea79bd80ee1de2e57c'
      }
    ]
  };
  const parsed = ReleaseService.parseGitHubRelease(stableRelease);
  assert.ok(parsed);
  assert.equal(parsed.versionCode, 95791);
  assert.equal(parsed.versionName, 'v95791.0');
  assert.equal(parsed.fileSizeBytes, 32663762);
  assert.equal(parsed.fileSizeMb, 31.15);
  assert.equal(parsed.apkSha256, '5445d7d226aeb1480f6b75499254cb2019d8d46daa2f2cea79bd80ee1de2e57c');
  assert.equal(parsed.isAvailable, true);
});

test('ReleaseService: never propagates 404 raw.githubusercontent.com APK links and picks highest stable release', () => {
  const fromLegacyManifest = ReleaseService.parseVersionJsonRecord(
    {
      versionCode: 17,
      versionName: 'v4.2',
      downloadUrl: 'https://raw.githubusercontent.com/ow7ab/ow7ab/main/public/pmp-paints-latest.apk',
      releaseStatus: 'PUBLISHED'
    },
    'legacy'
  );
  assert.ok(fromLegacyManifest);
  assert.equal(fromLegacyManifest.downloadUrl.includes('raw.githubusercontent.com/'), false);
  assert.equal(fromLegacyManifest.downloadUrl, 'https://github.com/ow7ab/ow7ab/releases/latest/download/pmp-paints-latest.apk');

  const newerGitHub = {
    ...fromLegacyManifest,
    versionCode: 95791,
    versionName: 'v95791.0',
    downloadUrl: 'https://github.com/ow7ab/ow7ab/releases/download/v95791.0/pmp-paints-latest.apk'
  };
  const failedStatus = ReleaseService.parseVersionJsonRecord(
    {
      versionCode: 999999,
      versionName: 'v999',
      downloadUrl: 'https://github.com/ow7ab/ow7ab/releases/download/v999/broken.apk',
      releaseStatus: 'FAILED'
    },
    'broken'
  );
  assert.equal(failedStatus, null);

  const chosen = ReleaseService.selectBestStableRelease([fromLegacyManifest, newerGitHub]);
  assert.ok(chosen);
  assert.equal(chosen.versionCode, 95791);
});

test('Routing: normalizes root and /ow7ab subpath routes cleanly without 404', () => {
  assert.equal(Routing.stripBasePath('/ow7ab/download', '/ow7ab/'), '/download');
  assert.equal(Routing.stripBasePath('/ow7ab/', '/ow7ab/'), '/');
  assert.equal(Routing.stripBasePath('/login', '/'), '/login');

  assert.equal(Routing.normalizeAppRoute('/'), '/');
  assert.equal(Routing.normalizeAppRoute('/login'), '/login');
  assert.equal(Routing.normalizeAppRoute('/download'), '/download');
  assert.equal(Routing.normalizeAppRoute('/dashboard'), '/dashboard');
  assert.equal(Routing.normalizeAppRoute('/admin'), '/admin');
  assert.equal(Routing.normalizeAppRoute('/employee'), '/employee');
  assert.equal(Routing.normalizeAppRoute('/unknown-random-route'), '/');
});
