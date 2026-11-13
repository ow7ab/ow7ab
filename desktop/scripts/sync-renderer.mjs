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
fs.mkdirSync(WEB_PUBLIC_ASSETS_DIR, { recursive: true });
const DRAWABLE_DIR = path.join(REPO_ROOT, 'app', 'src', 'main', 'res', 'drawable');
for (const assetName of [
  'ic_pmp_emblem.png',
  'ic_pmp_emblem_solid.png',
  'pmp_brand_logo.jpg',
  'pmp_brand_poster.jpg',
  'pmp_reference_poster.jpg'
]) {
  const fromDrawable = path.join(DRAWABLE_DIR, assetName);
  if (fs.existsSync(fromDrawable) && fs.statSync(fromDrawable).isFile()) {
    fs.copyFileSync(fromDrawable, path.join(WEB_PUBLIC_ASSETS_DIR, assetName));
  }
}
if (fs.existsSync(PUBLIC_ASSETS_DIR)) {
  for (const file of fs.readdirSync(PUBLIC_ASSETS_DIR)) {
    const src = path.join(PUBLIC_ASSETS_DIR, file);
    if (fs.statSync(src).isFile()) {
      fs.copyFileSync(src, path.join(WEB_PUBLIC_ASSETS_DIR, file));
    }
  }
}

console.log('[Desktop Sync] Step 2/3: Building unified ERP web application (VITE_BASE_PATH=/)...');
const tscBin = path.join(WEB_DIR, 'node_modules', 'typescript', 'bin', 'tsc');
const viteBin = path.join(WEB_DIR, 'node_modules', 'vite', 'bin', 'vite.js');

if (!fs.existsSync(tscBin) || !fs.existsSync(viteBin)) {
  throw new Error(`Web build tools missing under ${path.join(WEB_DIR, 'node_modules')}. Run "npm ci --prefix web" first.`);
}

execFileSync(process.execPath, [tscBin], {
  cwd: WEB_DIR,
  stdio: 'inherit'
});

execFileSync(process.execPath, [viteBin, 'build'], {
  cwd: WEB_DIR,
  stdio: 'inherit',
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
