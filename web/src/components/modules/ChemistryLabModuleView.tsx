import React, { useState } from 'react';
import {
  FlaskConical,
  Plus,
  Search,
  Filter,
  CheckCircle2,
  AlertTriangle,
  Beaker,
  FileSpreadsheet,
  Printer,
  Sparkles
} from 'lucide-react';
import { GlassCard } from '../ui/GlassCard';
import { GlassBadge } from '../ui/GlassBadge';
import { GlassButton } from '../ui/GlassButton';
import { Modal } from '../ui/Modal';
import { LabFormulation } from '../../types/erp';
import { ErpDataService } from '../../services/ErpDataService';
import { ReportExporter } from '../../services/ReportExporter';
import { useLanguage } from '../../context/LanguageContext';
import { useAuth } from '../../context/AuthContext';

export const ChemistryLabModuleView: React.FC = () => {
  const { language } = useLanguage();
  const { canPerformAction } = useAuth();

  const [formulations, setFormulations] = useState<LabFormulation[]>(() => ErpDataService.getInitialLabFormulations());
  const [searchTerm, setSearchTerm] = useState('');
  const [isNewFormulaOpen, setIsNewFormulaOpen] = useState(false);

  // New Formula State
  const [newFormula, setNewFormula] = useState({
    formulaName: '',
    paintType: 'مطاطي',
    colorHex: '#FFFFFF',
    viscosityTarget: '110-115 KU',
    densityTarget: '1.42 g/cm³',
    rawMaterialCount: 6
  });

  const filtered = formulations.filter(f =>
    f.formulaName.toLowerCase().includes(searchTerm.toLowerCase()) ||
    f.formulaNo.toLowerCase().includes(searchTerm.toLowerCase()) ||
    f.paintType.toLowerCase().includes(searchTerm.toLowerCase())
  );

  const handleCreateFormula = async (e: React.FormEvent) => {
    e.preventDefault();
    const formulaNo = `FORM-${newFormula.paintType.slice(0, 3).toUpperCase()}-${(formulations.length + 5).toString().padStart(2, '0')}`;
    const created: LabFormulation = {
      id: Date.now(),
      formulaNo,
      formulaName: newFormula.formulaName,
      paintType: newFormula.paintType,
      colorHex: newFormula.colorHex,
      viscosityTarget: newFormula.viscosityTarget,
      densityTarget: newFormula.densityTarget,
      rawMaterialCount: Number(newFormula.rawMaterialCount),
      isActive: true
    };

    setFormulations(prev => [created, ...prev]);
    await ErpDataService.saveRecord('lab_formulations', formulaNo, created);
    setIsNewFormulaOpen(false);
  };

  const handleExportCsv = () => {
    const headers = ['كود التركيبة', 'اسم التركيبة', 'نوع الدهان', 'اللون (Hex)', 'اللزوجة القياسية', 'الكثافة المستهدفة', 'عدد المواد الخام'];
    const rows = filtered.map(f => [
      f.formulaNo, f.formulaName, f.paintType, f.colorHex, f.viscosityTarget, f.densityTarget, f.rawMaterialCount
    ]);
    ReportExporter.exportToCsv('pmp_chemical_formulations', headers, rows);
  };

  const handlePrint = () => {
    const headers = ['الكود', 'التركيبة', 'النوع', 'اللزوجة', 'الكثافة', 'الخامات'];
    const rows = filtered.map(f => [
      f.formulaNo, f.formulaName, f.paintType, f.viscosityTarget, f.densityTarget, f.rawMaterialCount
    ]);
    ReportExporter.exportToPrint('دليل التركيبات والمعايير الكيميائية - مختبر PMP', headers, rows);
  };

  return (
    <div className="space-y-6">
      <div className="flex flex-col sm:flex-row justify-between items-start sm:items-center gap-4 pb-4 border-b border-theme">
        <div>
          <div className="flex items-center gap-2">
            <h2 className="text-xl font-black text-theme-primary">
              {language === 'ar' ? 'المختبر الكيميائي والجودة (Chemistry Lab & QC)' : 'Chemistry Lab & Quality Control'}
            </h2>
            <GlassBadge variant="cyan">
              {formulations.length} {language === 'ar' ? 'تركيبة قياسية' : 'Formulas'}
            </GlassBadge>
          </div>
          <p className="text-xs text-theme-secondary mt-1">
            {language === 'ar'
              ? 'معايير اللزوجة، الكثافة، نسب الخلط، وفحص العينات المخبرية قبل التعبئة النهائية.'
              : 'Formulation recipes, viscosity KU, density standards, and batch QC verification.'}
          </p>
        </div>

        <div className="flex items-center gap-2 flex-wrap">
          <GlassButton variant="secondary" icon={<FileSpreadsheet className="w-4 h-4" />} onClick={handleExportCsv} className="text-xs">
            {language === 'ar' ? 'تصدير CSV' : 'Export CSV'}
          </GlassButton>
          <GlassButton variant="secondary" icon={<Printer className="w-4 h-4" />} onClick={handlePrint} className="text-xs">
            {language === 'ar' ? 'طباعة' : 'Print'}
          </GlassButton>
          {canPerformAction('صلاحية الإنشاء') && (
            <GlassButton variant="primary" icon={<Plus className="w-4 h-4" />} onClick={() => setIsNewFormulaOpen(true)} className="text-xs">
              {language === 'ar' ? 'تركيبة كيميائية جديدة' : 'New Formula'}
            </GlassButton>
          )}
        </div>
      </div>

      {/* Search */}
      <GlassCard className="p-4">
        <div className="relative">
          <Search className="w-4 h-4 text-theme-muted absolute right-3 top-2.5" />
          <input
            type="text"
            placeholder={language === 'ar' ? 'بحث باسم التركيبة، الكود، أو نوع الدهان...' : 'Search formulations...'}
            value={searchTerm}
            onChange={(e) => setSearchTerm(e.target.value)}
            className="w-full pr-9 pl-3 py-2 rounded-lg bg-theme-surface border border-theme text-theme-primary text-xs focus:outline-none focus:border-pmp-primary"
          />
        </div>
      </GlassCard>

      {/* Formulations Grid */}
      <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
        {filtered.map(f => (
          <GlassCard key={f.id} className="p-5 relative">
            <div className="flex justify-between items-start mb-3">
              <div className="flex items-center gap-2.5">
                <div
                  className="w-5 h-5 rounded-full border border-theme shadow-sm shrink-0"
                  style={{ backgroundColor: f.colorHex }}
                />
                <div>
                  <h4 className="font-bold text-theme-primary text-sm">{f.formulaName}</h4>
                  <span className="font-mono text-[11px] text-pmp-primary font-bold">{f.formulaNo}</span>
                </div>
              </div>
              <GlassBadge variant="cyan">{f.paintType}</GlassBadge>
            </div>

            <div className="grid grid-cols-3 gap-2 my-3 p-3 rounded-xl bg-theme-surface border border-theme text-xs">
              <div>
                <span className="text-theme-muted block text-[10px]">{language === 'ar' ? 'اللزوجة القياسية' : 'Viscosity'}</span>
                <span className="font-bold text-theme-primary">{f.viscosityTarget}</span>
              </div>
              <div>
                <span className="text-theme-muted block text-[10px]">{language === 'ar' ? 'الكثافة المستهدفة' : 'Density'}</span>
                <span className="font-bold text-theme-primary">{f.densityTarget}</span>
              </div>
              <div>
                <span className="text-theme-muted block text-[10px]">{language === 'ar' ? 'المواد الخام' : 'Ingredients'}</span>
                <span className="font-bold text-pmp-accent">{f.rawMaterialCount} خامات</span>
              </div>
            </div>

            <div className="flex items-center justify-between text-[11px] text-theme-muted pt-2 border-t border-theme">
              <span className="flex items-center gap-1 text-green-500 font-bold">
                <CheckCircle2 className="w-3.5 h-3.5" />
                {language === 'ar' ? 'معتمدة للإنتاج الصناعي' : 'Approved Formula'}
              </span>
              <span>مختبر PMP المركزي</span>
            </div>
          </GlassCard>
        ))}
      </div>

      {/* New Formula Modal */}
      <Modal
        isOpen={isNewFormulaOpen}
        onClose={() => setIsNewFormulaOpen(false)}
        title={language === 'ar' ? 'تسجيل تركيبة كيميائية ومعايير جودة جديدة' : 'Add Chemical Formulation'}
        maxWidth="max-w-lg"
      >
        <form onSubmit={handleCreateFormula} className="space-y-4 text-xs">
          <div>
            <label className="block font-semibold text-theme-primary mb-1">
              {language === 'ar' ? 'اسم التركيبة أو الصنف *' : 'Formula Name *'}
            </label>
            <input
              type="text"
              required
              value={newFormula.formulaName}
              onChange={(e) => setNewFormula({ ...newFormula, formulaName: e.target.value })}
              placeholder={language === 'ar' ? 'مثال: دهان إيبوكسي أرضيات عالي المقاومة' : 'Formula name...'}
              className="w-full px-3 py-2 rounded-lg bg-theme-surface border border-theme text-theme-primary focus:outline-none focus:border-pmp-primary"
            />
          </div>

          <div className="grid grid-cols-2 gap-3">
            <div>
              <label className="block font-semibold text-theme-primary mb-1">
                {language === 'ar' ? 'نوع الطلاء' : 'Paint Type'}
              </label>
              <select
                value={newFormula.paintType}
                onChange={(e) => setNewFormula({ ...newFormula, paintType: e.target.value })}
                className="w-full px-3 py-2 rounded-lg bg-theme-surface border border-theme text-theme-primary focus:outline-none focus:border-pmp-primary"
              >
                <option value="مطاطي">مطاطي (Rubber)</option>
                <option value="حراري">حراري (Thermal)</option>
                <option value="ديكوري">ديكوري وميتاليك</option>
                <option value="ورنيش">ورنيش وعوازل</option>
              </select>
            </div>

            <div>
              <label className="block font-semibold text-theme-primary mb-1">
                {language === 'ar' ? 'اللون (كود Hex)' : 'Color Hex'}
              </label>
              <input
                type="text"
                value={newFormula.colorHex}
                onChange={(e) => setNewFormula({ ...newFormula, colorHex: e.target.value })}
                className="w-full px-3 py-2 rounded-lg bg-theme-surface border border-theme text-theme-primary focus:outline-none focus:border-pmp-primary font-mono"
              />
            </div>
          </div>

          <div className="grid grid-cols-2 gap-3">
            <div>
              <label className="block font-semibold text-theme-primary mb-1">
                {language === 'ar' ? 'اللزوجة القياسية' : 'Target Viscosity'}
              </label>
              <input
                type="text"
                value={newFormula.viscosityTarget}
                onChange={(e) => setNewFormula({ ...newFormula, viscosityTarget: e.target.value })}
                placeholder="110-115 KU"
                className="w-full px-3 py-2 rounded-lg bg-theme-surface border border-theme text-theme-primary focus:outline-none focus:border-pmp-primary"
              />
            </div>

            <div>
              <label className="block font-semibold text-theme-primary mb-1">
                {language === 'ar' ? 'الكثافة المستهدفة' : 'Target Density'}
              </label>
              <input
                type="text"
                value={newFormula.densityTarget}
                onChange={(e) => setNewFormula({ ...newFormula, densityTarget: e.target.value })}
                placeholder="1.42 g/cm³"
                className="w-full px-3 py-2 rounded-lg bg-theme-surface border border-theme text-theme-primary focus:outline-none focus:border-pmp-primary"
              />
            </div>
          </div>

          <div className="flex justify-end gap-3 pt-3 border-t border-theme">
            <GlassButton variant="secondary" onClick={() => setIsNewFormulaOpen(false)}>
              {language === 'ar' ? 'إلغاء' : 'Cancel'}
            </GlassButton>
            <GlassButton type="submit" variant="primary">
              {language === 'ar' ? 'اعتماد التركيبة' : 'Save Formula'}
            </GlassButton>
          </div>
        </form>
      </Modal>
    </div>
  );
};
