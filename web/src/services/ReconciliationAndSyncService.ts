import {
  Cashbox,
  CurrencyCode,
  InvoicePayment,
  JournalEntry,
  JournalLine,
  SalesInvoice,
  TreasuryMovement,
  WarehouseStockBalance
} from '../types/erp';
import {
  computeCashboxBalancesByCurrency,
  computeDistinctCashboxesWithBalances,
  computeInvoiceFinancials,
  deduplicateCollectionRecords,
  isCancelledOrReversedStatus,
  isMovementInflow,
  normalizeEntityKey,
  resolveCanonicalCurrency
} from './ErpCalculations';
import { computeTrialBalance, computeReconciliationReport } from './AccountingEngine';

export type UnifiedSyncStatus =
  | 'Synced'
  | 'Syncing'
  | 'Pending'
  | 'Failed'
  | 'Offline'
  | 'Conflict Detected';

export interface CollectionSyncMetadata {
  collectionName: string;
  hasPendingWrites: boolean;
  fromCache: boolean;
  docCount: number;
  lastServerAckAt: number;
  lastError?: string | null;
}

export interface UnifiedSyncSnapshot {
  status: UnifiedSyncStatus;
  labelAr: string;
  online: boolean;
  pendingWritesCount: number;
  fromCacheCollectionsCount: number;
  failedCount: number;
  conflictCount: number;
  lastServerAckAt: number;
  collections: Record<string, CollectionSyncMetadata>;
}

export function getSyncStatusArabicLabel(status: UnifiedSyncStatus): string {
  switch (status) {
    case 'Synced':
      return 'متزامن ومؤكد على الخادم';
    case 'Syncing':
      return 'جارٍ المزامنة مع الخادم...';
    case 'Pending':
      return 'عمليات محلية معلقة بانتظار تأكيد الخادم';
    case 'Failed':
      return 'فشل المزامنة — يرجى المراجعة وإعادة المحاولة';
    case 'Offline':
      return 'وضع عدم الاتصال (Offline) — حفظ محلي مؤقت';
    case 'Conflict Detected':
      return 'تم رصد تعارض بيانات أو أرصدة';
  }
}

/**
 * Strictly enforces that 'Synced' is NEVER returned until all pending writes flush
 * and a server-backed snapshot (not cache-only) is acknowledged.
 */
export function resolveUnifiedSyncState(params: {
  online: boolean;
  hasPendingWrites: boolean;
  fromCache: boolean;
  syncingCount?: number;
  failedCount?: number;
  conflictCount?: number;
  lastServerAckAt: number;
}): UnifiedSyncStatus {
  if ((params.conflictCount ?? 0) > 0) return 'Conflict Detected';
  if (!params.online) return 'Offline';
  if ((params.failedCount ?? 0) > 0) return 'Failed';
  if (params.hasPendingWrites) return 'Pending';
  if ((params.syncingCount ?? 0) > 0 || params.fromCache || params.lastServerAckAt <= 0) {
    return 'Syncing';
  }
  return 'Synced';
}

export type PlatformKind = 'WEB' | 'DESKTOP' | 'ANDROID';

export interface CrossPlatformBalanceCheckpoint {
  platform: PlatformKind;
  cashboxTotals: Record<string, Partial<Record<CurrencyCode, number>>>;
  currencyTotals: Record<CurrencyCode, number>;
  arTotals: Record<CurrencyCode, number>;
  movementCount: number;
  invoiceCount: number;
  lastSyncedTransactionRef: string;
  lastSyncedAtMillis: number;
  updatedAtMillis: number;
  appVersion?: string;
}

export type DivergenceRootCause =
  | 'PENDING_OFFLINE_WRITES'
  | 'STALE_CACHED_CASHBOX_BALANCE'
  | 'UNSYNCED_CLIENT_CHECKPOINT'
  | 'GL_JOURNAL_OMISSION'
  | 'DUPLICATE_OR_MISSING_RECORDS'
  | 'INVOICE_PAYMENT_DRIFT';

export type SafeRemediationKind =
  | 'FLUSH_PENDING_OUTBOX'
  | 'POST_LEDGER_ADJUSTMENT_ENTRY'
  | 'POST_MISSING_GL_JOURNAL'
  | 'REFRESH_CLIENT_SNAPSHOT_FROM_SERVER';

