import React, { useEffect, useMemo, useState } from 'react';
import {
  DollarSign,
  Package,
  Factory,
  AlertTriangle,
  FileText,
  Building,
  Users,
  RefreshCw,
  ArrowUpRight
} from 'lucide-react';
import { GlassCard } from '../ui/GlassCard';
import { GlassBadge } from '../ui/GlassBadge';
import { GlassButton } from '../ui/GlassButton';
import { StatCard } from '../ui/StatCard';
import { useLanguage } from '../../context/LanguageContext';
import { useAuth } from '../../context/AuthContext';
import { ErpDataService } from '../../services/ErpDataService';
import { AccountService } from '../../services/AccountService';
import {
  Cashbox,
  TreasuryMovement,
  Product,
  StockMove,
  FactoryItem,
  FactoryMove,
  ProductionBatch,
  SalesInvoice,
  InvoicePayment,
  Customer,
  Warehouse,
  UserAccount
} from '../../types/erp';

interface AdminDashboardOverviewProps {
  onNavigateToModule: (moduleId: string) => void;
}

export const AdminDashboardOverview: React.FC<AdminDashboardOverviewProps> = ({ onNavigateToModule }) => {
  const { language } = useLanguage();
  const { userProfile, canAccessModule } = useAuth();

  const [cashboxes, setCashboxes] = useState<Cashbox[]>([]);
  const [movements, setMovements] = useState<TreasuryMovement[]>([]);
  const [products, setProducts] = useState<Product[]>([]);
  const [stockMoves, setStockMoves] = useState<StockMove[]>([]);
  const [factoryItems, setFactoryItems] = useState<FactoryItem[]>([]);
  const [factoryMoves, setFactoryMoves] = useState<FactoryMove[]>([]);
  const [batches, setBatches] = useState<ProductionBatch[]>([]);
  const [invoices, setInvoices] = useState<SalesInvoice[]>([]);
  const [payments, setPayments] = useState<InvoicePayment[]>([]);
  const [customers, setCustomers] = useState<Customer[]>([]);
  const [warehouses, setWarehouses] = useState<Warehouse[]>([]);
  const [employees, setEmployees] = useState<UserAccount[]>([]);
  const [isSyncing, setIsSyncing] = useState(true);

  useEffect(() => {
    const unsubs = [
      ErpDataService.subscribeToCollection<Cashbox>('cashboxes', (items) => {
        setCashboxes(items);
        setIsSyncing(false);
      }),
      ErpDataService.subscribeToCollection<TreasuryMovement>('movements', setMovements),
      ErpDataService.subscribeToCollection<Product>('products', setProducts),
      ErpDataService.subscribeToCollection<StockMove>('stock_moves', setStockMoves),
      ErpDataService.subscribeToCollection<FactoryItem>('factory_items', setFactoryItems),
      ErpDataService.subscribeToCollection<FactoryMove>('factory_moves', setFactoryMoves),
      ErpDataService.subscribeToCollection<ProductionBatch>('paint_batches', setBatches),
      ErpDataService.subscribeToCollection<SalesInvoice>('invoices', setInvoices),
      ErpDataService.subscribeToCollection<InvoicePayment>('invoice_payments', setPayments),
      ErpDataService.subscribeToCollection<Customer>('customers', setCustomers),
      ErpDataService.subscribeToCollection<Warehouse>('warehouses', setWarehouses),
      AccountService.subscribeEmployees(setEmployees)
    ];

    return () => unsubs.forEach((u) => u());
  }, []);

  const computedCashboxes = useMemo(
    () => ErpDataService.computeCashboxBalances(cashboxes, movements),
    [cashboxes, movements]
  );

  const productStocks = useMemo(
    () => ErpDataService.computeProductStocks(products, stockMoves),
    [products, stockMoves]
  );

  const factoryBalances = useMemo(
    () => ErpDataService.computeFactoryItemBalances(factoryItems, factoryMoves),
    [factoryItems, factoryMoves]
  );

  const totalSdgTreasury = useMemo(
    () => computedCashboxes.filter((c) => c.currency === 'SDG').reduce((s, c) => s + c.balance, 0),
    [computedCashboxes]
  );

  const totalEgpTreasury = useMemo(
    () => computedCashboxes.filter((c) => c.currency === 'EGP').reduce((s, c) => s + c.balance, 0),
    [computedCashboxes]
  );

  const totalUsdTreasury = useMemo(
    () => computedCashboxes.filter((c) => c.currency === 'USD').reduce((s, c) => s + c.balance, 0),
    [computedCashboxes]
  );

  const totalFinishedStock = useMemo(() => {
    let total = 0;
    productStocks.forEach((qty) => {
      total += qty;
    });
    return total;
  }, [productStocks]);

  const sortedBatches = useMemo(
    () => [...batches].sort((a, b) => (b.createdAt || 0) - (a.createdAt || 0)).slice(0, 6),
    [batches]
  );

  const sortedInvoices = useMemo(
    () => [...invoices].sort((a, b) => (b.createdAt || 0) - (a.createdAt || 0)).slice(0, 8),
    [invoices]
  );

  const lowStockRawItems = useMemo(() => {
    return factoryItems
      .map((item) => ({
        ...item,
        liveBalance: factoryBalances.get(Number(item.id)) ?? Number(item.actualBalance || 0)
      }))
      .filter((item) => item.liveBalance <= (item.reorderLevel ?? 10))
      .slice(0, 5);
  }, [factoryItems, factoryBalances]);

  const lowStockProducts = useMemo(() => {
    return products
      .map((p) => ({
        ...p,
        liveStock: productStocks.get(Number(p.id)) ?? Number(p.currentStock || 0)
      }))
      .filter((p) => p.active && p.liveStock <= (p.minimumStock ?? 5))
      .slice(0, 5);
  }, [products, productStocks]);

  const activeEmployeesCount = useMemo(
    () => employees.filter((e) => e.active && e.syncStatus !== 'PENDING_MANAGER_APPROVAL').length,
    [employees]
  );

  return (
    <div className="space-y-6">
      {/* Header Banner */}
      <GlassCard className="p-5 sm:p-6 bg-gradient-to-l from-theme-surface to-pmp-primary/10">
        <div className="flex flex-col sm:flex-row justify-between items-start sm:items-center gap-4">
          <div className="space-y-1">
            <div className="flex flex-wrap items-center gap-2">
              <h2 className="text-xl sm:text-2xl font-black text-theme-primary">
                {language === 'ar'
                  ? 'لوحة القيادة التنفيذية الموحدة'
                  : 'Unified Executive ERP Dashboard'}
              </h2>
              <GlassBadge variant="red">{ErpDataService.activeCluster}</GlassBadge>
            </div>
            <p className="text-xs text-theme-secondary">
              {language === 'ar'
                ? `مرحباً ${userProfile?.fullName || ''} — جميع الأرقام التالية محسوبة لحظياً من قاعدة بيانات Cloud Firestore المشتركة مع الأندرويد.`
                : `Welcome ${userProfile?.fullName || ''} — All metrics are computed live from the unified Cloud Firestore database.`}
            </p>
          </div>

          <div className="flex items-center gap-2 px-3 py-1.5 rounded-xl bg-emerald-500/10 border border-emerald-500/30 text-emerald-400 text-xs font-bold shrink-0">
            <RefreshCw className={`w-3.5 h-3.5 ${isSyncing ? 'animate-spin' : ''}`} />
            <span>{language === 'ar' ? 'مزامنة سحابية حية' : 'Live Cloud Sync'}</span>
          </div>
        </div>
      </GlassCard>

      {/* Live Financial & Operational KPIs */}
      <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-4">
        <StatCard
          title={language === 'ar' ? 'رصيد الخزن (جنيه سوداني)' : 'Sudan Treasury (SDG)'}
          value={`${totalSdgTreasury.toLocaleString()} SDG`}
          change={`${computedCashboxes.filter((c) => c.currency === 'SDG').length} خزنة سودانية`}
          isPositive={totalSdgTreasury >= 0}
          icon={<DollarSign className="w-5 h-5 text-pmp-primary" />}
        />

        <StatCard
          title={language === 'ar' ? 'رصيد الخزن (جنيه مصري)' : 'Egypt Treasury (EGP)'}
          value={`${totalEgpTreasury.toLocaleString()} EGP`}
          change={`${computedCashboxes.filter((c) => c.currency === 'EGP').length} خزنة مصرية`}
          isPositive={totalEgpTreasury >= 0}
          icon={<DollarSign className="w-5 h-5 text-pmp-gold" />}
        />

        <StatCard
          title={language === 'ar' ? 'خزنة الدولار الأمريكي' : 'USD Treasury'}
          value={`$${totalUsdTreasury.toLocaleString()}`}
          change={`${movements.length} حركة مالية مسجلة`}
          isPositive={totalUsdTreasury >= 0}
          icon={<DollarSign className="w-5 h-5 text-pmp-cyan" />}
        />

        <StatCard
          title={language === 'ar' ? 'مخزون المنتج التام' : 'Finished Goods Stock'}
          value={`${totalFinishedStock.toLocaleString()} وحدة`}
          change={`${products.length} صنف • ${warehouses.length} مخزن`}
          isPositive={totalFinishedStock > 0}
          icon={<Package className="w-5 h-5 text-pmp-success" />}
        />
      </div>

      {/* Secondary Quick Metrics Row */}
      <div className="grid grid-cols-2 sm:grid-cols-4 gap-3">
        <button
          type="button"
          onClick={() => onNavigateToModule('SALES')}
          className="p-3.5 rounded-xl bg-theme-surface border border-theme hover:border-pmp-primary text-right transition-all"
        >
          <span className="text-[11px] text-theme-muted block">فواتير المبيعات</span>
          <span className="text-lg font-black text-theme-primary">{invoices.length}</span>
          <span className="text-[10px] text-pmp-cyan block">{customers.length} عميل مسجل</span>
        </button>

        <button
          type="button"
          onClick={() => onNavigateToModule('FACTORY')}
          className="p-3.5 rounded-xl bg-theme-surface border border-theme hover:border-pmp-primary text-right transition-all"
        >
          <span className="text-[11px] text-theme-muted block">تشغيلات الإنتاج</span>
          <span className="text-lg font-black text-theme-primary">{batches.length}</span>
          <span className="text-[10px] text-pmp-gold block">{factoryItems.length} صنف خامات</span>
        </button>

        <button
          type="button"
          onClick={() => onNavigateToModule('TREASURY')}
          className="p-3.5 rounded-xl bg-theme-surface border border-theme hover:border-pmp-primary text-right transition-all"
        >
          <span className="text-[11px] text-theme-muted block">حركات الخزن</span>
          <span className="text-lg font-black text-theme-primary">{movements.length}</span>
          <span className="text-[10px] text-emerald-400 block">{computedCashboxes.length} خزنة نشطة</span>
        </button>

        <button
          type="button"
          onClick={() => onNavigateToModule('USERS')}
          className="p-3.5 rounded-xl bg-theme-surface border border-theme hover:border-pmp-primary text-right transition-all"
        >
          <span className="text-[11px] text-theme-muted block">حسابات الموظفين</span>
          <span className="text-lg font-black text-theme-primary">{activeEmployeesCount}</span>
          <span className="text-[10px] text-theme-muted block">من إجمالي {employees.length} حساب</span>
        </button>
      </div>

      {/* Main Content Split */}
      <div className="grid grid-cols-1 lg:grid-cols-12 gap-6 items-start">
        <div className="lg:col-span-8 space-y-6">
          {/* Active Production Batches */}
          <GlassCard className="p-5 sm:p-6">
            <div className="flex flex-wrap justify-between items-center gap-2 mb-4">
              <h3 className="text-base font-bold text-theme-primary flex items-center gap-2">
                <Factory className="w-4 h-4 text-pmp-accent" />
                {language === 'ar' ? 'أحدث تشغيلات الإنتاج بالمصنع' : 'Latest Factory Production Batches'}
              </h3>
              {canAccessModule('المصنع') && (
                <GlassButton
                  variant="secondary"
                  onClick={() => onNavigateToModule('FACTORY')}
                  className="text-xs"
                  icon={<ArrowUpRight className="w-3.5 h-3.5" />}
                >
                  {language === 'ar' ? 'فتح المصنع' : 'Open Factory'}
                </GlassButton>
              )}
            </div>

            {sortedBatches.length === 0 ? (
              <div className="p-6 text-center text-xs text-theme-muted border border-dashed border-theme rounded-xl">
                {language === 'ar'
                  ? 'لا توجد تشغيلات إنتاج مسجلة حتى الآن في قاعدة البيانات.'
                  : 'No production batches recorded yet.'}
              </div>
            ) : (
              <div className="space-y-3">
                {sortedBatches.map((b) => (
                  <div
                    key={b.id}
                    className="p-3.5 rounded-xl bg-theme-surface border border-theme flex flex-col sm:flex-row justify-between sm:items-center gap-2 text-xs"
                  >
                    <div className="space-y-1">
                      <div className="flex flex-wrap items-center gap-2">
                        <span className="font-mono font-bold text-theme-primary text-sm">{b.batchNumber}</span>
                        <GlassBadge
                          variant={
                            b.qualityStatus === 'معتمد ومطابق'
                              ? 'green'
                              : b.qualityStatus === 'مرفوض/معالجة'
                              ? 'red'
                              : 'gold'
                          }
                        >
                          {b.qualityStatus || 'تحت الفحص المخبري'}
                        </GlassBadge>
                      </div>
                      <div className="text-theme-secondary">
                        {b.productName} ({b.baseKind || b.paintType}) •{' '}
                        <b>{b.actualWeightKg || b.targetWeightKg} كجم</b> • {b.cartonsCount} كرتونة
                      </div>
                    </div>

                    <div className="flex items-center gap-2 text-theme-muted text-[11px] shrink-0">
                      <span>{b.supervisorOrEngineer || '-'}</span>
                      <span>•</span>
                      <span className="font-mono">{b.productionDate}</span>
                    </div>
                  </div>
                ))}
              </div>
            )}
          </GlassCard>

          {/* Recent Invoices */}
          <GlassCard className="p-5 sm:p-6">
            <div className="flex flex-wrap justify-between items-center gap-2 mb-4">
              <h3 className="text-base font-bold text-theme-primary flex items-center gap-2">
                <FileText className="w-4 h-4 text-pmp-primary" />
                {language === 'ar' ? 'أحدث فواتير المبيعات الصادرة' : 'Recent Sales Invoices'}
              </h3>
              {canAccessModule('المبيعات') && (
                <GlassButton
                  variant="secondary"
                  onClick={() => onNavigateToModule('SALES')}
                  className="text-xs"
                >
                  {language === 'ar' ? 'عرض المبيعات' : 'View Sales'}
                </GlassButton>
              )}
            </div>

            {sortedInvoices.length === 0 ? (
              <div className="p-6 text-center text-xs text-theme-muted border border-dashed border-theme rounded-xl">
                {language === 'ar' ? 'لا توجد فواتير مبيعات مسجلة بعد.' : 'No sales invoices recorded yet.'}
              </div>
            ) : (
              <div className="overflow-x-auto">
                <table className="w-full text-right border-collapse text-xs min-w-[480px]">
                  <thead>
                    <tr className="border-b border-theme text-theme-muted">
                      <th className="py-2 px-2">رقم الفاتورة</th>
                      <th className="py-2 px-2">العميل</th>
                      <th className="py-2 px-2">الإجمالي</th>
                      <th className="py-2 px-2">المتبقي</th>
                      <th className="py-2 px-2">الحالة</th>
                    </tr>
                  </thead>
                  <tbody className="divide-y divide-theme">
                    {sortedInvoices.map((inv) => {
                      const fin = ErpDataService.computeInvoiceFinancials(inv, payments);
                      const netTotal = Number(inv.total ?? inv.finalAmount ?? 0);
                      return (
                        <tr key={inv.id} className="hover:bg-theme-surface/40">
                          <td className="py-2.5 px-2 font-mono font-bold text-pmp-primary">
                            {inv.invoiceNo || inv.invoiceNumber}
                          </td>
                          <td className="py-2.5 px-2 font-semibold text-theme-primary">{inv.customerName}</td>
                          <td className="py-2.5 px-2 font-bold text-theme-primary whitespace-nowrap">
                            {netTotal.toLocaleString()} {inv.currency}
                          </td>
                          <td className="py-2.5 px-2 font-mono text-amber-400 whitespace-nowrap">
                            {fin.remainingAmount > 0 ? fin.remainingAmount.toLocaleString() : '0'} {inv.currency}
                          </td>
                          <td className="py-2.5 px-2">
                            <GlassBadge
                              variant={
                                fin.statusLabel === 'مدفوعة'
                                  ? 'green'
                                  : fin.statusLabel === 'سداد جزئي'
                                  ? 'gold'
                                  : 'red'
                              }
                            >
                              {fin.statusLabel}
                            </GlassBadge>
                          </td>
                        </tr>
                      );
                    })}
                  </tbody>
                </table>
              </div>
            )}
          </GlassCard>
        </div>

        {/* Right Sidebar Alerts & Warehouses */}
        <div className="lg:col-span-4 space-y-6">
          {/* Low Stock Raw Materials */}
          <GlassCard className="p-5 border-pmp-warning/30 bg-pmp-warning/5">
            <h4 className="text-sm font-bold text-pmp-warning mb-3 flex items-center gap-2">
              <AlertTriangle className="w-4 h-4 shrink-0" />
              <span>{language === 'ar' ? 'تنبيهات نقص الخامات والمواد' : 'Low Stock Raw Materials'}</span>
            </h4>

            {lowStockRawItems.length === 0 ? (
              <p className="text-xs text-theme-muted">جميع خامات الإنتاج أعلى من حد إعادة الطلب.</p>
            ) : (
              <div className="space-y-2.5 text-xs">
                {lowStockRawItems.map((raw) => (
                  <div
                    key={raw.id}
                    className="p-2.5 rounded-lg bg-theme-surface border border-theme flex justify-between items-center gap-2"
                  >
                    <div className="min-w-0">
                      <span className="font-bold text-theme-primary block truncate">{raw.name}</span>
                      <span className="text-[10px] text-theme-muted">{raw.category} • {raw.unit}</span>
                    </div>
                    <div className="text-left shrink-0">
                      <span className="font-mono font-bold text-pmp-warning block">
                        {raw.liveBalance.toLocaleString()}
                      </span>
                      <span className="text-[10px] text-theme-muted">حد الطلب: {raw.reorderLevel ?? 10}</span>
                    </div>
                  </div>
                ))}
              </div>
            )}
          </GlassCard>

          {/* Finished Products Low Stock */}
          {lowStockProducts.length > 0 && (
            <GlassCard className="p-5 border-red-500/30 bg-red-500/5">
              <h4 className="text-sm font-bold text-red-400 mb-3 flex items-center gap-2">
                <Package className="w-4 h-4 shrink-0" />
                <span>تنبيهات نقص المنتج التام</span>
              </h4>
              <div className="space-y-2 text-xs">
                {lowStockProducts.map((p) => (
                  <div
                    key={p.id}
                    className="p-2.5 rounded-lg bg-theme-surface border border-theme flex justify-between items-center gap-2"
                  >
                    <span className="font-bold text-theme-primary truncate">{p.name}</span>
                    <span className="font-mono font-bold text-red-400 shrink-0">
                      {p.liveStock} / {p.minimumStock}
                    </span>
                  </div>
                ))}
              </div>
            </GlassCard>
          )}

          {/* Active Warehouses */}
          <GlassCard className="p-5">
            <h4 className="text-sm font-bold text-theme-primary mb-3 flex items-center gap-2">
              <Building className="w-4 h-4 text-pmp-cyan shrink-0" />
              <span>{language === 'ar' ? 'المخازن والفروع المسجلة' : 'Registered Warehouses'}</span>
            </h4>

            {warehouses.length === 0 ? (
              <p className="text-xs text-theme-muted">لا توجد مخازن مسجلة بعد.</p>
            ) : (
              <div className="space-y-2.5 text-xs">
                {warehouses.map((w) => (
                  <div
                    key={w.id}
                    className="flex items-center justify-between gap-2 p-2.5 rounded-lg bg-theme-surface border border-theme"
                  >
                    <div className="min-w-0">
                      <span className="font-bold text-theme-primary block truncate">{w.name}</span>
                      <span className="text-[10px] text-theme-muted block truncate">
                        {w.warehouseType} • {w.linkedCashbox || '-'}
                      </span>
                    </div>
                    <GlassBadge variant={w.active ? 'green' : 'red'}>
                      {w.currency}
                    </GlassBadge>
                  </div>
                ))}
              </div>
            )}
          </GlassCard>
        </div>
      </div>
    </div>
  );
};
