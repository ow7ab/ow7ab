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
const ReportExporter = await loadTsModule('ReportExporter.ts');

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

test('ErpCalculations: safeId, cashbox deduplication, warehouse stock parity, and customer statement ledger match Android', () => {
  // Firestore rule ^[a-zA-Z0-9_\-]+$ compliance
  assert.match(ErpCalculations.safeId('INV/2026/001#', 'FALLBACK'), /^[a-zA-Z0-9_\-]+$/);

  // Cashbox balances from movements ledger
  const cashboxes = [
    { id: 1, name: 'خزنة الخرطوم', balance: 0, currency: 'SDG', branch: 'SD-001', active: true },
    { id: 2, name: 'خزنة مصر', balance: 0, currency: 'EGP', branch: 'EG-001', active: true }
  ];
  const movements = [
    { id: 10, cashbox: 'خزنة الخرطوم', currency: 'SDG', type: 'IN', amount: 500000 },
    { id: 11, cashbox: 'خزنة الخرطوم', currency: 'SDG', type: 'OUT', amount: 125000 },
    { id: 12, cashbox: 'خزنة مصر', currency: 'EGP', type: 'IN', amount: 10000 }
  ];
  const computedBoxes = ErpCalculations.computeDistinctCashboxesWithBalances(cashboxes, movements);
  assert.equal(computedBoxes.find((c) => c.name === 'خزنة الخرطوم')?.balance, 375000);
  assert.equal(computedBoxes.find((c) => c.name === 'خزنة مصر')?.balance, 10000);

  // Product stocks from stock_moves (matching Android PmpDao.kt & InventoryScreen.kt)
  const products = [
    { id: 101, name: 'معجون حراري أبيض', paintColor: 'أبيض', packageWeight: '900 جم', salePrice: 50000, currentStock: 0 },
    { id: 102, name: 'معجون حراري أحمر', paintColor: 'أحمر', packageWeight: '900 جم', salePrice: 55000, currentStock: 0 }
  ];
  const stockMoves = [
    { id: 1, productId: 101, warehouseId: 10, kind: 'IN', quantity: 50 },
    { id: 2, productId: 101, warehouseId: 10, kind: 'SALE', quantity: 12 },
    { id: 3, productId: 101, warehouseId: 10, kind: 'RETURN_IN', quantity: 2 },
    { id: 4, productId: 101, warehouseId: 20, kind: 'IN', quantity: 15 },
    { id: 5, productId: 102, warehouseId: 20, kind: 'IN', quantity: 30 },
    { id: 6, productId: 102, warehouseId: 20, kind: 'ADJUST', quantity: 5 }
  ];

  // All warehouses total for product 101 = (50 - 12 + 2) + 15 = 55; product 102 = 30 - 5 = 25
  const allStocksMap = ErpCalculations.computeProductStocks(products, stockMoves, null);
  assert.equal(allStocksMap.get(101), 55);
  assert.equal(allStocksMap.get(102), 25);

  const allStocks = ErpCalculations.computeProductStocksList(products, stockMoves, null);
  assert.equal(allStocks.find((p) => p.id === 101)?.currentStock, 55);
  assert.equal(allStocks.find((p) => p.id === 102)?.currentStock, 25);

  // Warehouse 10 filter: product 101 = 40, product 102 = 0
  const wh10Stocks = ErpCalculations.computeProductStocksList(products, stockMoves, 10);
  assert.equal(wh10Stocks.find((p) => p.id === 101)?.currentStock, 40);
  assert.equal(wh10Stocks.find((p) => p.id === 102)?.currentStock, 0);

  // Warehouse summary
  const whSummaries = ErpCalculations.computeWarehouseStockSummary(
    [
      { id: 10, code: 'WH-10', name: 'مخزن الخرطوم', branch: 'الخرطوم', location: 'الخرطوم', manager: '', active: true },
      { id: 20, code: 'WH-20', name: 'مخزن القاهرة', branch: 'القاهرة', location: 'القاهرة', manager: '', active: true }
    ],
    products,
    stockMoves
  );
  assert.equal(whSummaries.find((w) => w.warehouse.id === 10)?.netCartons, 40);
  assert.equal(whSummaries.find((w) => w.warehouse.id === 20)?.netCartons, 40); // 15 + 25

  // Specific Customer Account Statement (كشف حساب عميل محدد)
  const customer = { id: 1, name: 'شركة النيل للمقاولات', phone: '0912300000', address: 'الخرطوم', notes: '', active: true, createdAt: 1000 };
  const invoices = [
    {
      id: 301,
      invoiceNo: 'INV-301',
      customerId: 1,
      customerName: 'شركة النيل للمقاولات',
      total: 1000,
      paidAmount: 400,
      currency: 'SDG',
      status: 'سداد جزئي',
      cashbox: 'خزنة الخرطوم',
      timestamp: 10000
    }
  ];
  const payments = [
    { id: 1, invoiceId: 301, customerId: 1, amount: 400, currency: 'SDG', cashbox: 'خزنة الخرطوم', timestamp: 10001 },
    { id: 2, invoiceId: 301, customerId: 1, amount: 250, currency: 'SDG', cashbox: 'خزنة الخرطوم', timestamp: 20000 }
  ];

  const statement = ErpCalculations.computeCustomerStatement(customer, invoices, payments);
  assert.equal(statement.totalInvoiced, 1000);
  assert.equal(statement.totalPaid, 650);
  assert.equal(statement.remainingDebt, 350);
  assert.equal(statement.entries.length, 3);
  assert.equal(statement.entries[statement.entries.length - 1].runningBalance, 350);
});

