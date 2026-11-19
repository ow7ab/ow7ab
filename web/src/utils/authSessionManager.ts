/**
 * PMP PAINTS ERP — Unified Authentication Session, Device Registry & Account Switching Core.
 *
 * Security invariants:
 * - Zero mandatory Google Sign-In gating: Username+PIN/Password and Email+Password are first-class flows.
 * - Google Sign-In remains strictly optional.
 * - Local session persistence is cryptographically sealed (SHA-256 integrity seal + max age TTL).
 * - Never stores raw passwords or secret hashes in localStorage.
 * - Supports safe multi-account switching without bouncing back to previous users.
 */

import { UserAccount, UserRoleType } from '../types/erp';
import {
  computeSessionIntegritySeal,
  verifySessionIntegritySeal
} from '../services/PasswordSecurity';
import { resolveUserRoleType } from '../services/SecurityService';
import { safeStorageGet, safeStorageRemove, safeStorageSet } from './safeStorage';

export const SESSION_STORAGE_KEY = 'pmp_web_portal_user';
export const DEVICE_ID_STORAGE_KEY = 'pmp_web_device_id_v1';
export const DEVICE_SESSIONS_STORAGE_KEY = 'pmp_web_device_sessions_v1';
export const SESSION_MAX_AGE_MS = 24 * 60 * 60 * 1000; // 24 hours

export type AuthLoginMethod =
  | 'USERNAME_PIN'
  | 'EMAIL_PASSWORD'
  | 'GOOGLE_OPTIONAL'
  | 'ACCOUNT_SWITCH';

export interface DeviceSessionRecord {
  deviceId: string;
  userCode: string;
  username: string;
  fullName: string;
  role: string;
  branch: string;
  loginMethod: AuthLoginMethod;
  deviceLabel: string;
  startedAt: number;
  lastActiveAt: number;
  active: boolean;
}

export interface UsernameCandidateOptions {
  identifier: string;
}

/**
 * Generates candidate corporate/Firebase emails for a username-only login attempt
 * without forcing Google Sign-In.
 */
export function buildUsernameAuthEmailCandidates(identifier: string): string[] {
  const clean = (identifier || '').replace(/[\u0000-\u001F\u007F]/g, '').trim().toLowerCase().slice(0, 120);
  if (!clean) return [];
  if (clean.includes('@')) return [clean];
  if (clean === 'admin' || clean === 'usr-admin' || clean === 'usr-hq-001') {
    return ['admin@pmp-paints.com', 'oaw7ab@gmail.com'];
  }
  if (/^[a-z0-9._-]+$/.test(clean)) {
    return [`${clean}@pmp-paints.com`, `${clean}@pmppaints.com`];
  }
  return [];
}

/**
 * Resolves a stable, non-PII device identifier for session management on this browser/desktop client.
 */
export function getOrCreateDeviceId(): string {
  const existing = safeStorageGet(DEVICE_ID_STORAGE_KEY);
  if (existing && existing.length >= 8) {
    return existing;
  }
  const randomPart =
    typeof globalThis.crypto !== 'undefined' && typeof globalThis.crypto.randomUUID === 'function'
      ? globalThis.crypto.randomUUID().replace(/-/g, '').slice(0, 12).toUpperCase()
      : `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 8)}`.toUpperCase();
  const deviceId = `WEB-${randomPart}`;
  safeStorageSet(DEVICE_ID_STORAGE_KEY, deviceId);
  return deviceId;
}

export function detectDeviceLabel(): string {
  if (typeof navigator === 'undefined') return 'Web Browser';
  const ua = navigator.userAgent || '';
  if (/Electron/i.test(ua)) return 'PMP Windows Desktop';
  if (/Android/i.test(ua)) return 'Android Web Browser';
  if (/iPhone|iPad|iOS/i.test(ua)) return 'iOS Web Browser';
  if (/Windows/i.test(ua)) return 'Windows Browser';
  if (/Mac OS X/i.test(ua)) return 'macOS Browser';
  if (/Linux/i.test(ua)) return 'Linux Browser';
  return 'Web Browser';
}

export function sanitizeAccountForSession(account: UserAccount): UserAccount {
  const sanitized: UserAccount = {
    ...account,
    pinOrPassword: undefined, // Never persist secret hashes in client storage
    roleType: resolveUserRoleType(account) as UserRoleType
  };
  return sanitized;
}

