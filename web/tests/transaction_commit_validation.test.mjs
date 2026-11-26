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
const tmpOutDir = fs.mkdtempSync(path.join(os.tmpdir(), 'pmp-tx-commit-tests-'));

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

transpileRelative('types/erp.ts', 'types/erp.mjs');
const calcPath = transpileRelative('services/ErpCalculations.ts', 'services/ErpCalculations.mjs', (s) =>
  s.replace('../types/erp', '../types/erp.mjs')
);
const errLogPath = transpileRelative('services/CloudErrorLogger.ts', 'services/CloudErrorLogger.mjs');

const calcMod = await import(pathToFileURL(calcPath).href);
const errLogMod = await import(pathToFileURL(errLogPath).href);

test.after(() => {
  fs.rmSync(tmpOutDir, { recursive: true, force: true });
});

test('validateFinancialCommitPayload blocks tampered amounts, currencies, directions, and mismatched transfer FX math', () => {
  const { validateFinancialCommitPayload } = calcMod;

  assert.equal(
    validateFinancialCommitPayload('movements', 'MOV-1', {
      cashbox: 'خزنة السودان',
      currency: 'SDG',
      direction: 'وارد',
      amount: 15000,
      reference: 'REC-101'
    }).valid,
    true
  );

  // Negative/zero/unrealistic amounts -> TAMPER
  const neg = validateFinancialCommitPayload('movements', 'MOV-BAD1', {
    cashbox: 'خزنة السودان',
    currency: 'SDG',
    direction: 'وارد',
    amount: -500
  });
  assert.equal(neg.valid, false);
  assert.equal(neg.violationType, 'TAMPER');

  // Unsupported currency -> TAMPER
  const badCur = validateFinancialCommitPayload('movements', 'MOV-BAD2', {
    cashbox: 'خزنة السودان',
    currency: 'BTC',
    direction: 'وارد',
    amount: 500
  });
  assert.equal(badCur.valid, false);
  assert.equal(badCur.violationType, 'TAMPER');

  // Tampered transfer destinationAmount vs rate -> TAMPER
  const badTrf = validateFinancialCommitPayload('transfers', 'TRF-BAD', {
    sourceCashbox: 'خزنة السودان',
    destinationCashbox: 'خزنة مصر',
    sourceCurrency: 'SDG',
    destinationCurrency: 'EGP',
    sourceAmount: 100000,
    rate: 0.02,
    destinationAmount: 999999
  });
  assert.equal(badTrf.valid, false);
  assert.equal(badTrf.violationType, 'TAMPER');

  // Valid transfer (inverted or direct) passes
  const validTrf = validateFinancialCommitPayload('transfers', 'TRF-OK', {
    sourceCashbox: 'خزنة السودان',
    destinationCashbox: 'خزنة مصر',
    sourceCurrency: 'SDG',
    destinationCurrency: 'EGP',
    sourceAmount: 100000,
    rate: 50,
    destinationAmount: 2000
  });
  assert.equal(validTrf.valid, true);
});

test('checkExistingFinancialConflict detects Firestore duplicates vs tampered overwrites while preserving transfer legs', () => {
  const { checkExistingFinancialConflict } = calcMod;

  const existingMovements = [
    {
      _docId: 'MOV-IDEM-700',
      reference: 'REC-700',
      cashbox: 'خزنة السودان',
      direction: 'وارد',
      currency: 'SDG',
      amount: 25000,
      syncUuid: 'uuid-mov-700'
    }
  ];

  // Exact duplicate via reference + cashbox + direction -> DUPLICATE
  const dup = checkExistingFinancialConflict(
    'movements',
    'MOV-NEW-999',
    {
      reference: 'REC-700',
      cashbox: 'خزنه السودان',
      direction: 'وارد',
      currency: 'SDG',
      amount: 25000
    },
    existingMovements
  );
  assert.equal(dup.valid, false);
  assert.equal(dup.violationType, 'DUPLICATE');

  // Same docId/syncUuid with altered amount -> TAMPER
  const tamper = checkExistingFinancialConflict(
    'movements',
    'MOV-IDEM-700',
    {
      reference: 'REC-700',
      cashbox: 'خزنة السودان',
      direction: 'وارد',
      currency: 'SDG',
      amount: 99000
    },
    existingMovements
  );
  assert.equal(tamper.valid, false);
  assert.equal(tamper.violationType, 'TAMPER');

  // Opposite transfer leg with same reference in destination cashbox must NOT conflict
  const transferLegOk = checkExistingFinancialConflict(
    'movements',
    'MOV-IDEM-701-IN',
    {
      reference: 'REC-700',
      cashbox: 'خزنة مصر',
      direction: 'منصرف',
      currency: 'EGP',
      amount: 500
    },
    existingMovements
  );
  assert.equal(transferLegOk.valid, true);
});

test('CloudErrorLogger records SECURITY_TAMPER and DUPLICATE_TRANSACTION violations cleanly in error log', () => {
  const { CloudErrorLogger } = errLogMod;
  CloudErrorLogger.clear();

  const tamperEntry = CloudErrorLogger.recordTamperAttempt(
    'HANDLE_TRANSACTION_COMMIT',
    'erp_clusters/PMP-ERP-MAIN-CLUSTER/movements/MOV-10',
    'قيمة غير صالحة',
    'cashier_1'
  );
  assert.equal(tamperEntry.category, 'SECURITY_TAMPER');

  const dupEntry = CloudErrorLogger.recordDuplicateAttempt(
    'HANDLE_TRANSACTION_COMMIT',
    'erp_clusters/PMP-ERP-MAIN-CLUSTER/movements/MOV-11',
    'معاملة مكررة',
    'cashier_1'
  );
  assert.equal(dupEntry.category, 'DUPLICATE_TRANSACTION');
  assert.equal(CloudErrorLogger.getEntries().length, 2);
});

