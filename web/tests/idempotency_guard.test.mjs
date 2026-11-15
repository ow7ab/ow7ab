import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import os from 'node:os';
import { pathToFileURL, fileURLToPath } from 'node:url';
import ts from 'typescript';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);
const WEB_SRC = path.resolve(__dirname, '../src');
const ROOT_DIR = path.resolve(__dirname, '../..');

const tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), 'pmp-erp-idem-tests-'));

function transpileFile(relSrcPath, outFilename, rewriteImports = {}) {
  const fullPath = path.join(WEB_SRC, relSrcPath);
  let source = fs.readFileSync(fullPath, 'utf8');
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
  const outPath = path.join(tmpDir, outFilename);
  fs.writeFileSync(outPath, transpiled.outputText, 'utf8');
  return pathToFileURL(outPath).href;
}

// Stub firebase/firestore + firebaseConfig so IdempotencyService loads cleanly in pure Node
fs.writeFileSync(
  path.join(tmpDir, 'firestoreStub.mjs'),
  `
    export const doc = (...args) => ({ path: args.join('/') });
    export const getDoc = async () => ({ exists: () => false, data: () => ({}) });
    export const setDoc = async () => {};
    export const runTransaction = async (_db, fn) => fn({ get: async () => ({ exists: () => false }), set: () => {} });
    export const serverTimestamp = () => ({ __serverTimestamp: true });
  `,
  'utf8'
);

fs.writeFileSync(
  path.join(tmpDir, 'firebaseConfigStub.mjs'),
  `
    export const firestore = {};
    export const DEFAULT_CLUSTER_CODE = 'PMP-ERP-MAIN-CLUSTER';
  `,
  'utf8'
);

const versionContractHref = transpileFile('config/appVersionContract.ts', 'appVersionContract.mjs');
const idempotencyHref = transpileFile('services/IdempotencyService.ts', 'IdempotencyService.mjs', {
  "'firebase/firestore'": "'./firestoreStub.mjs'",
  "'../config/firebaseConfig'": "'./firebaseConfigStub.mjs'"
});
const releaseServiceHref = transpileFile('services/ReleaseService.ts', 'ReleaseService.mjs', {
  "'../config/appVersionContract'": "'./appVersionContract.mjs'"
});

const {
  TransactionSubmissionGuardEngine,
  OPERATION_LOADING_LABELS,
  IDEMPOTENCY_STATUS_MESSAGES
} = await import(idempotencyHref);
const { APP_VERSION_CONTRACT } = await import(versionContractHref);
const { parseGitHubDesktopRelease } = await import(releaseServiceHref);

test.after(() => {
  fs.rmSync(tmpDir, { recursive: true, force: true });
});

function createPersistentStorage(backingMap = new Map()) {
  return {
    getItem: (k) => (backingMap.has(k) ? backingMap.get(k) : null),
    setItem: (k, v) => {
      backingMap.set(k, String(v));
    },
    removeItem: (k) => {
      backingMap.delete(k);
    }
  };
}

