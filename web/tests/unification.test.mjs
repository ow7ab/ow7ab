import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import os from 'node:os';
import { pathToFileURL, fileURLToPath } from 'node:url';
import ts from 'typescript';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);
const SRC_SERVICES_DIR = path.resolve(__dirname, '../src/services');

const tmpOutDir = fs.mkdtempSync(path.join(os.tmpdir(), 'pmp-erp-tests-'));

function loadTsModule(filename) {
  const tsPath = path.join(SRC_SERVICES_DIR, filename);
  const source = fs.readFileSync(tsPath, 'utf8');
  const transpiled = ts.transpileModule(source, {
    compilerOptions: {
      module: ts.ModuleKind.ESNext,
      target: ts.ScriptTarget.ES2022,
      moduleResolution: ts.ModuleResolutionKind.Bundler
    }
  });
  const outName = filename.replace(/\.ts$/, '.mjs');
  const outPath = path.join(tmpOutDir, outName);
  fs.writeFileSync(outPath, transpiled.outputText, 'utf8');
  return import(pathToFileURL(outPath).href);
}

const PasswordSecurity = await loadTsModule('PasswordSecurity.ts');
const PvcEngine = await loadTsModule('PvcEngine.ts');
const SecurityService = await loadTsModule('SecurityService.ts');
const ErpCalculations = await loadTsModule('ErpCalculations.ts');

test.after(() => {
  fs.rmSync(tmpOutDir, { recursive: true, force: true });
});

test('PasswordSecurity: PBKDF2-HMAC-SHA256 generation, verification & legacy parity', async () => {
  const hashed = await PasswordSecurity.hashPassword('Pmp@2026!');
  assert.ok(hashed.startsWith('pbkdf2$sha256$65536$'));
  assert.equal(PasswordSecurity.isModernPbkdf2Hash(hashed), true);
  assert.equal(PasswordSecurity.needsUpgradeToPbkdf2(hashed), false);
  assert.equal(await PasswordSecurity.verifyPassword('Pmp@2026!', hashed), true);
  assert.equal(await PasswordSecurity.verifyPassword('wrongPass', hashed), false);

  // Legacy SHA-256 format compatibility
  const legacyHash = await PasswordSecurity.legacySha256Hash('998877');
  assert.equal(legacyHash.length, 64);
  assert.equal(PasswordSecurity.needsUpgradeToPbkdf2(legacyHash), true);
  assert.equal(await PasswordSecurity.verifyPassword('998877', legacyHash), true);
  assert.equal(await PasswordSecurity.verifyPassword('000000', legacyHash), false);

  // Constant-time rejection
  assert.equal(PasswordSecurity.constantTimeEquals('abc', 'abc'), true);
  assert.equal(PasswordSecurity.constantTimeEquals('abc', 'abd'), false);
});

test('PvcEngine: exact PVC, CPVC threshold, solids%, density, and cost/kg calculations', () => {
  const result = PvcEngine.calculatePvc([
    {
      id: '1',
      name: 'Rutile TiO2',
      type: 'صبغة / ملون',
      weight: 20,
      density: 4.0,
      solidsPercent: 100,
      unitCost: 4.5,
      notes: ''
    },
    {
      id: '2',
      name: 'Pure Acrylic',
      type: 'راتنج / بوليمر رابط',
      weight: 40,
      density: 1.0,
      solidsPercent: 50,
      unitCost: 2.8,
      notes: ''
    },
    {
      id: '3',
      name: 'Water',
      type: 'مذيب / ماء',
      weight: 40,
      density: 1.0,
      solidsPercent: 0,
      unitCost: 0.05,
      notes: ''
    }
  ]);

  // Pigment dry vol = 20 / 4.0 = 5.0
  // Binder dry vol = (40 * 0.5) / 1.0 = 20.0
  // PVC = 5 / (5 + 20) * 100 = 20.00%
  assert.equal(result.pigmentVolume, 5);
  assert.equal(result.binderDryVolume, 20);
  assert.equal(result.calculatedPvc, 20);
  assert.equal(result.cpvcEstimated, 52);
  assert.equal(result.totalBatchWeight, 100);
  // Wet vol = 5 + 40 + 40 = 85.0 -> Density = 100 / 85 = 1.176
  assert.equal(result.totalBatchVolume, 85);
  assert.equal(result.calculatedDensity, 1.176);
  // Solids weight = (20 + 20) / 100 = 40%
  assert.equal(result.totalSolidsWeight, 40);
  // Total cost = 20*4.5 + 40*2.8 + 40*0.05 = 90 + 112 + 2 = 204
  assert.equal(result.totalCost, 204);
  assert.equal(result.costPerKg, 2.04);
  assert.ok(result.paintClassification.includes('High Gloss'));
});

