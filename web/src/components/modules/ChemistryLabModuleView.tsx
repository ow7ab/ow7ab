import React, { useEffect, useMemo, useState } from 'react';
import {
  FlaskConical,
  Plus,
  Search,
  CheckCircle2,
  AlertTriangle,
  FileSpreadsheet,
  Printer,
  ShieldAlert,
  Trash2,
  CheckCircle
} from 'lucide-react';
import { GlassCard } from '../ui/GlassCard';
import { GlassBadge } from '../ui/GlassBadge';
import { GlassButton } from '../ui/GlassButton';
import { Modal } from '../ui/Modal';
import { ChemicalSafety, FormulationIngredient, LabFormulation } from '../../types/erp';
import { ErpDataService, safeId } from '../../services/ErpDataService';
import {
  PRESET_MATERIALS,
  calculatePvc,
  ingredientsToJson,
  jsonToIngredients
} from '../../services/PvcEngine';
import { ReportExporter } from '../../services/ReportExporter';
import { GRANULAR_ACTIONS } from '../../services/SecurityService';
import { useLanguage } from '../../context/LanguageContext';
import { useAuth } from '../../context/AuthContext';

type LabSubTab = 'FORMULATIONS' | 'MATERIALS_GUIDE' | 'SAFETY';

export const ChemistryLabModuleView: React.FC = () => {
  const { language } = useLanguage();
  const { canPerformAction, userProfile } = useAuth();

  const [subTab, setSubTab] = useState<LabSubTab>('FORMULATIONS');
  const [formulations, setFormulations] = useState<LabFormulation[]>([]);
  const [safetySheets, setSafetySheets] = useState<ChemicalSafety[]>([]);
  const [searchTerm, setSearchTerm] = useState('');
  const [isNewFormulaOpen, setIsNewFormulaOpen] = useState(false);
  const [notice, setNotice] = useState<{ type: 'ok' | 'err'; text: string } | null>(null);

  // Formula Builder State using exact PvcEngine.kt formulas
  const [formulaMeta, setFormulaMeta] = useState({
    productName: '',
    paintType: 'مطاطي داخلي/خارجي',
    baseKind: 'أبيض',
    notes: '',
    approvedForProduction: true
  });
  const [ingredients, setIngredients] = useState<FormulationIngredient[]>(() => [
    {
      id: 'ing-1',
      name: PRESET_MATERIALS[0].name,
      type: PRESET_MATERIALS[0].type,
      weight: 180,
      density: PRESET_MATERIALS[0].defaultDensity,
      solidsPercent: PRESET_MATERIALS[0].defaultSolidsPercent,
      unitCost: PRESET_MATERIALS[0].defaultUnitCost,
      notes: PRESET_MATERIALS[0].notes
    },
    {
      id: 'ing-2',
      name: PRESET_MATERIALS[8].name,
      type: PRESET_MATERIALS[8].type,
      weight: 340,
      density: PRESET_MATERIALS[8].defaultDensity,
      solidsPercent: PRESET_MATERIALS[8].defaultSolidsPercent,
      unitCost: PRESET_MATERIALS[8].defaultUnitCost,
      notes: PRESET_MATERIALS[8].notes
    },
    {
      id: 'ing-3',
      name: PRESET_MATERIALS[11].name,
      type: PRESET_MATERIALS[11].type,
      weight: 280,
      density: PRESET_MATERIALS[11].defaultDensity,
      solidsPercent: PRESET_MATERIALS[11].defaultSolidsPercent,
      unitCost: PRESET_MATERIALS[11].defaultUnitCost,
      notes: PRESET_MATERIALS[11].notes
    }
  ]);

  const [safetyForm, setSafetyForm] = useState({
    chemicalName: '',
    casNumber: '',
    signalWord: 'تحذير' as 'خطر' | 'تحذير',
    hazardStatements: '',
    storageConditions: '',
    firstAidMeasures: ''
  });

  useEffect(() => {
    const unsubs = [
      ErpDataService.subscribeToCollection<LabFormulation>('lab_formulations', setFormulations),
      ErpDataService.subscribeToCollection<ChemicalSafety>('chemical_safety', setSafetySheets)
    ];
    return () => unsubs.forEach((u) => u());
  }, []);

  const pvcResult = useMemo(() => calculatePvc(ingredients), [ingredients]);
  const canCreate = canPerformAction(GRANULAR_ACTIONS.CREATE);
  const canDelete = canPerformAction(GRANULAR_ACTIONS.DELETE);

  const showNotice = (type: 'ok' | 'err', text: string) => {
    setNotice({ type, text });
    setTimeout(() => setNotice(null), 4000);
  };

  const handleAddPresetIngredient = (idx: number) => {
    const preset = PRESET_MATERIALS[idx];
    if (!preset) return;
    setIngredients((prev) => [
      ...prev,
      {
        id: `ing-${Date.now()}-${Math.random().toString(16).slice(2, 6)}`,
        name: preset.name,
        type: preset.type,
        weight: 50,
        density: preset.defaultDensity,
        solidsPercent: preset.defaultSolidsPercent,
        unitCost: preset.defaultUnitCost,
        notes: preset.notes
      }
    ]);
  };

  const handleCreateFormula = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!canCreate || !formulaMeta.productName.trim()) return;

    const now = Date.now();
    const formulaNo = `FORM-${String(now).slice(-5)}`;
    const record: LabFormulation = {
      id: now,
      formulaNo,
      productName: formulaMeta.productName.trim(),
      paintType: formulaMeta.paintType,
      baseKind: formulaMeta.baseKind,
      targetPvc: pvcResult.calculatedPvc,
      calculatedPvc: pvcResult.calculatedPvc,
      cpvcEstimated: pvcResult.cpvcEstimated,
      totalSolidsWeight: pvcResult.totalSolidsWeight,
      totalSolidsVolume: pvcResult.totalSolidsVolume,
      totalBatchWeight: pvcResult.totalBatchWeight,
      totalBatchVolume: pvcResult.totalBatchVolume,
      calculatedDensity: pvcResult.calculatedDensity,
      totalCost: pvcResult.totalCost,
      costPerKg: pvcResult.costPerKg,
      ingredientsJson: ingredientsToJson(ingredients),
      technicalAnalysis: pvcResult.technicalAnalysisText,
      notes: formulaMeta.notes.trim() || pvcResult.paintClassification,
      approvedForProduction: formulaMeta.approvedForProduction,
      createdBy: userProfile?.fullName || userProfile?.username || 'WEB_LAB',
      createdAt: now
    };

    try {
      await ErpDataService.saveRecord(
        'lab_formulations',
        safeId(formulaNo, `FORM-${now}`),
        record,
        userProfile?.username
      );
      setIsNewFormulaOpen(false);
      setFormulaMeta((m) => ({ ...m, productName: '', notes: '' }));
      showNotice('ok', `تم حفظ التركيبة الكيميائية ${formulaNo} مع حسابات PVC الدقيقة`);
    } catch (err: any) {
      showNotice('err', err?.message || 'تعذر حفظ التركيبة');
    }
  };

  const handleDeleteFormula = async (f: LabFormulation) => {
    if (!canDelete) return;
    const docId = (f as any)._docId || safeId(f.formulaNo, `FORM-${f.id}`);
    await ErpDataService.deleteRecord('lab_formulations', docId, f.id, userProfile?.username);
    showNotice('ok', `تم حذف التركيبة ${f.formulaNo}`);
  };

  const handleSaveSafety = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!canCreate || !safetyForm.chemicalName.trim()) return;
    const id = Date.now();
    const record: ChemicalSafety = {
      id,
      chemicalName: safetyForm.chemicalName.trim(),
      commonName: safetyForm.chemicalName.trim(),
      casNumber: safetyForm.casNumber.trim(),
      molecularFormula: '',
      molecularWeight: 0,
      ghsPictograms: '',
      signalWord: safetyForm.signalWord,
      hazardStatements: safetyForm.hazardStatements.trim(),
      precautionaryStatements: '',
      storageConditions: safetyForm.storageConditions.trim(),
      firstAidMeasures: safetyForm.firstAidMeasures.trim(),
      sdsSupplierName: '',
      sdsFileUrlOrUri: '',
      notes: '',
      createdAt: id
    };
    await ErpDataService.saveRecord('chemical_safety', `SAFE-${id}`, record, userProfile?.username);
    setSafetyForm({
      chemicalName: '',
      casNumber: '',
      signalWord: 'تحذير',
      hazardStatements: '',
      storageConditions: '',
      firstAidMeasures: ''
    });
    showNotice('ok', 'تم حفظ بطاقة السلامة الكيميائية (SDS)');
  };

  const filtered = useMemo(() => {
    return [...formulations]
      .sort((a, b) => (b.createdAt || 0) - (a.createdAt || 0))
      .filter(
        (f) =>
          !searchTerm ||
          (f.productName || '').toLowerCase().includes(searchTerm.toLowerCase()) ||
          (f.formulaNo || '').toLowerCase().includes(searchTerm.toLowerCase()) ||
          (f.paintType || '').toLowerCase().includes(searchTerm.toLowerCase())
      );
  }, [formulations, searchTerm]);

  const handleExportCsv = () => {
    const headers = ['كود التركيبة', 'المنتج', 'النوع', 'PVC %', 'CPVC', 'الكثافة', 'الجوامد %', 'التكلفة/كجم'];
    const rows = filtered.map((f) => [
      f.formulaNo,
      f.productName,
      f.paintType,
      f.calculatedPvc,
      f.cpvcEstimated,
      f.calculatedDensity,
      f.totalSolidsWeight,
      f.costPerKg
    ]);
    ReportExporter.exportToCsv('pmp_chemical_formulations', headers, rows);
  };

  const handlePrint = () => {
    const headers = ['الكود', 'التركيبة', 'PVC %', 'الكثافة', 'التكلفة/كجم', 'الحالة'];
    const rows = filtered.map((f) => [
      f.formulaNo,
      f.productName,
      `${f.calculatedPvc}%`,
      f.calculatedDensity,
      f.costPerKg,
      f.approvedForProduction ? 'معتمدة' : 'مسودة'
    ]);
    ReportExporter.exportToPrint('التركيبات الكيميائية ومعادلات PVC - مختبر PMP', headers, rows);
  };

  return (
    <div className="space-y-6">
      <GlassCard className="p-5 sm:p-6">
        <div className="flex flex-col sm:flex-row justify-between items-start sm:items-center gap-4 pb-4 border-b border-theme">
          <div>
            <div className="flex items-center gap-2">
              <h2 className="text-xl font-black text-theme-primary">
                {language === 'ar' ? 'المختبر الكيميائي وهندسة الطلاء (PVC Engine)' : 'Chemistry Lab & PVC Engine'}
              </h2>
              <GlassBadge variant="cyan">{formulations.length} تركيبة</GlassBadge>
            </div>
            <p className="text-xs text-theme-secondary mt-1">
              نفس محرك الحسابات الفيزيائية والكيميائية من الأندرويد: PVC/CPVC، الكثافة، الجوامد، وتكلفة الكيلوجرام.
            </p>
          </div>
          <div className="flex items-center gap-2 flex-wrap">
            <GlassButton variant="secondary" icon={<FileSpreadsheet className="w-4 h-4" />} onClick={handleExportCsv} className="text-xs">
              CSV
            </GlassButton>
            <GlassButton variant="secondary" icon={<Printer className="w-4 h-4" />} onClick={handlePrint} className="text-xs">
              طباعة
            </GlassButton>
            {canCreate && (
              <GlassButton variant="primary" icon={<Plus className="w-4 h-4" />} onClick={() => setIsNewFormulaOpen(true)} className="text-xs">
                + حاسبة وتركيبة جديدة
              </GlassButton>
            )}
          </div>
        </div>

        <div className="flex items-center gap-2 mt-4 overflow-x-auto pb-1">
          {[
            { id: 'FORMULATIONS' as LabSubTab, label: `التركيبات والنسب (${formulations.length})` },
            { id: 'MATERIALS_GUIDE' as LabSubTab, label: `دليل المواد الخام الكيميائية (${PRESET_MATERIALS.length})` },
            { id: 'SAFETY' as LabSubTab, label: `معايير السلامة SDS (${safetySheets.length})` }
          ].map((t) => (
            <button
              key={t.id}
              type="button"
              onClick={() => setSubTab(t.id)}
              className={`px-4 py-2 rounded-xl text-xs font-bold whitespace-nowrap transition-all ${
                subTab === t.id
                  ? 'bg-pmp-primary text-white shadow-md'
                  : 'bg-theme-base border border-theme text-theme-secondary hover:text-theme-primary'
              }`}
            >
              {t.label}
            </button>
          ))}
        </div>
      </GlassCard>

      {notice && (
        <div className="p-3.5 rounded-xl border bg-emerald-500/10 border-emerald-500/30 text-emerald-400 text-xs flex items-center gap-2">
          <CheckCircle className="w-4 h-4 shrink-0" />
          <span>{notice.text}</span>
        </div>
      )}

      {subTab === 'FORMULATIONS' && (
        <div className="space-y-4">
          <GlassCard className="p-4">
            <div className="relative">
              <Search className="w-4 h-4 text-theme-muted absolute right-3 top-2.5" />
              <input
                type="text"
                placeholder="بحث باسم التركيبة، الكود، أو نوع الطلاء..."
                value={searchTerm}
                onChange={(e) => setSearchTerm(e.target.value)}
                className="w-full pr-9 pl-3 py-2 rounded-lg bg-theme-base border border-theme text-theme-primary text-xs"
              />
            </div>
          </GlassCard>

          <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
            {filtered.map((f) => {
              const ingList = jsonToIngredients(f.ingredientsJson);
              return (
                <GlassCard key={f.id} className="p-5 space-y-3">
                  <div className="flex justify-between items-start gap-2">
                    <div>
                      <h4 className="font-bold text-theme-primary text-sm">{f.productName}</h4>
                      <span className="font-mono text-[11px] text-pmp-primary font-bold">
                        {f.formulaNo} • {f.baseKind}
                      </span>
                    </div>
                    <div className="flex items-center gap-1.5">
                      <GlassBadge variant="cyan">{f.paintType}</GlassBadge>
                      {canDelete && (
                        <button
                          type="button"
                          onClick={() => handleDeleteFormula(f)}
                          className="p-1 text-red-400 hover:bg-red-500/10 rounded"
                        >
                          <Trash2 className="w-3.5 h-3.5" />
                        </button>
                      )}
                    </div>
                  </div>

                  <div className="grid grid-cols-4 gap-2 p-3 rounded-xl bg-theme-base border border-theme text-xs">
                    <div>
                      <span className="text-[10px] text-theme-muted block">PVC %</span>
                      <span className="font-mono font-bold text-pmp-primary">{f.calculatedPvc}%</span>
                    </div>
                    <div>
                      <span className="text-[10px] text-theme-muted block">الكثافة</span>
                      <span className="font-mono font-bold">{f.calculatedDensity}</span>
                    </div>
                    <div>
                      <span className="text-[10px] text-theme-muted block">الجوامد wt%</span>
                      <span className="font-mono font-bold">{f.totalSolidsWeight}%</span>
                    </div>
                    <div>
                      <span className="text-[10px] text-theme-muted block">التكلفة/كجم</span>
                      <span className="font-mono font-bold text-emerald-400">{f.costPerKg}</span>
                    </div>
                  </div>

                  {f.technicalAnalysis && (
                    <p className="text-[11px] text-theme-secondary leading-relaxed">{f.technicalAnalysis}</p>
                  )}

                  <div className="flex items-center justify-between text-[11px] text-theme-muted pt-2 border-t border-theme">
                    <span className="flex items-center gap-1 text-emerald-400 font-bold">
                      <CheckCircle2 className="w-3.5 h-3.5" />
                      {f.approvedForProduction ? 'معتمدة للإنتاج الصناعي' : 'قيد الدراسة المخبرية'}
                    </span>
                    <span>{ingList.length} مكونات</span>
                  </div>
                </GlassCard>
              );
            })}
          </div>
        </div>
      )}

      {subTab === 'MATERIALS_GUIDE' && (
        <GlassCard className="p-0 overflow-hidden">
          <div className="overflow-x-auto">
            <table className="w-full text-right text-xs min-w-[540px]">
              <thead>
                <tr className="bg-theme-surface/80 border-b border-theme text-theme-muted">
                  <th className="py-3 px-4">المادة القياسية</th>
                  <th className="py-3 px-4">التصنيف الكيميائي</th>
                  <th className="py-3 px-4">الكثافة g/cm³</th>
                  <th className="py-3 px-4">نسبة الجوامد %</th>
                  <th className="py-3 px-4">ملاحظات تقنية</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-theme">
                {PRESET_MATERIALS.map((m, idx) => (
                  <tr key={idx}>
                    <td className="py-3 px-4 font-bold">{m.name}</td>
                    <td className="py-3 px-4"><GlassBadge variant="cyan">{m.type}</GlassBadge></td>
                    <td className="py-3 px-4 font-mono">{m.defaultDensity}</td>
                    <td className="py-3 px-4 font-mono">{m.defaultSolidsPercent}%</td>
                    <td className="py-3 px-4 text-theme-muted">{m.notes}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </GlassCard>
      )}

      {subTab === 'SAFETY' && (
        <div className="grid grid-cols-1 lg:grid-cols-12 gap-6">
          <GlassCard className="p-6 lg:col-span-5">
            <h3 className="text-sm font-bold text-theme-primary mb-4 flex items-center gap-2">
              <ShieldAlert className="w-4 h-4 text-pmp-warning" />
              تسجيل بطاقة سلامة كيميائية (SDS)
            </h3>
            <form onSubmit={handleSaveSafety} className="space-y-3 text-xs">
              <input
                type="text"
                required
                placeholder="اسم المادة الكيميائية *"
                value={safetyForm.chemicalName}
                onChange={(e) => setSafetyForm({ ...safetyForm, chemicalName: e.target.value })}
                className="w-full px-3 py-2 rounded-xl bg-theme-base border border-theme text-theme-primary"
              />
              <div className="grid grid-cols-2 gap-2">
                <input
                  type="text"
                  placeholder="CAS Number"
                  value={safetyForm.casNumber}
                  onChange={(e) => setSafetyForm({ ...safetyForm, casNumber: e.target.value })}
                  className="px-3 py-2 rounded-xl bg-theme-base border border-theme text-theme-primary font-mono"
                />
                <select
                  value={safetyForm.signalWord}
                  onChange={(e) => setSafetyForm({ ...safetyForm, signalWord: e.target.value as any })}
                  className="px-3 py-2 rounded-xl bg-theme-base border border-theme text-theme-primary"
                >
                  <option value="تحذير">تحذير</option>
                  <option value="خطر">خطر</option>
                </select>
              </div>
              <input
                type="text"
                placeholder="المخاطر (Hazard Statements)"
                value={safetyForm.hazardStatements}
                onChange={(e) => setSafetyForm({ ...safetyForm, hazardStatements: e.target.value })}
                className="w-full px-3 py-2 rounded-xl bg-theme-base border border-theme text-theme-primary"
              />
              <input
                type="text"
                placeholder="شروط التخزين"
                value={safetyForm.storageConditions}
                onChange={(e) => setSafetyForm({ ...safetyForm, storageConditions: e.target.value })}
                className="w-full px-3 py-2 rounded-xl bg-theme-base border border-theme text-theme-primary"
              />
              <input
                type="text"
                placeholder="الإسعافات الأولية"
                value={safetyForm.firstAidMeasures}
                onChange={(e) => setSafetyForm({ ...safetyForm, firstAidMeasures: e.target.value })}
                className="w-full px-3 py-2 rounded-xl bg-theme-base border border-theme text-theme-primary"
              />
              <GlassButton type="submit" variant="primary" className="w-full justify-center" disabled={!canCreate}>
                حفظ بطاقة السلامة
              </GlassButton>
            </form>
          </GlassCard>

          <GlassCard className="p-6 lg:col-span-7">
            <h4 className="text-sm font-bold text-theme-primary mb-3">بطاقات السلامة المسجلة ({safetySheets.length})</h4>
            <div className="space-y-2.5 text-xs">
              {safetySheets.map((s) => (
                <div key={s.id} className="p-3 rounded-xl bg-theme-base border border-theme space-y-1">
                  <div className="flex justify-between items-center">
                    <span className="font-bold">{s.chemicalName} {s.casNumber ? `(${s.casNumber})` : ''}</span>
                    <GlassBadge variant={s.signalWord === 'خطر' ? 'red' : 'gold'}>{s.signalWord}</GlassBadge>
                  </div>
                  <p className="text-theme-secondary text-[11px]">{s.hazardStatements || '-'}</p>
                  <p className="text-theme-muted text-[10px]">التخزين: {s.storageConditions || '-'} • الإسعاف: {s.firstAidMeasures || '-'}</p>
                </div>
              ))}
            </div>
          </GlassCard>
        </div>
      )}

      {/* Formula + Live PVC Engine Builder Modal */}
      <Modal
        isOpen={isNewFormulaOpen}
        onClose={() => setIsNewFormulaOpen(false)}
        title="حاسبة التركيبات ومعادلة PVC (PvcEngine)"
        maxWidth="max-w-3xl"
      >
        <form onSubmit={handleCreateFormula} className="space-y-4 text-xs">
          <div className="grid grid-cols-1 sm:grid-cols-3 gap-3">
            <input
              type="text"
              required
              placeholder="اسم المنتج / التركيبة *"
              value={formulaMeta.productName}
              onChange={(e) => setFormulaMeta({ ...formulaMeta, productName: e.target.value })}
              className="px-3 py-2 rounded-lg bg-theme-surface border border-theme text-theme-primary"
            />
            <input
              type="text"
              placeholder="نوع الطلاء"
              value={formulaMeta.paintType}
              onChange={(e) => setFormulaMeta({ ...formulaMeta, paintType: e.target.value })}
              className="px-3 py-2 rounded-lg bg-theme-surface border border-theme text-theme-primary"
            />
            <input
              type="text"
              placeholder="اللون الأساسي"
              value={formulaMeta.baseKind}
              onChange={(e) => setFormulaMeta({ ...formulaMeta, baseKind: e.target.value })}
              className="px-3 py-2 rounded-lg bg-theme-surface border border-theme text-theme-primary"
            />
          </div>

          <div className="flex flex-wrap gap-1.5">
            {PRESET_MATERIALS.slice(0, 12).map((pm, idx) => (
              <button
                key={idx}
                type="button"
                onClick={() => handleAddPresetIngredient(idx)}
                className="px-2 py-1 rounded-lg bg-theme-base border border-theme hover:border-pmp-primary text-[10px] font-bold"
              >
                + {pm.name.split('(')[0].trim()}
              </button>
            ))}
          </div>

          <div className="max-h-56 overflow-y-auto space-y-2">
            {ingredients.map((ing, i) => (
              <div key={ing.id} className="grid grid-cols-12 gap-2 items-center bg-theme-base p-2 rounded-lg border border-theme">
                <span className="col-span-4 font-bold truncate" title={ing.name}>{ing.name}</span>
                <div className="col-span-2">
                  <input
                    type="number"
                    step="any"
                    value={ing.weight}
                    onChange={(e) => {
                      const val = Number(e.target.value);
                      setIngredients((prev) => prev.map((x, idx) => (idx === i ? { ...x, weight: val } : x)));
                    }}
                    title="الوزن كجم"
                    className="w-full px-2 py-1 rounded bg-theme-surface border border-theme font-mono"
                  />
                </div>
                <div className="col-span-2">
                  <input
                    type="number"
                    step="any"
                    value={ing.density}
                    onChange={(e) => {
                      const val = Number(e.target.value);
                      setIngredients((prev) => prev.map((x, idx) => (idx === i ? { ...x, density: val } : x)));
                    }}
                    title="الكثافة"
                    className="w-full px-2 py-1 rounded bg-theme-surface border border-theme font-mono"
                  />
                </div>
                <div className="col-span-2">
                  <input
                    type="number"
                    step="any"
                    value={ing.solidsPercent}
                    onChange={(e) => {
                      const val = Number(e.target.value);
                      setIngredients((prev) => prev.map((x, idx) => (idx === i ? { ...x, solidsPercent: val } : x)));
                    }}
                    title="الجوامد %"
                    className="w-full px-2 py-1 rounded bg-theme-surface border border-theme font-mono"
                  />
                </div>
                <button
                  type="button"
                  onClick={() => setIngredients((prev) => prev.filter((_, idx) => idx !== i))}
                  className="col-span-2 text-red-400 text-center"
                >
                  حذف
                </button>
              </div>
            ))}
          </div>

          {/* Live PVC Result Box */}
          <div className="p-3.5 rounded-xl bg-pmp-primary/10 border border-pmp-primary/30 space-y-2">
            <div className="flex flex-wrap justify-between gap-2 font-bold">
              <span>التصنيف: {pvcResult.paintClassification}</span>
              <span className="font-mono text-pmp-primary">
                PVC: {pvcResult.calculatedPvc}% | الكثافة: {pvcResult.calculatedDensity} | الجوامد: {pvcResult.totalSolidsWeight}% | التكلفة/كجم: {pvcResult.costPerKg}
              </span>
            </div>
            <p className="text-[11px] text-theme-secondary">{pvcResult.technicalAnalysisText}</p>
          </div>

          <div className="flex justify-end gap-2">
            <GlassButton variant="secondary" onClick={() => setIsNewFormulaOpen(false)}>
              إلغاء
            </GlassButton>
            <GlassButton type="submit" variant="primary">
              حفظ واعتماد التركيبة الكيميائية
            </GlassButton>
          </div>
        </form>
      </Modal>
    </div>
  );
};