export interface BalanceAnomalyRecord {
  id: string;
  affectedAccount: string;
  currency: CurrencyCode;
  platformOrSource: string;
  centralServerValue: number;
  ledgerSumValue: number;
  comparedValue: number;
  varianceAmount: number;
  detectedAtMillis: number;
  lastSyncedTransactionRef: string;
  lastSyncedAtMillis: number;
  rootCauseSource: DivergenceRootCause;
  rootCauseDetailAr: string;
  safeRemediationAction: SafeRemediationKind;
  safeRemediationDescriptionAr: string;
  proposedAdjustmentDirection?: 'وارد' | 'منصرف';
  proposedAdjustmentAmount?: number;
}

export interface CrossPlatformReconciliationReport {
  generatedAtMillis: number;
  overallStatus: 'MATCHED' | 'VARIANCE_DETECTED';
  centralCurrencyTotals: Record<CurrencyCode, number>;
  localCurrencyTotals: Record<CurrencyCode, number>;
  ledgerCurrencyTotals: Record<CurrencyCode, number>;
  glCashTotals: Record<CurrencyCode, number>;
  centralArTotals: Record<CurrencyCode, number>;
  checkpoints: {
    web?: CrossPlatformBalanceCheckpoint | null;
    desktop?: CrossPlatformBalanceCheckpoint | null;
    android?: CrossPlatformBalanceCheckpoint | null;
  };
  anomalies: BalanceAnomalyRecord[];
  lastSyncedTransactionRef: string;
  lastSyncedAtMillis: number;
  summaryAr: string;
}

export function sumMovementsDirectFromLedger(movements: TreasuryMovement[]): Record<CurrencyCode, number> {
  const unique = deduplicateCollectionRecords('movements', movements);
  const totals: Record<CurrencyCode, number> = { SDG: 0, EGP: 0, USD: 0 };
  for (const mv of unique) {
    if (!mv || isCancelledOrReversedStatus((mv as any).status)) continue;
    const rawBox = String(mv.cashbox || mv.cashboxName || '').trim();
    if (!rawBox) continue;
    const cur = resolveCanonicalCurrency(mv.currency, rawBox);
    const amt = Math.abs(Number(mv.amount || 0));
    if (!Number.isFinite(amt)) continue;
    totals[cur] += isMovementInflow(mv) ? amt : -amt;
  }
  return totals;
}

export function computePlatformCheckpointPayload(params: {
  platform: PlatformKind;
  cashboxes: Cashbox[];
  movements: TreasuryMovement[];
  invoices: SalesInvoice[];
  payments: InvoicePayment[];
  appVersion?: string;
}): CrossPlatformBalanceCheckpoint {
  const distinct = computeDistinctCashboxesWithBalances(params.cashboxes, params.movements);
  const cashboxTotals: Record<string, Partial<Record<CurrencyCode, number>>> = {};
  for (const b of distinct) {
    cashboxTotals[b.name] = {
      SDG: b.balances.SDG,
      EGP: b.balances.EGP,
      USD: b.balances.USD
    };
  }
  const currencyTotals = computeCashboxBalancesByCurrency(params.cashboxes, params.movements);

  const arTotals: Record<CurrencyCode, number> = { SDG: 0, EGP: 0, USD: 0 };
  for (const inv of deduplicateCollectionRecords('invoices', params.invoices)) {
    if (isCancelledOrReversedStatus(inv.status)) continue;
    const cur = resolveCanonicalCurrency(inv.currency);
    const fin = computeInvoiceFinancials(inv, params.payments);
    arTotals[cur] += fin.remainingAmount;
  }

  const latestMov = deduplicateCollectionRecords('movements', params.movements)
    .slice()
    .sort((a, b) => Number(b.createdAt || b.timestamp || 0) - Number(a.createdAt || a.timestamp || 0))[0];

  const now = Date.now();
  return {
    platform: params.platform,
    cashboxTotals,
    currencyTotals,
    arTotals,
    movementCount: deduplicateCollectionRecords('movements', params.movements).length,
    invoiceCount: deduplicateCollectionRecords('invoices', params.invoices).length,
    lastSyncedTransactionRef: latestMov?.reference || latestMov?.cloudDocId || (latestMov ? `MOV-${latestMov.id}` : 'NONE'),
    lastSyncedAtMillis: Number(latestMov?.createdAt || latestMov?.timestamp || now),
    updatedAtMillis: now,
    appVersion: params.appVersion
  };
}

