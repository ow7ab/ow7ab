/**
 * PMP PAINTS Unified Cloud ERP Data Service
 * Connects the Web Platform 1:1 to the EXACT Firestore Database & Cluster Structure
 * used by the Android App (`CloudErpSyncRepository.kt`):
 *
 * Database ID: `ai-studio-android-glassdro-d6052420-cbc0-47dd-8cdd-8caa5e6a7248`
 * Root Path: `/erp_clusters/PMP-ERP-MAIN-CLUSTER/{collectionName}/{docId}`
 *
 * All reads/writes are 100% live against Firestore. No mock fallback arrays exist.
 */

import {
  collection,
  doc,
  getDoc,
  getDocFromServer,
  getDocs,
  getDocsFromServer,
  limit,
  query,
  setDoc,
  deleteDoc,
  onSnapshot,
  serverTimestamp,
  where,
  Unsubscribe
} from 'firebase/firestore';
import {
  auth,
  firestore,
  DEFAULT_CLUSTER_CODE
} from '../config/firebaseConfig';
import { APP_VERSION_CONTRACT } from '../config/appVersionContract';
import {
  CloudErrorLogger,
  ServiceProbeId,
  ServiceProbeResult,
  ComprehensiveDiagnosticReport,
  sanitizeCloudMessage
} from './CloudErrorLogger';
import { isSuperAdminAccount, resolveCanonicalRbacTier } from './SecurityService';
import {
  Cashbox,
  TreasuryMovement,
  InterTreasuryTransfer,
  TreasuryTransfer,
  DailyClosing,
  Warehouse,
  Product,
  StockMove,
  FactoryItem,
  RawMaterialItem,
  FactoryMachine,
  Machine,
  FactoryMove,
  ProductionBatch,
  LabFormulation,
  ChemicalFormulation,
  ChemicalSafety,
  HalfaInvoice,
  PosterPrice,
  SalesInvoice,
  InvoicePayment,
  Customer,
  AuditLog,
  CurrencyCode,
  WarehouseClass,
  InventoryItemCategory,
  InventoryMovementType,
  MovementRecordStatus,
  WarehouseTransferStatus,
  InventoryMovement,
  WarehouseStockBalance,
  WarehouseTransfer,
  WarehouseTransferItem,
  PackagingRule,
  PurchaseReceipt,
  ProductionOrder,
  QcInspection,
  AccountType,
  ChartAccount,
  JournalEntry,
  JournalLine,
  TrialBalanceRow,
  TrialBalanceReport,
  IncomeStatementReport,
  BalanceSheetReport,
  ReconciliationItem,
  CurrencyFxRates,
  ConsolidatedFxSummary,
  UserAccount,
  FormulationIngredient,
  FormulationVersion,
  LabExperiment,
  RawMaterialPriceHistory,
  LabExcelImportBatch,
  ThreeCostComparisonReport
} from '../types/erp';
import { calculatePvc, ingredientsToJson, jsonToIngredients, summarizeIngredientChanges } from './PvcEngine';
import { sanitizeLabLog } from './LabSecurityService';
import type { ExcelWorkbookAnalysisReport } from './ExcelFormulaAnalyzer';
import {
  DEFAULT_CHART_OF_ACCOUNTS,
  resolveCashAccountCode,
  createBalancedJournalPayload,
  buildReversalJournalPayloads,
  buildPurchaseReceiptJournalPayload,
  buildProductionIssueJournalPayload,
  buildQcFinishedGoodsJournalPayload,
  buildSalesInvoiceJournalPayloads,
  buildTreasuryMovementJournalPayload,
  buildTreasuryTransferJournalPayloads,
  computeTrialBalance,
  computeIncomeStatement,
  computeBalanceSheet,
  computeConsolidatedFxSummary,
  computeReconciliationReport
} from './AccountingEngine';
import {
  DEFAULT_PACKAGING_RULES,
  resolveWarehouseClass,
  isSalesWarehouse,
  signedQuantityFor,
  validateWarehousePolicy,
  validateTransferRoute,
  balanceKey,
  postMovementInState,
  attemptDeleteMovement,
  reverseMovementInState,
  canApproveTransfer,
  getCansPerCarton,
  convertCansToCartons,
  convertCartonsToCans,
  receivePurchaseToRawMaterialsInState,
  createProductionOrderModel,
  isFormulaApprovedForProduction,
  issueRawMaterialsForOrderInState,
  inspectQcAndPostToFinishedGoodsInState
} from './InventoryMovementEngine';
import {
  safeId,
  cashboxDocId,
  posterPriceDocId,
  deduplicateCollectionRecords,
  computeDistinctCashboxesWithBalances,
  computeCashboxBalances,
  computeCashboxBalancesByCurrency,
  computeProductStocks,
  computeProductStocksList,
  computeProductMoveBreakdown,
  computeWarehouseStockSummary,
  computeColorWeightStockRows,
  computeFactoryItemBalances,
  computeInvoiceFinancials,
  computeCustomerDebts,
  computeCustomerDebtsList,
  computeCustomerStatement,
  normalizeArabicText,
  normalizeEntityKey,
  extractBareEntityId,
  buildTombstoneKeySet,
  isRecordTombstoned as checkRecordTombstoned,
  resolveCanonicalCurrency,
  isStockInMoveKind,
  resolveWarehouseFilterIds,
  parseDateStringToEpochMs,
  compareRecordsChronologically,
  buildFinancialTransactionFingerprint,
  validateFinancialCommitPayload,
  checkExistingFinancialConflict,
  DistinctCashboxSummary,
  WarehouseStockSummaryItem,
  ColorWeightStockRow,
  CustomerStatementResult,
  CustomerStatementLedgerEntry
} from './ErpCalculations';

export {
  safeId,
  cashboxDocId,
  posterPriceDocId,
  deduplicateCollectionRecords,
  computeDistinctCashboxesWithBalances,
  computeCashboxBalances,
  computeCashboxBalancesByCurrency,
  computeProductStocks,
  computeProductStocksList,
  computeProductMoveBreakdown,
  computeWarehouseStockSummary,
  computeColorWeightStockRows,
  computeFactoryItemBalances,
  computeInvoiceFinancials,
  computeCustomerDebts,
  computeCustomerDebtsList,
  computeCustomerStatement,
  normalizeArabicText,
  normalizeEntityKey,
  resolveCanonicalCurrency,
  isStockInMoveKind,
  resolveWarehouseFilterIds,
  parseDateStringToEpochMs,
  compareRecordsChronologically,
  DEFAULT_PACKAGING_RULES,
  resolveWarehouseClass,
  isSalesWarehouse,
  signedQuantityFor,
  validateWarehousePolicy,
  validateTransferRoute,
  balanceKey,
  postMovementInState,
  attemptDeleteMovement,
  reverseMovementInState,
  canApproveTransfer,
  getCansPerCarton,
  convertCansToCartons,
  convertCartonsToCans,
  receivePurchaseToRawMaterialsInState,
  createProductionOrderModel,
  isFormulaApprovedForProduction,
  issueRawMaterialsForOrderInState,
  inspectQcAndPostToFinishedGoodsInState,
  DEFAULT_CHART_OF_ACCOUNTS,
  resolveCashAccountCode,
  createBalancedJournalPayload,
  buildReversalJournalPayloads,
  buildPurchaseReceiptJournalPayload,
  buildProductionIssueJournalPayload,
  buildQcFinishedGoodsJournalPayload,
  buildSalesInvoiceJournalPayloads,
  buildTreasuryMovementJournalPayload,
  buildTreasuryTransferJournalPayloads,
  computeTrialBalance,
  computeIncomeStatement,
  computeBalanceSheet,
  computeConsolidatedFxSummary,
  computeReconciliationReport
};

export type {
  DistinctCashboxSummary,
  WarehouseStockSummaryItem,
  ColorWeightStockRow,
  CustomerStatementResult,
  CustomerStatementLedgerEntry
};

export type ErpCollectionName =
  | 'user_accounts'
  | 'pending_requests'
  | 'deleted_users'
  | 'cashboxes'
  | 'movements'
  | 'transfers'
  | 'daily_closings'
  | 'warehouses'
  | 'products'
  | 'stock_moves'
  | 'factory_items'
  | 'factory_moves'
  | 'factory_machines'
  | 'raw_materials'
  | 'recipes'
  | 'purchase_receipts'
  | 'production_orders'
  | 'qc_inspections'
  | 'paint_batches'
  | 'lab_formulations'
  | 'lab_formula_summaries'
  | 'lab_formula_versions'
  | 'lab_experiments'
  | 'raw_material_price_history'
  | 'lab_excel_imports'
  | 'chemical_safety'
  | 'halfa_invoices'
  | 'poster_prices'
  | 'invoices'
  | 'invoice_payments'
  | 'price_tiers'
  | 'customers'
  | 'employee_tasks'
  | 'purchase_suggestions'
  | 'inventory_movements'
  | 'warehouse_stock_balances'
  | 'warehouse_transfers'
  | 'warehouse_transfer_items'
  | 'packaging_rules'
  | 'stock_batches'
  | 'accounting_accounts'
  | 'journal_entries'
  | 'journal_lines'
  | 'audit_logs'
  | 'permission_audit_logs'
  | 'idempotency_requests';

const STORAGE_CLUSTER_KEY = 'pmp_active_erp_cluster';
const REMOVED_V16_CLUSTER = 'PMP-ERP-V16-CLUSTER';

export const PRIMARY_CLUSTER = DEFAULT_CLUSTER_CODE; // "PMP-ERP-MAIN-CLUSTER"