function createMockServerBackend() {
  const records = new Map();
  const writtenDocs = new Map(); // collection/docId -> payload
  return {
    records,
    writtenDocs,
    simulateDisconnect: false,
    simulateSlowMs: 0,
    async claimOrGet(record, leaseMs) {
      if (this.simulateDisconnect) {
        const err = new Error('network failed to fetch');
        err.code = 'unavailable';
        throw err;
      }
      if (this.simulateSlowMs > 0) {
        await new Promise((r) => setTimeout(r, this.simulateSlowMs));
      }
      const existing = records.get(record.idempotencyKey);
      const now = Date.now();
      if (existing) {
        if (existing.requestStatus === 'COMPLETED') {
          return { outcome: 'ALREADY_COMPLETED', existing };
        }
        if (
          (existing.requestStatus === 'PROCESSING' || existing.requestStatus === 'PENDING_CONFIRMATION') &&
          now - Number(existing.updatedAt || existing.createdAt || 0) < leaseMs
        ) {
          return { outcome: 'IN_PROGRESS_REMOTE', existing };
        }
      }
      records.set(record.idempotencyKey, { ...record, requestStatus: 'PROCESSING', updatedAt: now });
      return { outcome: 'CLAIMED' };
    },
    async markCompleted(idempotencyKey, resultReference, completionTime) {
      const prev = records.get(idempotencyKey) || {};
      if (prev.requestStatus === 'COMPLETED') return;
      records.set(idempotencyKey, {
        ...prev,
        idempotencyKey,
        requestStatus: 'COMPLETED',
        resultReference,
        completionTime,
        updatedAt: completionTime,
        errorDetails: null
      });
    },
    async markFailed(idempotencyKey, errorDetails) {
      const prev = records.get(idempotencyKey);
      if (!prev || prev.requestStatus === 'COMPLETED') return;
      records.set(idempotencyKey, {
        ...prev,
        requestStatus: 'FAILED',
        errorDetails,
        updatedAt: Date.now()
      });
    },
    async markPendingConfirmation(idempotencyKey, errorDetails) {
      const prev = records.get(idempotencyKey);
      if (!prev || prev.requestStatus === 'COMPLETED') return;
      records.set(idempotencyKey, {
        ...prev,
        requestStatus: 'PENDING_CONFIRMATION',
        errorDetails,
        updatedAt: Date.now()
      });
    },
    async getServerRecord(idempotencyKey) {
      if (this.simulateDisconnect) {
        const err = new Error('offline');
        err.code = 'unavailable';
        throw err;
      }
      return records.get(idempotencyKey) || null;
    },
    async checkTargetDocumentExists(collectionName, docId) {
      if (this.simulateDisconnect) return false;
      return writtenDocs.has(`${collectionName}/${docId}`);
    }
  };
}

test('Scenario 1 & 2: exact Arabic operation loading labels & status messages contract', () => {
  assert.equal(OPERATION_LOADING_LABELS.SAVE, 'جاري الحفظ...');
  assert.equal(OPERATION_LOADING_LABELS.CREATE, 'جاري إضافة العملية...');
  assert.equal(OPERATION_LOADING_LABELS.RAW_MATERIAL_SUPPLY, 'جاري توريد الخامات...');
  assert.equal(OPERATION_LOADING_LABELS.GOODS_ISSUE, 'جاري تنفيذ الصرف...');
  assert.equal(OPERATION_LOADING_LABELS.STOCK_TRANSFER, 'جاري التحويل...');
  assert.equal(OPERATION_LOADING_LABELS.APPROVE_INVOICE, 'جاري اعتماد الفاتورة...');
  assert.equal(OPERATION_LOADING_LABELS.PAYMENT, 'جاري تنفيذ السداد...');
  assert.equal(OPERATION_LOADING_LABELS.RECEIPT_VOUCHER, 'جاري تسجيل سند القبض...');
  assert.equal(OPERATION_LOADING_LABELS.PAYMENT_VOUCHER, 'جاري تسجيل سند الصرف...');
  assert.equal(OPERATION_LOADING_LABELS.UPDATE, 'جاري تحديث البيانات...');
  assert.equal(OPERATION_LOADING_LABELS.DELETE, 'جاري تنفيذ الحذف...');

  assert.equal(IDEMPOTENCY_STATUS_MESSAGES.PROCESSING_WAIT, 'جاري تنفيذ العملية، يرجى الانتظار...');
  assert.equal(IDEMPOTENCY_STATUS_MESSAGES.SLOW_CHECKING, 'الاتصال بطيء، يتم التحقق من حالة العملية...');
  assert.equal(IDEMPOTENCY_STATUS_MESSAGES.COMPLETED_SUCCESS, 'تم تنفيذ العملية بنجاح.');
  assert.equal(IDEMPOTENCY_STATUS_MESSAGES.FAILED_REVIEW, 'تعذر تنفيذ العملية، يرجى مراجعة التفاصيل.');
  assert.equal(
    IDEMPOTENCY_STATUS_MESSAGES.UNCERTAIN_DO_NOT_RESUBMIT,
    'لم يتم التأكد من نتيجة العملية بعد، لا تعِد إدخالها.'
  );
  assert.equal(IDEMPOTENCY_STATUS_MESSAGES.ALREADY_RECORDED, 'العملية مسجلة مسبقاً.');
});

