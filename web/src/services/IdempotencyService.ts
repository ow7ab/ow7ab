/**
 * PMP PAINTS Enterprise ERP — Unified Duplicate Transaction Prevention & Idempotency System
 *
 * Components:
 * 1. TransactionSubmissionGuard: Synchronous multi-click, multi-submit, re-render & same-document lock.
 * 2. IdempotencyProtectionSystem: Atomic Firestore `runTransaction` idempotency claim/replay engine,
 *    network-timeout PENDING_CONFIRMATION recovery, and startup queue reconciliation.
 */

import {
  doc,
  getDoc,
  setDoc,
  runTransaction,
  serverTimestamp,
  Transaction
} from 'firebase/firestore';
import { firestore, DEFAULT_CLUSTER_CODE } from '../config/firebaseConfig';

export type IdempotencyOperationType =
  | 'SAVE'
  | 'CREATE'
  | 'RAW_MATERIAL_SUPPLY'
  | 'GOODS_ISSUE'
  | 'STOCK_TRANSFER'
  | 'APPROVE_INVOICE'
  | 'PAYMENT'
  | 'RECEIPT_VOUCHER'
  | 'PAYMENT_VOUCHER'
  | 'UPDATE'
  | 'DELETE';

export type IdempotencyRequestStatus =
  | 'DRAFT'
  | 'PROCESSING'
  | 'PENDING_CONFIRMATION'
  | 'COMPLETED'
  | 'FAILED'
  | 'CANCELLED';

export const OPERATION_LOADING_LABELS: Record<IdempotencyOperationType, string> = {
  SAVE: 'جاري الحفظ...',
  CREATE: 'جاري إضافة العملية...',
  RAW_MATERIAL_SUPPLY: 'جاري توريد الخامات...',
  GOODS_ISSUE: 'جاري تنفيذ الصرف...',
  STOCK_TRANSFER: 'جاري التحويل...',
  APPROVE_INVOICE: 'جاري اعتماد الفاتورة...',
  PAYMENT: 'جاري تنفيذ السداد...',
  RECEIPT_VOUCHER: 'جاري تسجيل سند القبض...',
  PAYMENT_VOUCHER: 'جاري تسجيل سند الصرف...',
  UPDATE: 'جاري تحديث البيانات...',
  DELETE: 'جاري تنفيذ الحذف...'
};

export const IDEMPOTENCY_STATUS_MESSAGES = {
  PROCESSING_WAIT: 'جاري تنفيذ العملية، يرجى الانتظار...',
  SLOW_CHECKING: 'الاتصال بطيء، يتم التحقق من حالة العملية...',
  COMPLETED_SUCCESS: 'تم تنفيذ العملية بنجاح.',
  FAILED_REVIEW: 'تعذر تنفيذ العملية، يرجى مراجعة التفاصيل.',
  UNCERTAIN_DO_NOT_RESUBMIT: 'لم يتم التأكد من نتيجة العملية بعد، لا تعِد إدخالها.',
  ALREADY_RECORDED: 'العملية مسجلة مسبقاً.'
} as const;

export const OPERATION_TYPE_LABELS_AR: Record<IdempotencyOperationType, string> = {
  SAVE: 'حفظ بيانات',
  CREATE: 'إضافة عملية',
  RAW_MATERIAL_SUPPLY: 'توريد خامات',
  GOODS_ISSUE: 'صرف بضاعة / مواد',
  STOCK_TRANSFER: 'تحويل مخزني / مالي',
  APPROVE_INVOICE: 'اعتماد فاتورة / مستند',
  PAYMENT: 'سداد دفعة',
  RECEIPT_VOUCHER: 'سند قبض',
  PAYMENT_VOUCHER: 'سند صرف',
  UPDATE: 'تعديل بيانات',
  DELETE: 'حذف مسموح'
};

export const REQUEST_STATUS_LABELS_AR: Record<IdempotencyRequestStatus, string> = {
  DRAFT: 'مسودة',
  PROCESSING: 'قيد التنفيذ',
  PENDING_CONFIRMATION: 'بانتظار تأكيد الخادم',
  COMPLETED: 'مكتملة بنجاح',
  FAILED: 'تعذر التنفيذ',
  CANCELLED: 'ملغاة'
};

export interface IdempotencyRecord {
  idempotencyKey: string;
  userId: string;
  operationType: IdempotencyOperationType;
  documentReference: string;
  requestStatus: IdempotencyRequestStatus;
  createdAt: number;
  updatedAt: number;
  completionTime: number | null;
  resultReference: string | null;
  errorDetails: string | null;
  targetCollection: string;
  payloadSummary: string;
  platform: 'WEB' | 'DESKTOP' | 'ANDROID';
  guardKey?: string;
}

