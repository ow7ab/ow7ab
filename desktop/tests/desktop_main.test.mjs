import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import http from 'node:http';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { createRequire } from 'node:module';

const require = createRequire(import.meta.url);
const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);
const DESKTOP_ROOT = path.resolve(__dirname, '..');

const security = require(path.join(DESKTOP_ROOT, 'src', 'security.cjs'));
const { startLoopbackRendererServer } = require(path.join(DESKTOP_ROOT, 'src', 'loopbackServer.cjs'));

function rawHttpGet(port, rawPath) {
  return new Promise((resolve, reject) => {
    const req = http.request({ host: '127.0.0.1', port, path: rawPath, method: 'GET' }, (res) => {
      const chunks = [];
      res.on('data', (chunk) => chunks.push(chunk));
      res.on('end', () => {
        resolve({
          status: res.statusCode,
          headers: res.headers,
          body: Buffer.concat(chunks)
        });
      });
    });
    req.on('error', reject);
    req.end();
  });
}

test('Desktop Security: blocks path traversal while allowing clean renderer assets', () => {
  const root = path.join(DESKTOP_ROOT, 'renderer-dist');
  const home = security.resolveSafeRendererPath(root, '/');
  assert.equal(home, path.join(root, 'index.html'));

  const asset = security.resolveSafeRendererPath(root, '/assets/ic_pmp_emblem.png?v=1');
  assert.equal(asset, path.join(root, 'assets', 'ic_pmp_emblem.png'));

  assert.equal(security.resolveSafeRendererPath(root, '/../../etc/passwd'), null);
  assert.equal(security.resolveSafeRendererPath(root, '/..\\..\\Windows\\win.ini'), null);
  assert.equal(security.resolveSafeRendererPath(root, '/%2e%2e/%2e%2e/secret'), null);
  assert.equal(security.resolveSafeRendererPath(root, '/assets/test\0.png'), null);
});

test('Desktop Security: enforces strict loopback origin, Firebase Auth popups, and external URLs', () => {
  const origin = 'http://localhost:47821';
  assert.equal(security.isTrustedLoopbackOrigin('http://localhost:47821/dashboard', origin), true);
  assert.equal(security.isTrustedLoopbackOrigin('http://localhost:47822/dashboard', origin), false);
  assert.equal(security.isTrustedLoopbackOrigin('https://evil.example.com', origin), false);

  assert.equal(security.isAllowedAuthPopupUrl('https://accounts.google.com/o/oauth2/v2/auth'), true);
  assert.equal(security.isAllowedAuthPopupUrl('https://gen-lang-client-0220128746.firebaseapp.com/__/auth/handler'), true);
  assert.equal(security.isAllowedAuthPopupUrl('http://accounts.google.com/'), false);
  assert.equal(security.isAllowedAuthPopupUrl('https://phishing.invalid/login'), false);

  assert.equal(security.isAllowedExternalUrl('https://github.com/ow7ab/ow7ab/releases'), true);
  assert.equal(security.isAllowedExternalUrl('javascript:alert(1)'), false);
  assert.equal(security.isAllowedExternalUrl('file:///C:/Windows/System32/cmd.exe'), false);
});

test('Desktop Window Bounds: clamps off-screen or undersized windows inside active display workArea', () => {
  const workArea = { x: 0, y: 0, width: 1920, height: 1040 };
  const valid = security.clampWindowBounds({ x: 100, y: 80, width: 1400, height: 900, isMaximized: false }, workArea);
  assert.deepEqual(valid, { x: 100, y: 80, width: 1400, height: 900, isMaximized: false });

  const offscreen = security.clampWindowBounds({ x: -5000, y: 5000, width: 400, height: 300, isMaximized: true }, workArea);
  assert.equal(offscreen.width, 1024);
  assert.equal(offscreen.height, 680);
  assert.equal(offscreen.isMaximized, true);
  assert.ok(offscreen.x >= 0 && offscreen.y >= 0);
});

test('Desktop Print & Update Isolation: strips auto window.print() and rejects Android .apk assets', () => {
  const rawHtml = '<html><body><h1>Report</h1><script type="text/javascript">window.onload = function() { window.print(); };</script></body></html>';
  const cleaned = security.sanitizePrintHtmlDocument(rawHtml);
  assert.equal(cleaned.includes('window.print()'), false);
  assert.equal(cleaned.includes('<h1>Report</h1>'), true);

  assert.equal(security.isValidDesktopReleaseAssetName('pmp-paints-desktop-setup-18.0.0-x64.exe'), true);
  assert.equal(security.isValidDesktopReleaseAssetName('latest.yml'), true);
  assert.equal(security.isValidDesktopReleaseAssetName('pmp-paints-latest.apk'), false);
});

test('Desktop Assets & Hardening: verifies official icons and secure BrowserWindow flags', () => {
  const icoPath = path.join(DESKTOP_ROOT, 'build', 'icon.ico');
  assert.ok(fs.existsSync(icoPath));
  assert.ok(fs.existsSync(path.join(DESKTOP_ROOT, 'build', 'icon.png')));
  assert.ok(fs.existsSync(path.join(DESKTOP_ROOT, 'build', 'tray-icon.png')));
  const icoBytes = fs.readFileSync(icoPath);
  assert.ok(icoBytes.length > 22);
  assert.equal(icoBytes.readUInt16LE(0), 0);
  assert.equal(icoBytes.readUInt16LE(2), 1);

  const mainSource = fs.readFileSync(path.join(DESKTOP_ROOT, 'src', 'main.cjs'), 'utf8');
  assert.ok(mainSource.includes('contextIsolation: true'));
  assert.ok(mainSource.includes('nodeIntegration: false'));
  assert.ok(mainSource.includes('sandbox: true'));
  assert.ok(mainSource.includes('requestSingleInstanceLock'));

  const preloadSource = fs.readFileSync(path.join(DESKTOP_ROOT, 'src', 'preload.cjs'), 'utf8');
  assert.ok(preloadSource.includes("contextBridge.exposeInMainWorld('pmpDesktop'"));
});

test('Desktop Loopback Server: serves ERP SPA routes, official PNG asset, CSP headers, and blocks traversal', async () => {
  const rendererDistDir = path.join(DESKTOP_ROOT, 'renderer-dist');
  const { server, port, origin } = await startLoopbackRendererServer({ rendererDistDir, preferredPort: 0 });
  try {
    assert.ok(origin.startsWith('http://localhost:'));

    const homeRes = await rawHttpGet(port, '/');
    assert.equal(homeRes.status, 200);
    assert.ok(String(homeRes.headers['content-type']).includes('text/html'));
    assert.ok(String(homeRes.headers['content-security-policy']).includes(`default-src 'self' ${origin}`));
    assert.ok(homeRes.body.toString('utf8').includes('PMP PAINTS'));

    const spaRouteRes = await rawHttpGet(port, '/dashboard');
    assert.equal(spaRouteRes.status, 200);
    assert.ok(String(spaRouteRes.headers['content-type']).includes('text/html'));

    const assetRes = await rawHttpGet(port, '/assets/ic_pmp_emblem.png');
    assert.equal(assetRes.status, 200);
    assert.equal(assetRes.headers['content-type'], 'image/png');
    assert.ok(assetRes.body.length > 1000);

    const traversalRes = await rawHttpGet(port, '/../../etc/passwd');
    assert.equal(traversalRes.status, 403);

    const missingAssetRes = await rawHttpGet(port, '/assets/does-not-exist-123.js');
    assert.equal(missingAssetRes.status, 404);
  } finally {
    await new Promise((resolve) => server.close(resolve));
  }
});
