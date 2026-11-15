import React, { useEffect, useMemo, useState } from 'react';
import {
  BookOpen,
  Scale,
  TrendingUp,
  Landmark,
  CheckCircle2,
  AlertTriangle,
  Plus,
  RotateCcw,
  Globe2,
  Printer,
  FileSpreadsheet
} from 'lucide-react';
import { GlassCard } from '../ui/GlassCard';
import { GlassBadge } from '../ui/GlassBadge';
import { GlassButton } from '../ui/GlassButton';
import { Modal } from '../ui/Modal';
import { useAuth } from '../../context/AuthContext';
import {
  ErpDataService,
  DEFAULT_CHART_OF_ACCOUNTS,
  createBalancedJournalPayload,
  buildReversalJournalPayloads,
  computeTrialBalance,
  computeIncomeStatement,
  computeBalanceSheet,
  computeConsolidatedFxSummary,
  computeReconciliationReport
} from '../../services/ErpDataService';
import { ReportExporter } from '../../services/ReportExporter';
import { GRANULAR_ACTIONS, isSystemManager } from '../../services/SecurityService';
import {
  Cashbox,
  ChartAccount,
  CurrencyCode,
  InvoicePayment,
  JournalEntry,
  JournalLine,
  SalesInvoice,
  TreasuryMovement,
  WarehouseStockBalance
} from '../../types/erp';

type AccountingViewMode = 'LEDGER' | 'STATEMENTS';
type StatementSection = 'TRIAL_BALANCE' | 'INCOME_STATEMENT' | 'BALANCE_SHEET' | 'RECONCILIATION';