export interface StorageLike {
  getItem(key: string): string | null;
  setItem(key: string, value: string): void;
  removeItem?(key: string): void;
}

export interface BackendIdempotencyAdapter {
  claimOrGet(
    record: IdempotencyRecord,
    leaseMs: number
  ): Promise<{ outcome: 'CLAIMED' | 'ALREADY_COMPLETED' | 'IN_PROGRESS_REMOTE'; existing?: IdempotencyRecord }>;
  markCompleted(
    idempotencyKey: string,
    resultReference: string,
    completionTime: number,
    operationType?: IdempotencyOperationType | string
  ): Promise<void>;
  markFailed(idempotencyKey: string, errorDetails: string): Promise<void>;
  markPendingConfirmation(idempotencyKey: string, errorDetails: string): Promise<void>;
  getServerRecord(idempotencyKey: string): Promise<IdempotencyRecord | null>;
  checkTargetDocumentExists?(collectionName: string, docId: string): Promise<boolean>;
}

export interface ExecuteIdempotentOptions<T = unknown> {
  guardKey: string;
  operationType: IdempotencyOperationType;
  userId: string;
  documentReference: string;
  targetCollection: string;
  payloadSummary: string;
  idempotencyKey?: string;
  timeoutMs?: number;
  slowThresholdMs?: number;
  onStatusMessage?: (msg: string, status: IdempotencyRequestStatus) => void;
  execute: (context: { idempotencyKey: string; txHelper: typeof firestore }) => Promise<{ result?: T; resultReference?: string } | T>;
}

export interface ExecuteIdempotentResult<T = unknown> {
  ok: boolean;
  replayed: boolean;
  blocked: boolean;
  uncertain: boolean;
  status: IdempotencyRequestStatus;
  idempotencyKey: string;
  resultReference: string | null;
  message: string;
  errorDetails?: string | null;
  data?: T;
}

const STORAGE_KEY_RECORDS = 'pmp_erp_idempotency_queue_v1';
const STORAGE_KEY_DRAFTS = 'pmp_erp_idempotency_drafts_v1';
const DEFAULT_LEASE_MS = 45_000;
const DEFAULT_TIMEOUT_MS = 18_000;
const DEFAULT_SLOW_THRESHOLD_MS = 3_500;

function detectPlatform(): 'WEB' | 'DESKTOP' {
  try {
    if (
      typeof window !== 'undefined' &&
      ((window as unknown as Record<string, unknown>).pmpDesktop ||
        (typeof navigator !== 'undefined' && /Electron/i.test(navigator.userAgent)))
    ) {
      return 'DESKTOP';
    }
  } catch {
    // ignore in non-browser environment
  }
  return 'WEB';
}

function createMemoryStorage(): StorageLike {
  const mem = new Map<string, string>();
  return {
    getItem: (k) => mem.get(k) ?? null,
    setItem: (k, v) => {
      mem.set(k, v);
    },
    removeItem: (k) => {
      mem.delete(k);
    }
  };
}

function defaultStorage(): StorageLike {
  try {
    if (typeof window !== 'undefined' && window.localStorage) {
      return window.localStorage;
    }
    if (typeof globalThis !== 'undefined' && (globalThis as unknown as { localStorage?: StorageLike }).localStorage) {
      return (globalThis as unknown as { localStorage: StorageLike }).localStorage;
    }
  } catch {
    // ignore
  }
  return createMemoryStorage();
}

export function sanitizeIdempotencyKey(raw: string): string {
  const clean = String(raw || '')
    .trim()
    .replace(/[^a-zA-Z0-9_\-]/g, '-')
    .replace(/-+/g, '-');
  return clean.slice(0, 120) || generateIdempotencyKey('OP');
}

export function generateIdempotencyKey(prefix = 'OP'): string {
  const cleanPrefix = String(prefix || 'OP')
    .trim()
    .toUpperCase()
    .replace(/[^A-Z0-9_\-]/g, '')
    .slice(0, 24) || 'OP';
  let rand = '';
  try {
    if (typeof globalThis !== 'undefined' && globalThis.crypto?.randomUUID) {
      rand = globalThis.crypto.randomUUID().replace(/-/g, '').slice(0, 12).toUpperCase();
    }
  } catch {
    // ignore
  }
  if (!rand) {
    rand = Math.random().toString(36).slice(2, 10).toUpperCase() + Math.random().toString(36).slice(2, 6).toUpperCase();
  }
  return `IDEM-${cleanPrefix}-${Date.now()}-${rand}`;
}