test('Security Hardening: Pass-the-Hash blocked, session integrity seal detects role/permission tampering, and LoginRateLimiter locks brute force', async () => {
  const hashed = await PasswordSecurity.hashPassword('StrongPin@2026');
  // Submitting raw stored hash string directly MUST NOT pass verification (Pass-the-Hash blocked)
  assert.equal(await PasswordSecurity.verifyPassword(hashed, hashed), false);

  const legacy = await PasswordSecurity.legacySha256Hash('123456');
  assert.equal(await PasswordSecurity.verifyPassword(legacy, legacy), false);

  // Session integrity sealing & tamper detection
  const account = {
    userCode: 'EMP-100',
    username: 'cashier_sd',
    role: 'محاسب مبيعات',
    roleType: 'EMPLOYEE',
    branch: 'SD-001',
    permissions: 'الخزن,المبيعات',
    active: true,
    syncStatus: 'APPROVED_BY_MANAGER',
    serverSyncToken: 'SYNC-OK'
  };
  const issuedAt = Date.now();
  const seal = await PasswordSecurity.computeSessionIntegritySeal({ ...account, issuedAt });
  assert.equal(
    await PasswordSecurity.verifySessionIntegritySeal({ ...account, _issuedAt: issuedAt, _integritySeal: seal }),
    true
  );

  // Attacker tampers with role or permissions inside localStorage
  assert.equal(
    await PasswordSecurity.verifySessionIntegritySeal({
      ...account,
      roleType: 'ADMIN',
      permissions: 'ALL',
      _issuedAt: issuedAt,
      _integritySeal: seal
    }),
    false
  );

  // Rate limiter locks out after 5 consecutive failures and resets on success
  const limiter = PasswordSecurity.LoginRateLimiter;
  limiter.clearAll();
  const key = 'test_user_bruteforce';
  for (let i = 0; i < 4; i++) {
    const status = limiter.recordFailure(key);
    assert.equal(status.locked, false);
  }
  const lockResult = limiter.recordFailure(key);
  assert.equal(lockResult.locked, true);
  assert.ok(lockResult.remainingSeconds >= 240);
  assert.equal(limiter.checkLockStatus(key).locked, true);
  limiter.recordSuccess(key);
  assert.equal(limiter.checkLockStatus(key).locked, false);
});

test('Warehouse Stock Reconciliation & ReportExporter XSS / CSV formula neutralization', () => {
  const products = [
    { id: 1, name: 'معجون أبيض - 900 جم', paintColor: 'أبيض', packageWeight: '900 جم', salePrice: 100, currentStock: 0 }
  ];
  // Warehouse 10 has excess outflow (net -15 clamped to 0), Warehouse 20 has 40 net.
  // Total across warehouses MUST equal 0 + 40 = 40 (NOT -15 + 40 = 25).
  const moves = [
    { id: 1, productId: 1, warehouseId: 10, kind: 'IN', quantity: 5 },
    { id: 2, productId: 1, warehouseId: 10, kind: 'SALE', quantity: 20 },
    { id: 3, productId: 1, warehouseId: 20, kind: 'IN', quantity: 50 },
    { id: 4, productId: 1, warehouseId: 20, kind: 'OUT', quantity: 10 }
  ];
  const totalMap = ErpCalculations.computeProductStocks(products, moves, null);
  const whSummaries = ErpCalculations.computeWarehouseStockSummary(
    [
      { id: 10, code: 'WH-10', name: 'A', branch: 'A', location: '', manager: '', active: true },
      { id: 20, code: 'WH-20', name: 'B', branch: 'B', location: '', manager: '', active: true }
    ],
    products,
    moves
  );
  const sumOfWarehouses = whSummaries.reduce((s, w) => s + w.netCartons, 0);
  assert.equal(totalMap.get(1), 40);
  assert.equal(sumOfWarehouses, 40);

  const breakdown = ErpCalculations.computeProductMoveBreakdown(products, moves, null);
  assert.equal(breakdown.get(1)?.inQty, 55);
  assert.equal(breakdown.get(1)?.outQty, 30);
  assert.equal(breakdown.get(1)?.netStock, 40);

  // ReportExporter XSS & CSV neutralization
  assert.equal(ReportExporter.escapeHtml('<script>alert(1)</script>'), '&lt;script&gt;alert(1)&lt;/script&gt;');
  assert.equal(ReportExporter.sanitizeCsvCell('=CMD("calc")'), "'=CMD(\"calc\")");
});

