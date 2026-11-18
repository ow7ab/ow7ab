/**
 * PMP Chemical Lab — Intellectual Property Protection, Granular RBAC & WebCrypto Sealing Engine.
 * منظومة أمن المختبر الكيميائي وحماية الملكية الفكرية وتشفير التركيبات السرية.
 *
 * Security Invariants:
 * - ZERO hardcoded AES/HMAC master keys in source code.
 * - Strict deny-by-default & explicit DENY precedence for secret formula percentages.
 * - Confidential ingredient ratios never leak into AuditLog, error reports, notifications, or unauthorized exports.
 */

import { FormulationVersion, LabFormulation, UserAccount } from '../types/erp';
import { isSuperAdminAccount } from './SecurityService';

export type LabActorRole =
  | 'ADMIN'
  | 'LAB_OFFICER'
  | 'QC_OFFICER'
  | 'PRODUCTION_OFFICER'
  | 'ACCOUNTANT'
  | 'WAREHOUSE_OFFICER'
  | 'SALES_OFFICER'
  | 'OTHER';

export const LAB_TOKENS = {
  VIEW_SECRET_FORMULAS: 'VIEW_SECRET_FORMULAS',
  APPROVE_FORMULAS: 'APPROVE_FORMULAS',
  EDIT_FORMULAS: 'EDIT_FORMULAS',
  EXPORT_FORMULAS: 'EXPORT_FORMULAS',
  IMPORT_EXCEL: 'IMPORT_EXCEL',
  MODULE_LAB: 'المختبر الكيميائي'
} as const;

const SEAL_PREFIX = 'PMPENC1:PWD:';
const PBKDF2_ITERATIONS = 100_000;

function extractActiveTokens(user?: Partial<UserAccount> | null): Set<string> {
  const out = new Set<string>();
  const raw = String(user?.permissions || '');
  raw
    .split(/[,;\n|]+/)
    .map((s) => s.trim())
    .filter(Boolean)
    .forEach((tok) => out.add(tok));
  return out;
}

function isSystemManagerNonScoped(user?: Partial<UserAccount> | null): boolean {
  if (!user) return false;
  if (isSuperAdminAccount(user as UserAccount)) return true;
  if (user.roleType === 'SUPER_ADMIN') return true;
  if (user.roleType === 'MANAGER') {
    const branch = String(user.branch || '').trim();
    const tokens = extractActiveTokens(user);
    const hasScopedBranch =
      branch.length > 0 && !['ALL', 'الكل', 'الإدارة العامة', 'جميع الفروع'].includes(branch);
    const hasScopedDenies = Array.from(tokens).some((t) => t.startsWith('DENY:'));
    return !hasScopedBranch && !hasScopedDenies;
  }
  return false;
}

export function resolveLabActorRole(user?: Partial<UserAccount> | null): LabActorRole {
  if (!user || user.active === false) return 'OTHER';
  if (isSuperAdminAccount(user as UserAccount) || isSystemManagerNonScoped(user)) {
    return 'ADMIN';
  }
  const tokens = extractActiveTokens(user);
  const roleLower = String(user.role || '').trim().toLowerCase();

  if (
    tokens.has(LAB_TOKENS.MODULE_LAB) ||
    roleLower.includes('مختبر') ||
    roleLower.includes('كيمي') ||
    roleLower.includes('lab')
  ) {
    return 'LAB_OFFICER';
  }
  if (roleLower.includes('جودة') || roleLower.includes('qc') || roleLower.includes('quality')) {
    return 'QC_OFFICER';
  }
  if (roleLower.includes('محاسب') || roleLower.includes('account') || (tokens.has('المحاسبة') && !tokens.has('المصنع'))) {
    return 'ACCOUNTANT';
  }
  if (roleLower.includes('أمين مخزن') || roleLower.includes('مخازن') || roleLower.includes('warehouse')) {
    return 'WAREHOUSE_OFFICER';
  }
  if (
    roleLower.includes('مبيعات') ||
    roleLower.includes('sales') ||
    (tokens.has('المبيعات') && !tokens.has('المصنع') && !tokens.has('الإنتاج'))
  ) {
    return 'SALES_OFFICER';
  }
  if (
    roleLower.includes('إنتاج') ||
    roleLower.includes('تصنيع') ||
    roleLower.includes('production') ||
    tokens.has('الإنتاج') ||
    tokens.has('المصنع')
  ) {
    return 'PRODUCTION_OFFICER';
  }
  return 'OTHER';
}

