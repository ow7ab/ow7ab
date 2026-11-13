import {
  collection,
  doc,
  getDocs,
  getDoc,
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
  Cashbox,
  TreasuryMovement,
  InterTreasuryTransfer,
  DailyClosing,
  Customer,
  SalesInvoice,
  InvoicePayment,
  Warehouse,
  Product,
  StockMove,
  FactoryItem,
  FactoryMachine,
  FactoryMove,
  HalfaInvoice,
  PosterPrice,
  AuditLog,
  LabFormulation,
  ChemicalSafety,
  ProductionBatch,
  CurrencyCode
} from '../types/erp';

import {
  ComputedCashboxBalance,
  javaHashHex,
  safeId,
  cashboxDocId,
  posterPriceDocId,
  computeCashboxBalances,
  computeProductStocks,
  computeFactoryItemBalances,
  computeInvoiceFinancials,
  computeCustomerDebts
} from './ErpCalculations';

export {
  javaHashHex,
  safeId,
  cashboxDocId,
  posterPriceDocId
};
export type { ComputedCashboxBalance };

export const PRIMARY_CLUSTER = DEFAULT_CLUSTER_CODE;
export const LEGACY_V16_CLUSTER = LEGACY_CLUSTER_CODE;

const CLUSTER_STORAGE_KEY = 'pmp_active_cluster_code';

export interface ClusterHealthReport {
  ok: boolean;
  cluster: string;
  latencyMs: number;
  counts: Record<string, number>;
  error?: string;
}

// In-memory Distributed Tombstone & Epoch cache for the active cluster
const tombstonesByCollection = new Map<string, Set<string>>();
let activeEpochTimestamp = 0;

function normalizeTombKey(val: string): string {
  return String(val || '').trim().toLowerCase();
}

export function isRecordTombstoned(collectionName: string, docId: string, item: any): boolean {
  const colKey = normalizeTombKey(collectionName);
  const deletedSet = tombstonesByCollection.get(colKey);
  if (deletedSet) {
    const candidates = [
      docId,
      item?.id != null ? String(item.id) : '',
      item?.userCode,
      item?.invoiceNo,
      item?.name
    ].filter(Boolean);

    for (const c of candidates) {
      const n = normalizeTombKey(c);
      if (deletedSet.has(n)) return true;
      // Strip common Android prefixes for comparison
      const stripped = n.replace(/^(mov|cust|inv|trf|prod|box|fi|fm|sm|wh|close|hinv|form|batch|usr)-/i, '');
      if (stripped && deletedSet.has(stripped)) return true;
    }
  }

  const createdAt = Number(item?.createdAt || 0);
  if (createdAt >= 1_600_000_000_000 && activeEpochTimestamp > 0 && createdAt < activeEpochTimestamp) {
    return true;
  }
  return false;
}

/**
 * Enterprise ErpDataService — Single Source of Truth matching Android Room + CloudErpSyncRepository.kt
 */
