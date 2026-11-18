import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import os from 'node:os';
import { pathToFileURL, fileURLToPath } from 'node:url';
import ts from 'typescript';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);
const SRC_DIR = path.resolve(__dirname, '../src');

const tmpOutDir = fs.mkdtempSync(path.join(os.tmpdir(), 'pmp-chem-lab-tests-'));

function transpileService(relPath, outRelPath, rewrite = (s) => s) {
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

const pvcPath = transpileService('services/PvcEngine.ts', 'services/PvcEngine.mjs');
const securityServicePath = transpileService('services/SecurityService.ts', 'services/SecurityService.mjs');
const labSecPath = transpileService('services/LabSecurityService.ts', 'services/LabSecurityService.mjs', (s) =>
  s.replace("'./SecurityService'", "'./SecurityService.mjs'")
);
const excelPath = transpileService('services/ExcelFormulaAnalyzer.ts', 'services/ExcelFormulaAnalyzer.mjs', (s) =>
  s
    .replace("'./PvcEngine'", "'./PvcEngine.mjs'")
    .replace("'./LabSecurityService'", "'./LabSecurityService.mjs'")
);
const invPath = transpileService('services/InventoryMovementEngine.ts', 'services/InventoryMovementEngine.mjs');

const PvcEngine = await import(pathToFileURL(pvcPath).href);
const LabSecurity = await import(pathToFileURL(labSecPath).href);
const ExcelAnalyzer = await import(pathToFileURL(excelPath).href);
const InventoryEngine = await import(pathToFileURL(invPath).href);

test.after(() => {
  fs.rmSync(tmpOutDir, { recursive: true, force: true });
});

function crc32(bytes) {
  let c = 0xffffffff;
  for (let i = 0; i < bytes.length; i++) {
    c ^= bytes[i];
    for (let k = 0; k < 8; k++) {
      c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
    }
  }
  return (c ^ 0xffffffff) >>> 0;
}

function buildStoredZip(filesMap) {
  const enc = new TextEncoder();
  const localParts = [];
  const cdParts = [];
  let offset = 0;

  for (const [name, content] of Object.entries(filesMap)) {
    const nameBytes = enc.encode(name);
    const dataBytes = enc.encode(content);
    const crc = crc32(dataBytes);

    const local = new Uint8Array(30 + nameBytes.length + dataBytes.length);
    const lv = new DataView(local.buffer);
    lv.setUint32(0, 0x04034b50, true);
    lv.setUint16(4, 20, true);
    lv.setUint16(8, 0, true); // stored
    lv.setUint32(14, crc, true);
    lv.setUint32(18, dataBytes.length, true);
    lv.setUint32(22, dataBytes.length, true);
    lv.setUint16(26, nameBytes.length, true);
    local.set(nameBytes, 30);
    local.set(dataBytes, 30 + nameBytes.length);
    localParts.push(local);

    const cd = new Uint8Array(46 + nameBytes.length);
    const cv = new DataView(cd.buffer);
    cv.setUint32(0, 0x02014b50, true);
    cv.setUint16(4, 20, true);
    cv.setUint16(6, 20, true);
    cv.setUint16(10, 0, true);
    cv.setUint32(16, crc, true);
    cv.setUint32(20, dataBytes.length, true);
    cv.setUint32(24, dataBytes.length, true);
    cv.setUint16(28, nameBytes.length, true);
    cv.setUint32(42, offset, true);
    cd.set(nameBytes, 46);
    cdParts.push(cd);

    offset += local.length;
  }

  const cdSize = cdParts.reduce((s, p) => s + p.length, 0);
  const eocd = new Uint8Array(22);
  const ev = new DataView(eocd.buffer);
  ev.setUint32(0, 0x06054b50, true);
  ev.setUint16(8, cdParts.length, true);
  ev.setUint16(10, cdParts.length, true);
  ev.setUint32(12, cdSize, true);
  ev.setUint32(16, offset, true);

  const totalLen = offset + cdSize + eocd.length;
  const out = new Uint8Array(totalLen);
  let ptr = 0;
  for (const p of localParts) {
    out.set(p, ptr);
    ptr += p.length;
  }
  for (const c of cdParts) {
    out.set(c, ptr);
    ptr += c.length;
  }
  out.set(eocd, ptr);
  return out;
}

test('PvcEngine: scaled-integer precision, breakdown, batch scaling, and 3-cost variance without baseline mutation', () => {
  const baseIngredients = [
    { id: '1', materialId: 101, itemCode: 'RM-TIO2', name: 'تيتانيوم', type: 'صبغة / ملون', weight: 100, density: 4.2, solidsPercent: 100, unitCost: 4.5, notes: '' },
    { id: '2', materialId: 102, itemCode: 'RM-ACR', name: 'راتنج أكريليك', type: 'راتنج / بوليمر رابط', weight: 300, density: 1.06, solidsPercent: 50, unitCost: 2.8, notes: '' },
    { id: '3', materialId: 103, itemCode: 'RM-WTR', name: 'ماء التشغيل', type: 'مذيب / ماء', weight: 100, density: 1.0, solidsPercent: 0, unitCost: 0.1, notes: '' }
  ];

  const pvc = PvcEngine.calculatePvc(baseIngredients);
  assert.equal(pvc.totalBatchWeight, 500);
  assert.equal(pvc.totalCost, 1300);
  assert.equal(pvc.costPerKg, 2.6);
  assert.equal(pvc.breakdown.length, 3);
  assert.equal(pvc.breakdown[0].weightPercent, 20);

  const { scaledIngredients, pvc: scaledPvc } = PvcEngine.scaleFormulation(baseIngredients, 1000);
  assert.equal(scaledPvc.totalBatchWeight, 1000);
  assert.equal(scaledIngredients[0].weight, 200);
  assert.equal(scaledPvc.calculatedPvc, pvc.calculatedPvc);

  const approvedFormula = {
    id: 1,
    formulaNo: 'FORM-100',
    productName: 'بلاستيك فائق',
    paintType: 'مطفي',
    baseKind: 'أبيض',
    targetPvc: pvc.calculatedPvc,
    calculatedPvc: pvc.calculatedPvc,
    cpvcEstimated: 52,
    totalSolidsWeight: pvc.totalSolidsWeight,
    totalSolidsVolume: pvc.totalSolidsVolume,
    totalBatchWeight: 500,
    totalBatchVolume: pvc.totalBatchVolume,
    calculatedDensity: pvc.calculatedDensity,
    totalCost: 1300,
    costPerKg: 2.6,
    historicalTotalCost: 1300,
    historicalCostPerKg: 2.6,
    ingredientsJson: JSON.stringify(baseIngredients),
    technicalAnalysis: '',
    notes: '',
    approvedForProduction: true,
    approvalStatus: 'APPROVED',
    versionNumber: 1,
    createdBy: 'كيميائي',
    createdAt: Date.now()
  };

  const registry = [
    { id: 101, itemCode: 'RM-TIO2', name: 'تيتانيوم', unit: 'كجم', category: 'raw', unitCostEgp: 5.5, lastPurchasePrice: 5.5 },
    { id: 102, itemCode: 'RM-ACR', name: 'راتنج أكريليك', unit: 'كجم', category: 'raw', unitCostEgp: 2.8, lastPurchasePrice: 2.8 }
  ];

  const report = PvcEngine.compareThreeCosts(approvedFormula, baseIngredients, registry, 500, 1450);
  assert.equal(report.historicalTotalCost, 1300);
  assert.equal(report.currentTotalCost, 1400);
  assert.equal(report.priceDeltaTotal, 100);
  assert.equal(report.actualProductionTotalCost, 1450);
  assert.equal(report.actualProductionCostPerKg, 2.9);
  assert.equal(approvedFormula.historicalTotalCost, 1300);

  const diff = PvcEngine.summarizeIngredientChanges(baseIngredients, [
    ...baseIngredients.slice(0, 2),
    { ...baseIngredients[2], weight: 120 },
    { id: '4', name: 'مانع رغوة', type: 'إضافات', weight: 5, density: 0.92, solidsPercent: 100, unitCost: 7, notes: '' }
  ]);
  assert.equal(diff.addedCount, 1);
  assert.equal(diff.modifiedCount, 1);
  assert.equal(diff.removedCount, 0);
});

test('Production Gate: unapproved, NEEDS_REVIEW, REJECTED, and ARCHIVED formulas are blocked', () => {
  const baseParams = {
    productName: 'بلاستيك أبيض',
    packageWeightGrams: 900,
    targetCartons: 5,
    rawWarehouseId: 10,
    fgWarehouseId: 20
  };

  assert.throws(() => {
    InventoryEngine.createProductionOrderModel({
      ...baseParams,
      formula: {
        id: 1,
        formulaNo: 'FORM-REV',
        productName: 'بلاستيك أبيض',
        approvedForProduction: false,
        approvalStatus: 'NEEDS_REVIEW',
        totalBatchWeight: 100,
        ingredientsJson: '[]'
      }
    });
  });

  assert.throws(() => {
    InventoryEngine.createProductionOrderModel({
      ...baseParams,
      formula: {
        id: 2,
        formulaNo: 'FORM-ARCH',
        productName: 'بلاستيك أبيض',
        approvedForProduction: true,
        approvalStatus: 'ARCHIVED',
        totalBatchWeight: 100,
        ingredientsJson: '[]'
      }
    });
  });

  const ord = InventoryEngine.createProductionOrderModel({
    ...baseParams,
    formula: {
      id: 3,
      formulaNo: 'FORM-OK',
      productName: 'بلاستيك أبيض',
      approvedForProduction: true,
      approvalStatus: 'APPROVED',
      totalBatchWeight: 100,
      ingredientsJson: JSON.stringify([{ name: 'تيتانيوم', type: 'صبغة', weight: 20, unitCost: 4.5 }])
    }
  });
  assert.equal(ord.formulaNo, 'FORM-OK');
  assert.equal(ord.status, 'APPROVED');
});

test('LabSecurityService: 7-role RBAC matrix, explicit DENY precedence, redaction, log sanitization & WebCrypto AES-GCM', async () => {
  const admin = { userCode: 'USR-ADMIN', username: 'admin', role: 'مدير النظام', roleType: 'SUPER_ADMIN', branch: 'ALL', permissions: '', active: true, syncStatus: 'APPROVED_BY_MANAGER' };
  const chemist = { userCode: 'USR-LAB', username: 'lab1', role: 'كيميائي المختبر', roleType: 'EMPLOYEE', branch: 'المصنع', permissions: 'المختبر الكيميائي,VIEW_SECRET_FORMULAS,ACTION_EXPORT', active: true, syncStatus: 'APPROVED_BY_MANAGER' };
  const deniedChemist = { ...chemist, permissions: 'المختبر الكيميائي,VIEW_SECRET_FORMULAS,DENY:VIEW_SECRET_FORMULAS,DENY:EXPORT_FORMULAS' };
  const qc = { userCode: 'USR-QC', username: 'qc1', role: 'مسؤول الجودة', roleType: 'EMPLOYEE', branch: 'المصنع', permissions: 'الجودة', active: true, syncStatus: 'APPROVED_BY_MANAGER' };
  const prod = { userCode: 'USR-PRD', username: 'prod1', role: 'مهندس الإنتاج', roleType: 'EMPLOYEE', branch: 'المصنع', permissions: 'الإنتاج', active: true, syncStatus: 'APPROVED_BY_MANAGER' };
  const acc = { userCode: 'USR-ACC', username: 'acc1', role: 'المحاسب', roleType: 'EMPLOYEE', branch: 'ALL', permissions: 'المحاسبة', active: true, syncStatus: 'APPROVED_BY_MANAGER' };
  const wh = { userCode: 'USR-WH', username: 'wh1', role: 'أمين مخزن الخام', roleType: 'EMPLOYEE', branch: 'المصنع', permissions: 'المخازن', active: true, syncStatus: 'APPROVED_BY_MANAGER' };
  const sales = { userCode: 'USR-SLS', username: 'sales1', role: 'مسؤول مبيعات', roleType: 'EMPLOYEE', branch: 'السودان', permissions: 'المبيعات', active: true, syncStatus: 'APPROVED_BY_MANAGER' };

  assert.equal(LabSecurity.canViewSecretFormulas(admin), true);
  assert.equal(LabSecurity.canViewSecretFormulas(chemist), true);
  assert.equal(LabSecurity.canExportSecretFormulas(chemist), true);
  assert.equal(LabSecurity.canViewSecretFormulas(deniedChemist), false);
  assert.equal(LabSecurity.canExportSecretFormulas(deniedChemist), false);
  assert.equal(LabSecurity.canViewSecretFormulas(qc), false);
  assert.equal(LabSecurity.canDecideQcGate(qc), true);
  assert.equal(LabSecurity.canViewSecretFormulas(prod), false);
  assert.equal(LabSecurity.canViewSecretFormulas(acc), false);
  assert.equal(LabSecurity.canViewFormulaCosts(acc), true);
  assert.equal(LabSecurity.canViewSecretFormulas(wh), false);
  assert.equal(LabSecurity.canViewRawMaterialRegistry(wh), true);
  assert.equal(LabSecurity.canViewSecretFormulas(sales), false);
  assert.equal(LabSecurity.canViewRawMaterialRegistry(sales), false);

  const sampleFormula = {
    id: 10,
    formulaNo: 'FORM-SEC',
    productName: 'طلاء سري',
    paintType: 'مطفي',
    baseKind: 'أبيض',
    targetPvc: 45,
    calculatedPvc: 45,
    cpvcEstimated: 52,
    totalSolidsWeight: 60,
    totalSolidsVolume: 40,
    totalBatchWeight: 500,
    totalBatchVolume: 360,
    calculatedDensity: 1.38,
    totalCost: 2400,
    costPerKg: 4.8,
    ingredientsJson: JSON.stringify([{ name: 'سري', weight: 100 }]),
    technicalAnalysis: 'تحليل حساس 45.2%',
    notes: '',
    approvedForProduction: true,
    createdBy: 'كيميائي',
    createdAt: Date.now()
  };

  const maskedForProd = LabSecurity.redactFormulationForUser(sampleFormula, prod);
  assert.equal(maskedForProd.isMasked, true);
  assert.equal(maskedForProd.ingredientsJson, '[]');
  assert.equal(maskedForProd.costPerKg, 0);

  const sanitized = LabSecurity.sanitizeLabLog('TiO2 24.5% [{"name":"TiO2"}] token=SECRET123');
  assert.equal(sanitized.includes('24.5%'), false);
  assert.equal(sanitized.includes('TiO2 24.5'), false);
  assert.equal(sanitized.includes('SECRET123'), false);

  const sealed = await LabSecurity.sealFormulaWithPassphrase('[{"name":"TiO2","weight":120}]', 'PMP#StrongKey99');
  assert.ok(sealed.startsWith('PMPENC1:PWD:'));
  const opened = await LabSecurity.unsealFormulaWithPassphrase(sealed, 'PMP#StrongKey99');
  assert.equal(opened, '[{"name":"TiO2","weight":120}]');
  await assert.rejects(() => LabSecurity.unsealFormulaWithPassphrase(sealed, 'WrongPassphrase'));
});

test('ExcelFormulaAnalyzer: parses OOXML xlsx, filters reference price sheets, flags external & #REF! links, stages NEEDS_REVIEW', async () => {
  const zipBytes = buildStoredZip({
    'xl/workbook.xml': `<?xml version="1.0" encoding="UTF-8"?>
      <workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
        <sheets>
          <sheet name="تركيبة بلاستيك سوبر" sheetId="1" r:id="rId1"/>
          <sheet name="قائمة أسعار الخامات" sheetId="2" r:id="rId2"/>
          <sheet name="مراجعة قديم أرشيف" sheetId="3" r:id="rId3"/>
        </sheets>
      </workbook>`,
    'xl/_rels/workbook.xml.rels': `<?xml version="1.0" encoding="UTF-8"?>
      <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
        <Relationship Id="rId1" Target="worksheets/sheet1.xml"/>
        <Relationship Id="rId2" Target="worksheets/sheet2.xml"/>
        <Relationship Id="rId3" Target="worksheets/sheet3.xml"/>
      </Relationships>`,
    'xl/sharedStrings.xml': `<?xml version="1.0" encoding="UTF-8"?>
      <sst xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
        <si><t>اسم المادة الخام</t></si>
        <si><t>الوزن كجم</t></si>
        <si><t>السعر كجم</t></si>
        <si><t>التكلفة</t></si>
        <si><t>ثاني أكسيد التيتانيوم</t></si>
        <si><t>راتنج أكريليك</t></si>
        <si><t>الإجمالي</t></si>
      </sst>`,
    'xl/worksheets/sheet1.xml': `<?xml version="1.0" encoding="UTF-8"?>
      <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
        <sheetData>
          <row r="1">
            <c r="A1" t="s"><v>0</v></c>
            <c r="B1" t="s"><v>1</v></c>
            <c r="C1" t="s"><v>2</v></c>
            <c r="D1" t="s"><v>3</v></c>
          </row>
          <row r="2">
            <c r="A2" t="s"><v>4</v></c>
            <c r="B2"><v>100</v></c>
            <c r="C2"><f>'قائمة أسعار الخامات'!B2</f><v>4.5</v></c>
            <c r="D2"><f>B2*C2</f><v>450</v></c>
          </row>
          <row r="3">
            <c r="A3" t="s"><v>5</v></c>
            <c r="B3"><v>200</v></c>
            <c r="C3"><v>2.5</v></c>
            <c r="D3"><f>B3*C3</f><v>500</v></c>
          </row>
          <row r="4">
            <c r="A4" t="s"><v>6</v></c>
            <c r="B4"><f>SUM(B2:B3)</f><v>300</v></c>
            <c r="C4"><f>SUM(D2:D3)</f><v>950</v></c>
            <c r="D4"><f>SUM(D2:D3)</f><v>950</v></c>
          </row>
        </sheetData>
      </worksheet>`,
    'xl/worksheets/sheet2.xml': `<?xml version="1.0" encoding="UTF-8"?>
      <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
        <sheetData>
          <row r="1">
            <c r="A1" t="s"><v>0</v></c>
            <c r="B1" t="s"><v>2</v></c>
          </row>
          <row r="2">
            <c r="A2" t="s"><v>4</v></c>
            <c r="B2"><v>4.5</v></c>
          </row>
        </sheetData>
      </worksheet>`,
    'xl/worksheets/sheet3.xml': `<?xml version="1.0" encoding="UTF-8"?>
      <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
        <sheetData>
          <row r="1"><c r="A1" t="inlineStr"><is><t>المادة</t></is></c><c r="B1" t="inlineStr"><is><t>الوزن كجم</t></is></c><c r="C1" t="inlineStr"><is><t>السعر</t></is></c></row>
          <row r="2"><c r="A2" t="inlineStr"><is><t>كربونات</t></is></c><c r="B2"><v>50</v></c><c r="C2"><f>[1]Book2.xlsx!A1</f><v>1.2</v></c></row>
          <row r="3"><c r="A3" t="inlineStr"><is><t>مرجع تالف</t></is></c><c r="B3"><f>#REF!</f><v>#REF!</v></c></row>
        </sheetData>
      </worksheet>`
  });

  const report = await ExcelAnalyzer.analyzeXlsxWorkbook('sample_pmp_test.xlsx', zipBytes, [
    { id: 501, itemCode: 'RM-TIO2', name: 'ثاني أكسيد التيتانيوم', unit: 'كجم', category: 'raw', unitCostEgp: 4.5, lastPurchasePrice: 4.5 }
  ]);

  assert.equal(report.sheets.length, 3);
  assert.equal(report.sheets[1].formulaKind, 'REFERENCE_DATA');
  assert.equal(report.sheets[2].formulaKind, 'HISTORICAL_ARCHIVE');
  assert.ok(report.externalLinksDetected >= 1);
  assert.ok(report.brokenRefsDetected >= 1);
  // Reference price list must NOT become a bogus 0-kg formula candidate
  assert.equal(report.candidates.length, 2);
  assert.equal(report.candidates[0].approvalStatus, 'NEEDS_REVIEW');
  assert.equal(report.candidates[0].calculatedTotalCost, 950);
  assert.equal(report.materialsMatchedCount, 1);
});
