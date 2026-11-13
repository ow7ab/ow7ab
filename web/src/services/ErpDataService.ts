/**
 * PMP PAINTS Unified Cloud ERP Data Service
 * Connects the Web Platform 1:1 to the EXACT same Cloud Firestore database,
 * cluster path (`erp_clusters/{clusterCode}`), collection names, document ID conventions,
 * and ledger aggregation formulas used by Android (`CloudErpSyncRepository.kt` & `PmpViewModel.kt`).
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
  DEFAULT_CLUSTER_CODE,
  LEGACY_CLUSTER_CODE
} from '../config/firebaseConfig';
import {
  AuditLog,
  Cashbox,
  TreasuryMovement,
  TreasuryTransfer,
  DailyClosing,
  Product,
  Warehouse,
  StockMove,
  Customer,
  SalesInvoice,
  InvoicePayment,
  Supplier,
  PurchaseInvoice,
  FactoryItem,
  FactoryMove,
  Machine,
  ProductionBatch,
  ChemicalFormulation,
  QualityInspection,
  PosterPrice
} from '../types/erp';
import {
  safeId,
  cashboxDocId,
  posterPriceDocId,
  deduplicateCollectionRecords,
  computeDistinctCashboxesWithBalances,
  computeCashboxBalances,
  computeProductStocks,
  computeFactoryItemBalances,
  computeInvoiceFinancials,
  computeCustomerDebts,
  computeSupplierBalances
} from './ErpCalculations';

export {
  safeId,
  cashboxDocId,
  posterPriceDocId,
  deduplicateCollectionRecords,
  computeDistinctCashboxesWithBalances,
  computeCashboxBalances,
  computeProductStocks,
  computeFactoryItemBalances,
  computeInvoiceFinancials,
  computeCustomerDebts,
  computeSupplierBalances
};

export const PRIMARY_CLUSTER = DEFAULT_CLUSTER_CODE; // "PMP-ERP-MAIN-CLUSTER"
export const LEGACY_V16_CLUSTER = LEGACY_CLUSTER_CODE; // "PMP-ERP-V16-CLUSTER"

const CLUSTER_STORAGE_KEY = 'pmp_active_erp_cluster';

function normalizeTimestampToDateString(val: any): string {
  if (!val) return '';
  if (typeof val === 'string') return val;
  if (typeof val === 'number') {
    return new Date(val).toISOString().split('T')[0];
  }
  if (val && typeof val.seconds === 'number') {
    return new Date(val.seconds * 1000).toISOString().split('T')[0];
  }
  return String(val);
}

function normalizeFirestoreDoc<T>(docId: string, raw: Record<string, any>, indexFallback: number): T {
  const numericId =
    typeof raw.id === 'number'
      ? raw.id
      : typeof raw.id === 'string' && /^\d+$/.test(raw.id)
      ? Number(raw.id)
      : indexFallback;

  const normalized: Record<string, any> = {
    ...raw,
    id: numericId,
    _docId: docId
  };

  if (raw.serverUpdatedAt && typeof raw.serverUpdatedAt.seconds === 'number') {
    normalized.updatedAt = raw.serverUpdatedAt.seconds * 1000;
  }
  if (!normalized.txDate && raw.date) {
    normalized.txDate = normalizeTimestampToDateString(raw.date);
  }
  if (!normalized.invoiceDate && raw.date) {
    normalized.invoiceDate = normalizeTimestampToDateString(raw.date);
  }
  if (!normalized.moveDate && raw.date) {
    normalized.moveDate = normalizeTimestampToDateString(raw.date);
  }

  return normalized as T;
}

export interface ClusterHealthReport {
  cluster: string;
  ok: boolean;
  latencyMs: number;
  counts: Record<string, number>;
  error?: string;
}

class ErpDataServiceImpl {
  private currentCluster: string;

  constructor() {
    const saved =
      typeof localStorage !== 'undefined' ? localStorage.getItem(CLUSTER_STORAGE_KEY) : null;
    this.currentCluster =
      saved && saved.trim().length > 0 ? saved.trim().toUpperCase() : PRIMARY_CLUSTER;
  }

  get activeCluster(): string {
    return this.currentCluster;
  }

  setActiveCluster(clusterCode: string): void {
    const clean = (clusterCode || PRIMARY_CLUSTER).trim().toUpperCase();
    this.currentCluster = clean;
    if (typeof localStorage !== 'undefined') {
      localStorage.setItem(CLUSTER_STORAGE_KEY, clean);
    }
  }

  collectionPath(collectionName: string, clusterOverride?: string): string {
    const c = (clusterOverride || this.currentCluster).trim().toUpperCase();
    return `erp_clusters/${c}/${collectionName}`;
  }

  /**
   * Real-time Firestore listener for any Android ERP collection under `/erp_clusters/{activeCluster}/{collectionName}`.
   * Automatically deduplicates records on read and falls back to `LEGACY_V16_CLUSTER` if `PRIMARY_CLUSTER` is empty.
   */
  subscribeToCollection<T>(
    collectionName: string,
    onUpdate: (items: T[]) => void,
    onError?: (err: Error) => void
  ): Unsubscribe {
    const primaryCluster = this.currentCluster;
    const colRef = collection(firestore, this.collectionPath(collectionName, primaryCluster));
    let fallbackUnsub: Unsubscribe | null = null;

    const primaryUnsub = onSnapshot(
      colRef,
      (snapshot) => {
        if (!snapshot.empty) {
          if (fallbackUnsub) {
            fallbackUnsub();
            fallbackUnsub = null;
          }
          const items: T[] = [];
          let idx = 1;
          snapshot.forEach((docSnap) => {
            items.push(normalizeFirestoreDoc<T>(docSnap.id, docSnap.data(), idx++));
          });
          onUpdate(deduplicateCollectionRecords(collectionName, items));
        } else if (primaryCluster === PRIMARY_CLUSTER && !fallbackUnsub) {
          const legacyRef = collection(
            firestore,
            this.collectionPath(collectionName, LEGACY_V16_CLUSTER)
          );
          fallbackUnsub = onSnapshot(
            legacyRef,
            (legacySnap) => {
              const legacyItems: T[] = [];
              let idx = 1;
              legacySnap.forEach((docSnap) => {
                legacyItems.push(normalizeFirestoreDoc<T>(docSnap.id, docSnap.data(), idx++));
              });
              onUpdate(deduplicateCollectionRecords(collectionName, legacyItems));
            },
            () => {
              onUpdate([]);
            }
          );
        } else {
          onUpdate([]);
        }
      },
      (err) => {
        console.error(`Subscription error [${primaryCluster}/${collectionName}]:`, err);
        if (onError) onError(err);
      }
    );

    return () => {
      primaryUnsub();
      if (fallbackUnsub) fallbackUnsub();
    };
  }

  async fetchCollectionOnce<T>(collectionName: string, clusterOverride?: string): Promise<T[]> {
    const colRef = collection(firestore, this.collectionPath(collectionName, clusterOverride));
    const snap = await getDocs(colRef);
    if (snap.empty && !clusterOverride && this.currentCluster === PRIMARY_CLUSTER) {
      const legacySnap = await getDocs(
        collection(firestore, this.collectionPath(collectionName, LEGACY_V16_CLUSTER))
      );
      const legacyList: T[] = [];
      let idx = 1;
      legacySnap.forEach((d) =>
        legacyList.push(normalizeFirestoreDoc<T>(d.id, d.data(), idx++))
      );
      return deduplicateCollectionRecords(collectionName, legacyList);
    }
    const list: T[] = [];
    let idx = 1;
    snap.forEach((d) => list.push(normalizeFirestoreDoc<T>(d.id, d.data(), idx++)));
    return deduplicateCollectionRecords(collectionName, list);
  }

  async saveDocument<T extends Record<string, any>>(
    collectionName: string,
    docId: string,
    data: T
  ): Promise<void> {
    const cleanDocId = safeId(docId, `DOC-${Date.now()}`);
    const payload: Record<string, any> = {
      ...data,
      serverUpdatedAt: serverTimestamp()
    };
    delete payload._docId;

    const docRef = doc(firestore, this.collectionPath(collectionName), cleanDocId);
    await setDoc(docRef, payload, { merge: true });
  }

  async saveRecord<T extends Record<string, any>>(
    collectionName: string,
    docId: string,
    data: T,
    _actorUsername?: string
  ): Promise<void> {
    return this.saveDocument(collectionName, docId, data);
  }

  async deleteDocument(collectionName: string, docId: string): Promise<void> {
    const cleanDocId = safeId(docId, docId);
    const docRef = doc(firestore, this.collectionPath(collectionName), cleanDocId);
    await deleteDoc(docRef);
  }

  async deleteRecord(
    collectionName: string,
    docId: string,
    _numericId?: number | string,
    _actorUsername?: string
  ): Promise<void> {
    return this.deleteDocument(collectionName, docId);
  }

  subscribeTombstonesAndEpoch(): Unsubscribe {
    return () => {};
  }

  async logAudit(username: string, action: string, detail: string): Promise<void> {
    try {
      const ts = Date.now();
      const logId = `LOG-${ts}`;
      const logEntry: AuditLog = {
        id: ts,
        username: username || 'المدير',
        action,
        detail,
        timestamp: ts
      };
      await this.saveDocument('audit_logs', logId, logEntry);
    } catch (e) {
      console.warn('Audit log write skipped:', e);
    }
  }

  computeDistinctCashboxesWithBalances(
    cashboxes: Cashbox[],
    movements: TreasuryMovement[]
  ) {
    return computeDistinctCashboxesWithBalances(cashboxes, movements);
  }

  computeCashboxBalances(cashboxes: Cashbox[], movements: TreasuryMovement[]): Cashbox[] {
    return computeCashboxBalances(cashboxes, movements);
  }

  computeProductStocks(products: Product[], stockMoves: StockMove[]): Map<number, number> {
    return computeProductStocks(products, stockMoves);
  }

  computeFactoryItemBalances(
    items: FactoryItem[],
    moves: FactoryMove[]
  ): Map<number, number> {
    return computeFactoryItemBalances(items, moves);
  }

  computeInvoiceFinancials(
    invoice: SalesInvoice,
    externalPayments: InvoicePayment[]
  ): { paidAmount: number; remainingAmount: number; statusLabel: 'مدفوعة' | 'سداد جزئي' | 'غير مدفوعة' } {
    return computeInvoiceFinancials(invoice, externalPayments);
  }

  computeCustomerDebts(
    customers: Customer[],
    invoices: SalesInvoice[],
    payments: InvoicePayment[]
  ) {
    return computeCustomerDebts(customers, invoices, payments);
  }

  computeSupplierBalances(
    suppliers: Supplier[],
    purchases: PurchaseInvoice[]
  ) {
    return computeSupplierBalances(suppliers, purchases);
  }

  /**
   * Measures real round-trip cloud latency & collection counts for Settings diagnostic card.
   */
  async checkServerHealth(): Promise<ClusterHealthReport> {
    const start = Date.now();
    const counts: Record<string, number> = {};
    try {
      const cols = [
        'user_accounts',
        'cashboxes',
        'movements',
        'warehouses',
        'products',
        'customers',
        'invoices',
        'suppliers',
        'purchases',
        'factory_items',
        'paint_batches',
        'poster_prices'
      ];
      await Promise.all(
        cols.map(async (c) => {
          const items = await this.fetchCollectionOnce(c);
          counts[c] = items.length;
        })
      );
      return {
        cluster: this.currentCluster,
        ok: true,
        latencyMs: Date.now() - start,
        counts
      };
    } catch (err: any) {
      return {
        cluster: this.currentCluster,
        ok: false,
        latencyMs: Date.now() - start,
        counts,
        error: err?.message || 'فشل الاتصال بالخادم السحابي'
      };
    }
  }
}

export const ErpDataService = new ErpDataServiceImpl();

export type {
  Cashbox,
  TreasuryMovement,
  TreasuryTransfer,
  DailyClosing,
  Product,
  Warehouse,
  StockMove,
  Customer,
  SalesInvoice,
  InvoicePayment,
  Supplier,
  PurchaseInvoice,
  FactoryItem,
  FactoryMove,
  Machine,
  ProductionBatch,
  ChemicalFormulation,
  QualityInspection,
  PosterPrice
};
