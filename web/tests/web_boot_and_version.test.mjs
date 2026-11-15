import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import os from 'node:os';
import { pathToFileURL, fileURLToPath } from 'node:url';
import ts from 'typescript';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);
const WEB_DIR = path.resolve(__dirname, '..');
const ROOT_DIR = path.resolve(WEB_DIR, '..');
const SRC_DIR = path.join(WEB_DIR, 'src');

const tmpOutDir = fs.mkdtempSync(path.join(os.tmpdir(), 'pmp-web-boot-version-tests-'));

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

const contractPath = transpileRelative('config/appVersionContract.ts', 'config/appVersionContract.mjs');
const safeStoragePath = transpileRelative('utils/safeStorage.ts', 'utils/safeStorage.mjs');
const releaseServicePath = transpileRelative('services/ReleaseService.ts', 'services/ReleaseService.mjs', (s) =>
  s.replace('../config/appVersionContract', '../config/appVersionContract.mjs')
);

const { APP_VERSION_CONTRACT } = await import(pathToFileURL(contractPath).href);
const SafeStorage = await import(pathToFileURL(safeStoragePath).href);
const ReleaseService = await import(pathToFileURL(releaseServicePath).href);

test.after(() => {
  fs.rmSync(tmpOutDir, { recursive: true, force: true });
});

test('Version synchronization: Android, Web, Desktop, and JSON manifests are aligned on v21.0 (Build 21)', () => {
  assert.equal(APP_VERSION_CONTRACT.CURRENT_VERSION_CODE, 21);
  assert.equal(APP_VERSION_CONTRACT.CURRENT_VERSION_NAME, '21.0');
  assert.equal(APP_VERSION_CONTRACT.VERSION_NAME, 'v21.0');
  assert.equal(APP_VERSION_CONTRACT.WINDOWS_VERSION_NAME, '21.0.0');

  assert.equal(ReleaseService.DEFAULT_STABLE_RELEASE.versionCode, 21);
  assert.equal(ReleaseService.DEFAULT_STABLE_RELEASE.versionName, 'v21.0');
  assert.equal(ReleaseService.DEFAULT_DESKTOP_RELEASE.versionName, 'v21.0.0');

  const rootPkg = JSON.parse(fs.readFileSync(path.join(ROOT_DIR, 'package.json'), 'utf8'));
  const webPkg = JSON.parse(fs.readFileSync(path.join(WEB_DIR, 'package.json'), 'utf8'));
  const desktopPkg = JSON.parse(fs.readFileSync(path.join(ROOT_DIR, 'desktop/package.json'), 'utf8'));

  assert.equal(rootPkg.version, '21.0.0');
  assert.equal(webPkg.version, '21.0.0');
  assert.equal(desktopPkg.version, '21.0.0');

  for (const rel of ['version.json', 'public/version.json', 'web/public/version.json']) {
    const manifest = JSON.parse(fs.readFileSync(path.join(ROOT_DIR, rel), 'utf8'));
    assert.equal(manifest.versionCode, 21, `${rel} versionCode must be 21`);
    assert.equal(manifest.versionName, 'v21.0', `${rel} versionName must be v21.0`);
    assert.equal(manifest.buildId, 'PMP-v21.0-b21', `${rel} buildId must be PMP-v21.0-b21`);
  }

  for (const rel of ['public/releases-history.json', 'web/public/releases-history.json']) {
    const history = JSON.parse(fs.readFileSync(path.join(ROOT_DIR, rel), 'utf8'));
    const entries = Array.isArray(history) ? history : history.releases;
    assert.ok(Array.isArray(entries) && entries.length > 0, `${rel} must contain a non-empty releases array`);
    assert.equal(entries[0].versionCode, 21, `${rel} latest entry must be versionCode 21`);
    assert.equal(entries[0].versionName, 'v21.0', `${rel} latest entry must be v21.0`);
  }

  const heroSource = fs.readFileSync(path.join(SRC_DIR, 'components/sections/HeroSection.tsx'), 'utf8');
  assert.equal(
    heroSource.includes('الإصدار 20.0 المعتمد'),
    false,
    'HeroSection.tsx must not hardcode stale version 20.0'
  );
  assert.ok(heroSource.includes('APP_VERSION_CONTRACT'), 'HeroSection.tsx must reference APP_VERSION_CONTRACT');
});

test('Boot resilience: safeStorage handles SecurityError when globalThis.localStorage throws', () => {
  const originalDescriptor = Object.getOwnPropertyDescriptor(globalThis, 'localStorage');
  Object.defineProperty(globalThis, 'localStorage', {
    configurable: true,
    get() {
      throw new DOMException('Access to localStorage is denied in sandboxed origin', 'SecurityError');
    }
  });

  try {
    assert.equal(SafeStorage.safeStorageGet('pmp_web_theme'), null);
    assert.doesNotThrow(() => {
      SafeStorage.safeStorageSet('pmp_web_theme', 'dark');
      SafeStorage.safeStorageSet('pmp_web_lang', 'ar');
    });
    assert.equal(SafeStorage.safeStorageGet('pmp_web_theme'), 'dark');
    assert.equal(SafeStorage.safeStorageGet('pmp_web_lang'), 'ar');
    SafeStorage.safeStorageRemove('pmp_web_theme');
    assert.equal(SafeStorage.safeStorageGet('pmp_web_theme'), null);
  } finally {
    if (originalDescriptor) {
      Object.defineProperty(globalThis, 'localStorage', originalDescriptor);
    } else {
      delete globalThis.localStorage;
    }
  }
});

