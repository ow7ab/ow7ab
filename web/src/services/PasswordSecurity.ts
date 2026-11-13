/**
 * PMP PAINTS Enterprise Cryptographic Utility (TypeScript WebCrypto port of Android PasswordSecurityUtil.kt)
 *
 * Compatible 1:1 with Android `PasswordSecurityUtil`:
 * 1. PBKDF2-HMAC-SHA256 (`pbkdf2$sha256$65536$<hexSalt>$<hexHash>`)
 * 2. Legacy SHA-256 (`PMP_SECURE_V2:<secret>:PMP_HASH_SALT`)
 * 3. Legacy plaintext fallback for initial seeded accounts prior to migration
 */

const PBKDF2_PREFIX = 'pbkdf2$sha256';
export const DEFAULT_ITERATIONS = 65536;
const SALT_BYTES = 16;
const KEY_LENGTH_BITS = 256;

const LEGACY_SALT_PREFIX = 'PMP_SECURE_V2:';
const LEGACY_SALT_SUFFIX = ':PMP_HASH_SALT';

function bytesToHex(bytes: Uint8Array): string {
  return Array.from(bytes)
    .map((b) => b.toString(16).padStart(2, '0'))
    .join('');
}

function hexToBytes(hex: string): Uint8Array | null {
  if (!hex || hex.length % 2 !== 0) return null;
  const out = new Uint8Array(hex.length / 2);
  for (let i = 0; i < out.length; i++) {
    const hi = parseInt(hex[i * 2], 16);
    const lo = parseInt(hex[i * 2 + 1], 16);
    if (Number.isNaN(hi) || Number.isNaN(lo)) return null;
    out[i] = (hi << 4) | lo;
  }
  return out;
}

export function constantTimeBytesEquals(a: Uint8Array, b: Uint8Array): boolean {
  if (a.length !== b.length) return false;
  let diff = 0;
  for (let i = 0; i < a.length; i++) {
    diff |= a[i] ^ b[i];
  }
  return diff === 0;
}

export function constantTimeEquals(a: string, b: string): boolean {
  const enc = new TextEncoder();
  return constantTimeBytesEquals(enc.encode(a), enc.encode(b));
}

async function derivePbkdf2(
  password: string,
  salt: Uint8Array,
  iterations: number,
  keyBits: number
): Promise<Uint8Array> {
  const enc = new TextEncoder();
  const baseKey = await globalThis.crypto.subtle.importKey(
    'raw',
    enc.encode(password),
    'PBKDF2',
    false,
    ['deriveBits']
  );
  const saltCopy = new Uint8Array(salt);
  const bits = await globalThis.crypto.subtle.deriveBits(
    {
      name: 'PBKDF2',
      salt: saltCopy.buffer,
      iterations,
      hash: 'SHA-256'
    },
    baseKey,
    keyBits
  );
  return new Uint8Array(bits);
}

export function isModernPbkdf2Hash(storedSecret: string): boolean {
  return (storedSecret || '').trim().startsWith(`${PBKDF2_PREFIX}$`);
}

export function isHashedSecret(storedSecret: string): boolean {
  const clean = (storedSecret || '').trim();
  if (isModernPbkdf2Hash(clean)) return true;
  return clean.length === 64 && /^[0-9a-fA-F]{64}$/.test(clean);
}

export function needsUpgradeToPbkdf2(storedSecret: string): boolean {
  return !isModernPbkdf2Hash((storedSecret || '').trim());
}

export async function hashPassword(
  rawPassword: string,
  iterations: number = DEFAULT_ITERATIONS
): Promise<string> {
  const clean = (rawPassword || '').trim();
  if (!clean) {
    throw new Error('كلمة المرور أو الرمز السري لا يمكن أن يكون فارغاً');
  }
  if (isModernPbkdf2Hash(clean)) return clean;

  const salt = new Uint8Array(SALT_BYTES);
  globalThis.crypto.getRandomValues(salt);
  const derived = await derivePbkdf2(clean, salt, iterations, KEY_LENGTH_BITS);
  return `${PBKDF2_PREFIX}$${iterations}$${bytesToHex(salt)}$${bytesToHex(derived)}`;
}

export async function legacySha256Hash(secret: string): Promise<string> {
  const salted = `${LEGACY_SALT_PREFIX}${(secret || '').trim()}${LEGACY_SALT_SUFFIX}`;
  const enc = new TextEncoder();
  const digest = await globalThis.crypto.subtle.digest('SHA-256', enc.encode(salted));
  return bytesToHex(new Uint8Array(digest));
}