export const ErpDataService = {
  activeCluster:
    (typeof localStorage !== 'undefined' && localStorage.getItem(CLUSTER_STORAGE_KEY)) ||
    PRIMARY_CLUSTER,

  setActiveCluster(clusterCode: string) {
    const valid = clusterCode === LEGACY_V16_CLUSTER ? LEGACY_V16_CLUSTER : PRIMARY_CLUSTER;
    this.activeCluster = valid;
    if (typeof localStorage !== 'undefined') {
      localStorage.setItem(CLUSTER_STORAGE_KEY, valid);
    }
  },

  getClusterDocPath(cluster?: string) {
    return `erp_clusters/${cluster || ErpDataService.activeCluster}`;
  },

  /**
   * Starts background listener on `deleted_records` and `system_metadata/cluster_epoch`
   * matching Android `DeletedRecordsRegistry`.
   */
  subscribeTombstonesAndEpoch(onChange?: () => void): Unsubscribe {
    const cluster = this.activeCluster;
    const tombRef = collection(firestore, `erp_clusters/${cluster}/deleted_records`);
    const epochRef = doc(firestore, `erp_clusters/${cluster}/system_metadata`, 'cluster_epoch');

    const unsubTomb = onSnapshot(
      tombRef,
      (snap) => {
        tombstonesByCollection.clear();
        snap.forEach((d) => {
          const data = d.data() || {};
          const col = normalizeTombKey(data.collection || data.collectionName || '');
          if (!col) return;
          if (!tombstonesByCollection.has(col)) tombstonesByCollection.set(col, new Set());
          const set = tombstonesByCollection.get(col)!;
          if (data.entityId) set.add(normalizeTombKey(String(data.entityId)));
          if (data.originalId) set.add(normalizeTombKey(String(data.originalId)));
          if (data.docId) set.add(normalizeTombKey(String(data.docId)));
        });
        if (onChange) onChange();
      },
      () => {}
    );

    const unsubEpoch = onSnapshot(
      epochRef,
      (snap) => {
        if (snap.exists()) {
          const ts = Number(snap.data()?.wipeTimestamp || 0);
          if (Number.isFinite(ts) && ts > activeEpochTimestamp) {
            activeEpochTimestamp = ts;
            if (onChange) onChange();
          }
        }
      },
      () => {}
    );

    return () => {
      unsubTomb();
      unsubEpoch();
    };
  },

  /**
   * Real-time two-way collection subscription against `/erp_clusters/{activeCluster}/{collectionName}`.
   * Properly propagates empty snapshots when records are cleared/deleted and filters tombstones.
   */
  subscribeToCollection<T>(
    collectionName: string,
    onUpdate: (items: T[]) => void,
    onError?: (err: Error) => void
  ): Unsubscribe {
    try {
      const cluster = this.activeCluster;
      const colRef = collection(firestore, `erp_clusters/${cluster}/${collectionName}`);
      return onSnapshot(
        colRef,
        (snapshot) => {
          const items: T[] = [];
          snapshot.forEach((docSnap) => {
            const raw = docSnap.data() || {};
            if (isRecordTombstoned(collectionName, docSnap.id, raw)) {
              return;
            }
            const parsedId =
              typeof raw.id === 'number'
                ? raw.id
                : typeof raw.id === 'string' && /^\d+$/.test(raw.id)
                ? Number(raw.id)
                : raw.id ?? docSnap.id;

            items.push({
              ...raw,
              id: parsedId,
              _docId: docSnap.id
            } as unknown as T);
          });
          onUpdate(items);
        },
        (err) => {
          console.error(`Firestore subscription error [${cluster}/${collectionName}]:`, err);
          if (onError) onError(err);
        }
      );
    } catch (err: any) {
      console.error(`Sync listener init error for ${collectionName}:`, err);
      if (onError) onError(err);
      return () => {};
    }
  },

  async fetchCollectionOnce<T>(collectionName: string, cluster?: string): Promise<T[]> {
    const targetCluster = cluster || this.activeCluster;
    const colRef = collection(firestore, `erp_clusters/${targetCluster}/${collectionName}`);
    const snap = await getDocs(colRef);
    const items: T[] = [];
    snap.forEach((docSnap) => {
      const raw = docSnap.data() || {};
      if (!isRecordTombstoned(collectionName, docSnap.id, raw)) {
        const parsedId =
          typeof raw.id === 'number'
            ? raw.id
            : typeof raw.id === 'string' && /^\d+$/.test(raw.id)
            ? Number(raw.id)
            : raw.id ?? docSnap.id;
        items.push({ ...raw, id: parsedId, _docId: docSnap.id } as unknown as T);
      }
    });
    return items;
  },

  /**
   * Bidirectional Write Operation to active cluster + legacy mirror if needed.
   * Rejects non-ASCII docIds automatically via `safeId` so `firestore.rules` always accepts writes.
   */
  async saveRecord(
    collectionName: string,
    rawDocId: string,
    data: Record<string, any>,
    actor: string = 'WEB_USER'
  ): Promise<boolean> {
    const cleanDocId = safeId(String(rawDocId), `DOC-${Date.now()}`);
    const payload = {
      ...data,
      serverUpdatedBy: actor,
      serverUpdatedAt: serverTimestamp(),
      syncOrigin: 'PMP_UNIFIED_WEB_PORTAL',
      clusterCode: this.activeCluster
    };

    const targetCluster = this.activeCluster;
    const primaryRef = doc(firestore, `erp_clusters/${targetCluster}/${collectionName}`, cleanDocId);
    await setDoc(primaryRef, payload, { merge: true });

    // Mirror to secondary cluster silently for mixed V16/Main Android device compatibility
    const otherCluster = targetCluster === PRIMARY_CLUSTER ? LEGACY_V16_CLUSTER : PRIMARY_CLUSTER;
    setDoc(doc(firestore, `erp_clusters/${otherCluster}/${collectionName}`, cleanDocId), payload, {
      merge: true
    }).catch(() => {});

    return true;
  },

  /**
   * Deletes record from active cluster AND creates an Android-compatible tombstone in `deleted_records`
   * so older Android devices do not re-upload deleted rows.
   */
  async deleteRecord(
    collectionName: string,
    rawDocId: string,
    entityId?: string | number,
    actor: string = 'WEB_ADMIN'
  ): Promise<boolean> {
    const cleanDocId = safeId(String(rawDocId), String(entityId || Date.now()));
    const resolvedEntityId = String(entityId ?? cleanDocId);
    const tombstoneDocId = safeId(`TOMB-${collectionName}-${cleanDocId}`, `TOMB-${Date.now()}`);

    const tombstoneData = {
      collection: collectionName,
      entityId: resolvedEntityId,
      docId: cleanDocId,
      deletedAt: Date.now(),
      deletedBy: actor,
      serverUpdatedAt: serverTimestamp()
    };

    const targetCluster = this.activeCluster;
    await deleteDoc(doc(firestore, `erp_clusters/${targetCluster}/${collectionName}`, cleanDocId));
    await setDoc(doc(firestore, `erp_clusters/${targetCluster}/deleted_records`, tombstoneDocId), tombstoneData, {
      merge: true
    });

    // Update local tombstone cache immediately
    const colKey = normalizeTombKey(collectionName);
    if (!tombstonesByCollection.has(colKey)) tombstonesByCollection.set(colKey, new Set());
    tombstonesByCollection.get(colKey)!.add(normalizeTombKey(cleanDocId));
    tombstonesByCollection.get(colKey)!.add(normalizeTombKey(resolvedEntityId));

    return true;
  },

  /**
   * Appends an immutable entry to `/erp_clusters/{activeCluster}/audit_logs` matching Android.
   */
  async logAudit(username: string, action: string, detail: string): Promise<void> {
    try {
      const id = Date.now();
      const docId = `LOG-${id}-${Math.floor(Math.random() * 1000)}`;
      const entry: AuditLog = {
        id,
        username,
        action,
        detail,
        timestamp: id
      };
      await setDoc(doc(firestore, `erp_clusters/${this.activeCluster}/audit_logs`, docId), {
        ...entry,
        serverUpdatedAt: serverTimestamp()
      });
    } catch (err) {
      console.warn('Audit log write skipped:', err);
    }
  },

  /**
   * Measures real round-trip Firestore latency & collection counts for Settings diagnostic card.
   */
  async checkServerHealth(): Promise<ClusterHealthReport> {
    const start = performance.now();
    const cluster = this.activeCluster;
    const collectionsToCheck = [
      'user_accounts',
      'cashboxes',
      'movements',
      'invoices',
      'customers',
      'products',
      'stock_moves',
      'factory_items',
      'factory_moves',
      'lab_formulations',
      'paint_batches',
      'poster_prices'
    ];
    try {
      const counts: Record<string, number> = {};
      await Promise.all(
        collectionsToCheck.map(async (col) => {
          const snap = await getDocs(collection(firestore, `erp_clusters/${cluster}/${col}`));
          counts[col] = snap.size;
        })
      );
      const latencyMs = Math.round(performance.now() - start);
      return { ok: true, cluster, latencyMs, counts };
    } catch (err: any) {
      return {
        ok: false,
        cluster,
        latencyMs: Math.round(performance.now() - start),
        counts: {},
        error: err?.message || 'فشل الاتصال بقاعدة البيانات السحابية'
      };
    }
  },

  // ===========================================================================
  // Android Parity Business Calculations (PmpDao.kt & PmpRepository.kt)
  // ===========================================================================

  /**
   * Computes live cashbox balances per (cashbox, currency) from `movements`,
   * matching `PmpDao.getAggregatedCashboxBalances()` SQL query.
   */
  computeCashboxBalances,
  computeProductStocks,
  computeFactoryItemBalances,
  computeInvoiceFinancials,
  computeCustomerDebts
};

export type { FactoryMachine };
