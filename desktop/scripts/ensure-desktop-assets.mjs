import { execFileSync } from 'child_process';
import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);
const DESKTOP_ROOT = path.resolve(__dirname, '..');
const REPO_ROOT = path.resolve(DESKTOP_ROOT, '..');

const DESKTOP_BUILD_DIR = path.join(DESKTOP_ROOT, 'build');
const PUBLIC_ASSETS_DIR = path.join(REPO_ROOT, 'public', 'assets');
const DRAWABLE_DIR = path.join(REPO_ROOT, 'app', 'src', 'main', 'res', 'drawable');
const WEB_PUBLIC_ASSETS_DIR = path.join(REPO_ROOT, 'web', 'public', 'assets');

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

export function ensureDesktopBuildAssets() {
  fs.mkdirSync(DESKTOP_BUILD_DIR, { recursive: true });

  const emblemCandidates = [
    path.join(PUBLIC_ASSETS_DIR, 'ic_pmp_emblem.png'),
    path.join(DRAWABLE_DIR, 'ic_pmp_emblem.png'),
    path.join(WEB_PUBLIC_ASSETS_DIR, 'ic_pmp_emblem.png')
  ];
  const sourcePng = emblemCandidates.find((p) => fs.existsSync(p) && fs.statSync(p).isFile());
  if (!sourcePng) {
    throw new Error(
      `Cannot provision desktop/build/icon.ico: missing brand emblem PNG in ${emblemCandidates.join(', ')}`
    );
  }

  const iconPng = path.join(DESKTOP_BUILD_DIR, 'icon.png');
  const trayPng = path.join(DESKTOP_BUILD_DIR, 'tray-icon.png');
  const iconIco = path.join(DESKTOP_BUILD_DIR, 'icon.ico');
  const installerNsh = path.join(DESKTOP_BUILD_DIR, 'installer.nsh');

  if (!fs.existsSync(iconPng) || fs.statSync(iconPng).size === 0) {
    fs.copyFileSync(sourcePng, iconPng);
  }
  if (!fs.existsSync(trayPng) || fs.statSync(trayPng).size === 0) {
    fs.copyFileSync(sourcePng, trayPng);
  }

  if (!fs.existsSync(iconIco) || fs.statSync(iconIco).size === 0) {
    let generatedWithMagick = false;
    for (const cmd of ['magick', 'convert']) {
      try {
        execFileSync(
          cmd,
          [sourcePng, '-background', 'none', '-define', 'icon:auto-resize=256,128,64,48,32,16', iconIco],
          { stdio: 'ignore' }
        );
        if (fs.existsSync(iconIco) && fs.statSync(iconIco).size > 22) {
          generatedWithMagick = true;
          break;
        }
      } catch {
        // Fallback to zero-dependency Node PNG-in-ICO builder below
      }
    }
    if (!generatedWithMagick) {
      fs.writeFileSync(iconIco, buildPngWrappedIco(fs.readFileSync(sourcePng)));
    }
  }

  if (!fs.existsSync(installerNsh)) {
    fs.writeFileSync(
      installerNsh,
      [
        '; PMP PAINTS ERP — NSIS Installer Custom Hooks',
        '; Ensures user settings, window state, and encrypted session metadata in %APPDATA%',
        '; are preserved across upgrades and normal installations.',
        '',
        '!macro customInstall',
        '  IfFileExists "$APPDATA\\pmp-paints-desktop\\*.*" 0 +2',
        '    DetailPrint "Preserving existing PMP PAINTS ERP user settings and preferences."',
        '!macroend',
        '',
        '!macro customUnInstall',
        '  DetailPrint "Uninstalling PMP PAINTS ERP application binaries while preserving local preferences."',
        '!macroend',
        ''
      ].join('\n'),
      'utf8'
    );
  }

  // Validate icon.ico header (0x0000 Reserved, 0x0001 Type ICO)
  const icoHeader = fs.readFileSync(iconIco).subarray(0, 4);
  if (icoHeader.length < 4 || icoHeader.readUInt16LE(0) !== 0 || icoHeader.readUInt16LE(2) !== 1) {
    throw new Error(`Invalid ICO header in ${iconIco}`);
  }

  return { sourcePng, iconIco, iconPng, trayPng, installerNsh };
}

if (process.argv[1] && path.resolve(process.argv[1]) === __filename) {
  const res = ensureDesktopBuildAssets();
  console.log(`[Desktop Assets] Verified desktop/build resources from ${path.relative(REPO_ROOT, res.sourcePng)}:`);
  console.log(`  - ${path.relative(REPO_ROOT, res.iconIco)} (${fs.statSync(res.iconIco).size} bytes)`);
  console.log(`  - ${path.relative(REPO_ROOT, res.iconPng)} (${fs.statSync(res.iconPng).size} bytes)`);
  console.log(`  - ${path.relative(REPO_ROOT, res.trayPng)} (${fs.statSync(res.trayPng).size} bytes)`);
  console.log(`  - ${path.relative(REPO_ROOT, res.installerNsh)} (${fs.statSync(res.installerNsh).size} bytes)`);
}
