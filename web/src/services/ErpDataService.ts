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
  getDocsFromServer,
  limit,
  query,
  setDoc,
  deleteDoc,
  onSnapshot,
  serverTimestamp,
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
  UserAccount
} from '../types/erp';
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
        CloudErrorLogger.recordError(`SUBSCRIBE_${String(collectionName).toUpperCase()}`, path, err);
        console.error(`Firestore subscription error on [${collectionName}]:`, sanitizeCloudMessage(err));
        if (onError) onError(err);
      }
    );

    return () => {
      this.activeCollectionRefreshers.delete(refilterAndEmit);
      unsubPrimary();
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

  /**
   * Upserts a document into `/erp_clusters/PMP-ERP-MAIN-CLUSTER/{collectionName}/{docId}`
   * with `serverUpdatedAt` matching Android `CloudErpSyncRepository.kt`.
   */
  async upsertDocument<T extends Record<string, any>>(
    collectionName: ErpCollectionName | string,
    docId: string,
    payload: T,
    clusterOverride?: string
  ): Promise<string> {
    if (clusterOverride === REMOVED_V16_CLUSTER) {
      const err = new Error('الكتابة في الكلاستر المهجور PMP-ERP-V16-CLUSTER مرفوضة أمنياً');
      CloudErrorLogger.recordError('UPSERT_BLOCKED', `erp_clusters/${REMOVED_V16_CLUSTER}/${collectionName}`, err);
      throw err;
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

    // Enforce balanced journal entry invariant before touching Firestore
    if (collectionName === 'journal_entries') {
      const td = Number(dataToWrite.totalDebit ?? 0);
      const tc = Number(dataToWrite.totalCredit ?? 0);
      if (Math.abs(td - tc) >= 0.001) {
        const err = new Error('القيد المحاسبي غير متوازن: إجمالي المدين لا يساوي إجمالي الدائن.');
        CloudErrorLogger.recordError('UPSERT_JOURNAL_ENTRY', path, err);
        throw err;
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
    _actorUsername?: string
  ): Promise<string> {
    return this.upsertDocument(collectionName, docId, payload);
  }

  /**
   * Alias for `upsertDocument` used by Purchases, Factory, Lab, and Sales modules.
   */
  async saveRecord<T extends Record<string, any>>(
    collectionName: ErpCollectionName | string,
    docId: string,
    payload: T,
    _actorUsername?: string
  ): Promise<string> {
    return this.upsertDocument(collectionName, docId, payload);
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
      .filter((d) => d && d !== cleanId && /^(MOV|INV|TRF|CUST|WH|SM|FI|FM|PRD|PROD|BOX|CB)-/i.test(d));
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
    if (numericId !== undefined && numericId !== null) {
      await this.deleteDocumentWithTombstone(collectionName, docId, numericId, actorUsername || 'المدير');
    } else {
      await this.deleteDocument(collectionName, docId);
    }
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
  ConsolidatedFxSummary
};
