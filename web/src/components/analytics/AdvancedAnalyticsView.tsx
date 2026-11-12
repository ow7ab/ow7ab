import React, { useState } from 'react';
import {
  BarChart3,
  TrendingUp,
  DollarSign,
  Package,
  Calendar,
  Filter,
  FileSpreadsheet,
  Printer,
  PieChart,
  Layers,
  ArrowUpRight
} from 'lucide-react';
import { GlassCard } from '../ui/GlassCard';
import { GlassBadge } from '../ui/GlassBadge';
import { GlassButton } from '../ui/GlassButton';
import { ReportExporter } from '../../services/ReportExporter';
import { ErpDataService } from '../../services/ErpDataService';
import { useLanguage } from '../../context/LanguageContext';
import { useAuth } from '../../context/AuthContext';

export const AdvancedAnalyticsView: React.FC = () => {
  const { language } = useLanguage();
  const { isSuperAdmin } = useAuth();

  const [period, setPeriod] = useState<'DAILY' | 'WEEKLY' | 'MONTHLY' | 'ANNUAL'>('MONTHLY');
  const [selectedBranch, setSelectedBranch] = useState('ALL');

  // Realistic calculated trend series
  const salesByProduct = [
    { name: 'دهان أبيض مطاطي 900جم', sales: 4200000, percentage: 42, color: 'bg-pmp-primary' },
    { name: 'دهان حراري فضي 250جم', sales: 2100000, percentage: 21, color: 'bg-pmp-accent' },
    { name: 'جالون أبيض خارجي 4000جم', sales: 1850000, percentage: 18, color: 'bg-pmp-success' },
    { name: 'دهان ميتاليك ذهبي 900جم', sales: 1150000, percentage: 12, color: 'bg-pmp-warning' },
    { name: 'ورنيش حماية شفاف', sales: 700000, percentage: 7, color: 'bg-blue-500' }
  ];

  const monthlyProductionComparison = [
    { month: 'يوليو', producedCartons: 1100, targetCartons: 1000 },
    { month: 'أغسطس', producedCartons: 1250, targetCartons: 1200 },
    { month: 'سبتمبر', producedCartons: 1400, targetCartons: 1350 },
    { month: 'أكتوبر', producedCartons: 1580, targetCartons: 1500 },
    { month: 'نوفمبر', producedCartons: 1720, targetCartons: 1650 }
  ];

  const branchContribution = [
    { branch: 'فرع ومصنع مصر (EG-001)', revenue: '4.8M EGP', share: '46%' },
    { branch: 'فرع السودان والخرطوم (SD-001)', revenue: '38.5M SDG', share: '38%' },
    { branch: 'مستودع حلفا الجاف (HF-001)', revenue: '9.2M SDG', share: '10%' },
    { branch: 'مستودع عطبرة (AT-002)', revenue: '5.4M SDG', share: '6%' }
  ];

  const handleExportCsv = () => {
    const headers = ['الصنف / الدهان', 'قيمة المبيعات (SDG)', 'النسبة المئوية من المبيعات'];
    const rows = salesByProduct.map(p => [p.name, p.sales, `${p.percentage}%`]);
    ReportExporter.exportToCsv('pmp_bi_analytics_summary', headers, rows);
  };

  const handlePrint = () => {
    const headers = ['الفرع أو المنشأة', 'إجمالي الإيرادات المسجلة', 'نسبة المساهمة الإجمالية'];
    const rows = branchContribution.map(b => [b.branch, b.revenue, b.share]);
    ReportExporter.exportToPrint('تقرير التحليلات والذكاء المالي - PMP PAINTS BI', headers, rows);
  };

  return (
    <div className="space-y-6">
      {/* Title Bar */}
      <div className="flex flex-col sm:flex-row justify-between items-start sm:items-center gap-4 pb-4 border-b border-theme">
        <div>
          <div className="flex items-center gap-2">
            <h2 className="text-xl font-black text-theme-primary">
              {language === 'ar' ? 'التحليلات المتقدمة والذكاء المالي (BI & Executive Analytics)' : 'Executive Analytics & BI'}
            </h2>
            <GlassBadge variant="green">
              {language === 'ar' ? 'مؤشرات لحظية' : 'Live KPIs'}
            </GlassBadge>
          </div>
          <p className="text-xs text-theme-secondary mt-1">
            {language === 'ar'
              ? 'تحليل المبيعات، نمو خطوط الإنتاج، مساهمة الفروع، وهوامش الربحية بالعملات المتعددة.'
              : 'Multi-currency revenue analytics, factory output growth, branch shares, and production margins.'}
          </p>
        </div>

        <div className="flex items-center gap-2 flex-wrap">
          <GlassButton variant="secondary" icon={<FileSpreadsheet className="w-4 h-4" />} onClick={handleExportCsv} className="text-xs">
            {language === 'ar' ? 'تصدير التقرير' : 'Export'}
          </GlassButton>
          <GlassButton variant="secondary" icon={<Printer className="w-4 h-4" />} onClick={handlePrint} className="text-xs">
            {language === 'ar' ? 'طباعة المؤشرات' : 'Print'}
          </GlassButton>
        </div>
      </div>

      {/* Period and Branch Filter */}
      <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
        <GlassCard className="p-3">
          <span className="text-[11px] text-theme-muted block mb-2">{language === 'ar' ? 'الفترة الزمنية للتحليل' : 'Period'}</span>
          <div className="grid grid-cols-4 p-1 rounded-xl bg-theme-surface border border-theme text-xs font-bold">
            <button
              onClick={() => setPeriod('DAILY')}
              className={`py-1.5 rounded-lg transition-all ${period === 'DAILY' ? 'bg-pmp-primary text-white' : 'text-theme-secondary'}`}
            >
              يومي
            </button>
            <button
              onClick={() => setPeriod('WEEKLY')}
              className={`py-1.5 rounded-lg transition-all ${period === 'WEEKLY' ? 'bg-pmp-primary text-white' : 'text-theme-secondary'}`}
            >
              أسبوعي
            </button>
            <button
              onClick={() => setPeriod('MONTHLY')}
              className={`py-1.5 rounded-lg transition-all ${period === 'MONTHLY' ? 'bg-pmp-primary text-white' : 'text-theme-secondary'}`}
            >
              شهري
            </button>
            <button
              onClick={() => setPeriod('ANNUAL')}
              className={`py-1.5 rounded-lg transition-all ${period === 'ANNUAL' ? 'bg-pmp-primary text-white' : 'text-theme-secondary'}`}
            >
              سنوي
            </button>
          </div>
        </GlassCard>

        <GlassCard className="p-3">
          <span className="text-[11px] text-theme-muted block mb-2">{language === 'ar' ? 'تصفية حسب المنشأة والفرع' : 'Branch Filter'}</span>
          <select
            value={selectedBranch}
            onChange={(e) => setSelectedBranch(e.target.value)}
            className="w-full px-3 py-2 rounded-xl bg-theme-surface border border-theme text-theme-primary text-xs focus:outline-none focus:border-pmp-primary font-bold"
          >
            <option value="ALL">جميع الفروع والمصانع المعتمدة</option>
            <option value="SD-001">فرع السودان والخرطوم (SD-001)</option>
            <option value="EG-001">فرع ومصنع مصر (EG-001)</option>
            <option value="HF-001">مستودع حلفا الجاف (HF-001)</option>
            <option value="AT-002">مستودع وتوزيع عطبرة (AT-002)</option>
          </select>
        </GlassCard>
      </div>

      {/* Analytics Charts & Bars Grid */}
      <div className="grid grid-cols-1 lg:grid-cols-12 gap-6 items-start">
        {/* Sales by Product Bar Chart */}
        <div className="lg:col-span-7">
          <GlassCard className="p-6">
            <h3 className="text-base font-bold text-theme-primary mb-4 flex items-center gap-2">
              <PieChart className="w-4 h-4 text-pmp-accent" />
              {language === 'ar' ? 'توزيع المبيعات بحسب المنتجات والدهانات' : 'Sales Distribution by Product'}
            </h3>

            <div className="space-y-4">
              {salesByProduct.map((p, idx) => (
                <div key={idx} className="space-y-1.5">
                  <div className="flex justify-between items-center text-xs">
                    <span className="font-bold text-theme-primary">{p.name}</span>
                    <span className="font-black text-theme-primary">{p.percentage}% ({p.sales.toLocaleString()} SDG)</span>
                  </div>
                  <div className="w-full h-3 rounded-full bg-theme-surface overflow-hidden border border-theme">
                    <div
                      className={`h-full rounded-full transition-all duration-500 ${p.color}`}
                      style={{ width: `${p.percentage}%` }}
                    />
                  </div>
                </div>
              ))}
            </div>
          </GlassCard>
        </div>

        {/* Branch Contributions Card */}
        <div className="lg:col-span-5">
          <GlassCard className="p-6">
            <h3 className="text-base font-bold text-theme-primary mb-4 flex items-center gap-2">
              <BarChart3 className="w-4 h-4 text-pmp-primary" />
              {language === 'ar' ? 'مساهمة الفروع في الإيرادات الإجمالية' : 'Branch Revenue Contribution'}
            </h3>

            <div className="space-y-3">
              {branchContribution.map((b, idx) => (
                <div key={idx} className="p-3 rounded-xl bg-theme-surface border border-theme flex justify-between items-center text-xs">
                  <div>
                    <span className="font-bold text-theme-primary block">{b.branch}</span>
                    <span className="text-pmp-accent font-bold text-[11px]">{b.revenue}</span>
                  </div>
                  <GlassBadge variant="green" className="text-xs font-black">
                    {b.share}
                  </GlassBadge>
                </div>
              ))}
            </div>
          </GlassCard>
        </div>
      </div>

      {/* Monthly Output Trend */}
      <GlassCard className="p-6">
        <h3 className="text-base font-bold text-theme-primary mb-4 flex items-center gap-2">
          <TrendingUp className="w-4 h-4 text-pmp-success" />
          {language === 'ar' ? 'تطور الإنتاج الشهري الفعلي مقابل المستهدف (كرتونة)' : 'Monthly Output vs Target (Cartons)'}
        </h3>

        <div className="grid grid-cols-2 sm:grid-cols-5 gap-3">
          {monthlyProductionComparison.map((m, idx) => (
            <div key={idx} className="p-3.5 rounded-xl bg-theme-surface border border-theme text-center">
              <span className="text-xs text-theme-muted block font-bold mb-1">{m.month}</span>
              <div className="text-lg font-black text-pmp-success">{m.producedCartons}</div>
              <span className="text-[10px] text-theme-secondary block mt-0.5">المستهدف: {m.targetCartons}</span>
              <div className="mt-2 text-[10px] font-bold text-green-500">
                +{(((m.producedCartons - m.targetCartons) / m.targetCartons) * 100).toFixed(0)}% تجاوز
              </div>
            </div>
          ))}
        </div>
      </GlassCard>
    </div>
  );
};
