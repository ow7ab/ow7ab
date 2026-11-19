import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import os from 'node:os';
import { pathToFileURL, fileURLToPath } from 'node:url';
import ts from 'typescript';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);
const WEB_ROOT = path.resolve(__dirname, '..');
const REPO_ROOT = path.resolve(WEB_ROOT, '..');
const SRC_DIR = path.join(WEB_ROOT, 'src');

// Provide a clean in-memory localStorage mock for Node test runtime
const memoryStore = new Map();
globalThis.localStorage = {
  getItem: (k) => (memoryStore.has(k) ? memoryStore.get(k) : null),
  setItem: (k, v) => {
    memoryStore.set(k, String(v));
  },
  removeItem: (k) => {
    memoryStore.delete(k);
  },
  clear: () => {
    memoryStore.clear();
  }
};

const tmpOutDir = fs.mkdtempSync(path.join(os.tmpdir(), 'pmp-auth-session-tests-'));

function transpile(relPath, outRelPath, rewrite = (s) => s) {
  const fullPath = path.join(SRC_DIR, relPath);
  let source = fs.readFileSync(fullPath, 'utf8');
  source = rewrite(source);
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

const safeStoragePath = transpile('utils/safeStorage.ts', 'utils/safeStorage.mjs');
const securityPath = transpile('services/SecurityService.ts', 'services/SecurityService.mjs');
const pwdSecPath = transpile('services/PasswordSecurity.ts', 'services/PasswordSecurity.mjs', (s) =>
  s.replace("'../utils/safeStorage'", "'../utils/safeStorage.mjs'")
);
const sessionMgrPath = transpile('utils/authSessionManager.ts', 'utils/authSessionManager.mjs', (s) =>
  s
    .replace("'../services/PasswordSecurity'", "'../services/PasswordSecurity.mjs'")
    .replace("'../services/SecurityService'", "'../services/SecurityService.mjs'")
    .replace("'./safeStorage'", "'./safeStorage.mjs'")
);

const SecurityService = await import(pathToFileURL(securityPath).href);
const PasswordSecurity = await import(pathToFileURL(pwdSecPath).href);
const SessionManager = await import(pathToFileURL(sessionMgrPath).href);

test.after(() => {
  fs.rmSync(tmpOutDir, { recursive: true, force: true });
});

function readRepoFile(relFromRepo) {
  return fs.readFileSync(path.join(REPO_ROOT, relFromRepo), 'utf8');
}

// =========================================================================
// SCENARIO 1, 2, 3: Username + Password/PIN, Email + Password, Quick PIN
// =========================================================================
test('Scenarios 1-3: Username alias candidates, Email pass-through, and PBKDF2/SHA-256 PIN verification', async () => {
  // Scenario 1: Username-only resolves candidate corporate emails without requiring Google
  assert.deepEqual(SessionManager.buildUsernameAuthEmailCandidates('admin'), [
    'admin@pmp-paints.com',
    'oaw7ab@gmail.com'
  ]);
  assert.deepEqual(SessionManager.buildUsernameAuthEmailCandidates('ahmed.sales'), [
    'ahmed.sales@pmp-paints.com',
    'ahmed.sales@pmppaints.com'
  ]);

  // Scenario 2: Full email passes through directly
  assert.deepEqual(SessionManager.buildUsernameAuthEmailCandidates('Manager@PMP-Paints.com'), [
    'manager@pmp-paints.com'
  ]);

  // Scenario 3: PBKDF2 & legacy SHA-256 PIN verification + upgrade detection
  const pinHash = await PasswordSecurity.hashPassword('482910');
  assert.equal(PasswordSecurity.isModernPbkdf2Hash(pinHash), true);
  assert.equal(await PasswordSecurity.verifyPassword('482910', pinHash), true);
  assert.equal(await PasswordSecurity.verifyPassword('000000', pinHash), false);

  const legacyHash = await PasswordSecurity.legacySha256Hash('1234');
  assert.equal(PasswordSecurity.needsUpgradeToPbkdf2(legacyHash), true);
  assert.equal(await PasswordSecurity.verifyPassword('1234', legacyHash), true);
});

// =========================================================================
// SCENARIOS 4, 5, 6, 7: Admin, Manager, Accountant, Employee RBAC Matrix
// =========================================================================
test('Scenarios 4-7: Role-based access control preserved across Admin, Manager, Accountant, and Employee', () => {
  const admin = {
    userCode: 'USR-ADMIN',
    username: 'admin',
    fullName: 'مدير النظام',
    role: 'المدير العام',
    roleType: 'SUPER_ADMIN',
    branch: 'ALL',
    permissions: 'ALL',
    active: true,
    syncStatus: 'APPROVED_BY_MANAGER'
  };
  const manager = {
    userCode: 'USR-MGR-01',
    username: 'branch_mgr',
    fullName: 'مدير الفرع',
    role: 'مدير فرع',
    roleType: 'MANAGER',
    branch: 'السودان',
    permissions: 'المبيعات,المخازن,التقارير',
    active: true,
    syncStatus: 'APPROVED_BY_MANAGER'
  };
  const accountant = {
    userCode: 'USR-ACC-01',
    username: 'acc1',
    fullName: 'المحاسب الرئيسي',
    role: 'المحاسب',
    roleType: 'EMPLOYEE',
    branch: 'ALL',
    permissions: 'الخزينة,المبيعات,التقارير,ACTION_EXPORT',
    active: true,
    syncStatus: 'APPROVED_BY_MANAGER'
  };
  const employee = {
    userCode: 'USR-EMP-01',
    username: 'sales1',
    fullName: 'موظف مبيعات',
    role: 'موظف مبيعات',
    roleType: 'EMPLOYEE',
    branch: 'بورتسودان',
    permissions: 'المبيعات',
    active: true,
    syncStatus: 'APPROVED_BY_MANAGER'
  };

  assert.equal(SecurityService.resolveCanonicalRbacTier(admin), 'ADMIN');
  assert.equal(SecurityService.checkModuleAccess(admin, 'المختبر الكيميائي'), true);
  assert.equal(SecurityService.canConfigureEmployeePermissions(admin), true);

  assert.equal(SecurityService.resolveCanonicalRbacTier(manager), 'MANAGER');
  assert.equal(SecurityService.checkModuleAccess(manager, 'المبيعات'), true);

  assert.equal(SecurityService.resolveCanonicalRbacTier(accountant), 'ACCOUNTANT');
  assert.equal(SecurityService.checkModuleAccess(accountant, 'الخزينة'), true);
  assert.equal(SecurityService.canConfigureEmployeePermissions(accountant), false);

  assert.equal(SecurityService.resolveCanonicalRbacTier(employee), 'EMPLOYEE');
  assert.equal(SecurityService.checkModuleAccess(employee, 'المبيعات'), true);
  assert.equal(SecurityService.checkModuleAccess(employee, 'الخزينة'), false);
  assert.equal(SecurityService.canConfigureEmployeePermissions(employee), false);
});

// =========================================================================
// SCENARIOS 8, 9, 10, 11, 13: Sign-Out, Account Switching, Reload Restore,
// Device Session Tracking, Tamper Rejection, and Expired Session Rejection
// =========================================================================
test('Scenarios 8-11 & 13: Sealed session persistence, tamper/expiry rejection, account switch, and device sessions', async () => {
  memoryStore.clear();
  const deviceId = SessionManager.getOrCreateDeviceId();
  assert.ok(deviceId.startsWith('WEB-'));

  const adminAccount = {
    userCode: 'USR-ADMIN',
    username: 'admin',
    fullName: 'مدير النظام',
    role: 'مدير النظام',
    roleType: 'SUPER_ADMIN',
    branch: 'ALL',
    permissions: 'ALL',
    pinOrPassword: 'MUST_NEVER_BE_STORED',
    active: true,
    syncStatus: 'APPROVED_BY_MANAGER'
  };
  const employeeAccount = {
    userCode: 'USR-EMP-02',
    username: 'khaled',
    fullName: 'خالد محمد',
    role: 'موظف مبيعات',
    roleType: 'EMPLOYEE',
    branch: 'السودان',
    permissions: 'المبيعات',
    pinOrPassword: 'SECRET_PIN_HASH',
    active: true,
    syncStatus: 'APPROVED_BY_MANAGER'
  };

  // Scenario 10: Sealed session survives reload without leaking pinOrPassword
  const sealedAdmin = await SessionManager.createSealedSessionJson(adminAccount, Date.now(), 'USERNAME_PIN', deviceId);
  assert.equal(sealedAdmin.includes('MUST_NEVER_BE_STORED'), false);
  const restored = await SessionManager.verifyAndParseStoredSession(sealedAdmin);
  assert.equal(restored.status, 'VALID');
  assert.equal(restored.account?.username, 'admin');
  assert.equal(restored.account?.pinOrPassword, undefined);

  // Tampering with role/permissions in localStorage MUST be rejected (Zero-Trust integrity)
  const tamperedObj = JSON.parse(sealedAdmin);
  tamperedObj.permissions = 'ALL,EXTRA_ESCALATION';
  const tamperedRes = await SessionManager.verifyAndParseStoredSession(JSON.stringify(tamperedObj));
  assert.equal(tamperedRes.status, 'INVALID');
  assert.equal(tamperedRes.account, null);

  // Scenario 13: Expired session (> 24h TTL) rejected cleanly with status = EXPIRED
  const expiredIssuedAt = Date.now() - (SessionManager.SESSION_MAX_AGE_MS + 60_000);
  const expiredJson = await SessionManager.createSealedSessionJson(adminAccount, expiredIssuedAt, 'USERNAME_PIN', deviceId);
  const expiredRes = await SessionManager.verifyAndParseStoredSession(expiredJson);
  assert.equal(expiredRes.status, 'EXPIRED');
  assert.equal(expiredRes.account, null);

  // Scenario 11 & 9: Device session registration & Account Switch (Admin -> Employee)
  const sessionsAfterAdmin = SessionManager.registerOrUpdateDeviceSession(adminAccount, 'USERNAME_PIN', deviceId);
  assert.equal(sessionsAfterAdmin.length, 1);
  assert.equal(sessionsAfterAdmin[0].username, 'admin');
  assert.equal(sessionsAfterAdmin[0].active, true);

  // Switch to Employee on same device: replaces active account without retaining Admin
  const sealedEmployee = await SessionManager.createSealedSessionJson(employeeAccount, Date.now(), 'ACCOUNT_SWITCH', deviceId);
  const restoredEmp = await SessionManager.verifyAndParseStoredSession(sealedEmployee);
  assert.equal(restoredEmp.status, 'VALID');
  assert.equal(restoredEmp.account?.username, 'khaled');
  assert.equal(restoredEmp.loginMethod, 'ACCOUNT_SWITCH');
  const sessionsAfterSwitch = SessionManager.registerOrUpdateDeviceSession(employeeAccount, 'ACCOUNT_SWITCH', deviceId);
  assert.equal(sessionsAfterSwitch[0].username, 'khaled');
  assert.equal(sessionsAfterSwitch[0].active, true);

  // Scenario 8: Clean sign-out deactivates current device session
  const sessionsAfterLogout = SessionManager.deactivateDeviceSession(deviceId);
  assert.equal(sessionsAfterLogout[0].active, false);
});

// =========================================================================
// SCENARIO 12: Invalid Credentials & Progressive Brute-Force Lockout
// =========================================================================
test('Scenario 12: LoginRateLimiter enforces progressive lockout after repeated invalid attempts', () => {
  memoryStore.clear();
  const id = 'intruder_user_test';
  PasswordSecurity.LoginRateLimiter.recordSuccess(id);

  assert.equal(PasswordSecurity.LoginRateLimiter.checkLockStatus(id).locked, false);
  for (let i = 0; i < 5; i++) {
    PasswordSecurity.LoginRateLimiter.recordFailure(id);
  }
  const lockedState = PasswordSecurity.LoginRateLimiter.checkLockStatus(id);
  assert.equal(lockedState.locked, true);
  assert.ok(lockedState.remainingSeconds > 0);

  PasswordSecurity.LoginRateLimiter.recordSuccess(id);
  assert.equal(PasswordSecurity.LoginRateLimiter.checkLockStatus(id).locked, false);
});

// =========================================================================
// SCENARIOS 9, 14, 15 + SECURITY INVARIANTS:
// No Mandatory Google Sign-In Gate, Optional Google Placement, Bounce-Back Fix,
// Double-Submit Prevention, and Firestore Zero-Trust Rules Preserved
// =========================================================================
test('Scenarios 9, 14, 15 & Security: No mandatory Google gate, Google is secondary/optional, double-submit guard & rules intact', () => {
  const loginPage = readRepoFile('web/src/pages/LoginPage.tsx');
  const loginModal = readRepoFile('web/src/components/sections/LoginModal.tsx');
  const authContext = readRepoFile('web/src/context/AuthContext.tsx');
  const accountService = readRepoFile('web/src/services/AccountService.ts');
  const erpDataService = readRepoFile('web/src/services/ErpDataService.ts');
  const portalPage = readRepoFile('web/src/pages/PortalPage.tsx');
  const androidCloudErp = readRepoFile('app/src/main/java/com/example/data/remote/CloudErpSyncRepository.kt');
  const androidCloudAcc = readRepoFile('app/src/main/java/com/example/data/remote/CloudAccountSyncRepository.kt');
  const firestoreRules = readRepoFile('firestore.rules');

  // Scenario 14: Forbidden mandatory Google-first error strings must NOT exist anywhere
  const forbiddenPhrases = [
    'يجب تسجيل الدخول عن طريق حساب Google أولاً',
    'استخدم زر «تسجيل الدخول عبر Google» أعلاه',
    'تسجيل الدخول بحساب Google أولاً',
    'جلسة المصادقة السحابية (Google Sign-In) غير نشطة'
  ];
  for (const [label, content] of [
    ['LoginPage.tsx', loginPage],
    ['LoginModal.tsx', loginModal],
    ['AuthContext.tsx', authContext],
    ['AccountService.ts', accountService],
    ['ErpDataService.ts', erpDataService],
    ['CloudErpSyncRepository.kt', androidCloudErp],
    ['CloudAccountSyncRepository.kt', androidCloudAcc]
  ]) {
    for (const phrase of forbiddenPhrases) {
      assert.equal(
        content.includes(phrase),
        false,
        `${label} must NOT contain mandatory Google Sign-In prompt: "${phrase}"`
      );
    }
  }

  // Scenario 14: Primary Username/Email tabs MUST appear BEFORE optional Google Sign-In button
  const pageUsernameIdx = loginPage.indexOf('login-tab-username-only');
  const pageGoogleIdx = loginPage.indexOf('login-google-optional-btn');
  assert.ok(pageUsernameIdx > 0 && pageGoogleIdx > pageUsernameIdx, 'LoginPage must render Username/Email primary form BEFORE optional Google button');

  const modalUsernameIdx = loginModal.indexOf('handlePinLogin');
  const modalGoogleIdx = loginModal.indexOf('modal-google-optional-btn');
  assert.ok(modalUsernameIdx > 0 && modalGoogleIdx > modalUsernameIdx, 'LoginModal must render Username/Email primary form BEFORE optional Google button');

  // Scenario 9: Bounce-back prevention refs & Account Switching UI exist
  assert.ok(authContext.includes('linkedAccountRef'), 'AuthContext must track linkedAccountRef to prevent stale closure bounce-back');
  assert.ok(authContext.includes('explicitSignOutRef'), 'AuthContext must track explicitSignOutRef during sign-out/switch');
  assert.ok(authContext.includes('switchAccount'), 'AuthContext must expose switchAccount');
  assert.ok(portalPage.includes('portal-switch-account-btn'), 'PortalPage must expose Switch Account action');
  assert.ok(portalPage.includes('switch-account-form'), 'PortalPage must expose Switch Account modal form');

  // Scenario 13: Session expiration notice surfaced in LoginPage
  assert.ok(loginPage.includes('login-session-expired-banner'), 'LoginPage must show session expired notice when TTL expires');

  // Scenario 15: Double-submit guard in AuthContext
  assert.ok(authContext.includes('isSubmittingRef'), 'AuthContext must guard against double-submit via isSubmittingRef');

  // Zero-Trust Firestore rules intact (never weakened to allow unauthenticated reads/writes)
  assert.ok(firestoreRules.includes('request.auth != null'), 'firestore.rules must enforce request.auth != null');
  assert.equal(firestoreRules.includes('allow read, write: if true'), false, 'firestore.rules must NEVER allow unauthenticated wildcard access');
  assert.equal(authContext.includes('firebase-admin'), false, 'Web client must NEVER import firebase-admin');
});