test('Scenario 1-8: Single fast click + 5x rapid multi-tap across ALL ERP operation types causes zero duplicates', async () => {
  const storage = createPersistentStorage();
  const backend = createMockServerBackend();
  const guard = new TransactionSubmissionGuardEngine(storage, backend);

  const ledger = {
    invoices: [],
    stockDelta: 0,
    cashDelta: 0,
    journalEntries: []
  };

  // Scenario 1: Single normal click
  const singleRes = await guard.execute({
    guardKey: 'single_click_test',
    operationType: 'SAVE',
    userId: 'manager1',
    documentReference: 'PINV-001',
    targetCollection: 'purchase_invoices',
    payloadSummary: 'فاتورة شراء عادية',
    execute: async ({ idempotencyKey }) => {
      await new Promise((r) => setTimeout(r, 15));
      ledger.invoices.push(`PINV-${idempotencyKey}`);
      return { resultReference: `PINV-${idempotencyKey}` };
    }
  });
  assert.equal(singleRes.ok, true);
  assert.equal(singleRes.replayed, false);
  assert.equal(singleRes.status, 'COMPLETED');
  assert.equal(ledger.invoices.length, 1);

  // Scenarios 2-8: 5 rapid simultaneous clicks for Purchase Invoice, Raw Material Supply,
  // Goods Issue, Stock Transfer, Sales Invoice, Receipt Voucher, Payment Voucher
  const operations = [
    { key: 'rapid_pinv', type: 'SAVE', ref: 'PINV-RAPID-1', col: 'purchase_invoices', effect: () => ledger.invoices.push('PINV-R') },
    { key: 'rapid_grn', type: 'RAW_MATERIAL_SUPPLY', ref: 'GRN-RAPID-1', col: 'grns', effect: () => (ledger.stockDelta += 100) },
    { key: 'rapid_issue', type: 'GOODS_ISSUE', ref: 'ISS-RAPID-1', col: 'issues', effect: () => (ledger.stockDelta -= 40) },
    { key: 'rapid_transfer', type: 'STOCK_TRANSFER', ref: 'TRF-RAPID-1', col: 'stock_transfers', effect: () => ledger.journalEntries.push('JRN-TRF') },
    { key: 'rapid_sinv', type: 'APPROVE_INVOICE', ref: 'SINV-RAPID-1', col: 'invoices', effect: () => ledger.invoices.push('SINV-R') },
    { key: 'rapid_receipt', type: 'RECEIPT_VOUCHER', ref: 'REC-RAPID-1', col: 'movements', effect: () => (ledger.cashDelta += 5000) },
    { key: 'rapid_payment', type: 'PAYMENT_VOUCHER', ref: 'PAY-RAPID-1', col: 'movements', effect: () => (ledger.cashDelta -= 1500) }
  ];

  for (const op of operations) {
    let calls = 0;
    const results = await Promise.all(
      Array.from({ length: 5 }, () =>
        guard.execute({
          guardKey: op.key,
          operationType: op.type,
          userId: 'cashier1',
          documentReference: op.ref,
          targetCollection: op.col,
          payloadSummary: op.ref,
          execute: async () => {
            calls += 1;
            await new Promise((r) => setTimeout(r, 35));
            op.effect();
            return { resultReference: op.ref };
          }
        })
      )
    );
    assert.equal(calls, 1, `${op.key} must execute exactly once under 5 rapid clicks`);
    assert.equal(results.filter((r) => r.ok && !r.replayed).length, 1);
    assert.equal(results.filter((r) => r.blocked).length, 4);
  }

  // Strict invariants check (Scenarios 15-17): zero duplicate invoices, stock, cash, or journals
  assert.equal(ledger.invoices.length, 3); // 1 single + 1 purchase + 1 sales
  assert.equal(ledger.stockDelta, 60); // +100 GRN - 40 Issue
  assert.equal(ledger.cashDelta, 3500); // +5000 receipt - 1500 payment
  assert.equal(ledger.journalEntries.length, 1);
});

