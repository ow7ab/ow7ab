import React, { useEffect, useMemo, useState } from 'react';
import {
  FileText,
  Printer,
  FileSpreadsheet,
  Wallet,
  Package,
  ShoppingCart,
  Users,
  ShieldCheck,
  Filter
} from 'lucide-react';
import { GlassCard } from '../ui/GlassCard';
import { GlassBadge } from '../ui/GlassBadge';
import { GlassButton } from '../ui/GlassButton';
import {
  AuditLog,
  Cashbox,
  Customer,
  InvoicePayment,
  Product,
  SalesInvoice,
  StockMove,
  TreasuryMovement,
  Warehouse
} from '../../types/erp';
import { ErpDataService } from '../../services/ErpDataService';
import { ReportExporter } from '../../services/ReportExporter';
import { useLanguage } from '../../context/LanguageContext';

type ReportTab = 'المبيعات' | 'الخزنة' | 'المخزون' | 'المديونيات' | 'سجل الرقابة';

export const ReportsCenterView: React.FC = () => {
  const { language } = useLanguage();
  const [selectedReport, setSelectedReport] = useState<ReportTab>('المبيعات');
  const [selectedCustomerId, setSelectedCustomerId] = useState<number>(0); // 0 = كل العملاء
  const [selectedWarehouseId, setSelectedWarehouseId] = useState<number>(0); // 0 = كل المخازن

  const [invoices, setInvoices] = useState<SalesInvoice[]>([]);
  const [payments, setPayments] = useState<InvoicePayment[]>([]);
  const [cashboxes, setCashboxes] = useState<Cashbox[]>([]);
  const [movements, setMovements] = useState<TreasuryMovement[]>([]);
  const [warehouses, setWarehouses] = useState<Warehouse[]>([]);
  const [products, setProducts] = useState<Product[]>([]);
  const [stockMoves, setStockMoves] = useState<StockMove[]>([]);
  const [customers, setCustomers] = useState<Customer[]>([]);
  const [auditLogs, setAuditLogs] = useState<AuditLog[]>([]);

  useEffect(() => {
    const unsubs = [
      ErpDataService.subscribeToCollection<SalesInvoice>('invoices', setInvoices),
      ErpDataService.subscribeToCollection<InvoicePayment>('invoice_payments', setPayments),
      ErpDataService.subscribeToCollection<Cashbox>('cashboxes', setCashboxes),
      ErpDataService.subscribeToCollection<TreasuryMovement>('movements', setMovements),
      ErpDataService.subscribeToCollection<Warehouse>('warehouses', setWarehouses),
      ErpDataService.subscribeToCollection<Product>('products', setProducts),
      ErpDataService.subscribeToCollection<StockMove>('stock_moves', setStockMoves),
      ErpDataService.subscribeToCollection<Customer>('customers', setCustomers),
      ErpDataService.subscribeToCollection<AuditLog>('audit_logs', setAuditLogs)
    ];
    return () => unsubs.forEach((u) => u());
  }, []);

  const distinctCashboxes = useMemo(
    () => ErpDataService.computeDistinctCashboxesWithBalances(cashboxes, movements),
    [cashboxes, movements]
  );

  const cashboxBalances = useMemo(
    () => ErpDataService.computeCashboxBalancesByCurrency(cashboxes, movements),
    [cashboxes, movements]
  );

  const computedProducts = useMemo(
    () =>
      ErpDataService.computeProductStocksList(
        products,
        stockMoves,
        selectedWarehouseId > 0 ? selectedWarehouseId : null
      ),
    [products, stockMoves, selectedWarehouseId]
  );

  const customerDebts = useMemo(
    () => ErpDataService.computeCustomerDebtsList(customers, invoices, payments),
    [customers, invoices, payments]
  );

  const selectedCustomerObj = useMemo(
    () => customers.find((c) => Number(c.id) === Number(selectedCustomerId)) || null,
    [customers, selectedCustomerId]
  );

  const selectedCustomerStatement = useMemo(() => {
    if (!selectedCustomerObj) return null;
    return ErpDataService.computeCustomerStatement(selectedCustomerObj, invoices, payments);
  }, [selectedCustomerObj, invoices, payments]);

  const activeInvoices = invoices.filter((i) => i.status !== 'ملغاة');
  const totalSalesSdg = activeInvoices
    .filter((i) => i.currency === 'SDG')
    .reduce((s, i) => s + Number(i.total || 0), 0);
  const totalSalesEgp = activeInvoices
    .filter((i) => i.currency === 'EGP')
    .reduce((s, i) => s + Number(i.total || 0), 0);

  const totalDebtSdg = customerDebts
    .filter((c) => c.currency === 'SDG')
    .reduce((s, c) => s + c.remainingDebt, 0);

  const reportPayload = useMemo(() => {
    switch (selectedReport) {
      case 'المبيعات':
        return {
          title: 'التقرير التفصيلي للمبيعات والفواتير',
          subtitle: `إجمالي المبيعات (SDG): ${totalSalesSdg.toLocaleString()} | (EGP): ${totalSalesEgp.toLocaleString()}`,
          headers: ['رقم الفاتورة', 'العميل', 'التاريخ', 'الإجمالي', 'المسدد', 'المتبقي', 'العملة', 'الحالة'],
          rows: [...invoices]
            .sort((a, b) => (b.timestamp || 0) - (a.timestamp || 0))
            .map((inv) => [
              inv.invoiceNo,
              inv.customerName,
              new Date(inv.timestamp || Date.now()).toLocaleDateString('ar-EG'),
              Number(inv.total || 0).toLocaleString(),
              Number(inv.paidAmount || 0).toLocaleString(),
              Math.max(0, Number(inv.total || 0) - Number(inv.paidAmount || 0)).toLocaleString(),
              inv.currency,
              inv.status
            ])
        };
      case 'الخزنة':
        return {
          title: 'كشف حساب الخزنة والسيولة النقدية',
          subtitle: `الرصيد المجمع: ${cashboxBalances.SDG.toLocaleString()} SDG | ${cashboxBalances.EGP.toLocaleString()} EGP | $${cashboxBalances.USD.toLocaleString()}`,
          headers: ['التاريخ', 'الخزنة', 'النوع', 'الفئة', 'المبلغ', 'العملة', 'البيان', 'المسؤول'],
          rows: [...movements]
            .sort((a, b) => (b.timestamp || 0) - (a.timestamp || 0))
            .map((m) => [
              new Date(m.timestamp || Date.now()).toLocaleDateString('ar-EG'),
              m.cashbox,
              m.type === 'IN' ? 'توريد (+)' : 'صرف (-)',
              m.category,
              Number(m.amount || 0).toLocaleString(),
              m.currency,
              m.note,
              m.actor || '-'
            ])
        };
      case 'المخزون': {
        const whName =
          selectedWarehouseId > 0
            ? warehouses.find((w) => Number(w.id) === Number(selectedWarehouseId))?.name || 'مخزن محدد'
            : 'كل المخازن والفروع';
        const totalBoxes = computedProducts.reduce((s, p) => s + Math.max(0, Number(p.currentStock || 0)), 0);
        return {
          title: `تقرير الجرد الفعلي للمخزون — ${whName}`,
          subtitle: `إجمالي الأصناف: ${computedProducts.length} | إجمالي الكراتين المتاحة: ${totalBoxes.toLocaleString()} كرتونة`,
          headers: ['الكود', 'الصنف', 'اللون', 'الوزن', 'الرصيد الفعلي', 'الوحدة', 'حد الطلب', 'سعر الكرتونة SDG'],
          rows: computedProducts.map((p) => [
            p.sku || `PRD-${p.id}`,
            p.name,
            p.paintColor || '-',
            p.packageWeight || '-',
            Number(p.currentStock || 0).toLocaleString(),
            p.unit || 'كرتونة',
            p.minLevel || 10,
            Number(p.salePrice || p.salePriceSdg || 0).toLocaleString()
          ])
        };
      }
      case 'المديونيات': {
        if (selectedCustomerStatement) {
          const c = selectedCustomerStatement.customer;
          return {
            title: `كشف حساب العميل التفصيلي: ${c.name}`,
            subtitle: `إجمالي المسحوبات: ${selectedCustomerStatement.totalInvoiced.toLocaleString()} ${selectedCustomerStatement.currency} | المسدد: ${selectedCustomerStatement.totalPaid.toLocaleString()} | المتبقي: ${selectedCustomerStatement.remainingDebt.toLocaleString()} ${selectedCustomerStatement.currency}`,
            headers: [
              'التاريخ',
              'نوع الحركة',
              'المرجع / الفاتورة',
              'البيان والتفاصيل',
              'مدين (+)',
              'دائن (-)',
              'الرصيد التراكمي',
              'الحالة / الخزنة'
            ],
            rows: selectedCustomerStatement.entries.map((e) => [
              e.dateFormatted,
              e.typeLabel,
              e.referenceNo,
              e.description,
              e.debit > 0 ? `${e.debit.toLocaleString()} ${e.currency}` : '—',
              e.credit > 0 ? `${e.credit.toLocaleString()} ${e.currency}` : '—',
              `${e.runningBalance.toLocaleString()} ${e.currency}`,
              e.statusOrCashbox
            ])
          };
        }
        return {
          title: 'كشف حساب العملاء والمديونيات المستحقة (جميع العملاء)',
          subtitle: `إجمالي المديونيات القائمة: ${totalDebtSdg.toLocaleString()} SDG`,
          headers: ['اسم العميل', 'الهاتف', 'العنوان', 'إجمالي المسحوبات', 'إجمالي المسدد', 'الرصيد المتبقي', 'العملة'],
          rows: customerDebts.map((c) => [
            c.name,
            c.phone || '-',
            c.address || '-',
            c.totalInvoiced.toLocaleString(),
            c.totalPaid.toLocaleString(),
            c.remainingDebt.toLocaleString(),
            c.currency
          ])
        };
      }
      case 'سجل الرقابة':
      default:
        return {
          title: 'سجل الرقابة الإدارية والعمليات السحابية',
          subtitle: `إجمالي العمليات المسجلة: ${auditLogs.length}`,
          headers: ['التاريخ والوقت', 'المستخدم', 'العملية', 'التفاصيل'],
          rows: [...auditLogs]
            .sort((a, b) => (b.timestamp || 0) - (a.timestamp || 0))
            .map((l) => [
              new Date(l.timestamp || Date.now()).toLocaleString('ar-EG'),
              l.username,
              l.action,
              l.detail
            ])
        };
    }
  }, [
    selectedReport,
    invoices,
    movements,
    computedProducts,
    customerDebts,
    auditLogs,
    totalSalesSdg,
    totalSalesEgp,
    cashboxBalances,
    totalDebtSdg,
    selectedCustomerStatement,
    selectedWarehouseId,
    warehouses
  ]);

  const handleExportCsv = () => {
    if (selectedReport === 'المديونيات' && selectedCustomerStatement) {
      ReportExporter.exportCustomerStatementToCsv(selectedCustomerStatement);
      return;
    }
    ReportExporter.exportToCsv(
      `PMP_Report_${selectedReport}`,
      reportPayload.headers,
      reportPayload.rows
    );
  };

  const handlePrintPdf = () => {
    if (selectedReport === 'المديونيات' && selectedCustomerStatement) {
      ReportExporter.exportCustomerStatementToPrint(selectedCustomerStatement);
      return;
    }
    ReportExporter.exportToPrint(
      reportPayload.title,
      reportPayload.subtitle,
      reportPayload.headers,
      reportPayload.rows
    );
  };

  return (
    <div className="space-y-6">
      {/* Header */}
      <GlassCard className="p-5 flex flex-col lg:flex-row justify-between items-start lg:items-center gap-4">
        <div className="flex items-center gap-3">
          <div className="w-12 h-12 rounded-2xl bg-red-600/15 border border-red-500/30 flex items-center justify-center text-red-400">
            <FileText className="w-6 h-6" />
          </div>
          <div>
            <h2 className="text-lg font-black text-theme-primary">
              {language === 'ar'
                ? 'مركز التقارير الشامل والطباعة والتصدير (PDF / Excel)'
                : 'Enterprise Reports, Print & Export Center'}
            </h2>
            <p className="text-xs text-theme-secondary">
              توليد فوري للتقارير الرسمية وكشوفات حسابات العملاء المعتمدة من قاعدة البيانات السحابية.
            </p>
          </div>
        </div>

        <div className="flex gap-2">
          <GlassButton
            variant="secondary"
            size="sm"
            icon={<FileSpreadsheet className="w-4 h-4" />}
            onClick={handleExportCsv}
          >
            {selectedReport === 'المديونيات' && selectedCustomerStatement
              ? `تصدير كشف حساب (${selectedCustomerStatement.customer.name}) Excel`
              : 'تصدير Excel / CSV'}
          </GlassButton>
          <GlassButton
            variant="primary"
            size="sm"
            icon={<Printer className="w-4 h-4" />}
            onClick={handlePrintPdf}
          >
            {selectedReport === 'المديونيات' && selectedCustomerStatement
              ? `طباعة كشف حساب (${selectedCustomerStatement.customer.name}) PDF`
              : 'طباعة / حفظ PDF'}
          </GlassButton>
        </div>
      </GlassCard>

      {/* Executive Summary Cards */}
      <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-4">
        <GlassCard className="p-4 border-r-4 border-r-emerald-500">
          <p className="text-xs text-theme-muted">إجمالي المبيعات المعتمدة (SDG)</p>
          <p className="text-xl font-black text-emerald-400 font-mono mt-1">
            {totalSalesSdg.toLocaleString()} SDG
          </p>
        </GlassCard>
        <GlassCard className="p-4 border-r-4 border-r-amber-500">
          <p className="text-xs text-theme-muted">سيولة الخزن النقدية (SDG) — {distinctCashboxes.length} خزنة</p>
          <p className="text-xl font-black text-amber-400 font-mono mt-1">
            {cashboxBalances.SDG.toLocaleString()} SDG
          </p>
        </GlassCard>
        <GlassCard className="p-4 border-r-4 border-r-cyan-500">
          <p className="text-xs text-theme-muted">إجمالي أرصدة الكراتين بالمخازن</p>
          <p className="text-xl font-black text-cyan-400 font-mono mt-1">
            {computedProducts.reduce((s, p) => s + Math.max(0, Number(p.currentStock || 0)), 0).toLocaleString()} كرتونة
          </p>
        </GlassCard>
        <GlassCard className="p-4 border-r-4 border-r-red-500">
          <p className="text-xs text-theme-muted">إجمالي مديونيات العملاء (SDG)</p>
          <p className="text-xl font-black text-red-400 font-mono mt-1">
            {totalDebtSdg.toLocaleString()} SDG
          </p>
        </GlassCard>
      </div>

      {/* Report Selector Pills */}
      <div className="flex flex-wrap gap-2">
        {[
          { id: 'المبيعات' as const, label: 'تقرير المبيعات والفواتير', icon: <ShoppingCart className="w-4 h-4" /> },
          { id: 'الخزنة' as const, label: 'تقرير الخزنة والسيولة', icon: <Wallet className="w-4 h-4" /> },
          { id: 'المخزون' as const, label: 'تقرير جرد المخزون', icon: <Package className="w-4 h-4" /> },
          { id: 'المديونيات' as const, label: 'كشف حساب عميل / المديونيات', icon: <Users className="w-4 h-4" /> },
          { id: 'سجل الرقابة' as const, label: 'سجل الرقابة والعمليات', icon: <ShieldCheck className="w-4 h-4" /> }
        ].map((tab) => (
          <button
            key={tab.id}
            onClick={() => setSelectedReport(tab.id)}
            className={`px-4 py-2.5 rounded-xl text-xs font-bold flex items-center gap-2 transition-all ${
              selectedReport === tab.id
                ? 'bg-red-600 text-white shadow-lg shadow-red-600/25'
                : 'bg-theme-surface text-theme-secondary hover:text-theme-primary border border-theme'
            }`}
          >
            {tab.icon}
            <span>{tab.label}</span>
          </button>
        ))}
      </div>

      {/* Specific Customer Selector when 'المديونيات' is active */}
      {selectedReport === 'المديونيات' && (
        <GlassCard className="p-4 border-r-4 border-r-red-500 flex flex-col sm:flex-row justify-between items-start sm:items-center gap-3">
          <div className="flex items-center gap-2 text-xs font-bold text-theme-primary">
            <Filter className="w-4 h-4 text-red-500" />
            <span>تخصيص كشف الحساب لعميل محدد أو جميع العملاء:</span>
          </div>
          <div className="flex flex-wrap items-center gap-2 w-full sm:w-auto">
            <select
              value={selectedCustomerId}
              onChange={(e) => setSelectedCustomerId(Number(e.target.value))}
              className="px-3.5 py-2 rounded-xl bg-theme-base border border-theme text-xs font-bold text-theme-primary min-w-[240px]"
            >
              <option value={0}>كل العملاء — ملخص المديونيات العام ({customers.length})</option>
              {customers.map((c) => (
                <option key={c.id} value={c.id}>
                  كشف حساب العميل: {c.name} {c.phone ? `(${c.phone})` : ''}
                </option>
              ))}
            </select>
            {selectedCustomerStatement && (
              <>
                <GlassButton
                  size="sm"
                  variant="primary"
                  icon={<Printer className="w-3.5 h-3.5" />}
                  onClick={() => ReportExporter.exportCustomerStatementToPrint(selectedCustomerStatement)}
                >
                  طباعة كشف العميل PDF
                </GlassButton>
                <GlassButton
                  size="sm"
                  variant="secondary"
                  icon={<FileSpreadsheet className="w-3.5 h-3.5" />}
                  onClick={() => ReportExporter.exportCustomerStatementToCsv(selectedCustomerStatement)}
                >
                  تصدير كشف العميل Excel
                </GlassButton>
              </>
            )}
          </div>
        </GlassCard>
      )}

      {/* Specific Warehouse Selector when 'المخزون' is active */}
      {selectedReport === 'المخزون' && (
        <GlassCard className="p-4 border-r-4 border-r-cyan-500 flex flex-col sm:flex-row justify-between items-start sm:items-center gap-3">
          <div className="flex items-center gap-2 text-xs font-bold text-theme-primary">
            <Filter className="w-4 h-4 text-cyan-400" />
            <span>تصفية تقرير جرد المخزون حسب المستودع:</span>
          </div>
          <select
            value={selectedWarehouseId}
            onChange={(e) => setSelectedWarehouseId(Number(e.target.value))}
            className="px-3.5 py-2 rounded-xl bg-theme-base border border-theme text-xs font-bold text-theme-primary min-w-[240px]"
          >
            <option value={0}>كل المخازن والفروع ({warehouses.length})</option>
            {warehouses.map((w) => (
              <option key={w.id} value={w.id}>
                {w.name} ({w.branch})
              </option>
            ))}
          </select>
        </GlassCard>
      )}

      {/* Active Report Table */}
      <GlassCard className="p-5 space-y-4">
        <div className="flex flex-col sm:flex-row justify-between items-start sm:items-center gap-2 border-b border-theme pb-3">
          <div>
            <h3 className="text-base font-black text-theme-primary">{reportPayload.title}</h3>
            <p className="text-xs text-theme-secondary mt-0.5">{reportPayload.subtitle}</p>
          </div>
          <GlassBadge variant="cyan">{reportPayload.rows.length} سجل</GlassBadge>
        </div>

        <div className="overflow-x-auto">
          <table className="w-full text-xs text-right">
            <thead>
              <tr className="border-b border-theme text-theme-muted">
                {reportPayload.headers.map((h, i) => (
                  <th key={i} className="py-3 px-3">
                    {h}
                  </th>
                ))}
              </tr>
            </thead>
            <tbody className="divide-y divide-white/5">
              {reportPayload.rows.map((row, rIdx) => (
                <tr key={rIdx} className="hover:bg-white/[0.02]">
                  {row.map((cell, cIdx) => (
                    <td key={cIdx} className="py-2.5 px-3 text-theme-primary">
                      {cell}
                    </td>
                  ))}
                </tr>
              ))}
              {reportPayload.rows.length === 0 && (
                <tr>
                  <td colSpan={reportPayload.headers.length} className="py-8 text-center text-theme-muted">
                    لا توجد بيانات مسجلة في هذا التقرير حالياً
                  </td>
                </tr>
              )}
            </tbody>
          </table>
        </div>
      </GlassCard>
    </div>
  );
};
