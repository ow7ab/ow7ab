import React, { useState } from 'react';
import {
  TrendingUp,
  TrendingDown,
  DollarSign,
  Package,
  Factory,
  Users,
  AlertTriangle,
  ArrowUpRight,
  Clock,
  Layers,
  CheckCircle2,
  Calendar,
  Filter,
  FileText,
  BarChart3,
  Activity,
  Building
} from 'lucide-react';
import { GlassCard } from '../ui/GlassCard';
import { GlassBadge } from '../ui/GlassBadge';
import { GlassButton } from '../ui/GlassButton';
import { StatCard } from '../ui/StatCard';
import { useLanguage } from '../../context/LanguageContext';
import { useAuth } from '../../context/AuthContext';
import { ErpDataService } from '../../services/ErpDataService';

interface AdminDashboardOverviewProps {
  onNavigateToModule: (moduleId: string) => void;
}

export const AdminDashboardOverview: React.FC<AdminDashboardOverviewProps> = ({ onNavigateToModule }) => {
  const { language } = useLanguage();
  const { userProfile, isSuperAdmin } = useAuth();

  const [cashboxes] = useState(() => ErpDataService.getInitialCashboxes());
  const [products] = useState(() => ErpDataService.getInitialProducts());
  const [rawMaterials] = useState(() => ErpDataService.getInitialRawMaterials());
  const [batches] = useState(() => ErpDataService.getInitialProductionBatches());
  const [invoices] = useState(() => ErpDataService.getInitialSalesInvoices());
  const [movements] = useState(() => ErpDataService.getInitialTreasuryMovements());

  // Calculations from actual data
  const totalSdgTreasury = cashboxes
    .filter(c => c.currency === 'SDG')
    .reduce((sum, c) => sum + c.balance, 0);

  const totalEgpTreasury = cashboxes
    .filter(c => c.currency === 'EGP')
    .reduce((sum, c) => sum + c.balance, 0);

  const totalUsdTreasury = cashboxes
    .filter(c => c.currency === 'USD')
    .reduce((sum, c) => sum + c.balance, 0);

  const totalFinishedCartons = products.reduce((sum, p) => sum + p.currentStock, 0);
  const totalActiveBatches = batches.filter(b => b.status === 'IN_PRODUCTION' || b.status === 'QC_PENDING').length;
  const lowStockCount = rawMaterials.filter(r => r.actualBalance <= r.reorderLevel).length;

  return (
    <div className="space-y-6">
      {/* 1. Header Banner with Cluster Status */}
      <GlassCard className="p-6 relative overflow-hidden bg-gradient-to-r from-theme-surface to-pmp-primary/10">
        <div className="flex flex-col sm:flex-row justify-between items-start sm:items-center gap-4">
          <div>
            <div className="flex items-center gap-2">
              <h2 className="text-2xl font-black text-theme-primary">
                {language === 'ar' ? 'لوحة القيادة التنفيذية والمؤشرات المالية' : 'Executive Management Dashboard'}
              </h2>
              <GlassBadge variant="red">
                {language === 'ar' ? 'السيرفر المعتمد V16' : 'Active Cluster'}
              </GlassBadge>
            </div>
            <p className="text-xs text-theme-secondary mt-1">
              {language === 'ar'
                ? 'مراقبة فورية لمصانع الخرطوم والقاهرة، أرصدة الخزن متعددة العملات، ومستودعات التوزيع وحلفا.'
                : 'Real-time multi-currency treasury, raw materials, batch production, and transit hub monitoring.'}
            </p>
          </div>

          <div className="flex items-center gap-2 flex-wrap">
            <div className="flex items-center gap-2 px-3 py-1.5 rounded-lg bg-green-500/10 border border-green-500/30 text-green-500 text-xs font-bold">
              <span className="w-2 h-2 rounded-full bg-green-500 animate-ping" />
              <span>{language === 'ar' ? 'المزامنة السحابية لحظية' : 'Cloud Sync: Live'}</span>
            </div>
          </div>
        </div>
      </GlassCard>

      {/* 2. Real Financial & Operational KPIs */}
      <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-4">
        {/* SDG Treasury */}
        <StatCard
          title={language === 'ar' ? 'رصيد الخزن (جنيه سوداني)' : 'Sudan Treasury (SDG)'}
          value={`${(totalSdgTreasury / 1000000).toFixed(2)}M SDG`}
          change="+12.4% هذا الشهر"
          isPositive={true}
          icon={<DollarSign className="w-5 h-5 text-pmp-primary" />}
        />

        {/* EGP Treasury */}
        <StatCard
          title={language === 'ar' ? 'رصيد الخزن (جنيه مصري)' : 'Egypt Treasury (EGP)'}
          value={`${(totalEgpTreasury / 1000).toFixed(1)}K EGP`}
          change="+8.1% توريدات المصنع"
          isPositive={true}
          icon={<DollarSign className="w-5 h-5 text-pmp-gold" />}
        />

        {/* USD Vault */}
        <StatCard
          title={language === 'ar' ? 'خزنة الدولار الأمريكي' : 'USD Cashbox (USD)'}
          value={`$${totalUsdTreasury.toLocaleString()}`}
          change="مخصص للتجارة الخارجية"
          isPositive={true}
          icon={<DollarSign className="w-5 h-5 text-pmp-cyan" />}
        />

        {/* Finished Goods Inventory */}
        <StatCard
          title={language === 'ar' ? 'مخزون المنتج التام' : 'Finished Goods'}
          value={`${totalFinishedCartons} كرتونة`}
          change="موزعة على 4 مخازن"
          isPositive={true}
          icon={<Package className="w-5 h-5 text-pmp-success" />}
        />
      </div>

      {/* 3. Operational Highlights & Alerts */}
      <div className="grid grid-cols-1 lg:grid-cols-12 gap-6 items-start">
        {/* Left: Active Production Orders & Quick Actions */}
        <div className="lg:col-span-8 space-y-6">
          <GlassCard className="p-6">
            <div className="flex justify-between items-center mb-4">
              <h3 className="text-base font-bold text-theme-primary flex items-center gap-2">
                <Factory className="w-4 h-4 text-pmp-accent" />
                {language === 'ar' ? 'تشغيلات وأوامر الإنتاج النشطة بالمصنع' : 'Active Production Line Orders'}
              </h3>
              <GlassButton variant="secondary" onClick={() => onNavigateToModule('FACTORY')} className="text-xs">
                {language === 'ar' ? 'فتح صالة الإنتاج' : 'Open Factory'}
              </GlassButton>
            </div>

            <div className="space-y-3">
              {batches.map(b => (
                <div
                  key={b.id}
                  className="p-3.5 rounded-xl bg-theme-surface border border-theme flex flex-col sm:flex-row justify-between sm:items-center gap-3 text-xs"
                >
                  <div className="space-y-1">
                    <div className="flex items-center gap-2">
                      <span className="font-bold text-theme-primary text-sm">{b.orderNumber}</span>
                      <GlassBadge variant={b.status === 'COMPLETED' ? 'green' : 'gold'}>
                        {b.status === 'COMPLETED' ? (language === 'ar' ? 'مكتمل' : 'Completed') : (language === 'ar' ? 'قيد الخلط' : 'In Progress')}
                      </GlassBadge>
                    </div>
                    <div className="text-theme-secondary">
                      {b.formulaName} • <b>{b.actualProducedCans} علبة</b> ({b.packageWeightGrams}جم)
                    </div>
                  </div>

                  <div className="flex items-center gap-3 text-theme-muted">
                    <span>{b.productionEngineer}</span>
                    <span>•</span>
                    <span className="font-mono">{b.date}</span>
                  </div>
                </div>
              ))}
            </div>
          </GlassCard>

          {/* Recent Invoices Table */}
          <GlassCard className="p-6">
            <div className="flex justify-between items-center mb-4">
              <h3 className="text-base font-bold text-theme-primary flex items-center gap-2">
                <FileText className="w-4 h-4 text-pmp-primary" />
                {language === 'ar' ? 'أحدث فواتير المبيعات الصادرة' : 'Recent Sales Invoices'}
              </h3>
              <GlassButton variant="secondary" onClick={() => onNavigateToModule('SALES')} className="text-xs">
                {language === 'ar' ? 'عرض كل المبيعات' : 'View Sales'}
              </GlassButton>
            </div>

            <div className="overflow-x-auto">
              <table className="w-full text-right border-collapse text-xs">
                <thead>
                  <tr className="border-b border-theme text-theme-muted pb-2">
                    <th className="py-2">{language === 'ar' ? 'رقم الفاتورة' : 'Invoice #'}</th>
                    <th className="py-2">{language === 'ar' ? 'العميل' : 'Customer'}</th>
                    <th className="py-2">{language === 'ar' ? 'القيمة الإجمالية' : 'Total'}</th>
                    <th className="py-2">{language === 'ar' ? 'حالة السداد' : 'Status'}</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-theme">
                  {invoices.map(inv => (
                    <tr key={inv.id} className="hover:bg-theme-surface/40">
                      <td className="py-3 font-mono font-bold text-pmp-primary">{inv.invoiceNumber}</td>
                      <td className="py-3 font-semibold text-theme-primary">{inv.customerName}</td>
                      <td className="py-3 font-bold text-theme-primary">
                        {inv.finalAmount.toLocaleString()} {inv.currency}
                      </td>
                      <td className="py-3">
                        <GlassBadge variant={inv.status === 'PAID' ? 'green' : 'gold'}>
                          {inv.status === 'PAID' ? (language === 'ar' ? 'مدفوعة بالكامل' : 'Paid') : (language === 'ar' ? 'سداد جزئي' : 'Partial')}
                        </GlassBadge>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </GlassCard>
        </div>

        {/* Right: Low Stock & Hub Status */}
        <div className="lg:col-span-4 space-y-6">
          {/* Low Stock Alerts */}
          <GlassCard className="p-5 border-pmp-warning/30 bg-pmp-warning/5">
            <h4 className="text-sm font-bold text-pmp-warning mb-3 flex items-center gap-2">
              <AlertTriangle className="w-4 h-4" />
              {language === 'ar' ? 'تنبيهات خامات الإنتاج وحد الطلب' : 'Raw Materials Reorder Alerts'}
            </h4>

            <div className="space-y-2.5 text-xs">
              {rawMaterials.slice(0, 3).map(raw => (
                <div key={raw.id} className="p-2.5 rounded-lg bg-theme-surface border border-theme flex justify-between items-center">
                  <div>
                    <span className="font-bold text-theme-primary block">{raw.name}</span>
                    <span className="text-[11px] text-theme-muted">{raw.branch}</span>
                  </div>
                  <div className="text-left">
                    <span className="font-bold text-pmp-warning">{raw.actualBalance} {raw.unit}</span>
                    <span className="text-[10px] text-theme-muted block">حد الطلب: {raw.reorderLevel}</span>
                  </div>
                </div>
              ))}
            </div>
          </GlassCard>

          {/* Branch Facilities Operational Overview */}
          <GlassCard className="p-5">
            <h4 className="text-sm font-bold text-theme-primary mb-3 flex items-center gap-2">
              <Building className="w-4 h-4 text-pmp-cyan" />
              {language === 'ar' ? 'حالة جاهزية الفروع والمستودعات' : 'Branch Hub Status'}
            </h4>

            <div className="space-y-3 text-xs">
              <div className="flex items-center justify-between p-2 rounded-lg bg-theme-surface border border-theme">
                <div>
                  <span className="font-bold text-theme-primary block">فرع ومصنع مصر (EG-001)</span>
                  <span className="text-[10px] text-theme-muted">خطوط التعبئة والخلط الآلي</span>
                </div>
                <GlassBadge variant="green">{language === 'ar' ? 'يعمل 100%' : 'Online'}</GlassBadge>
              </div>

              <div className="flex items-center justify-between p-2 rounded-lg bg-theme-surface border border-theme">
                <div>
                  <span className="font-bold text-theme-primary block">مستودع حلفا الجاف (HF-001)</span>
                  <span className="text-[10px] text-theme-muted">تخليص بضاعة العبور</span>
                </div>
                <GlassBadge variant="green">{language === 'ar' ? 'نشط' : 'Active'}</GlassBadge>
              </div>

              <div className="flex items-center justify-between p-2 rounded-lg bg-theme-surface border border-theme">
                <div>
                  <span className="font-bold text-theme-primary block">مستودع عطبرة (AT-002)</span>
                  <span className="text-[10px] text-theme-muted">توزيع الولايات الشمالية</span>
                </div>
                <GlassBadge variant="cyan">{language === 'ar' ? 'جاهزية كاملة' : 'Ready'}</GlassBadge>
              </div>
            </div>
          </GlassCard>
        </div>
      </div>
    </div>
  );
};