/**
 * Normalizes Firestore numeric or Timestamp fields into epoch milliseconds or numbers.
 */
function normalizeDocData<T>(docId: string, raw: Record<string, any>): T {
  const out: Record<string, any> = { ...raw };
  if (out.id === undefined || out.id === null) {
    const numMatch = docId.match(/(\d+)/);
    out.id = numMatch ? Number(numMatch[1]) : docId;
  } else if (typeof out.id === 'string' && /^\d+$/.test(out.id)) {
    out.id = Number(out.id);
  }

  for (const key of ['timestamp', 'createdAt', 'updatedAt', 'dueDate', 'deletedAt', 'changedAtMillis', 'closedAt']) {
    const val = out[key];
    if (val && typeof val === 'object' && typeof val.seconds === 'number') {
      out[key] = val.seconds * 1000;
    } else if (typeof val === 'string' && /^\d+$/.test(val)) {
      out[key] = Number(val);
    }
  }

  out._docId = docId;
  return out as T;
}

export interface ClusterHealthReport {
  clusterCode: string;
  ok: boolean;
  latencyMs: number;
  counts: Record<string, number>;
  error?: string;
}

class ErpDataServiceImpl {
  private currentCluster: string = PRIMARY_CLUSTER;
  private legacyPurged = false;

  constructor() {
    try {
      const saved = localStorage.getItem(STORAGE_CLUSTER_KEY);
      if (saved !== PRIMARY_CLUSTER) {
        localStorage.setItem(STORAGE_CLUSTER_KEY, PRIMARY_CLUSTER);
      }
    } catch {
      // Ignore storage access issues
    }
    this.currentCluster = PRIMARY_CLUSTER;
    // Security audit fix: Do NOT trigger background unauthenticated reads/deletes against
    // deprecated PMP-ERP-V16-CLUSTER during module initialization (caused immediate PERMISSION_DENIED).
  }

  get activeCluster(): string {
    return PRIMARY_CLUSTER;
  }

  setActiveCluster(_clusterCode: string): void {
    this.currentCluster = PRIMARY_CLUSTER;
    try {
      localStorage.setItem(STORAGE_CLUSTER_KEY, PRIMARY_CLUSTER);
    } catch {
      // Ignore storage issues
    }
  }

  private collectionPath(collectionName: ErpCollectionName | string, clusterOverride?: string): string {
    const cluster = safeId(clusterOverride || this.currentCluster, PRIMARY_CLUSTER);
    return `erp_clusters/${cluster}/${collectionName}`;
  }

  /**
   * Legacy V16 cluster cleanup is explicitly blocked at firestore.rules level for security;
   * only allow manual invocation when an authenticated primary Super Admin is signed in.
   */
  async purgeLegacyV16Cluster(actorAccount?: UserAccount | null): Promise<void> {
    if (this.legacyPurged) return;
    if (!auth.currentUser || !isSuperAdminAccount(actorAccount)) {
      return;
    }
    this.legacyPurged = true;
  }

  private tombstoneKeysByCollection: Map<string, Set<string>> = new Map();
  private clusterEpochTimestamp: number = 0;
  private tombstoneUnsub: Unsubscribe | null = null;
  private epochUnsub: Unsubscribe | null = null;
  private activeCollectionRefreshers: Set<() => void> = new Set();

  private notifyCollectionRefreshers(): void {
    this.activeCollectionRefreshers.forEach((refresh) => {
      try {
        refresh();
      } catch {
        // Ignore stale listener cleanup races
      }
    });
  }

  private registerLocalTombstoneKeys(collectionName: string, rawKeys: Array<unknown>): void {
    const col = String(collectionName || '').trim().toLowerCase();
    if (!col) return;
    const nextMap = new Map(this.tombstoneKeysByCollection);
    const set = new Set(nextMap.get(col) || []);
    for (const rawKey of rawKeys) {
      if (rawKey === undefined || rawKey === null) continue;
      const str = String(rawKey).trim();
      if (!str) continue;
      set.add(str.toLowerCase());
      const norm = normalizeEntityKey(str);
      if (norm) set.add(norm);
      const bare = extractBareEntityId(str);
      if (bare) {
        set.add(bare.toLowerCase());
        const bareNorm = normalizeEntityKey(bare);
        if (bareNorm) set.add(bareNorm);
      }
    }
    nextMap.set(col, set);
    this.tombstoneKeysByCollection = nextMap;
    this.notifyCollectionRefreshers();
  }

  private ensureTombstoneSubscription(): void {
    if (!this.tombstoneUnsub) {
      try {
        const colRef = collection(firestore, this.collectionPath('deleted_records', PRIMARY_CLUSTER));
        this.tombstoneUnsub = onSnapshot(
          colRef,
          (snap) => {
            const docs: Array<Record<string, any>> = [];
            snap.forEach((d) => docs.push({ _docId: d.id, ...d.data() }));
            this.tombstoneKeysByCollection = buildTombstoneKeySet(docs);
            this.notifyCollectionRefreshers();
          },
          () => {
            // Reset so post-auth callers re-attach automatically
            this.tombstoneUnsub = null;
          }
        );
      } catch {
        this.tombstoneUnsub = null;
      }
    }

    if (!this.epochUnsub) {
      try {
        const epochRef = doc(firestore, this.collectionPath('system_metadata', PRIMARY_CLUSTER), 'cluster_epoch');
        this.epochUnsub = onSnapshot(
          epochRef,
          (snap) => {
            if (!snap.exists()) return;
            const d = snap.data() || {};
            const rawEpoch = d.wipeTimestamp;
            const parsed =
              rawEpoch && typeof rawEpoch === 'object' && typeof rawEpoch.seconds === 'number'
                ? rawEpoch.seconds * 1000
                : Number(rawEpoch || 0);
            if (Number.isFinite(parsed) && parsed > this.clusterEpochTimestamp) {
              this.clusterEpochTimestamp = parsed;
              this.notifyCollectionRefreshers();
            }
          },
          () => {
            this.epochUnsub = null;
          }
        );
      } catch {
        this.epochUnsub = null;
      }
    }
  }

  private isRecordTombstoned(collectionName: string, raw: Record<string, any>): boolean {
    return checkRecordTombstoned(collectionName, raw, this.tombstoneKeysByCollection, this.clusterEpochTimestamp);
  }

  /**
   * Subscribes in real-time to `/erp_clusters/PMP-ERP-MAIN-CLUSTER/{collectionName}`.
   * Automatically filters out records present in `deleted_records` tombstones or pre-epoch wiped data,
   * re-emits when tombstones update, and deduplicates records on read.
   */
  subscribeToCollection<T = any>(
    collectionName: ErpCollectionName | string,
    onData: (items: T[]) => void,
    onError?: (err: Error) => void
  ): Unsubscribe {
    this.ensureTombstoneSubscription();
    const primaryColRef = collection(firestore, this.collectionPath(collectionName, PRIMARY_CLUSTER));

    let latestRawItems: T[] = [];
    let hasReceivedSnapshot = false;

    const refilterAndEmit = () => {
      if (!hasReceivedSnapshot) return;
      const filtered = latestRawItems.filter(
        (item) => !this.isRecordTombstoned(String(collectionName), item as Record<string, any>)
      );
      onData(deduplicateCollectionRecords<T>(String(collectionName), filtered));
    };

    this.activeCollectionRefreshers.add(refilterAndEmit);

    let fallbackUnsub: Unsubscribe | null = null;
    const unsubPrimary = onSnapshot(
      primaryColRef,
      (snap) => {
        const rawList: T[] = [];
        snap.forEach((docSnap) => {
          rawList.push(normalizeDocData<T>(docSnap.id, docSnap.data()));
        });
        latestRawItems = rawList;
        hasReceivedSnapshot = true;
        refilterAndEmit();
      },
      (err) => {
        const path = this.collectionPath(collectionName, PRIMARY_CLUSTER);
        const msg = String((err as any)?.code || (err as any)?.message || '');
        if (collectionName === 'lab_formulations' && msg.toLowerCase().includes('permission')) {
          if (!fallbackUnsub) {
            fallbackUnsub = this.subscribeToCollection<T>('lab_formula_summaries', onData, onError);
          }
          return;
        }
        CloudErrorLogger.recordError(`SUBSCRIBE_${String(collectionName).toUpperCase()}`, path, err);
        console.error(`Firestore subscription error on [${collectionName}]:`, sanitizeCloudMessage(err));
        if (onError) onError(err);
      }
    );

    return () => {
      this.activeCollectionRefreshers.delete(refilterAndEmit);
      unsubPrimary();
      if (fallbackUnsub) fallbackUnsub();
    };
  }

  /**
   * Subscribes to tombstones and cluster epoch changes in `/erp_clusters/PMP-ERP-MAIN-CLUSTER`.
   */
  subscribeTombstonesAndEpoch(): Unsubscribe {
    this.ensureTombstoneSubscription();
    return () => {
      if (this.activeCollectionRefreshers.size === 0) {
        this.tombstoneUnsub?.();
        this.tombstoneUnsub = null;
        this.epochUnsub?.();
        this.epochUnsub = null;
      }
    };
  }

