import { execFileSync, execSync } from 'node:child_process';
import crypto from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);
const WEB_DIR = path.resolve(__dirname, '..');
const REPO_ROOT = path.resolve(WEB_DIR, '..');
const PUBLIC_DIR = path.join(REPO_ROOT, 'public');
const WEB_PUBLIC_DIR = path.join(WEB_DIR, 'public');
const WEB_DIST_DIR = path.join(WEB_DIR, 'dist');
const DRAWABLE_DIR = path.join(REPO_ROOT, 'app', 'src', 'main', 'res', 'drawable');

const basePath = process.env.VITE_BASE_PATH || '/';
const skipInstall = process.env.SKIP_WEB_INSTALL === 'true';

console.log(`[PMP Build] Starting clean production web build (Node ${process.version}, VITE_BASE_PATH=${basePath})`);

// 1. Sync brand assets and release manifests into web/public without touching user data
const webPublicAssetsDir = path.join(WEB_PUBLIC_DIR, 'assets');
fs.mkdirSync(webPublicAssetsDir, { recursive: true });

for (const assetName of [
  'ic_pmp_emblem.png',
  'ic_pmp_emblem_solid.png',
  'pmp_brand_logo.jpg',
  'pmp_brand_poster.jpg',
  'pmp_reference_poster.jpg'
]) {
  const fromDrawable = path.join(DRAWABLE_DIR, assetName);
  if (fs.existsSync(fromDrawable) && fs.statSync(fromDrawable).isFile()) {
    fs.copyFileSync(fromDrawable, path.join(webPublicAssetsDir, assetName));
  }
}

const publicAssetsDir = path.join(PUBLIC_DIR, 'assets');
if (fs.existsSync(publicAssetsDir)) {
  for (const entry of fs.readdirSync(publicAssetsDir, { withFileTypes: true })) {
    if (entry.isFile()) {
      fs.copyFileSync(path.join(publicAssetsDir, entry.name), path.join(webPublicAssetsDir, entry.name));
    }
  }
}

for (const manifestName of ['version.json', 'releases-history.json']) {
  const srcManifest = path.join(PUBLIC_DIR, manifestName);
  if (fs.existsSync(srcManifest) && fs.statSync(srcManifest).isFile()) {
    fs.copyFileSync(srcManifest, path.join(WEB_PUBLIC_DIR, manifestName));
  }
}

// 2. Deterministic dependency installation (skip redundant reinstall when lockfile hash already matches)
const tscBin = path.join(WEB_DIR, 'node_modules', 'typescript', 'bin', 'tsc');
const viteBin = path.join(WEB_DIR, 'node_modules', 'vite', 'bin', 'vite.js');
const lockfilePath = path.join(WEB_DIR, 'package-lock.json');
const lockHashFile = path.join(WEB_DIR, 'node_modules', '.pmp-lock-hash');
const currentLockHash = fs.existsSync(lockfilePath)
  ? crypto.createHash('sha256').update(fs.readFileSync(lockfilePath)).digest('hex')
  : '';
const cachedLockHash = fs.existsSync(lockHashFile) ? fs.readFileSync(lockHashFile, 'utf8').trim() : '';
const depsReady = fs.existsSync(tscBin) && fs.existsSync(viteBin);
const lockMatches = currentLockHash.length > 0 && cachedLockHash === currentLockHash;

if ((!skipInstall && !lockMatches) || !depsReady) {
  console.log('[PMP Build] Installing web dependencies via npm ci...');
  execSync('npm ci --no-audit --no-fund', {
    cwd: WEB_DIR,
    stdio: 'inherit',
    env: { ...process.env }
  });
  if (currentLockHash) {
    fs.writeFileSync(lockHashFile, `${currentLockHash}\n`, 'utf8');
  }
} else {
  console.log('[PMP Build] Web dependencies already installed and lockfile matches; skipping redundant npm ci.');
}

// 3. Remove stale production output to prevent cached artifact leakage
if (fs.existsSync(WEB_DIST_DIR)) {
  fs.rmSync(WEB_DIST_DIR, { recursive: true, force: true });
}

// 4. TypeScript strict type-checking + Vite production bundle
console.log('[PMP Build] Running TypeScript strict verification (tsc --noEmit)...');
execFileSync(process.execPath, [tscBin, '--noEmit'], {
  cwd: WEB_DIR,
  stdio: 'inherit'
});

console.log('[PMP Build] Running Vite production build -> web/dist...');
execFileSync(process.execPath, [viteBin, 'build'], {
  cwd: WEB_DIR,
  stdio: 'inherit',
  env: {
    ...process.env,
    VITE_BASE_PATH: basePath
  }
});

// 5. Verify required production artifacts exist and guard against Cloudflare code 10021 (_redirects loop)
const requiredOutputs = [
  path.join(WEB_DIST_DIR, 'index.html'),
  path.join(WEB_DIST_DIR, '_headers'),
  path.join(WEB_DIST_DIR, '.assetsignore'),
  path.join(WEB_DIST_DIR, 'assets')
];

for (const artifactPath of requiredOutputs) {
  if (!fs.existsSync(artifactPath)) {
    throw new Error(`[PMP Build] Verification failed: required build output missing at ${artifactPath}`);
  }
}

const forbiddenRedirects = path.join(WEB_DIST_DIR, '_redirects');
if (fs.existsSync(forbiddenRedirects)) {
  throw new Error(
    '[PMP Build] Verification failed: web/dist/_redirects must not exist (Cloudflare Workers Static Assets rejects catch-all 200 rules with code 10021; SPA fallback is configured via wrangler.toml not_found_handling).'
  );
}

const indexSize = fs.statSync(path.join(WEB_DIST_DIR, 'index.html')).size;
if (indexSize < 100) {
  throw new Error(`[PMP Build] Verification failed: web/dist/index.html is unexpectedly small (${indexSize} bytes)`);
}

console.log(`[PMP Build] Production build verified cleanly at ${WEB_DIST_DIR} (index.html: ${indexSize} bytes).`);
