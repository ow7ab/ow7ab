/**
 * PMP PAINTS Enterprise Security & RBAC Service
 * Strictly mirrors Android `EnterpriseRbacManager.kt` and `MainActivity.kt` permission functions.
 */

import { TemporaryPermissionGrant, UserAccount, UserRoleType } from '../types/erp';

export const SCOPED_ADMIN_MARKER = 'SCOPED_ADMIN';

export const GRANULAR_ACTIONS = {
  VIEW: 'صلاحية العرض',
  CREATE: 'صلاحية الإنشاء',
  EDIT: 'صلاحية التعديل',
  DELETE: 'صلاحية الحذف والتصفير',
  APPROVE: 'اعتماد المستندات',
  EXPORT: 'تصدير PDF',
  PRINT: 'طباعة المستندات',
  FINANCIAL_OPS: 'العمليات المالية',
  INVENTORY_OPS: 'عمليات المخزون',
  USER_MANAGEMENT: 'إدارة المستخدمين',
  ADMIN_SETTINGS: 'الإعدادات الإدارية'
} as const;

export const ERP_MODULES = [
  { id: 'FACTORY', nameAr: 'المصنع', nameEn: 'Factory', aliases: ['المصنع', 'Factory', 'FACTORY'] },
  { id: 'WAREHOUSES', nameAr: 'المخازن', nameEn: 'Warehouses', aliases: ['المخازن', 'Warehouses', 'WAREHOUSES', 'INVENTORY'] },
  { id: 'PRODUCTION', nameAr: 'الإنتاج', nameEn: 'Production', aliases: ['الإنتاج', 'Production', 'PRODUCTION', 'أوامر الإنتاج'] },
  { id: 'RAW_MATERIALS', nameAr: 'مواد الإنتاج', nameEn: 'Raw Materials', aliases: ['مواد الإنتاج', 'المواد الخام', 'Raw materials', 'RAW_MATERIALS', 'مخزن الخام'] },
  { id: 'PACKAGING', nameAr: 'التعبئة والتغليف', nameEn: 'Packaging', aliases: ['التعبئة والتغليف', 'Packaging', 'PACKAGING'] },
  { id: 'ACCOUNTING', nameAr: 'المحاسبة', nameEn: 'Accounting', aliases: ['المحاسبة', 'Accounting', 'ACCOUNTING'] },
  { id: 'TREASURY', nameAr: 'الخزن', nameEn: 'Treasury', aliases: ['الخزن', 'Treasury', 'TREASURY'] },
  { id: 'SALES', nameAr: 'المبيعات', nameEn: 'Sales', aliases: ['المبيعات', 'Sales', 'SALES'] },
  { id: 'TRANSFERS', nameAr: 'التحويلات', nameEn: 'Transfers', aliases: ['التحويلات', 'Transfers', 'TRANSFERS'] },
  { id: 'CUSTOMERS', nameAr: 'العملاء', nameEn: 'Customers', aliases: ['العملاء', 'Customers', 'CUSTOMERS', 'مديونيات العملاء'] },
  { id: 'SUPPLIERS', nameAr: 'المشتريات', nameEn: 'Purchases & Suppliers', aliases: ['الموردين', 'المشتريات', 'Suppliers', 'SUPPLIERS', 'PURCHASES'] },
  { id: 'CHEMISTRY_LAB', nameAr: 'المختبر الكيميائي', nameEn: 'Chemistry Lab', aliases: ['المختبر الكيميائي', 'Chemistry Lab', 'CHEMISTRY_LAB'] },
  { id: 'PRICES', nameAr: 'الأسعار', nameEn: 'Prices', aliases: ['الأسعار', 'Prices', 'POSTER'] },
  { id: 'REPORTS', nameAr: 'التقارير', nameEn: 'Reports', aliases: ['التقارير', 'التقارير (كاملة)', 'Reports', 'REPORTS'] },
  { id: 'USERS', nameAr: 'المستخدمين', nameEn: 'Users & RBAC', aliases: ['المستخدمين', 'إدارة المستخدمين', 'Users', 'USERS'] },
  { id: 'SETTINGS', nameAr: 'الإعدادات', nameEn: 'Settings', aliases: ['الإعدادات', 'الإعدادات الإدارية', 'Settings', 'SETTINGS'] }
];

const SYSTEM_MANAGER_DEFAULT_TOKENS = [
  'المصنع',
  'الإنتاج',
  'المختبر الكيميائي',
  'مواد الإنتاج',
  'التعبئة والتغليف',
  'المخازن',
  'المحاسبة',
  'الخزن',
  'المشتريات',
  'الموردين',
  'العملاء',
  'التحويلات',
  'المبيعات',
  'تصدير PDF',
  'طباعة المستندات',
  'الأسعار',
  'التقارير',
  'التقارير (كاملة)',
  'تقارير المبيعات',
  'تقارير مبيعات الشهر الحالي',
  'تقارير المواد الخام',
  'تقارير المنتج التام',
  'تقارير حركة المخزون',
  'تقارير حركات الخزن',
  'تقارير المديونيات',
  'المستخدمين',
  'الإعدادات',
  'تعديل نوع الصنف',
  'صلاحية الحذف والتصفير'
];