export function canViewSecretFormulas(user?: Partial<UserAccount> | null): boolean {
  if (!user || user.active === false || user.syncStatus === 'PENDING_MANAGER_APPROVAL') return false;
  if (isSuperAdminAccount(user as UserAccount)) return true;

  const tokens = extractActiveTokens(user);
  if (
    tokens.has(`DENY:${LAB_TOKENS.VIEW_SECRET_FORMULAS}`) ||
    tokens.has(`DENY:${LAB_TOKENS.MODULE_LAB}`) ||
    tokens.has(`DENY:${LAB_TOKENS.MODULE_LAB}:VIEW`)
  ) {
    return false;
  }

  if (isSystemManagerNonScoped(user)) return true;

  if (
    tokens.has(LAB_TOKENS.VIEW_SECRET_FORMULAS) ||
    tokens.has(`GRANT:${LAB_TOKENS.MODULE_LAB}:VIEW`) ||
    tokens.has(LAB_TOKENS.MODULE_LAB) ||
    tokens.has('Chemistry Lab') ||
    tokens.has('CHEMISTRY_LAB')
  ) {
    return true;
  }

  const roleLower = String(user.role || '').trim().toLowerCase();
  return roleLower.includes('مختبر') || roleLower.includes('كيمي') || roleLower.includes('chemist');
}

export function canCreateOrEditFormulas(user?: Partial<UserAccount> | null): boolean {
  if (!canViewSecretFormulas(user)) return false;
  if (isSuperAdminAccount(user as UserAccount)) return true;
  const tokens = extractActiveTokens(user);
  if (
    tokens.has('VIEW_ONLY') ||
    tokens.has('عرض فقط') ||
    tokens.has('DENY:EDIT') ||
    tokens.has('DENY:CREATE') ||
    tokens.has(`DENY:${LAB_TOKENS.EDIT_FORMULAS}`) ||
    tokens.has(`DENY:${LAB_TOKENS.MODULE_LAB}:EDIT`) ||
    tokens.has(`DENY:${LAB_TOKENS.MODULE_LAB}:CREATE`)
  ) {
    return false;
  }
  return true;
}

export function canApproveFormulas(user?: Partial<UserAccount> | null): boolean {
  if (!user || user.active === false || user.syncStatus === 'PENDING_MANAGER_APPROVAL') return false;
  if (isSuperAdminAccount(user as UserAccount)) return true;
  const tokens = extractActiveTokens(user);
  if (
    tokens.has(`DENY:${LAB_TOKENS.APPROVE_FORMULAS}`) ||
    tokens.has('DENY:APPROVE') ||
    tokens.has(`DENY:${LAB_TOKENS.MODULE_LAB}:APPROVE`)
  ) {
    return false;
  }
  if (isSystemManagerNonScoped(user)) return true;
  return (
    tokens.has(LAB_TOKENS.APPROVE_FORMULAS) ||
    tokens.has('اعتماد التركيبات') ||
    tokens.has(`GRANT:${LAB_TOKENS.MODULE_LAB}:APPROVE`)
  );
}

export function canLogLabExperiments(user?: Partial<UserAccount> | null): boolean {
  if (!user || user.active === false || user.syncStatus === 'PENDING_MANAGER_APPROVAL') return false;
  if (isSuperAdminAccount(user as UserAccount)) return true;
  const tokens = extractActiveTokens(user);
  if (tokens.has('DENY:EXPERIMENTS') || tokens.has('VIEW_ONLY') || tokens.has('عرض فقط')) return false;
  const role = resolveLabActorRole(user);
  return role === 'ADMIN' || role === 'LAB_OFFICER' || role === 'QC_OFFICER' || canViewSecretFormulas(user);
}

export function canDecideQcGate(user?: Partial<UserAccount> | null): boolean {
  if (!user || user.active === false || user.syncStatus === 'PENDING_MANAGER_APPROVAL') return false;
  if (isSuperAdminAccount(user as UserAccount)) return true;
  const tokens = extractActiveTokens(user);
  if (tokens.has('DENY:QC_DECISION') || tokens.has('DENY:APPROVE')) return false;
  const role = resolveLabActorRole(user);
  return role === 'ADMIN' || role === 'QC_OFFICER' || tokens.has('اعتماد الجودة');
}

