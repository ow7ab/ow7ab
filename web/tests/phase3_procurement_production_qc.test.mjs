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

const tmpOutDir = fs.mkdtempSync(path.join(os.tmpdir(), 'pmp-erp-phase3-tests-'));

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

const Engine = await loadTsModule('InventoryMovementEngine.ts');
const ErpCalculations = await loadTsModule('ErpCalculations.ts');

test.after(() => {
  fs.rmSync(tmpOutDir, { recursive: true, force: true });
});

const rawWh = { id: 10, name: 'مخزن المواد الخام بالمصنع', warehouseClass: 'RAW_MATERIALS', active: true, currency: 'EGP' };
const fgWh = { id: 20, name: 'مخزن الإنتاج التام بالمصنع', warehouseClass: 'FINISHED_GOODS', active: true, currency: 'EGP' };
const salesWh = { id: 30, name: 'مخزن مبيعات الخرطوم', warehouseClass: 'SALES', active: true, currency: 'SDG' };

test('Phase 3: GRN accepts valid quantity to RAW_MATERIALS & quarantines rejected quantity with required discrepancy reason', () => {
  let state = { movements: [], balances: [] };

  // Missing discrepancy reason when rejected > 0 must fail
  assert.throws(() => {
    Engine.receivePurchaseToRawMaterialsInState(state, {
      supplierName: 'شركة الكيماويات',
      rawWarehouse: rawWh,
      materialId: 101,
      materialName: 'تيتانيوم دايوكسيد',
      orderedQty: 100,
      receivedQty: 100,
      acceptedQty: 85,
      rejectedQty: 15,
      unitCost: 120,
      discrepancyReason: ''
    });
  });

  // Cannot receive purchase directly into SALES or FINISHED_GOODS
  assert.throws(() => {
    Engine.receivePurchaseToRawMaterialsInState(state, {
      supplierName: 'مورد',
      rawWarehouse: salesWh,
      materialId: 101,
      materialName: 'تيتانيوم دايوكسيد',
      receivedQty: 50,
      acceptedQty: 50,
      rejectedQty: 0
    });
  });

  const res = Engine.receivePurchaseToRawMaterialsInState(state, {
    supplierName: 'شركة الكيماويات الحديثة',
    rawWarehouse: rawWh,
    materialId: 101,
    materialName: 'تيتانيوم دايوكسيد',
    batchNumber: 'RAW-2026-A1',
    orderedQty: 100,
    receivedQty: 100,
    acceptedQty: 85,
    rejectedQty: 15,
    unitCost: 120,
    currency: 'EGP',
    discrepancyReason: 'تلف 15 كجم بسبب الرطوبة أثناء الشحن',
    now: 1700000000000
  });

  state = res.state;
  assert.equal(res.receipt.qcStatus, 'PARTIAL');
  assert.equal(res.receipt.stockPosted, true);
  assert.equal(res.receipt.totalCost, 85 * 120);

  const acceptedBal = state.balances.find((b) => b.batchNumber === 'RAW-2026-A1');
  const rejBal = state.balances.find((b) => b.batchNumber === 'RAW-2026-A1-REJ');
  assert.equal(acceptedBal.availableQty, 85);
  assert.equal(acceptedBal.quarantineQty, 0);
  assert.equal(rejBal.quarantineQty, 15);
  assert.equal(rejBal.availableQty, 0);
});