test('SecurityService: SCOPED_ADMIN isolation, DENY precedence, TEMP grant expiry & report tabs', () => {
  const now = 1_800_000_000_000;
  const scopedAdmin = {
    id: 2,
    userCode: 'EMP-SCOPED-1',
    fullName: 'Scoped Supervisor',
    username: 'scoped_ops',
    phoneOrEmail: '',
    role: 'مسؤول نظام مخصص',
    roleType: 'EMPLOYEE',
    branch: 'HF-001',
    permissions: `SCOPED_ADMIN,المبيعات,DENY:العمليات المالية,TEMP[اعتماد المستندات|${now + 60000}|admin|urgent|T1],TEMP[صلاحية الحذف والتصفير|${now - 60000}|admin|expired|T2]`,
    serverSyncToken: 'SYNC',
    syncStatus: 'APPROVED_BY_MANAGER',
    active: true
  };

  assert.equal(SecurityService.isScopedAdministrator(scopedAdmin), true);
  assert.equal(SecurityService.isSystemManager(scopedAdmin), false);
  // Scoped admin only accesses explicitly granted modules
  assert.equal(SecurityService.checkModuleAccess(scopedAdmin, 'المبيعات', now), true);
  assert.equal(SecurityService.checkModuleAccess(scopedAdmin, 'المستخدمين', now), false);
  // Active temp grant allowed, expired temp grant rejected
  assert.equal(SecurityService.checkActionPermission(scopedAdmin, 'اعتماد المستندات', now), true);
  assert.equal(SecurityService.checkActionPermission(scopedAdmin, 'صلاحية الحذف والتصفير', now), false);
  // DENY token blocks even if granted later
  assert.equal(SecurityService.checkActionPermission(scopedAdmin, 'العمليات المالية', now), false);

  // Scoped admin with report tab permission
  const salesReporter = {
    ...scopedAdmin,
    permissions: 'SCOPED_ADMIN,تقارير المبيعات'
  };
  assert.equal(SecurityService.hasReportTabPermission(salesReporter, 'مبيعات الشهر الحالي', now), true);
  assert.equal(SecurityService.hasReportTabPermission(salesReporter, 'المديونيات', now), false);
});

test('ErpCalculations: safeId, cashboxDocId, posterPriceDocId and ledger aggregations match Android', () => {
  // Firestore rule ^[a-zA-Z0-9_\-]+$ compliance
  assert.match(ErpCalculations.safeId('INV/2026/001#', 'FALLBACK'), /^[a-zA-Z0-9_\-]+$/);
  assert.match(ErpCalculations.cashboxDocId('خزنة الخرطوم', 1), /^[a-zA-Z0-9_\-]+$/);
  assert.match(ErpCalculations.posterPriceDocId('900', 'أبيض', 'SDG'), /^[a-zA-Z0-9_\-]+$/);
  assert.match(ErpCalculations.posterPriceDocId('900', 'أبيض', 'EGP'), /^[a-zA-Z0-9_\-]+$/);

  // Cashbox balances from movements ledger
  const cashboxes = [
    { id: 1, name: 'خزنة الخرطوم', balance: 0, currency: 'SDG', branch: 'SD-001' },
    { id: 2, name: 'خزنة مصر', balance: 0, currency: 'EGP', branch: 'EG-001' }
  ];
  const movements = [
    { id: 10, cashbox: 'خزنة الخرطوم', currency: 'SDG', direction: 'وارد', amount: 500000 },
    { id: 11, cashbox: 'خزنة الخرطوم', currency: 'SDG', direction: 'منصرف', amount: 125000 },
    { id: 12, cashbox: 'خزنة مصر', currency: 'EGP', direction: 'وارد', amount: 10000 }
  ];
  const computedBoxes = ErpCalculations.computeCashboxBalances(cashboxes, movements);
  assert.equal(computedBoxes.find((c) => c.name === 'خزنة الخرطوم')?.balance, 375000);
  assert.equal(computedBoxes.find((c) => c.name === 'خزنة مصر')?.balance, 10000);

  // Product stocks from stock_moves
  const stocks = ErpCalculations.computeProductStocks(
    [{ id: 101 }],
    [
      { productId: 101, kind: 'IN', quantity: 50 },
      { productId: 101, kind: 'SALE', quantity: 12 },
      { productId: 101, kind: 'RETURN_IN', quantity: 2 }
    ]
  );
  assert.equal(stocks.get(101), 40);

  // Factory item balance from factory_moves
  const fiBalances = ErpCalculations.computeFactoryItemBalances(
    [{ id: 201 }],
    [
      { itemId: 201, kind: 'توريد خامات', qty: 100 },
      { itemId: 201, kind: 'صرف خامات', qty: 35 }
    ]
  );
  assert.equal(fiBalances.get(201), 65);

  // Invoice financials & customer debts (deduping embedded vs external payments by ID)
  const inv = {
    id: 301,
    customerId: 1,
    total: 1000,
    currency: 'SDG',
    status: 'active',
    payments: [{ id: 1, invoiceId: 301, amount: 400, status: 'active' }]
  };
  const externalPayments = [
    { id: 1, invoiceId: 301, amount: 400, status: 'active' }, // duplicate ID should not double-count
    { id: 2, invoiceId: 301, amount: 250, status: 'active' },
    { id: 3, invoiceId: 301, amount: 100, status: 'reversed' } // reversed must be ignored
  ];
  const fin = ErpCalculations.computeInvoiceFinancials(inv, externalPayments);
  assert.equal(fin.paidAmount, 650);
  assert.equal(fin.remainingAmount, 350);
  assert.equal(fin.statusLabel, 'سداد جزئي');

  const debts = ErpCalculations.computeCustomerDebts([{ id: 1 }], [inv], externalPayments);
  assert.equal(debts.get(1)?.remaining, 350);
});