export function parseTempGrant(rawToken: string, fallbackUserCode: string = ''): TemporaryPermissionGrant | null {
  const trimmed = (rawToken || '').trim();
  if (!trimmed.startsWith('TEMP[') || !trimmed.endsWith(']')) return null;
  const inner = trimmed.slice(5, -1);
  const parts = inner.split('|');
  if (parts.length < 2) return null;
  const permissionToken = parts[0].trim();
  const expiresAtMillis = Number(parts[1].trim());
  if (!permissionToken || !Number.isFinite(expiresAtMillis)) return null;
  const grantedBy = (parts[2] || '').trim() || 'Admin';
  const reason = (parts[3] || '').trim() || 'صلاحية مؤقتة';
  const grantId = (parts[4] || '').trim() || `TMP-${expiresAtMillis}`;
  return {
    grantId,
    targetUserCode: fallbackUserCode,
    permissionToken,
    grantedBy,
    grantedAtMillis: Math.max(0, expiresAtMillis - 86_400_000),
    expiresAtMillis,
    reason
  };
}

/**
 * Checks whether the account is strictly the primary `admin` Super Admin account.
 * Mirrors `MainActivity.kt#isSuperAdminAccount`.
 */
export function isSuperAdminAccount(account: Partial<UserAccount> | null | undefined): boolean {
  if (!account) return false;
  return (account.username || '').trim().toLowerCase() === 'admin';
}

/**
 * Mirrors `EnterpriseRbacManager.kt#isScopedAdministrator`.
 * Scoped Administrators do NOT automatically receive unrestricted manager privileges.
 */
export function isScopedAdministrator(account: Partial<UserAccount> | null | undefined): boolean {
  if (!account || isSuperAdminAccount(account)) return false;
  const role = (account.role || '').trim();
  const rawPerms = account.permissions || '';
  return (
    rawPerms.includes(SCOPED_ADMIN_MARKER) ||
    role.includes('مخصص') ||
    role.includes('مقيد') ||
    role.includes('فرع') ||
    role.includes('قطاع') ||
    role.includes('مسؤول نظام') ||
    role.includes('مسؤول صلاحيات') ||
    role.toLowerCase() === 'scoped admin'
  );
}

/**
 * Mirrors `MainActivity.kt#isSystemManager`.
 */
export function isSystemManager(account: Partial<UserAccount> | null | undefined): boolean {
  if (!account) return false;
  if (isScopedAdministrator(account)) return false;
  const role = (account.role || '').trim();
  const username = (account.username || '').trim().toLowerCase();
  return role.includes('مدير النظام') || username === 'admin';
}

/**
 * Determines the 3-tier system role for display/UI grouping while keeping scoped admins isolated.
 */
export function resolveUserRoleType(account: Partial<UserAccount> | null | undefined): UserRoleType {
  if (!account) return 'EMPLOYEE';
  if (isSuperAdminAccount(account)) return 'SUPER_ADMIN';
  if (isSystemManager(account)) return 'SUPER_ADMIN';

  const roleStr = (account.role || '').toLowerCase();
  if (
    !isScopedAdministrator(account) &&
    (roleStr.includes('المدير') || roleStr.includes('manager') || roleStr.includes('مشرف'))
  ) {
    return 'MANAGER';
  }
  if (isScopedAdministrator(account) && (roleStr.includes('مدير فرع') || roleStr.includes('مسؤول نظام'))) {
    return 'MANAGER';
  }
  return 'EMPLOYEE';
}

/**
 * Mirrors `EnterpriseRbacManager.kt#extractActiveRawTokens` (filters expired TEMP grants).
 */
export function extractActiveRawTokens(
  account: Partial<UserAccount> | null | undefined,
  nowMillis: number = Date.now()
): Set<string> {
  const result = new Set<string>();
  if (!account || account.active === false) return result;
  const raw = account.permissions || '';
  raw
    .split(',')
    .map((t) => t.trim())
    .filter(Boolean)
    .forEach((token) => {
      if (token.startsWith('TEMP[')) {
        const parsed = parseTempGrant(token, account.userCode || '');
        if (parsed && nowMillis <= parsed.expiresAtMillis) {
          result.add(parsed.permissionToken);
        }
      } else {
        result.add(token);
      }
    });
  return result;
}

/**
 * Mirrors `MainActivity.kt#parsedPermissionsSet`.
 */
export function parsedPermissionsSet(
  account: Partial<UserAccount> | null | undefined,
  nowMillis: number = Date.now()
): Set<string> {
  if (!account || account.active === false) return new Set();
  const explicitActive = extractActiveRawTokens(account, nowMillis);
  if (isSystemManager(account)) {
    return new Set([...SYSTEM_MANAGER_DEFAULT_TOKENS, ...explicitActive]);
  }
  return explicitActive;
}

/**
 * Resolves module aliases (Arabic <-> English <-> Code) matching `EnterpriseRbacManager.ErpModule`.
 */