  /**
   * Fetches all documents once directly from the Firestore server (bypasses stale offline cache)
   * and strictly filters out tombstoned & pre-epoch records.
   */
  async fetchCollectionOnce<T = any>(
    collectionName: ErpCollectionName | string,
    clusterOverride?: string
  ): Promise<T[]> {
    this.ensureTombstoneSubscription();
    const targetCluster = clusterOverride || PRIMARY_CLUSTER;
    const path = this.collectionPath(collectionName, targetCluster);
    const colRef = collection(firestore, path);
    try {
      const snap = await getDocsFromServer(colRef);
      const items: T[] = [];
      snap.forEach((d) => {
        const normalized = normalizeDocData<T>(d.id, d.data());
        if (!this.isRecordTombstoned(String(collectionName), normalized as Record<string, any>)) {
          items.push(normalized);
        }
      });
      return deduplicateCollectionRecords<T>(collectionName, items);
    } catch (err) {
      CloudErrorLogger.recordError(`FETCH_${String(collectionName).toUpperCase()}`, path, err);
      throw err;
    }
  }

  private recentFinancialFingerprints: Map<string, number> = new Map();

  private async collectMatchingFinancialDocs(
    colRef: ReturnType<typeof collection>,
    docRef: ReturnType<typeof doc>,
    payload: Record<string, any>
  ): Promise<Array<Record<string, any>>> {
    const foundById = new Map<string, Record<string, any>>();
    const addDocSnap = (d: { id: string; exists: () => boolean; data: () => any }) => {
      if (!d.exists()) return;
      foundById.set(d.id, { _docId: d.id, ...(d.data() || {}) });
    };

    try {
      const directServerSnap = await getDocFromServer(docRef);
      addDocSnap(directServerSnap);
    } catch {
      try {
        const fallbackSnap = await getDoc(docRef);
        addDocSnap(fallbackSnap);
      } catch {
        // Ignore unavailable direct lookup; proceed to field queries or offline check
      }
    }

    const fieldProbes: Array<[string, string]> = [];
    const syncUuid = String(payload.syncUuid || '').trim();
    const reference = String(payload.reference || '').trim();
    const referenceNo = String(payload.referenceNo || '').trim();
    const invoiceNo = String(payload.invoiceNo || '').trim();
    if (syncUuid) fieldProbes.push(['syncUuid', syncUuid]);
    if (reference) fieldProbes.push(['reference', reference]);
    if (referenceNo && referenceNo !== reference) fieldProbes.push(['referenceNo', referenceNo]);
    if (invoiceNo && invoiceNo !== reference) fieldProbes.push(['invoiceNo', invoiceNo]);

    for (const [field, val] of fieldProbes) {
      const q = query(colRef, where(field, '==', val), limit(10));
      try {
        const snap = await getDocsFromServer(q);
        snap.forEach((d) => foundById.set(d.id, { _docId: d.id, ...(d.data() || {}) }));
      } catch {
        try {
          const fallbackSnap = await getDocs(q);
          fallbackSnap.forEach((d) => foundById.set(d.id, { _docId: d.id, ...(d.data() || {}) }));
        } catch {
          // Graceful degradation when offline
        }
      }
    }

    return Array.from(foundById.values());
  }

  /**
   * Additional Validation Layer (`handleTransactionCommit`) before inserting any financial transaction into Firestore.
   * Verifies payload integrity, blocks tombstone/epoch replays, checks duplicate transactions on Firestore server,
   * detects tampering attempts against already-committed records, and logs every violation to CloudErrorLogger + audit_logs.
   */
  async handleTransactionCommit<T extends Record<string, any>>(params: {
    collectionName: ErpCollectionName | string;
    docId: string;
    payload: T;
    actorUsername?: string;
    clusterOverride?: string;
  }): Promise<string> {
    const colStr = String(params.collectionName || '').trim();
    const cluster = params.clusterOverride || PRIMARY_CLUSTER;
    if (cluster === REMOVED_V16_CLUSTER) {
      const err = new Error('الكتابة في الكلاستر المهجور PMP-ERP-V16-CLUSTER مرفوضة أمنياً');
      CloudErrorLogger.recordTamperAttempt('HANDLE_TRANSACTION_COMMIT', `erp_clusters/${cluster}/${colStr}`, err.message, params.actorUsername);
      throw err;
    }

    const fbUser = auth.currentUser;
    if (!fbUser) {
      const err = new Error('جلسة المصادقة غير نشطة — يرجى إعادة تسجيل الدخول قبل حفظ المعاملة المالية.');
      CloudErrorLogger.recordError('HANDLE_TRANSACTION_COMMIT_AUTH', this.collectionPath(colStr, cluster), err);
      throw err;
    }

    const cleanId = safeId(params.docId, `DOC-${Date.now()}`);
    const colPath = this.collectionPath(colStr, cluster);
    const fullPath = `${colPath}/${cleanId}`;
    const actor = params.actorUsername || fbUser.email || 'WEB_USER';
    const dataToWrite: Record<string, any> = { ...params.payload };
    delete dataToWrite._docId;

    // 1. Payload integrity & anti-tampering validation
    const integrity = validateFinancialCommitPayload(colStr, cleanId, dataToWrite);
    if (!integrity.valid) {
      const reason = integrity.reason || 'قيم مالية غير صالحة أو مشبوهة.';
      CloudErrorLogger.recordTamperAttempt('HANDLE_TRANSACTION_COMMIT', fullPath, reason, actor);
      void this.logAudit(actor, 'TAMPER_ATTEMPT_BLOCKED', `رفض معاملة [${colStr}/${cleanId}]: ${reason}`);
      throw new Error(reason);
    }

    // 2. Strict tombstone & pre-epoch replay guard
    if (this.isRecordTombstoned(colStr, { ...dataToWrite, _docId: cleanId })) {
      const reason = `محاولة إعادة إدخال معاملة مالية محذوفة مسبقاً (${cleanId}) — تم حظر الاسترجاع.`;
      CloudErrorLogger.recordTamperAttempt('TOMBSTONE_REPLAY_BLOCKED', fullPath, reason, actor);
      void this.logAudit(actor, 'TOMBSTONE_REPLAY_BLOCKED', `محاولة إعادة حقن سجل محذوف [${colStr}/${cleanId}]`);
      throw new Error(reason);
    }

    // 3. Sliding-window fingerprint deduplication guard (protects against race/bypass bursts)
    const now = Date.now();
    for (const [k, ts] of this.recentFinancialFingerprints.entries()) {
      if (now - ts > 12_000) this.recentFinancialFingerprints.delete(k);
    }
    const fingerprint = buildFinancialTransactionFingerprint(colStr, dataToWrite);
    const isInvoicePaidUpdate =
      colStr === 'invoices' &&
      Number(dataToWrite.paidAmount ?? 0) > 0 &&
      Boolean(params.payload && (params.payload as any)._docId);
    const recentTs = this.recentFinancialFingerprints.get(fingerprint);
    if (!isInvoicePaidUpdate && recentTs && now - recentTs < 10_000) {
      const reason = `معاملة مالية مكررة خلال نافذة الحماية (${fingerprint}) — تم منع الإرسال المكرر إلى Firestore.`;
      CloudErrorLogger.recordDuplicateAttempt('HANDLE_TRANSACTION_COMMIT', fullPath, reason, actor);
      void this.logAudit(actor, 'DUPLICATE_TRANSACTION_BLOCKED', `منع تكرار فوري [${colStr}/${cleanId}] (${fingerprint})`);
      throw new Error(reason);
    }

    // 4. Server-backed Firestore conflict & tamper inspection
    const colRef = collection(firestore, colPath);
    const docRef = doc(firestore, colPath, cleanId);
    const existingDocs = await this.collectMatchingFinancialDocs(colRef, docRef, dataToWrite);
    const conflict = checkExistingFinancialConflict(colStr, cleanId, dataToWrite, existingDocs);
    if (!conflict.valid) {
      const targetRef = conflict.matchedDocId || cleanId;
      const reason = conflict.reason || 'معاملة مالية مكررة أو معدلة بشكل غير مصرح به في Firestore.';
      if (conflict.violationType === 'TAMPER') {
        CloudErrorLogger.recordTamperAttempt('HANDLE_TRANSACTION_COMMIT', `${colPath}/${targetRef}`, reason, actor);
        void this.logAudit(actor, 'TAMPER_ATTEMPT_BLOCKED', `رصد تلاعب ضد سجل [${colStr}/${targetRef}]: ${reason}`);
      } else {
        CloudErrorLogger.recordDuplicateAttempt('HANDLE_TRANSACTION_COMMIT', `${colPath}/${targetRef}`, reason, actor);
        void this.logAudit(actor, 'DUPLICATE_TRANSACTION_BLOCKED', `منع تكرار سجل [${colStr}/${targetRef}]: ${reason}`);
      }
      throw new Error(reason);
    }

    // 5. Commit verified transaction atomically
    this.recentFinancialFingerprints.set(fingerprint, now);
    try {
      await setDoc(
        docRef,
        {
          ...dataToWrite,
          serverUpdatedAt: serverTimestamp()
        },
        { merge: true }
      );
      return cleanId;
    } catch (err) {
      this.recentFinancialFingerprints.delete(fingerprint);
      CloudErrorLogger.recordError(`COMMIT_${colStr.toUpperCase()}`, fullPath, err);
      throw err;
    }
  }