export async function verifyPassword(
  candidateRaw: string,
  storedSecret: string
): Promise<boolean> {
  const cleanCandidate = (candidateRaw || '').trim();
  const cleanStored = (storedSecret || '').trim();
  if (!cleanCandidate || !cleanStored) return false;

  // 1. Modern PBKDF2-HMAC-SHA256 format: pbkdf2$sha256$<iterations>$<hexSalt>$<hexHash>
  if (isModernPbkdf2Hash(cleanStored)) {
    const parts = cleanStored.split('$');
    if (parts.length !== 5) return false;
    const iterations = parseInt(parts[2], 10);
    if (!Number.isFinite(iterations) || iterations <= 0) return false;
    const salt = hexToBytes(parts[3]);
    const expectedHash = hexToBytes(parts[4]);
    if (!salt || !expectedHash || salt.length === 0 || expectedHash.length === 0) return false;

    const actualHash = await derivePbkdf2(cleanCandidate, salt, iterations, expectedHash.length * 8);
    return constantTimeBytesEquals(actualHash, expectedHash);
  }

  // 2. Legacy 64-char SHA-256 hex format (PMP_SECURE_V2:<secret>:PMP_HASH_SALT)
  // SECURITY: Never accept a raw hash string directly as candidate input (blocks Pass-the-Hash attacks).
  if (isHashedSecret(cleanCandidate)) {
    return false;
  }

  const candidateLegacyHash = await legacySha256Hash(cleanCandidate);
  if (constantTimeEquals(candidateLegacyHash.toLowerCase(), cleanStored.toLowerCase())) {
    return true;
  }

  // 3. Legacy plaintext PIN fallback (only if storedSecret itself is not a hash format)
  if (!isHashedSecret(cleanStored)) {
    return constantTimeEquals(cleanCandidate, cleanStored);
  }
  return false;
}

export interface PasswordStrengthResult {
  isValid: boolean;
  score: number;
  labelAr: string;
  errorMessageAr?: string;
}

export function evaluatePasswordStrength(
  password: string,
  enforceStrongPolicy: boolean = false
): PasswordStrengthResult {
  const clean = (password || '').trim();
  if (clean.length < 4) {
    return {
      isValid: false,
      score: 0,
      labelAr: 'ضعيفة جداً',
      errorMessageAr: 'كلمة المرور أو رمز الدخول يجب أن يتكون من 4 خانات على الأقل'
    };
  }
  let score = 1;
  if (clean.length >= 8) score++;
  if (/[a-zA-Z\u0600-\u06FF]/.test(clean) && /\d/.test(clean)) score++;
  if (/[^a-zA-Z0-9\u0600-\u06FF]/.test(clean) || (/[A-Z]/.test(clean) && /[a-z]/.test(clean))) score++;

  const weakDefaults = new Set([
    '1234',
    '0000',
    '1111',
    '2222',
    '3333',
    '4444',
    '5555',
    '6666',
    '12345',
    '123456',
    'password',
    'admin',
    'qwerty'
  ]);
  if (weakDefaults.has(clean.toLowerCase())) {
    score = 1;
    if (enforceStrongPolicy) {
      return {
        isValid: false,
        score: 1,
        labelAr: 'ضعيفة (شائعة)',
        errorMessageAr: 'يرجى اختيار كلمة مرور قوية وغير افتراضية لحماية الحساب'
      };
    }
  }

  const labelAr =
    score === 1 ? 'مقبولة (أساسية)' : score === 2 ? 'متوسطة' : score === 3 ? 'قوية' : 'قوية جداً';
  return { isValid: true, score, labelAr };
}

// ============================================================================
// ANTI-TAMPERING SESSION INTEGRITY SEALING (Blocks localStorage role/perm spoofing)
// ============================================================================

const SESSION_SEAL_DOMAIN = 'PMP-PAINTS-ERP-SESSION-SEAL-V2';

function buildCanonicalSessionString(payload: {
  userCode?: string;
  username?: string;
  role?: string;
  roleType?: string;
  branch?: string;
  permissions?: string;
  active?: boolean;
  syncStatus?: string;
  serverSyncToken?: string;
  issuedAt?: number;
}): string {
  return [
    SESSION_SEAL_DOMAIN,
    String(payload.userCode || ''),
    String(payload.username || '').toLowerCase(),
    String(payload.role || ''),
    String(payload.roleType || ''),
    String(payload.branch || ''),
    String(payload.permissions || ''),
    String(payload.active !== false),
    String(payload.syncStatus || ''),
    String(payload.serverSyncToken || ''),
    String(payload.issuedAt || 0)
  ].join('|');
}

export async function computeSessionIntegritySeal(payload: {
  userCode?: string;
  username?: string;
  role?: string;
  roleType?: string;
  branch?: string;
  permissions?: string;
  active?: boolean;
  syncStatus?: string;
  serverSyncToken?: string;
  issuedAt?: number;
}): Promise<string> {
  const canonical = buildCanonicalSessionString(payload);
  const enc = new TextEncoder();
  const digest = await globalThis.crypto.subtle.digest('SHA-256', enc.encode(canonical));
  return bytesToHex(new Uint8Array(digest));
}

export async function verifySessionIntegritySeal(
  payload: Record<string, any> | null | undefined,
  maxAgeMs: number = 24 * 60 * 60 * 1000
): Promise<boolean> {
  if (!payload || typeof payload !== 'object') return false;
  const seal = String(payload._integritySeal || '');
  const issuedAt = Number(payload._issuedAt || 0);
  if (!seal || !Number.isFinite(issuedAt) || issuedAt <= 0) return false;
  if (Date.now() - issuedAt > maxAgeMs) return false;

  const expected = await computeSessionIntegritySeal({
    userCode: payload.userCode,
    username: payload.username,
    role: payload.role,
    roleType: payload.roleType,
    branch: payload.branch,
    permissions: payload.permissions,
    active: payload.active,
    syncStatus: payload.syncStatus,
    serverSyncToken: payload.serverSyncToken,
    issuedAt
  });
  return constantTimeEquals(seal, expected);
}

