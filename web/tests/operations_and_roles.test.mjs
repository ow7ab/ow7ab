import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import vm from 'node:vm';
import ts from 'typescript';

const __dirname = path.dirname(new URL(import.meta.url).pathname).replace(/^\/([A-Za-z]:)/, '$1');
const webRoot = path.resolve(__dirname, '..');

function loadTsModule(relPath, customRequire) {
  const fullPath = path.join(webRoot, relPath);
  const source = fs.readFileSync(fullPath, 'utf8');
  const transpiled = ts.transpileModule(source, {
    compilerOptions: {
      module: ts.ModuleKind.CommonJS,
      target: ts.ScriptTarget.ES2020,
      esModuleInterop: true
    }
  });
  const mod = { exports: {} };
  const fn = new Function('require', 'module', 'exports', transpiled.outputText);
  fn(customRequire, mod, mod.exports);
  return mod.exports;
}

const securityMod = loadTsModule('src/services/SecurityService.ts', () => ({}));
const rolesMod = loadTsModule('src/config/employeeRoles.ts', (id) => {
  if (id.includes('SecurityService')) return securityMod;
  return {};
});
const opsMod = loadTsModule('src/services/OperationsService.ts', (id) => {
  if (id.includes('employeeRoles')) return rolesMod;
  if (id.includes('ErpDataService')) {
    return {
      ErpDataService: {
        fetchCollectionOnce: async () => [],
        saveRecord: async (_col, rec) => rec
      }
    };
  }
  return {};
});

test('employeeRoles resolves all 9 factory and Sudan roles cleanly', () => {
  assert.equal(rolesMod.ROLE_PRESET_LIST.length, 9);
  assert.equal(rolesMod.resolveEmployeeRoleKey({ role: 'المدير العام', active: true }), 'GENERAL_MANAGER');
  assert.equal(rolesMod.resolveEmployeeRoleKey({ role: 'مراجع مالي وتقارير', branch: 'EG-001', active: true }), 'FINANCE_MANAGER_EGYPT');
  assert.equal(rolesMod.resolveEmployeeRoleKey({ role: 'مسؤول الجودة', active: true }), 'QUALITY_PROCUREMENT_MANAGER');
  assert.equal(rolesMod.resolveEmployeeRoleKey({ role: 'مهندس الإنتاج', active: true }), 'PRODUCTION_MANAGER');
  assert.equal(rolesMod.resolveEmployeeRoleKey({ role: 'مهندس التعبئة والتغليف', active: true }), 'PACKING_ENGINEER');
  assert.equal(rolesMod.resolveEmployeeRoleKey({ role: 'أمين مخزن المنتج التام', active: true }), 'WAREHOUSE_KEEPER_EGYPT');
  assert.equal(rolesMod.resolveEmployeeRoleKey({ role: 'مسؤول التحميل والعدد', active: true }), 'LOADING_TOOLS_SUPERVISOR');
  assert.equal(
    rolesMod.resolveEmployeeRoleKey({ role: 'محاسب خزنة ومبيعات', branch: 'HF-001 (مستودع حلفا اللوجستي)', active: true }),
    'SUDAN_BRANCH_MANAGER'
  );
  assert.equal(rolesMod.resolveEmployeeRoleKey({ role: 'مسؤول مبيعات وفواتير', active: true }), 'SALES_AGENT');
});

test('evaluateShiftLogMetrics flags waste > 25 cans and tracks 1000 carton shift target', () => {
  const okShift = opsMod.evaluateShiftLogMetrics({
    targetCartons: 1000,
    producedCartons: 980,
    wasteCans: 18,
    maxWasteAllowed: 25,
    technicalDowntimeMin: 10,
    materialDowntimeMin: 5
  });
  assert.equal(okShift.achievementPercent, 98);
  assert.equal(okShift.wasteExceeded, false);
  assert.equal(okShift.totalDowntimeMin, 15);
  assert.equal(okShift.statusLabel, 'ممتاز');

  const highWaste = opsMod.evaluateShiftLogMetrics({
    targetCartons: 1000,
    producedCartons: 1000,
    wasteCans: 32,
    maxWasteAllowed: 25,
    technicalDowntimeMin: 0,
    materialDowntimeMin: 0
  });
  assert.equal(highWaste.wasteExceeded, true);
  assert.equal(highWaste.wasteDiff, 7);
  assert.equal(highWaste.statusLabel, 'تجاوز حد التوالف');
});

test('evaluateBudgetDeviation enforces 5% threshold', () => {
  const within = opsMod.evaluateBudgetDeviation(100000, 104500);
  assert.equal(within.exceeded, false);
  assert.equal(within.deviationPercent, 4.5);

  const over = opsMod.evaluateBudgetDeviation(100000, 106000);
  assert.equal(over.exceeded, true);
  assert.equal(over.deviationPercent, 6);
});

test('evaluateEmployeeBonusTier enforces 3-tier reward policy', () => {
  assert.deepEqual(
    opsMod.evaluateEmployeeBonusTier({ completionPercent: 100, deviationPercent: 2, errorCount: 0, innovationCount: 1 }),
    { bonusTier: 'المستوى الثالث (5 شهور)', bonusMonths: 5 }
  );
  assert.deepEqual(
    opsMod.evaluateEmployeeBonusTier({ completionPercent: 100, deviationPercent: 3, errorCount: 0, innovationCount: 0 }),
    { bonusTier: 'المستوى الثاني (4 شهور)', bonusMonths: 4 }
  );
  assert.deepEqual(
    opsMod.evaluateEmployeeBonusTier({ completionPercent: 96, deviationPercent: 4.9, errorCount: 4, innovationCount: 0 }),
    { bonusTier: 'المستوى الأول (3 شهور)', bonusMonths: 3 }
  );
  assert.deepEqual(
    opsMod.evaluateEmployeeBonusTier({ completionPercent: 94, deviationPercent: 2, errorCount: 0, innovationCount: 0 }),
    { bonusTier: 'غير مستوفٍ للشرط', bonusMonths: 0 }
  );
});

test('computeSeqaofaBalances & computeInTransitSummary aggregate Sudan flows', () => {
  const balances = opsMod.computeSeqaofaBalances([
    { custodianName: 'عمر عبدالوهاب', direction: 'وارد', amountSdg: 1200000, equivalentEgp: 100000 },
    { custodianName: 'عمر عبدالوهاب', direction: 'منصرف', amountSdg: 450000, equivalentEgp: 37500 }
  ]);
  assert.equal(balances.length, 1);
  assert.equal(balances[0].balanceSdg, 750000);

  const transit = opsMod.computeInTransitSummary([
    { status: 'جمرك أرقين', cartons116: 300, cartons1Kg: 200, cartonsGallon: 50, customsSdg: 900000, freightEgp: 45000 },
    { status: 'تم الاستلام', cartons116: 999, cartons1Kg: 999, cartonsGallon: 999, customsSdg: 0, freightEgp: 0 }
  ]);
  assert.equal(transit.pendingCount, 1);
  assert.equal(transit.receivedCount, 1);
  assert.equal(transit.totalCartons116, 300);
});