export async function createSealedSessionJson(
  account: UserAccount,
  issuedAt: number = Date.now(),
  loginMethod: AuthLoginMethod = 'USERNAME_PIN',
  deviceId: string = getOrCreateDeviceId()
): Promise<string> {
  const sanitized = sanitizeAccountForSession(account);
  const seal = await computeSessionIntegritySeal({
    userCode: sanitized.userCode,
    username: sanitized.username,
    role: sanitized.role,
    roleType: sanitized.roleType,
    branch: sanitized.branch,
    permissions: sanitized.permissions,
    active: sanitized.active,
    syncStatus: sanitized.syncStatus,
    serverSyncToken: sanitized.serverSyncToken,
    issuedAt
  });
  return JSON.stringify({
    ...sanitized,
    _issuedAt: issuedAt,
    _loginMethod: loginMethod,
    _deviceId: deviceId,
    _integritySeal: seal
  });
}

export interface SessionRestoreResult {
  status: 'VALID' | 'EMPTY' | 'EXPIRED' | 'INVALID';
  account: UserAccount | null;
  loginMethod?: AuthLoginMethod;
  deviceId?: string;
  issuedAt?: number;
}

export async function verifyAndParseStoredSession(
  rawJson: string | null,
  maxAgeMs: number = SESSION_MAX_AGE_MS
): Promise<SessionRestoreResult> {
  if (!rawJson) {
    return { status: 'EMPTY', account: null };
  }
  try {
    const parsed = JSON.parse(rawJson);
    if (!parsed || typeof parsed !== 'object' || !parsed.userCode) {
      return { status: 'INVALID', account: null };
    }
    const issuedAt = Number(parsed._issuedAt || 0);
    if (issuedAt > 0 && Date.now() - issuedAt > maxAgeMs) {
      return { status: 'EXPIRED', account: null, issuedAt };
    }
    const sealOk = await verifySessionIntegritySeal(parsed, maxAgeMs);
    if (!sealOk || parsed.active === false || parsed.syncStatus === 'PENDING_MANAGER_APPROVAL') {
      return { status: 'INVALID', account: null };
    }
    const cleanAccount: UserAccount = { ...parsed };
    const loginMethod = parsed._loginMethod as AuthLoginMethod | undefined;
    const deviceId = typeof parsed._deviceId === 'string' ? parsed._deviceId : undefined;
    delete (cleanAccount as any)._integritySeal;
    delete (cleanAccount as any)._issuedAt;
    delete (cleanAccount as any)._loginMethod;
    delete (cleanAccount as any)._deviceId;
    cleanAccount.pinOrPassword = undefined;
    cleanAccount.roleType = resolveUserRoleType(cleanAccount);
    return {
      status: 'VALID',
      account: cleanAccount,
      loginMethod,
      deviceId,
      issuedAt
    };
  } catch {
    return { status: 'INVALID', account: null };
  }
}

export function readDeviceSessions(): DeviceSessionRecord[] {
  try {
    const raw = safeStorageGet(DEVICE_SESSIONS_STORAGE_KEY);
    if (!raw) return [];
    const parsed = JSON.parse(raw);
    if (!Array.isArray(parsed)) return [];
    return parsed
      .filter((item) => item && typeof item === 'object' && typeof item.deviceId === 'string')
      .slice(0, 12);
  } catch {
    return [];
  }
}

function writeDeviceSessions(list: DeviceSessionRecord[]): void {
  try {
    safeStorageSet(DEVICE_SESSIONS_STORAGE_KEY, JSON.stringify(list.slice(0, 12)));
  } catch {
    // Ignore restricted storage environments
  }
}

export function registerOrUpdateDeviceSession(
  account: UserAccount,
  loginMethod: AuthLoginMethod,
  deviceId: string = getOrCreateDeviceId(),
  now: number = Date.now()
): DeviceSessionRecord[] {
  const existing = readDeviceSessions();
  const label = detectDeviceLabel();
  const prev = existing.find((s) => s.deviceId === deviceId);
  const updatedRecord: DeviceSessionRecord = {
    deviceId,
    userCode: account.userCode,
    username: account.username,
    fullName: account.fullName,
    role: account.role,
    branch: account.branch,
    loginMethod,
    deviceLabel: label,
    startedAt: prev && prev.userCode === account.userCode && prev.active ? prev.startedAt : now,
    lastActiveAt: now,
    active: true
  };
  const next = [updatedRecord, ...existing.filter((s) => s.deviceId !== deviceId)].slice(0, 12);
  writeDeviceSessions(next);
  return next;
}

export function deactivateDeviceSession(deviceId: string): DeviceSessionRecord[] {
  const existing = readDeviceSessions();
  const next = existing.map((s) =>
    s.deviceId === deviceId ? { ...s, active: false, lastActiveAt: Date.now() } : s
  );
  writeDeviceSessions(next);
  return next;
}

export function clearAllLocalDeviceSessions(): DeviceSessionRecord[] {
  safeStorageRemove(DEVICE_SESSIONS_STORAGE_KEY);
  return [];
}