  /**
   * Upserts a document into `/erp_clusters/PMP-ERP-MAIN-CLUSTER/{collectionName}/{docId}`
   * with `serverUpdatedAt` matching Android `CloudErpSyncRepository.kt`.
   */
  async upsertDocument<T extends Record<string, any>>(
    collectionName: ErpCollectionName | string,
    docId: string,
    payload: T,
    clusterOverride?: string,
    options?: { skipCommitValidation?: boolean; actorUsername?: string }
  ): Promise<string> {
    if (clusterOverride === REMOVED_V16_CLUSTER) {
      const err = new Error('الكتابة في الكلاستر المهجور PMP-ERP-V16-CLUSTER مرفوضة أمنياً');
      CloudErrorLogger.recordError('UPSERT_BLOCKED', `erp_clusters/${REMOVED_V16_CLUSTER}/${collectionName}`, err);
      throw err;
    }

    const financialValidatedCollections = new Set(['movements', 'transfers', 'invoices', 'invoice_payments']);
    if (!options?.skipCommitValidation && financialValidatedCollections.has(String(collectionName))) {
      return this.handleTransactionCommit({
        collectionName,
        docId,
        payload,
        actorUsername: options?.actorUsername,
        clusterOverride
      });
    }

    const cleanId = safeId(docId, `DOC-${Date.now()}`);
    const path = `${this.collectionPath(collectionName, clusterOverride)}/${cleanId}`;
    const ref = doc(firestore, this.collectionPath(collectionName, clusterOverride), cleanId);
    const dataToWrite: Record<string, any> = { ...payload };
    delete dataToWrite._docId;

    // Guard transactional collections against re-uploading already-tombstoned records from stale retry queues
    const guardTombstoneCollections = new Set([
      'movements',
      'transfers',
      'invoices',
      'invoice_items',
      'invoice_payments',
      'stock_moves',
      'factory_moves',
      'halfa_invoices',
      'lab_formulations',
      'paint_batches'
    ]);
    if (
      guardTombstoneCollections.has(String(collectionName)) &&
      this.isRecordTombstoned(String(collectionName), { ...dataToWrite, _docId: cleanId })
    ) {
      console.warn(`Blocked upsert of tombstoned record [${collectionName}/${cleanId}] to prevent resurrection.`);
      return cleanId;
    }

    // Enforce balanced journal entry invariant and append-only immutability on retries
    if (collectionName === 'journal_entries') {
      const td = Number(dataToWrite.totalDebit ?? 0);
      const tc = Number(dataToWrite.totalCredit ?? 0);
      if (Math.abs(td - tc) >= 0.001) {
        const err = new Error('القيد المحاسبي غير متوازن: إجمالي المدين لا يساوي إجمالي الدائن.');
        CloudErrorLogger.recordError('UPSERT_JOURNAL_ENTRY', path, err);
        throw err;
      }
    }

    if (
      collectionName === 'journal_lines' ||
      collectionName === 'journal_entries' ||
      collectionName === 'inventory_movements'
    ) {
      try {
        const existingSnap = await getDoc(ref);
        if (existingSnap.exists()) {
          return cleanId;
        }
      } catch {
        // Proceed to setDoc; Firestore rules will still enforce RBAC and immutability
      }
    }

    try {
      await setDoc(
        ref,
        {
          ...dataToWrite,
          serverUpdatedAt: serverTimestamp()
        },
        { merge: true }
      );
      return cleanId;
    } catch (err) {
      CloudErrorLogger.recordError(`UPSERT_${String(collectionName).toUpperCase()}`, path, err);
      throw err;
    }
  }

  /**
   * Alias for `upsertDocument` used by TreasuryModuleView and other modules.
   */
  async saveDocument<T extends Record<string, any>>(
    collectionName: ErpCollectionName | string,
    docId: string,
    payload: T,
    actorUsername?: string
  ): Promise<string> {
    return this.upsertDocument(collectionName, docId, payload, undefined, { actorUsername });
  }

  /**
   * Alias for `upsertDocument` used by Purchases, Factory, Lab, and Sales modules.
   */
  async saveRecord<T extends Record<string, any>>(
    collectionName: ErpCollectionName | string,
    docId: string,
    payload: T,
    actorUsername?: string
  ): Promise<string> {
    return this.upsertDocument(collectionName, docId, payload, undefined, { actorUsername });
  }

  /**
   * Reverses a POSTED journal entry or inventory movement strictly using the 7 allowed reversal keys.
   */
  async reverseImmutableRecord(
    collectionName: 'journal_entries' | 'inventory_movements',
    docId: string,
    reversedByEntryNo: string,
    reversalReason: string,
    actorUsername: string,
    nextVersion: number = 2
  ): Promise<void> {
    const cleanId = safeId(docId, docId);
    const path = `${this.collectionPath(collectionName, PRIMARY_CLUSTER)}/${cleanId}`;
    try {
      await setDoc(
        doc(firestore, this.collectionPath(collectionName, PRIMARY_CLUSTER), cleanId),
        {
          status: 'REVERSED',
          reversedByEntryNo,
          reversalReason: reversalReason || 'قيد عكسي معتمد',
          recordVersion: nextVersion,
          updatedAt: Date.now(),
          serverUpdatedBy: actorUsername || 'system',
          serverUpdatedAt: serverTimestamp()
        },
        { merge: true }
      );
    } catch (err) {
      CloudErrorLogger.recordError(`REVERSE_${collectionName.toUpperCase()}`, path, err);
      throw err;
    }
  }

  /**
   * Deletes a document from `/erp_clusters/PMP-ERP-MAIN-CLUSTER/{collectionName}/{docId}`.
   * Enforces append-only protection on audit logs, ledgers, inventory movements, and idempotency keys.
   */
  async deleteDocument(
    collectionName: ErpCollectionName | string,
    docId: string,
    clusterOverride?: string
  ): Promise<void> {
    const immutableCollections = new Set([
      'audit_logs',
      'permission_audit_logs',
      'journal_entries',
      'journal_lines',
      'inventory_movements',
      'idempotency_requests'
    ]);
    const cleanId = safeId(docId, docId);
    const path = `${this.collectionPath(collectionName, clusterOverride)}/${cleanId}`;
    if (immutableCollections.has(collectionName)) {
      const err = new Error(
        `السجل في [${collectionName}] محصن ضد الحذف المباشر — استخدم القيد العكسي المعتمد بدلاً من الحذف.`
      );
      CloudErrorLogger.recordError('DELETE_IMMUTABLE_BLOCKED', path, err);
      throw err;
    }

    try {
      const ref = doc(firestore, this.collectionPath(collectionName, clusterOverride), cleanId);
      await deleteDoc(ref);
    } catch (err) {
      CloudErrorLogger.recordError(`DELETE_${String(collectionName).toUpperCase()}`, path, err);
      throw err;
    }
  }

  /**
   * Registers an authoritative tombstone in `deleted_records` BEFORE deleting the target document(s)
   * (plus any cross-platform aliases) so offline/mobile devices remove it locally and never re-upload it.
   */
  async deleteDocumentWithTombstone(
    collectionName: ErpCollectionName | string,
    docId: string,
    entityIdOrName: number | string,
    deletedBy: string = 'المدير',
    aliasKeys: Array<string | number | null | undefined> = []
  ): Promise<void> {
    const cleanId = safeId(docId, docId);
    const cleanAliases = Array.from(
      new Set(
        [cleanId, String(entityIdOrName), ...aliasKeys]
          .map((v) => (v === undefined || v === null ? '' : String(v).trim()))
          .filter(Boolean)
      )
    );

    this.registerLocalTombstoneKeys(String(collectionName), cleanAliases);

    const tombDocId = safeId(`${collectionName}_${cleanId}`, `${collectionName}_${Date.now()}`);
    const tombPath = `${this.collectionPath('deleted_records', PRIMARY_CLUSTER)}/${tombDocId}`;
    try {
      const numericEntityId = Number(entityIdOrName);
      const tombPayload: Record<string, any> = {
        collection: collectionName,
        docId: cleanId,
        entityId: String(entityIdOrName),
        aliases: cleanAliases,
        deletedBy: deletedBy || 'المدير',
        deletedAt: Date.now(),
        serverUpdatedAt: serverTimestamp()
      };
      if (Number.isFinite(numericEntityId) && numericEntityId > 0) {
        tombPayload.numericId = numericEntityId;
      }
      await setDoc(
        doc(firestore, this.collectionPath('deleted_records', PRIMARY_CLUSTER), tombDocId),
        tombPayload,
        { merge: true }
      );
    } catch (err) {
      CloudErrorLogger.recordError('WRITE_TOMBSTONE', tombPath, err);
      throw err;
    }

    await this.deleteDocument(collectionName, cleanId);

    // Best-effort cleanup of secondary alias Firestore docs (e.g. MOV-123 alongside MOV-IDEM-...)
    const extraDocCandidates = aliasKeys
      .map((a) => (a === undefined || a === null ? '' : safeId(String(a), '')))
      .filter((d) => d && d !== cleanId && /^(MOV|INV|TRF|CUST|WH|SM|FI|FM|PRD|PROD|BOX|CB|PAY|II|DC|PR|PO|GRP)-/i.test(d));
    for (const extraDoc of extraDocCandidates) {
      await this.deleteDocument(collectionName, extraDoc).catch(() => {});
    }
  }

  /**
   * Alias for `deleteDocument` used by modules passing `(collectionName, docId, numericId?, actor?)`.
   */
  async deleteRecord(
    collectionName: ErpCollectionName | string,
    docId: string,
    numericId?: number | string,
    actorUsername?: string
  ): Promise<void> {
    const resolvedEntityKey =
      numericId !== undefined && numericId !== null && String(numericId).trim() !== ''
        ? numericId
        : docId;
    await this.deleteDocumentWithTombstone(
      collectionName,
      docId,
      resolvedEntityKey,
      actorUsername || 'المدير'
    );
  }