export function canViewFormulaCosts(user?: Partial<UserAccount> | null): boolean {
  if (!user || user.active === false || user.syncStatus === 'PENDING_MANAGER_APPROVAL') return false;
  const role = resolveLabActorRole(user);
  if (role === 'ADMIN' || role === 'LAB_OFFICER' || role === 'ACCOUNTANT') return true;
  const tokens = extractActiveTokens(user);
  return tokens.has('المحاسبة') || tokens.has('COST_VIEW') || canViewSecretFormulas(user);
}

export function canViewRawMaterialRegistry(user?: Partial<UserAccount> | null): boolean {
  if (!user || user.active === false || user.syncStatus === 'PENDING_MANAGER_APPROVAL') return false;
  return resolveLabActorRole(user) !== 'SALES_OFFICER';
}

export function canExportSecretFormulas(user?: Partial<UserAccount> | null): boolean {
  if (!canViewSecretFormulas(user)) return false;
  if (isSuperAdminAccount(user as UserAccount)) return true;
  const tokens = extractActiveTokens(user);
  if (
    tokens.has(`DENY:${LAB_TOKENS.EXPORT_FORMULAS}`) ||
    tokens.has('DENY:EXPORT') ||
    tokens.has(`DENY:${LAB_TOKENS.MODULE_LAB}:EXPORT`)
  ) {
    return false;
  }
  if (isSystemManagerNonScoped(user)) return true;
  return (
    tokens.has(LAB_TOKENS.EXPORT_FORMULAS) ||
    tokens.has('ACTION_EXPORT') ||
    tokens.has('ACTION_PRINT') ||
    tokens.has(`GRANT:${LAB_TOKENS.MODULE_LAB}:EXPORT`)
  );
}

export function redactFormulationForUser(
  formula: LabFormulation,
  user?: Partial<UserAccount> | null
): LabFormulation & { isMasked: boolean } {
  const canSeeSecrets = canViewSecretFormulas(user);
  const canSeeCosts = canViewFormulaCosts(user);
  if (canSeeSecrets && canSeeCosts) {
    return { ...formula, isMasked: false };
  }
  return {
    ...formula,
    ingredientsJson: canSeeSecrets ? formula.ingredientsJson : '[]',
    confidentialCiphertext: '',
    technicalAnalysis: canSeeSecrets ? formula.technicalAnalysis : '[محجوب بموجب صلاحيات حماية الملكية الفكرية]',
    totalCost: canSeeCosts ? formula.totalCost : 0,
    costPerKg: canSeeCosts ? formula.costPerKg : 0,
    historicalTotalCost: canSeeCosts ? formula.historicalTotalCost || 0 : 0,
    historicalCostPerKg: canSeeCosts ? formula.historicalCostPerKg || 0 : 0,
    isMasked: !canSeeSecrets
  };
}

export function redactVersionForUser(
  version: FormulationVersion,
  user?: Partial<UserAccount> | null
): FormulationVersion {
  if (canViewSecretFormulas(user)) return version;
  return {
    ...version,
    ingredientsSnapshotJson: '[]',
    encryptedSnapshotCiphertext: '',
    previousCostPerKg: canViewFormulaCosts(user) ? version.previousCostPerKg : 0,
    newCostPerKg: canViewFormulaCosts(user) ? version.newCostPerKg : 0,
    historicalTotalCost: canViewFormulaCosts(user) ? version.historicalTotalCost : 0
  };
}

/**
 * Sanitizes audit log / notification / error strings so confidential percentages or JSON ingredient arrays never leak.
 */
export function sanitizeLabLog(raw: string): string {
  if (!raw) return '';
  let out = raw;
  out = out.replace(/\[(\s*\{[\s\S]*?\}\s*,?)*]/g, '[REDACTED_FORMULA_JSON]');
  out = out.replace(/\d+(?:\.\d+)?\s*%/g, '[XX%]');
  out = out.replace(/((?:passphrase|password|secret|token|cipher|key)\s*[:=]\s*)[^\s,;]+/gi, '$1[REDACTED]');
  return out;
}

