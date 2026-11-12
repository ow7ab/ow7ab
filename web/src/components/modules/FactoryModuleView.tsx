import React, { useState } from 'react';
import {
  Factory,
  Package,
  Plus,
  Play,
  CheckCircle2,
  Clock,
  FlaskConical,
  Layers,
  Search,
  Filter,
  FileSpreadsheet,
  Printer
} from 'lucide-react';
import { GlassCard } from '../ui/GlassCard';
import { GlassBadge } from '../ui/GlassBadge';
import { GlassButton } from '../ui/GlassButton';
import { Modal } from '../ui/Modal';
import { ProductionOrder, LabFormulation } from '../../types/erp';
import { ErpDataService } from '../../services/ErpDataService';
import { ReportExporter } from '../../services/ReportExporter';
import { useLanguage } from '../../context/LanguageContext';
import { useAuth } from '../../context/AuthContext';

export const FactoryModuleView: React.FC = () => {
  const { language } = useLanguage();
  const { canPerformAction, userProfile } = useAuth();

  const [batches, setBatches] = useState<ProductionOrder[]>(() => ErpDataService.getInitialProductionBatches());
  const [formulations] = useState<LabFormulation[]>(() => ErpDataService.getInitialLabFormulations());
  const [searchTerm, setSearchTerm] = useState('');
  const [statusFilter, setStatusFilter] = useState('ALL');
  const [isNewBatchOpen, setIsNewBatchOpen] = useState(false);

  // New Batch Form State
  const [newBatch, setNewBatch] = useState({
    paintColor: 'أبيض',
    formulaName: 'تركيبة دهان أبيض مطاطي V-4',
    packageWeightGrams: 900 as 250 | 900 | 4000,
    targetWeightKg: 1000,
    targetCartons: 90
  });

  const filteredBatches = batches.filter(b => {
    const matchesSearch =
      b.orderNumber.toLowerCase().includes(searchTerm.toLowerCase()) ||
      b.formulaName.toLowerCase().includes(searchTerm.toLowerCase()) ||
      b.productionEngineer.toLowerCase().includes(searchTerm.toLowerCase());

    const matchesStatus = statusFilter === 'ALL' || b.status === statusFilter;
    return matchesSearch && matchesStatus;
  });

  const handleCreateBatch = async (e: React.FormEvent) => {
    e.preventDefault();
    const orderNumber = `PRD-2026-${(batches.length + 42).toString().padStart(3, '0')}`;
    const cansPerCarton = newBatch.packageWeightGrams === 250 ? 48 : newBatch.packageWeightGrams === 900 ? 12 : 4;
    const targetCans = newBatch.targetCartons * cansPerCarton;

    const created: ProductionOrder = {
      id: Date.now(),
      orderNumber,
      paintColor: newBatch.paintColor,
      formulaName: newBatch.formulaName,
      packageWeightGrams: newBatch.packageWeightGrams,
      targetWeightKg: Number(newBatch.targetWeightKg),
      targetCartons: Number(newBatch.targetCartons),
      actualProducedCans: targetCans,
      goodApprovedCans: targetCans,
      status: 'IN_PRODUCTION',
      productionEngineer: userProfile?.fullName || 'مهندس الوردية',
      date: new Date().toISOString().split('T')[0]
    };

    setBatches(prev => [created, ...prev]);
    await ErpDataService.saveRecord('production_orders', orderNumber, created);
    setIsNewBatchOpen(false);
  };

  const handleExportCsv = () => {
    const headers = ['رقم الأمر', 'اللون والنوع', 'التركيبة المعتمدة', 'سعة العبوة', 'الوزن المستهدف (كجم)', 'الكراتين المستهدفة', 'الحالة', 'مهندس الإنتاج', 'التاريخ'];
    const rows = filteredBatches.map(b => [
      b.orderNumber,
      b.paintColor,
      b.formulaName,
      `${b.packageWeightGrams}جم`,
      b.targetWeightKg,
      b.targetCartons,
      b.status,
      b.productionEngineer,
      b.date
    ]);
    ReportExporter.exportToCsv('pmp_factory_batches', headers, rows);
  };

  const handlePrint = () => {
    const headers = ['رقم الأمر', 'اللون', 'التركيبة', 'العبوة', 'الكراتين', 'الحالة', 'المهندس'];
    const rows = filteredBatches.map(b => [
      b.orderNumber,
      b.paintColor,
      b.formulaName,
      `${b.packageWeightGrams}جم`,
      b.targetCartons,
      b.status,
      b.productionEngineer
    ]);
    ReportExporter.exportToPrint('تقرير أوامر تشغيل خطوط الإنتاج - مصنع PMP', headers, rows);
  };

  return (
    <div className="space-y-6">
      {/* Module Title Bar */}
      <div className="flex flex-col sm:flex-row justify-between items-start sm:items-center gap-4 pb-4 border-b border-theme">
        <div>
          <div className="flex items-center gap-2">
            <h2 className="text-xl font-black text-theme-primary">
              {language === 'ar' ? 'المصنع وصالة الإنتاج (Factory & Production Lines)' : 'Factory Production Management'}
            </h2>
            <GlassBadge variant="red">
              {batches.length} {language === 'ar' ? 'أمر تشغيل' : 'Batches'}
            </GlassBadge>
          </div>
          <p className="text-xs text-theme-secondary mt-1">
            {language === 'ar'
              ? 'أوامر خلط الدهانات، نسب التركيبات الكيميائية (BOM)، حساب العلب والكراتين، ومطابقة الجودة.'
              : 'Production batch control, BOM formulation calculations, packaging outputs, and line oversight.'}
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
            <GlassButton variant="primary" icon={<Plus className="w-4 h-4" />} onClick={() => setIsNewBatchOpen(true)} className="text-xs">
              {language === 'ar' ? 'أمر تشغيل جديد' : 'New Batch'}
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
              placeholder={language === 'ar' ? 'بحث برقم الأمر، اسم التركيبة، أو المهندس المسؤول...' : 'Search batches...'}
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
              <option value="IN_PRODUCTION">{language === 'ar' ? 'قيد الخلط والتشغيل' : 'In Production'}</option>
              <option value="QC_PENDING">{language === 'ar' ? 'بانتظار فحص المختبر' : 'QC Pending'}</option>
              <option value="COMPLETED">{language === 'ar' ? 'مكتمل ومعتمد للتعبئة' : 'Completed'}</option>
            </select>
          </div>
        </div>
      </GlassCard>

      {/* Production Batches Table */}
      <GlassCard className="p-0 overflow-hidden">
        <div className="overflow-x-auto">
          <table className="w-full text-right border-collapse text-xs">
            <thead>
              <tr className="bg-theme-surface/70 border-b border-theme text-theme-muted font-bold">
                <th className="py-3 px-4">{language === 'ar' ? 'رقم التشغيلة' : 'Order #'}</th>
                <th className="py-3 px-4">{language === 'ar' ? 'التركيبة واللون' : 'Formula & Color'}</th>
                <th className="py-3 px-4">{language === 'ar' ? 'معيار التعبئة' : 'Packaging'}</th>
                <th className="py-3 px-4">{language === 'ar' ? 'الإنتاج الفعلي' : 'Output Output'}</th>
                <th className="py-3 px-4">{language === 'ar' ? 'الحالة' : 'Status'}</th>
                <th className="py-3 px-4">{language === 'ar' ? 'المهندس المسؤول' : 'Engineer'}</th>
                <th className="py-3 px-4">{language === 'ar' ? 'التاريخ' : 'Date'}</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-theme">
              {filteredBatches.map(b => (
                <tr key={b.id} className="hover:bg-theme-surface/40 transition-colors">
                  <td className="py-3.5 px-4 font-mono font-bold text-pmp-primary">{b.orderNumber}</td>
                  <td className="py-3.5 px-4">
                    <span className="font-bold text-theme-primary block">{b.formulaName}</span>
                    <span className="text-[11px] text-theme-muted">لون: {b.paintColor}</span>
                  </td>
                  <td className="py-3.5 px-4 font-semibold text-theme-primary">
                    {b.packageWeightGrams}جم ({b.packageWeightGrams === 250 ? '48 علبة/كرتونة' : b.packageWeightGrams === 900 ? '12 علبة/كرتونة' : '4 علب/كرتونة'})
                  </td>
                  <td className="py-3.5 px-4">
                    <span className="font-bold text-pmp-accent block">{b.actualProducedCans} علبة</span>
                    <span className="text-[10px] text-theme-muted">{b.targetCartons} كرتونة معتمدة</span>
                  </td>
                  <td className="py-3.5 px-4">
                    <GlassBadge variant={b.status === 'COMPLETED' ? 'green' : b.status === 'IN_PRODUCTION' ? 'gold' : 'cyan'}>
                      {b.status === 'COMPLETED' ? 'مكتمل' : b.status === 'IN_PRODUCTION' ? 'قيد الخلط' : 'فحص الجودة'}
                    </GlassBadge>
                  </td>
                  <td className="py-3.5 px-4 text-theme-secondary">{b.productionEngineer}</td>
                  <td className="py-3.5 px-4 font-mono text-theme-muted">{b.date}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </GlassCard>

      {/* New Batch Creation Modal */}
      <Modal
        isOpen={isNewBatchOpen}
        onClose={() => setIsNewBatchOpen(false)}
        title={language === 'ar' ? 'إصدار أمر تشغيل وخلط جديد' : 'Issue New Production Batch'}
        maxWidth="max-w-lg"
      >
        <form onSubmit={handleCreateBatch} className="space-y-4 text-xs">
          <div>
            <label className="block font-semibold text-theme-primary mb-1">
              {language === 'ar' ? 'التركيبة الكيميائية المعتمدة' : 'Chemical Formulation'}
            </label>
            <select
              value={newBatch.formulaName}
              onChange={(e) => setNewBatch({ ...newBatch, formulaName: e.target.value })}
              className="w-full px-3 py-2 rounded-lg bg-theme-surface border border-theme text-theme-primary focus:outline-none focus:border-pmp-primary"
            >
              {formulations.map(f => (
                <option key={f.id} value={f.formulaName}>{f.formulaName} ({f.formulaNo})</option>
              ))}
            </select>
          </div>

          <div className="grid grid-cols-2 gap-3">
            <div>
              <label className="block font-semibold text-theme-primary mb-1">
                {language === 'ar' ? 'لون الدهان' : 'Paint Color'}
              </label>
              <select
                value={newBatch.paintColor}
                onChange={(e) => setNewBatch({ ...newBatch, paintColor: e.target.value })}
                className="w-full px-3 py-2 rounded-lg bg-theme-surface border border-theme text-theme-primary focus:outline-none focus:border-pmp-primary"
              >
                <option value="أبيض">أبيض (PMP White)</option>
                <option value="فضي">فضي حراري</option>
                <option value="ذهبي">ذهبي ميتاليك</option>
                <option value="ورنيش">ورنيش حماية</option>
              </select>
            </div>

            <div>
              <label className="block font-semibold text-theme-primary mb-1">
                {language === 'ar' ? 'معيار حجم العلبة' : 'Can Weight'}
              </label>
              <select
                value={newBatch.packageWeightGrams}
                onChange={(e) => setNewBatch({ ...newBatch, packageWeightGrams: Number(e.target.value) as any })}
                className="w-full px-3 py-2 rounded-lg bg-theme-surface border border-theme text-theme-primary focus:outline-none focus:border-pmp-primary"
              >
                <option value={250}>250 جرام (48 علبة/كرتونة)</option>
                <option value={900}>900 جرام (12 علبة/كرتونة)</option>
                <option value={4000}>4000 جرام جالون (4 علب/كرتونة)</option>
              </select>
            </div>
          </div>

          <div className="grid grid-cols-2 gap-3">
            <div>
              <label className="block font-semibold text-theme-primary mb-1">
                {language === 'ar' ? 'الوزن المستهدف (كجم)' : 'Target Weight (Kg)'}
              </label>
              <input
                type="number"
                required
                value={newBatch.targetWeightKg}
                onChange={(e) => setNewBatch({ ...newBatch, targetWeightKg: Number(e.target.value) })}
                className="w-full px-3 py-2 rounded-lg bg-theme-surface border border-theme text-theme-primary focus:outline-none focus:border-pmp-primary"
              />
            </div>

            <div>
              <label className="block font-semibold text-theme-primary mb-1">
                {language === 'ar' ? 'الكراتين المستهدفة' : 'Target Cartons'}
              </label>
              <input
                type="number"
                required
                value={newBatch.targetCartons}
                onChange={(e) => setNewBatch({ ...newBatch, targetCartons: Number(e.target.value) })}
                className="w-full px-3 py-2 rounded-lg bg-theme-surface border border-theme text-theme-primary focus:outline-none focus:border-pmp-primary"
              />
            </div>
          </div>

          <div className="flex justify-end gap-3 pt-3 border-t border-theme">
            <GlassButton variant="secondary" onClick={() => setIsNewBatchOpen(false)}>
              {language === 'ar' ? 'إلغاء' : 'Cancel'}
            </GlassButton>
            <GlassButton type="submit" variant="primary">
              {language === 'ar' ? 'بدء خط الخلط' : 'Start Batch'}
            </GlassButton>
          </div>
        </form>
      </Modal>
    </div>
  );
};