  /**
   * Logs an administrative or business action to `/erp_clusters/{activeCluster}/audit_logs`.
   */
  async logAudit(username: string, action: string, detail: string): Promise<void> {
    try {
      const now = Date.now();
      const docId = `LOG-${now}-${Math.floor(Math.random() * 1000)}`;
      const entry: AuditLog = {
        id: now,
        username: username || 'النظام',
        action,
        detail,
        timestamp: now
      };
      await this.upsertDocument('audit_logs', docId, entry);
    } catch (err) {
      console.warn('Failed to write audit log:', sanitizeCloudMessage(err));
    }
  }

  /**
   * Performs a live read-only health and record-count check directly against Firestore servers.
   */
  async checkServerHealth(clusterOverride?: string): Promise<ClusterHealthReport> {
    const cluster = clusterOverride || PRIMARY_CLUSTER;
    const start = performance.now();
    const counts: Record<string, number> = {};
    try {
      const collectionsToProbe: ErpCollectionName[] = [
        'user_accounts',
        'cashboxes',
        'movements',
        'warehouses',
        'products',
        'customers',
        'invoices'
      ];
      await Promise.all(
        collectionsToProbe.map(async (col) => {
          const items = await this.fetchCollectionOnce(col, cluster);
          counts[col] = items.length;
        })
      );
      const latencyMs = Math.round(performance.now() - start);
      return {
        clusterCode: cluster,
        ok: true,
        latencyMs,
        counts
      };
    } catch (err: any) {
      const entry = CloudErrorLogger.recordError('CHECK_SERVER_HEALTH', `erp_clusters/${cluster}`, err);
      return {
        clusterCode: cluster,
        ok: false,
        latencyMs: Math.round(performance.now() - start),
        counts,
        error: `${entry.categoryTitleAr}: ${entry.sanitizedMessage}`
      };
    }
  }

  /**
   * Runs one strictly read-only service diagnostic probe (zero writes).
   */
  async probeSingleService(
    probeId: ServiceProbeId,
    actorAccount?: UserAccount | null
  ): Promise<ServiceProbeResult> {
    const start = performance.now();
    const now = Date.now();

    if (probeId === 'NETWORK') {
      const online = typeof navigator !== 'undefined' ? navigator.onLine !== false : true;
      if (!online) {
        return {
          id: 'NETWORK',
          titleAr: 'اتصال الشبكة والإنترنت',
          ok: false,
          measured: true,
          latencyMs: Math.round(performance.now() - start),
          summaryAr: 'الجهاز غير متصل بالشبكة (navigator.onLine = false)',
          remediationHintAr: 'تأكد من اتصال الإنترنت وأعد المحاولة.',
          checkedAtMillis: now
        };
      }
      return {
        id: 'NETWORK',
        titleAr: 'اتصال الشبكة والإنترنت',
        ok: true,
        measured: true,
        latencyMs: Math.round(performance.now() - start),
        summaryAr: 'الشبكة متصلة ومتاحة للاستعلامات السحابية (قراءة فقط)',
        remediationHintAr: 'الاتصال سليم',
        checkedAtMillis: now
      };
    }

    if (probeId === 'FIREBASE_AUTH') {
      const fbUser = auth.currentUser;
      if (!fbUser) {
        return {
          id: 'FIREBASE_AUTH',
          titleAr: 'جلسة مصادقة Firebase Auth',
          ok: false,
          measured: true,
          latencyMs: Math.round(performance.now() - start),
          summaryAr: 'لا توجد جلسة Firebase Auth نشطة حالياً (request.auth == null)',
          remediationHintAr: 'سجّل الدخول عبر Google SSO أو البريد المعتمد لتمكين الكتابة والمزامنة السحابية.',
          checkedAtMillis: now
        };
      }
      try {
        const tokenRes = await fbUser.getIdTokenResult(false);
        return {
          id: 'FIREBASE_AUTH',
          titleAr: 'جلسة مصادقة Firebase Auth',
          ok: true,
          measured: true,
          latencyMs: Math.round(performance.now() - start),
          summaryAr: `المصادقة نشطة (UID: ${fbUser.uid.slice(0, 8)}... | provider: ${tokenRes.signInProvider || 'custom'})`,
          remediationHintAr: 'رمز الجلسة ساري المفعول',
          checkedAtMillis: now
        };
      } catch (err) {
        const logged = CloudErrorLogger.recordError('PROBE_FIREBASE_AUTH', 'auth/currentUser', err);
        return {
          id: 'FIREBASE_AUTH',
          titleAr: 'جلسة مصادقة Firebase Auth',
          ok: false,
          measured: true,
          latencyMs: Math.round(performance.now() - start),
          summaryAr: logged.sanitizedMessage,
          remediationHintAr: logged.remediationHintAr,
          checkedAtMillis: now
        };
      }
    }

    if (probeId === 'FIRESTORE') {
      const probePath = this.collectionPath('warehouses', PRIMARY_CLUSTER);
      try {
        const snap = await getDocsFromServer(query(collection(firestore, probePath), limit(1)));
        return {
          id: 'FIRESTORE',
          titleAr: 'قراءة Cloud Firestore الحية',
          ok: true,
          measured: true,
          latencyMs: Math.round(performance.now() - start),
          summaryAr: `تمت قراءة السيرفر مباشرة بدون كاش (${snap.size} سجل فحص)`,
          remediationHintAr: 'قاعدة البيانات تستجيب للقراءة المباشرة',
          checkedAtMillis: now
        };
      } catch (err) {
        const logged = CloudErrorLogger.recordError('PROBE_FIRESTORE', probePath, err);
        return {
          id: 'FIRESTORE',
          titleAr: 'قراءة Cloud Firestore الحية',
          ok: false,
          measured: true,
          latencyMs: Math.round(performance.now() - start),
          summaryAr: `${logged.categoryTitleAr}: ${logged.sanitizedMessage}`,
          remediationHintAr: logged.remediationHintAr,
          checkedAtMillis: now
        };
      }
    }

    if (probeId === 'RBAC_POLICY') {
      const fbUser = auth.currentUser;
      const tier = resolveCanonicalRbacTier(actorAccount);
      if (!fbUser) {
        return {
          id: 'RBAC_POLICY',
          titleAr: 'سياسات الصلاحيات والعضوية (RBAC)',
          ok: false,
          measured: true,
          latencyMs: Math.round(performance.now() - start),
          summaryAr: `جلسة محلية (${tier}) بدون ربط Firebase Auth — السيرفر يرفض الكتابة المحمية`,
          remediationHintAr: 'اربط الحساب بمستند cluster_members/{uid} عبر Google Sign-In المعتمد.',
          checkedAtMillis: now
        };
      }
      const memberDocPath = `erp_clusters/${PRIMARY_CLUSTER}/cluster_members/${fbUser.uid}`;
      try {
        const memberSnap = await getDocFromServer(doc(firestore, memberDocPath));
        if (!memberSnap.exists()) {
          return {
            id: 'RBAC_POLICY',
            titleAr: 'سياسات الصلاحيات والعضوية (RBAC)',
            ok: false,
            measured: true,
            latencyMs: Math.round(performance.now() - start),
            summaryAr: `مستند العضوية مفقود (${memberDocPath}) رغم دخول الجلسة (${tier})`,
            remediationHintAr: 'اطلب من مدير النظام مزامنة سجل cluster_members أو تهيئة email_directory.',
            checkedAtMillis: now
          };
        }
        const mData = memberSnap.data() || {};
        const isActive = mData.active === true;
        return {
          id: 'RBAC_POLICY',
          titleAr: 'سياسات الصلاحيات والعضوية (RBAC)',
          ok: isActive,
          measured: true,
          latencyMs: Math.round(performance.now() - start),
          summaryAr: isActive
            ? `عضوية موثقة: roleKey=${mData.roleKey || tier} | active=true`
            : 'العضوية مجمدة (active=false) في cluster_members',
          remediationHintAr: isActive ? 'صلاحيات السيرفر متطابقة' : 'يلزم تفعيل العضوية من مدير النظام.',
          checkedAtMillis: now
        };
      } catch (err) {
        const logged = CloudErrorLogger.recordError('PROBE_RBAC_POLICY', memberDocPath, err);
        return {
          id: 'RBAC_POLICY',
          titleAr: 'سياسات الصلاحيات والعضوية (RBAC)',
          ok: false,
          measured: true,
          latencyMs: Math.round(performance.now() - start),
          summaryAr: `${logged.categoryTitleAr}: ${logged.sanitizedMessage}`,
          remediationHintAr: logged.remediationHintAr,
          checkedAtMillis: now
        };
      }
    }

    // APP_UPDATES (Read-only check)
    try {
      const verSnap = await getDoc(doc(firestore, 'app_updates', 'version_json'));
      const remoteCode = verSnap.exists() ? Number(verSnap.data()?.versionCode ?? 0) : 0;
      const contractCode = APP_VERSION_CONTRACT.CURRENT_VERSION_CODE;
      return {
        id: 'APP_UPDATES',
        titleAr: 'بيانات تحديثات الإصدار (app_updates)',
        ok: true,
        measured: true,
        latencyMs: Math.round(performance.now() - start),
        summaryAr: verSnap.exists()
          ? `نسخة السيرفر ${remoteCode} | نسخة النظام المعتمدة ${contractCode}`
          : `عقد الإصدار المحلي المعتمد v${APP_VERSION_CONTRACT.CURRENT_VERSION_NAME} (b${contractCode})`,
        remediationHintAr: 'قناة التحديثات متاحة للقراءة فقط',
        checkedAtMillis: now
      };
    } catch (err) {
      const logged = CloudErrorLogger.recordError('PROBE_APP_UPDATES', 'app_updates/version_json', err);
      return {
        id: 'APP_UPDATES',
        titleAr: 'بيانات تحديثات الإصدار (app_updates)',
        ok: false,
        measured: true,
        latencyMs: Math.round(performance.now() - start),
        summaryAr: logged.sanitizedMessage,
        remediationHintAr: logged.remediationHintAr,
        checkedAtMillis: now
      };
    }
  }

