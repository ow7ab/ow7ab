import React, { useState, useEffect, useMemo } from 'react';
import {
  Printer,
  FileSpreadsheet,
  TrendingUp,
  Receipt,
  CreditCard,
  Layers,
  PackageCheck,
  ArrowLeftRight,
  Coins,
  Calendar,
  ShieldAlert
} from 'lucide-react';
import { GlassCard } from '../ui/GlassCard';
import { GlassBadge } from '../ui/GlassBadge';
import { GlassButton } from '../ui/GlassButton';
import { ReportExporter } from '../../services/ReportExporter';
import { ErpDataService } from '../../services/ErpDataService';
import { GRANULAR_ACTIONS, hasReportTabPermission } from '../../services/SecurityService';
import { useLanguage } from '../../context/LanguageContext';
import { useAuth } from '../../context/AuthContext';
import {
  SalesInvoice,
  InvoicePayment,
  Customer,
  TreasuryMovement,
  Product,
  StockMove,
  FactoryItem,
  FactoryMove
} from '../../types/erp';

type ReportTabId =
  | 'مبيعات الشهر الحالي'
  | 'المبيعات'
  | 'المديونيات'
  | 'المواد الخام في المخزن'
  | 'المنتج التام'
  | 'حركة المخزون'
  | 'حركات الخزن';

type PeriodFilter = 'ALL' | 'TODAY' | 'DAYS_7' | 'DAYS_30' | 'CUSTOM';

const REPORT_TABS: { id: ReportTabId; labelEn: string; icon: React.ReactNode }[] = [
  { id: 'مبيعات الشهر الحالي', labelEn: 'Current Month Sales', icon: <TrendingUp className="w-4 h-4" /> },
  { id: 'المبيعات', labelEn: 'All Sales', icon: <Receipt className="w-4 h-4" /> },
  { id: 'المديونيات', labelEn: 'Customer Debts', icon: <CreditCard className="w-4 h-4" /> },
  { id: 'المواد الخام في المخزن', labelEn: 'Raw Materials', icon: <Layers className="w-4 h-4" /> },
  { id: 'المنتج التام', labelEn: 'Finished Goods', icon: <PackageCheck className="w-4 h-4" /> },
  { id: 'حركة المخزون', labelEn: 'Stock Moves', icon: <ArrowLeftRight className="w-4 h-4" /> },
  { id: 'حركات الخزن', labelEn: 'Treasury Ledger', icon: <Coins className="w-4 h-4" /> }
];

function parseDateMs(dateStr?: string, fallbackTs?: number): number {
  if (fallbackTs && Number.isFinite(fallbackTs) && fallbackTs > 1_000_000_000_000) return fallbackTs;
  if (!dateStr) return 0;
  const normalized = dateStr.trim().slice(0, 10);
  const t = Date.parse(normalized);
  return Number.isFinite(t) ? t : 0;
}

