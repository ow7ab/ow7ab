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

const tmpOutDir = fs.mkdtempSync(path.join(os.tmpdir(), 'pmp-erp-phase2-tests-'));

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

const InventoryEngine = await loadTsModule('InventoryMovementEngine.ts');
const ErpCalculations = await loadTsModule('ErpCalculations.ts');

test.after(() => {
  fs.rmSync(tmpOutDir, { recursive: true, force: true });
});

test('Phase 2: Three-Warehouse isolation & QC policy checks match Android Room engine', () => {
  const rawWh = { id: 5, name: 'مخزن المواد الخام داخل المصنع', warehouseType: 'مواد خام', active: true, currency: 'EGP' };
  const fgWh = { id: 6, name: 'مخزن الإنتاج التام داخل المصنع', warehouseType: 'داخل المصنع/إنتاج', active: true, currency: 'EGP' };
  const salesWh = { id: 1, name: 'مخزن مبيعات حلفا', warehouseType: 'مبيعات', active: true, currency: 'SDG' };

  assert.equal(InventoryEngine.resolveWarehouseClass(rawWh), 'RAW_MATERIALS');
  assert.equal(InventoryEngine.resolveWarehouseClass(fgWh), 'FINISHED_GOODS');
  assert.equal(InventoryEngine.resolveWarehouseClass(salesWh), 'SALES');

  let state = { movements: [], balances: [] };

  // Receive raw material into RAW_MATERIALS
  const r1 = InventoryEngine.postMovementInState(state, {
    warehouse: rawWh,
    movementType: 'PURCHASE_RECEIPT',
    itemCategory: 'RAW_MATERIAL',
    itemId: 101,
    itemName: 'راتنج أكريليك',
    quantity: 300,
    unit: 'كجم',
    unitCost: 80,
    idempotencyKey: 'PUR-101'
  });
  state = r1.state;
  assert.equal(r1.balance.availableQty, 300);

  // Idempotency: second call with same key leaves balance 300
  const r1Dup = InventoryEngine.postMovementInState(state, {
    warehouse: rawWh,
    movementType: 'PURCHASE_RECEIPT',
    itemCategory: 'RAW_MATERIAL',
    itemId: 101,
    itemName: 'راتنج أكريليك',
    quantity: 300,
    idempotencyKey: 'PUR-101'
  });
  state = r1Dup.state;
  assert.equal(r1Dup.balance.availableQty, 300);
  assert.equal(state.movements.length, 1);

  // Direct sale from FINISHED_GOODS blocked
  assert.throws(() => {
    InventoryEngine.postMovementInState(state, {
      warehouse: fgWh,
      movementType: 'SALES_ISSUE',
      itemCategory: 'FINISHED_GOOD',
      itemId: 1,
      itemName: 'بوهية أبيض',
      quantity: 5
    });
  });

  // Direct production receipt into SALES blocked
  assert.throws(() => {
    InventoryEngine.postMovementInState(state, {
      warehouse: salesWh,
      movementType: 'PRODUCTION_RECEIPT',
      itemCategory: 'FINISHED_GOOD',
      itemId: 1,
      itemName: 'بوهية أبيض',
      quantity: 5,
      qcApproved: true
    });
  });

  // Unapproved production receipt into FINISHED_GOODS without quarantine blocked
  assert.throws(() => {
    InventoryEngine.postMovementInState(state, {
      warehouse: fgWh,
      movementType: 'PRODUCTION_RECEIPT',
      itemCategory: 'FINISHED_GOOD',
      itemId: 1,
      itemName: 'بوهية أبيض',
      quantity: 10,
      qcApproved: false,
      quarantineHold: false
    });
  });
});

