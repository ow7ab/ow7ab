import { useCallback, useEffect, useRef, useState } from 'react';
import {
  ExecuteIdempotentResult,
  IDEMPOTENCY_STATUS_MESSAGES,
  IdempotencyOperationType,
  IdempotencyRecord,
  IdempotencyProtectionSystem,
  IdempotencyRequestStatus,
  OPERATION_LOADING_LABELS
} from '../services/IdempotencyService';

export interface GuardRunParams<T = unknown> {
  guardKey?: string;
  operationType: IdempotencyOperationType;
  userId: string;
  documentReference: string;
  targetCollection: string;
  payloadSummary: string;
  timeoutMs?: number;
  execute: (ctx: { idempotencyKey: string }) => Promise<{ result?: T; resultReference?: string } | T>;
}

export interface UseTransactionGuardState {
  isSubmitting: boolean;
  loadingText: string;
  statusMessage: string | null;
  statusType: IdempotencyRequestStatus | null;
  pendingRecord: IdempotencyRecord | null;
  lastResult: ExecuteIdempotentResult | null;
  runGuarded: <T = unknown>(params: GuardRunParams<T>) => Promise<ExecuteIdempotentResult<T>>;
  verifyPending: () => Promise<IdempotencyRecord | null>;
  clearStatus: () => void;
}

/**
 * React Hook wrapping TransactionSubmissionGuard with synchronous ref re-entrancy locking,
 * exact Arabic operation loading text, and PENDING_CONFIRMATION state retention.
 */
export function useTransactionGuard(
  defaultGuardKey: string,
  defaultOperationType: IdempotencyOperationType = 'SAVE'
): UseTransactionGuardState {
  const syncLockRef = useRef<boolean>(false);
  const [isSubmitting, setIsSubmitting] = useState<boolean>(false);
  const [activeOperation, setActiveOperation] = useState<IdempotencyOperationType>(defaultOperationType);
  const [statusMessage, setStatusMessage] = useState<string | null>(null);
  const [statusType, setStatusType] = useState<IdempotencyRequestStatus | null>(null);
  const [lastResult, setLastResult] = useState<ExecuteIdempotentResult | null>(null);
  const [pendingRecord, setPendingRecord] = useState<IdempotencyRecord | null>(() => {
    const unresolved = IdempotencyProtectionSystem.getUnresolvedRecords().find(
      (r) => r.guardKey === defaultGuardKey
    );
    return unresolved || null;
  });

  useEffect(() => {
    const unsub = IdempotencyProtectionSystem.subscribe(() => {
      const unresolved = IdempotencyProtectionSystem.getUnresolvedRecords().find(
        (r) => r.guardKey === defaultGuardKey
      );
      setPendingRecord(unresolved || null);
    });
    return unsub;
  }, [defaultGuardKey]);

  const runGuarded = useCallback(
    async <T = unknown>(params: GuardRunParams<T>): Promise<ExecuteIdempotentResult<T>> => {
      const resolvedGuardKey = params.guardKey || defaultGuardKey;
      // Synchronous callback/re-render/double-tap barrier
      if (syncLockRef.current || IdempotencyProtectionSystem.isLocked(resolvedGuardKey, params.documentReference)) {
        const msg =
          pendingRecord?.requestStatus === 'PENDING_CONFIRMATION'
            ? IDEMPOTENCY_STATUS_MESSAGES.UNCERTAIN_DO_NOT_RESUBMIT
            : IDEMPOTENCY_STATUS_MESSAGES.PROCESSING_WAIT;
        setStatusMessage(msg);
        setStatusType(pendingRecord?.requestStatus || 'PROCESSING');
        return {
          ok: false,
          replayed: false,
          blocked: true,
          uncertain: pendingRecord?.requestStatus === 'PENDING_CONFIRMATION',
          status: pendingRecord?.requestStatus || 'PROCESSING',
          idempotencyKey:
            pendingRecord?.idempotencyKey ||
            IdempotencyProtectionSystem.getOrCreateDraftKey(resolvedGuardKey, params.operationType),
          resultReference: null,
          message: msg
        };
      }

      syncLockRef.current = true;
      setActiveOperation(params.operationType);
      setIsSubmitting(true);
      setStatusMessage(IDEMPOTENCY_STATUS_MESSAGES.PROCESSING_WAIT);
      setStatusType('PROCESSING');

      try {
        const res = await IdempotencyProtectionSystem.execute<T>({
          guardKey: resolvedGuardKey,
          operationType: params.operationType,
          userId: params.userId,
          documentReference: params.documentReference,
          targetCollection: params.targetCollection,
          payloadSummary: params.payloadSummary,
          timeoutMs: params.timeoutMs,
          onStatusMessage: (msg, status) => {
            setStatusMessage(msg);
            setStatusType(status);
          },
          execute: ({ idempotencyKey }) => params.execute({ idempotencyKey })
        });
        setLastResult(res);
        setStatusMessage(res.message);
        setStatusType(res.status);
        return res;
      } finally {
        syncLockRef.current = false;
        setIsSubmitting(false);
      }
    },
    [defaultGuardKey, pendingRecord]
  );

  const verifyPending = useCallback(async () => {
    const key = pendingRecord?.idempotencyKey || lastResult?.idempotencyKey;
    if (!key) return null;
    setIsSubmitting(true);
    setStatusMessage(IDEMPOTENCY_STATUS_MESSAGES.SLOW_CHECKING);
    try {
      const rec = await IdempotencyProtectionSystem.verifyOperationStatus(key);
      if (rec) {
        setStatusType(rec.requestStatus);
        if (rec.requestStatus === 'COMPLETED') {
          setStatusMessage(IDEMPOTENCY_STATUS_MESSAGES.ALREADY_RECORDED);
          setPendingRecord(null);
        } else if (rec.requestStatus === 'FAILED') {
          setStatusMessage(rec.errorDetails || IDEMPOTENCY_STATUS_MESSAGES.FAILED_REVIEW);
          setPendingRecord(null);
        } else {
          setStatusMessage(IDEMPOTENCY_STATUS_MESSAGES.UNCERTAIN_DO_NOT_RESUBMIT);
          setPendingRecord(rec);
        }
      }
      return rec;
    } finally {
      setIsSubmitting(false);
    }
  }, [pendingRecord, lastResult]);

  const clearStatus = useCallback(() => {
    setStatusMessage(null);
    setStatusType(null);
    setLastResult(null);
  }, []);

  return {
    isSubmitting: isSubmitting || pendingRecord?.requestStatus === 'PENDING_CONFIRMATION',
    loadingText: OPERATION_LOADING_LABELS[activeOperation] || OPERATION_LOADING_LABELS.SAVE,
    statusMessage,
    statusType,
    pendingRecord,
    lastResult,
    runGuarded,
    verifyPending,
    clearStatus
  };
}
