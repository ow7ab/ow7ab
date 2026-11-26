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
const ROOT_DIR = path.resolve(WEB_DIR, '..');

const tmpOutDir = fs.mkdtempSync(path.join(os.tmpdir(), 'pmp-recon-sync-tests-'));

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

transpileRelative('services/ErpCalculations.ts', 'services/ErpCalculations.mjs');
transpileRelative('services/AccountingEngine.ts', 'services/AccountingEngine.mjs', (s) =>
  s.replace("'./ErpCalculations'", "'./ErpCalculations.mjs'")
);
const reconPath = transpileRelative(
  'services/ReconciliationAndSyncService.ts',
  'services/ReconciliationAndSyncService.mjs',
  (s) =>
    s
      .replace("'./ErpCalculations'", "'./ErpCalculations.mjs'")
      .replace("'./AccountingEngine'", "'./AccountingEngine.mjs'")
);

const ErpCalculations = await import(pathToFileURL(path.join(tmpOutDir, 'services/ErpCalculations.mjs')).href);
const AccountingEngine = await import(pathToFileURL(path.join(tmpOutDir, 'services/AccountingEngine.mjs')).href);
const ReconService = await import(pathToFileURL(reconPath).href);

// Extract & transpile real production redactHealthTelemetry without React/DOM imports
const healthSrc = fs.readFileSync(path.join(SRC_DIR, 'components/admin/PmpSystemHealthCenter.tsx'), 'utf8');
const redactFnMatch = healthSrc.match(/export function redactHealthTelemetry[\s\S]*?\n\}/);
assert.ok(redactFnMatch, 'Expected exported redactHealthTelemetry in PmpSystemHealthCenter.tsx');
const redactTranspiled = ts.transpileModule(redactFnMatch[0], {
  compilerOptions: { module: ts.ModuleKind.ESNext, target: ts.ScriptTarget.ES2022 }
});
const redactModPath = path.join(tmpOutDir, 'redact.mjs');
fs.writeFileSync(redactModPath, redactTranspiled.outputText, 'utf8');
const { redactHealthTelemetry } = await import(pathToFileURL(redactModPath).href);

test.after(() => {
  fs.rmSync(tmpOutDir, { recursive: true, force: true });
});

test('1. resolveUnifiedSyncState enforces all 6 explicit sync states and never marks pending/cache/offline as Synced', () => {
  const { resolveUnifiedSyncState, getSyncStatusArabicLabel } = ReconService;

  assert.equal(
    resolveUnifiedSyncState({
      online: true,
      hasPendingWrites: false,
      fromCache: false,
      syncingCount: 0,
      failedCount: 0,
      conflictCount: 0,
      lastServerAckAt: Date.now()
    }),
    'Synced'
  );

  // Must NEVER return Synced if pending writes exist or snapshot is fromCache or no server ack
  assert.equal(
    resolveUnifiedSyncState({
      online: true,
      hasPendingWrites: true,
      fromCache: false,
      lastServerAckAt: Date.now()
    }),
    'Pending'
  );
  assert.equal(
    resolveUnifiedSyncState({
      online: true,
      hasPendingWrites: false,
      fromCache: true,
      lastServerAckAt: Date.now()
    }),
    'Syncing'
  );
  assert.equal(
    resolveUnifiedSyncState({
      online: true,
      hasPendingWrites: false,
      fromCache: false,
      lastServerAckAt: 0
    }),
    'Syncing'
  );
  assert.equal(
    resolveUnifiedSyncState({
      online: false,
      hasPendingWrites: false,
      fromCache: true,
      lastServerAckAt: Date.now()
    }),
    'Offline'
  );
  assert.equal(
    resolveUnifiedSyncState({
      online: true,
      hasPendingWrites: false,
      fromCache: false,
      failedCount: 2,
      lastServerAckAt: Date.now()
    }),
    'Failed'
  );
  assert.equal(
    resolveUnifiedSyncState({
      online: true,
      hasPendingWrites: false,
      fromCache: false,
      conflictCount: 1,
      failedCount: 1,
      lastServerAckAt: Date.now()
    }),
    'Conflict Detected'
  );

  for (const state of ['Synced', 'Syncing', 'Pending', 'Failed', 'Offline', 'Conflict Detected']) {
    assert.ok(getSyncStatusArabicLabel(state).length > 3, `Expected Arabic label for ${state}`);
  }
});

