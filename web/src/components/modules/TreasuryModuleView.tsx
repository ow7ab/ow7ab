import React, { useEffect, useMemo, useState } from 'react';
import {
  ArrowUpRight,
  ArrowDownLeft,
  ArrowLeftRight,
  Lock,
  Plus,
  Search,
  FileSpreadsheet,
  Printer,
  Trash2,
  Building2,
  CheckCircle2,
  AlertCircle
} from 'lucide-react';
import { GlassCard } from '../ui/GlassCard';
import { GlassBadge } from '../ui/GlassBadge';
import { GlassButton } from '../ui/GlassButton';
import {
  Cashbox,
  TreasuryMovement,
  InterTreasuryTransfer,
  DailyClosing,
  CurrencyCode
} from '../../types/erp';
import { ErpDataService, cashboxDocId, safeId } from '../../services/ErpDataService';
import { ReportExporter } from '../../services/ReportExporter';
import { GRANULAR_ACTIONS } from '../../services/SecurityService';
import { useLanguage } from '../../context/LanguageContext';
import { useAuth } from '../../context/AuthContext';

type TreasurySubTab = 'MOVEMENT' | 'TRANSFER' | 'CLOSING' | 'STATEMENT' | 'CASHBOXES';

export const TreasuryModuleView: React.FC = () => {
  const { language } = useLanguage();
  const { canPerformAction, userProfile } = useAuth();

  const [subTab, setSubTab] = useState<TreasurySubTab>('MOVEMENT');
  const [cashboxes, setCashboxes] = useState<Cashbox[]>([]);
  const [movements, setMovements] = useState<TreasuryMovement[]>([]);
  const [transfers, setTransfers] = useState<InterTreasuryTransfer[]>([]);
  const [closings, setClosings] = useState<DailyClosing[]>([]);

  const [searchTerm, setSearchTerm] = useState('');
  const [directionFilter, setDirectionFilter] = useState<'ALL' | 'وارد' | 'منصرف'>('ALL');
  const [cashboxFilter, setCashboxFilter] = useState('ALL');
  const [statusNotice, setStatusNotice] = useState<{ type: 'ok' | 'err'; text: string } | null>(null);

  // Sub-tab 1: Movement Form
  const [movForm, setMovForm] = useState({
    txDate: new Date().toISOString().split('T')[0],
    cashbox: '',
    currency: 'SDG' as CurrencyCode,
    direction: 'وارد' as 'وارد' | 'منصرف',
    amount: '',
    description: '',
    reference: ''
  });

  // Sub-tab 2: Transfer Form
  const [trfForm, setTrfForm] = useState({
    txDate: new Date().toISOString().split('T')[0],
    sourceCashbox: '',
    destinationCashbox: '',
    sourceCurrency: 'SDG' as CurrencyCode,
    destinationCurrency: 'EGP' as CurrencyCode,
    sourceAmount: '',
    rate: '1',
    description: '',
    reference: ''
  });

  // Sub-tab 3: Daily Closing Form
  const [closeForm, setCloseForm] = useState({
    closeDate: new Date().toISOString().split('T')[0],
    cashbox: '',
    countedBalance: '',
    notes: ''
  });

  // Sub-tab 5: Manage Cashbox Form
  const [boxForm, setBoxForm] = useState({
    name: '',
    currency: 'SDG' as CurrencyCode,
    branch: userProfile?.branch || 'الخرطوم'
  });

  useEffect(() => {
    const unsubs = [
      ErpDataService.subscribeToCollection<Cashbox>('cashboxes', setCashboxes),
      ErpDataService.subscribeToCollection<TreasuryMovement>('movements', setMovements),
      ErpDataService.subscribeToCollection<InterTreasuryTransfer>('transfers', setTransfers),
      ErpDataService.subscribeToCollection<DailyClosing>('daily_closings', setClosings)
    ];
    return () => unsubs.forEach((u) => u());
  }, []);

  const computedBalances = useMemo(
    () => ErpDataService.computeCashboxBalances(cashboxes, movements),
    [cashboxes, movements]
  );

  // Initialize dropdown defaults from live cashboxes
  useEffect(() => {
    if (computedBalances.length > 0) {
      if (!movForm.cashbox) {
        setMovForm((f) => ({
          ...f,
          cashbox: computedBalances[0].name,
          currency: computedBalances[0].currency
        }));
      }
      if (!trfForm.sourceCashbox) {
        const dest = computedBalances[1] || computedBalances[0];
        setTrfForm((f) => ({
          ...f,
          sourceCashbox: computedBalances[0].name,
          sourceCurrency: computedBalances[0].currency,
          destinationCashbox: dest.name,
          destinationCurrency: dest.currency
        }));
      }
      if (!closeForm.cashbox) {
        setCloseForm((f) => ({ ...f, cashbox: computedBalances[0].name }));
      }
    }
  }, [computedBalances]);

  const showMsg = (type: 'ok' | 'err', text: string) => {
    setStatusNotice({ type, text });
    setTimeout(() => setStatusNotice(null), 4000);
  };

  const canWriteFinance =
    canPerformAction(GRANULAR_ACTIONS.FINANCIAL_OPS) || canPerformAction(GRANULAR_ACTIONS.CREATE);
  const canDelete = canPerformAction(GRANULAR_ACTIONS.DELETE);

  const handleSaveMovement = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!canWriteFinance) {
      showMsg('err', 'لا تملك صلاحية تنفيذ العمليات المالية');
      return;
    }
    const amt = Number(movForm.amount);
    if (!movForm.cashbox || !Number.isFinite(amt) || amt <= 0) {
      showMsg('err', 'يرجى اختيار الخزنة وإدخال مبلغ صحيح أكبر من صفر');
      return;
    }

    const id = Date.now();
    const record: TreasuryMovement = {
      id,
      txDate: movForm.txDate,
      cashbox: movForm.cashbox,
      currency: movForm.currency,
      direction: movForm.direction,
      amount: amt,
      description: movForm.description.trim() || `${movForm.direction} نقدي`,
      reference: movForm.reference.trim() || `MOV-${String(id).slice(-5)}`,
      createdBy: userProfile?.fullName || userProfile?.username || 'WEB_USER',
      createdAt: id
    };

    try {
      await ErpDataService.saveRecord('movements', `MOV-${id}`, record, userProfile?.username || 'WEB_USER');
      await ErpDataService.logAudit(
        userProfile?.username || 'WEB',
        'TREASURY_MOVEMENT',
        `${record.direction} ${record.amount} ${record.currency} — ${record.cashbox}`
      );
      setMovForm((f) => ({ ...f, amount: '', description: '', reference: '' }));
      showMsg('ok', 'تم ترحيل الحركة المالية ومزامنتها مع تطبيق الأندرويد بنجاح');
    } catch (err: any) {
      showMsg('err', err?.message || 'فشل حفظ الحركة المالية');
    }
  };

  const handleSaveTransfer = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!canWriteFinance) {
      showMsg('err', 'لا تملك صلاحية التحويل بين الخزن');
      return;
    }
    const srcAmt = Number(trfForm.sourceAmount);
    const rate = Number(trfForm.rate) || 1;
    if (!trfForm.sourceCashbox || !trfForm.destinationCashbox || srcAmt <= 0) {
      showMsg('err', 'يرجى تحديد خزنة المصدر والوجهة والمبلغ بشكل صحيح');
      return;
    }
    if (trfForm.sourceCashbox === trfForm.destinationCashbox) {
      showMsg('err', 'خزنة المصدر والوجهة يجب أن تكونا مختلفتين');
      return;
    }

    const id = Date.now();
    const destAmt = Math.round(srcAmt * rate * 100) / 100;
    const reference = trfForm.reference.trim() || `TRF-${String(id).slice(-5)}`;

    const transferRecord: InterTreasuryTransfer = {
      id,
      reference,
      txDate: trfForm.txDate,
      sourceCashbox: trfForm.sourceCashbox,
      destinationCashbox: trfForm.destinationCashbox,
      sourceCurrency: trfForm.sourceCurrency,
      destinationCurrency: trfForm.destinationCurrency,
      sourceAmount: srcAmt,
      rate,
      destinationAmount: destAmt,
      description: trfForm.description.trim() || `تحويل من ${trfForm.sourceCashbox} إلى ${trfForm.destinationCashbox}`,
      createdBy: userProfile?.fullName || userProfile?.username || 'WEB_USER',
      createdAt: id
    };

    // Android parity: create both OUT and IN ledger entries in `movements`
    const outMov: TreasuryMovement = {
      id: id + 1,
      txDate: trfForm.txDate,
      cashbox: trfForm.sourceCashbox,
      currency: trfForm.sourceCurrency,
      direction: 'منصرف',
      amount: srcAmt,
      description: `تحويل صادر إلى ${trfForm.destinationCashbox} (${transferRecord.description})`,
      reference: `TRF:${reference}:OUT`,
      createdBy: transferRecord.createdBy,
      createdAt: id
    };

    const inMov: TreasuryMovement = {
      id: id + 2,
      txDate: trfForm.txDate,
      cashbox: trfForm.destinationCashbox,
      currency: trfForm.destinationCurrency,
      direction: 'وارد',
      amount: destAmt,
      description: `تحويل وارد من ${trfForm.sourceCashbox} (${transferRecord.description})`,
      reference: `TRF:${reference}:IN`,
      createdBy: transferRecord.createdBy,
      createdAt: id + 1
    };

    try {
      await Promise.all([
        ErpDataService.saveRecord('transfers', safeId(`TRF-${id}-${reference}`, `TRF-${id}`), transferRecord, userProfile?.username),
        ErpDataService.saveRecord('movements', `MOV-${outMov.id}`, outMov, userProfile?.username),
        ErpDataService.saveRecord('movements', `MOV-${inMov.id}`, inMov, userProfile?.username)
      ]);
      await ErpDataService.logAudit(
        userProfile?.username || 'WEB',
        'TREASURY_TRANSFER',
        `تحويل ${srcAmt} ${trfForm.sourceCurrency} -> ${destAmt} ${trfForm.destinationCurrency}`
      );
      setTrfForm((f) => ({ ...f, sourceAmount: '', description: '', reference: '' }));
      showMsg('ok', `تم تنفيذ التحويل المزدوج (${reference}) بنجاح!`);
    } catch (err: any) {
      showMsg('err', err?.message || 'فشل تسجيل التحويل');
    }
  };

  const handleSaveDailyClosing = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!canWriteFinance) {
      showMsg('err', 'لا تملك صلاحية اعتماد الإقفال اليومي');
      return;
    }
    const targetBox = computedBalances.find((b) => b.name === closeForm.cashbox);
    if (!targetBox) {
      showMsg('err', 'يرجى اختيار الخزنة المراد إقفالها');
      return;
    }
    const counted = Number(closeForm.countedBalance);
    if (!Number.isFinite(counted)) {
      showMsg('err', 'أدخل الرصيد الفعلي المعدود');
      return;
    }

    const id = Date.now();
    const record: DailyClosing = {
      id,
      closeDate: closeForm.closeDate,
      cashbox: targetBox.name,
      currency: targetBox.currency,
      bookBalance: targetBox.balance,
      countedBalance: counted,
      difference: counted - targetBox.balance,
      notes: closeForm.notes.trim(),
      preparedBy: userProfile?.fullName || userProfile?.username || 'WEB_USER',
      status: 'closed',
      closedAt: id
    };

    try {
      await ErpDataService.saveRecord('daily_closings', `CLOSE-${id}`, record, userProfile?.username);
      setCloseForm((f) => ({ ...f, countedBalance: '', notes: '' }));
      showMsg('ok', 'تم اعتماد وحفظ محضر الإقفال اليومي للخزنة');
    } catch (err: any) {
      showMsg('err', err?.message || 'تعذر حفظ الإقفال اليومي');
    }
  };

  const handleCreateCashbox = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!canWriteFinance || !boxForm.name.trim()) return;
    const id = Date.now();
    const record: Cashbox = {
      id,
      name: boxForm.name.trim(),
      balance: 0,
      currency: boxForm.currency,
      branch: boxForm.branch.trim() || 'الخرطوم',
      lastUpdated: new Date().toISOString().split('T')[0]
    };
    try {
      await ErpDataService.saveRecord(
        'cashboxes',
        cashboxDocId(record.name, id),
        record,
        userProfile?.username
      );
      setBoxForm((f) => ({ ...f, name: '' }));
      showMsg('ok', `تم إنشاء الخزنة "${record.name}" ومزامنتها`);
    } catch (err: any) {
      showMsg('err', err?.message || 'فشل إنشاء الخزنة');
    }
  };

  const handleDeleteMovement = async (mov: TreasuryMovement) => {
    if (!canDelete) return;
    const docId = (mov as any)._docId || `MOV-${mov.id}`;
    await ErpDataService.deleteRecord('movements', docId, mov.id, userProfile?.username);
    showMsg('ok', 'تم حذف السند وإرسال علامة الحذف لبقية الأجهزة');
  };

  const handleDeleteCashbox = async (box: { id: number; docId: string; name: string }) => {
    if (!canDelete) return;
    await ErpDataService.deleteRecord('cashboxes', box.docId, box.id || box.name, userProfile?.username);
    showMsg('ok', `تم حذف الخزنة ${box.name}`);
  };

  const sortedMovements = useMemo(() => {
    return [...movements]
      .sort((a, b) => (b.createdAt || 0) - (a.createdAt || 0))
      .filter((m) => {
        const cashName = (m.cashbox || m.cashboxName || '').trim();
        const dir =
          m.direction || (m.type === 'INCOME' ? 'وارد' : m.type === 'EXPENSE' ? 'منصرف' : 'وارد');
        const matchesSearch =
          !searchTerm ||
          (m.description || '').toLowerCase().includes(searchTerm.toLowerCase()) ||
          cashName.toLowerCase().includes(searchTerm.toLowerCase()) ||
          (m.reference || '').toLowerCase().includes(searchTerm.toLowerCase());
        const matchesDir = directionFilter === 'ALL' || dir === directionFilter;
        const matchesBox = cashboxFilter === 'ALL' || cashName === cashboxFilter;
        return matchesSearch && matchesDir && matchesBox;
      });
  }, [movements, searchTerm, directionFilter, cashboxFilter]);

  const handleExportCsv = () => {
    const headers = ['التاريخ', 'الخزنة', 'الاتجاه', 'المبلغ', 'العملة', 'البيان', 'المرجع', 'المسؤول'];
    const rows = sortedMovements.map((m) => [
      m.txDate || m.date || '-',
      m.cashbox || m.cashboxName || '-',
      m.direction || (m.type === 'INCOME' ? 'وارد' : 'منصرف'),
      m.amount,
      m.currency,
      m.description,
      m.reference || '-',
      m.createdBy || m.employeeName || '-'
    ]);
    ReportExporter.exportToCsv('pmp_treasury_ledger', headers, rows);
  };

  const handlePrint = () => {
    const headers = ['التاريخ', 'الخزنة', 'الاتجاه', 'المبلغ', 'العملة', 'البيان', 'المرجع'];
    const rows = sortedMovements.map((m) => [
      m.txDate || m.date || '-',
      m.cashbox || m.cashboxName || '-',
      m.direction || (m.type === 'INCOME' ? 'وارد' : 'منصرف'),
      Number(m.amount || 0).toLocaleString(),
      m.currency,
      m.description,
      m.reference || '-'
    ]);
    ReportExporter.exportToPrint('كشف حساب وحركات الخزن - PMP PAINTS', headers, rows);
  };

  return (
    <div className="space-y-6">
      {/* Header & Android 5 Sub-Tabs */}
      <GlassCard className="p-5 sm:p-6">
        <div className="flex flex-col sm:flex-row justify-between items-start sm:items-center gap-4 pb-4 border-b border-theme">
          <div>
            <div className="flex flex-wrap items-center gap-2">
              <h2 className="text-xl font-black text-theme-primary">
                {language === 'ar' ? 'الخزن والسيولة النقدية' : 'Treasury & Cash Vaults'}
              </h2>
              <GlassBadge variant="green">{computedBalances.length} خزنة</GlassBadge>
            </div>
            <p className="text-xs text-theme-secondary mt-1">
              {language === 'ar'
                ? 'نفس تبويبات تطبيق الأندرويد الخمسة: الحركات، التحويلات، إقفال اليومية، كشف الحساب، وإدارة الخزن.'
                : 'Unified 5 Android Treasury sub-tabs: Movements, Transfers, Closings, Statement & Cashboxes.'}
            </p>
          </div>

          <div className="flex items-center gap-2 flex-wrap">
            <GlassButton
              variant="secondary"
              icon={<FileSpreadsheet className="w-4 h-4" />}
              onClick={handleExportCsv}
              className="text-xs"
            >
              Excel / CSV
            </GlassButton>
            <GlassButton
              variant="secondary"
              icon={<Printer className="w-4 h-4" />}
              onClick={handlePrint}
              className="text-xs"
            >
              طباعة
            </GlassButton>
          </div>
        </div>

        {/* 5 Android Sub-Tabs */}
        <div className="flex items-center gap-2 mt-4 overflow-x-auto pb-1">
          {[
            { id: 'MOVEMENT' as TreasurySubTab, label: 'حركة مالية' },
            { id: 'TRANSFER' as TreasurySubTab, label: 'تحويل خزن' },
            { id: 'CLOSING' as TreasurySubTab, label: 'إقفال اليومية' },
            { id: 'STATEMENT' as TreasurySubTab, label: `كشف الحساب (${movements.length})` },
            { id: 'CASHBOXES' as TreasurySubTab, label: 'إدارة الخزن' }
          ].map((t) => (
            <button
              key={t.id}
              type="button"
              onClick={() => setSubTab(t.id)}
              className={`px-4 py-2 rounded-xl text-xs font-bold whitespace-nowrap transition-all ${
                subTab === t.id
                  ? 'bg-pmp-primary text-white shadow-md'
                  : 'bg-theme-base border border-theme text-theme-secondary hover:text-theme-primary'
              }`}
            >
              {t.label}
            </button>
          ))}
        </div>
      </GlassCard>

      {statusNotice && (
        <div
          className={`p-3.5 rounded-xl border text-xs flex items-center gap-2 ${
            statusNotice.type === 'ok'
              ? 'bg-emerald-500/10 border-emerald-500/30 text-emerald-400'
              : 'bg-red-500/10 border-red-500/30 text-red-400'
          }`}
        >
          {statusNotice.type === 'ok' ? (
            <CheckCircle2 className="w-4 h-4 shrink-0" />
          ) : (
            <AlertCircle className="w-4 h-4 shrink-0" />
          )}
          <span>{statusNotice.text}</span>
        </div>
      )}

      {/* Live Cashbox Cards (Computed Dynamically from `movements`) */}
      <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-4">
        {computedBalances.map((box) => (
          <GlassCard key={`${box.name}_${box.currency}`} className="p-4">
            <div className="flex justify-between items-start gap-2 mb-2">
              <span className="font-bold text-theme-primary text-xs leading-snug">{box.name}</span>
              <GlassBadge
                variant={box.currency === 'SDG' ? 'red' : box.currency === 'EGP' ? 'gold' : 'cyan'}
              >
                {box.currency}
              </GlassBadge>
            </div>
            <div className="text-xl font-black text-theme-primary my-1">
              {box.balance.toLocaleString()} <span className="text-xs font-bold">{box.currency}</span>
            </div>
            <div className="text-[10px] text-theme-muted pt-2 border-t border-theme flex justify-between">
              <span>{box.branch}</span>
              <span className="font-mono">رصيد فعلي محسوب</span>
            </div>
          </GlassCard>
        ))}
      </div>

      {/* Sub-Tab 1: Record Financial Movement */}
      {subTab === 'MOVEMENT' && (
        <GlassCard className="p-6">
          <h3 className="text-sm font-bold text-theme-primary mb-4 flex items-center gap-2">
            <Plus className="w-4 h-4 text-pmp-primary" />
            تسجيل حركة مالية (وارد / منصرف)
          </h3>

          <form onSubmit={handleSaveMovement} className="grid grid-cols-1 md:grid-cols-2 gap-4 text-xs">
            <div>
              <label className="block font-bold text-theme-secondary mb-1">الخزنة *</label>
              <select
                required
                value={movForm.cashbox}
                onChange={(e) => {
                  const box = computedBalances.find((b) => b.name === e.target.value);
                  setMovForm({
                    ...movForm,
                    cashbox: e.target.value,
                    currency: box?.currency || movForm.currency
                  });
                }}
                className="w-full px-3 py-2.5 rounded-xl bg-theme-base border border-theme text-theme-primary"
              >
                {computedBalances.length === 0 && <option value="">لا توجد خزن — أضف خزنة أولاً</option>}
                {computedBalances.map((b) => (
                  <option key={`${b.name}_${b.currency}`} value={b.name}>
                    {b.name} ({b.currency}) — الرصيد: {b.balance.toLocaleString()}
                  </option>
                ))}
              </select>
            </div>

            <div>
              <label className="block font-bold text-theme-secondary mb-1">نوع الحركة *</label>
              <select
                value={movForm.direction}
                onChange={(e) => setMovForm({ ...movForm, direction: e.target.value as 'وارد' | 'منصرف' })}
                className="w-full px-3 py-2.5 rounded-xl bg-theme-base border border-theme text-theme-primary"
              >
                <option value="وارد">وارد (سند قبض / توريد للخزنة)</option>
                <option value="منصرف">منصرف (سند صرف / مصروفات)</option>
              </select>
            </div>

            <div>
              <label className="block font-bold text-theme-secondary mb-1">المبلغ ({movForm.currency}) *</label>
              <input
                type="number"
                step="any"
                required
                value={movForm.amount}
                onChange={(e) => setMovForm({ ...movForm, amount: e.target.value })}
                placeholder="0"
                className="w-full px-3 py-2.5 rounded-xl bg-theme-base border border-theme text-theme-primary font-mono"
              />
            </div>

            <div>
              <label className="block font-bold text-theme-secondary mb-1">التاريخ</label>
              <input
                type="date"
                value={movForm.txDate}
                onChange={(e) => setMovForm({ ...movForm, txDate: e.target.value })}
                className="w-full px-3 py-2.5 rounded-xl bg-theme-base border border-theme text-theme-primary font-mono"
              />
            </div>

            <div>
              <label className="block font-bold text-theme-secondary mb-1">البيان / التفاصيل *</label>
              <input
                type="text"
                required
                value={movForm.description}
                onChange={(e) => setMovForm({ ...movForm, description: e.target.value })}
                placeholder="مثال: تحصيل عميل، مصروف نقل، نولون..."
                className="w-full px-3 py-2.5 rounded-xl bg-theme-base border border-theme text-theme-primary"
              />
            </div>

            <div>
              <label className="block font-bold text-theme-secondary mb-1">رقم المرجع / الإيصال</label>
              <input
                type="text"
                value={movForm.reference}
                onChange={(e) => setMovForm({ ...movForm, reference: e.target.value })}
                placeholder="اختياري (يولد تلقائياً)"
                className="w-full px-3 py-2.5 rounded-xl bg-theme-base border border-theme text-theme-primary font-mono"
              />
            </div>

            <div className="md:col-span-2 flex justify-end pt-2">
              <GlassButton type="submit" variant="primary" disabled={!canWriteFinance}>
                ترحيل واعتماد الحركة المالية
              </GlassButton>
            </div>
          </form>
        </GlassCard>
      )}

      {/* Sub-Tab 2: Inter-Treasury Transfer */}
      {subTab === 'TRANSFER' && (
        <div className="space-y-6">
          <GlassCard className="p-6">
            <h3 className="text-sm font-bold text-theme-primary mb-4 flex items-center gap-2">
              <ArrowLeftRight className="w-4 h-4 text-pmp-cyan" />
              تحويل نقدي بين الخزن (مع سعر الصرف)
            </h3>

            <form onSubmit={handleSaveTransfer} className="grid grid-cols-1 md:grid-cols-2 gap-4 text-xs">
              <div>
                <label className="block font-bold text-theme-secondary mb-1">من خزنة (المصدر)</label>
                <select
                  value={trfForm.sourceCashbox}
                  onChange={(e) => {
                    const b = computedBalances.find((x) => x.name === e.target.value);
                    setTrfForm({
                      ...trfForm,
                      sourceCashbox: e.target.value,
                      sourceCurrency: b?.currency || 'SDG'
                    });
                  }}
                  className="w-full px-3 py-2.5 rounded-xl bg-theme-base border border-theme text-theme-primary"
                >
                  {computedBalances.map((b) => (
                    <option key={`src_${b.name}_${b.currency}`} value={b.name}>
                      {b.name} ({b.currency}) — {b.balance.toLocaleString()}
                    </option>
                  ))}
                </select>
              </div>

              <div>
                <label className="block font-bold text-theme-secondary mb-1">إلى خزنة (الوجهة)</label>
                <select
                  value={trfForm.destinationCashbox}
                  onChange={(e) => {
                    const b = computedBalances.find((x) => x.name === e.target.value);
                    setTrfForm({
                      ...trfForm,
                      destinationCashbox: e.target.value,
                      destinationCurrency: b?.currency || 'EGP'
                    });
                  }}
                  className="w-full px-3 py-2.5 rounded-xl bg-theme-base border border-theme text-theme-primary"
                >
                  {computedBalances.map((b) => (
                    <option key={`dst_${b.name}_${b.currency}`} value={b.name}>
                      {b.name} ({b.currency}) — {b.balance.toLocaleString()}
                    </option>
                  ))}
                </select>
              </div>

              <div>
                <label className="block font-bold text-theme-secondary mb-1">
                  المبلغ المحوّل ({trfForm.sourceCurrency})
                </label>
                <input
                  type="number"
                  step="any"
                  required
                  value={trfForm.sourceAmount}
                  onChange={(e) => setTrfForm({ ...trfForm, sourceAmount: e.target.value })}
                  className="w-full px-3 py-2.5 rounded-xl bg-theme-base border border-theme text-theme-primary font-mono"
                />
              </div>

              <div>
                <label className="block font-bold text-theme-secondary mb-1">
                  سعر التحويل ({trfForm.sourceCurrency} → {trfForm.destinationCurrency})
                </label>
                <input
                  type="number"
                  step="any"
                  required
                  value={trfForm.rate}
                  onChange={(e) => setTrfForm({ ...trfForm, rate: e.target.value })}
                  className="w-full px-3 py-2.5 rounded-xl bg-theme-base border border-theme text-theme-primary font-mono"
                />
                {trfForm.sourceAmount && (
                  <span className="text-[11px] text-emerald-400 font-bold mt-1 block">
                    الصافي المستلم:{' '}
                    {(
                      (Number(trfForm.sourceAmount) || 0) * (Number(trfForm.rate) || 1)
                    ).toLocaleString()}{' '}
                    {trfForm.destinationCurrency}
                  </span>
                )}
              </div>

              <div>
                <label className="block font-bold text-theme-secondary mb-1">البيان</label>
                <input
                  type="text"
                  value={trfForm.description}
                  onChange={(e) => setTrfForm({ ...trfForm, description: e.target.value })}
                  placeholder="وصف التحويل بين الفروع أو الخزن"
                  className="w-full px-3 py-2.5 rounded-xl bg-theme-base border border-theme text-theme-primary"
                />
              </div>

              <div>
                <label className="block font-bold text-theme-secondary mb-1">المرجع والتاريخ</label>
                <input
                  type="date"
                  value={trfForm.txDate}
                  onChange={(e) => setTrfForm({ ...trfForm, txDate: e.target.value })}
                  className="w-full px-3 py-2.5 rounded-xl bg-theme-base border border-theme text-theme-primary font-mono"
                />
              </div>

              <div className="md:col-span-2 flex justify-end">
                <GlassButton type="submit" variant="primary" disabled={!canWriteFinance}>
                  تنفيذ التحويل المزدوج والمزامنة
                </GlassButton>
              </div>
            </form>
          </GlassCard>

          <GlassCard className="p-6">
            <h4 className="text-sm font-bold text-theme-primary mb-3">
              سجل التحويلات بين الخزن ({transfers.length})
            </h4>
            <div className="overflow-x-auto">
              <table className="w-full text-right text-xs min-w-[560px]">
                <thead>
                  <tr className="border-b border-theme text-theme-muted">
                    <th className="py-2">المرجع</th>
                    <th className="py-2">التاريخ</th>
                    <th className="py-2">من خزنة</th>
                    <th className="py-2">إلى خزنة</th>
                    <th className="py-2">المبلغ الصادر</th>
                    <th className="py-2">المبلغ الوارد</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-theme">
                  {transfers
                    .sort((a, b) => (b.createdAt || 0) - (a.createdAt || 0))
                    .map((t) => (
                      <tr key={t.id}>
                        <td className="py-2.5 font-mono font-bold text-pmp-cyan">{t.reference}</td>
                        <td className="py-2.5 font-mono text-theme-muted">{t.txDate}</td>
                        <td className="py-2.5">{t.sourceCashbox}</td>
                        <td className="py-2.5">{t.destinationCashbox}</td>
                        <td className="py-2.5 font-mono text-red-400">
                          -{Number(t.sourceAmount).toLocaleString()} {t.sourceCurrency}
                        </td>
                        <td className="py-2.5 font-mono text-emerald-400">
                          +{Number(t.destinationAmount).toLocaleString()} {t.destinationCurrency}
                        </td>
                      </tr>
                    ))}
                </tbody>
              </table>
            </div>
          </GlassCard>
        </div>
      )}

      {/* Sub-Tab 3: Daily Closings */}
      {subTab === 'CLOSING' && (
        <div className="grid grid-cols-1 lg:grid-cols-12 gap-6">
          <GlassCard className="p-6 lg:col-span-5">
            <h3 className="text-sm font-bold text-theme-primary mb-4 flex items-center gap-2">
              <Lock className="w-4 h-4 text-pmp-gold" />
              إقفال ومطابقة اليومية
            </h3>
            <form onSubmit={handleSaveDailyClosing} className="space-y-3 text-xs">
              <div>
                <label className="block font-bold text-theme-secondary mb-1">الخزنة</label>
                <select
                  value={closeForm.cashbox}
                  onChange={(e) => setCloseForm({ ...closeForm, cashbox: e.target.value })}
                  className="w-full px-3 py-2 rounded-xl bg-theme-base border border-theme text-theme-primary"
                >
                  {computedBalances.map((b) => (
                    <option key={b.name} value={b.name}>
                      {b.name} (الدفتري: {b.balance.toLocaleString()} {b.currency})
                    </option>
                  ))}
                </select>
              </div>
              <div>
                <label className="block font-bold text-theme-secondary mb-1">الرصيد الفعلي المعدود</label>
                <input
                  type="number"
                  step="any"
                  required
                  value={closeForm.countedBalance}
                  onChange={(e) => setCloseForm({ ...closeForm, countedBalance: e.target.value })}
                  className="w-full px-3 py-2 rounded-xl bg-theme-base border border-theme text-theme-primary font-mono"
                />
              </div>
              <div>
                <label className="block font-bold text-theme-secondary mb-1">تاريخ الإقفال</label>
                <input
                  type="date"
                  value={closeForm.closeDate}
                  onChange={(e) => setCloseForm({ ...closeForm, closeDate: e.target.value })}
                  className="w-full px-3 py-2 rounded-xl bg-theme-base border border-theme text-theme-primary font-mono"
                />
              </div>
              <div>
                <label className="block font-bold text-theme-secondary mb-1">ملاحظات العجز/الزيادة</label>
                <input
                  type="text"
                  value={closeForm.notes}
                  onChange={(e) => setCloseForm({ ...closeForm, notes: e.target.value })}
                  className="w-full px-3 py-2 rounded-xl bg-theme-base border border-theme text-theme-primary"
                />
              </div>
              <GlassButton type="submit" variant="primary" className="w-full justify-center" disabled={!canWriteFinance}>
                اعتماد الإقفال اليومي
              </GlassButton>
            </form>
          </GlassCard>

          <GlassCard className="p-6 lg:col-span-7">
            <h4 className="text-sm font-bold text-theme-primary mb-3">سجل الإقفالات المعتمدة ({closings.length})</h4>
            <div className="overflow-x-auto">
              <table className="w-full text-right text-xs min-w-[460px]">
                <thead>
                  <tr className="border-b border-theme text-theme-muted">
                    <th className="py-2">التاريخ</th>
                    <th className="py-2">الخزنة</th>
                    <th className="py-2">الدفتري</th>
                    <th className="py-2">المعدود</th>
                    <th className="py-2">الفرق</th>
                    <th className="py-2">المسؤول</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-theme">
                  {closings
                    .sort((a, b) => (b.closedAt || 0) - (a.closedAt || 0))
                    .map((c) => (
                      <tr key={c.id}>
                        <td className="py-2.5 font-mono">{c.closeDate}</td>
                        <td className="py-2.5 font-bold">{c.cashbox}</td>
                        <td className="py-2.5 font-mono">{Number(c.bookBalance).toLocaleString()}</td>
                        <td className="py-2.5 font-mono">{Number(c.countedBalance).toLocaleString()}</td>
                        <td className={`py-2.5 font-mono font-bold ${c.difference === 0 ? 'text-emerald-400' : 'text-amber-400'}`}>
                          {c.difference} {c.currency}
                        </td>
                        <td className="py-2.5 text-theme-muted">{c.preparedBy}</td>
                      </tr>
                    ))}
                </tbody>
              </table>
            </div>
          </GlassCard>
        </div>
      )}

      {/* Sub-Tab 4: Account Statement */}
      {subTab === 'STATEMENT' && (
        <div className="space-y-4">
          <GlassCard className="p-4">
            <div className="grid grid-cols-1 sm:grid-cols-3 gap-3 text-xs">
              <div className="relative">
                <Search className="w-4 h-4 text-theme-muted absolute right-3 top-2.5" />
                <input
                  type="text"
                  placeholder="بحث في البيان أو المرجع..."
                  value={searchTerm}
                  onChange={(e) => setSearchTerm(e.target.value)}
                  className="w-full pr-9 pl-3 py-2 rounded-lg bg-theme-base border border-theme text-theme-primary"
                />
              </div>
              <select
                value={cashboxFilter}
                onChange={(e) => setCashboxFilter(e.target.value)}
                className="px-3 py-2 rounded-lg bg-theme-base border border-theme text-theme-primary"
              >
                <option value="ALL">كافة الخزن</option>
                {computedBalances.map((b) => (
                  <option key={b.name} value={b.name}>
                    {b.name}
                  </option>
                ))}
              </select>
              <select
                value={directionFilter}
                onChange={(e) => setDirectionFilter(e.target.value as any)}
                className="px-3 py-2 rounded-lg bg-theme-base border border-theme text-theme-primary"
              >
                <option value="ALL">جميع الحركات (وارد ومنصرف)</option>
                <option value="وارد">الوارد فقط (سندات قبض)</option>
                <option value="منصرف">المنصرف فقط (سندات صرف)</option>
              </select>
            </div>
          </GlassCard>

          <GlassCard className="p-0 overflow-hidden">
            <div className="overflow-x-auto">
              <table className="w-full text-right border-collapse text-xs min-w-[650px]">
                <thead>
                  <tr className="bg-theme-surface/80 border-b border-theme text-theme-muted">
                    <th className="py-3 px-4">التاريخ</th>
                    <th className="py-3 px-4">الاتجاه</th>
                    <th className="py-3 px-4">المبلغ</th>
                    <th className="py-3 px-4">الخزنة</th>
                    <th className="py-3 px-4">البيان</th>
                    <th className="py-3 px-4">المرجع / المسؤول</th>
                    {canDelete && <th className="py-3 px-4">إجراء</th>}
                  </tr>
                </thead>
                <tbody className="divide-y divide-theme">
                  {sortedMovements.map((m) => {
                    const dir = m.direction || (m.type === 'INCOME' ? 'وارد' : 'منصرف');
                    const isIn = dir === 'وارد';
                    return (
                      <tr key={m.id} className="hover:bg-theme-surface/40">
                        <td className="py-3 px-4 font-mono text-theme-muted">{m.txDate || m.date}</td>
                        <td className="py-3 px-4">
                          <span className={`inline-flex items-center gap-1 font-bold ${isIn ? 'text-emerald-400' : 'text-red-400'}`}>
                            {isIn ? <ArrowDownLeft className="w-4 h-4" /> : <ArrowUpRight className="w-4 h-4" />}
                            {dir}
                          </span>
                        </td>
                        <td className="py-3 px-4 font-mono font-black text-theme-primary">
                          {Number(m.amount || 0).toLocaleString()} {m.currency}
                        </td>
                        <td className="py-3 px-4 text-theme-secondary">{m.cashbox || m.cashboxName}</td>
                        <td className="py-3 px-4 text-theme-primary">{m.description}</td>
                        <td className="py-3 px-4">
                          <span className="font-mono text-[10px] text-pmp-cyan block">{m.reference}</span>
                          <span className="text-[11px] text-theme-muted">{m.createdBy || m.employeeName}</span>
                        </td>
                        {canDelete && (
                          <td className="py-3 px-4">
                            <button
                              type="button"
                              onClick={() => handleDeleteMovement(m)}
                              className="p-1.5 rounded-lg text-red-400 hover:bg-red-500/10"
                              title="حذف السند"
                            >
                              <Trash2 className="w-4 h-4" />
                            </button>
                          </td>
                        )}
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            </div>
          </GlassCard>
        </div>
      )}

      {/* Sub-Tab 5: Manage Cashboxes */}
      {subTab === 'CASHBOXES' && (
        <div className="grid grid-cols-1 lg:grid-cols-12 gap-6">
          <GlassCard className="p-6 lg:col-span-5">
            <h3 className="text-sm font-bold text-theme-primary mb-4 flex items-center gap-2">
              <Building2 className="w-4 h-4 text-pmp-primary" />
              إضافة خزنة جديدة
            </h3>
            <form onSubmit={handleCreateCashbox} className="space-y-3 text-xs">
              <div>
                <label className="block font-bold text-theme-secondary mb-1">اسم الخزنة *</label>
                <input
                  type="text"
                  required
                  value={boxForm.name}
                  onChange={(e) => setBoxForm({ ...boxForm, name: e.target.value })}
                  placeholder="مثال: الخزنة الرئيسية أو خزنة مصر"
                  className="w-full px-3 py-2 rounded-xl bg-theme-base border border-theme text-theme-primary"
                />
              </div>
              <div>
                <label className="block font-bold text-theme-secondary mb-1">العملة</label>
                <select
                  value={boxForm.currency}
                  onChange={(e) => setBoxForm({ ...boxForm, currency: e.target.value as CurrencyCode })}
                  className="w-full px-3 py-2 rounded-xl bg-theme-base border border-theme text-theme-primary"
                >
                  <option value="SDG">SDG — جنيه سوداني</option>
                  <option value="EGP">EGP — جنيه مصري</option>
                  <option value="USD">USD — دولار أمريكي</option>
                </select>
              </div>
              <div>
                <label className="block font-bold text-theme-secondary mb-1">الفرع</label>
                <input
                  type="text"
                  value={boxForm.branch}
                  onChange={(e) => setBoxForm({ ...boxForm, branch: e.target.value })}
                  className="w-full px-3 py-2 rounded-xl bg-theme-base border border-theme text-theme-primary"
                />
              </div>
              <GlassButton type="submit" variant="primary" className="w-full justify-center" disabled={!canWriteFinance}>
                حفظ الخزنة في السحابة
              </GlassButton>
            </form>
          </GlassCard>

          <GlassCard className="p-6 lg:col-span-7">
            <h4 className="text-sm font-bold text-theme-primary mb-3">الخزن المعتمدة ({computedBalances.length})</h4>
            <div className="space-y-2.5 text-xs">
              {computedBalances.map((box) => (
                <div
                  key={`${box.name}_${box.currency}`}
                  className="p-3 rounded-xl bg-theme-base border border-theme flex items-center justify-between gap-3"
                >
                  <div>
                    <span className="font-bold text-theme-primary block">{box.name}</span>
                    <span className="text-[11px] text-theme-muted">
                      {box.branch} • {box.currency} • الرصيد المحسوب: {box.balance.toLocaleString()}
                    </span>
                  </div>
                  {canDelete && (
                    <button
                      type="button"
                      onClick={() => handleDeleteCashbox(box)}
                      className="p-2 rounded-lg text-red-400 hover:bg-red-500/10"
                      title="حذف الخزنة"
                    >
                      <Trash2 className="w-4 h-4" />
                    </button>
                  )}
                </div>
              ))}
            </div>
          </GlassCard>
        </div>
      )}
    </div>
  );
};
