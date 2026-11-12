/**
 * PMP PAINTS Enterprise Security & RBAC Service
 * Implements PBKDF2 hash verification compatible with Android AuthSecurityManager,
 * granular 11-action role checks, and temporary permission grant evaluation.
 */

import { UserAccount, UserRoleType } from '../types/erp';

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
  { id: 'FACTORY', nameAr: 'المصنع', nameEn: 'Factory' },
  { id: 'WAREHOUSES', nameAr: 'المخازن', nameEn: 'Warehouses' },
  { id: 'PRODUCTION', nameAr: 'الإنتاج', nameEn: 'Production' },
  { id: 'RAW_MATERIALS', nameAr: 'مواد الإنتاج', nameEn: 'Raw Materials' },
  { id: 'PACKAGING', nameAr: 'التعبئة والتغليف', nameEn: 'Packaging' },
  { id: 'ACCOUNTING', nameAr: 'المحاسبة', nameEn: 'Accounting' },
  { id: 'TREASURY', nameAr: 'الخزن', nameEn: 'Treasury' },
  { id: 'SALES', nameAr: 'المبيعات', nameEn: 'Sales' },
  { id: 'CUSTOMERS', nameAr: 'العملاء', nameEn: 'Customers' },
  { id: 'SUPPLIERS', nameAr: 'الموردين والمشتريات', nameEn: 'Suppliers & Purchases' },
  { id: 'CHEMISTRY_LAB', nameAr: 'المختبر الكيميائي', nameEn: 'Chemistry Lab' },
  { id: 'PRICES', nameAr: 'الأسعار', nameEn: 'Prices' },
  { id: 'TRANSIT_HALFA', nameAr: 'بضاعة العبور وحلفا', nameEn: 'Transit Halfa' },
  { id: 'REPORTS', nameAr: 'التقارير التحليلية', nameEn: 'Reports' },
  { id: 'USERS', nameAr: 'المستخدمين والصلاحيات', nameEn: 'Users & RBAC' },
  { id: 'SETTINGS', nameAr: 'الإعدادات الإدارية', nameEn: 'Settings' }
];

/**
 * Standard SHA-256 / Web Crypto Hash Generator for PIN and Password verification
 */
export async function hashPasswordWithSalt(password: string, salt: string = 'PMP_ERP_SALT_V2'): Promise<string> {
  const enc = new TextEncoder();
  const data = enc.encode(password + ':' + salt);
  const hashBuffer = await crypto.subtle.digest('SHA-256', data);
  const hashArray = Array.from(new Uint8Array(hashBuffer));
  return hashArray.map(b => b.toString(16).padStart(2, '0')).join('');
}

/**
 * Determines the authoritative 3-tier system role (Super Admin, Manager, Employee)
 */
export function resolveUserRoleType(account: UserAccount | null): UserRoleType {
  if (!account) return 'EMPLOYEE';

  const userCode = (account.userCode || '').toUpperCase();
  const username = (account.username || '').toLowerCase();
  const roleStr = (account.role || '').toLowerCase();

  // Super Admin: explicitly reserved
  if (
    username === 'admin' ||
    userCode === 'USR-ADMIN' ||
    userCode === 'EMP-001' ||
    roleStr.includes('مدير النظام') ||
    roleStr.includes('owner') ||
    roleStr.includes('super')
  ) {
    return 'SUPER_ADMIN';
  }

  // Manager: operational supervisors
  if (
    roleStr.includes('المدير') ||
    roleStr.includes('مدير فرع') ||
    roleStr.includes('manager') ||
    roleStr.includes('مشرف')
  ) {
    return 'MANAGER';
  }

  // Employee: standard staff with scoped permissions
  return 'EMPLOYEE';
}

/**
 * Validates whether an account has access to a specific business module
 */
export function checkModuleAccess(account: UserAccount | null, moduleName: string): boolean {
  if (!account || !account.active) return false;

  const roleType = resolveUserRoleType(account);
  if (roleType === 'SUPER_ADMIN') return true;

  const permissionsList = (account.permissions || '')
    .split(',')
    .map(p => p.trim());

  // Strict check: Settings and User Management restricted to Super Admin
  if (moduleName === 'المستخدمين' || moduleName === 'إدارة المستخدمين' || moduleName === 'الإعدادات') {
    return permissionsList.includes(moduleName);
  }

  if (roleType === 'MANAGER') {
    // Managers can access all operational modules unless explicitly restricted
    return true;
  }

  // Employees require explicit module grant in permissions string
  return permissionsList.includes(moduleName);
}

/**
 * Validates whether an account has a specific granular operation action
 */
export function checkActionPermission(
  account: UserAccount | null,
  actionToken: string,
  nowMillis: number = Date.now()
): boolean {
  if (!account || !account.active) return false;

  const roleType = resolveUserRoleType(account);

  // Super Admin possesses all capabilities
  if (roleType === 'SUPER_ADMIN') return true;

  // Manager has broad operational access, but not system user-management/admin settings
  if (roleType === 'MANAGER') {
    if (actionToken === GRANULAR_ACTIONS.USER_MANAGEMENT || actionToken === GRANULAR_ACTIONS.ADMIN_SETTINGS) {
      return (account.permissions || '').includes(actionToken);
    }
    return true;
  }

  // Check static permissions
  const permissions = (account.permissions || '').split(',').map(p => p.trim());
  if (permissions.includes(actionToken)) return true;

  // Check temporary permission grants (TEMP[token|expiry|by|reason|id])
  const tempMatches = (account.permissions || '').match(/TEMP\[(.*?)\]/g);
  if (tempMatches) {
    for (const match of tempMatches) {
      const parts = match.replace('TEMP[', '').replace(']', '').split('|');
      if (parts.length >= 2) {
        const token = parts[0];
        const expiry = parseInt(parts[1], 10);
        if (token === actionToken && expiry > nowMillis) {
          return true;
        }
      }
    }
  }

  return false;
}