test('Scenario 9 & 10 & 11: Slow internet -> slow warning; Disconnect mid-flight -> PENDING_CONFIRMATION -> Reconnect recovers safely', async () => {
  const backing = new Map();
  const storage = createPersistentStorage(backing);
  const backend = createMockServerBackend();
  backend.simulateSlowMs = 80;
  const guard = new TransactionSubmissionGuardEngine(storage, backend);

  const observedStatusMessages = [];
  const slowRes = await guard.execute({
    guardKey: 'slow_op',
    operationType: 'RECEIPT_VOUCHER',
    userId: 'accountant',
    documentReference: 'REC-SLOW-1',
    targetCollection: 'movements',
    payloadSummary: 'سند قبض بطيء',
    slowThresholdMs: 25,
    timeoutMs: 2000,
    onStatusMessage: (msg) => observedStatusMessages.push(msg),
    execute: async () => {
      await new Promise((r) => setTimeout(r, 40));
      return { resultReference: 'REC-SLOW-1' };
    }
  });
  assert.equal(slowRes.ok, true);
  assert.ok(observedStatusMessages.includes(IDEMPOTENCY_STATUS_MESSAGES.SLOW_CHECKING));

  // Disconnect mid-flight (execute finishes server write right before timeout/disconnect exception on client ack)
  backend.simulateSlowMs = 0;
  let disconnectExecCount = 0;
  const pendingRes = await guard.execute({
    guardKey: 'midflight_drop',
    operationType: 'GOODS_ISSUE',
    userId: 'storekeeper',
    documentReference: 'ISS-DROP-1',
    targetCollection: 'movements',
    payloadSummary: 'صرف مع انقطاع',
    execute: async ({ idempotencyKey }) => {
      disconnectExecCount += 1;
      // Simulate server received the document before client lost connection
      backend.writtenDocs.set('movements/ISS-DROP-1', { idempotencyKey });
      backend.records.set(idempotencyKey, {
        idempotencyKey,
        requestStatus: 'COMPLETED',
        resultReference: 'ISS-DROP-1',
        completionTime: Date.now()
      });
      const netErr = new Error('network connection lost during ack');
      netErr.code = 'unavailable';
      throw netErr;
    }
  });

  assert.equal(disconnectExecCount, 1);
  assert.equal(pendingRes.ok, false);
  assert.equal(pendingRes.uncertain, true);
  assert.equal(pendingRes.status, 'PENDING_CONFIRMATION');
  assert.equal(pendingRes.message, IDEMPOTENCY_STATUS_MESSAGES.UNCERTAIN_DO_NOT_RESUBMIT);

  // Attempting to re-submit before verification MUST be blocked
  const blockedRetry = await guard.execute({
    guardKey: 'midflight_drop',
    operationType: 'GOODS_ISSUE',
    userId: 'storekeeper',
    documentReference: 'ISS-DROP-1',
    targetCollection: 'movements',
    payloadSummary: 'إعادة إرسال غير مصرح بها',
    execute: async () => {
      disconnectExecCount += 1;
      return { resultReference: 'ISS-DROP-1' };
    }
  });
  assert.equal(blockedRetry.blocked, true);
  assert.equal(disconnectExecCount, 1);

  // Reconnect & recoverPendingOperations reconciles server truth WITHOUT creating duplicate
  const recovery = await guard.recoverPendingOperations();
  assert.equal(recovery.checked, 1);
  assert.equal(recovery.completedCount, 1);
  assert.equal(recovery.pendingCount, 0);
  assert.equal(guard.getRecordByKey(pendingRes.idempotencyKey).requestStatus, 'COMPLETED');
});

