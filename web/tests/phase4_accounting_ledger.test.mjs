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

const tmpOutDir = fs.mkdtempSync(path.join(os.tmpdir(), 'pmp-erp-phase4-tests-'));

function transpileAndLoad(filename, rewriteImports = {}) {
  const tsPath = path.join(SRC_SERVICES_DIR, filename);
  let source = fs.readFileSync(tsPath, 'utf8');
  for (const [from, to] of Object.entries(rewriteImports)) {
    source = source.replaceAll(from, to);
  }
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

const ErpCalculations = await transpileAndLoad('ErpCalculations.ts');
const AccountingEngine = await transpileAndLoad('AccountingEngine.ts', {
  "'./ErpCalculations'": "'./ErpCalculations.mjs'"
});

test.after(() => {
  fs.rmSync(tmpOutDir, { recursive: true, force: true });
});

test('Phase 4: unbalanced manual journal entry is rejected & balanced append-only reversal zeroes net balance', () => {
  assert.throws(() => {
    AccountingEngine.createBalancedJournalPayload({
      description: 'قيد مختل',
      currency: 'SDG',
      lines: [
        { accountCode: '1110', debit: 1500 },
        { accountCode: '4100', credit: 1400 }
      ]
    });
  });

  const { entry, lines } = AccountingEngine.createBalancedJournalPayload({
    description: 'قيد افتتاحي نقدية السودان',
    currency: 'SDG',
    lines: [
      { accountCode: '1110', debit: 50000 },
      { accountCode: '3100', credit: 50000 }
    ]
  });

  assert.equal(entry.status, 'POSTED');
  assert.equal(entry.totalDebit, 50000);
  assert.equal(lines.length, 2);

  const { updatedOriginal, reversalEntry, reversalLines } = AccountingEngine.buildReversalJournalPayloads(
    entry,
    lines,
    'خطأ في المبلغ الافتتاحي',
    'المدير'
  );
  assert.equal(updatedOriginal.status, 'REVERSED');
  assert.equal(reversalEntry.status, 'POSTED');

  assert.throws(() => {
    AccountingEngine.buildReversalJournalPayloads(updatedOriginal, lines, 'محاولة ثانية', 'المدير');
  });

  const tb = AccountingEngine.computeTrialBalance(
    AccountingEngine.DEFAULT_CHART_OF_ACCOUNTS,
    [updatedOriginal, reversalEntry],
    [...lines, ...reversalLines],
    'SDG'
  );
  assert.equal(tb.isBalanced, true);
  assert.equal(tb.rows.find((r) => r.accountCode === '1110').signedBalance, 0);
});

test('Phase 4: automated GRN, Production Issue, QC FG, Sales, Collections & FX transfers maintain balanced multi-currency statements', () => {
  const entries = [];
  const lines = [];
  const pushPayload = (p) => {
    if (!p) return;
    entries.push(p.entry);
    lines.push(...p.lines);
  };

  pushPayload(
    AccountingEngine.buildPurchaseReceiptJournalPayload({
      grnSyncUuid: 'GRN-1',
      grnNo: 'GRN-1001',
      receiptDate: '2026-11-15',
      supplierName: 'مورد خامات',
      materialName: 'تيتانيوم',
      unit: 'كجم',
      acceptedQty: 100,
      unitCost: 100,
      currency: 'EGP',
      performedBy: 'أمين الخام'
    })
  );

  pushPayload(
    AccountingEngine.buildProductionIssueJournalPayload({
      orderSyncUuid: 'ORD-1',
      orderNo: 'PRD-1001',
      orderDate: '2026-11-15',
      productName: 'بوهية أبيض',
      netIssuedCost: 4500,
      wasteCost: 500,
      actor: 'مهندس الإنتاج',
      currency: 'EGP'
    })
  );

  pushPayload(
    AccountingEngine.buildQcFinishedGoodsJournalPayload({
      qcSyncUuid: 'QC-1',
      qcNo: 'QC-1001',
      orderNo: 'PRD-1001',
      productName: 'بوهية أبيض',
      producedFullCartons: 5,
      fgTotalCost: 4500,
      inspectorName: 'مسؤول الجودة',
      currency: 'EGP'
    })
  );

  const salesPayloads = AccountingEngine.buildSalesInvoiceJournalPayloads({
    invoiceId: 77,
    invoiceNo: 'INV-77',
    invoiceDate: '2026-11-15',
    customerName: 'عميل الخرطوم',
    currency: 'SDG',
    totalRevenue: 150000,
    totalCogs: 90000,
    paidAmount: 100000,
    cashbox: 'خزنة السودان',
    actor: 'مبيعات'
  });
  salesPayloads.forEach(pushPayload);

  const fxPayloads = AccountingEngine.buildTreasuryTransferJournalPayloads({
    id: 88,
    txDate: '2026-11-15',
    reference: 'TRF-FX-88',
    sourceCashbox: 'خزنة مصر',
    destinationCashbox: 'خزنة السودان',
    sourceCurrency: 'EGP',
    destinationCurrency: 'SDG',
    sourceAmount: 1000,
    destinationAmount: 12500,
    createdBy: 'المدير'
  });
  fxPayloads.forEach(pushPayload);

  // EGP & SDG Trial Balances & Balance Sheets stay strictly isolated & balanced
  const egpTb = AccountingEngine.computeTrialBalance(AccountingEngine.DEFAULT_CHART_OF_ACCOUNTS, entries, lines, 'EGP');
  const egpPnl = AccountingEngine.computeIncomeStatement(egpTb);
  const egpBs = AccountingEngine.computeBalanceSheet(egpTb, egpPnl.netIncome);
  assert.equal(egpTb.isBalanced, true);
  assert.equal(egpBs.isBalanced, true);
  assert.equal(egpPnl.totalOperatingExpenses, 500);

  const sdgTb = AccountingEngine.computeTrialBalance(AccountingEngine.DEFAULT_CHART_OF_ACCOUNTS, entries, lines, 'SDG');
  const sdgPnl = AccountingEngine.computeIncomeStatement(sdgTb);
  const sdgBs = AccountingEngine.computeBalanceSheet(sdgTb, sdgPnl.netIncome);
  assert.equal(sdgTb.isBalanced, true);
  assert.equal(sdgBs.isBalanced, true);
  assert.equal(sdgPnl.totalRevenue, 150000);
  assert.equal(sdgPnl.totalCogs, 90000);
  assert.equal(sdgPnl.grossProfit, 60000);
  assert.equal(sdgTb.rows.find((r) => r.accountCode === '1130').signedBalance, 50000);

  const consolidated = AccountingEngine.computeConsolidatedFxSummary(
    AccountingEngine.DEFAULT_CHART_OF_ACCOUNTS,
    entries,
    lines,
    { egpToSdg: 12.5, usdToSdg: 600 }
  );
  assert.equal(consolidated.totalRevenueConsolidated, 150000);
  assert.equal(consolidated.totalExpensesConsolidated, 90000 + 500 * 12.5);
});