test('Web modules & ErpDataService wire handleTransactionCommit and smooth tab navigation', () => {
  const erpDataSrc = fs.readFileSync(path.join(SRC_DIR, 'services/ErpDataService.ts'), 'utf8');
  const treasurySrc = fs.readFileSync(path.join(SRC_DIR, 'components/modules/TreasuryModuleView.tsx'), 'utf8');
  const salesSrc = fs.readFileSync(path.join(SRC_DIR, 'components/modules/SalesModuleView.tsx'), 'utf8');
  const purchasesSrc = fs.readFileSync(path.join(SRC_DIR, 'components/modules/PurchasesModuleView.tsx'), 'utf8');
  const portalSrc = fs.readFileSync(path.join(SRC_DIR, 'pages/PortalPage.tsx'), 'utf8');
  const cssSrc = fs.readFileSync(path.join(SRC_DIR, 'styles/designSystem.css'), 'utf8');

  assert.equal(erpDataSrc.includes('async handleTransactionCommit'), true);
  assert.equal(erpDataSrc.includes('TAMPER_ATTEMPT_BLOCKED'), true);
  assert.equal(erpDataSrc.includes('DUPLICATE_TRANSACTION_BLOCKED'), true);
  assert.equal(
    treasurySrc.includes('ErpDataService.handleTransactionCommit') ||
      treasurySrc.includes('ErpDataService.commitAtomicTransactionBundle'),
    true
  );
  assert.equal(
    salesSrc.includes('ErpDataService.handleTransactionCommit') ||
      salesSrc.includes('ErpDataService.commitAtomicTransactionBundle'),
    true
  );
  assert.equal(
    purchasesSrc.includes('ErpDataService.handleTransactionCommit') ||
      purchasesSrc.includes('ErpDataService.commitAtomicTransactionBundle'),
    true
  );
  assert.equal(portalSrc.includes('pmp-tab-transition'), true);
  assert.equal(portalSrc.includes('navigateToTab'), true);
  assert.equal(cssSrc.includes('@keyframes pmpTabEnter'), true);
});

test('Modal renders through document.body portal with working max-width mapping, unconstrained scroll, and non-trapping pmpTabEnter keyframes', async () => {
  const modalSrc = fs.readFileSync(path.join(SRC_DIR, 'components/ui/Modal.tsx'), 'utf8');
  const searchSrc = fs.readFileSync(path.join(SRC_DIR, 'components/search/GlobalSearchModal.tsx'), 'utf8');
  const chromeSrc = fs.readFileSync(path.join(SRC_DIR, 'components/layout/DesktopWindowChrome.tsx'), 'utf8');
  const portalSrc = fs.readFileSync(path.join(SRC_DIR, 'pages/PortalPage.tsx'), 'utf8');
  const cssSrc = fs.readFileSync(path.join(SRC_DIR, 'styles/designSystem.css'), 'utf8');

  assert.equal(modalSrc.includes('createPortal('), true);
  assert.equal(modalSrc.includes('renderInBodyPortal('), true);
  assert.equal(modalSrc.includes("overflowY: 'auto'"), true);
  assert.equal(modalSrc.includes("overscrollBehavior: 'contain'"), true);
  assert.equal(modalSrc.includes('pmpModalEnter'), true);
  assert.equal(searchSrc.includes('renderInBodyPortal('), true);
  assert.equal(chromeSrc.includes('renderInBodyPortal('), true);
  assert.equal(portalSrc.includes('renderInBodyPortal('), true);

  const tabKeyframeMatch = cssSrc.match(/@keyframes pmpTabEnter\s*\{[\s\S]*?\n\}/);
  assert.ok(tabKeyframeMatch, 'Expected @keyframes pmpTabEnter block');
  assert.equal(tabKeyframeMatch[0].includes('transform: none'), true);
  assert.equal(cssSrc.includes('@keyframes pmpModalEnter'), true);

  const helperSliceMatch = modalSrc.match(
    /export const MODAL_MAX_WIDTH_MAP[\s\S]*?export function resolveModalMaxWidth[\s\S]*?\n\}/
  );
  assert.ok(helperSliceMatch, 'Expected resolveModalMaxWidth helper in Modal.tsx');
  const transpiledHelper = ts.transpileModule(helperSliceMatch[0], {
    compilerOptions: { module: ts.ModuleKind.ESNext, target: ts.ScriptTarget.ES2022 }
  });
  const helperPath = path.join(tmpOutDir, 'modal-width-helper.mjs');
  fs.writeFileSync(helperPath, transpiledHelper.outputText, 'utf8');
  const helperMod = await import(pathToFileURL(helperPath).href);

  assert.equal(helperMod.resolveModalMaxWidth('max-w-md'), '28rem');
  assert.equal(helperMod.resolveModalMaxWidth('max-w-4xl'), '56rem');
  assert.equal(helperMod.resolveModalMaxWidth('640px'), '640px');
  assert.equal(helperMod.resolveModalMaxWidth(undefined), '540px');
});