test('2. computeCrossPlatformBalanceReconciliation matches aligned ledger/checkpoints and pinpoints platform, cache, and GL divergences', () => {
  const { computeCrossPlatformBalanceReconciliation, computePlatformCheckpointPayload } = ReconService;
  const { createBalancedJournalPayload } = AccountingEngine;

  const centralCashboxes = [{ id: 1, name: 'خزنة السودان', currency: 'SDG', balance: 25000, active: true }];
  const centralMovements = [
    {
      id: 101,
      cashbox: 'خزنة السودان',
      currency: 'SDG',
      direction: 'وارد',
      amount: 30000,
      reference: 'REC-30000',
      status: 'active',
      createdAt: 1700000010000
    },
    {
      id: 102,
      cashbox: 'خزنة السودان',
      currency: 'SDG',
      direction: 'منصرف',
      amount: 5000,
      reference: 'EXP-5000',
      status: 'active',
      createdAt: 1700000020000
    },
    {
      id: 103,
      cashbox: 'خزنة السودان',
      currency: 'SDG',
      direction: 'منصرف',
      amount: 99000,
      reference: 'EXP-REV-99',
      status: 'reversed',
      createdAt: 1700000030000
    }
  ];

  const { entry, lines } = createBalancedJournalPayload({
    description: 'صافي حركة خزنة السودان',
    currency: 'SDG',
    lines: [
      { accountCode: '1110', debit: 25000 },
      { accountCode: '3100', credit: 25000 }
    ]
  });

  const webCp = computePlatformCheckpointPayload({
    platform: 'WEB',
    cashboxes: centralCashboxes,
    movements: centralMovements,
    invoices: [],
    payments: []
  });
  const desktopCp = { ...webCp, platform: 'DESKTOP' };
  const androidCpAligned = { ...webCp, platform: 'ANDROID' };

  const matched = computeCrossPlatformBalanceReconciliation({
    centralCashboxes,
    centralMovements,
    centralInvoices: [],
    centralPayments: [],
    localCashboxes: centralCashboxes,
    localMovements: centralMovements,
    journalEntries: [entry],
    journalLines: lines,
    checkpoints: { web: webCp, desktop: desktopCp, android: androidCpAligned },
    nowMillis: 1700000050000
  });

  assert.equal(matched.overallStatus, 'MATCHED');
  assert.equal(matched.anomalies.length, 0);
  assert.equal(matched.centralCurrencyTotals.SDG, 25000);
  assert.equal(matched.glCashTotals.SDG, 25000);

  // Now introduce 3 real anomalies: Android stale checkpoint, stale cached cashbox field, and local missing movement with pending writes
  const staleCashboxes = [{ id: 1, name: 'خزنة السودان', currency: 'SDG', balance: 99999, active: true }];
  const laggedAndroidCp = {
    ...androidCpAligned,
    currencyTotals: { SDG: 30000, EGP: 0, USD: 0 },
    lastSyncedTransactionRef: 'REC-30000'
  };

  const diverged = computeCrossPlatformBalanceReconciliation({
    centralCashboxes: staleCashboxes,
    centralMovements,
    centralInvoices: [],
    centralPayments: [],
    localCashboxes: centralCashboxes,
    localMovements: [centralMovements[0]], // local missing EXP-5000
    journalEntries: [entry],
    journalLines: lines,
    checkpoints: { web: webCp, desktop: desktopCp, android: laggedAndroidCp },
    pendingWritesCount: 1,
    nowMillis: 1700000060000
  });

  assert.equal(diverged.overallStatus, 'VARIANCE_DETECTED');
  assert.ok(diverged.anomalies.some((a) => a.rootCauseSource === 'PENDING_OFFLINE_WRITES' && a.safeRemediationAction === 'FLUSH_PENDING_OUTBOX'));
  assert.ok(diverged.anomalies.some((a) => a.rootCauseSource === 'STALE_CACHED_CASHBOX_BALANCE'));
  const androidAnomaly = diverged.anomalies.find((a) => a.id === 'CHECKPOINT_ANDROID_SDG');
  assert.ok(androidAnomaly, 'Expected CHECKPOINT_ANDROID_SDG anomaly');
  assert.equal(androidAnomaly.varianceAmount, -5000);
  assert.equal(androidAnomaly.rootCauseSource, 'UNSYNCED_CLIENT_CHECKPOINT');
  assert.equal(androidAnomaly.safeRemediationAction, 'REFRESH_CLIENT_SNAPSHOT_FROM_SERVER');
});