test('Duplicate Warehouse Deduplication, Orphan +720 Move Exclusion, Chronological Treasury Sorting & Treasury PDF Export', () => {
  // 1. Duplicate warehouses sharing identical Arabic name must merge into 1 canonical warehouse preserving _aliasIds & _aliasDocIds
  const rawWarehouses = [
    { id: 1, _docId: 'WH-1', name: 'مخزن مبيعات حلفا', branch: 'وادي حلفا', active: true },
    { id: 7, _docId: 'WH-7', name: 'مخزن مبيعات حلفا ', branch: 'حلفا', active: true },
    { id: 2, _docId: 'WH-2', name: 'مخزن مبيعات عطبرة', branch: 'عطبرة', active: true }
  ];
  const dedupedWh = ErpCalculations.deduplicateCollectionRecords('warehouses', rawWarehouses);
  assert.equal(dedupedWh.length, 2);
  const halfaWh = dedupedWh.find((w) => w.name.includes('حلفا'));
  assert.ok(halfaWh);
  assert.equal(halfaWh.id, 1);
  assert.deepEqual(halfaWh._aliasIds, [1, 7]);
  assert.deepEqual(halfaWh._aliasDocIds, ['WH-1', 'WH-7']);

  // 2. Orphan stock_moves (+720) for deleted productId=999 must NOT inflate warehouse inflow or net balance
  const products = [
    { id: 101, name: 'معجون أبيض', paintColor: 'أبيض', packageWeight: '900 جم', salePrice: 100, currentStock: 0 }
  ];
  const stockMovesWithOrphan = [
    { id: 1, productId: 999, warehouseId: 7, kind: 'IN', quantity: 720 }, // Deleted product phantom inflow
    { id: 2, productId: 101, warehouseId: 1, kind: 'IN', quantity: 25 },
    { id: 3, productId: 101, warehouseId: 7, kind: 'IN', quantity: 15 }  // Alias warehouse ID 7 merges with canonical ID 1!
  ];
  const whSummaries = ErpCalculations.computeWarehouseStockSummary(dedupedWh, products, stockMovesWithOrphan);
  const halfaSummary = whSummaries.find((w) => w.warehouse.id === 1);
  assert.equal(halfaSummary?.inQty, 40); // Excludes 720 orphan! Combines 25 + 15 across alias IDs 1 & 7
  assert.equal(halfaSummary?.netCartons, 40);

  const halfaProducts = ErpCalculations.computeProductStocksList(products, stockMovesWithOrphan, 1, dedupedWh);
  assert.equal(halfaProducts[0].currentStock, 40);
  const halfaBreakdown = ErpCalculations.computeProductMoveBreakdown(products, stockMovesWithOrphan, 1, dedupedWh);
  assert.equal(halfaBreakdown.get(101)?.inQty, 40);

  // If existing catalog has 0 inflow for Halfa (only deleted product had 720), both chip & KPI show 0
  const onlyOrphanMoves = [{ id: 1, productId: 999, warehouseId: 7, kind: 'IN', quantity: 720 }];
  const zeroCheck = ErpCalculations.computeWarehouseStockSummary(dedupedWh, products, onlyOrphanMoves);
  assert.equal(zeroCheck.find((w) => w.warehouse.id === 1)?.inQty, 0);
  assert.equal(zeroCheck.find((w) => w.warehouse.id === 1)?.netCartons, 0);

  // 3. Chronological sorting works accurately by txDate even when Android sync omitted createdAt
  const unsortedMovements = [
    { id: 1, txDate: '2026-10-12', amount: 100 }, // missing createdAt
    { id: 2, txDate: '2026-10-15', amount: 200, createdAt: 1700000000000 },
    { id: 3, txDate: '2026-10-10', amount: 50, createdAt: 1999999999999 }, // newer timestamp on older txDate!
    { id: 4, txDate: '14/10/2026', amount: 150 } // DD/MM/YYYY format
  ];
  const sortedDesc = [...unsortedMovements].sort((a, b) =>
    ErpCalculations.compareRecordsChronologically(a, b, 'desc')
  );
  assert.deepEqual(
    sortedDesc.map((m) => m.id),
    [2, 4, 1, 3]
  );

  const sortedAsc = [...unsortedMovements].sort((a, b) =>
    ErpCalculations.compareRecordsChronologically(a, b, 'asc')
  );
  assert.deepEqual(
    sortedAsc.map((m) => m.id),
    [3, 1, 4, 2]
  );

  // 4. ReportExporter exposes Treasury Cashbox PDF statement export
  assert.equal(typeof ReportExporter.ReportExporter.exportTreasuryStatementToPrint, 'function');
});