  /**
   * Executes all 5 read-only service probes sequentially/concurrently and returns a diagnostic report.
   */
  async runComprehensiveDiagnostics(actorAccount?: UserAccount | null): Promise<ComprehensiveDiagnosticReport> {
    const start = performance.now();
    const probeIds: ServiceProbeId[] = ['NETWORK', 'FIREBASE_AUTH', 'FIRESTORE', 'RBAC_POLICY', 'APP_UPDATES'];
    const probes = await Promise.all(probeIds.map((id) => this.probeSingleService(id, actorAccount)));
    return {
      clusterCode: PRIMARY_CLUSTER,
      generatedAtMillis: Date.now(),
      overallOk: probes.every((p) => p.ok),
      readOnlyGuarantee: true,
      probes,
      totalLatencyMs: Math.round(performance.now() - start)
    };
  }

  computeDistinctCashboxesWithBalances(
    cashboxes: Cashbox[],
    movements: TreasuryMovement[]
  ): DistinctCashboxSummary[] {
    return computeDistinctCashboxesWithBalances(cashboxes, movements);
  }

  computeCashboxBalances(
    cashboxes: Cashbox[],
    movements: TreasuryMovement[]
  ): Cashbox[] {
    return computeCashboxBalances(cashboxes, movements);
  }

  computeCashboxBalancesByCurrency(
    cashboxes: Cashbox[],
    movements: TreasuryMovement[]
  ): Record<CurrencyCode, number> {
    return computeCashboxBalancesByCurrency(cashboxes, movements);
  }

  computeProductStocks(
    products: Product[],
    stockMoves: StockMove[],
    warehouseIdFilter?: number | null,
    warehouses?: Warehouse[]
  ): Map<number, number> {
    return computeProductStocks(products, stockMoves, warehouseIdFilter, warehouses);
  }

  computeProductStocksList(
    products: Product[],
    stockMoves: StockMove[],
    warehouseIdFilter?: number | null,
    warehouses?: Warehouse[]
  ): Product[] {
    return computeProductStocksList(products, stockMoves, warehouseIdFilter, warehouses);
  }

  computeProductMoveBreakdown(
    products: Product[],
    stockMoves: StockMove[],
    warehouseIdFilter?: number | null,
    warehouses?: Warehouse[]
  ): Map<number, { inQty: number; outQty: number; netStock: number }> {
    return computeProductMoveBreakdown(products, stockMoves, warehouseIdFilter, warehouses);
  }

  computeWarehouseStockSummary(
    warehouses: Warehouse[],
    products: Product[],
    stockMoves: StockMove[]
  ): WarehouseStockSummaryItem[] {
    return computeWarehouseStockSummary(warehouses, products, stockMoves);
  }

  computeColorWeightStockRows(
    products: Product[],
    stockMoves: StockMove[],
    selectedWeight: string,
    warehouseIdFilter?: number | null,
    warehouses?: Warehouse[]
  ): ColorWeightStockRow[] {
    return computeColorWeightStockRows(products, stockMoves, selectedWeight, warehouseIdFilter, warehouses);
  }

  computeFactoryItemBalances(
    factoryItems: FactoryItem[],
    factoryMoves: FactoryMove[]
  ): Map<number, number> {
    return computeFactoryItemBalances(factoryItems, factoryMoves);
  }

  computeInvoiceFinancials(
    inv: SalesInvoice,
    payments: InvoicePayment[]
  ): {
    paidAmount: number;
    remainingAmount: number;
    statusLabel: 'مدفوعة' | 'سداد جزئي' | 'غير مدفوعة' | 'ملغاة';
  } {
    return computeInvoiceFinancials(inv, payments);
  }

  computeCustomerDebts(
    customers: Customer[],
    invoices: SalesInvoice[],
    payments: InvoicePayment[]
  ): Map<
    number,
    {
      totalInvoiced: number;
      totalPaid: number;
      remaining: number;
      primaryCurrency: CurrencyCode;
    }
  > {
    return computeCustomerDebts(customers, invoices, payments);
  }

  computeCustomerDebtsList(
    customers: Customer[],
    invoices: SalesInvoice[],
    payments: InvoicePayment[]
  ): Array<Customer & { totalInvoiced: number; totalPaid: number; remainingDebt: number; currency: CurrencyCode }> {
    return computeCustomerDebtsList(customers, invoices, payments);
  }

  computeCustomerStatement(
    customer: Customer,
    invoices: SalesInvoice[],
    payments: InvoicePayment[]
  ): CustomerStatementResult {
    return computeCustomerStatement(customer, invoices, payments);
  }

  parseDateStringToEpochMs(rawDate: unknown): number {
    return parseDateStringToEpochMs(rawDate);
  }

  compareRecordsChronologically(
    a: Record<string, any>,
    b: Record<string, any>,
    order: 'desc' | 'asc' = 'desc'
  ): number {
    return compareRecordsChronologically(a, b, order);
  }

  async persistJournalPayload(
    payload: { entry: JournalEntry; lines: JournalLine[] } | null | undefined
  ): Promise<void> {
    if (!payload) return;
    const totalDebit = payload.lines.reduce((s, l) => s + (Number(l.debit) || 0), 0);
    const totalCredit = payload.lines.reduce((s, l) => s + (Number(l.credit) || 0), 0);
    if (Math.abs(totalDebit - totalCredit) >= 0.001) {
      const err = new Error('رفض الترحيل: إجمالي المدين لا يساوي إجمالي الدائن في أسطر القيد.');
      CloudErrorLogger.recordError('PERSIST_JOURNAL', 'journal_entries', err);
      throw err;
    }
    await this.saveRecord('journal_entries', payload.entry.syncUuid, payload.entry);
    await Promise.all(
      payload.lines.map((ln) => this.saveRecord('journal_lines', ln.syncUuid, ln))
    );
  }

  async persistJournalPayloads(
    payloads: Array<{ entry: JournalEntry; lines: JournalLine[] } | null | undefined>
  ): Promise<void> {
    for (const p of payloads) {
      if (p) await this.persistJournalPayload(p);
    }
  }

  /**
   * Builds a non-secret operational summary of a formulation (strictly strips secret ingredient ratios & ciphertext).
   */
  buildNonSecretFormulaSummary(f: LabFormulation): Record<string, any> {
    return {
      id: f.id,
      syncUuid: f.syncUuid || f.formulaNo,
      formulaNo: f.formulaNo,
      productName: f.productName,
      paintType: f.paintType,
      baseKind: f.baseKind,
      linkedProductId: f.linkedProductId ?? null,
      linkedProductSku: f.linkedProductSku || '',
      category: f.category || 'معجونات ودهانات',
      calculatedPvc: f.calculatedPvc,
      cpvcEstimated: f.cpvcEstimated,
      calculatedDensity: f.calculatedDensity,
      totalBatchWeight: f.totalBatchWeight,
      totalBatchVolume: f.totalBatchVolume,
      totalSolidsWeight: f.totalSolidsWeight,
      totalSolidsVolume: f.totalSolidsVolume,
      approvedForProduction: Boolean(f.approvedForProduction),
      approvalStatus: f.approvalStatus || (f.approvedForProduction ? 'APPROVED' : 'NEEDS_REVIEW'),
      versionNumber: f.versionNumber || 1,
      approvedBy: f.approvedBy || '',
      approvedAt: f.approvedAt || 0,
      createdBy: f.createdBy,
      createdAt: f.createdAt,
      updatedAt: f.updatedAt || f.createdAt
    };
  }