test('3. Immutable reversal status filtering excludes reversed payments/movements in ErpCalculations', () => {
  const { isCancelledOrReversedStatus, computeInvoiceFinancials, computeDistinctCashboxesWithBalances } = ErpCalculations;

  for (const st of ['cancelled', 'reversed', 'void', 'ملغاة', 'ملغي', 'REVERSED']) {
    assert.equal(isCancelledOrReversedStatus(st), true);
  }
  assert.equal(isCancelledOrReversedStatus('active'), false);
  assert.equal(isCancelledOrReversedStatus(''), false);

  // Invoice had stale cached paidAmount = 1000, but its payment was reversed
  const inv = { id: 55, total: 1000, paidAmount: 1000, status: 'active' };
  const payments = [{ id: 1, invoiceId: 55, amount: 1000, status: 'reversed', payDate: '2026-11-20', cashbox: 'خزنة السودان' }];
  const fin = computeInvoiceFinancials(inv, payments);
  assert.equal(fin.paidAmount, 0);
  assert.equal(fin.remainingAmount, 1000);
  assert.equal(fin.statusLabel, 'غير مدفوعة');

  const boxes = computeDistinctCashboxesWithBalances(
    [{ id: 1, name: 'خزنة السودان', currency: 'SDG', active: true }],
    [
      { id: 1, cashbox: 'خزنة السودان', currency: 'SDG', direction: 'وارد', amount: 12000, status: 'active' },
      { id: 2, cashbox: 'خزنة السودان', currency: 'SDG', direction: 'وارد', amount: 8000, status: 'reversed' }
    ]
  );
  assert.equal(boxes[0].balances.SDG, 12000);
});