test('Phase 3: BOM Production Order scales ingredients & applies packaging rates (250=48, 900=12, 4000=4)', () => {
  const formula = {
    id: 55,
    formulaNo: 'FRM-W900',
    productName: 'بلاستيك مط أبيض',
    paintType: 'مائي',
    baseKind: 'أبيض',
    targetPvc: 60,
    calculatedPvc: 60,
    cpvcEstimated: 65,
    totalSolidsWeight: 70,
    totalSolidsVolume: 50,
    totalBatchWeight: 100,
    totalBatchVolume: 75,
    calculatedDensity: 1.33,
    totalCost: 4500,
    costPerKg: 45,
    ingredientsJson: JSON.stringify([
      { name: 'راتنج أكريليك', type: 'Binder', weight: 40, unitCost: 80 },
      { name: 'تيتانيوم دايوكسيد', type: 'Pigment', weight: 30, unitCost: 120 },
      { name: 'ماء ومضافات', type: 'Solvent', weight: 30, unitCost: 5 }
    ]),
    technicalAnalysis: '',
    notes: '',
    approvedForProduction: true,
    createdBy: 'chemist',
    createdAt: 1700000000000
  };

  // 250g -> 48 cans/carton -> 10 cartons = 480 cans = 120 kg -> scale 1.2x
  const ord250 = Engine.createProductionOrderModel({
    formula,
    productName: 'بلاستيك مط أبيض 250',
    packageWeightGrams: 250,
    targetCartons: 10,
    rawWarehouseId: rawWh.id,
    fgWarehouseId: fgWh.id
  });
  assert.equal(ord250.cansPerCarton, 48);
  assert.equal(ord250.targetCans, 480);
  assert.equal(ord250.targetWeightKg, 120);
  const bom250 = JSON.parse(ord250.bomLinesJson);
  assert.equal(bom250[0].theoreticalRequiredKg, 48); // 40 * 1.2

  // 900g -> 12 cans/carton -> 5 cartons = 60 cans
  const ord900 = Engine.createProductionOrderModel({
    productName: 'بوهية 900',
    packageWeightGrams: 900,
    targetCartons: 5,
    rawWarehouseId: rawWh.id,
    fgWarehouseId: fgWh.id
  });
  assert.equal(ord900.cansPerCarton, 12);
  assert.equal(ord900.targetCans, 60);

  // 4000g -> 4 cans/carton -> 5 cartons = 20 cans
  const ord4000 = Engine.createProductionOrderModel({
    productName: 'جالون 4000',
    packageWeightGrams: 4000,
    targetCartons: 5,
    rawWarehouseId: rawWh.id,
    fgWarehouseId: fgWh.id
  });
  assert.equal(ord4000.cansPerCarton, 4);
  assert.equal(ord4000.targetCans, 20);
});

