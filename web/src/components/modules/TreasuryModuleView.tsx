import React, { useState } from 'react';
import {
  Coins,
  ArrowUpRight,
  ArrowDownLeft,
  RefreshCw,
  Plus,
  Search,
  Filter,
  DollarSign,
  FileSpreadsheet,
  Printer,
  CheckCircle2,
  Calendar
} from 'lucide-react';
import { GlassCard } from '../ui/GlassCard';
import { GlassBadge } from '../ui/GlassBadge';
import { GlassButton } from '../ui/GlassButton';
import { Modal } from '../ui/Modal';
import { Cashbox, TreasuryMovement, InterTreasuryTransfer, DailyClosing } from '../../types/erp';
import { ErpDataService } from '../../services/ErpDataService';
import { ReportExporter } from '../../services/ReportExporter';
import { useLanguage } from '../../context/LanguageContext';
import { useAuth } from '../../context/AuthContext';

export const TreasuryModuleView: React.FC = () => {
  const { language } = useLanguage();
  const { canPerformAction, userProfile } = useAuth();

  const [cashboxes, setCashboxes] = useState<Cashbox[]>(() => ErpDataService.getInitialCashboxes());
  const [movements, setMovements] = useState<TreasuryMovement[]>(() => ErpDataService.getInitialTreasuryMovements());
  const [searchTerm, setSearchTerm] = useState('');
  const [typeFilter, setTypeFilter] = useState('ALL');
  const [isNewMoveOpen, setIsNewMoveOpen] = useState(false);

  // New Movement Form State
  const [newMove, setNewMove] = useState({
    type: 'INCOME' as 'INCOME' | 'EXPENSE',
    category: 'تحصيل مبيعات',
    amount: 500000,
    currency: 'SDG' as 'SDG' | 'EGP' | 'USD',
    cashboxName: 'خزنة الجنيه السوداني الرئيسية (SDG)',
    description: '',
    referenceNo: ''
  });

  const filteredMovements = movements.filter(m => {
    const matchesSearch =
      m.description.toLowerCase().includes(searchTerm.toLowerCase()) ||
      m.category.toLowerCase().includes(searchTerm.toLowerCase()) ||
      m.cashboxName.toLowerCase().includes(searchTerm.toLowerCase());

    const matchesType = typeFilter === 'ALL' || m.type === typeFilter;
    return matchesSearch && matchesType;
  });

  const handleCreateMovement = async (e: React.FormEvent) => {
    e.preventDefault();
    const created: TreasuryMovement = {
      id: Date.now(),
      type: newMove.type,
      category: newMove.category,
      amount: Number(newMove.amount),
      currency: newMove.currency,
      cashboxName: newMove.cashboxName,
      description: newMove.description,
      employeeName: userProfile?.fullName || 'أمين الخزينة',
      referenceNo: newMove.referenceNo || `REC-${Date.now().toString().slice(-4)}`,
      date: new Date().toISOString().split('T')[0],
      timestamp: Date.now()
    };

    // Update local cashbox balance
    setCashboxes(prev =>
      prev.map(c => {
        if (c.name === newMove.cashboxName) {
          const delta = newMove.type === 'INCOME' ? Number(newMove.amount) : -Number(newMove.amount);
          return { ...c, balance: c.balance + delta, lastUpdated: 'الآن' };
        }
        return c;
      })
    );

    setMovements(prev => [created, ...prev]);
    await ErpDataService.saveRecord('treasury_transactions', `TR-${Date.now()}`, created);
    setIsNewMoveOpen(false);
  };

  const handleExportCsv = () => {
    const headers = ['النوع', 'البند', 'المبلغ', 'العملة', 'الخزنة المستهدفة', 'البيان', 'المسؤول', 'المرجع', 'التاريخ'];
    const rows = filteredMovements.map(m => [
      m.type === 'INCOME' ? 'قبض / توريد' : 'صرف / منصرف',
      m.category,
      m.amount,
      m.currency,
      m.cashboxName,
      m.description,
      m.employeeName,
      m.referenceNo || '-',
      m.date
    ]);
    ReportExporter.exportToCsv('pmp_treasury_movements', headers, rows);
  };

  const handlePrint = () => {
    const headers = ['النوع', 'البند', 'المبلغ', 'العملة', 'الخزنة', 'البيان', 'المسؤول'];
    const rows = filteredMovements.map(m => [
      m.type === 'INCOME' ? 'قبض' : 'صرف',
      m.category,
      m.amount.toLocaleString(),
      m.currency,
      m.cashboxName,
      m.description,
      m.employeeName
    ]);
    ReportExporter.exportToPrint('كشف حركة الخزينة والسيولة النقدية - PMP PAINTS', headers, rows);
  };

  return (
    <div className="space-y-6">
      {/* Title Bar */}
      <div className="flex flex-col sm:flex-row justify-between items-start sm:items-center gap-4 pb-4 border-b border-theme">
        <div>
          <div className="flex items-center gap-2">
            <h2 className="text-xl font-black text-theme-primary">
              {language === 'ar' ? 'الخزن والسيولة النقدية (Treasury & Cash Vaults)' : 'Multi-Currency Treasury Management'}
            </h2>
            <GlassBadge variant="green">
              {cashboxes.length} {language === 'ar' ? 'خزن معتمدة' : 'Vaults'}
            </GlassBadge>
          </div>
          <p className="text-xs text-theme-secondary mt-1">
            {language === 'ar'
              ? 'إدارة الخزن الثلاث (SDG, EGP, USD)، التحويلات اللحظية، وتوثيق سندات القبض والصرف.'
              : 'Multi-currency cash boxes (SDG, EGP, USD), transfers, receipts, and daily closings.'}
          </p>
        </div>

        <div className="flex items-center gap-2 flex-wrap">
          <GlassButton variant="secondary" icon={<FileSpreadsheet className="w-4 h-4" />} onClick={handleExportCsv} className="text-xs">
            {language === 'ar' ? 'تصدير Excel/CSV' : 'Export CSV'}
          </GlassButton>
          <GlassButton variant="secondary" icon={<Printer className="w-4 h-4" />} onClick={handlePrint} className="text-xs">
            {language === 'ar' ? 'طباعة' : 'Print'}
          </GlassButton>
          {canPerformAction('العمليات المالية') && (
            <GlassButton variant="primary" icon={<Plus className="w-4 h-4" />} onClick={() => setIsNewMoveOpen(true)} className="text-xs">
              {language === 'ar' ? 'سند قبض / صرف جديد' : 'New Movement'}
            </GlassButton>
          )}
        </div>
      </div>

      {/* Cashbox Balance Cards Grid */}
      <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-4">
        {cashboxes.map(box => (
          <GlassCard key={box.id} className="p-4 relative">
            <div className="flex justify-between items-start mb-2">
              <span className="font-bold text-theme-primary text-xs leading-snug">{box.name}</span>
              <GlassBadge variant={box.currency === 'SDG' ? 'red' : box.currency === 'EGP' ? 'gold' : 'cyan'}>
                {box.currency}
              </GlassBadge>
            </div>
            <div className="text-xl font-black text-theme-primary my-1">
              {box.balance.toLocaleString()} {box.currency}
            </div>
            <div className="text-[10px] text-theme-muted flex justify-between items-center mt-2 pt-2 border-t border-theme">
              <span>{box.branch}</span>
              <span>{box.lastUpdated}</span>
            </div>
          </GlassCard>
        ))}
      </div>

      {/* Filter and Search */}
      <GlassCard className="p-4">
        <div className="grid grid-cols-1 sm:grid-cols-3 gap-3 text-xs">
          <div className="relative sm:col-span-2">
            <Search className="w-4 h-4 text-theme-muted absolute right-3 top-2.5" />
            <input
              type="text"
              placeholder={language === 'ar' ? 'بحث بالبيان، البند، أو اسم الخزنة...' : 'Search cash movements...'}
              value={searchTerm}
              onChange={(e) => setSearchTerm(e.target.value)}
              className="w-full pr-9 pl-3 py-2 rounded-lg bg-theme-surface border border-theme text-theme-primary text-xs focus:outline-none focus:border-pmp-primary"
            />
          </div>

          <div>
            <select
              value={typeFilter}
              onChange={(e) => setTypeFilter(e.target.value)}
              className="w-full px-3 py-2 rounded-lg bg-theme-surface border border-theme text-theme-primary text-xs focus:outline-none focus:border-pmp-primary"
            >
              <option value="ALL">{language === 'ar' ? 'جميع الحركات (قبض وصرف)' : 'All (Income & Expense)'}</option>
              <option value="INCOME">{language === 'ar' ? 'سندات القبض والتوريد (Income)' : 'Income Only'}</option>
              <option value="EXPENSE">{language === 'ar' ? 'سندات الصرف والمنصرفات (Expense)' : 'Expense Only'}</option>
            </select>
          </div>
        </div>
      </GlassCard>

      {/* Movements Table */}
      <GlassCard className="p-0 overflow-hidden">
        <div className="overflow-x-auto">
          <table className="w-full text-right border-collapse text-xs">
            <thead>
              <tr className="bg-theme-surface/70 border-b border-theme text-theme-muted font-bold">
                <th className="py-3 px-4">{language === 'ar' ? 'نوع الحركة' : 'Type'}</th>
                <th className="py-3 px-4">{language === 'ar' ? 'البند والتصنيف' : 'Category'}</th>
                <th className="py-3 px-4">{language === 'ar' ? 'المبلغ والعملة' : 'Amount'}</th>
                <th className="py-3 px-4">{language === 'ar' ? 'الخزنة المستهدفة' : 'Cashbox'}</th>
                <th className="py-3 px-4">{language === 'ar' ? 'البيان والتفاصيل' : 'Description'}</th>
                <th className="py-3 px-4">{language === 'ar' ? 'المسؤول والمرجع' : 'Staff / Ref'}</th>
                <th className="py-3 px-4">{language === 'ar' ? 'التاريخ' : 'Date'}</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-theme">
              {filteredMovements.map(m => (
                <tr key={m.id} className="hover:bg-theme-surface/40 transition-colors">
                  <td className="py-3.5 px-4">
                    <span className={`inline-flex items-center gap-1 font-bold ${m.type === 'INCOME' ? 'text-green-500' : 'text-red-500'}`}>
                      {m.type === 'INCOME' ? <ArrowDownLeft className="w-4 h-4" /> : <ArrowUpRight className="w-4 h-4" />}
                      {m.type === 'INCOME' ? (language === 'ar' ? 'قبض' : 'Income') : (language === 'ar' ? 'صرف' : 'Expense')}
                    </span>
                  </td>
                  <td className="py-3.5 px-4 font-semibold text-theme-primary">{m.category}</td>
                  <td className="py-3.5 px-4 font-black text-sm text-theme-primary">
                    {m.amount.toLocaleString()} {m.currency}
                  </td>
                  <td className="py-3.5 px-4 text-theme-secondary text-[11px]">{m.cashboxName}</td>
                  <td className="py-3.5 px-4 text-theme-secondary">{m.description}</td>
                  <td className="py-3.5 px-4">
                    <span className="text-theme-primary block">{m.employeeName}</span>
                    <span className="text-[10px] text-theme-muted font-mono">{m.referenceNo}</span>
                  </td>
                  <td className="py-3.5 px-4 font-mono text-theme-muted">{m.date}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </GlassCard>

      {/* New Movement Modal */}
      <Modal
        isOpen={isNewMoveOpen}
        onClose={() => setIsNewMoveOpen(false)}
        title={language === 'ar' ? 'تسجيل حركة خزينة جديدة (قبض / صرف)' : 'Record Treasury Transaction'}
        maxWidth="max-w-lg"
      >
        <form onSubmit={handleCreateMovement} className="space-y-4 text-xs">
          <div className="grid grid-cols-2 gap-3">
            <div>
              <label className="block font-semibold text-theme-primary mb-1">
                {language === 'ar' ? 'نوع السند' : 'Type'}
              </label>
              <select
                value={newMove.type}
                onChange={(e) => setNewMove({ ...newMove, type: e.target.value as any })}
                className="w-full px-3 py-2 rounded-lg bg-theme-surface border border-theme text-theme-primary focus:outline-none focus:border-pmp-primary"
              >
                <option value="INCOME">{language === 'ar' ? 'سند قبض وتوريد نقدي' : 'Income / Deposit'}</option>
                <option value="EXPENSE">{language === 'ar' ? 'سند صرف منصرفات وتشغيل' : 'Expense / Payment'}</option>
              </select>
            </div>

            <div>
              <label className="block font-semibold text-theme-primary mb-1">
                {language === 'ar' ? 'الخزنة' : 'Target Cashbox'}
              </label>
              <select
                value={newMove.cashboxName}
                onChange={(e) => {
                  const box = cashboxes.find(c => c.name === e.target.value);
                  setNewMove({ ...newMove, cashboxName: e.target.value, currency: box?.currency || 'SDG' });
                }}
                className="w-full px-3 py-2 rounded-lg bg-theme-surface border border-theme text-theme-primary focus:outline-none focus:border-pmp-primary"
              >
                {cashboxes.map(c => (
                  <option key={c.id} value={c.name}>{c.name}</option>
                ))}
              </select>
            </div>
          </div>

          <div className="grid grid-cols-2 gap-3">
            <div>
              <label className="block font-semibold text-theme-primary mb-1">
                {language === 'ar' ? 'المبلغ' : 'Amount'}
              </label>
              <input
                type="number"
                required
                value={newMove.amount}
                onChange={(e) => setNewMove({ ...newMove, amount: Number(e.target.value) })}
                className="w-full px-3 py-2 rounded-lg bg-theme-surface border border-theme text-theme-primary focus:outline-none focus:border-pmp-primary"
              />
            </div>

            <div>
              <label className="block font-semibold text-theme-primary mb-1">
                {language === 'ar' ? 'بند الحركة' : 'Category'}
              </label>
              <input
                type="text"
                required
                value={newMove.category}
                onChange={(e) => setNewMove({ ...newMove, category: e.target.value })}
                placeholder={language === 'ar' ? 'مثال: تحصيل مبيعات، نولون، شراء خامات' : 'Category...'}
                className="w-full px-3 py-2 rounded-lg bg-theme-surface border border-theme text-theme-primary focus:outline-none focus:border-pmp-primary"
              />
            </div>
          </div>

          <div>
            <label className="block font-semibold text-theme-primary mb-1">
              {language === 'ar' ? 'البيان والتفاصيل *' : 'Description *'}
            </label>
            <input
              type="text"
              required
              value={newMove.description}
              onChange={(e) => setNewMove({ ...newMove, description: e.target.value })}
              placeholder={language === 'ar' ? 'اكتب تفاصيل العملية والمستفيد...' : 'Details...'}
              className="w-full px-3 py-2 rounded-lg bg-theme-surface border border-theme text-theme-primary focus:outline-none focus:border-pmp-primary"
            />
          </div>

          <div className="flex justify-end gap-3 pt-3 border-t border-theme">
            <GlassButton variant="secondary" onClick={() => setIsNewMoveOpen(false)}>
              {language === 'ar' ? 'إلغاء' : 'Cancel'}
            </GlassButton>
            <GlassButton type="submit" variant="primary">
              {language === 'ar' ? 'ترحيل واعتماد السند' : 'Post Transaction'}
            </GlassButton>
          </div>
        </form>
      </Modal>
    </div>
  );
};