test('4. Telemetry redaction strips secrets/JWTs/API keys and Admin-only + Immutable Ledger contracts hold across modules', () => {
  const dirty =
    'Auth fail Bearer eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjMifQ.sig123 key=AIzaSyD1234567890abcdefghijk pinOrPassword=1234 refreshToken=abc_secret_tok';
  const clean = redactHealthTelemetry(dirty);
  assert.ok(!clean.includes('eyJhbGciOiJIUzI1NiJ9'));
  assert.ok(!clean.includes('AIzaSyD1234567890'));
  assert.ok(!clean.includes('1234'));
  assert.ok(!clean.includes('abc_secret_tok'));
  assert.ok(clean.includes('Bearer [REDACTED]'));
  assert.ok(clean.includes('[API_KEY_REDACTED]'));

  const healthComponent = fs.readFileSync(path.join(SRC_DIR, 'components/admin/PmpSystemHealthCenter.tsx'), 'utf8');
  assert.ok(healthComponent.includes('if (!isSuperAdmin)'));
  assert.ok(healthComponent.includes('return null;'));
  assert.ok(healthComponent.includes('PMP SYSTEM HEALTH CENTER'));

  const settingsSrc = fs.readFileSync(path.join(SRC_DIR, 'components/modules/SettingsModuleView.tsx'), 'utf8');
  assert.ok(settingsSrc.includes('isSuperAdmin && <PmpSystemHealthCenter />'));
  assert.ok(settingsSrc.includes('{isSuperAdmin && ('));
  assert.ok(settingsSrc.indexOf('{isSuperAdmin && (') < settingsSrc.indexOf('onClick={handleRunHealthCheck}'));

  const erpDataSrc = fs.readFileSync(path.join(SRC_DIR, 'services/ErpDataService.ts'), 'utf8');
  assert.ok(erpDataSrc.includes('includeMetadataChanges: true'));
  assert.ok(erpDataSrc.includes('reverseTreasuryMovementAtomic'));
  assert.ok(erpDataSrc.includes('cancelInvoiceAndReverseAtomic'));
  assert.ok(erpDataSrc.includes('runTransaction'));
  assert.ok(erpDataSrc.includes('publishPlatformBalanceCheckpoint'));

  const fbConfigSrc = fs.readFileSync(path.join(SRC_DIR, 'config/firebaseConfig.ts'), 'utf8');
  assert.ok(fbConfigSrc.includes('persistentLocalCache'));

  const authCtxSrc = fs.readFileSync(path.join(SRC_DIR, 'context/AuthContext.tsx'), 'utf8');
  const acctSvcSrc = fs.readFileSync(path.join(SRC_DIR, 'services/AccountService.ts'), 'utf8');
  assert.ok(!authCtxSrc.includes('signInAnonymously('));
  assert.ok(!acctSvcSrc.includes('signInAnonymously('));

  const rulesSrc = fs.readFileSync(path.join(ROOT_DIR, 'firestore.rules'), 'utf8');
  assert.ok(rulesSrc.includes('function isNonAnonymousAuth()'));
  assert.ok(rulesSrc.includes('function isImmutableFinancialLedgerCollection(col)'));
  assert.ok(rulesSrc.includes('function isValidFinancialLedgerReversal()'));

  assert.ok(erpDataSrc.includes('hasActiveClusterMembership'));
  assert.ok(erpDataSrc.includes('flushPendingAuditLogs'));
  assert.ok(erpDataSrc.includes('enqueuePreAuthAudit'));
});

test('5. Account 3900 reconciliation adjustment is defined in DEFAULT_CHART_OF_ACCOUNTS and produces balanced GL statements', () => {
  const { DEFAULT_CHART_OF_ACCOUNTS, createBalancedJournalPayload, computeTrialBalance, computeIncomeStatement, computeBalanceSheet } =
    AccountingEngine;

  const acc3900 = DEFAULT_CHART_OF_ACCOUNTS.find((a) => a.accountCode === '3900');
  assert.ok(acc3900, 'Expected system adjustment account 3900 in DEFAULT_CHART_OF_ACCOUNTS');
  assert.equal(acc3900.accountType, 'EQUITY');

  const adj = createBalancedJournalPayload({
    sourceType: 'RECONCILIATION_ADJUSTMENT',
    description: 'قيد تسوية مطابقة خزنة السودان',
    currency: 'SDG',
    lines: [
      { accountCode: '1110', debit: 4500 },
      { accountCode: '3900', credit: 4500 }
    ]
  });
  assert.equal(adj.entry.totalDebit, 4500);
  assert.equal(adj.entry.totalCredit, 4500);

  const tb = computeTrialBalance(DEFAULT_CHART_OF_ACCOUNTS, [adj.entry], adj.lines, 'SDG');
  const pnl = computeIncomeStatement(tb);
  const bs = computeBalanceSheet(tb, pnl.netIncome);
  assert.equal(tb.isBalanced, true);
  assert.equal(bs.isBalanced, true);
  assert.equal(bs.totalAssets, 4500);
  assert.equal(bs.totalLiabilitiesAndEquity, 4500);
});
