import React, { useState } from 'react';
import {
  FileText,
  Plus,
  Search,
  Filter,
  DollarSign,
  Printer,
  FileSpreadsheet,
  CheckCircle2,
  Clock,
  User,
  ShoppingBag
} from 'lucide-react';
import { GlassCard } from '../ui/GlassCard';
import { GlassBadge } from '../ui/GlassBadge';
import { GlassButton } from '../ui/GlassButton';
import { Modal } from '../ui/Modal';
import { SalesInvoice } from '../../types/erp';
import { ErpDataService } from '../../services/ErpDataService';
import { ReportExporter } from '../../services/ReportExporter';
import { useLanguage } from '../../context/LanguageContext';
import { useAuth } from '../../context/AuthContext';

export const SalesModuleView: React.FC = () => {
  const { language } = useLanguage();
  const { canPerformAction, userProfile } = useAuth();

  const [invoices, setInvoices] = useState<SalesInvoice[]>(() => ErpDataService.getInitialSalesInvoices());
  const [searchTerm, setSearchTerm] = useState('');
  const [statusFilter, setStatusFilter] = useState('ALL');
  const [isNewInvoiceOpen, setIsNewInvoiceOpen] = useState(false);

  // New Invoice Form State
  const [newInv, setNewInv] = useState({
    customerName: '',
    totalAmount: 1500000,
    discount: 50000,
    paidAmount: 1450000,
    currency: 'SDG' as 'SDG' | 'EGP' | 'USD',
    paymentType: 'CASH' as 'CASH' | 'INSTALLMENT' | 'TRANSFER'
  });

  const filteredInvoices = invoices.filter(inv => {
    const matchesSearch =
      inv.invoiceNumber.toLowerCase().includes(searchTerm.toLowerCase()) ||
      inv.customerName.toLowerCase().includes(searchTerm.toLowerCase());

    const matchesStatus = statusFilter === 'ALL' || inv.status === statusFilter;
    return matchesSearch && matchesStatus;
  });

  const handleCreateInvoice = async (e: React.FormEvent) => {
    e.preventDefault();
    const invoiceNumber = `INV-2026-${(invoices.length + 111).toString()}`;
    const finalAmount = newInv.totalAmount - newInv.discount;
    const remainingAmount = finalAmount - newInv.paidAmount;
    const status = remainingAmount <= 0 ? 'PAID' : newInv.paidAmount > 0 ? 'PARTIAL' : 'UNPAID';

    const created: SalesInvoice = {
      id: Date.now(),
      invoiceNumber,
      customerId: Date.now(),
      customerName: newInv.customerName,
      totalAmount: Number(newInv.totalAmount),
      discount: Number(newInv.discount),
      finalAmount,
      paidAmount: Number(newInv.paidAmount),
      remainingAmount,
      currency: newInv.currency,
      paymentType: newInv.paymentType,
      status,
      createdBy: userProfile?.username || 'sales_agent',
      date: new Date().toISOString().split('T')[0],
      timestamp: Date.now()
    };

    setInvoices(prev => [created, ...prev]);
    await ErpDataService.saveRecord('sales_invoices', invoiceNumber, created);
    setIsNewInvoiceOpen(false);
  };

  const handleExportCsv = () => {
    const headers = ['رقم الفاتورة', 'اسم العميل', 'الإجمالي', 'الخصم', 'الصافي', 'المدفوع', 'المتبقي', 'العملة', 'طريقة الدفع', 'الحالة', 'التاريخ'];
    const rows = filteredInvoices.map(inv => [
      inv.invoiceNumber,
      inv.customerName,
      inv.totalAmount,
      inv.discount,
      inv.finalAmount,
      inv.paidAmount,
      inv.remainingAmount,
      inv.currency,
      inv.paymentType,
      inv.status,
      inv.date
    ]);
    ReportExporter.exportToCsv('pmp_sales_invoices', headers, rows);
  };

  const handlePrint = () => {
    const headers = ['الفاتورة', 'العميل', 'الصافي', 'المدفوع', 'المتبقي', 'العملة', 'الحالة'];
    const rows = filteredInvoices.map(inv => [
      inv.invoiceNumber,
      inv.customerName,
      inv.finalAmount.toLocaleString(),
      inv.paidAmount.toLocaleString(),
      inv.remainingAmount.toLocaleString(),
      inv.currency,
      inv.status
    ]);
    ReportExporter.exportToPrint('سجل فواتير المبيعات الصادرة - PMP PAINTS', headers, rows);
  };

  return (
    <div className="space-y-6">
      {/* Title Bar */}
      <div className="flex flex-col sm:flex-row justify-between items-start sm:items-center gap-4 pb-4 border-b border-theme">
        <div>
          <div className="flex items-center gap-2">
            <h2 className="text-xl font-black text-theme-primary">
              {language === 'ar' ? 'المبيعات والفواتير (Sales & Invoicing)' : 'Sales & Invoicing Management'}
            </h2>
            <GlassBadge variant="red">
              {invoices.length} {language === 'ar' ? 'فاتورة' : 'Invoices'}
            </GlassBadge>
          </div>
          <p className="text-xs text-theme-secondary mt-1">
            {language === 'ar'
              ? 'إصدار الفواتير اللحظية، مدفوعات العملاء، حساب الخصومات، والربط بالخزينة.'
              : 'Invoice generation, customer ledgers, discounts, multi-currency receipts, and payment logs.'}
          </p>
        </div>

        <div className="flex items-center gap-2 flex-wrap">
          <GlassButton variant="secondary" icon={<FileSpreadsheet className="w-4 h-4" />} onClick={handleExportCsv} className="text-xs">
            {language === 'ar' ? 'تصدير Excel/CSV' : 'Export CSV'}
          </GlassButton>
          <GlassButton variant="secondary" icon={<Printer className="w-4 h-4" />} onClick={handlePrint} className="text-xs">
            {language === 'ar' ? 'طباعة' : 'Print'}
          </GlassButton>
          {canPerformAction('صلاحية الإنشاء') && (
            <GlassButton variant="primary" icon={<Plus className="w-4 h-4" />} onClick={() => setIsNewInvoiceOpen(true)} className="text-xs">
              {language === 'ar' ? 'إصدار فاتورة بيع' : 'New Invoice'}
            </GlassButton>
          )}
        </div>
      </div>

      {/* Filter and Search */}
      <GlassCard className="p-4">
        <div className="grid grid-cols-1 sm:grid-cols-3 gap-3 text-xs">
          <div className="relative sm:col-span-2">
            <Search className="w-4 h-4 text-theme-muted absolute right-3 top-2.5" />
            <input
              type="text"
              placeholder={language === 'ar' ? 'بحث برقم الفاتورة أو اسم العميل...' : 'Search invoices...'}
              value={searchTerm}
              onChange={(e) => setSearchTerm(e.target.value)}
              className="w-full pr-9 pl-3 py-2 rounded-lg bg-theme-surface border border-theme text-theme-primary text-xs focus:outline-none focus:border-pmp-primary"
            />
          </div>

          <div>
            <select
              value={statusFilter}
              onChange={(e) => setStatusFilter(e.target.value)}
              className="w-full px-3 py-2 rounded-lg bg-theme-surface border border-theme text-theme-primary text-xs focus:outline-none focus:border-pmp-primary"
            >
              <option value="ALL">{language === 'ar' ? 'جميع الحالات' : 'All Statuses'}</option>
              <option value="PAID">{language === 'ar' ? 'مدفوعة بالكامل (Paid)' : 'Paid'}</option>
              <option value="PARTIAL">{language === 'ar' ? 'سداد جزئي (Partial)' : 'Partial'}</option>
              <option value="UNPAID">{language === 'ar' ? 'غير مدفوعة (Unpaid)' : 'Unpaid'}</option>
            </select>
          </div>
        </div>
      </GlassCard>

      {/* Invoices Table */}
      <GlassCard className="p-0 overflow-hidden">
        <div className="overflow-x-auto">
          <table className="w-full text-right border-collapse text-xs">
            <thead>
              <tr className="bg-theme-surface/70 border-b border-theme text-theme-muted font-bold">
                <th className="py-3 px-4">{language === 'ar' ? 'رقم الفاتورة' : 'Invoice #'}</th>
                <th className="py-3 px-4">{language === 'ar' ? 'العميل' : 'Customer'}</th>
                <th className="py-3 px-4">{language === 'ar' ? 'صافي القيمة' : 'Net Amount'}</th>
                <th className="py-3 px-4">{language === 'ar' ? 'المدفوع' : 'Paid'}</th>
                <th className="py-3 px-4">{language === 'ar' ? 'المتبقي (مديونية)' : 'Remaining'}</th>
                <th className="py-3 px-4">{language === 'ar' ? 'طريقة السداد' : 'Payment'}</th>
                <th className="py-3 px-4">{language === 'ar' ? 'الحالة' : 'Status'}</th>
                <th className="py-3 px-4">{language === 'ar' ? 'التاريخ' : 'Date'}</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-theme">
              {filteredInvoices.map(inv => (
                <tr key={inv.id} className="hover:bg-theme-surface/40 transition-colors">
                  <td className="py-3.5 px-4 font-mono font-bold text-pmp-primary">{inv.invoiceNumber}</td>
                  <td className="py-3.5 px-4 font-bold text-theme-primary text-sm">{inv.customerName}</td>
                  <td className="py-3.5 px-4 font-black text-sm text-theme-primary">
                    {inv.finalAmount.toLocaleString()} {inv.currency}
                  </td>
                  <td className="py-3.5 px-4 text-green-500 font-bold">
                    {inv.paidAmount.toLocaleString()} {inv.currency}
                  </td>
                  <td className="py-3.5 px-4 font-bold text-theme-muted">
                    {inv.remainingAmount > 0 ? `${inv.remainingAmount.toLocaleString()} ${inv.currency}` : '-'}
                  </td>
                  <td className="py-3.5 px-4 text-theme-secondary text-[11px]">{inv.paymentType}</td>
                  <td className="py-3.5 px-4">
                    <GlassBadge variant={inv.status === 'PAID' ? 'green' : inv.status === 'PARTIAL' ? 'gold' : 'red'}>
                      {inv.status === 'PAID' ? 'مدفوعة' : inv.status === 'PARTIAL' ? 'سداد جزئي' : 'غير مدفوعة'}
                    </GlassBadge>
                  </td>
                  <td className="py-3.5 px-4 font-mono text-theme-muted">{inv.date}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </GlassCard>

      {/* New Invoice Modal */}
      <Modal
        isOpen={isNewInvoiceOpen}
        onClose={() => setIsNewInvoiceOpen(false)}
        title={language === 'ar' ? 'إصدار فاتورة بيع جديدة للعميل' : 'Create Sales Invoice'}
        maxWidth="max-w-lg"
      >
        <form onSubmit={handleCreateInvoice} className="space-y-4 text-xs">
          <div>
            <label className="block font-semibold text-theme-primary mb-1">
              {language === 'ar' ? 'اسم العميل أو المنشأة *' : 'Customer Name *'}
            </label>
            <input
              type="text"
              required
              value={newInv.customerName}
              onChange={(e) => setNewInv({ ...newInv, customerName: e.target.value })}
              placeholder={language === 'ar' ? 'مثال: مؤسسة النهضة للديكور' : 'Customer name...'}
              className="w-full px-3 py-2 rounded-lg bg-theme-surface border border-theme text-theme-primary focus:outline-none focus:border-pmp-primary"
            />
          </div>

          <div className="grid grid-cols-2 gap-3">
            <div>
              <label className="block font-semibold text-theme-primary mb-1">
                {language === 'ar' ? 'إجمالي الفاتورة' : 'Total Amount'}
              </label>
              <input
                type="number"
                required
                value={newInv.totalAmount}
                onChange={(e) => setNewInv({ ...newInv, totalAmount: Number(e.target.value) })}
                className="w-full px-3 py-2 rounded-lg bg-theme-surface border border-theme text-theme-primary focus:outline-none focus:border-pmp-primary"
              />
            </div>

            <div>
              <label className="block font-semibold text-theme-primary mb-1">
                {language === 'ar' ? 'الخصم الممنوح' : 'Discount'}
              </label>
              <input
                type="number"
                value={newInv.discount}
                onChange={(e) => setNewInv({ ...newInv, discount: Number(e.target.value) })}
                className="w-full px-3 py-2 rounded-lg bg-theme-surface border border-theme text-theme-primary focus:outline-none focus:border-pmp-primary"
              />
            </div>
          </div>

          <div className="grid grid-cols-2 gap-3">
            <div>
              <label className="block font-semibold text-theme-primary mb-1">
                {language === 'ar' ? 'المبلغ المسدد نقداً / فوري' : 'Paid Amount'}
              </label>
              <input
                type="number"
                required
                value={newInv.paidAmount}
                onChange={(e) => setNewInv({ ...newInv, paidAmount: Number(e.target.value) })}
                className="w-full px-3 py-2 rounded-lg bg-theme-surface border border-theme text-theme-primary focus:outline-none focus:border-pmp-primary"
              />
            </div>

            <div>
              <label className="block font-semibold text-theme-primary mb-1">
                {language === 'ar' ? 'العملة' : 'Currency'}
              </label>
              <select
                value={newInv.currency}
                onChange={(e) => setNewInv({ ...newInv, currency: e.target.value as any })}
                className="w-full px-3 py-2 rounded-lg bg-theme-surface border border-theme text-theme-primary focus:outline-none focus:border-pmp-primary"
              >
                <option value="SDG">جنيه سوداني (SDG)</option>
                <option value="EGP">جنيه مصري (EGP)</option>
                <option value="USD">دولار أمريكي (USD)</option>
              </select>
            </div>
          </div>

          <div>
            <label className="block font-semibold text-theme-primary mb-1">
              {language === 'ar' ? 'طريقة السداد' : 'Payment Type'}
            </label>
            <select
              value={newInv.paymentType}
              onChange={(e) => setNewInv({ ...newInv, paymentType: e.target.value as any })}
              className="w-full px-3 py-2 rounded-lg bg-theme-surface border border-theme text-theme-primary focus:outline-none focus:border-pmp-primary"
            >
              <option value="CASH">{language === 'ar' ? 'نقدي فوري (Cash)' : 'Cash'}</option>
              <option value="TRANSFER">{language === 'ar' ? 'تحويل بنكي (Transfer)' : 'Bank Transfer'}</option>
              <option value="INSTALLMENT">{language === 'ar' ? 'آجل / أقساط (Installment)' : 'Installment'}</option>
            </select>
          </div>

          <div className="flex justify-end gap-3 pt-3 border-t border-theme">
            <GlassButton variant="secondary" onClick={() => setIsNewInvoiceOpen(false)}>
              {language === 'ar' ? 'إلغاء' : 'Cancel'}
            </GlassButton>
            <GlassButton type="submit" variant="primary">
              {language === 'ar' ? 'إصدار الفاتورة وتحديث الأرصدة' : 'Issue Invoice'}
            </GlassButton>
          </div>
        </form>
      </Modal>
    </div>
  );
};