test('Boot resilience: web/index.html, ErrorBoundary, and compiled web/dist assets prevent blank black-and-red screen', () => {
  const indexHtml = fs.readFileSync(path.join(WEB_DIR, 'index.html'), 'utf8');
  assert.ok(indexHtml.includes('pmp-initial-loader'), 'web/index.html must include initial loader shell');
  assert.ok(indexHtml.includes('__PMP_BOOT_OK'), 'web/index.html must include boot watchdog guard');

  const mainTsx = fs.readFileSync(path.join(SRC_DIR, 'main.tsx'), 'utf8');
  assert.ok(mainTsx.includes('<ErrorBoundary>'), 'main.tsx must wrap App in ErrorBoundary');
  assert.ok(mainTsx.includes('__PMP_BOOT_OK'), 'main.tsx must mark window.__PMP_BOOT_OK on mount');

  const errorBoundaryTsx = fs.readFileSync(path.join(SRC_DIR, 'components/ui/ErrorBoundary.tsx'), 'utf8');
  assert.ok(errorBoundaryTsx.includes('getDerivedStateFromError'), 'ErrorBoundary must implement getDerivedStateFromError');

  const distIndexPath = path.join(WEB_DIR, 'dist/index.html');
  assert.ok(fs.existsSync(distIndexPath), 'web/dist/index.html must exist after web build');
  const distHtml = fs.readFileSync(distIndexPath, 'utf8');
  assert.ok(distHtml.includes('pmp-initial-loader'), 'Built web/dist/index.html must contain initial loader');

  const scriptMatches = [...distHtml.matchAll(/src="(\/assets\/[^"]+\.js)"/g)].map((m) => m[1]);
  assert.ok(scriptMatches.length > 0, 'Built web/dist/index.html must reference built root-relative JS module(s)');
  for (const assetUrl of scriptMatches) {
    const assetDiskPath = path.join(WEB_DIR, 'dist', assetUrl.replace(/^\//, ''));
    assert.ok(fs.existsSync(assetDiskPath), `Referenced JS asset must exist on disk: ${assetDiskPath}`);
    assert.ok(fs.statSync(assetDiskPath).size > 1000, `JS bundle must not be empty: ${assetDiskPath}`);
  }

  const cssMatches = [...distHtml.matchAll(/href="(\/assets\/[^"]+\.css)"/g)].map((m) => m[1]);
  assert.ok(cssMatches.length > 0, 'Built web/dist/index.html must reference built CSS asset(s)');
  for (const cssUrl of cssMatches) {
    const cssDiskPath = path.join(WEB_DIR, 'dist', cssUrl.replace(/^\//, ''));
    assert.ok(fs.existsSync(cssDiskPath), `Referenced CSS asset must exist on disk: ${cssDiskPath}`);
  }
});

test('Web Server: serves root, SPA routes, version.json (v21.0), and compiled JS/CSS/image assets with 200 OK', async () => {
  const { spawn } = await import('node:child_process');
  const http = await import('node:http');
  const port = 39127;

  const child = spawn(process.execPath, [path.join(WEB_DIR, 'server.mjs')], {
    env: { ...process.env, PORT: String(port) },
    stdio: ['ignore', 'pipe', 'pipe']
  });

  const waitForReady = () =>
    new Promise((resolve, reject) => {
      const timeout = setTimeout(() => reject(new Error('web/server.mjs startup timeout')), 5000);
      child.stdout.on('data', (chunk) => {
        if (String(chunk).includes('listening on')) {
          clearTimeout(timeout);
          resolve();
        }
      });
      child.stderr.on('data', (chunk) => {
        const msg = String(chunk);
        if (msg.trim()) {
          clearTimeout(timeout);
          reject(new Error(msg));
        }
      });
      child.on('exit', (code) => {
        clearTimeout(timeout);
        reject(new Error(`web/server.mjs exited early with code ${code}`));
      });
    });

  const httpGet = (urlPath) =>
    new Promise((resolve, reject) => {
      http
        .get(`http://127.0.0.1:${port}${urlPath}`, (res) => {
          const chunks = [];
          res.on('data', (c) => chunks.push(c));
          res.on('end', () =>
            resolve({
              status: res.statusCode,
              headers: res.headers,
              body: Buffer.concat(chunks).toString('utf8')
            })
          );
        })
        .on('error', reject);
    });

  try {
    await waitForReady();
    const rootRes = await httpGet('/');
    assert.equal(rootRes.status, 200);
    assert.ok(rootRes.body.includes('PMP PAINTS ERP'));

    const loginRes = await httpGet('/login');
    assert.equal(loginRes.status, 200);
    assert.ok(loginRes.body.includes('pmp-initial-loader'));

    const downloadRes = await httpGet('/download');
    assert.equal(downloadRes.status, 200);

    const versionRes = await httpGet('/version.json');
    assert.equal(versionRes.status, 200);
    const parsedVersion = JSON.parse(versionRes.body);
    assert.equal(parsedVersion.versionCode, 21);
    assert.equal(parsedVersion.versionName, 'v21.0');

    const emblemRes = await httpGet('/assets/ic_pmp_emblem.png');
    assert.equal(emblemRes.status, 200);
    assert.equal(emblemRes.headers['content-type'], 'image/png');
  } finally {
    child.kill('SIGTERM');
  }
});
