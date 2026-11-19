import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import test from 'node:test';
import { fileURLToPath } from 'node:url';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);
const WEB_DIR = path.resolve(__dirname, '..');
const REPO_ROOT = path.resolve(WEB_DIR, '..');

test('Cloudflare Workers Static Assets config: valid single-env assets-only ow7ab configuration', () => {
  const wranglerPath = path.join(REPO_ROOT, 'wrangler.toml');
  assert.equal(fs.existsSync(wranglerPath), true, 'Root wrangler.toml must exist');
  assert.equal(
    fs.existsSync(path.join(WEB_DIR, 'wrangler.toml')),
    false,
    'Duplicate web/wrangler.toml must not exist'
  );

  const toml = fs.readFileSync(wranglerPath, 'utf8');
  assert.match(toml, /^name\s*=\s*"ow7ab"/m, 'Worker name must be ow7ab');
  assert.doesNotMatch(toml, /^\[env\./m, 'wrangler.toml must not declare multiple environments');
  assert.doesNotMatch(
    toml,
    /^\[vars\]/m,
    'Assets-only Worker without main script must not declare runtime [vars] (rejected by Cloudflare API)'
  );
  assert.match(toml, /^\[build\]\s*\ncommand\s*=\s*"npm run build"/m, '[build] command must build web/dist');
  assert.match(toml, /directory\s*=\s*"\.\/web\/dist"/, '[assets].directory must point to ./web/dist');
  assert.match(
    toml,
    /not_found_handling\s*=\s*"single-page-application"/,
    '[assets].not_found_handling must be single-page-application'
  );
});

test('Cloudflare Static Assets hygiene: forbids _redirects loop (code 10021) and excludes sourcemaps/404.html', () => {
  assert.equal(
    fs.existsSync(path.join(WEB_DIR, 'public', '_redirects')),
    false,
    'web/public/_redirects must not exist (Cloudflare rejects catch-all 200 rules with code 10021)'
  );

  const assetsIgnorePath = path.join(WEB_DIR, 'public', '.assetsignore');
  assert.equal(fs.existsSync(assetsIgnorePath), true, 'web/public/.assetsignore must exist');
  const assetsIgnore = fs.readFileSync(assetsIgnorePath, 'utf8');
  assert.match(assetsIgnore, /^\*\.map$/m, '.assetsignore must exclude *.map sourcemaps');
  assert.match(assetsIgnore, /^404\.html$/m, '.assetsignore must exclude GitHub Pages 404.html');

  const nodeVersionPath = path.join(REPO_ROOT, '.node-version');
  assert.equal(fs.existsSync(nodeVersionPath), true, '.node-version must exist for Cloudflare Workers Builds');
  assert.equal(fs.readFileSync(nodeVersionPath, 'utf8').trim(), '22', '.node-version must pin Node 22');

  const rootPkg = JSON.parse(fs.readFileSync(path.join(REPO_ROOT, 'package.json'), 'utf8'));
  assert.ok(
    rootPkg.devDependencies && typeof rootPkg.devDependencies.wrangler === 'string',
    'Root package.json must pin wrangler in devDependencies for Cloudflare Workers Builds'
  );
});