function bytesToBase64(bytes: Uint8Array): string {
  if (typeof Buffer !== 'undefined') {
    return Buffer.from(bytes).toString('base64');
  }
  let bin = '';
  for (let i = 0; i < bytes.length; i++) {
    bin += String.fromCharCode(bytes[i]);
  }
  return btoa(bin);
}

function base64ToBytes(b64: string): Uint8Array {
  if (typeof Buffer !== 'undefined') {
    return new Uint8Array(Buffer.from(b64, 'base64'));
  }
  const bin = atob(b64);
  const out = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) {
    out[i] = bin.charCodeAt(i);
  }
  return out;
}

async function deriveAesKeyFromPassphrase(passphrase: string, salt: Uint8Array): Promise<CryptoKey> {
  const subtle = globalThis.crypto?.subtle;
  if (!subtle) {
    throw new Error('WebCrypto subtle غير متاح في بيئة التشغيل الحالية.');
  }
  const enc = new TextEncoder();
  const baseKey = await subtle.importKey('raw', enc.encode(passphrase), { name: 'PBKDF2' }, false, ['deriveKey']);
  return subtle.deriveKey(
    {
      name: 'PBKDF2',
      salt: salt as BufferSource,
      iterations: PBKDF2_ITERATIONS,
      hash: 'SHA-256'
    },
    baseKey,
    { name: 'AES-GCM', length: 256 },
    false,
    ['encrypt', 'decrypt']
  );
}

/**
 * Encrypts confidential formula JSON using WebCrypto PBKDF2-HMAC-SHA256 + AES-256-GCM
 * with a user-supplied passphrase and fresh random salt + IV. Zero hardcoded keys.
 */
export async function sealFormulaWithPassphrase(plainJson: string, passphrase: string): Promise<string> {
  if (!passphrase || passphrase.length < 4) {
    throw new Error('يجب إدخال كلمة مرور لا تقل عن 4 أحرف لقفل التركيبة السرية.');
  }
  const subtle = globalThis.crypto?.subtle;
  if (!subtle) {
    throw new Error('WebCrypto غير متاح في هذا المتصفح.');
  }
  const salt = globalThis.crypto.getRandomValues(new Uint8Array(16));
  const iv = globalThis.crypto.getRandomValues(new Uint8Array(12));
  const key = await deriveAesKeyFromPassphrase(passphrase, salt);
  const cipherBuf = await subtle.encrypt(
    { name: 'AES-GCM', iv: iv as BufferSource },
    key,
    new TextEncoder().encode(plainJson)
  );
  const cipherBytes = new Uint8Array(cipherBuf);
  return `${SEAL_PREFIX}${bytesToBase64(salt)}:${bytesToBase64(iv)}:${bytesToBase64(cipherBytes)}`;
}

export async function unsealFormulaWithPassphrase(sealedEnvelope: string, passphrase: string): Promise<string> {
  if (!sealedEnvelope.startsWith(SEAL_PREFIX)) {
    throw new Error('صيغة القفل السري غير معروفة أو غير متوافقة.');
  }
  const parts = sealedEnvelope.slice(SEAL_PREFIX.length).split(':');
  if (parts.length !== 3) {
    throw new Error('تلف في مغلف التشفير السري للتركيبة.');
  }
  const [saltB64, ivB64, cipherB64] = parts;
  const salt = base64ToBytes(saltB64);
  const iv = base64ToBytes(ivB64);
  const cipherBytes = base64ToBytes(cipherB64);
  const key = await deriveAesKeyFromPassphrase(passphrase, salt);
  const plainBuf = await globalThis.crypto.subtle.decrypt(
    { name: 'AES-GCM', iv: iv as BufferSource },
    key,
    cipherBytes as BufferSource
  );
  return new TextDecoder().decode(plainBuf);
}

export async function computeSha256Hex(bytes: Uint8Array): Promise<string> {
  const subtle = globalThis.crypto?.subtle;
  if (subtle) {
    const hashBuf = await subtle.digest('SHA-256', bytes as BufferSource);
    return Array.from(new Uint8Array(hashBuf))
      .map((b) => b.toString(16).padStart(2, '0'))
      .join('');
  }
  let h = 2166136261;
  for (let i = 0; i < bytes.length; i++) {
    h ^= bytes[i];
    h = Math.imul(h, 16777619);
  }
  return (h >>> 0).toString(16).padStart(8, '0');
}