test('Scenario 12: Page close/crash during PROCESSING recovers on restart without duplicating', async () => {
  const backing = new Map();
  const storage1 = createPersistentStorage(backing);
  const backend = createMockServerBackend();

  const orphanedKey = 'IDEM-CRASH-9999';
  const guard1 = new TransactionSubmissionGuardEngine(storage1, backend);
  // Seed an orphaned PROCESSING record (simulating tab closed before server ack returned)
  storage1.setItem(
    'pmp_erp_idempotency_queue_v1',
    JSON.stringify([
      {
        idempotencyKey: orphanedKey,
        userId: 'manager1',
        operationType: 'SAVE',
        documentReference: 'PINV-ORPHAN-9',
        requestStatus: 'PROCESSING',
        createdAt: Date.now() - 5000,
        updatedAt: Date.now() - 5000,
        completionTime: null,
        resultReference: null,
        errorDetails: null,
        targetCollection: 'purchase_invoices',
        payloadSummary: 'فاتورة شراء أثناء إغلاق الصفحة',
        platform: 'WEB',
        guardKey: 'orphan_pinv'
      }
    ])
  );
  // Ensure fresh reload on new instance checks server: when server has NO record, transitions to FAILED (safe retry)
  const guardRestarted = new TransactionSubmissionGuardEngine(createPersistentStorage(backing), backend);
  const recovery1 = await guardRestarted.recoverPendingOperations();
  assert.equal(recovery1.checked, 1);
  assert.equal(recovery1.completedCount, 0);
  const afterCheck = guardRestarted.getRecordByKey(orphanedKey);
  assert.equal(afterCheck.requestStatus, 'FAILED');
});

test('Scenario 13 & 14: Two tabs / Two devices sharing same documentReference or idempotencyKey cannot duplicate', async () => {
  const backend = createMockServerBackend();
  const tabA = new TransactionSubmissionGuardEngine(createPersistentStorage(), backend);
  const tabB = new TransactionSubmissionGuardEngine(createPersistentStorage(), backend);

  const sharedKey = 'IDEM-SHARED-DOC-777';
  let serverWrites = 0;

  // Device/Tab A runs slow transaction holding lease
  const pA = tabA.execute({
    guardKey: 'tab_a_inv',
    idempotencyKey: sharedKey,
    operationType: 'APPROVE_INVOICE',
    userId: 'userA',
    documentReference: 'SINV-777',
    targetCollection: 'invoices',
    payloadSummary: 'اعتماد فاتورة 777',
    execute: async () => {
      await new Promise((r) => setTimeout(r, 50));
      serverWrites += 1;
      return { resultReference: 'SINV-777' };
    }
  });

  // Device/Tab B triggers simultaneously while A is in-flight
  await new Promise((r) => setTimeout(r, 10));
  const resBWhileRunning = await tabB.execute({
    guardKey: 'tab_b_inv',
    idempotencyKey: sharedKey,
    operationType: 'APPROVE_INVOICE',
    userId: 'userB',
    documentReference: 'SINV-777',
    targetCollection: 'invoices',
    payloadSummary: 'اعتماد فاتورة 777',
    execute: async () => {
      serverWrites += 1;
      return { resultReference: 'SINV-777' };
    }
  });

  const resA = await pA;
  assert.equal(resA.ok, true);
  assert.equal(resBWhileRunning.blocked, true);
  assert.equal(resBWhileRunning.status, 'PENDING_CONFIRMATION');
  assert.equal(serverWrites, 1);

  // Scenario 15 & 18: Replaying the SAME completed idempotency key returns ALREADY_RECORDED without re-executing
  const tabC = new TransactionSubmissionGuardEngine(createPersistentStorage(), backend);
  const replayRes = await tabC.execute({
    guardKey: 'tab_c_replay',
    idempotencyKey: sharedKey,
    operationType: 'APPROVE_INVOICE',
    userId: 'userB',
    documentReference: 'SINV-777',
    targetCollection: 'invoices',
    payloadSummary: 'إعادة إرسال نفس المفتاح',
    execute: async () => {
      serverWrites += 1;
      return { resultReference: 'SINV-777' };
    }
  });
  assert.equal(replayRes.ok, true);
  assert.equal(replayRes.replayed, true);
  assert.equal(replayRes.message, IDEMPOTENCY_STATUS_MESSAGES.ALREADY_RECORDED);
  assert.equal(serverWrites, 1);

  // Scenario 19: New operation after completion gets a fresh key and executes cleanly
  const nextRes = await tabA.execute({
    guardKey: 'tab_a_inv',
    operationType: 'APPROVE_INVOICE',
    userId: 'userA',
    documentReference: 'SINV-778',
    targetCollection: 'invoices',
    payloadSummary: 'فاتورة جديدة 778',
    execute: async () => {
      serverWrites += 1;
      return { resultReference: 'SINV-778' };
    }
  });
  assert.equal(nextRes.ok, true);
  assert.equal(nextRes.replayed, false);
  assert.notEqual(nextRes.idempotencyKey, sharedKey);
  assert.equal(serverWrites, 2);
});

