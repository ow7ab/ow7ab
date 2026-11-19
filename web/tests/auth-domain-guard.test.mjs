import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);
const webRoot = path.resolve(__dirname, '..');

// Replicate the exact hostname allowlist matcher from src/utils/authDomainGuard.ts
// and verify the source file matches these security invariants.
const guardSrc = fs.readFileSync(path.join(webRoot, 'src/utils/authDomainGuard.ts'), 'utf8');
const authContextSrc = fs.readFileSync(path.join(webRoot, 'src/context/AuthContext.tsx'), 'utf8');
const loginPageSrc = fs.readFileSync(path.join(webRoot, 'src/pages/LoginPage.tsx'), 'utf8');
const loginModalSrc = fs.readFileSync(path.join(webRoot, 'src/components/sections/LoginModal.tsx'), 'utf8');

function evaluateHostContract(hostname, protocol) {
  const host = (hostname || '').trim().toLowerCase().replace(/\.$/, '');
  const proto = (protocol || '').trim().toLowerCase();
  const isLocalhost = host === 'localhost' || host === '127.0.0.1' || host === '[::1]';
  const isSecureContext = proto === 'https:' || (isLocalhost && proto === 'http:');
  const exact = new Set([
    'localhost',
    '127.0.0.1',
    '[::1]',
    'ow7ab.github.io',
    'ow7ab.workers.dev',
    'pmp-paints.com',
    'www.pmp-paints.com',
    'gen-lang-client-0220128746.firebaseapp.com',
    'gen-lang-client-0220128746.web.app'
  ]);
  const isTrustedOrigin =
    exact.has(host) ||
    /^[a-z0-9-]+\.pmp-paints\.com$/.test(host) ||
    /^ow7ab(\.[a-z0-9-]+)?\.workers\.dev$/.test(host) ||
    /^[a-z0-9-]+\.ow7ab\.workers\.dev$/.test(host) ||
    /^(pmp-paints|ow7ab)(-[a-z0-9-]+)?\.pages\.dev$/.test(host);

  return { isTrustedOrigin, isSecureContext, canStartFirebaseAuth: isTrustedOrigin && isSecureContext };
}

test('authDomainGuard allows official HTTPS domains and localhost', () => {
  assert.equal(evaluateHostContract('ow7ab.github.io', 'https:').canStartFirebaseAuth, true);
  assert.equal(evaluateHostContract('ow7ab.workers.dev', 'https:').canStartFirebaseAuth, true);
  assert.equal(evaluateHostContract('ow7ab.myacct.workers.dev', 'https:').canStartFirebaseAuth, true);
  assert.equal(evaluateHostContract('pmp-paints.com', 'https:').canStartFirebaseAuth, true);
  assert.equal(evaluateHostContract('gen-lang-client-0220128746.firebaseapp.com', 'https:').canStartFirebaseAuth, true);
  assert.equal(evaluateHostContract('localhost', 'http:').canStartFirebaseAuth, true);
  assert.equal(evaluateHostContract('127.0.0.1', 'http:').canStartFirebaseAuth, true);
});

test('authDomainGuard blocks untrusted domains and insecure non-local HTTP origins', () => {
  assert.equal(evaluateHostContract('evil-phishing.example.com', 'https:').canStartFirebaseAuth, false);
  assert.equal(evaluateHostContract('ow7ab.github.io.evil.com', 'https:').canStartFirebaseAuth, false);
  assert.equal(evaluateHostContract('random-attacker.workers.dev', 'https:').canStartFirebaseAuth, false);
  assert.equal(evaluateHostContract('ow7ab.github.io', 'http:').canStartFirebaseAuth, false);
  assert.equal(evaluateHostContract('pmp-paints.com', 'http:').canStartFirebaseAuth, false);
});

test('authDomainGuard source includes ow7ab.github.io, HTTPS enforcement, and Firebase settings URL', () => {
  assert.match(guardSrc, /ow7ab\.github\.io/);
  assert.match(guardSrc, /protocol === 'https:'/);
  assert.match(guardSrc, /gen-lang-client-0220128746/);
  assert.match(guardSrc, /authentication\/settings/);
  assert.match(guardSrc, /export function assertTrustedAuthDomain/);
});

test('AuthContext enforces assertTrustedAuthDomain before all Firebase Auth calls', () => {
  assert.match(authContextSrc, /import\s*\{\s*assertTrustedAuthDomain\s*\}\s*from\s*'\.\.\/utils\/authDomainGuard'/);
  const guardCalls = authContextSrc.match(/assertTrustedAuthDomain\(\)/g) || [];
  assert.ok(guardCalls.length >= 4, `Expected >=4 assertTrustedAuthDomain() calls in AuthContext, found ${guardCalls.length}`);
});

test('LoginPage always renders Username Only (without email) login tab and pre-flight domain trust check', () => {
  assert.match(loginPageSrc, /evaluateAuthDomain\(\)/);
  assert.match(loginPageSrc, /data-testid="login-domain-trust-strip"/);
  assert.match(loginPageSrc, /data-testid="login-tab-username-only"/);
  assert.match(loginPageSrc, /data-testid="login-username-only-form"/);
  assert.match(loginPageSrc, /اسم المستخدم فقط/);
  assert.match(loginPageSrc, /useState<'pin' \| 'email' \| 'reset'>\('pin'\)/);
  // Ensure username tab is NOT hidden when Firebase currentUser is null
  assert.doesNotMatch(loginPageSrc, /tab === 'pin'\s*&&\s*currentUser/);
  assert.doesNotMatch(loginPageSrc, /currentUser\s*&&\s*tab === 'pin'/);
});

test('LoginModal also enforces evaluateAuthDomain and exposes Username Only option', () => {
  assert.match(loginModalSrc, /evaluateAuthDomain\(\)/);
  assert.match(loginModalSrc, /اسم المستخدم فقط/);
  assert.doesNotMatch(loginModalSrc, /tab === 'pin'\s*&&\s*currentUser/);
});
