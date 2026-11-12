import React, { useState } from 'react';
import {
  FileText,
  Download,
  Printer,
  Calendar,
  Filter,
  CheckCircle2,
  DollarSign,
  Package,
  Factory,
  Building,
  ShieldCheck,
  FileSpreadsheet
} from 'lucide-react';
import { GlassCard } from '../ui/GlassCard';
import { GlassBadge } from '../ui/GlassBadge';
import { GlassButton } from '../ui/GlassButton';
import { ReportExporter } from '../../services/ReportExporter';
import { ErpDataService } from '../../services/ErpDataService';
import { useLanguage } from '../../context/LanguageContext';
import { useAuth } from '../../context/AuthContext';

export const ReportsCenterView: React.FC = () => {
  const { language } = useLanguage();
  const { canPerformAction, isSuperAdmin } = useAuth();

  const [reportType, setReportType] = useState<'FINANCIAL' | 'INVENTORY' | 'PRODUCTION' | 'CUSTOMERS'>('FINANCIAL');
  const [dateFrom, setDateFrom] = useState('2026-11-01');
  const [dateTo, setDateTo] = useState('2026-11-12');

  const cashboxes = ErpDataService.getInitialCashboxes();
  const movements = ErpDataService.getInitialTreasuryMovements();
  const products = ErpDataService.getInitialProducts();
  const batches = ErpDataService.getInitialProductionBatches();
  const invoices = ErpDataService.getInitialSalesInvoices();

  const handleExportCsv = () => {
    if (reportType === 'FINANCIAL') {
      const headers = ['نوع السند', 'البند', 'المبلغ', 'العملة', 'الخزنة', 'البيان', 'المسؤول', 'التاريخ'];
      const rows = movements.map(m => [m.type, m.category, m.amount, m.currency, m.cashboxName, m.description, m.employeeName, m.date]);
      ReportExporter.exportToCsv('pmp_financial_report', headers, rows);
    } else if (reportType === 'INVENTORY') {
      const headers = ['اسم الصنف', 'اللون', 'الحجم', 'الرصيد الفعلي (كرتونة)', 'سعر البيع SDG', 'سعر البيع EGP'];
      const rows = products.map(p => [p.name, p.paintColor, `${p.packageWeight}جم`, p.currentStock, p.salePriceSdg, p.salePriceEgp]);
      ReportExporter.exportToCsv('pmp_inventory_valuation_report', headers, rows);
    } else if (reportType === 'PRODUCTION') {
      const headers = ['رقم الأمر', 'اللون', 'التركيبة', 'الكراتين المنتجة', 'العبوات السليمة', 'المهندس', 'التاريخ'];
      const rows = batches.map(b => [b.orderNumber, b.paintColor, b.formulaName, b.targetCartons, b.goodApprovedCans, b.productionEngineer, b.date]);
      ReportExporter.exportToCsv('pmp_production_report', headers, rows);
    } else {
      const headers = ['رقم الفاتورة', 'اسم العميل', 'الصافي', 'المدفوع', 'المتبقي', 'العملة', 'الحالة'];
      const rows = invoices.map(i => [i.invoiceNumber, i.customerName, i.finalAmount, i.paidAmount, i.remainingAmount, i.currency, i.status]);
      ReportExporter.exportToCsv('pmp_customer_receivables_report', headers, rows);
    }
  };

  const handlePrint = () => {
    if (reportType === 'FINANCIAL') {
      const headers = ['النوع', 'البند', 'المبلغ', 'العملة', 'الخزنة', 'البيان'];
      const rows = movements.map(m => [m.type === 'INCOME' ? 'قبض' : 'صرف', m.category, m.amount.toLocaleString(), m.currency, m.cashboxName, m.description]);
      ReportExporter.exportToPrint('التقرير المالي وحركة الخزينة والسيولة', headers, rows);
    } else if (reportType === 'INVENTORY') {
      const headers = ['الصنف', 'اللون', 'الوزن', 'الرصيد الفعلي', 'السعر SDG'];
      const rows = products.map(p => [p.name, p.paintColor, `${p.packageWeight}جم`, p.currentStock, p.salePriceSdg.toLocaleString()]);
      ReportExporter.exportToPrint('تقرير جرد وتقييم مخزون المنتج التام', headers, rows);
    } else if (reportType === 'PRODUCTION') {
      const headers = ['الأمر', 'اللون', 'التركيبة', 'الكراتين', 'المهندس'];
      const rows = batches.map(b => [b.orderNumber, b.paintColor, b.formulaName, b.targetCartons, b.productionEngineer]);
      ReportExporter.exportToPrint('تقرير كفاءة وتشغيل خطوط إنتاج المصنع', headers, rows);
    } else {
      const headers = ['الفاتورة', 'العميل', 'الصافي', 'المدفوع', 'المتبقي', 'الحالة'];
      const rows = invoices.map(i => [i.invoiceNumber, i.customerName, i.finalAmount.toLocaleString(), i.paidAmount.toLocaleString(), i.remainingAmount.toLocaleString(), i.status]);
      ReportExporter.exportToPrint('تقرير مديونيات وحسابات العملاء', headers, rows);
    }
  };

  return (
    <div className="space-y-6">
      {/* Title Bar */}
      <div className="flex flex-col sm:flex-row justify-between items-start sm:items-center gap-4 pb-4 border-b border-theme">
        <div>
          <div className="flex items-center gap-2">
            <h2 className="text-xl font-black text-theme-primary">
              {language === 'ar' ? 'مركز التقارير التنفيذية والطباعة (Enterprise Reports Center)' : 'Reports & Exports Center'}
            </h2>
            <GlassBadge variant="cyan">
              {language === 'ar' ? 'تصدير PDF وExcel' : 'PDF & Excel'}
            </GlassBadge>
          </div>
          <p className="text-xs text-theme-secondary mt-1">
            {language === 'ar'
              ? 'استخراج وطباعة الكشوفات المالية المعتمدة، جرد المخازن، أوامر المصنع، ومديونيات العملاء.'
              : 'Generate approved statement sheets, inventory valuations, batch records, and customer accounts.'}
          </p>
        </div>

        <div className="flex items-center gap-2 flex-wrap">
          <GlassButton variant="secondary" icon={<FileSpreadsheet className="w-4 h-4" />} onClick={handleExportCsv} className="text-xs">
            {language === 'ar' ? 'تصدير Excel/CSV' : 'Export Excel'}
          </GlassButton>
          <GlassButton variant="primary" icon={<Printer className="w-4 h-4" />} onClick={handlePrint} className="text-xs">
            {language === 'ar' ? 'طباعة / PDF' : 'Print PDF'}
          </GlassButton>
        </div>
      </div>

      {/* Report Types Grid Selection */}
      <div className="grid grid-cols-2 sm:grid-cols-4 gap-3">
        <button
          onClick={() => setReportType('FINANCIAL')}
          className={`p-3.5 rounded-xl border text-xs text-right transition-all ${
            reportType === 'FINANCIAL'
              ? 'bg-pmp-primary/10 border-pmp-primary shadow-md font-bold text-theme-primary'
              : 'bg-theme-surface border-theme text-theme-secondary hover:border-theme-hover'
          }`}
        >
          <DollarSign className="w-5 h-5 text-pmp-primary mb-1.5" />
          <span className="block text-sm font-bold">{language === 'ar' ? 'التقرير المالي وحركة الخزن' : 'Financial Statement'}</span>
          <span className="text-[10px] text-theme-muted">سندات القبض والصرف والإقفالات</span>
        </button>

        <button
          onClick={() => setReportType('INVENTORY')}
          className={`p-3.5 rounded-xl border text-xs text-right transition-all ${
            reportType === 'INVENTORY'
              ? 'bg-pmp-primary/10 border-pmp-primary shadow-md font-bold text-theme-primary'
              : 'bg-theme-surface border-theme text-theme-secondary hover:border-theme-hover'
          }`}
        >
          <Package className="w-5 h-5 text-pmp-accent mb-1.5" />
          <span className="block text-sm font-bold">{language === 'ar' ? 'جرد وتقييم المخزون' : 'Inventory Valuation'}</span>
          <span className="text-[10px] text-theme-muted">المنتج التام وبراميل الخامات</span>
        </button>

        <button
          onClick={() => setReportType('PRODUCTION')}
          className={`p-3.5 rounded-xl border text-xs text-right transition-all ${
            reportType === 'PRODUCTION'
              ? 'bg-pmp-primary/10 border-pmp-primary shadow-md font-bold text-theme-primary'
              : 'bg-theme-surface border-theme text-theme-secondary hover:border-theme-hover'
          }`}
        >
          <Factory className="w-5 h-5 text-pmp-success mb-1.5" />
          <span className="block text-sm font-bold">{language === 'ar' ? 'تقرير إنتاج المصنع' : 'Factory Output'}</span>
          <span className="text-[10px] text-theme-muted">أوامر الخلط ومخرجات الكراتين</span>
        </button>

        <button
          onClick={() => setReportType('CUSTOMERS')}
          className={`p-3.5 rounded-xl border text-xs text-right transition-all ${
            reportType === 'CUSTOMERS'
              ? 'bg-pmp-primary/10 border-pmp-primary shadow-md font-bold text-theme-primary'
              : 'bg-theme-surface border-theme text-theme-secondary hover:border-theme-hover'
          }`}
        >
          <Building className="w-5 h-5 text-pmp-warning mb-1.5" />
          <span className="block text-sm font-bold">{language === 'ar' ? 'مديونيات وفواتير العملاء' : 'Customer Receivables'}</span>
          <span className="text-[10px] text-theme-muted">الأرصدة الآجلة وسداد الأقساط</span>
        </button>
      </div>

      {/* Date Range Selection Bar */}
      <GlassCard className="p-4">
        <div className="grid grid-cols-1 sm:grid-cols-2 gap-4 text-xs">
          <div>
            <label className="block text-theme-muted mb-1 font-bold">{language === 'ar' ? 'من تاريخ:' : 'From Date:'}</label>
            <input
              type="date"
              value={dateFrom}
              onChange={(e) => setDateFrom(e.target.value)}
              className="w-full px-3 py-2 rounded-lg bg-theme-surface border border-theme text-theme-primary focus:outline-none focus:border-pmp-primary font-mono"
            />
          </div>

          <div>
            <label className="block text-theme-muted mb-1 font-bold">{language === 'ar' ? 'إلى تاريخ:' : 'To Date:'}</label>
            <input
              type="date"
              value={dateTo}
              onChange={(e) => setDateTo(e.target.value)}
              className="w-full px-3 py-2 rounded-lg bg-theme-surface border border-theme text-theme-primary focus:outline-none focus:border-pmp-primary font-mono"
            />
          </div>
        </div>
      </GlassCard>

      {/* Report Preview Data Table */}
      <GlassCard className="p-0 overflow-hidden">
        <div className="p-4 border-b border-theme flex justify-between items-center">
          <h4 className="font-bold text-theme-primary text-sm">
            {reportType === 'FINANCIAL' ? 'معاينة: حركة الخزينة والسيولة النقدية' :
             reportType === 'INVENTORY' ? 'معاينة: رصيد وتقييم مستودعات المنتج التام' :
             reportType === 'PRODUCTION' ? 'معاينة: أوامر وتشغيلات خطوط خلط الدهانات' :
             'معاينة: كشف فواتير ومستحقات العملاء'}
          </h4>
          <span className="text-xs text-theme-muted font-mono">{dateFrom} إلى {dateTo}</span>
        </div>

        <div className="overflow-x-auto">
          {reportType === 'FINANCIAL' && (
            <table className="w-full text-right border-collapse text-xs">
              <thead>
                <tr className="bg-theme-surface/70 border-b border-theme text-theme-muted font-bold">
                  <th className="py-2.5 px-4">نوع السند</th>
                  <th className="py-2.5 px-4">البند</th>
                  <th className="py-2.5 px-4">المبلغ والعملة</th>
                  <th className="py-2.5 px-4">الخزنة</th>
                  <th className="py-2.5 px-4">البيان</th>
                  <th className="py-2.5 px-4">التاريخ</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-theme">
                {movements.map(m => (
                  <tr key={m.id} className="hover:bg-theme-surface/40">
                    <td className="py-3 px-4 font-bold text-pmp-primary">{m.type === 'INCOME' ? 'قبض' : 'صرف'}</td>
                    <td className="py-3 px-4">{m.category}</td>
                    <td className="py-3 px-4 font-black">{m.amount.toLocaleString()} {m.currency}</td>
                    <td className="py-3 px-4 text-theme-secondary">{m.cashboxName}</td>
                    <td className="py-3 px-4">{m.description}</td>
                    <td className="py-3 px-4 font-mono text-theme-muted">{m.date}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}

          {reportType === 'INVENTORY' && (
            <table className="w-full text-right border-collapse text-xs">
              <thead>
                <tr className="bg-theme-surface/70 border-b border-theme text-theme-muted font-bold">
                  <th className="py-2.5 px-4">الصنف والطلاء</th>
                  <th className="py-2.5 px-4">اللون والتصنيف</th>
                  <th className="py-2.5 px-4">حجم العبوة</th>
                  <th className="py-2.5 px-4">الرصيد الفعلي</th>
                  <th className="py-2.5 px-4">السعر (SDG)</th>
                  <th className="py-2.5 px-4">السعر (EGP)</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-theme">
                {products.map(p => (
                  <tr key={p.id} className="hover:bg-theme-surface/40">
                    <td className="py-3 px-4 font-bold text-theme-primary">{p.name}</td>
                    <td className="py-3 px-4">{p.paintColor} ({p.category})</td>
                    <td className="py-3 px-4 font-mono">{p.packageWeight}جم</td>
                    <td className="py-3 px-4 font-black text-pmp-accent">{p.currentStock} كرتونة</td>
                    <td className="py-3 px-4 font-bold">{p.salePriceSdg.toLocaleString()} SDG</td>
                    <td className="py-3 px-4 font-bold">{p.salePriceEgp.toLocaleString()} EGP</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}

          {reportType === 'PRODUCTION' && (
            <table className="w-full text-right border-collapse text-xs">
              <thead>
                <tr className="bg-theme-surface/70 border-b border-theme text-theme-muted font-bold">
                  <th className="py-2.5 px-4">رقم الأمر</th>
                  <th className="py-2.5 px-4">التركيبة واللون</th>
                  <th className="py-2.5 px-4">العبوة</th>
                  <th className="py-2.5 px-4">الإنتاج المستهدف</th>
                  <th className="py-2.5 px-4">الإنتاج الفعلي</th>
                  <th className="py-2.5 px-4">المهندس</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-theme">
                {batches.map(b => (
                  <tr key={b.id} className="hover:bg-theme-surface/40">
                    <td className="py-3 px-4 font-mono font-bold text-pmp-primary">{b.orderNumber}</td>
                    <td className="py-3 px-4 font-bold">{b.formulaName} ({b.paintColor})</td>
                    <td className="py-3 px-4 font-mono">{b.packageWeightGrams}جم</td>
                    <td className="py-3 px-4">{b.targetCartons} كرتونة</td>
                    <td className="py-3 px-4 font-black text-pmp-success">{b.actualProducedCans} علبة</td>
                    <td className="py-3 px-4 text-theme-secondary">{b.productionEngineer}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}

          {reportType === 'CUSTOMERS' && (
            <table className="w-full text-right border-collapse text-xs">
              <thead>
                <tr className="bg-theme-surface/70 border-b border-theme text-theme-muted font-bold">
                  <th className="py-2.5 px-4">رقم الفاتورة</th>
                  <th className="py-2.5 px-4">اسم العميل</th>
                  <th className="py-2.5 px-4">صافي القيمة</th>
                  <th className="py-2.5 px-4">المسدد</th>
                  <th className="py-2.5 px-4">المتبقي (مديونية)</th>
                  <th className="py-2.5 px-4">الحالة</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-theme">
                {invoices.map(i => (
                  <tr key={i.id} className="hover:bg-theme-surface/40">
                    <td className="py-3 px-4 font-mono font-bold text-pmp-primary">{i.invoiceNumber}</td>
                    <td className="py-3 px-4 font-bold">{i.customerName}</td>
                    <td className="py-3 px-4 font-black">{i.finalAmount.toLocaleString()} {i.currency}</td>
                    <td className="py-3 px-4 text-green-500 font-bold">{i.paidAmount.toLocaleString()} {i.currency}</td>
                    <td className="py-3 px-4 font-bold text-theme-muted">{i.remainingAmount > 0 ? `${i.remainingAmount.toLocaleString()} ${i.currency}` : '-'}</td>
                    <td className="py-3 px-4">
                      <GlassBadge variant={i.status === 'PAID' ? 'green' : 'gold'}>{i.status}</GlassBadge>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </div>
      </GlassCard>
    </div>
  );
};
