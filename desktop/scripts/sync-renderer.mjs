import { execFileSync } from 'child_process';
import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);
const DESKTOP_ROOT = path.resolve(__dirname, '..');
const REPO_ROOT = path.resolve(DESKTOP_ROOT, '..');
const WEB_DIR = path.join(REPO_ROOT, 'web');
const WEB_DIST_DIR = path.join(WEB_DIR, 'dist');
const RENDERER_DIST_DIR = path.join(DESKTOP_ROOT, 'renderer-dist');
const PUBLIC_ASSETS_DIR = path.join(REPO_ROOT, 'public', 'assets');
const WEB_PUBLIC_ASSETS_DIR = path.join(WEB_DIR, 'public', 'assets');

function copyDirRecursive(src, dst) {
  fs.mkdirSync(dst, { recursive: true });
  for (const entry of fs.readdirSync(src, { withFileTypes: true })) {
    const srcPath = path.join(src, entry.name);
    const dstPath = path.join(dst, entry.name);
    if (entry.isDirectory()) {
      copyDirRecursive(srcPath, dstPath);
    } else if (entry.isFile()) {
      fs.copyFileSync(srcPath, dstPath);
    }
  }
}

console.log('[Desktop Sync] Step 1/3: Ensuring official PMP Paints brand assets are synced to web/public/assets...');
if (fs.existsSync(PUBLIC_ASSETS_DIR)) {
  fs.mkdirSync(WEB_PUBLIC_ASSETS_DIR, { recursive: true });
  for (const file of fs.readdirSync(PUBLIC_ASSETS_DIR)) {
    const src = path.join(PUBLIC_ASSETS_DIR, file);
    if (fs.statSync(src).isFile()) {
      fs.copyFileSync(src, path.join(WEB_PUBLIC_ASSETS_DIR, file));
    }
  }
}

console.log('[Desktop Sync] Step 2/3: Building unified ERP web application (VITE_BASE_PATH=/)...');
const npmCmd = process.platform === 'win32' ? 'npm.cmd' : 'npm';
execFileSync(npmCmd, ['run', 'build'], {
  cwd: WEB_DIR,
  stdio: 'inherit',
  shell: process.platform === 'win32',
  env: {
    ...process.env,
    VITE_BASE_PATH: '/'
  }
});

if (!fs.existsSync(path.join(WEB_DIST_DIR, 'index.html'))) {
  throw new Error(`Expected built web bundle at ${WEB_DIST_DIR}/index.html`);
}

console.log('[Desktop Sync] Step 3/3: Copying web/dist into desktop/renderer-dist...');
fs.rmSync(RENDERER_DIST_DIR, { recursive: true, force: true });
copyDirRecursive(WEB_DIST_DIR, RENDERER_DIST_DIR);

console.log('[Desktop Sync] Completed successfully -> desktop/renderer-dist');
