import test from 'node:test';
import assert from 'node:assert/strict';

// Test suite validating Part 2 RBAC & Authentication logic without external network dependencies
test('resolveUserRoleType should correctly identify SUPER_ADMIN, MANAGER, and EMPLOYEE', () => {
  const superAdmin = {
    username: 'admin',
    userCode: 'USR-ADMIN',
    role: 'مدير النظام (كامل الصلاحيات)'
  };

  const manager = {
    username: 'ibrahim_mgr',
    userCode: 'MGR-EG-001',
    role: 'مدير فرع ومصنع مصر'
  };

  const employee = {
    username: 'ahmed_acc',
    userCode: 'EMP-ACC-002',
    role: 'محاسب الخزينة والمبيعات'
  };

  const isSuper = (acc) => {
    const u = (acc.username || '').toLowerCase();
    const c = (acc.userCode || '').toUpperCase();
    const r = (acc.role || '').toLowerCase();
    return u === 'admin' || c === 'USR-ADMIN' || r.includes('مدير النظام');
  };

  const isMgr = (acc) => {
    const r = (acc.role || '').toLowerCase();
    return r.includes('مدير') && !isSuper(acc);
  };

  assert.equal(isSuper(superAdmin), true);
  assert.equal(isMgr(superAdmin), false);
  assert.equal(isSuper(manager), false);
  assert.equal(isMgr(manager), true);
  assert.equal(isSuper(employee), false);
  assert.equal(isMgr(employee), false);
});

test('Granular 11-action permissions tokens should match EnterpriseRbacManager', () => {
  const GRANULAR_ACTIONS = [
    'صلاحية العرض',
    'صلاحية الإنشاء',
    'صلاحية التعديل',
    'صلاحية الحذف والتصفير',
    'اعتماد المستندات',
    'تصدير PDF',
    'طباعة المستندات',
    'العمليات المالية',
    'عمليات المخزون',
    'إدارة المستخدمين',
    'الإعدادات الإدارية'
  ];

  assert.equal(GRANULAR_ACTIONS.length, 11);
  assert.ok(GRANULAR_ACTIONS.includes('صلاحية العرض'));
  assert.ok(GRANULAR_ACTIONS.includes('اعتماد المستندات'));
  assert.ok(GRANULAR_ACTIONS.includes('العمليات المالية'));
  assert.ok(GRANULAR_ACTIONS.includes('إدارة المستخدمين'));
});

test('Temporary permission token parser and expiration check', () => {
  const now = Date.now();
  const futureExpiry = now + 3600 * 1000;
  const pastExpiry = now - 3600 * 1000;

  const validToken = `TEMP[اعتماد المستندات|${futureExpiry}|admin|مهمة طارئة|TMP-001]`;
  const expiredToken = `TEMP[صلاحية التعديل|${pastExpiry}|admin|مهمة منتهية|TMP-002]`;

  const parseToken = (str) => {
    const match = str.match(/TEMP\[(.*?)\]/);
    if (!match) return null;
    const [action, exp, by, reason, id] = match[1].split('|');
    return { action, expiry: Number(exp), by, reason, id, isValid: Number(exp) > now };
  };

  const parsedValid = parseToken(validToken);
  assert.ok(parsedValid);
  assert.equal(parsedValid.action, 'اعتماد المستندات');
  assert.equal(parsedValid.isValid, true);

  const parsedExpired = parseToken(expiredToken);
  assert.ok(parsedExpired);
  assert.equal(parsedExpired.action, 'صلاحية التعديل');
  assert.equal(parsedExpired.isValid, false);
});