export function computeCrossPlatformBalanceReconciliation(params: {
  centralCashboxes: Cashbox[];
  centralMovements: TreasuryMovement[];
  centralInvoices: SalesInvoice[];
  centralPayments: InvoicePayment[];
  localCashboxes: Cashbox[];
  localMovements: TreasuryMovement[];
  journalEntries: JournalEntry[];
  journalLines: JournalLine[];
  checkpoints?: {
    web?: CrossPlatformBalanceCheckpoint | null;
    desktop?: CrossPlatformBalanceCheckpoint | null;
    android?: CrossPlatformBalanceCheckpoint | null;
  };
  pendingWritesCount?: number;
  nowMillis?: number;
}): CrossPlatformReconciliationReport {
  const now = params.nowMillis ?? Date.now();
  const centralDistinct = computeDistinctCashboxesWithBalances(params.centralCashboxes, params.centralMovements);
  const localDistinct = computeDistinctCashboxesWithBalances(params.localCashboxes, params.localMovements);

  const centralCurrencyTotals = computeCashboxBalancesByCurrency(params.centralCashboxes, params.centralMovements);
  const localCurrencyTotals = computeCashboxBalancesByCurrency(params.localCashboxes, params.localMovements);
  const ledgerCurrencyTotals = sumMovementsDirectFromLedger(params.centralMovements);

  const latestCentralMov = deduplicateCollectionRecords('movements', params.centralMovements)
    .slice()
    .sort((a, b) => Number(b.createdAt || b.timestamp || 0) - Number(a.createdAt || a.timestamp || 0))[0];
  const lastSyncedTransactionRef =
    latestCentralMov?.reference || latestCentralMov?.cloudDocId || (latestCentralMov ? `MOV-${latestCentralMov.id}` : 'NONE');
  const lastSyncedAtMillis = Number(latestCentralMov?.createdAt || latestCentralMov?.timestamp || 0);

  const anomalies: BalanceAnomalyRecord[] = [];
  const currencies: CurrencyCode[] = ['SDG', 'EGP', 'USD'];

  // 1. Check Central Computed vs Immutable Ledger Sum & Local Client Cache per cashbox/currency
  const centralByKey = new Map<string, typeof centralDistinct[number]>();
  for (const c of centralDistinct) centralByKey.set(normalizeEntityKey(c.name), c);
  const localByKey = new Map<string, typeof localDistinct[number]>();
  for (const l of localDistinct) localByKey.set(normalizeEntityKey(l.name), l);

  const allBoxKeys = new Set<string>([...centralByKey.keys(), ...localByKey.keys()]);
  for (const key of allBoxKeys) {
    const cBox = centralByKey.get(key);
    const lBox = localByKey.get(key);
    const displayName = cBox?.name || lBox?.name || key;
    for (const cur of currencies) {
      const cVal = cBox?.balances[cur] ?? 0;
      const lVal = lBox?.balances[cur] ?? 0;
      const diff = Number((cVal - lVal).toFixed(2));
      if (Math.abs(diff) >= 0.01) {
        const hasPending = (params.pendingWritesCount ?? 0) > 0;
        anomalies.push({
          id: `LOCAL_VS_CENTRAL_${key}_${cur}`,
          affectedAccount: `${displayName} (${cur})`,
          currency: cur,
          platformOrSource: 'Local Cache vs Firestore Central',
          centralServerValue: cVal,
          ledgerSumValue: cVal,
          comparedValue: lVal,
          varianceAmount: diff,
          detectedAtMillis: now,
          lastSyncedTransactionRef,
          lastSyncedAtMillis,
          rootCauseSource: hasPending ? 'PENDING_OFFLINE_WRITES' : 'DUPLICATE_OR_MISSING_RECORDS',
          rootCauseDetailAr: hasPending
            ? `توجد (${params.pendingWritesCount}) عمليات محلية معلقة لم تتزامن بعد مع السيرفر المركزي.`
            : `تباين بين رصيد الخزنة المحلي والسحابة المركزية بمقدار ${Math.abs(diff).toLocaleString()} ${cur}.`,
          safeRemediationAction: hasPending ? 'FLUSH_PENDING_OUTBOX' : 'REFRESH_CLIENT_SNAPSHOT_FROM_SERVER',
          safeRemediationDescriptionAr: hasPending
            ? 'إتمام إرسال العمليات المعلقة إلى Firestore وإعادة التحقق من دفتر الأستاذ.'
            : 'إعادة بناء الرصيد المحلي مباشرة من حركات دفتر الأستاذ المركزية على Firestore دون نسخ رصيد مجرد.'
        });
      }
    }
  }

  // 2. Check stale cached cashboxes.balance field against authoritative ledger sum
  for (const rawBox of deduplicateCollectionRecords('cashboxes', params.centralCashboxes)) {
    const boxKey = normalizeEntityKey(rawBox.name);
    const primCur = resolveCanonicalCurrency(rawBox.currency, rawBox.name);
    const ledgerBoxVal = centralByKey.get(boxKey)?.balances[primCur] ?? 0;
    const cachedBal = Number(rawBox.balance || 0);
    if (Number.isFinite(cachedBal) && Math.abs(cachedBal) > 0.01 && Math.abs(ledgerBoxVal - cachedBal) >= 1.0) {
      anomalies.push({
        id: `CACHED_FIELD_DRIFT_${boxKey}_${primCur}`,
        affectedAccount: `${rawBox.name} (${primCur})`,
        currency: primCur,
        platformOrSource: 'Cached cashboxes.balance Field vs Movements Ledger',
        centralServerValue: ledgerBoxVal,
        ledgerSumValue: ledgerBoxVal,
        comparedValue: cachedBal,
        varianceAmount: Number((ledgerBoxVal - cachedBal).toFixed(2)),
        detectedAtMillis: Number(rawBox.updatedAt || now),
        lastSyncedTransactionRef,
        lastSyncedAtMillis,
        rootCauseSource: 'STALE_CACHED_CASHBOX_BALANCE',
        rootCauseDetailAr: `حقل الرصيد المخزن (${cachedBal.toLocaleString()}) في مستند الخزنة متأخر عن مجموع دفتر الحركات الفعلي (${ledgerBoxVal.toLocaleString()}).`,
        safeRemediationAction: 'REFRESH_CLIENT_SNAPSHOT_FROM_SERVER',
        safeRemediationDescriptionAr: 'اعتماد مجموع الحركات المالية (Immutable Ledger) كمصدر وحيد للحقيقة وتجاهل حقل balance المخزن.'
      });
    }
  }

  // 3. Check Central Server Totals vs Platform Checkpoints (Web / Desktop / Android)
  const platformEntries: Array<[string, CrossPlatformBalanceCheckpoint | null | undefined]> = [
    ['نسخة الويب (Web)', params.checkpoints?.web],
    ['نسخة سطح المكتب (Desktop)', params.checkpoints?.desktop],
    ['نسخة الأندرويد (Android)', params.checkpoints?.android]
  ];
  for (const [label, cp] of platformEntries) {
    if (!cp) continue;
    for (const cur of currencies) {
      const centralVal = centralCurrencyTotals[cur] ?? 0;
      const cpVal = Number(cp.currencyTotals?.[cur] ?? 0);
      const diff = Number((centralVal - cpVal).toFixed(2));
      if (Math.abs(diff) >= 0.01) {
        anomalies.push({
          id: `CHECKPOINT_${cp.platform}_${cur}`,
          affectedAccount: `إجمالي العملة (${cur}) — ${label}`,
          currency: cur,
          platformOrSource: label,
          centralServerValue: centralVal,
          ledgerSumValue: ledgerCurrencyTotals[cur] ?? 0,
          comparedValue: cpVal,
          varianceAmount: diff,
          detectedAtMillis: cp.updatedAtMillis || now,
          lastSyncedTransactionRef: cp.lastSyncedTransactionRef || lastSyncedTransactionRef,
          lastSyncedAtMillis: cp.lastSyncedAtMillis || lastSyncedAtMillis,
          rootCauseSource: 'UNSYNCED_CLIENT_CHECKPOINT',
          rootCauseDetailAr: `تختلف قراءة ${label} (${cpVal.toLocaleString()} ${cur}) عن السيرفر المركزي (${centralVal.toLocaleString()} ${cur}) بفارق ${Math.abs(diff).toLocaleString()} منذ ${new Date(cp.updatedAtMillis || now).toLocaleString('ar-EG')}.`,
          safeRemediationAction: 'REFRESH_CLIENT_SNAPSHOT_FROM_SERVER',
          safeRemediationDescriptionAr: `مزامنة ${label} مع دفتر الأستاذ المركزي وترحيل أي حركات معلقة باستخدام Idempotency ثم إعادة المطابقة.`
        });
      }
    }
  }

  // 4. Compare Operational Treasury & AR vs GL Trial Balance
  const glCashTotals: Record<CurrencyCode, number> = { SDG: 0, EGP: 0, USD: 0 };
  const centralArTotals: Record<CurrencyCode, number> = { SDG: 0, EGP: 0, USD: 0 };
  for (const inv of deduplicateCollectionRecords('invoices', params.centralInvoices)) {
    if (isCancelledOrReversedStatus(inv.status)) continue;
    const cur = resolveCanonicalCurrency(inv.currency);
    centralArTotals[cur] += computeInvoiceFinancials(inv, params.centralPayments).remainingAmount;
  }

  for (const cur of currencies) {
    const tb = computeTrialBalance(params.journalEntries, params.journalLines, cur);
    const glCash = tb.rows
      .filter((r) => ['1110', '1111', '1112', '1115'].includes(r.accountCode))
      .reduce((s, r) => s + r.signedBalance, 0);
    glCashTotals[cur] = glCash;

    const reconItems = computeReconciliationReport(
      cur,
      centralCurrencyTotals[cur],
      params.centralInvoices,
      params.centralPayments,
      [] as WarehouseStockBalance[],
      tb
    );
    for (const item of reconItems) {
      if (item.label.includes('النقدية') && item.status === 'VARIANCE') {
        const diff = Number(item.difference.toFixed(2));
        anomalies.push({
          id: `GL_CASH_DRIFT_${cur}`,
          affectedAccount: item.label,
          currency: cur,
          platformOrSource: 'Operational Ledger vs General Ledger (GL)',
          centralServerValue: item.operationalValue,
          ledgerSumValue: ledgerCurrencyTotals[cur],
          comparedValue: item.glValue,
          varianceAmount: diff,
          detectedAtMillis: now,
          lastSyncedTransactionRef,
          lastSyncedAtMillis,
          rootCauseSource: 'GL_JOURNAL_OMISSION',
          rootCauseDetailAr: `فرق بين رصيد حركات الخزينة الفعلي (${item.operationalValue.toLocaleString()}) وميزان المراجعة في دفتر الأستاذ (${item.glValue.toLocaleString()}).`,
          safeRemediationAction: 'POST_MISSING_GL_JOURNAL',
          safeRemediationDescriptionAr: 'إنشاء قيد تسوية محاسبي موثق في دفتر الأستاذ بعد مراجعة آخر الحركات غير المرحلة.',
          proposedAdjustmentDirection: diff > 0 ? 'وارد' : 'منصرف',
          proposedAdjustmentAmount: Math.abs(diff)
        });
      }
    }
  }

  const overallStatus = anomalies.length === 0 ? 'MATCHED' : 'VARIANCE_DETECTED';
  const summaryAr =
    overallStatus === 'MATCHED'
      ? `تمت المطابقة الشاملة بنجاح بين السيرفر المركزي ودفتر الأستاذ والمنصات (${Object.values(params.checkpoints || {}).filter(Boolean).length} منصات مرصودة).`
      : `تم اكتشاف ${anomalies.length} تباين/تنبيه مطابقة يتطلب المراجعة وتطبيق الإجراء الآمن من دفتر الحركات.`;

  return {
    generatedAtMillis: now,
    overallStatus,
    centralCurrencyTotals,
    localCurrencyTotals,
    ledgerCurrencyTotals,
    glCashTotals,
    centralArTotals,
    checkpoints: params.checkpoints || {},
    anomalies,
    lastSyncedTransactionRef,
    lastSyncedAtMillis,
    summaryAr
  };
}
