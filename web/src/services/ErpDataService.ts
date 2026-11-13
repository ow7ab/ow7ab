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
  getDocs,
  setDoc,
  deleteDoc,
  onSnapshot,
  serverTimestamp,
  Unsubscribe
} from 'firebase/firestore';
import {
  firestore,
  DEFAULT_CLUSTER_CODE
} from '../config/firebaseConfig';
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
  CurrencyCode
} from '../types/erp';
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
  isStockInMoveKind
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
  | 'production_orders'
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
  | 'audit_logs'
  | 'permission_audit_logs';

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
    void this.purgeLegacyV16Cluster();
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
   * Purges the deprecated `PMP-ERP-V16-CLUSTER` from Firestore so only `PMP-ERP-MAIN-CLUSTER` remains.
   */
  async purgeLegacyV16Cluster(): Promise<void> {
    if (this.legacyPurged) return;
    this.legacyPurged = true;

    const collectionsToPurge: ErpCollectionName[] = [
      'user_accounts',
      'pending_requests',
      'deleted_users',
      'cashboxes',
      'movements',
      'transfers',
      'daily_closings',
      'warehouses',
      'products',
      'stock_moves',
      'factory_items',
      'factory_moves',
      'factory_machines',
      'paint_batches',
      'lab_formulations',
      'chemical_safety',
      'halfa_invoices',
      'poster_prices',
      'invoices',
      'invoice_payments',
      'customers',
      'audit_logs',
      'permission_audit_logs'
    ];

    try {
      for (const col of collectionsToPurge) {
        const snap = await getDocs(collection(firestore, `erp_clusters/${REMOVED_V16_CLUSTER}/${col}`));
        for (const d of snap.docs) {
          await deleteDoc(doc(firestore, `erp_clusters/${REMOVED_V16_CLUSTER}/${col}`, d.id)).catch(() => {});
        }
      }
      await deleteDoc(doc(firestore, 'erp_clusters', REMOVED_V16_CLUSTER)).catch(() => {});
    } catch {
      // Ignore permission or network issues during background cleanup
    }
  }

  /**
   * Subscribes in real-time to `/erp_clusters/PMP-ERP-MAIN-CLUSTER/{collectionName}`.
   * Automatically deduplicates records on read.
   */
  subscribeToCollection<T = any>(
    collectionName: ErpCollectionName | string,
    onData: (items: T[]) => void,
    onError?: (err: Error) => void
  ): Unsubscribe {
    const primaryColRef = collection(firestore, this.collectionPath(collectionName, PRIMARY_CLUSTER));

    const unsubPrimary = onSnapshot(
      primaryColRef,
      (snap) => {
        const primaryItems: T[] = [];
        snap.forEach((docSnap) => {
          primaryItems.push(normalizeDocData<T>(docSnap.id, docSnap.data()));
        });
        onData(deduplicateCollectionRecords<T>(collectionName, primaryItems));
      },
      (err) => {
        console.error(`Firestore subscription error on [${collectionName}]:`, err);
        if (onError) onError(err);
      }
    );

    return () => {
      unsubPrimary();
    };
  }

  /**
   * Subscribes to tombstones and cluster epoch changes in `/erp_clusters/PMP-ERP-MAIN-CLUSTER`.
   */
  subscribeTombstonesAndEpoch(): Unsubscribe {
    const unsub = onSnapshot(
      collection(firestore, this.collectionPath('deleted_users', PRIMARY_CLUSTER)),
      () => {},
      () => {}
    );
    return () => unsub();
  }

  /**
   * Fetches all documents once from `/erp_clusters/PMP-ERP-MAIN-CLUSTER/{collectionName}`.
   */
  async fetchCollectionOnce<T = any>(
    collectionName: ErpCollectionName | string,
    clusterOverride?: string
  ): Promise<T[]> {
    const targetCluster = clusterOverride || PRIMARY_CLUSTER;
    const colRef = collection(firestore, this.collectionPath(collectionName, targetCluster));
    const snap = await getDocs(colRef);
    const items: T[] = [];
    snap.forEach((d) => items.push(normalizeDocData<T>(d.id, d.data())));
    return deduplicateCollectionRecords<T>(collectionName, items);
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
    const cleanId = safeId(docId, `DOC-${Date.now()}`);
    const ref = doc(firestore, this.collectionPath(collectionName, clusterOverride), cleanId);
    const dataToWrite: Record<string, any> = { ...payload };
    delete dataToWrite._docId;

    await setDoc(
      ref,
      {
        ...dataToWrite,
        serverUpdatedAt: serverTimestamp()
      },
      { merge: true }
    );
    return cleanId;
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
   * Deletes a document from `/erp_clusters/PMP-ERP-MAIN-CLUSTER/{collectionName}/{docId}`.
   */
  async deleteDocument(
    collectionName: ErpCollectionName | string,
    docId: string,
    clusterOverride?: string
  ): Promise<void> {
    const cleanId = safeId(docId, docId);
    const ref = doc(firestore, this.collectionPath(collectionName, clusterOverride), cleanId);
    await deleteDoc(ref);
  }

  /**
   * Alias for `deleteDocument` used by modules passing `(collectionName, docId, numericId?, actor?)`.
   */
  async deleteRecord(
    collectionName: ErpCollectionName | string,
    docId: string,
    _numericId?: number | string,
    _actorUsername?: string
  ): Promise<void> {
    await this.deleteDocument(collectionName, docId);
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
      console.warn('Failed to write audit log:', err);
    }
  }

  /**
   * Performs a live health and record-count check on the active Firestore cluster.
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
      return {
        clusterCode: cluster,
        ok: false,
        latencyMs: Math.round(performance.now() - start),
        counts,
        error: err?.message || 'تعذر الاتصال بقاعدة البيانات السحابية'
      };
    }
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
    warehouseIdFilter?: number | null
  ): Map<number, number> {
    return computeProductStocks(products, stockMoves, warehouseIdFilter);
  }

  computeProductStocksList(
    products: Product[],
    stockMoves: StockMove[],
    warehouseIdFilter?: number | null
  ): Product[] {
    return computeProductStocksList(products, stockMoves, warehouseIdFilter);
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
    warehouseIdFilter?: number | null
  ): ColorWeightStockRow[] {
    return computeColorWeightStockRows(products, stockMoves, selectedWeight, warehouseIdFilter);
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
  AuditLog
};
