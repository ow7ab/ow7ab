import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import os from 'node:os';
import { pathToFileURL, fileURLToPath } from 'node:url';
import ts from 'typescript';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);
const WEB_DIR = path.resolve(__dirname, '..');
const SRC_DIR = path.join(WEB_DIR, 'src');
const tmpOutDir = fs.mkdtempSync(path.join(os.tmpdir(), 'pmp-server-audit-tests-'));

function transpileRelative(relPath, outRelPath, transformSource = (s) => s) {
  const fullPath = path.join(SRC_DIR, relPath);
  let source = fs.readFileSync(fullPath, 'utf8');
  source = transformSource(source);
  const transpiled = ts.transpileModule(source, {
    compilerOptions: {
      module: ts.ModuleKind.ESNext,
      target: ts.ScriptTarget.ES2022,
      moduleResolution: ts.ModuleResolutionKind.Bundler
    }
  });
  const outPath = path.join(tmpOutDir, outRelPath);
  fs.mkdirSync(path.dirname(outPath), { recursive: true });
  fs.writeFileSync(outPath, transpiled.outputText, 'utf8');
  return outPath;
}

const cloudLoggerPath = transpileRelative('services/CloudErrorLogger.ts', 'services/CloudErrorLogger.mjs');
transpileRelative('types/erp.ts', 'types/erp.mjs');
const securityPath = transpileRelative('services/SecurityService.ts', 'services/SecurityService.mjs', (s) =>
  s.replace('../types/erp', '../types/erp.mjs')
);

const CloudErrorLoggerMod = await import(pathToFileURL(cloudLoggerPath).href);
const SecurityMod = await import(pathToFileURL(securityPath).href);

test.after(() => {
  fs.rmSync(tmpOutDir, { recursive: true, force: true });
});

test('CloudErrorLogger classifies all 8 cloud error categories accurately', () => {
  const { classifyCloudError, sanitizeCloudMessage, CloudErrorLogger } = CloudErrorLoggerMod;

  assert.equal(classifyCloudError({ code: 'permission-denied', message: 'Missing permissions' }).category, 'PERMISSION_DENIED');
  assert.equal(classifyCloudError({ code: 'unauthenticated', message: 'ID token missing' }).category, 'UNAUTHENTICATED');
  assert.equal(classifyCloudError({ code: 'unavailable', message: 'Service unavailable' }).category, 'UNAVAILABLE');
  assert.equal(classifyCloudError({ code: 'deadline-exceeded', message: 'Request timeout' }).category, 'DEADLINE_EXCEEDED');
  assert.equal(classifyCloudError({ code: 'resource-exhausted', message: 'Quota exceeded' }).category, 'RESOURCE_EXHAUSTED');
  assert.equal(classifyCloudError({ code: 'failed-precondition', message: 'Requires composite index' }).category, 'FAILED_PRECONDITION');
  assert.equal(classifyCloudError(new Error('Failed to fetch: client is offline')).category, 'NETWORK_ERROR');
  assert.equal(classifyCloudError({ code: 'aborted', message: 'Concurrency conflict' }).category, 'DATA_CONFLICT');

  const dirty = 'Bearer eyJhbGciOiJIUzI1NiJ9.eyJ1aWQiOiIxIn0.secretSig AIzaSyDalLI3Bg-vR0NXaw69a6nyx4IxBw2dINo pinOrPassword=123456';
  const redacted = sanitizeCloudMessage(dirty);
  assert.equal(redacted.includes('secretSig'), false);
  assert.equal(redacted.includes('AIzaSyDalLI3Bg'), false);
  assert.equal(redacted.includes('123456'), false);

  CloudErrorLogger.clear();
  for (let i = 0; i < 60; i++) {
    CloudErrorLogger.recordError(`OP_${i}`, `path/${i}`, { code: 'permission-denied', message: `err ${i}` });
  }
  assert.equal(CloudErrorLogger.getEntries().length, 50, 'Ring buffer must cap at 50 sanitized entries');
});