test('Scenario 20: Non-network validation failure releases lock cleanly so user fixes input without losing draft', async () => {
  const guard = new TransactionSubmissionGuardEngine(createPersistentStorage(), createMockServerBackend());
  const failedRes = await guard.execute({
    guardKey: 'form_val',
    operationType: 'SAVE',
    userId: 'emp1',
    documentReference: 'DRAFT-1',
    targetCollection: 'invoices',
    payloadSummary: 'اختبار خطأ منطقي',
    execute: async () => {
      throw new Error('الكمية المطلوبة غير متوفرة في المخزن');
    }
  });
  assert.equal(failedRes.ok, false);
  assert.equal(failedRes.status, 'FAILED');
  assert.equal(guard.isLocked('form_val', 'DRAFT-1'), false);

  // Corrected resubmission succeeds
  const fixedRes = await guard.execute({
    guardKey: 'form_val',
    operationType: 'SAVE',
    userId: 'emp1',
    documentReference: 'DRAFT-1',
    targetCollection: 'invoices',
    payloadSummary: 'اختبار بعد التصحيح',
    execute: async () => ({ resultReference: 'INV-FIXED-1' })
  });
  assert.equal(fixedRes.ok, true);
  assert.equal(fixedRes.resultReference, 'INV-FIXED-1');
});

test('Version v21.0 sync & Windows Desktop GitHub release parser work accurately', () => {
  assert.equal(APP_VERSION_CONTRACT.CURRENT_VERSION_CODE, 21);
  assert.equal(APP_VERSION_CONTRACT.CURRENT_VERSION_NAME, '21.0');
  assert.equal(APP_VERSION_CONTRACT.WINDOWS_VERSION_NAME, '21.0.0');

  const rootVersion = JSON.parse(fs.readFileSync(path.join(ROOT_DIR, 'version.json'), 'utf8'));
  const pubVersion = JSON.parse(fs.readFileSync(path.join(ROOT_DIR, 'public/version.json'), 'utf8'));
  const desktopPkg = JSON.parse(fs.readFileSync(path.join(ROOT_DIR, 'desktop/package.json'), 'utf8'));
  assert.equal(rootVersion.versionCode, 21);
  assert.equal(pubVersion.versionCode, 21);
  assert.equal(desktopPkg.version, '21.0.0');

  const parsedDesktop = parseGitHubDesktopRelease({
    tag_name: 'desktop-v21.0.0',
    published_at: '2026-11-15T20:00:00Z',
    draft: false,
    prerelease: false,
    assets: [
      {
        name: 'pmp-paints-desktop-setup-21.0.0-x64.exe',
        size: 85 * 1024 * 1024,
        browser_download_url: 'https://github.com/ow7ab/ow7ab/releases/download/desktop-v21.0.0/pmp-paints-desktop-setup-21.0.0-x64.exe'
      },
      {
        name: 'pmp-paints-desktop-portable-21.0.0-x64.exe',
        size: 80 * 1024 * 1024,
        browser_download_url: 'https://github.com/ow7ab/ow7ab/releases/download/desktop-v21.0.0/pmp-paints-desktop-portable-21.0.0-x64.exe'
      }
    ]
  });
  assert.ok(parsedDesktop);
  assert.equal(parsedDesktop.tagName, 'desktop-v21.0.0');
  assert.equal(parsedDesktop.setupSizeMb, 85);
  assert.equal(parsedDesktop.portableSizeMb, 80);

  // Ignores drafts or releases without .exe
  assert.equal(
    parseGitHubDesktopRelease({
      tag_name: 'desktop-v21.1.0',
      draft: true,
      assets: [{ name: 'app.exe', browser_download_url: 'https://example.com/app.exe' }]
    }),
    null
  );
});