// ============================================================================
// BRUTE-FORCE LOGIN RATE LIMITER & PROGRESSIVE LOCKOUT
// ============================================================================

const RATE_LIMIT_STORAGE_KEY = 'pmp_sec_login_rl_v1';
const MAX_ATTEMPTS_BEFORE_LOCK = 5;
const BASE_LOCK_MS = 5 * 60 * 1000; // 5 minutes
const ESCALATED_LOCK_MS = 15 * 60 * 1000; // 15 minutes
const WINDOW_RESET_MS = 30 * 60 * 1000; // 30 minutes

interface RateLimitBucket {
  attempts: number;
  lockCount: number;
  lockedUntil: number;
  lastAttemptAt: number;
}

const memoryBuckets = new Map<string, RateLimitBucket>();

function normalizeRateKey(identifier: string): string {
  return (identifier || '__global__').trim().toLowerCase().slice(0, 80) || '__global__';
}

function readRateBuckets(): Record<string, RateLimitBucket> {
  try {
    if (typeof globalThis.localStorage !== 'undefined') {
      const raw = globalThis.localStorage.getItem(RATE_LIMIT_STORAGE_KEY);
      if (raw) {
        const parsed = JSON.parse(raw);
        if (parsed && typeof parsed === 'object') return parsed;
      }
    }
  } catch {
    // Fallback to memory buckets if storage is restricted
  }
  const obj: Record<string, RateLimitBucket> = {};
  memoryBuckets.forEach((v, k) => {
    obj[k] = v;
  });
  return obj;
}

function writeRateBuckets(buckets: Record<string, RateLimitBucket>): void {
  Object.entries(buckets).forEach(([k, v]) => memoryBuckets.set(k, v));
  try {
    if (typeof globalThis.localStorage !== 'undefined') {
      globalThis.localStorage.setItem(RATE_LIMIT_STORAGE_KEY, JSON.stringify(buckets));
    }
  } catch {
    // Ignore storage errors
  }
}

export const LoginRateLimiter = {
  checkLockStatus(identifier: string, now: number = Date.now()): { locked: boolean; remainingSeconds: number; attempts: number } {
    const key = normalizeRateKey(identifier);
    const all = readRateBuckets();
    const bucket = all[key];
    if (!bucket) return { locked: false, remainingSeconds: 0, attempts: 0 };

    if (bucket.lockedUntil > now) {
      return {
        locked: true,
        remainingSeconds: Math.ceil((bucket.lockedUntil - now) / 1000),
        attempts: bucket.attempts
      };
    }

    if (now - bucket.lastAttemptAt > WINDOW_RESET_MS) {
      return { locked: false, remainingSeconds: 0, attempts: 0 };
    }

    return { locked: false, remainingSeconds: 0, attempts: bucket.attempts };
  },

  recordFailure(identifier: string, now: number = Date.now()): { locked: boolean; remainingSeconds: number; attemptsRemaining: number } {
    const key = normalizeRateKey(identifier);
    const all = readRateBuckets();
    const prev = all[key];
    const expiredWindow = !prev || now - prev.lastAttemptAt > WINDOW_RESET_MS;

    const attempts = expiredWindow ? 1 : (prev?.attempts || 0) + 1;
    let lockCount = expiredWindow ? 0 : (prev?.lockCount || 0);
    let lockedUntil = prev?.lockedUntil || 0;

    if (attempts >= MAX_ATTEMPTS_BEFORE_LOCK) {
      lockCount += 1;
      const duration = lockCount > 1 ? ESCALATED_LOCK_MS : BASE_LOCK_MS;
      lockedUntil = now + duration;
      all[key] = {
        attempts,
        lockCount,
        lockedUntil,
        lastAttemptAt: now
      };
      writeRateBuckets(all);
      return {
        locked: true,
        remainingSeconds: Math.ceil(duration / 1000),
        attemptsRemaining: 0
      };
    }

    all[key] = {
      attempts,
      lockCount,
      lockedUntil: 0,
      lastAttemptAt: now
    };
    writeRateBuckets(all);
    return {
      locked: false,
      remainingSeconds: 0,
      attemptsRemaining: Math.max(0, MAX_ATTEMPTS_BEFORE_LOCK - attempts)
    };
  },

  recordSuccess(identifier: string): void {
    const key = normalizeRateKey(identifier);
    memoryBuckets.delete(key);
    const all = readRateBuckets();
    if (all[key]) {
      delete all[key];
      writeRateBuckets(all);
    }
  },

  clearAll(): void {
    memoryBuckets.clear();
    try {
      if (typeof globalThis.localStorage !== 'undefined') {
        globalThis.localStorage.removeItem(RATE_LIMIT_STORAGE_KEY);
      }
    } catch {
      // Ignore
    }
  }
};