  /**
   * Immutable Formulation Versioning:
   * - If editing an APPROVED formulation, archives the prior approved version & advances to v+1 in NEEDS_REVIEW
   *   (`approvedForProduction = false`) without overwriting the historical snapshot or locked cost.
   */
  async saveFormulationWithVersioning(params: {
    existing?: LabFormulation | null;
    productName: string;
    paintType: string;
    baseKind: string;
    notes?: string;
    category?: string;
    currency?: string;
    ingredients: FormulationIngredient[];
    changeReason: string;
    actor: string;
    confidentialCiphertext?: string;
    isConfidentialSealed?: boolean;
  }): Promise<{ formula: LabFormulation; version: FormulationVersion; docId: string }> {
    const now = Date.now();
    const pvc = calculatePvc(params.ingredients);
    const existing = params.existing || null;
    const isRevision = Boolean(existing);
    const oldVersion = existing ? existing.versionNumber || 1 : 0;
    const wasApproved = Boolean(
      existing && (existing.approvedForProduction || existing.approvalStatus === 'APPROVED')
    );
    const nextVersion = existing ? (wasApproved ? oldVersion + 1 : oldVersion) : 1;
    const nextStatus = existing ? 'NEEDS_REVIEW' : 'NEEDS_REVIEW';
    const formulaNo = existing?.formulaNo || `FORM-${String(now).slice(-5)}`;
    const docId = (existing as any)?._docId || safeId(formulaNo, `FORM-${now}`);

    if (existing && wasApproved) {
      const archivedSnap: FormulationVersion = {
        syncUuid: `VER-${formulaNo}-v${oldVersion}-ARCH`,
        formulaId: existing.id,
        formulaNo,
        productName: existing.productName,
        versionNumber: oldVersion,
        previousVersionNumber: Math.max(0, oldVersion - 1),
        status: 'ARCHIVED',
        changeReason: existing.changeReason || 'لقطة معتمدة محفوظة قبل التعديل',
        diffSummaryJson: JSON.stringify({ archivedBeforeRevision: nextVersion }),
        previousCostPerKg: existing.historicalCostPerKg || existing.costPerKg,
        newCostPerKg: existing.historicalCostPerKg || existing.costPerKg,
        historicalTotalCost: existing.historicalTotalCost || existing.totalCost,
        calculatedPvc: existing.calculatedPvc,
        calculatedDensity: existing.calculatedDensity,
        currency: existing.currency || 'EGP',
        ingredientsSnapshotJson: existing.isConfidentialSealed ? '[]' : existing.ingredientsJson,
        encryptedSnapshotCiphertext: existing.confidentialCiphertext || '',
        createdBy: existing.createdBy,
        approvedBy: existing.approvedBy || '',
        approvedAt: existing.approvedAt || 0,
        createdAt: existing.createdAt
      };
      await this.upsertDocument('lab_formula_versions', archivedSnap.syncUuid, archivedSnap).catch(() => {});
    }

    const oldIngredients = existing ? jsonToIngredients(existing.ingredientsJson) : [];
    const diffSummary = summarizeIngredientChanges(oldIngredients, params.ingredients);

    const formula: LabFormulation = {
      id: existing?.id || now,
      syncUuid: existing?.syncUuid || formulaNo,
      formulaNo,
      productName: params.productName.trim(),
      paintType: params.paintType.trim() || 'مطفي معماري',
      baseKind: params.baseKind.trim() || 'أبيض',
      linkedProductId: existing?.linkedProductId ?? null,
      linkedProductSku: existing?.linkedProductSku || '',
      category: params.category || existing?.category || 'معجونات ودهانات',
      targetPvc: pvc.calculatedPvc,
      calculatedPvc: pvc.calculatedPvc,
      cpvcEstimated: pvc.cpvcEstimated,
      totalSolidsWeight: pvc.totalSolidsWeight,
      totalSolidsVolume: pvc.totalSolidsVolume,
      totalBatchWeight: pvc.totalBatchWeight,
      totalBatchVolume: pvc.totalBatchVolume,
      calculatedDensity: pvc.calculatedDensity,
      totalCost: pvc.totalCost,
      costPerKg: pvc.costPerKg,
      historicalTotalCost:
        existing && (existing.historicalTotalCost || 0) > 0
          ? existing.historicalTotalCost
          : 0,
      historicalCostPerKg:
        existing && (existing.historicalCostPerKg || 0) > 0
          ? existing.historicalCostPerKg
          : 0,
      currency: params.currency || existing?.currency || 'EGP',
      ingredientsJson: params.isConfidentialSealed ? '[]' : ingredientsToJson(params.ingredients),
      technicalAnalysis: pvc.technicalAnalysisText,
      notes: (params.notes || '').trim() || pvc.paintClassification,
      approvedForProduction: false,
      approvalStatus: nextStatus,
      versionNumber: nextVersion,
      approvedBy: '',
      approvedAt: 0,
      changeReason: params.changeReason.trim() || (isRevision ? 'تعديل إصدار تركيبة' : 'إنشاء تركيبة جديدة'),
      createdBy: existing?.createdBy || params.actor,
      updatedBy: params.actor,
      createdAt: existing?.createdAt || now,
      updatedAt: now,
      confidentialCiphertext: params.confidentialCiphertext || '',
      isConfidentialSealed: Boolean(params.isConfidentialSealed)
    };

    const versionRecord: FormulationVersion = {
      syncUuid: `VER-${formulaNo}-v${nextVersion}-${now}`,
      formulaId: formula.id,
      formulaNo,
      productName: formula.productName,
      versionNumber: nextVersion,
      previousVersionNumber: oldVersion,
      status: nextStatus,
      changeReason: formula.changeReason || '',
      diffSummaryJson: JSON.stringify(diffSummary),
      previousCostPerKg: existing ? existing.costPerKg : 0,
      newCostPerKg: pvc.costPerKg,
      historicalTotalCost: existing?.historicalTotalCost || 0,
      calculatedPvc: pvc.calculatedPvc,
      calculatedDensity: pvc.calculatedDensity,
      currency: formula.currency || 'EGP',
      ingredientsSnapshotJson: formula.ingredientsJson,
      encryptedSnapshotCiphertext: formula.confidentialCiphertext || '',
      createdBy: params.actor,
      approvedBy: '',
      approvedAt: 0,
      createdAt: now
    };

    await this.upsertDocument('lab_formulations', docId, formula, undefined, { actorUsername: params.actor });
    await this.upsertDocument('lab_formula_summaries', docId, this.buildNonSecretFormulaSummary(formula), undefined, {
      actorUsername: params.actor
    }).catch(() => {});
    await this.upsertDocument('lab_formula_versions', versionRecord.syncUuid, versionRecord, undefined, {
      actorUsername: params.actor
    });

    await this.logAudit(
      params.actor,
      isRevision ? 'LAB_FORMULA_REVISION' : 'LAB_FORMULA_CREATE',
      sanitizeLabLog(
        `${isRevision ? 'تحديث إصدار' : 'إنشاء'} تركيبة ${formulaNo} v${nextVersion} (${formula.productName}) بالحالة NEEDS_REVIEW`
      )
    );

    return { formula, version: versionRecord, docId };
  }

  async decideFormulationApproval(params: {
    formula: LabFormulation;
    decision: 'APPROVED' | 'REJECTED' | 'NEEDS_REVIEW';
    actor: string;
    reason?: string;
  }): Promise<LabFormulation> {
    const now = Date.now();
    const docId = (params.formula as any)._docId || safeId(params.formula.formulaNo, `FORM-${params.formula.id}`);
    const isApproved = params.decision === 'APPROVED';
    const updated: LabFormulation = {
      ...params.formula,
      approvalStatus: params.decision,
      approvedForProduction: isApproved,
      approvedBy: isApproved ? params.actor : '',
      approvedAt: isApproved ? now : 0,
      historicalTotalCost: isApproved
        ? params.formula.totalCost
        : params.formula.historicalTotalCost || 0,
      historicalCostPerKg: isApproved
        ? params.formula.costPerKg
        : params.formula.historicalCostPerKg || 0,
      changeReason: (params.reason || '').trim() || params.formula.changeReason || '',
      updatedBy: params.actor,
      updatedAt: now
    };

    await this.upsertDocument('lab_formulations', docId, updated, undefined, { actorUsername: params.actor });
    await this.upsertDocument('lab_formula_summaries', docId, this.buildNonSecretFormulaSummary(updated), undefined, {
      actorUsername: params.actor
    }).catch(() => {});

    const verSnapshot: FormulationVersion = {
      syncUuid: `VER-${updated.formulaNo}-v${updated.versionNumber || 1}-${params.decision}-${now}`,
      formulaId: updated.id,
      formulaNo: updated.formulaNo,
      productName: updated.productName,
      versionNumber: updated.versionNumber || 1,
      previousVersionNumber: updated.versionNumber || 1,
      status: params.decision,
      changeReason: (params.reason || '').trim() || `قرار الاعتماد: ${params.decision}`,
      diffSummaryJson: JSON.stringify({ approvalDecision: params.decision }),
      previousCostPerKg: params.formula.costPerKg,
      newCostPerKg: updated.costPerKg,
      historicalTotalCost: updated.historicalTotalCost || updated.totalCost,
      calculatedPvc: updated.calculatedPvc,
      calculatedDensity: updated.calculatedDensity,
      currency: updated.currency || 'EGP',
      ingredientsSnapshotJson: updated.isConfidentialSealed ? '[]' : updated.ingredientsJson,
      encryptedSnapshotCiphertext: updated.confidentialCiphertext || '',
      createdBy: params.actor,
      approvedBy: isApproved ? params.actor : '',
      approvedAt: isApproved ? now : 0,
      createdAt: now
    };
    await this.upsertDocument('lab_formula_versions', verSnapshot.syncUuid, verSnapshot, undefined, {
      actorUsername: params.actor
    }).catch(() => {});

    await this.logAudit(
      params.actor,
      `LAB_FORMULA_${params.decision}`,
      sanitizeLabLog(`قرار ${params.decision} للتركيبة ${updated.formulaNo} v${updated.versionNumber || 1}: ${params.reason || ''}`)
    );
    return updated;
  }

  async recordLabExperiment(params: {
    formula: LabFormulation;
    experimentDate: string;
    technicianName: string;
    measuredDensity: number;
    measuredViscosity: string;
    measuredPh: number;
    measuredGrindMicrons: number;
    hidingPowerPercent: number;
    notes: string;
    actor: string;
  }): Promise<LabExperiment> {
    const now = Date.now();
    const expNo = `EXP-${String(now).slice(-6)}`;
    const syncUuid = `EXP-${params.formula.formulaNo}-${now}`;
    const record: LabExperiment = {
      id: now,
      syncUuid,
      experimentNo: expNo,
      formulaId: params.formula.id,
      formulaNo: params.formula.formulaNo,
      versionNumber: params.formula.versionNumber || 1,
      productId: params.formula.linkedProductId ?? null,
      productName: params.formula.productName,
      experimentDate: params.experimentDate || new Date(now).toISOString().split('T')[0],
      technicianName: params.technicianName.trim() || params.actor,
      materialsUsedJson: '[]', // Keep confidential formula breakdown redacted out of experiment records
      measuredDensity: Number(params.measuredDensity) || 0,
      measuredViscosity: params.measuredViscosity.trim(),
      measuredPh: Number(params.measuredPh) || 0,
      measuredGrindMicrons: Number(params.measuredGrindMicrons) || 0,
      hidingPowerPercent: Number(params.hidingPowerPercent) || 0,
      notes: sanitizeLabLog(params.notes.trim()),
      status: 'PENDING',
      decision: 'PENDING',
      decidedBy: '',
      decidedAt: 0,
      idempotencyKey: syncUuid,
      createdAt: now,
      updatedAt: now
    };
    await this.upsertDocument('lab_experiments', syncUuid, record, undefined, { actorUsername: params.actor });
    await this.logAudit(
      params.actor,
      'LAB_EXPERIMENT_CREATE',
      sanitizeLabLog(`تسجيل تجربة مخبرية ${expNo} للتركيبة ${params.formula.formulaNo} v${record.versionNumber}`)
    );
    return record;
  }