test('Phase 2: Append-only protection, reversing entries & negative stock guard', () => {
  const salesWh = { id: 1, name: 'مخزن مبيعات حلفا', warehouseClass: 'SALES', active: true, currency: 'SDG' };
  let state = { movements: [], balances: [] };

  const ob = InventoryEngine.postMovementInState(state, {
    warehouse: salesWh,
    movementType: 'OPENING_BALANCE',
    itemCategory: 'FINISHED_GOOD',
    itemId: 7,
    itemName: 'بوهية ذهبي 250 جم',
    quantity: 20,
    unitCost: 1500
  });
  state = ob.state;

  // Negative stock rejected
  assert.throws(() => {
    InventoryEngine.postMovementInState(state, {
      warehouse: salesWh,
      movementType: 'SALES_ISSUE',
      itemCategory: 'FINISHED_GOOD',
      itemId: 7,
      itemName: 'بوهية ذهبي 250 جم',
      quantity: 25
    });
  });

  // Direct deletion rejected
  assert.throws(() => InventoryEngine.attemptDeleteMovement(ob.movement.id));

  // Issue 5 cartons then reverse it cleanly
  const sale = InventoryEngine.postMovementInState(state, {
    warehouse: salesWh,
    movementType: 'SALES_ISSUE',
    itemCategory: 'FINISHED_GOOD',
    itemId: 7,
    itemName: 'بوهية ذهبي 250 جم',
    quantity: 5
  });
  state = sale.state;
  assert.equal(sale.balance.availableQty, 15);

  const reversed = InventoryEngine.reverseMovementInState(state, sale.movement.id, 'خطأ في الصنف', 'admin');
  state = reversed.state;
  assert.equal(state.movements.find((m) => m.id === sale.movement.id).status, 'REVERSED');
  assert.equal(state.balances[0].availableQty, 20);
  assert.equal(reversed.reversalMovement.signedQuantity, 5);
});

test('Phase 2: Transfer route validation, SoD, packaging rules (250=48, 900=12, 4000=4) & deduplication', () => {
  assert.equal(InventoryEngine.validateTransferRoute('RAW_MATERIALS', 'SALES', [{ itemCategory: 'RAW_MATERIAL', requestedQty: 5, qcApprovedRequired: false, qcApproved: true }]) !== null, true);
  assert.equal(InventoryEngine.validateTransferRoute('FINISHED_GOODS', 'SALES', [{ itemCategory: 'FINISHED_GOOD', requestedQty: 5, qcApprovedRequired: true, qcApproved: false }]) !== null, true);
  assert.equal(InventoryEngine.validateTransferRoute('FINISHED_GOODS', 'SALES', [{ itemCategory: 'FINISHED_GOOD', requestedQty: 5, qcApprovedRequired: true, qcApproved: true }]), null);

  const sodBlocked = InventoryEngine.canApproveTransfer(
    { status: 'PENDING_APPROVAL', createdBy: 'halfa_logistics' },
    { username: 'halfa_logistics', userCode: 'USR-HF-004', role: 'أمين مخزن وترحيل', roleType: 'EMPLOYEE', active: true }
  );
  assert.equal(sodBlocked.ok, false);

  const sodAdminOk = InventoryEngine.canApproveTransfer(
    { status: 'PENDING_APPROVAL', createdBy: 'admin' },
    { username: 'admin', userCode: 'USR-PMP-001', role: 'مدير النظام', roleType: 'SUPER_ADMIN', active: true }
  );
  assert.equal(sodAdminOk.ok, true);

  assert.deepEqual(InventoryEngine.convertCansToCartons(250, 100), { weightGrams: 250, cansPerCarton: 48, totalCans: 100, fullCartons: 2, looseCans: 4 });
  assert.equal(InventoryEngine.convertCartonsToCans(900, 3), 36);
  assert.equal(InventoryEngine.convertCartonsToCans(4000, 5), 20);

  // Deduplication checks
  const dedupedMovements = ErpCalculations.deduplicateCollectionRecords('inventory_movements', [
    { syncUuid: 'UUID-1', id: 1, quantity: 10, updatedAt: 100 },
    { syncUuid: 'UUID-1', id: 1, quantity: 10, updatedAt: 200 }
  ]);
  assert.equal(dedupedMovements.length, 1);
  assert.equal(dedupedMovements[0].updatedAt, 200);
});