export const AccountingModuleView: React.FC<{ initialMode?: AccountingViewMode }> = ({
  initialMode = 'LEDGER'
}) => {
  const { userProfile, canPerformAction } = useAuth();
  const canCreate =
    canPerformAction(GRANULAR_ACTIONS.CREATE) ||
    canPerformAction(GRANULAR_ACTIONS.FINANCIAL_OPS) ||
    isSystemManager(userProfile);
  const canReverse =
    canPerformAction(GRANULAR_ACTIONS.DELETE) ||
    canPerformAction(GRANULAR_ACTIONS.FINANCIAL_OPS) ||
    isSystemManager(userProfile);

  const [mode, setMode] = useState<AccountingViewMode>(initialMode);
  const [section, setSection] = useState<StatementSection>('TRIAL_BALANCE');
  const [selectedCurrency, setSelectedCurrency] = useState<CurrencyCode>('SDG');
  const [egpToSdgInput, setEgpToSdgInput] = useState('12.5');
  const [usdToSdgInput, setUsdToSdgInput] = useState('600');
  const [showFxSummary, setShowFxSummary] = useState(false);
  const [showCoaModal, setShowCoaModal] = useState(false);

  const [accounts, setAccounts] = useState<ChartAccount[]>([]);
  const [entries, setEntries] = useState<JournalEntry[]>([]);
  const [lines, setLines] = useState<JournalLine[]>([]);
  const [cashboxes, setCashboxes] = useState<Cashbox[]>([]);
  const [movements, setMovements] = useState<TreasuryMovement[]>([]);
  const [invoices, setInvoices] = useState<SalesInvoice[]>([]);
  const [payments, setPayments] = useState<InvoicePayment[]>([]);
  const [stockBalances, setStockBalances] = useState<WarehouseStockBalance[]>([]);

  const [notice, setNotice] = useState<{ type: 'ok' | 'err'; text: string } | null>(null);
  const [isManualModalOpen, setIsManualModalOpen] = useState(false);
  const [manualDesc, setManualDesc] = useState('');
  const [manualCurrency, setManualCurrency] = useState<CurrencyCode>('SDG');
  const [manualDebitCode, setManualDebitCode] = useState('5300');
  const [manualCreditCode, setManualCreditCode] = useState('1110');
  const [manualAmount, setManualAmount] = useState('');

  const [reversingEntry, setReversingEntry] = useState<JournalEntry | null>(null);
  const [reverseReason, setReverseReason] = useState('');
  const [submitting, setSubmitting] = useState(false);

  useEffect(() => {
    setMode(initialMode);
  }, [initialMode]);

  useEffect(() => {
    const unsubs = [
      ErpDataService.subscribeToCollection<ChartAccount>('accounting_accounts', setAccounts),
      ErpDataService.subscribeToCollection<JournalEntry>('journal_entries', setEntries),
      ErpDataService.subscribeToCollection<JournalLine>('journal_lines', setLines),
      ErpDataService.subscribeToCollection<Cashbox>('cashboxes', setCashboxes),
      ErpDataService.subscribeToCollection<TreasuryMovement>('movements', setMovements),
      ErpDataService.subscribeToCollection<SalesInvoice>('invoices', setInvoices),
      ErpDataService.subscribeToCollection<InvoicePayment>('invoice_payments', setPayments),
      ErpDataService.subscribeToCollection<WarehouseStockBalance>('warehouse_stock_balances', setStockBalances)
    ];
    return () => unsubs.forEach((u) => u());
  }, []);

  const notify = (type: 'ok' | 'err', text: string) => {
    setNotice({ type, text });
    setTimeout(() => setNotice(null), 5000);
  };

  const effectiveAccounts = useMemo(() => {
    const map = new Map<string, ChartAccount>();
    for (const a of DEFAULT_CHART_OF_ACCOUNTS) map.set(a.accountCode, a);
    for (const a of accounts) {
      if (a && a.accountCode) map.set(a.accountCode, a);
    }
    return Array.from(map.values()).sort((a, b) => a.accountCode.localeCompare(b.accountCode));
  }, [accounts]);

  const sortedEntries = useMemo(
    () => [...entries].sort((a, b) => Number(b.createdAt || 0) - Number(a.createdAt || 0)),
    [entries]
  );

  const linesByEntryUuid = useMemo(() => {
    const map = new Map<string, JournalLine[]>();
    for (const l of lines) {
      const k = l.journalEntryUuid || String(l.journalEntryId || '');
      const list = map.get(k) || [];
      list.push(l);
      map.set(k, list);
    }
    return map;
  }, [lines]);

  const trialBalance = useMemo(
    () => computeTrialBalance(effectiveAccounts, entries, lines, selectedCurrency),
    [effectiveAccounts, entries, lines, selectedCurrency]
  );

  const incomeStatement = useMemo(
    () => computeIncomeStatement(trialBalance),
    [trialBalance]
  );

  const balanceSheet = useMemo(
    () => computeBalanceSheet(trialBalance, incomeStatement.netIncome),
    [trialBalance, incomeStatement.netIncome]
  );

  const cashByCurrency = useMemo(
    () => ErpDataService.computeCashboxBalancesByCurrency(cashboxes, movements),
    [cashboxes, movements]
  );

  const reconciliationItems = useMemo(
    () =>
      computeReconciliationReport(
        selectedCurrency,
        cashByCurrency[selectedCurrency] || 0,
        invoices,
        payments,
        stockBalances,
        trialBalance
      ),
    [selectedCurrency, cashByCurrency, invoices, payments, stockBalances, trialBalance]
  );

  const fxSummary = useMemo(
    () =>
      computeConsolidatedFxSummary(effectiveAccounts, entries, lines, {
        egpToSdg: Number(egpToSdgInput) || 12.5,
        usdToSdg: Number(usdToSdgInput) || 600
      }),
    [effectiveAccounts, entries, lines, egpToSdgInput, usdToSdgInput]
  );

  const handleSeedChartOfAccounts = async () => {
    try {
      await Promise.all(
        DEFAULT_CHART_OF_ACCOUNTS.map((acc) =>
          ErpDataService.saveRecord('accounting_accounts', acc.syncUuid, acc)
        )
      );
      notify('ok', 'تمت مزامنة دليل الحسابات القياسي (18 حساباً) إلى السحابة بنجاح');
    } catch (err: any) {
      notify('err', err?.message || 'تعذر مزامنة دليل الحسابات');
    }
  };

  const handlePostManualEntry = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!canCreate || submitting) return;
    const amt = Number(manualAmount);
    if (!Number.isFinite(amt) || amt <= 0) {
      notify('err', 'أدخل مبلغاً موجباً صحيحاً');
      return;
    }
    if (manualDebitCode === manualCreditCode) {
      notify('err', 'يجب أن يختلف الحساب المدين عن الحساب الدائن');
      return;
    }

    setSubmitting(true);
    try {
      const actor = userProfile?.fullName || userProfile?.username || 'المحاسب';
      const { entry, lines: createdLines } = createBalancedJournalPayload(
        {
          sourceType: 'MANUAL_ADJUSTMENT',
          sourceDocNo: `MAN-${Date.now().toString().slice(-6)}`,
          description: manualDesc.trim(),
          currency: manualCurrency,
          lines: [
            { accountCode: manualDebitCode, debit: amt, memo: manualDesc.trim() },
            { accountCode: manualCreditCode, credit: amt, memo: manualDesc.trim() }
          ],
          createdBy: actor,
          approvedBy: actor
        },
        effectiveAccounts,
        entries
      );

      await Promise.all([
        ErpDataService.saveRecord('journal_entries', entry.syncUuid, entry),
        ...createdLines.map((ln) => ErpDataService.saveRecord('journal_lines', ln.syncUuid, ln))
      ]);
      await ErpDataService.logAudit(
        actor,
        'POST_MANUAL_JOURNAL',
        `ترحيل قيد يومية يدوي متوازن ${entry.entryNo}: ${amt} ${manualCurrency} (مدين ${manualDebitCode} / دائن ${manualCreditCode})`
      );
      setIsManualModalOpen(false);
      setManualDesc('');
      setManualAmount('');
      notify('ok', `تم ترحيل القيد المزدوج المتوازن ${entry.entryNo} بنجاح`);
    } catch (err: any) {
      notify('err', err?.message || 'فشل ترحيل القيد المحاسبي');
    } finally {
      setSubmitting(false);
    }
  };

  const handleConfirmReverse = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!reversingEntry || !canReverse || submitting) return;
    setSubmitting(true);
    try {
      const actor = userProfile?.fullName || userProfile?.username || 'المدير';
      const origLines =
        linesByEntryUuid.get(reversingEntry.syncUuid) ||
        lines.filter((l) => Number(l.journalEntryId) === Number(reversingEntry.id));

      const { updatedOriginal, reversalEntry, reversalLines } = buildReversalJournalPayloads(
        reversingEntry,
        origLines,
        reverseReason,
        actor,
        effectiveAccounts
      );

      await Promise.all([
        ErpDataService.saveRecord('journal_entries', updatedOriginal.syncUuid, {
          status: updatedOriginal.status,
          reversedByEntryNo: updatedOriginal.reversedByEntryNo,
          reversalReason: updatedOriginal.reversalReason,
          recordVersion: updatedOriginal.recordVersion,
          updatedAt: updatedOriginal.updatedAt
        }),
        ErpDataService.saveRecord('journal_entries', reversalEntry.syncUuid, reversalEntry),
        ...reversalLines.map((ln) => ErpDataService.saveRecord('journal_lines', ln.syncUuid, ln))
      ]);
      await ErpDataService.logAudit(
        actor,
        'REVERSE_JOURNAL_ENTRY',
        `عكس قيد ${reversingEntry.entryNo} بقيد عكسي ${reversalEntry.entryNo} — السبب: ${reverseReason}`
      );
      setReversingEntry(null);
      setReverseReason('');
      notify('ok', `تم إنشاء القيد العكسي ${reversalEntry.entryNo} وتعليم القيد الأصلي كـ REVERSED`);
    } catch (err: any) {
      notify('err', err?.message || 'تعذر عكس القيد المحاسبي');
    } finally {
      setSubmitting(false);
    }
  };

  const handleExportStatements = (format: 'CSV' | 'PDF') => {
    const title =
      section === 'TRIAL_BALANCE'
        ? `ميزان المراجعة التفصيلي (${selectedCurrency})`
        : section === 'INCOME_STATEMENT'
        ? `قائمة الدخل والأرباح والخسائر (${selectedCurrency})`
        : section === 'BALANCE_SHEET'
        ? `الميزانية العمومية — المركز المالي (${selectedCurrency})`
        : `تقرير مطابقة التشغيل مع الأستاذ العام (${selectedCurrency})`;

    let headers: string[] = [];
    let rows: Array<Array<string | number>> = [];

    if (section === 'TRIAL_BALANCE') {
      headers = ['رمز الحساب', 'اسم الحساب', 'النوع', 'إجمالي مدين', 'إجمالي دائن', 'صافي الرصيد'];
      rows = trialBalance.rows.map((r) => [
        r.accountCode,
        r.accountName,
        r.accountType,
        r.totalDebit.toFixed(2),
        r.totalCredit.toFixed(2),
        r.signedBalance.toFixed(2)
      ]);
    } else if (section === 'INCOME_STATEMENT') {
      headers = ['البند', 'الحساب', 'القيمة'];
      rows = [
        ...incomeStatement.revenueRows.map((r) => ['إيرادات', `${r.accountCode} - ${r.accountName}`, r.signedBalance.toFixed(2)]),
        ...incomeStatement.cogsRows.map((r) => ['تكلفة مبيعات COGS', `${r.accountCode} - ${r.accountName}`, r.signedBalance.toFixed(2)]),
        ...incomeStatement.operatingExpenseRows.map((r) => ['مصروفات تشغيلية', `${r.accountCode} - ${r.accountName}`, r.signedBalance.toFixed(2)]),
        ['صافي الربح / الخسارة', 'الإجمالي الصافي', incomeStatement.netIncome.toFixed(2)]
      ];
    } else if (section === 'BALANCE_SHEET') {
      headers = ['التصنيف', 'الحساب', 'الرصيد'];
      rows = [
        ...balanceSheet.assetRows.map((r) => ['الأصول', `${r.accountCode} - ${r.accountName}`, r.signedBalance.toFixed(2)]),
        ...balanceSheet.liabilityRows.map((r) => ['الالتزامات', `${r.accountCode} - ${r.accountName}`, r.signedBalance.toFixed(2)]),
        ...balanceSheet.equityRows.map((r) => ['حقوق الملكية', `${r.accountCode} - ${r.accountName}`, r.signedBalance.toFixed(2)]),
        ['حقوق الملكية', 'صافي ربح/خسارة الفترة', balanceSheet.currentNetIncome.toFixed(2)]
      ];
    } else {
      headers = ['البند التشغيلي', 'القيمة التشغيلية', 'دفتر الأستاذ GL', 'الفارق', 'الحالة'];
      rows = reconciliationItems.map((it) => [
        it.label,
        it.operationalValue.toFixed(2),
        it.glValue.toFixed(2),
        it.difference.toFixed(2),
        it.status
      ]);
    }

    if (format === 'CSV') {
      ReportExporter.exportToCsv(`PMP_${section}_${selectedCurrency}`, headers, rows);
    } else {
      ReportExporter.exportToPrint(
        title,
        `عملة التقرير المعزولة: ${selectedCurrency} • الحالة: ${trialBalance.isBalanced ? 'متوازن' : 'يوجد فرق'}`,
        headers,
        rows
      );
    }
  };

  return (
    <div className="space-y-5">
      {notice && (
        <div
          className={`p-3.5 rounded-xl border text-xs font-bold flex items-center gap-2 ${
            notice.type === 'ok'
              ? 'bg-emerald-500/10 border-emerald-500/30 text-emerald-400'
              : 'bg-red-500/10 border-red-500/30 text-red-400'
          }`}
        >
          {notice.type === 'ok' ? <CheckCircle2 className="w-4 h-4 shrink-0" /> : <AlertTriangle className="w-4 h-4 shrink-0" />}
          <span>{notice.text}</span>
        </div>
      )}

      {/* Mode Switcher */}
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div className="flex flex-wrap gap-2">
          <button
            type="button"
            onClick={() => setMode('LEDGER')}
            className={`px-4 py-2 rounded-xl text-xs font-bold flex items-center gap-2 border ${
              mode === 'LEDGER'
                ? 'bg-red-600 text-white border-red-600'
                : 'bg-theme-surface text-theme-secondary border-theme'
            }`}
          >
            <BookOpen className="w-4 h-4" />
            <span>دفتر الأستاذ والقيود المزدوجة ({entries.length})</span>
          </button>
          <button
            type="button"
            onClick={() => setMode('STATEMENTS')}
            className={`px-4 py-2 rounded-xl text-xs font-bold flex items-center gap-2 border ${
              mode === 'STATEMENTS'
                ? 'bg-red-600 text-white border-red-600'
                : 'bg-theme-surface text-theme-secondary border-theme'
            }`}
          >
            <Scale className="w-4 h-4" />
            <span>القوائم المالية والمطابقة ({selectedCurrency})</span>
          </button>
        </div>

        <div className="flex flex-wrap gap-2">
          <GlassButton size="sm" variant="secondary" onClick={() => setShowCoaModal(true)}>
            دليل الحسابات ({effectiveAccounts.length})
          </GlassButton>
          {canCreate && (
            <GlassButton size="sm" variant="primary" icon={<Plus className="w-3.5 h-3.5" />} onClick={() => setIsManualModalOpen(true)}>
              قيد يومية يدوي
            </GlassButton>
          )}
        </div>
      </div>

      {/* Mode 1: Ledger & Journal Entries */}
      {mode === 'LEDGER' && (
        <GlassCard className="p-5 space-y-4">
          <div className="flex flex-col sm:flex-row justify-between items-start sm:items-center gap-3 border-b border-theme pb-3">
            <div>
              <h3 className="text-sm font-black text-theme-primary">
                سجل القيود المحاسبية غير القابلة للحذف (Append-Only Double-Entry Ledger)
              </h3>
              <p className="text-[11px] text-theme-muted">
                كل عملية شراء خامات أو صرف إنتاج أو اعتماد جودة أو مبيعات أو خزنة تولّد قيداً متوازناً (المدين = الدائن) تلقائياً.
              </p>
            </div>
            <div className="flex items-center gap-2">
              {accounts.length < DEFAULT_CHART_OF_ACCOUNTS.length && canCreate && (
                <GlassButton size="sm" variant="secondary" onClick={handleSeedChartOfAccounts}>
                  مزامنة الدليل للسحابة
                </GlassButton>
              )}
              <GlassBadge variant="cyan">{sortedEntries.length} قيد مرحّل</GlassBadge>
            </div>
          </div>

          <div className="space-y-3">
            {sortedEntries.map((entry) => {
              const eLines =
                linesByEntryUuid.get(entry.syncUuid) ||
                lines.filter((l) => Number(l.journalEntryId) === Number(entry.id));
              const isReversed = entry.status === 'REVERSED';
              return (
                <div
                  key={entry.syncUuid}
                  className={`p-4 rounded-xl border ${
                    isReversed ? 'bg-amber-500/5 border-amber-500/30' : 'bg-theme-base border-theme'
                  } space-y-2.5 text-xs`}
                >
                  <div className="flex flex-wrap justify-between items-center gap-2">
                    <div className="flex items-center gap-2">
                      <span className="font-mono font-black text-theme-primary">{entry.entryNo}</span>
                      <GlassBadge variant={isReversed ? 'gold' : 'green'}>{entry.status}</GlassBadge>
                      <GlassBadge variant="cyan">{entry.currency}</GlassBadge>
                      <span className="text-[11px] text-theme-muted">{entry.sourceType}</span>
                    </div>
                    <div className="flex items-center gap-3">
                      <span className="font-mono font-bold text-emerald-400">
                        مدين: {Number(entry.totalDebit || 0).toLocaleString()} | دائن: {Number(entry.totalCredit || 0).toLocaleString()}
                      </span>
                      {!isReversed && entry.sourceType !== 'REVERSAL' && canReverse && (
                        <GlassButton
                          size="sm"
                          variant="secondary"
                          icon={<RotateCcw className="w-3.5 h-3.5" />}
                          onClick={() => {
                            setReversingEntry(entry);
                            setReverseReason('');
                          }}
                        >
                          عكس القيد
                        </GlassButton>
                      )}
                    </div>
                  </div>
                  <p className="text-theme-primary font-bold">{entry.description}</p>
                  {isReversed && entry.reversedByEntryNo && (
                    <p className="text-[11px] text-amber-400 font-bold">
                      معكوس بالقيد {entry.reversedByEntryNo} — السبب: {entry.reversalReason}
                    </p>
                  )}
                  <div className="grid grid-cols-1 md:grid-cols-2 gap-1.5 pt-1 border-t border-theme/60">
                    {eLines
                      .slice()
                      .sort((a, b) => (a.lineOrder || 0) - (b.lineOrder || 0))
                      .map((ln) => (
                        <div key={ln.syncUuid} className="flex justify-between items-center text-[11px] px-2.5 py-1 rounded bg-theme-surface/60">
                          <span className="text-theme-secondary truncate">
                            {ln.accountCode} — {ln.accountName}
                          </span>
                          <span className="font-mono font-bold shrink-0">
                            {Number(ln.debit) > 0
                              ? `مدين ${Number(ln.debit).toLocaleString()}`
                              : `دائن ${Number(ln.credit).toLocaleString()}`}
                          </span>
                        </div>
                      ))}
                  </div>
                </div>
              );
            })}
            {sortedEntries.length === 0 && (
              <div className="py-10 text-center text-xs text-theme-muted">
                لا توجد قيود محاسبية مسجلة بعد. ابدأ بتنفيذ أي عملية مبيعات أو مشتريات أو خزنة أو أضف قيداً يدوياً.
              </div>
            )}
          </div>
        </GlassCard>
      )}

      {/* Mode 2: Financial Statements & Reconciliation */}
      {mode === 'STATEMENTS' && (
        <div className="space-y-4">
          <GlassCard className="p-4 flex flex-col lg:flex-row justify-between items-start lg:items-center gap-3">
            <div className="flex flex-wrap items-center gap-2">
              <span className="text-xs font-bold text-theme-secondary">العملة المعزولة للتقرير:</span>
              {(['SDG', 'EGP', 'USD'] as CurrencyCode[]).map((cur) => (
                <button
                  key={cur}
                  type="button"
                  onClick={() => setSelectedCurrency(cur)}
                  className={`px-3.5 py-1.5 rounded-xl text-xs font-black border ${
                    selectedCurrency === cur
                      ? 'bg-emerald-600 text-white border-emerald-600'
                      : 'bg-theme-base text-theme-secondary border-theme'
                  }`}
                >
                  {cur}
                </button>
              ))}
              <button
                type="button"
                onClick={() => setShowFxSummary(!showFxSummary)}
                className="px-3 py-1.5 rounded-xl text-xs font-bold border border-cyan-500/40 bg-cyan-500/10 text-cyan-400 flex items-center gap-1.5"
              >
                <Globe2 className="w-3.5 h-3.5" />
                <span>توحيد العملات FX</span>
              </button>
            </div>

            <div className="flex gap-2">
              <GlassButton size="sm" variant="secondary" icon={<FileSpreadsheet className="w-3.5 h-3.5" />} onClick={() => handleExportStatements('CSV')}>
                تصدير Excel
              </GlassButton>
              <GlassButton size="sm" variant="primary" icon={<Printer className="w-3.5 h-3.5" />} onClick={() => handleExportStatements('PDF')}>
                طباعة PDF
              </GlassButton>
            </div>
          </GlassCard>

          {showFxSummary && (
            <GlassCard className="p-4 border border-cyan-500/30 space-y-3 text-xs">
              <div className="flex flex-wrap items-center justify-between gap-2">
                <h4 className="font-black text-cyan-400">ملخص التوحيد المالي بالعملة الأساسية (SDG) — بأسعار صرف صريحة</h4>
                <div className="flex items-center gap-2">
                  <label className="text-theme-muted">1 EGP =</label>
                  <input
                    type="number"
                    step="any"
                    value={egpToSdgInput}
                    onChange={(e) => setEgpToSdgInput(e.target.value)}
                    className="w-20 px-2 py-1 rounded-lg bg-theme-base border border-theme font-mono"
                  />
                  <label className="text-theme-muted">1 USD =</label>
                  <input
                    type="number"
                    step="any"
                    value={usdToSdgInput}
                    onChange={(e) => setUsdToSdgInput(e.target.value)}
                    className="w-20 px-2 py-1 rounded-lg bg-theme-base border border-theme font-mono"
                  />
                </div>
              </div>
              <div className="grid grid-cols-2 lg:grid-cols-4 gap-3">
                <div className="p-3 rounded-xl bg-theme-base border border-theme">
                  <p className="text-theme-muted">إجمالي الإيرادات الموحد</p>
                  <p className="font-mono font-black text-emerald-400 mt-1">{fxSummary.totalRevenueConsolidated.toLocaleString()} SDG</p>
                </div>
                <div className="p-3 rounded-xl bg-theme-base border border-theme">
                  <p className="text-theme-muted">صافي الربح الموحد</p>
                  <p className="font-mono font-black text-cyan-400 mt-1">{fxSummary.netIncomeConsolidated.toLocaleString()} SDG</p>
                </div>
                <div className="p-3 rounded-xl bg-theme-base border border-theme">
                  <p className="text-theme-muted">إجمالي الأصول الموحد</p>
                  <p className="font-mono font-black text-theme-primary mt-1">{fxSummary.totalAssetsConsolidated.toLocaleString()} SDG</p>
                </div>
                <div className="p-3 rounded-xl bg-theme-base border border-theme">
                  <p className="text-theme-muted">إجمالي الالتزامات الموحد</p>
                  <p className="font-mono font-black text-amber-400 mt-1">{fxSummary.totalLiabilitiesConsolidated.toLocaleString()} SDG</p>
                </div>
              </div>
            </GlassCard>
          )}

          {/* Section Pills */}
          <div className="flex flex-wrap gap-2">
            {[
              { id: 'TRIAL_BALANCE' as const, label: 'ميزان المراجعة', icon: <Scale className="w-3.5 h-3.5" /> },
              { id: 'INCOME_STATEMENT' as const, label: 'قائمة الدخل (الأرباح والخسائر)', icon: <TrendingUp className="w-3.5 h-3.5" /> },
              { id: 'BALANCE_SHEET' as const, label: 'الميزانية العمومية', icon: <Landmark className="w-3.5 h-3.5" /> },
              { id: 'RECONCILIATION' as const, label: 'مطابقة التشغيل مع الأستاذ العام', icon: <CheckCircle2 className="w-3.5 h-3.5" /> }
            ].map((s) => (
              <button
                key={s.id}
                type="button"
                onClick={() => setSection(s.id)}
                className={`px-3.5 py-2 rounded-xl text-xs font-bold flex items-center gap-1.5 border ${
                  section === s.id
                    ? 'bg-blue-600 text-white border-blue-600'
                    : 'bg-theme-surface text-theme-secondary border-theme'
                }`}
              >
                {s.icon}
                <span>{s.label}</span>
              </button>
            ))}
          </div>

          {/* Statement Tables */}
          {section === 'TRIAL_BALANCE' && (
            <GlassCard className="p-5 space-y-3">
              <div className="flex justify-between items-center">
                <h4 className="text-sm font-black text-theme-primary">ميزان المراجعة ({selectedCurrency})</h4>
                <GlassBadge variant={trialBalance.isBalanced ? 'green' : 'red'}>
                  {trialBalance.isBalanced ? 'متوازن (المدين = الدائن)' : 'غير متوازن'}
                </GlassBadge>
              </div>
              <div className="overflow-x-auto">
                <table className="w-full text-xs text-right">
                  <thead>
                    <tr className="border-b border-theme text-theme-muted">
                      <th className="py-2.5 px-3">الكود</th>
                      <th className="py-2.5 px-3">الحساب</th>
                      <th className="py-2.5 px-3">إجمالي مدين</th>
                      <th className="py-2.5 px-3">إجمالي دائن</th>
                      <th className="py-2.5 px-3">صافي الرصيد</th>
                    </tr>
                  </thead>
                  <tbody className="divide-y divide-white/5">
                    {trialBalance.rows.map((r) => (
                      <tr key={r.accountCode}>
                        <td className="py-2 px-3 font-mono font-bold">{r.accountCode}</td>
                        <td className="py-2 px-3">{r.accountName}</td>
                        <td className="py-2 px-3 font-mono text-emerald-400">{r.totalDebit.toLocaleString()}</td>
                        <td className="py-2 px-3 font-mono text-amber-400">{r.totalCredit.toLocaleString()}</td>
                        <td className="py-2 px-3 font-mono font-bold">{r.signedBalance.toLocaleString()} {selectedCurrency}</td>
                      </tr>
                    ))}
                  </tbody>
                  <tfoot>
                    <tr className="border-t-2 border-theme font-black">
                      <td colSpan={2} className="py-3 px-3">الإجمالي الكلي</td>
                      <td className="py-3 px-3 font-mono text-emerald-400">{trialBalance.totalDebit.toLocaleString()}</td>
                      <td className="py-3 px-3 font-mono text-amber-400">{trialBalance.totalCredit.toLocaleString()}</td>
                      <td className="py-3 px-3 font-mono">{selectedCurrency}</td>
                    </tr>
                  </tfoot>
                </table>
              </div>
            </GlassCard>
          )}

          {section === 'INCOME_STATEMENT' && (
            <GlassCard className="p-5 space-y-4 text-xs">
              <h4 className="text-sm font-black text-theme-primary">قائمة الدخل والأرباح والخسائر ({selectedCurrency})</h4>
              <div className="grid grid-cols-2 lg:grid-cols-5 gap-3">
                <div className="p-3 rounded-xl bg-theme-base border border-theme">
                  <p className="text-theme-muted">إجمالي الإيرادات</p>
                  <p className="font-mono font-black text-emerald-400 mt-1">{incomeStatement.totalRevenue.toLocaleString()}</p>
                </div>
                <div className="p-3 rounded-xl bg-theme-base border border-theme">
                  <p className="text-theme-muted">تكلفة المبيعات COGS</p>
                  <p className="font-mono font-black text-amber-400 mt-1">{incomeStatement.totalCogs.toLocaleString()}</p>
                </div>
                <div className="p-3 rounded-xl bg-theme-base border border-theme">
                  <p className="text-theme-muted">مجمل الربح</p>
                  <p className="font-mono font-black text-cyan-400 mt-1">{incomeStatement.grossProfit.toLocaleString()}</p>
                </div>
                <div className="p-3 rounded-xl bg-theme-base border border-theme">
                  <p className="text-theme-muted">مصروفات التشغيل</p>
                  <p className="font-mono font-black text-red-400 mt-1">{incomeStatement.totalOperatingExpenses.toLocaleString()}</p>
                </div>
                <div className="p-3 rounded-xl bg-theme-base border border-emerald-500/40">
                  <p className="text-theme-muted">صافي الربح / الخسارة</p>
                  <p className={`font-mono font-black mt-1 ${incomeStatement.netIncome >= 0 ? 'text-emerald-400' : 'text-red-400'}`}>
                    {incomeStatement.netIncome.toLocaleString()} {selectedCurrency}
                  </p>
                </div>
              </div>
            </GlassCard>
          )}

          {section === 'BALANCE_SHEET' && (
            <GlassCard className="p-5 space-y-4 text-xs">
              <div className="flex justify-between items-center">
                <h4 className="text-sm font-black text-theme-primary">الميزانية العمومية — معادلة المركز المالي ({selectedCurrency})</h4>
                <GlassBadge variant={balanceSheet.isBalanced ? 'green' : 'red'}>
                  {balanceSheet.isBalanced ? 'الأصول = الالتزامات + حقوق الملكية ✓' : `فرق ${balanceSheet.imbalanceDiff.toFixed(2)}`}
                </GlassBadge>
              </div>
              <div className="grid grid-cols-1 md:grid-cols-3 gap-3">
                <div className="p-4 rounded-xl bg-theme-base border border-theme space-y-2">
                  <p className="font-black text-emerald-400">الأصول: {balanceSheet.totalAssets.toLocaleString()} {selectedCurrency}</p>
                  {balanceSheet.assetRows.map((r) => (
                    <div key={r.accountCode} className="flex justify-between text-[11px]">
                      <span>{r.accountName}</span>
                      <span className="font-mono font-bold">{r.signedBalance.toLocaleString()}</span>
                    </div>
                  ))}
                </div>
                <div className="p-4 rounded-xl bg-theme-base border border-theme space-y-2">
                  <p className="font-black text-amber-400">الالتزامات: {balanceSheet.totalLiabilities.toLocaleString()} {selectedCurrency}</p>
                  {balanceSheet.liabilityRows.map((r) => (
                    <div key={r.accountCode} className="flex justify-between text-[11px]">
                      <span>{r.accountName}</span>
                      <span className="font-mono font-bold">{r.signedBalance.toLocaleString()}</span>
                    </div>
                  ))}
                </div>
                <div className="p-4 rounded-xl bg-theme-base border border-theme space-y-2">
                  <p className="font-black text-cyan-400">
                    حقوق الملكية + صافي الربح: {(balanceSheet.totalEquityBeforeNetIncome + balanceSheet.currentNetIncome).toLocaleString()} {selectedCurrency}
                  </p>
                  {balanceSheet.equityRows.map((r) => (
                    <div key={r.accountCode} className="flex justify-between text-[11px]">
                      <span>{r.accountName}</span>
                      <span className="font-mono font-bold">{r.signedBalance.toLocaleString()}</span>
                    </div>
                  ))}
                  <div className="flex justify-between text-[11px] pt-1 border-t border-theme font-bold">
                    <span>صافي ربح/خسارة الفترة</span>
                    <span className="font-mono">{balanceSheet.currentNetIncome.toLocaleString()}</span>
                  </div>
                </div>
              </div>
            </GlassCard>
          )}

          {section === 'RECONCILIATION' && (
            <GlassCard className="p-5 space-y-3">
              <h4 className="text-sm font-black text-theme-primary">مطابقة الأرصدة التشغيلية مع دفتر الأستاذ العام ({selectedCurrency})</h4>
              <div className="space-y-2">
                {reconciliationItems.map((it) => (
                  <div key={it.label} className="p-3.5 rounded-xl bg-theme-base border border-theme flex flex-wrap justify-between items-center gap-3 text-xs">
                    <div>
                      <p className="font-bold text-theme-primary">{it.label}</p>
                      <p className="text-[11px] text-theme-muted font-mono">
                        تشغيلي: {it.operationalValue.toLocaleString()} | الأستاذ GL: {it.glValue.toLocaleString()} | الفارق: {it.difference.toLocaleString()}
                      </p>
                    </div>
                    <GlassBadge variant={it.status === 'MATCHED' ? 'green' : 'gold'}>
                      {it.status === 'MATCHED' ? 'متطابق ✓' : 'فارق أرصدة سابقة'}
                    </GlassBadge>
                  </div>
                ))}
              </div>
            </GlassCard>
          )}
        </div>
      )}

      {/* Manual Entry Modal */}
      <Modal isOpen={isManualModalOpen} onClose={() => setIsManualModalOpen(false)} title="إنشاء قيد يومية يدوي مزدوج">
        <form onSubmit={handlePostManualEntry} className="space-y-3 text-xs">
          <div>
            <label className="block font-bold text-theme-secondary mb-1">العملة</label>
            <select
              value={manualCurrency}
              onChange={(e) => setManualCurrency(e.target.value as CurrencyCode)}
              className="w-full px-3 py-2 rounded-xl bg-theme-surface border border-theme text-theme-primary"
            >
              <option value="SDG">SDG - جنيه سوداني</option>
              <option value="EGP">EGP - جنيه مصري</option>
              <option value="USD">USD - دولار أمريكي</option>
            </select>
          </div>
          <div>
            <label className="block font-bold text-theme-secondary mb-1">الحساب المدين (من حـ/)</label>
            <select
              value={manualDebitCode}
              onChange={(e) => setManualDebitCode(e.target.value)}
              className="w-full px-3 py-2 rounded-xl bg-theme-surface border border-theme text-theme-primary"
            >
              {effectiveAccounts.map((a) => (
                <option key={a.accountCode} value={a.accountCode}>
                  {a.accountCode} — {a.nameAr}
                </option>
              ))}
            </select>
          </div>
          <div>
            <label className="block font-bold text-theme-secondary mb-1">الحساب الدائن (إلى حـ/)</label>
            <select
              value={manualCreditCode}
              onChange={(e) => setManualCreditCode(e.target.value)}
              className="w-full px-3 py-2 rounded-xl bg-theme-surface border border-theme text-theme-primary"
            >
              {effectiveAccounts.map((a) => (
                <option key={a.accountCode} value={a.accountCode}>
                  {a.accountCode} — {a.nameAr}
                </option>
              ))}
            </select>
          </div>
          <div>
            <label className="block font-bold text-theme-secondary mb-1">المبلغ ({manualCurrency}) *</label>
            <input
              type="number"
              step="any"
              required
              value={manualAmount}
              onChange={(e) => setManualAmount(e.target.value)}
              className="w-full px-3 py-2 rounded-xl bg-theme-surface border border-theme text-theme-primary font-mono"
            />
          </div>
          <div>
            <label className="block font-bold text-theme-secondary mb-1">البيان المحاسبي *</label>
            <input
              type="text"
              required
              value={manualDesc}
              onChange={(e) => setManualDesc(e.target.value)}
              className="w-full px-3 py-2 rounded-xl bg-theme-surface border border-theme text-theme-primary"
            />
          </div>
          <div className="flex justify-end gap-2 pt-2">
            <GlassButton type="button" variant="secondary" onClick={() => setIsManualModalOpen(false)}>إلغاء</GlassButton>
            <GlassButton type="submit" variant="primary" disabled={submitting}>ترحيل القيد المتوازن</GlassButton>
          </div>
        </form>
      </Modal>

      {/* Reverse Entry Modal */}
      <Modal isOpen={Boolean(reversingEntry)} onClose={() => setReversingEntry(null)} title={`عكس القيد المحاسبي ${reversingEntry?.entryNo || ''}`}>
        <form onSubmit={handleConfirmReverse} className="space-y-3 text-xs">
          <p className="text-theme-secondary">
            سيتم إنشاء قيد عكسي معاكس بالكامل (Append-Only) دون حذف القيد الأصلي حفاظاً على المسار الرقابي.
          </p>
          <div>
            <label className="block font-bold text-theme-secondary mb-1">سبب العكس الإلزامي *</label>
            <input
              type="text"
              required
              value={reverseReason}
              onChange={(e) => setReverseReason(e.target.value)}
              className="w-full px-3 py-2 rounded-xl bg-theme-surface border border-theme text-theme-primary"
            />
          </div>
          <div className="flex justify-end gap-2 pt-2">
            <GlassButton type="button" variant="secondary" onClick={() => setReversingEntry(null)}>تراجع</GlassButton>
            <GlassButton type="submit" variant="primary" disabled={submitting}>اعتماد القيد العكسي</GlassButton>
          </div>
        </form>
      </Modal>

      {/* Chart of Accounts Modal */}
      <Modal isOpen={showCoaModal} onClose={() => setShowCoaModal(false)} title="دليل الحسابات الصناعي القياسي (Chart of Accounts)">
        <div className="max-h-96 overflow-y-auto space-y-1.5 text-xs">
          {effectiveAccounts.map((a) => (
            <div key={a.accountCode} className="flex justify-between items-center px-3 py-2 rounded-lg bg-theme-surface border border-theme">
              <span className="font-bold text-theme-primary">{a.accountCode} — {a.nameAr}</span>
              <GlassBadge variant="cyan">{a.accountType}</GlassBadge>
            </div>
          ))}
        </div>
      </Modal>
    </div>
  );
};