  async decideLabExperimentQc(params: {
    experiment: LabExperiment;
    decision: 'APPROVED' | 'REJECTED' | 'RETEST';
    actor: string;
    linkedQcNo?: string;
    notes?: string;
  }): Promise<LabExperiment> {
    const now = Date.now();
    const statusMap: Record<'APPROVED' | 'REJECTED' | 'RETEST', LabExperiment['status']> = {
      APPROVED: 'PASSED',
      REJECTED: 'FAILED',
      RETEST: 'RETEST_REQUIRED'
    };
    const updated: LabExperiment = {
      ...params.experiment,
      status: statusMap[params.decision],
      decision: params.decision,
      decidedBy: params.actor,
      decidedAt: now,
      linkedQcNo: params.linkedQcNo || params.experiment.linkedQcNo || '',
      notes: params.notes ? sanitizeLabLog(params.notes) : params.experiment.notes,
      updatedAt: now
    };
    const docId = (params.experiment as any)._docId || params.experiment.syncUuid;
    await this.upsertDocument('lab_experiments', docId, updated, undefined, { actorUsername: params.actor });
    await this.logAudit(
      params.actor,
      'LAB_EXPERIMENT_QC_DECISION',
      sanitizeLabLog(`قرار الجودة ${params.decision} للتجربة ${updated.experimentNo} (${updated.formulaNo})`)
    );
    return updated;
  }

  async updateRawMaterialRegistryPrice(params: {
    item: FactoryItem;
    newPrice: number;
    supplierName: string;
    sourceType: 'PURCHASE_GRN' | 'MANUAL_LAB' | 'EXCEL_IMPORT';
    sourceDocNo: string;
    currency?: string;
    actor: string;
  }): Promise<RawMaterialPriceHistory> {
    const now = Date.now();
    const prevPrice = Number(params.item.lastPurchasePrice || params.item.unitCostEgp || 0);
    const updatedItem: FactoryItem = {
      ...params.item,
      unitCostEgp: params.newPrice,
      lastPurchasePrice: params.newPrice,
      lastPriceUpdatedAt: now,
      supplierName: params.supplierName.trim() || params.item.supplierName || '',
      updatedAt: now
    };
    const itemDocId = (params.item as any)._docId || safeId(params.item.syncUuid || `FI-${params.item.id}`, `FI-${params.item.id}`);
    await this.upsertDocument('factory_items', itemDocId, updatedItem, undefined, { actorUsername: params.actor });

    const phSyncUuid = `PRC-${params.item.id}-${now}`;
    const hist: RawMaterialPriceHistory = {
      id: now,
      syncUuid: phSyncUuid,
      materialId: params.item.id,
      itemCode: params.item.itemCode || `RAW-${params.item.id}`,
      materialName: params.item.name,
      supplierName: updatedItem.supplierName || '',
      unitPrice: params.newPrice,
      previousPrice: prevPrice,
      currency: params.currency || params.item.defaultCurrency || 'EGP',
      sourceType: params.sourceType,
      sourceDocNo: params.sourceDocNo || '-',
      effectiveDate: new Date(now).toISOString().split('T')[0],
      recordedBy: params.actor,
      createdAt: now
    };
    await this.upsertDocument('raw_material_price_history', phSyncUuid, hist, undefined, { actorUsername: params.actor });
    return hist;
  }

  async commitStagedExcelImport(params: {
    report: ExcelWorkbookAnalysisReport;
    selectedCandidateIndices: number[];
    existingItems: FactoryItem[];
    actor: string;
  }): Promise<{ importedCount: number; createdMaterialsCount: number; batch: LabExcelImportBatch }> {
    const now = Date.now();
    const currentItems = [...params.existingItems];
    let createdMaterialsCount = 0;
    let importedCount = 0;

    for (const cIdx of params.selectedCandidateIndices) {
      const candidate = params.report.candidates[cIdx];
      if (!candidate) continue;

      const resolvedIngredients: FormulationIngredient[] = [];
      for (const ing of candidate.ingredients) {
        let matched = currentItems.find(
          (fi) =>
            (ing.materialId && Number(fi.id) === Number(ing.materialId)) ||
            (ing.itemCode && (fi.itemCode || '').toLowerCase() === ing.itemCode.toLowerCase()) ||
            fi.name.trim().toLowerCase() === ing.name.trim().toLowerCase()
        );
        if (!matched) {
          const newId = Date.now() + createdMaterialsCount + Math.floor(Math.random() * 100);
          const itemCode = ing.itemCode || `RM-XLS-${String(newId).slice(-5)}`;
          matched = {
            id: newId,
            syncUuid: `FI-${newId}`,
            itemCode,
            name: ing.name,
            unit: 'كجم',
            category: 'خامات إنتاج',
            defaultCurrency: 'EGP',
            unitCostEgp: ing.unitCost,
            lastPurchasePrice: ing.unitCost,
            lastPriceUpdatedAt: now,
            density: ing.density,
            solidsPercent: ing.solidsPercent,
            chemicalType: ing.type,
            updatedAt: now
          };
          await this.upsertDocument('factory_items', matched.syncUuid!, matched, undefined, { actorUsername: params.actor });
          currentItems.push(matched);
          createdMaterialsCount++;
        }
        resolvedIngredients.push({
          ...ing,
          materialId: matched.id,
          itemCode: matched.itemCode || ing.itemCode || ''
        });
      }

      await this.saveFormulationWithVersioning({
        productName: candidate.productName,
        paintType: candidate.paintType,
        baseKind: candidate.baseKind,
        notes: `[استيراد إكسل: ${params.report.fileName} / ${candidate.sheetName}] ${candidate.warnings.join(' | ')}`,
        ingredients: resolvedIngredients,
        changeReason: `استيراد أولي من Excel (${candidate.formulaKind}) — يحتاج مراجعة واعتماد`,
        actor: params.actor
      });
      importedCount++;
    }

    const importNo = `XLS-${String(now).slice(-6)}`;
    const batch: LabExcelImportBatch = {
      id: now,
      syncUuid: importNo,
      importNo,
      fileName: params.report.fileName,
      sheetCount: params.report.sheets.length,
      formulasDetected: params.report.candidates.length,
      formulasImported: importedCount,
      materialsMatched: params.report.materialsMatchedCount,
      materialsCreated: createdMaterialsCount,
      externalLinksDetected: params.report.externalLinksDetected,
      brokenRefsDetected: params.report.brokenRefsDetected,
      warningsCount: params.report.warningsCount,
      approvalStatus: 'NEEDS_REVIEW',
      checksumSha256: params.report.checksumSha256,
      restrictedSummaryJson: JSON.stringify(params.report.nonSecretSummary),
      importedBy: params.actor,
      idempotencyKey: `IMP-${params.report.checksumSha256.slice(0, 16)}-${now}`,
      createdAt: now
    };
    await this.upsertDocument('lab_excel_imports', importNo, batch, undefined, { actorUsername: params.actor });
    await this.logAudit(
      params.actor,
      'LAB_EXCEL_IMPORT',
      sanitizeLabLog(`استيراد ملف (${params.report.fileName}): ${importedCount} تركيبة بحالة NEEDS_REVIEW (checksum=${params.report.checksumSha256.slice(0, 12)})`)
    );
    return { importedCount, createdMaterialsCount, batch };
  }
}

export const ErpDataService = new ErpDataServiceImpl();

export type {
  Cashbox,
  TreasuryMovement,
  InterTreasuryTransfer,
  TreasuryTransfer,
  DailyClosing,
  Warehouse,
  Product,
  StockMove,
  FactoryItem,
  RawMaterialItem,
  FactoryMachine,
  Machine,
  FactoryMove,
  ProductionBatch,
  LabFormulation,
  ChemicalFormulation,
  ChemicalSafety,
  HalfaInvoice,
  PosterPrice,
  SalesInvoice,
  InvoicePayment,
  Customer,
  AuditLog,
  WarehouseClass,
  InventoryItemCategory,
  InventoryMovementType,
  MovementRecordStatus,
  WarehouseTransferStatus,
  InventoryMovement,
  WarehouseStockBalance,
  WarehouseTransfer,
  WarehouseTransferItem,
  PackagingRule,
  PurchaseReceipt,
  ProductionOrder,
  QcInspection,
  AccountType,
  ChartAccount,
  JournalEntry,
  JournalLine,
  TrialBalanceRow,
  TrialBalanceReport,
  IncomeStatementReport,
  BalanceSheetReport,
  ReconciliationItem,
  CurrencyFxRates,
  ConsolidatedFxSummary,
  FormulationIngredient,
  FormulationVersion,
  LabExperiment,
  RawMaterialPriceHistory,
  LabExcelImportBatch,
  ThreeCostComparisonReport
};