export const ReportsCenterView: React.FC = () => {
  const { language } = useLanguage();
  const { userProfile, canPerformAction } = useAuth();

  const allowedTabs = useMemo(
    () => REPORT_TABS.filter((tab) => hasReportTabPermission(userProfile, tab.id)),
    [userProfile]
  );

  const [activeTab, setActiveTab] = useState<ReportTabId>(allowedTabs[0]?.id || 'مبيعات الشهر الحالي');
  const [period, setPeriod] = useState<PeriodFilter>('ALL');
  const [dateFrom, setDateFrom] = useState(() => {
    const d = new Date();
    d.setDate(1);
    return d.toISOString().slice(0, 10);
  });
  const [dateTo, setDateTo] = useState(() => new Date().toISOString().slice(0, 10));

  // Live Firestore states
  const [invoices, setInvoices] = useState<SalesInvoice[]>([]);
  const [payments, setPayments] = useState<InvoicePayment[]>([]);
  const [customers, setCustomers] = useState<Customer[]>([]);
  const [movements, setMovements] = useState<TreasuryMovement[]>([]);
  const [products, setProducts] = useState<Product[]>([]);
  const [stockMoves, setStockMoves] = useState<StockMove[]>([]);
  const [factoryItems, setFactoryItems] = useState<FactoryItem[]>([]);
  const [factoryMoves, setFactoryMoves] = useState<FactoryMove[]>([]);

  useEffect(() => {
    if (allowedTabs.length > 0 && !allowedTabs.some((t) => t.id === activeTab)) {
      setActiveTab(allowedTabs[0].id);
    }
  }, [allowedTabs, activeTab]);

  useEffect(() => {
    const unsubs = [
      ErpDataService.subscribeToCollection<SalesInvoice>('invoices', setInvoices),
      ErpDataService.subscribeToCollection<InvoicePayment>('invoice_payments', setPayments),
      ErpDataService.subscribeToCollection<Customer>('customers', setCustomers),
      ErpDataService.subscribeToCollection<TreasuryMovement>('movements', setMovements),
      ErpDataService.subscribeToCollection<Product>('products', setProducts),
      ErpDataService.subscribeToCollection<StockMove>('stock_moves', setStockMoves),
      ErpDataService.subscribeToCollection<FactoryItem>('factory_items', setFactoryItems),
      ErpDataService.subscribeToCollection<FactoryMove>('factory_moves', setFactoryMoves)
    ];
    return () => unsubs.forEach((u) => u());
  }, []);

  const matchesPeriod = (dateStr?: string, ts?: number): boolean => {
    if (period === 'ALL') return true;
    const itemMs = parseDateMs(dateStr, ts);
    if (!itemMs) return true;
    const now = Date.now();
    const todayStr = new Date().toISOString().slice(0, 10);

    if (period === 'TODAY') {
      const d = (dateStr || new Date(itemMs).toISOString()).slice(0, 10);
      return d === todayStr;
    }
    if (period === 'DAYS_7') {
      return itemMs >= now - 7 * 86_400_000;
    }
    if (period === 'DAYS_30') {
      return itemMs >= now - 30 * 86_400_000;
    }
    if (period === 'CUSTOM') {
      const fromMs = dateFrom ? Date.parse(dateFrom) : 0;
      const toMs = dateTo ? Date.parse(dateTo) + 86_399_999 : Number.MAX_SAFE_INTEGER;
      return itemMs >= fromMs && itemMs <= toMs;
    }
    return true;
  };

  // Android calculations
  const productStocks = useMemo(() => ErpDataService.computeProductStocks(products, stockMoves), [products, stockMoves]);
  const factoryBalances = useMemo(
    () => ErpDataService.computeFactoryItemBalances(factoryItems, factoryMoves),
    [factoryItems, factoryMoves]
  );
  const customerDebts = useMemo(
    () => ErpDataService.computeCustomerDebts(customers, invoices, payments),
    [customers, invoices, payments]
  );

  const currentMonthPrefix = new Date().toISOString().slice(0, 7); // YYYY-MM

  const activeInvoices = useMemo(
    () =>
      invoices
        .filter((i) => i.status !== 'cancelled')
        .filter((i) => matchesPeriod(i.invoiceDate || i.date, i.createdAt))
        .sort((a, b) => (b.createdAt || 0) - (a.createdAt || 0)),
    [invoices, period, dateFrom, dateTo]
  );

  const currentMonthInvoices = useMemo(
    () =>
      invoices
        .filter((i) => i.status !== 'cancelled')
        .filter((i) => (i.invoiceDate || i.date || '').startsWith(currentMonthPrefix))
        .sort((a, b) => (b.createdAt || 0) - (a.createdAt || 0)),
    [invoices, currentMonthPrefix]
  );

  const filteredMovements = useMemo(
    () =>
      movements
        .filter((m) => matchesPeriod(m.txDate || m.date, m.createdAt))
        .sort((a, b) => (b.createdAt || 0) - (a.createdAt || 0)),
    [movements, period, dateFrom, dateTo]
  );

  const filteredStockMoves = useMemo(
    () =>
      stockMoves
        .filter((s) => matchesPeriod(undefined, s.createdAt))
        .sort((a, b) => (b.createdAt || 0) - (a.createdAt || 0)),
    [stockMoves, period, dateFrom, dateTo]
  );

  const debtorRows = useMemo(() => {
    return customers
      .map((c) => {
        const debt = customerDebts.get(Number(c.id)) || {
          totalInvoiced: 0,
          totalPaid: 0,
          remaining: 0,
          primaryCurrency: 'SDG' as const
        };
        return { customer: c, ...debt };
      })
      .filter((r) => r.totalInvoiced > 0 || r.remaining > 0)
      .sort((a, b) => b.remaining - a.remaining);
  }, [customers, customerDebts]);

  const canExportOrPrint =
    canPerformAction(GRANULAR_ACTIONS.EXPORT) ||
    canPerformAction(GRANULAR_ACTIONS.PRINT) ||
    allowedTabs.length > 0;

  const buildReportTable = (): { title: string; headers: string[]; rows: (string | number)[][] } => {
    if (activeTab === 'مبيعات الشهر الحالي' || activeTab === 'المبيعات') {
      const list = activeTab === 'مبيعات الشهر الحالي' ? currentMonthInvoices : activeInvoices;
      return {
        title: activeTab === 'مبيعات الشهر الحالي' ? `تقرير مبيعات الشهر الحالي (${currentMonthPrefix})` : 'تقرير المبيعات الشامل',
        headers: ['رقم الفاتورة', 'التاريخ', 'العميل', 'الإجمالي', 'المدفوع', 'المتبقي', 'العملة', 'الحالة'],
        rows: list.map((inv) => {
          const fin = ErpDataService.computeInvoiceFinancials(inv, payments);
          return [
            inv.invoiceNo || String(inv.id),
            inv.invoiceDate || inv.date || '',
            inv.customerName,
            Number(inv.total ?? inv.finalAmount ?? 0),
            fin.paidAmount,
            fin.remainingAmount,
            inv.currency,
            fin.statusLabel
          ];
        })
      };
    }

    if (activeTab === 'المديونيات') {
      return {
        title: 'تقرير مديونيات العملاء وأرصدة السداد',
        headers: ['العميل', 'الهاتف', 'إجمالي الفواتير', 'إجمالي المسدد', 'المتبقي (المديونية)', 'العملة'],
        rows: debtorRows.map((d) => [
          d.customer.name,
          d.customer.phone,
          d.totalInvoiced,
          d.totalPaid,
          d.remaining,
          d.primaryCurrency
        ])
      };
    }

    if (activeTab === 'المواد الخام في المخزن') {
      const rawItems = factoryItems.filter((fi) => fi.category === 'raw' || !fi.category || fi.category.includes('خام'));
      return {
        title: 'تقرير أرصدة المواد الخام بالمصنع',
        headers: ['المادة الخام', 'الوحدة', 'الرصيد المحسوب', 'تكلفة الوحدة EGP'],
        rows: rawItems.map((fi) => [
          fi.name,
          fi.unit,
          factoryBalances.get(Number(fi.id)) ?? 0,
          Number(fi.unitCostEgp || 0)
        ])
      };
    }

    if (activeTab === 'المنتج التام') {
      return {
        title: 'تقرير جرد وتقييم المنتج التام بالمخازن',
        headers: ['الصنف', 'اللون', 'الحجم', 'الرصيد الفعلي', 'سعر البيع SDG', 'سعر البيع EGP'],
        rows: products.map((p) => [
          p.name,
          p.paintColor,
          p.packageWeight,
          productStocks.get(Number(p.id)) ?? 0,
          Number(p.salePrice ?? p.salePriceSdg ?? 0),
          Number(p.salePriceEgp ?? 0)
        ])
      };
    }

    if (activeTab === 'حركة المخزون') {
      return {
        title: 'تقرير حركة المخزون للمنتجات التامة',
        headers: ['رقم الحركة', 'رقم الصنف', 'اسم الصنف', 'نوع الحركة', 'الكمية', 'البيان', 'الموظف', 'التاريخ'],
        rows: filteredStockMoves.map((sm) => {
          const prod = products.find((p) => Number(p.id) === Number(sm.productId));
          return [
            sm.id,
            sm.productId,
            prod?.name || `صنف #${sm.productId}`,
            sm.kind,
            sm.quantity,
            sm.note || '-',
            sm.createdBy,
            sm.createdAt ? new Date(sm.createdAt).toLocaleDateString('ar-EG') : '-'
          ];
        })
      };
    }

    return {
      title: 'تقرير حركات الخزن والسيولة النقدية',
      headers: ['التاريخ', 'الخزنة', 'النوع', 'المبلغ', 'العملة', 'المرجع', 'البيان', 'بواسطة'],
      rows: filteredMovements.map((m) => [
        m.txDate || m.date || '',
        m.cashbox || m.cashboxName || '',
        m.direction || (m.type === 'INCOME' ? 'وارد' : 'منصرف'),
        Number(m.amount) || 0,
        m.currency,
        m.reference || '-',
        m.description,
        m.createdBy || m.employeeName || '-'
      ])
    };
  };

  if (allowedTabs.length === 0) {
    return (
      <GlassCard className="p-8 text-center space-y-3">
        <ShieldAlert className="w-10 h-10 text-red-500 mx-auto" />
        <h3 className="text-base font-bold text-theme-primary">لا تملك صلاحية عرض التقارير</h3>
        <p className="text-xs text-theme-secondary">
          يرجى مراجعة مدير النظام لمنحك أحد صلاحيات التقارير (مثل: تقارير المبيعات، التقارير، المحاسبة).
        </p>
      </GlassCard>
    );
  }

  const reportData = buildReportTable();

  return (
    <div className="space-y-5">
      {/* Header */}
      <div className="flex flex-col sm:flex-row justify-between items-start sm:items-center gap-4 pb-4 border-b border-theme">
        <div>
          <div className="flex items-center gap-2">
            <h2 className="text-xl font-black text-theme-primary">
              {language === 'ar' ? 'مركز التقارير المعتمدة والطباعة' : 'Enterprise Reports Center'}
            </h2>
            <GlassBadge variant="cyan">{allowedTabs.length} تقارير مصرحة</GlassBadge>
          </div>
          <p className="text-xs text-theme-secondary mt-1">
            {language === 'ar'
              ? 'متطابق مع 7 تقارير الأندرويد • أرقام حية من Firestore وتصدير مباشر Excel/CSV و PDF.'
              : 'Matches the 7 Android Reports tabs with live Firestore calculations & CSV/Print export.'}
          </p>
        </div>

        {canExportOrPrint && (
          <div className="flex items-center gap-2 flex-wrap">
            <GlassButton
              variant="secondary"
              icon={<FileSpreadsheet className="w-4 h-4" />}
              onClick={() => ReportExporter.exportToCsv(`pmp_${activeTab}`, reportData.headers, reportData.rows)}
              className="text-xs"
            >
              {language === 'ar' ? 'تصدير Excel/CSV' : 'Export CSV'}
            </GlassButton>
            <GlassButton
              variant="primary"
              icon={<Printer className="w-4 h-4" />}
              onClick={() => ReportExporter.exportToPrint(reportData.title, reportData.headers, reportData.rows)}
              className="text-xs"
            >
              {language === 'ar' ? 'طباعة / PDF' : 'Print / PDF'}
            </GlassButton>
          </div>
        )}
      </div>

      {/* 7 Android Report Tabs (RBAC Filtered) */}
      <div className="flex flex-wrap gap-2">
        {allowedTabs.map((tab) => {
          const isActive = activeTab === tab.id;
          return (
            <button
              key={tab.id}
              type="button"
              onClick={() => setActiveTab(tab.id)}
              className={`px-3.5 py-2 rounded-xl border text-xs font-bold transition-all flex items-center gap-1.5 min-h-[40px] ${
                isActive
                  ? 'bg-pmp-primary text-white border-pmp-primary shadow-md'
                  : 'bg-theme-surface border-theme text-theme-secondary hover:text-theme-primary'
              }`}
            >
              {tab.icon}
              <span>{language === 'ar' ? tab.id : tab.labelEn}</span>
            </button>
          );
        })}
      </div>

      {/* Period Filter Bar */}
      <GlassCard className="p-4 space-y-3">
        <div className="flex flex-wrap items-center gap-2 text-xs">
          <Calendar className="w-4 h-4 text-pmp-primary" />
          <span className="font-bold text-theme-primary">الفترة الزمنية:</span>
          {(
            [
              { id: 'ALL', label: 'الكل' },
              { id: 'TODAY', label: 'اليوم' },
              { id: 'DAYS_7', label: 'آخر 7 أيام' },
              { id: 'DAYS_30', label: 'آخر 30 يوم' },
              { id: 'CUSTOM', label: 'تاريخ مخصص' }
            ] as { id: PeriodFilter; label: string }[]
          ).map((p) => (
            <button
              key={p.id}
              type="button"
              onClick={() => setPeriod(p.id)}
              className={`px-3 py-1.5 rounded-lg border font-bold transition-all ${
                period === p.id
                  ? 'bg-pmp-primary/15 border-pmp-primary text-pmp-primary'
                  : 'bg-theme-base border-theme text-theme-secondary'
              }`}
            >
              {p.label}
            </button>
          ))}
        </div>

        {period === 'CUSTOM' && (
          <div className="grid grid-cols-1 sm:grid-cols-2 gap-3 pt-2 border-t border-theme text-xs">
            <div>
              <label className="block text-theme-muted mb-1 font-bold">من تاريخ:</label>
              <input
                type="date"
                value={dateFrom}
                onChange={(e) => setDateFrom(e.target.value)}
                className="w-full px-3 py-2 rounded-lg bg-theme-base border border-theme text-theme-primary font-mono"
              />
            </div>
            <div>
              <label className="block text-theme-muted mb-1 font-bold">إلى تاريخ:</label>
              <input
                type="date"
                value={dateTo}
                onChange={(e) => setDateTo(e.target.value)}
                className="w-full px-3 py-2 rounded-lg bg-theme-base border border-theme text-theme-primary font-mono"
              />
            </div>
          </div>
        )}
      </GlassCard>

      {/* Report Data Table */}
      <GlassCard className="p-0 overflow-hidden">
        <div className="p-4 border-b border-theme flex flex-wrap justify-between items-center gap-2">
          <h4 className="font-bold text-theme-primary text-sm">{reportData.title}</h4>
          <span className="text-xs text-theme-muted font-mono">{reportData.rows.length} سجل</span>
        </div>

        {reportData.rows.length === 0 ? (
          <div className="p-10 text-center text-xs text-theme-muted">
            لا توجد سجلات مطابقة لهذا التقرير ضمن الفترة المختارة.
          </div>
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-right border-collapse text-xs">
              <thead>
                <tr className="bg-theme-surface/70 border-b border-theme text-theme-muted font-bold">
                  {reportData.headers.map((h) => (
                    <th key={h} className="py-3 px-4 whitespace-nowrap">
                      {h}
                    </th>
                  ))}
                </tr>
              </thead>
              <tbody className="divide-y divide-theme">
                {reportData.rows.map((row, rIdx) => (
                  <tr key={rIdx} className="hover:bg-theme-surface/40">
                    {row.map((cell, cIdx) => (
                      <td key={cIdx} className="py-3 px-4 whitespace-nowrap text-theme-primary">
                        {typeof cell === 'number' ? cell.toLocaleString() : cell}
                      </td>
                    ))}
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </GlassCard>
    </div>
  );
};