test('SecurityService RBAC enforces Admin > Manager > Accountant > Deputy > Employee boundaries', () => {
  const {
    GRANULAR_ACTIONS,
    SCOPED_ADMIN_MARKER,
    resolveCanonicalRbacTier,
    canModifyEmployeeTarget,
    canAssignRole,
    validatePermissionChange
  } = SecurityMod;

  const superAdmin = {
    userCode: 'USR-ADMIN',
    username: 'admin',
    role: 'مدير النظام',
    permissions: GRANULAR_ACTIONS.USER_MANAGEMENT,
    active: true
  };
  const manager = {
    userCode: 'MGR-01',
    username: 'ops_manager',
    role: 'المدير',
    permissions: `${GRANULAR_ACTIONS.USER_MANAGEMENT},المبيعات`,
    active: true
  };
  const otherManager = {
    userCode: 'MGR-02',
    username: 'branch_manager',
    role: 'مدير فرع',
    permissions: 'الإنتاج',
    active: true
  };
  const accountant = {
    userCode: 'ACC-01',
    username: 'chief_acc',
    role: 'المحاسب',
    permissions: 'المحاسبة,الخزن',
    active: true
  };
  const deputyAcc = {
    userCode: 'DEP-01',
    username: 'deputy_acc',
    role: 'نائب محاسب',
    permissions: 'الخزن',
    active: true
  };
  const employee = {
    userCode: 'EMP-01',
    username: 'sales_staff',
    role: 'مسؤول مبيعات وفواتير',
    permissions: 'المبيعات',
    active: true
  };

  assert.equal(resolveCanonicalRbacTier(superAdmin), 'ADMIN');
  assert.equal(resolveCanonicalRbacTier(manager), 'MANAGER');
  assert.equal(resolveCanonicalRbacTier(accountant), 'ACCOUNTANT');
  assert.equal(resolveCanonicalRbacTier(deputyAcc), 'DEPUTY_ACCOUNTANT');
  assert.equal(resolveCanonicalRbacTier(employee), 'EMPLOYEE');

  // Manager cannot modify self, primary admin, or another manager
  assert.equal(canModifyEmployeeTarget(manager, manager), false);
  assert.equal(canModifyEmployeeTarget(manager, superAdmin), false);
  assert.equal(canModifyEmployeeTarget(manager, otherManager), false);
  assert.equal(canModifyEmployeeTarget(manager, employee), true);

  // Manager cannot assign privileged roles or escalate tokens
  assert.equal(canAssignRole(manager, 'مدير النظام'), false);
  assert.equal(canAssignRole(manager, 'المدير'), false);
  assert.equal(canAssignRole(manager, 'المحاسب'), true);
  assert.equal(canAssignRole(superAdmin, 'مدير النظام'), true);

  assert.equal(
    validatePermissionChange(manager, employee, 'مدير النظام', 'المبيعات').allowed,
    false
  );
  assert.equal(
    validatePermissionChange(manager, employee, 'مسؤول مبيعات وفواتير', `${SCOPED_ADMIN_MARKER},المبيعات`).allowed,
    false
  );
  assert.equal(
    validatePermissionChange(manager, employee, 'مسؤول مبيعات وفواتير', GRANULAR_ACTIONS.ADMIN_SETTINGS).allowed,
    false
  );
  assert.equal(
    validatePermissionChange(superAdmin, otherManager, 'مدير فرع', 'الإنتاج,المصنع').allowed,
    true
  );
});

test('ErpDataService and SettingsModuleView remove legacy V16 boot queries, stale v20.0 copy, and cached health checks', () => {
  const erpDataSrc = fs.readFileSync(path.join(SRC_DIR, 'services/ErpDataService.ts'), 'utf8');
  assert.equal(
    /constructor\s*\(\)\s*\{[\s\S]*void\s+this\.purgeLegacyV16Cluster/.test(erpDataSrc),
    false,
    'ErpDataService constructor must not trigger unauthenticated background V16 purge'
  );
  assert.ok(erpDataSrc.includes('getDocsFromServer'), 'ErpDataService must bypass cache for live server probes');
  assert.ok(erpDataSrc.includes('probeSingleService'), 'ErpDataService must expose read-only service probes');
  assert.ok(erpDataSrc.includes('reverseImmutableRecord'), 'ErpDataService must support reversal-only immutable updates');

  const settingsSrc = fs.readFileSync(path.join(SRC_DIR, 'components/modules/SettingsModuleView.tsx'), 'utf8');
  assert.equal(settingsSrc.includes('الإصدار 20.0 المعتمد'), false, 'SettingsModuleView must not show stale 20.0 copy');
  assert.equal(settingsSrc.includes("'20.0.0'"), false, 'SettingsModuleView must not show stale 20.0.0 fallback');
  assert.ok(settingsSrc.includes('APP_VERSION_CONTRACT'), 'SettingsModuleView must use APP_VERSION_CONTRACT');
  assert.ok(settingsSrc.includes('CloudErrorLogger'), 'SettingsModuleView must display sanitized CloudErrorLogger entries');
});