export function isLikelyNetworkOrTimeoutError(err: unknown): boolean {
  if (!err) return false;
  const e = err as { code?: string; name?: string; message?: string };
  const code = String(e.code || '').toLowerCase();
  const name = String(e.name || '').toLowerCase();
  const msg = String(e.message || err || '').toLowerCase();

  if (
    code === 'unavailable' ||
    code === 'deadline-exceeded' ||
    code === 'aborted' ||
    code === 'network-request-failed' ||
    name === 'aborterror'
  ) {
    return true;
  }

  return (
    msg.includes('timeout') ||
    msg.includes('timed out') ||
    msg.includes('network') ||
    msg.includes('offline') ||
    msg.includes('failed to fetch') ||
    msg.includes('connection') ||
    msg.includes('unavailable') ||
    msg.includes('اتصال') ||
    msg.includes('انتهت مهلة')
  );
}

/**
 * Default Firestore adapter operating atomically on
 * `/erp_clusters/PMP-ERP-MAIN-CLUSTER/idempotency_requests/{idempotencyKey}`
 */
export function createFirestoreIdempotencyAdapter(clusterCode = DEFAULT_CLUSTER_CODE): BackendIdempotencyAdapter {
  const recordDocRef = (key: string) =>
    doc(firestore, `erp_clusters/${clusterCode}/idempotency_requests`, sanitizeIdempotencyKey(key));

  return {
    async claimOrGet(record: IdempotencyRecord, leaseMs: number) {
      const ref = recordDocRef(record.idempotencyKey);
      return await runTransaction(firestore, async (tx: Transaction) => {
        const snap = await tx.get(ref);
        const now = Date.now();
        if (snap.exists()) {
          const data = snap.data() as Partial<IdempotencyRecord>;
          const existing: IdempotencyRecord = {
            ...record,
            ...data,
            idempotencyKey: data.idempotencyKey || record.idempotencyKey
          };
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

        tx.set(
          ref,
          {
            idempotencyKey: record.idempotencyKey,
            userId: record.userId || 'SYSTEM',
            operationType: record.operationType,
            documentReference: record.documentReference,
            requestStatus: 'PROCESSING',
            createdAt: record.createdAt,
            updatedAt: now,
            completionTime: null,
            resultReference: null,
            errorDetails: null,
            targetCollection: record.targetCollection,
            payloadSummary: record.payloadSummary.slice(0, 500),
            platform: record.platform,
            serverUpdatedAt: serverTimestamp()
          },
          { merge: true }
        );
        return { outcome: 'CLAIMED' };
      });
    },

    async markCompleted(
      idempotencyKey: string,
      resultReference: string,
      completionTime: number,
      operationType?: IdempotencyOperationType | string
    ) {
      const ref = recordDocRef(idempotencyKey);
      await runTransaction(firestore, async (tx: Transaction) => {
        const snap = await tx.get(ref);
        if (snap.exists() && snap.data()?.requestStatus === 'COMPLETED') {
          return;
        }
        const existingData = snap.exists() ? snap.data() : null;
        const resolvedOpType =
          (existingData?.operationType && String(existingData.operationType).trim()) ||
          (operationType ? String(operationType).trim() : '') ||
          'SAVE';
        const payload: Record<string, unknown> = {
          idempotencyKey: sanitizeIdempotencyKey(idempotencyKey),
          operationType: resolvedOpType,
          requestStatus: 'COMPLETED',
          resultReference: String(resultReference || '').slice(0, 256),
          completionTime,
          updatedAt: completionTime,
          errorDetails: null,
          serverUpdatedAt: serverTimestamp()
        };
        if (!snap.exists()) {
          payload.createdAt = completionTime;
          payload.platform = 'WEB';
        }
        tx.set(ref, payload, { merge: true });
      });
    },

    async markFailed(idempotencyKey: string, errorDetails: string) {
      const ref = recordDocRef(idempotencyKey);
      try {
        await runTransaction(firestore, async (tx: Transaction) => {
          const snap = await tx.get(ref);
          if (snap.exists() && snap.data()?.requestStatus === 'COMPLETED') {
            return;
          }
          if (!snap.exists()) return;
          tx.set(
            ref,
            {
              requestStatus: 'FAILED',
              errorDetails: String(errorDetails || '').slice(0, 900),
              updatedAt: Date.now(),
              serverUpdatedAt: serverTimestamp()
            },
            { merge: true }
          );
        });
      } catch {
        // Non-fatal if offline; local state holds FAILED
      }
    },

    async markPendingConfirmation(idempotencyKey: string, errorDetails: string) {
      const ref = recordDocRef(idempotencyKey);
      try {
        await runTransaction(firestore, async (tx: Transaction) => {
          const snap = await tx.get(ref);
          if (!snap.exists() || snap.data()?.requestStatus === 'COMPLETED') {
            return;
          }
          tx.set(
            ref,
            {
              requestStatus: 'PENDING_CONFIRMATION',
              errorDetails: String(errorDetails || '').slice(0, 900),
              updatedAt: Date.now(),
              serverUpdatedAt: serverTimestamp()
            },
            { merge: true }
          );
        });
      } catch {
        // Expected when offline
      }
    },

    async getServerRecord(idempotencyKey: string): Promise<IdempotencyRecord | null> {
      const ref = recordDocRef(idempotencyKey);
      const snap = await getDoc(ref);
      if (!snap.exists()) return null;
      return snap.data() as IdempotencyRecord;
    },

    async checkTargetDocumentExists(collectionName: string, docId: string): Promise<boolean> {
      if (!collectionName || !docId) return false;
      try {
        const cleanDocId = sanitizeIdempotencyKey(docId);
        const snap = await getDoc(doc(firestore, `erp_clusters/${clusterCode}/${collectionName}`, cleanDocId));
        return snap.exists();
      } catch {
        return false;
      }
    }
  };
}

/**
 * Central Synchronous Submission Guard & Persistent Queue Store
 */
export class TransactionSubmissionGuardEngine {
  private activeGuardLocks = new Map<string, string>();
  private activeDocLocks = new Map<string, string>();
  private draftKeys = new Map<string, string>();
  private listeners = new Set<() => void>();
  private storage: StorageLike;
  private adapter: BackendIdempotencyAdapter;
  private recordsCache: IdempotencyRecord[] | null = null;

  constructor(storage?: StorageLike, adapter?: BackendIdempotencyAdapter) {
    this.storage = storage || defaultStorage();
    this.adapter = adapter || createFirestoreIdempotencyAdapter();
    this.loadDraftsFromStorage();
  }

  public setBackendAdapter(adapter: BackendIdempotencyAdapter): void {
    this.adapter = adapter;
  }

  public setStorage(storage: StorageLike): void {
    this.storage = storage;
    this.recordsCache = null;
    this.loadDraftsFromStorage();
    this.notify();
  }

  private loadDraftsFromStorage(): void {
    try {
      const raw = this.storage.getItem(STORAGE_KEY_DRAFTS);
      if (raw) {
        const parsed = JSON.parse(raw) as Record<string, string>;
        this.draftKeys = new Map(Object.entries(parsed));
      }
    } catch {
      this.draftKeys = new Map();
    }
  }

  private saveDraftsToStorage(): void {
    try {
      this.storage.setItem(STORAGE_KEY_DRAFTS, JSON.stringify(Object.fromEntries(this.draftKeys.entries())));
    } catch {
      // ignore quota errors
    }
  }

  private loadRecords(): IdempotencyRecord[] {
    if (this.recordsCache) return this.recordsCache;
    try {
      const raw = this.storage.getItem(STORAGE_KEY_RECORDS);
      if (!raw) {
        this.recordsCache = [];
        return this.recordsCache;
      }
      const parsed = JSON.parse(raw);
      this.recordsCache = Array.isArray(parsed) ? parsed : [];
    } catch {
      this.recordsCache = [];
    }
    return this.recordsCache;
  }

  private saveRecords(records: IdempotencyRecord[]): void {
    const trimmed = [...records]
      .sort((a, b) => (b.updatedAt || b.createdAt) - (a.updatedAt || a.createdAt))
      .slice(0, 300);
    this.recordsCache = trimmed;
    try {
      this.storage.setItem(STORAGE_KEY_RECORDS, JSON.stringify(trimmed));
    } catch {
      // ignore quota errors
    }
    this.notify();
  }

  private upsertLocalRecord(record: IdempotencyRecord): void {
    const list = this.loadRecords().filter((r) => r.idempotencyKey !== record.idempotencyKey);
    list.unshift(record);
    this.saveRecords(list);
  }

  public subscribe(listener: () => void): () => void {
    this.listeners.add(listener);
    return () => {
      this.listeners.delete(listener);
    };
  }

  private notify(): void {
    for (const listener of this.listeners) {
      try {
        listener();
      } catch {
        // ignore listener failure
      }
    }
  }

  public getRecords(): IdempotencyRecord[] {
    return [...this.loadRecords()];
  }

  public getUnresolvedRecords(): IdempotencyRecord[] {
    return this.loadRecords().filter(
      (r) => r.requestStatus === 'PROCESSING' || r.requestStatus === 'PENDING_CONFIRMATION'
    );
  }

  public isLocked(guardKey: string, documentReference?: string): boolean {
    if (this.activeGuardLocks.has(guardKey)) return true;
    if (documentReference && this.activeDocLocks.has(documentReference)) return true;
    const localUnresolved = this.loadRecords().some(
      (r) =>
        (r.guardKey === guardKey || (documentReference && r.documentReference === documentReference)) &&
        (r.requestStatus === 'PROCESSING' || r.requestStatus === 'PENDING_CONFIRMATION')
    );
    return localUnresolved;
  }

  public getOrCreateDraftKey(guardKey: string, prefix = 'OP'): string {
    const existing = this.draftKeys.get(guardKey);
    if (existing) return existing;
    const key = generateIdempotencyKey(prefix);
    this.draftKeys.set(guardKey, key);
    this.saveDraftsToStorage();
    return key;
  }

  public resetDraftKey(guardKey: string): void {
    this.draftKeys.delete(guardKey);
    this.saveDraftsToStorage();
  }

  public getRecordByKey(idempotencyKey: string): IdempotencyRecord | null {
    return this.loadRecords().find((r) => r.idempotencyKey === idempotencyKey) || null;
  }

  public releaseTransientLock(guardKey: string, documentReference?: string): void {
    this.activeGuardLocks.delete(guardKey);
    if (documentReference) {
      this.activeDocLocks.delete(documentReference);
    }
    this.notify();
  }

  /**
   * Executes an ERP mutating operation with:
   * 1. Synchronous pre-async re-entrancy & same-document lock.
   * 2. Atomic Firestore `idempotency_requests` claim via `runTransaction`.
   * 3. Slow connection notification + mid-flight disconnect `PENDING_CONFIRMATION`.
   * 4. Strict duplicate replay protection.
   */
  public async execute<T = unknown>(options: ExecuteIdempotentOptions<T>): Promise<ExecuteIdempotentResult<T>> {
    const {
      guardKey,
      operationType,
      userId,
      documentReference,
      targetCollection,
      payloadSummary,
      timeoutMs = DEFAULT_TIMEOUT_MS,
      slowThresholdMs = DEFAULT_SLOW_THRESHOLD_MS,
      onStatusMessage,
      execute
    } = options;

    // 1. Synchronous multi-click / same-document / unresolved-pending guard
    if (this.activeGuardLocks.has(guardKey)) {
      const lockedKey = this.activeGuardLocks.get(guardKey)!;
      return {
        ok: false,
        replayed: false,
        blocked: true,
        uncertain: false,
        status: 'PROCESSING',
        idempotencyKey: lockedKey,
        resultReference: null,
        message: IDEMPOTENCY_STATUS_MESSAGES.PROCESSING_WAIT
      };
    }

    if (documentReference && this.activeDocLocks.has(documentReference)) {
      const lockedKey = this.activeDocLocks.get(documentReference)!;
      return {
        ok: false,
        replayed: false,
        blocked: true,
        uncertain: false,
        status: 'PROCESSING',
        idempotencyKey: lockedKey,
        resultReference: null,
        message: IDEMPOTENCY_STATUS_MESSAGES.PROCESSING_WAIT
      };
    }

    const unresolvedForDoc = this.loadRecords().find(
      (r) =>
        (r.guardKey === guardKey || (documentReference && r.documentReference === documentReference)) &&
        r.requestStatus === 'PENDING_CONFIRMATION'
    );
    if (unresolvedForDoc && (!options.idempotencyKey || options.idempotencyKey === unresolvedForDoc.idempotencyKey)) {
      return {
        ok: false,
        replayed: false,
        blocked: true,
        uncertain: true,
        status: 'PENDING_CONFIRMATION',
        idempotencyKey: unresolvedForDoc.idempotencyKey,
        resultReference: unresolvedForDoc.resultReference,
        message: IDEMPOTENCY_STATUS_MESSAGES.UNCERTAIN_DO_NOT_RESUBMIT
      };
    }

    const idempotencyKey = sanitizeIdempotencyKey(
      options.idempotencyKey || this.getOrCreateDraftKey(guardKey, operationType)
    );

    const existingLocal = this.getRecordByKey(idempotencyKey);
    if (existingLocal && existingLocal.requestStatus === 'COMPLETED') {
      return {
        ok: true,
        replayed: true,
        blocked: false,
        uncertain: false,
        status: 'COMPLETED',
        idempotencyKey,
        resultReference: existingLocal.resultReference || documentReference,
        message: IDEMPOTENCY_STATUS_MESSAGES.ALREADY_RECORDED
      };
    }

    // Acquire synchronous locks IMMEDIATELY before any `await`
    this.activeGuardLocks.set(guardKey, idempotencyKey);
    if (documentReference) {
      this.activeDocLocks.set(documentReference, idempotencyKey);
    }

    const now = Date.now();
    const processingRecord: IdempotencyRecord = {
      idempotencyKey,
      userId: userId || 'النظام',
      operationType,
      documentReference: documentReference || idempotencyKey,
      requestStatus: 'PROCESSING',
      createdAt: existingLocal?.createdAt || now,
      updatedAt: now,
      completionTime: null,
      resultReference: null,
      errorDetails: null,
      targetCollection,
      payloadSummary,
      platform: detectPlatform(),
      guardKey
    };

    // Persist locally as PROCESSING immediately
    this.upsertLocalRecord(processingRecord);
    onStatusMessage?.(IDEMPOTENCY_STATUS_MESSAGES.PROCESSING_WAIT, 'PROCESSING');

    let slowTimer: ReturnType<typeof setTimeout> | null = null;
    if (slowThresholdMs > 0) {
      slowTimer = setTimeout(() => {
        onStatusMessage?.(IDEMPOTENCY_STATUS_MESSAGES.SLOW_CHECKING, 'PROCESSING');
      }, slowThresholdMs);
    }

    let timeoutHandle: ReturnType<typeof setTimeout> | null = null;

    try {
      const executionPromise = (async (): Promise<ExecuteIdempotentResult<T>> => {
        // 2. Server-side atomic idempotency claim
        const claim = await this.adapter.claimOrGet(processingRecord, DEFAULT_LEASE_MS);
        if (claim.outcome === 'ALREADY_COMPLETED') {
          const completedRec: IdempotencyRecord = {
            ...processingRecord,
            ...(claim.existing || {}),
            requestStatus: 'COMPLETED',
            updatedAt: Date.now()
          };
          this.upsertLocalRecord(completedRec);
          this.resetDraftKey(guardKey);
          onStatusMessage?.(IDEMPOTENCY_STATUS_MESSAGES.ALREADY_RECORDED, 'COMPLETED');
          return {
            ok: true,
            replayed: true,
            blocked: false,
            uncertain: false,
            status: 'COMPLETED',
            idempotencyKey,
            resultReference: completedRec.resultReference || documentReference,
            message: IDEMPOTENCY_STATUS_MESSAGES.ALREADY_RECORDED
          };
        }

        if (claim.outcome === 'IN_PROGRESS_REMOTE') {
          const remoteRec: IdempotencyRecord = {
            ...processingRecord,
            ...(claim.existing || {}),
            requestStatus: 'PENDING_CONFIRMATION',
            updatedAt: Date.now(),
            errorDetails: 'العملية قيد التنفيذ من جلسة أو جهاز آخر.'
          };
          this.upsertLocalRecord(remoteRec);
          onStatusMessage?.(IDEMPOTENCY_STATUS_MESSAGES.UNCERTAIN_DO_NOT_RESUBMIT, 'PENDING_CONFIRMATION');
          return {
            ok: false,
            replayed: false,
            blocked: true,
            uncertain: true,
            status: 'PENDING_CONFIRMATION',
            idempotencyKey,
            resultReference: null,
            message: IDEMPOTENCY_STATUS_MESSAGES.UNCERTAIN_DO_NOT_RESUBMIT
          };
        }

        // 3. Execute actual ERP domain action
        const rawRes = await execute({ idempotencyKey, txHelper: firestore });
        let data: T | undefined;
        let resultReference = documentReference || idempotencyKey;
        if (rawRes && typeof rawRes === 'object' && ('resultReference' in rawRes || 'result' in rawRes)) {
          const wrapped = rawRes as { result?: T; resultReference?: string };
          data = wrapped.result;
          if (wrapped.resultReference) resultReference = wrapped.resultReference;
        } else {
          data = rawRes as T;
        }

        const completionTime = Date.now();
        await this.adapter.markCompleted(idempotencyKey, resultReference, completionTime, operationType);

        const completedRecord: IdempotencyRecord = {
          ...processingRecord,
          requestStatus: 'COMPLETED',
          resultReference,
          completionTime,
          updatedAt: completionTime,
          errorDetails: null
        };
        this.upsertLocalRecord(completedRecord);
        this.resetDraftKey(guardKey);
        onStatusMessage?.(IDEMPOTENCY_STATUS_MESSAGES.COMPLETED_SUCCESS, 'COMPLETED');

        return {
          ok: true,
          replayed: false,
          blocked: false,
          uncertain: false,
          status: 'COMPLETED',
          idempotencyKey,
          resultReference,
          message: IDEMPOTENCY_STATUS_MESSAGES.COMPLETED_SUCCESS,
          data
        };
      })();

      const timeoutPromise = new Promise<never>((_, reject) => {
        timeoutHandle = setTimeout(() => {
          const err = new Error('انتهت مهلة الاتصال بالخادم أثناء تنفيذ العملية (timeout)');
          (err as Error & { code?: string }).code = 'deadline-exceeded';
          reject(err);
        }, timeoutMs);
      });

      return await Promise.race([executionPromise, timeoutPromise]);
    } catch (err: unknown) {
      const errMsg = err instanceof Error ? err.message : String(err || 'خطأ غير معروف');
      const isUncertain =
        isLikelyNetworkOrTimeoutError(err) ||
        (typeof navigator !== 'undefined' && navigator.onLine === false);

      if (isUncertain) {
        const pendingRec: IdempotencyRecord = {
          ...processingRecord,
          requestStatus: 'PENDING_CONFIRMATION',
          updatedAt: Date.now(),
          errorDetails: errMsg
        };
        this.upsertLocalRecord(pendingRec);
        await this.adapter.markPendingConfirmation(idempotencyKey, errMsg);
        onStatusMessage?.(IDEMPOTENCY_STATUS_MESSAGES.UNCERTAIN_DO_NOT_RESUBMIT, 'PENDING_CONFIRMATION');
        return {
          ok: false,
          replayed: false,
          blocked: false,
          uncertain: true,
          status: 'PENDING_CONFIRMATION',
          idempotencyKey,
          resultReference: null,
          message: IDEMPOTENCY_STATUS_MESSAGES.UNCERTAIN_DO_NOT_RESUBMIT,
          errorDetails: errMsg
        };
      }

      const failedRec: IdempotencyRecord = {
        ...processingRecord,
        requestStatus: 'FAILED',
        updatedAt: Date.now(),
        errorDetails: errMsg
      };
      this.upsertLocalRecord(failedRec);
      await this.adapter.markFailed(idempotencyKey, errMsg);
      onStatusMessage?.(IDEMPOTENCY_STATUS_MESSAGES.FAILED_REVIEW, 'FAILED');
      return {
        ok: false,
        replayed: false,
        blocked: false,
        uncertain: false,
        status: 'FAILED',
        idempotencyKey,
        resultReference: null,
        message: errMsg || IDEMPOTENCY_STATUS_MESSAGES.FAILED_REVIEW,
        errorDetails: errMsg
      };
    } finally {
      if (slowTimer) clearTimeout(slowTimer);
      if (timeoutHandle) clearTimeout(timeoutHandle);
      this.activeGuardLocks.delete(guardKey);
      if (documentReference) {
        this.activeDocLocks.delete(documentReference);
      }
      this.notify();
    }
  }

  /**
   * Queries the server to verify the true state of an uncertain / pending operation.
   * Returns COMPLETED if confirmed executed, FAILED (safe to retry) if verified NOT executed,
   * or PENDING_CONFIRMATION if server still unreachable.
   */
  public async verifyOperationStatus(idempotencyKey: string): Promise<IdempotencyRecord | null> {
    const local = this.getRecordByKey(idempotencyKey);
    try {
      const serverRec = await this.adapter.getServerRecord(idempotencyKey);
      if (serverRec) {
        if (serverRec.requestStatus === 'COMPLETED') {
          const updated: IdempotencyRecord = {
            ...(local || serverRec),
            ...serverRec,
            requestStatus: 'COMPLETED',
            updatedAt: Date.now()
          };
          this.upsertLocalRecord(updated);
          if (updated.guardKey) this.resetDraftKey(updated.guardKey);
          return updated;
        }
        // Check if target document actually landed on server despite incomplete status update
        if (
          local?.targetCollection &&
          local?.documentReference &&
          this.adapter.checkTargetDocumentExists
        ) {
          const exists = await this.adapter.checkTargetDocumentExists(
            local.targetCollection,
            local.documentReference
          );
          if (exists) {
            const now = Date.now();
            await this.adapter.markCompleted(idempotencyKey, local.documentReference, now, local.operationType);
            const completed: IdempotencyRecord = {
              ...local,
              requestStatus: 'COMPLETED',
              resultReference: local.documentReference,
              completionTime: now,
              updatedAt: now,
              errorDetails: null
            };
            this.upsertLocalRecord(completed);
            if (completed.guardKey) this.resetDraftKey(completed.guardKey);
            return completed;
          }
        }
        const merged: IdempotencyRecord = {
          ...(local || serverRec),
          ...serverRec,
          updatedAt: Date.now()
        };
        this.upsertLocalRecord(merged);
        return merged;
      }

      // Server returned null — verify if target doc was written
      if (local && local.targetCollection && local.documentReference && this.adapter.checkTargetDocumentExists) {
        const exists = await this.adapter.checkTargetDocumentExists(
          local.targetCollection,
          local.documentReference
        );
        if (exists) {
          const now = Date.now();
          const completed: IdempotencyRecord = {
            ...local,
            requestStatus: 'COMPLETED',
            resultReference: local.documentReference,
            completionTime: now,
            updatedAt: now,
            errorDetails: null
          };
          this.upsertLocalRecord(completed);
          if (completed.guardKey) this.resetDraftKey(completed.guardKey);
          return completed;
        }
      }

      // Verified not executed on server — mark FAILED so user can safely retry with the SAME idempotencyKey
      if (local && (local.requestStatus === 'PROCESSING' || local.requestStatus === 'PENDING_CONFIRMATION')) {
        const verifiedNotExecuted: IdempotencyRecord = {
          ...local,
          requestStatus: 'FAILED',
          updatedAt: Date.now(),
          errorDetails: 'تم التحقق من الخادم: لم تصل العملية أو لم تُحفظ، يمكنك إعادة المحاولة بأمان.'
        };
        this.upsertLocalRecord(verifiedNotExecuted);
        return verifiedNotExecuted;
      }

      return local;
    } catch {
      return local;
    }
  }

  /**
   * Cancels/clears a failed or unconfirmed draft so user can start fresh manually.
   */
  public markCancelled(idempotencyKey: string): void {
    const local = this.getRecordByKey(idempotencyKey);
    if (!local || local.requestStatus === 'COMPLETED') return;
    this.upsertLocalRecord({
      ...local,
      requestStatus: 'CANCELLED',
      updatedAt: Date.now()
    });
    if (local.guardKey) {
      this.resetDraftKey(local.guardKey);
    }
  }

  /**
   * Startup & reconnect recovery: checks all unresolved PROCESSING / PENDING_CONFIRMATION operations
   * against the server without auto-generating duplicate requests.
   */
  public async recoverPendingOperations(): Promise<{
    checked: number;
    completedCount: number;
    pendingCount: number;
  }> {
    const unresolved = this.getUnresolvedRecords();
    let completedCount = 0;
    let pendingCount = 0;

    for (const rec of unresolved) {
      // Any orphaned PROCESSING left behind after page close/crash transitions to PENDING_CONFIRMATION before check
      if (rec.requestStatus === 'PROCESSING' && !this.activeGuardLocks.has(rec.guardKey || '')) {
        this.upsertLocalRecord({
          ...rec,
          requestStatus: 'PENDING_CONFIRMATION',
          updatedAt: Date.now(),
          errorDetails: 'انقطعت الجلسة قبل تأكيد الخادم؛ جاري التحقق من الحالة.'
        });
      }
      const checkedRec = await this.verifyOperationStatus(rec.idempotencyKey);
      if (checkedRec?.requestStatus === 'COMPLETED') {
        completedCount += 1;
      } else if (
        checkedRec?.requestStatus === 'PENDING_CONFIRMATION' ||
        checkedRec?.requestStatus === 'PROCESSING'
      ) {
        pendingCount += 1;
      }
    }

    return {
      checked: unresolved.length,
      completedCount,
      pendingCount
    };
  }
}

export const TransactionSubmissionGuard = new TransactionSubmissionGuardEngine();
export const IdempotencyProtectionSystem = TransactionSubmissionGuard;
