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
const DRAWABLE_DIR = path.join(REPO_ROOT, 'app', 'src', 'main', 'res', 'drawable');
const DESKTOP_ASSETS_DIR = path.join(DESKTOP_ROOT, 'assets');

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

function buildPngWrappedIco(pngBuffer) {
  // Standard Windows Vista+ ICO container wrapping a 256x256 PNG (width=0 & height=0 signify 256px)
  const header = Buffer.alloc(22);
  header.writeUInt16LE(0, 0); // Reserved
  header.writeUInt16LE(1, 2); // Type: 1 = ICO
  header.writeUInt16LE(1, 4); // Image count: 1
  header.writeUInt8(0, 6); // Width (0 = 256)
  header.writeUInt8(0, 7); // Height (0 = 256)
  header.writeUInt8(0, 8); // Color count
  header.writeUInt8(0, 9); // Reserved
  header.writeUInt16LE(1, 10); // Color planes
  header.writeUInt16LE(32, 12); // Bits per pixel
  header.writeUInt32LE(pngBuffer.length, 14); // Image byte size
  header.writeUInt32LE(22, 18); // Image data offset
  return Buffer.concat([header, pngBuffer]);
}

function ensureDesktopAppIcons() {
  fs.mkdirSync(DESKTOP_ASSETS_DIR, { recursive: true });
  const emblemCandidates = [
    path.join(DRAWABLE_DIR, 'ic_pmp_emblem.png'),
    path.join(PUBLIC_ASSETS_DIR, 'ic_pmp_emblem.png'),
    path.join(WEB_PUBLIC_ASSETS_DIR, 'ic_pmp_emblem.png')
  ];
  const sourcePng = emblemCandidates.find((p) => fs.existsSync(p) && fs.statSync(p).isFile());

  const iconPng = path.join(DESKTOP_ASSETS_DIR, 'icon.png');
  const trayPng = path.join(DESKTOP_ASSETS_DIR, 'tray-icon.png');
  const iconIco = path.join(DESKTOP_ASSETS_DIR, 'icon.ico');
  const installerNsh = path.join(DESKTOP_ASSETS_DIR, 'installer.nsh');

  if (!fs.existsSync(iconPng) && sourcePng) {
    fs.copyFileSync(sourcePng, iconPng);
  }
  if (!fs.existsSync(trayPng) && (sourcePng || fs.existsSync(iconPng))) {
    fs.copyFileSync(sourcePng || iconPng, trayPng);
  }
  if (!fs.existsSync(iconIco) && fs.existsSync(iconPng)) {
    let generatedWithMagick = false;
    for (const cmd of ['magick', 'convert']) {
      try {
        execFileSync(cmd, [iconPng, '-background', 'none', '-define', 'icon:auto-resize=256,128,64,48,32,16', iconIco], {
          stdio: 'ignore'
        });
        if (fs.existsSync(iconIco) && fs.statSync(iconIco).size > 0) {
          generatedWithMagick = true;
          break;
        }
      } catch {
        // Fallback to pure Node PNG-in-ICO container below
      }
    }
    if (!generatedWithMagick) {
      const pngBytes = fs.readFileSync(sourcePng || iconPng);
      fs.writeFileSync(iconIco, buildPngWrappedIco(pngBytes));
    }
  }
  if (!fs.existsSync(installerNsh)) {
    fs.writeFileSync(
      installerNsh,
      [
        '; PMP PAINTS ERP — NSIS Installer Custom Hooks',
        '!macro customInstall',
        '  IfFileExists "$APPDATA\\pmp-paints-desktop\\*.*" 0 +2',
        '    DetailPrint "Preserving existing PMP PAINTS ERP user settings and preferences."',
        '!macroend',
        '!macro customUnInstall',
        '  DetailPrint "Uninstalling PMP PAINTS ERP application binaries while preserving local preferences."',
        '!macroend',
        ''
      ].join('\n'),
      'utf8'
    );
  }
}

console.log('[Desktop Sync] Step 1/4: Ensuring official PMP Paints brand assets are synced to web/public/assets...');
fs.mkdirSync(WEB_PUBLIC_ASSETS_DIR, { recursive: true });
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

console.log('[Desktop Sync] Step 2/4: Verifying desktop icons & installer resources (desktop/assets)...');
ensureDesktopAppIcons();

console.log('[Desktop Sync] Step 3/4: Building unified ERP web application (VITE_BASE_PATH=/)...');
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

console.log('[Desktop Sync] Step 4/4: Copying web/dist into desktop/renderer-dist...');
fs.rmSync(RENDERER_DIST_DIR, { recursive: true, force: true });
copyDirRecursive(WEB_DIST_DIR, RENDERER_DIST_DIR);

console.log('[Desktop Sync] Completed successfully -> desktop/renderer-dist');