test('Phase 3: Raw material issue prevents negative stock, supports surplus return & QC gates FINISHED_GOODS stock', () => {
  let state = { movements: [], balances: [] };

  state = Engine.receivePurchaseToRawMaterialsInState(state, {
    supplierName: 'مورد راتنج',
    rawWarehouse: rawWh,
    materialId: 201,
    materialName: 'راتنج أكريليك',
    batchNumber: 'RAW-RESIN-01',
    receivedQty: 100,
    acceptedQty: 100,
    rejectedQty: 0,
    unitCost: 80
  }).state;

  const order = Engine.createProductionOrderModel({
    productName: 'بوهية ذهبي 900 جم',
    packageWeightGrams: 900,
    targetCartons: 5,
    rawWarehouseId: rawWh.id,
    fgWarehouseId: fgWh.id,
    now: 1700001000000
  });

  // Attempting to issue 150 kg (> 100 kg available) must throw
  assert.throws(() => {
    Engine.issueRawMaterialsForOrderInState(state, order, rawWh, [
      { itemId: 201, itemName: 'راتنج أكريليك', qty: 150, batchNumber: 'RAW-RESIN-01' }
    ]);
  });

  // Valid issue 60 kg + surplus return 10 kg + waste 2 kg -> net raw consumed 50 kg -> 50 remaining
  const issued = Engine.issueRawMaterialsForOrderInState(
    state,
    order,
    rawWh,
    [{ itemId: 201, itemName: 'راتنج أكريليك', qty: 60, returnedQty: 10, wastedQty: 2, batchNumber: 'RAW-RESIN-01' }],
    'factory_storekeeper',
    1700002000000
  );
  state = issued.state;
  assert.equal(issued.order.status, 'MATERIALS_ISSUED');
  assert.equal(issued.order.wastedRawKg, 2);
  const rawBal = state.balances.find((b) => b.itemId === 201 && b.batchNumber === 'RAW-RESIN-01');
  assert.equal(rawBal.availableQty, 50);

  // Failed QC (colorMatchPassed = false) quarantines entire production & adds 0 available stock
  const failedQc = Engine.inspectQcAndPostToFinishedGoodsInState(state, {
    order: issued.order,
    fgWarehouse: fgWh,
    productId: 901,
    measuredDensity: 1.25,
    expectedDensity: 1.25,
    colorMatchPassed: false,
    goodCans: 60,
    now: 1700003000000
  });
  assert.equal(failedQc.qc.status, 'REJECTED');
  assert.equal(failedQc.qc.postedToStock, false);
  assert.equal(failedQc.order.status, 'QC_REJECTED');
  const qBal = failedQc.state.balances.find((b) => b.warehouseId === fgWh.id);
  assert.equal(qBal.availableQty, 0);
  assert.equal(qBal.quarantineQty, 5);

  // Passing QC posts 5 full cartons (60 cans / 12) into FINISHED_GOODS available stock
  const passedQc = Engine.inspectQcAndPostToFinishedGoodsInState(state, {
    order: issued.order,
    fgWarehouse: fgWh,
    productId: 901,
    measuredDensity: 1.26,
    expectedDensity: 1.25,
    colorMatchPassed: true,
    goodCans: 60,
    now: 1700004000000
  });
  state = passedQc.state;
  assert.equal(passedQc.qc.status, 'APPROVED');
  assert.equal(passedQc.qc.postedToStock, true);
  assert.equal(passedQc.order.status, 'POSTED_TO_FG');
  assert.equal(passedQc.order.producedFullCartons, 5);
  const fgBal = state.balances.find((b) => b.warehouseId === fgWh.id && b.batchNumber === issued.order.batchNumber);
  assert.equal(fgBal.availableQty, 5);

  // Duplicate QC posting on already POSTED_TO_FG order must be blocked
  assert.throws(() => {
    Engine.inspectQcAndPostToFinishedGoodsInState(state, {
      order: passedQc.order,
      fgWarehouse: fgWh,
      productId: 901,
      measuredDensity: 1.25,
      colorMatchPassed: true,
      goodCans: 60
    });
  });
});

test('Phase 3: Sales isolation & Phase 3 collection deduplication', () => {
  assert.equal(Engine.isSalesWarehouse(rawWh), false);
  assert.equal(Engine.isSalesWarehouse(fgWh), false);
  assert.equal(Engine.isSalesWarehouse(salesWh), true);

  let state = { movements: [], balances: [] };
  state = Engine.postMovementInState(state, {
    warehouse: salesWh,
    movementType: 'OPENING_BALANCE',
    itemCategory: 'FINISHED_GOOD',
    itemId: 901,
    itemName: 'بوهية ذهبي 900 جم',
    quantity: 10
  }).state;

  // Sale from SALES warehouse succeeds
  const sold = Engine.postMovementInState(state, {
    warehouse: salesWh,
    movementType: 'SALES_ISSUE',
    itemCategory: 'FINISHED_GOOD',
    itemId: 901,
    itemName: 'بوهية ذهبي 900 جم',
    quantity: 4
  });
  assert.equal(sold.balance.availableQty, 6);

  // Phase 3 deduplication checks
  const dedupedGrn = ErpCalculations.deduplicateCollectionRecords('purchase_receipts', [
    { syncUuid: 'PR-GRN-1', grnNo: 'GRN-1', acceptedQty: 50, updatedAt: 100 },
    { syncUuid: 'PR-GRN-1', grnNo: 'GRN-1', acceptedQty: 50, updatedAt: 200 }
  ]);
  assert.equal(dedupedGrn.length, 1);
  assert.equal(dedupedGrn[0].updatedAt, 200);
});