function expandModuleAliases(moduleName: string): string[] {
  const clean = (moduleName || '').trim();
  if (!clean) return [];
  const lower = clean.toLowerCase();
  const found = ERP_MODULES.find(
    (m) =>
      m.id.toLowerCase() === lower ||
      m.nameAr.toLowerCase() === lower ||
      m.nameEn.toLowerCase() === lower ||
      m.aliases.some((a) => a.toLowerCase() === lower)
  );
  if (!found) return [clean];
  return Array.from(new Set([found.nameAr, found.nameEn, found.id, ...found.aliases]));
}

/**
 * Mirrors `EnterpriseRbacManager.hasModuleAccess` & `MainActivity.kt#hasModulePermission`.
 */
export function checkModuleAccess(
  account: UserAccount | null | undefined,
  moduleName: string,
  nowMillis: number = Date.now()
): boolean {
  if (!account || !account.active) return false;
  if (account.syncStatus === 'PENDING_MANAGER_APPROVAL') return false;
  if (isSuperAdminAccount(account) || isSystemManager(account)) return true;

  const activeTokens = parsedPermissionsSet(account, nowMillis);
  const candidates = expandModuleAliases(moduleName);

  // Explicit DENY wins
  for (const c of candidates) {
    if (activeTokens.has(`DENY:${c}`)) return false;
  }

  for (const token of activeTokens) {
    for (const c of candidates) {
      if (token.toLowerCase() === c.toLowerCase()) return true;
      if (token.toLowerCase().startsWith(`grant:${c.toLowerCase()}:`)) return true;
    }
  }

  return false;
}

/**
 * Mirrors `MainActivity.kt#hasReportTabPermission`.
 */
export function hasReportTabPermission(
  account: UserAccount | null | undefined,
  tabName: string,
  nowMillis: number = Date.now()
): boolean {
  if (!account || !account.active) return false;
  if (isSystemManager(account)) return true;
  const perms = parsedPermissionsSet(account, nowMillis);
  if (perms.has('التقارير (كاملة)') || perms.has('المحاسبة')) return true;

  const specificKeyMap: Record<string, string> = {
    'المبيعات': 'تقارير المبيعات',
    'مبيعات الشهر الحالي': 'تقارير المبيعات',
    'المديونيات': 'تقارير المديونيات',
    'العملاء': 'تقارير المديونيات',
    'حركة المخزون': 'تقارير حركة المخزون',
    'المنتج التام': 'تقارير حركة المخزون',
    'حركات الخزن': 'تقارير حركات الخزن',
    'تقارير المواد الخام': 'تقارير المواد الخام',
    'المواد الخام في المخزن': 'تقارير المواد الخام'
  };
  const specificKey = specificKeyMap[tabName] || `تقارير ${tabName}`;
  if (perms.has(specificKey) || perms.has(`تقارير ${tabName}`)) return true;

  const hasGranularReportPerms = Array.from(perms).some((p) => p.startsWith('تقارير '));
  if (perms.has('التقارير') && !hasGranularReportPerms) return true;
  return false;
}

/**
 * Validates whether an account can perform a specific granular operation action.
 * Strict parity with Android: Scoped Admins and Employees only get explicitly granted + active TEMP permissions.
 */
export function checkActionPermission(
  account: UserAccount | null | undefined,
  actionToken: string,
  nowMillis: number = Date.now()
): boolean {
  if (!account || !account.active) return false;
  if (account.syncStatus === 'PENDING_MANAGER_APPROVAL') return false;
  if (isSuperAdminAccount(account) || isSystemManager(account)) return true;

  const activeTokens = parsedPermissionsSet(account, nowMillis);

  if (activeTokens.has(`DENY:${actionToken}`)) return false;
  if (activeTokens.has(actionToken)) return true;

  // Allow basic VIEW if user has any active module grant
  if (actionToken === GRANULAR_ACTIONS.VIEW && activeTokens.size > 0) {
    return true;
  }

  return false;
}

/**
 * Mirrors `EnterpriseRbacManager.canConfigureEmployeePermissions`.
 */
export function canConfigureEmployeePermissions(
  actor: UserAccount | null | undefined,
  nowMillis: number = Date.now()
): boolean {
  if (!actor || !actor.active) return false;
  if (actor.syncStatus === 'PENDING_MANAGER_APPROVAL') return false;
  if (isSuperAdminAccount(actor)) return true;
  const tokens = extractActiveRawTokens(actor, nowMillis);
  const hasExplicit =
    tokens.has(GRANULAR_ACTIONS.USER_MANAGEMENT) ||
    tokens.has('ACTION:USER_MANAGEMENT') ||
    tokens.has('GRANT:المستخدمين:USER_MANAGEMENT');
  const isDenied =
    tokens.has(`DENY:${GRANULAR_ACTIONS.USER_MANAGEMENT}`) ||
    tokens.has('DENY:USER_MANAGEMENT') ||
    tokens.has('DENY:المستخدمين:USER_MANAGEMENT');
  return hasExplicit && !isDenied;
}
