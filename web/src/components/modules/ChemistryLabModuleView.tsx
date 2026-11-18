import React, { useEffect, useMemo, useState } from 'react';
import {
  CheckCircle,
  CheckCircle2,
  Coins,
  Database,
  Edit3,
  FileSpreadsheet,
  FlaskConical,
  GitBranch,
  Lock,
  Plus,
  Printer,
  Scale,
  Search,
  ShieldAlert,
  ShieldCheck,
  TestTube2,
  Trash2,
  Unlock,
  Upload,
  XCircle
} from 'lucide-react';
import { GlassCard } from '../ui/GlassCard';
import { GlassBadge } from '../ui/GlassBadge';
import { GlassButton } from '../ui/GlassButton';
import { Modal } from '../ui/Modal';
import {
  ChemicalSafety,
  FactoryItem,
  FormulationIngredient,
  FormulationVersion,
  LabExcelImportBatch,
  LabExperiment,
  LabFormulation,
  RawMaterialPriceHistory,
  ThreeCostComparisonReport
} from '../../types/erp';
import { ErpDataService, safeId } from '../../services/ErpDataService';
import {
  PRESET_MATERIALS,
  calculatePvc,
  compareThreeCosts,
  jsonToIngredients,
  scaleFormulation
} from '../../services/PvcEngine';
import {
  analyzeXlsxWorkbook,
  ExcelWorkbookAnalysisReport
} from '../../services/ExcelFormulaAnalyzer';
import {
  canApproveFormulas,
  canCreateOrEditFormulas,
  canDecideQcGate,
  canExportSecretFormulas,
  canLogLabExperiments,
  canViewFormulaCosts,
  canViewRawMaterialRegistry,
  canViewSecretFormulas,
  redactFormulationForUser,
  redactVersionForUser,
  resolveLabActorRole,
  sealFormulaWithPassphrase,
  unsealFormulaWithPassphrase
} from '../../services/LabSecurityService';
import { ReportExporter } from '../../services/ReportExporter';
import { GRANULAR_ACTIONS } from '../../services/SecurityService';
import { useLanguage } from '../../context/LanguageContext';
import { useAuth } from '../../context/AuthContext';
import { useTransactionGuard } from '../../hooks/useTransactionGuard';
import { OPERATION_LOADING_LABELS } from '../../services/IdempotencyService';

type LabSubTab = 'FORMULATIONS' | 'EXPERIMENTS' | 'MATERIALS_REGISTRY' | 'SAFETY';

function statusBadgeMeta(formula: LabFormulation): {
  label: string;
  variant: 'green' | 'gold' | 'red' | 'cyan';
} {
  const st = String(formula.approvalStatus || '').toUpperCase();
  if (st === 'APPROVED' || (formula.approvedForProduction && !st)) {
    return { label: 'معتمدة للإنتاج', variant: 'green' };
  }
  if (st === 'REJECTED') {
    return { label: 'مرفوضة مخبرياً', variant: 'red' };
  }
  if (st === 'ARCHIVED') {
    return { label: 'مؤرشفة', variant: 'cyan' };
  }
  return { label: 'تحتاج إلى مراجعة واعتماد', variant: 'gold' };
}

export const ChemistryLabModuleView: React.FC = () => {
  const { language } = useLanguage();
  const { canPerformAction, userProfile } = useAuth();

  const [subTab, setSubTab] = useState<LabSubTab>('FORMULATIONS');
  const [formulations, setFormulations] = useState<LabFormulation[]>([]);
  const [versions, setVersions] = useState<FormulationVersion[]>([]);
  const [experiments, setExperiments] = useState<LabExperiment[]>([]);
  const [factoryItems, setFactoryItems] = useState<FactoryItem[]>([]);
  const [priceHistory, setPriceHistory] = useState<RawMaterialPriceHistory[]>([]);
  const [excelBatches, setExcelBatches] = useState<LabExcelImportBatch[]>([]);
  const [safetySheets, setSafetySheets] = useState<ChemicalSafety[]>([]);

  const [searchTerm, setSearchTerm] = useState('');
  const [statusFilter, setStatusFilter] = useState<'ALL' | 'APPROVED' | 'NEEDS_REVIEW' | 'REJECTED'>('ALL');
  const [notice, setNotice] = useState<{ type: 'ok' | 'err'; text: string } | null>(null);

  // RBAC permissions derived from active user
  const actorName = userProfile?.fullName || userProfile?.username || 'WEB_LAB';
  const labRole = useMemo(() => resolveLabActorRole(userProfile), [userProfile]);
  const allowSecretRead = useMemo(() => canViewSecretFormulas(userProfile), [userProfile]);
  const allowFormulaEdit = useMemo(
    () => canCreateOrEditFormulas(userProfile) && canPerformAction(GRANULAR_ACTIONS.CREATE),
    [userProfile, canPerformAction]
  );
  const allowFormulaApprove = useMemo(() => canApproveFormulas(userProfile), [userProfile]);
  const allowLogExperiment = useMemo(() => canLogLabExperiments(userProfile), [userProfile]);
  const allowQcGate = useMemo(() => canDecideQcGate(userProfile), [userProfile]);
  const allowCostView = useMemo(() => canViewFormulaCosts(userProfile), [userProfile]);
  const allowMaterialRegistry = useMemo(() => canViewRawMaterialRegistry(userProfile), [userProfile]);
  const allowExportSecret = useMemo(() => canExportSecretFormulas(userProfile), [userProfile]);
  const canDelete = canPerformAction(GRANULAR_ACTIONS.DELETE) && allowFormulaEdit;

  // Formula Builder / Revision Modal
  const [isFormulaModalOpen, setIsFormulaModalOpen] = useState(false);
  const [editingFormula, setEditingFormula] = useState<LabFormulation | null>(null);
  const [formulaMeta, setFormulaMeta] = useState({
    productName: '',
    paintType: 'مطاطي داخلي/خارجي',
    baseKind: 'أبيض',
    notes: '',
    changeReason: '',
    sealPassphrase: ''
  });
  const [scaleTargetKg, setScaleTargetKg] = useState('');
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

  // Detail Modals: Versioning History, 3-Cost Variance, Approval Decision, Unseal Passphrase, Excel Import
  const [historyFormula, setHistoryFormula] = useState<LabFormulation | null>(null);
  const [costModalFormula, setCostModalFormula] = useState<LabFormulation | null>(null);
  const [approvalModal, setApprovalModal] = useState<{
    formula: LabFormulation;
    decision: 'APPROVED' | 'REJECTED' | 'NEEDS_REVIEW';
  } | null>(null);
  const [approvalReason, setApprovalReason] = useState('');
  const [unsealTarget, setUnsealTarget] = useState<LabFormulation | null>(null);
  const [unsealPassphrase, setUnsealPassphrase] = useState('');
  const [unsealedIngredientsMap, setUnsealedIngredientsMap] = useState<Record<string, FormulationIngredient[]>>({});

  // Excel Analyzer & Preview Importer
  const [isExcelModalOpen, setIsExcelModalOpen] = useState(false);
  const [excelReport, setExcelReport] = useState<ExcelWorkbookAnalysisReport | null>(null);
  const [selectedCandidates, setSelectedCandidates] = useState<Record<number, boolean>>({});
  const [excelBusy, setExcelBusy] = useState(false);

  // Experiments Form State
  const [expForm, setExpForm] = useState({
    formulaId: 0,
    measuredDensity: '1.35',
    measuredViscosity: '98 KU',
    measuredPh: '8.3',
    measuredGrindMicrons: '25',
    hidingPowerPercent: '96',
    notes: ''
  });

  // Raw Material Price Update Inline Modal
  const [priceEditItem, setPriceEditItem] = useState<FactoryItem | null>(null);
  const [newMatPrice, setNewMatPrice] = useState('');
  const [newMatSupplier, setNewMatSupplier] = useState('');

  // Safety Form
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
      ErpDataService.subscribeToCollection<ChemicalSafety>('chemical_safety', setSafetySheets),
      ErpDataService.subscribeToCollection<FactoryItem>('factory_items', setFactoryItems),
      ErpDataService.subscribeToCollection<LabExperiment>('lab_experiments', setExperiments),
      ErpDataService.subscribeToCollection<RawMaterialPriceHistory>('raw_material_price_history', setPriceHistory)
    ];
    if (allowSecretRead) {
      unsubs.push(ErpDataService.subscribeToCollection<FormulationVersion>('lab_formula_versions', setVersions));
      unsubs.push(ErpDataService.subscribeToCollection<LabExcelImportBatch>('lab_excel_imports', setExcelBatches));
    }
    return () => unsubs.forEach((u) => u());
  }, [allowSecretRead]);

  useEffect(() => {
    if (formulations.length > 0 && expForm.formulaId === 0) {
      setExpForm((p) => ({ ...p, formulaId: Number(formulations[0].id) }));
    }
  }, [formulations, expForm.formulaId]);

  const pvcResult = useMemo(() => calculatePvc(ingredients), [ingredients]);

  const formulaGuard = useTransactionGuard('lab_create_formula', 'CREATE');
  const approveGuard = useTransactionGuard('lab_approve_formula', 'APPROVE_INVOICE');
  const expGuard = useTransactionGuard('lab_record_experiment', 'CREATE');
  const qcExpGuard = useTransactionGuard('lab_decide_experiment_qc', 'APPROVE_INVOICE');
  const priceGuard = useTransactionGuard('lab_update_rm_price', 'SAVE');
  const excelGuard = useTransactionGuard('lab_import_excel', 'CREATE');
  const sdsGuard = useTransactionGuard('lab_save_sds', 'SAVE');
  const deleteGuard = useTransactionGuard('lab_delete_formula', 'DELETE');

  const showNotice = (type: 'ok' | 'err', text: string) => {
    setNotice({ type, text });
    setTimeout(() => setNotice(null), 5000);
  };

  const maskedFormulations = useMemo(
    () => formulations.map((f) => redactFormulationForUser(f, userProfile)),
    [formulations, userProfile]
  );

  const filtered = useMemo(() => {
    return [...maskedFormulations]
      .sort((a, b) => (b.updatedAt || b.createdAt || 0) - (a.updatedAt || a.createdAt || 0))
      .filter((f) => {
        const st = String(f.approvalStatus || (f.approvedForProduction ? 'APPROVED' : 'NEEDS_REVIEW')).toUpperCase();
        if (statusFilter !== 'ALL' && st !== statusFilter) return false;
        if (!searchTerm) return true;
        const q = searchTerm.toLowerCase();
        return (
          (f.productName || '').toLowerCase().includes(q) ||
          (f.formulaNo || '').toLowerCase().includes(q) ||
          (f.paintType || '').toLowerCase().includes(q)
        );
      });
  }, [maskedFormulations, searchTerm, statusFilter]);

  const costComparison: ThreeCostComparisonReport | null = useMemo(() => {
    if (!costModalFormula || !allowCostView) return null;
    const ing =
      unsealedIngredientsMap[costModalFormula.formulaNo] || jsonToIngredients(costModalFormula.ingredientsJson);
    return compareThreeCosts(costModalFormula, ing, factoryItems);
  }, [costModalFormula, allowCostView, unsealedIngredientsMap, factoryItems]);

  const openNewFormulaModal = () => {
    setEditingFormula(null);
    setFormulaMeta({
      productName: '',
      paintType: 'مطاطي داخلي/خارجي',
      baseKind: 'أبيض',
      notes: '',
      changeReason: '',
      sealPassphrase: ''
    });
    setIsFormulaModalOpen(true);
  };

  const openRevisionModal = (f: LabFormulation) => {
    if (!allowFormulaEdit) return;
    const loaded = unsealedIngredientsMap[f.formulaNo] || jsonToIngredients(f.ingredientsJson);
    if (f.isConfidentialSealed && loaded.length === 0) {
      showNotice('err', 'التركيبة مقفولة بالتشفير السري — قم بفك القفل بكلمة المرور أولاً قبل التعديل.');
      return;
    }
    setEditingFormula(f);
    setFormulaMeta({
      productName: f.productName,
      paintType: f.paintType,
      baseKind: f.baseKind,
      notes: f.notes,
      changeReason: '',
      sealPassphrase: ''
    });
    setIngredients(loaded.length > 0 ? loaded : ingredients);
    setIsFormulaModalOpen(true);
  };

  const handleAddPresetIngredient = (idx: number) => {
    const preset = PRESET_MATERIALS[idx];
    if (!preset) return;
    const linked = factoryItems.find((fi) => fi.name.trim().toLowerCase() === preset.name.trim().toLowerCase());
    setIngredients((prev) => [
      ...prev,
      {
        id: `ing-${Date.now()}-${Math.random().toString(16).slice(2, 6)}`,
        materialId: linked?.id ?? null,
        itemCode: linked?.itemCode || '',
        name: preset.name,
        type: preset.type,
        weight: 50,
        density: linked?.density || preset.defaultDensity,
        solidsPercent: linked?.solidsPercent ?? preset.defaultSolidsPercent,
        unitCost: linked ? Number(linked.lastPurchasePrice || linked.unitCostEgp || preset.defaultUnitCost) : preset.defaultUnitCost,
        notes: preset.notes
      }
    ]);
  };

  const handleAddRegistryIngredient = (item: FactoryItem) => {
    setIngredients((prev) => [
      ...prev,
      {
        id: `ing-${Date.now()}-${Math.random().toString(16).slice(2, 6)}`,
        materialId: item.id,
        itemCode: item.itemCode || '',
        name: item.name,
        type: item.chemicalType || 'إضافات ومساعدات',
        weight: 25,
        density: item.density || 1.1,
        solidsPercent: item.solidsPercent ?? 100,
        unitCost: Number(item.lastPurchasePrice || item.unitCostEgp || 0),
        notes: item.supplierName ? `مورد: ${item.supplierName}` : 'مرتبط بسجل المخازن'
      }
    ]);
  };

  const handleScaleBatch = () => {
    const target = Number(scaleTargetKg);
    if (!Number.isFinite(target) || target <= 0) return;
    const { scaledIngredients } = scaleFormulation(ingredients, target);
    setIngredients(scaledIngredients);
    setScaleTargetKg('');
  };

  const handleSaveFormula = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!allowFormulaEdit || !formulaMeta.productName.trim() || formulaGuard.isSubmitting) return;
    if (editingFormula && !formulaMeta.changeReason.trim()) {
      showNotice('err', 'يرجى كتابة سبب التعديل عند إنشاء إصدار جديد من تركيبة قائمة.');
      return;
    }

    const cleanProd = formulaMeta.productName.trim();
    const pass = formulaMeta.sealPassphrase.trim();
    if (pass && pass.length < 4) {
      showNotice('err', 'كلمة مرور القفل السري يجب أن تكون 4 أحرف على الأقل.');
      return;
    }

    const res = await formulaGuard.runGuarded({
      guardKey: `lab_save_formula_${editingFormula?.formulaNo || cleanProd}_${Date.now()}`,
      operationType: 'CREATE',
      userId: userProfile?.username || actorName,
      documentReference: editingFormula?.formulaNo || `FORM-${cleanProd}`,
      targetCollection: 'lab_formulations',
      payloadSummary: `${editingFormula ? 'إصدار جديد' : 'إنشاء'} تركيبة ${cleanProd}`,
      execute: async () => {
        let ciphertext = '';
        let sealed = false;
        if (pass) {
          ciphertext = await sealFormulaWithPassphrase(JSON.stringify(ingredients), pass);
          sealed = true;
        }
        const saved = await ErpDataService.saveFormulationWithVersioning({
          existing: editingFormula,
          productName: cleanProd,
          paintType: formulaMeta.paintType,
          baseKind: formulaMeta.baseKind,
          notes: formulaMeta.notes,
          ingredients,
          changeReason: formulaMeta.changeReason,
          actor: actorName,
          confidentialCiphertext: ciphertext,
          isConfidentialSealed: sealed
        });
        return { resultReference: saved.docId, result: saved.formula };
      }
    });

    if (res.ok) {
      const f = res.data as LabFormulation | undefined;
      setIsFormulaModalOpen(false);
      setEditingFormula(null);
      showNotice(
        'ok',
        res.replayed
          ? res.message
          : `تم حفظ التركيبة ${f?.formulaNo || ''} (v${f?.versionNumber || 1}) بالحالة «تحتاج إلى مراجعة واعتماد» وحفظ لقطة الإصدار.`
      );
    } else {
      showNotice('err', res.message);
    }
  };

  const handleSubmitApproval = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!approvalModal || !allowFormulaApprove || approveGuard.isSubmitting) return;
    const { formula, decision } = approvalModal;
    const res = await approveGuard.runGuarded({
      guardKey: `lab_approval_${formula.formulaNo}_${decision}_${formula.versionNumber || 1}`,
      operationType: 'APPROVE_INVOICE',
      userId: userProfile?.username || actorName,
      documentReference: formula.formulaNo,
      targetCollection: 'lab_formulations',
      payloadSummary: `قرار ${decision} للتركيبة ${formula.formulaNo}`,
      execute: async () => {
        const updated = await ErpDataService.decideFormulationApproval({
          formula,
          decision,
          actor: actorName,
          reason: approvalReason
        });
        return { resultReference: updated.formulaNo, result: updated };
      }
    });
    if (res.ok) {
      setApprovalModal(null);
      setApprovalReason('');
      showNotice(
        'ok',
        decision === 'APPROVED'
          ? `تم اعتماد التركيبة ${formula.formulaNo} v${formula.versionNumber || 1} وتثبيت التكلفة التاريخية للإنتاج.`
          : `تم تحديث حالة التركيبة ${formula.formulaNo} إلى ${decision}.`
      );
    } else {
      showNotice('err', res.message);
    }
  };

  const handleUnsealFormula = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!unsealTarget || !unsealTarget.confidentialCiphertext) return;
    try {
      const plain = await unsealFormulaWithPassphrase(unsealTarget.confidentialCiphertext, unsealPassphrase);
      const parsed = jsonToIngredients(plain);
      setUnsealedIngredientsMap((prev) => ({ ...prev, [unsealTarget.formulaNo]: parsed }));
      setUnsealTarget(null);
      setUnsealPassphrase('');
      showNotice('ok', `تم فك القفل السري للتركيبة ${unsealTarget.formulaNo} في الذاكرة للعرض الحالي فقط.`);
    } catch {
      showNotice('err', 'كلمة المرور غير صحيحة أو تعذر فك تشفير التركيبة السرية.');
    }
  };

  const handleExcelFileSelected = async (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (!file) return;
    setExcelBusy(true);
    try {
      const buf = new Uint8Array(await file.arrayBuffer());
      const report = await analyzeXlsxWorkbook(file.name, buf, factoryItems, formulations);
      setExcelReport(report);
      const initialSelection: Record<number, boolean> = {};
      report.candidates.forEach((_, idx) => {
        initialSelection[idx] = true;
      });
      setSelectedCandidates(initialSelection);
    } catch (err: any) {
      showNotice('err', err?.message || 'تعذر تحليل ملف Excel — تأكد من صيغة .xlsx الصالحة.');
    } finally {
      setExcelBusy(false);
    }
  };

  const handleCommitExcelImport = async () => {
    if (!excelReport || !allowFormulaEdit || excelGuard.isSubmitting) return;
    const indices = Object.entries(selectedCandidates)
      .filter(([, checked]) => checked)
      .map(([idx]) => Number(idx));
    if (indices.length === 0) {
      showNotice('err', 'حدد تركيبة واحدة على الأقل للاستيراد.');
      return;
    }

    const res = await excelGuard.runGuarded({
      guardKey: `lab_excel_${excelReport.checksumSha256.slice(0, 16)}`,
      operationType: 'CREATE',
      userId: userProfile?.username || actorName,
      documentReference: excelReport.fileName,
      targetCollection: 'lab_excel_imports',
      payloadSummary: `استيراد ${indices.length} تركيبة من ${excelReport.fileName}`,
      execute: async () => {
        const result = await ErpDataService.commitStagedExcelImport({
          report: excelReport,
          selectedCandidateIndices: indices,
          existingItems: factoryItems,
          actor: actorName
        });
        return { resultReference: result.batch.importNo, result };
      }
    });

    if (res.ok) {
      const info = res.data as { importedCount: number; createdMaterialsCount: number } | undefined;
      setIsExcelModalOpen(false);
      setExcelReport(null);
      showNotice(
        'ok',
        `تم استيراد ${info?.importedCount || indices.length} تركيبة بحالة «تحتاج إلى مراجعة واعتماد» وإضافة ${info?.createdMaterialsCount || 0} مادة خام جديدة.`
      );
    } else {
      showNotice('err', res.message);
    }
  };

  const handleRecordExperiment = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!allowLogExperiment || expGuard.isSubmitting) return;
    const targetFormula = formulations.find((f) => Number(f.id) === Number(expForm.formulaId));
    if (!targetFormula) {
      showNotice('err', 'اختر التركيبة المرتبطة بالتجربة أولاً.');
      return;
    }
    const res = await expGuard.runGuarded({
      guardKey: `lab_exp_${targetFormula.formulaNo}_${Date.now()}`,
      operationType: 'CREATE',
      userId: userProfile?.username || actorName,
      documentReference: `EXP-${targetFormula.formulaNo}`,
      targetCollection: 'lab_experiments',
      payloadSummary: `تسجيل تجربة مخبرية للتركيبة ${targetFormula.formulaNo}`,
      execute: async () => {
        const saved = await ErpDataService.recordLabExperiment({
          formula: targetFormula,
          experimentDate: new Date().toISOString().split('T')[0],
          technicianName: actorName,
          measuredDensity: Number(expForm.measuredDensity) || 0,
          measuredViscosity: expForm.measuredViscosity,
          measuredPh: Number(expForm.measuredPh) || 0,
          measuredGrindMicrons: Number(expForm.measuredGrindMicrons) || 0,
          hidingPowerPercent: Number(expForm.hidingPowerPercent) || 0,
          notes: expForm.notes,
          actor: actorName
        });
        return { resultReference: saved.experimentNo, result: saved };
      }
    });
    if (res.ok) {
      setExpForm((p) => ({ ...p, notes: '' }));
      showNotice('ok', 'تم تسجيل التجربة المخبرية بنجاح وربطها بإصدار التركيبة.');
    } else {
      showNotice('err', res.message);
    }
  };

  const handleDecideExperimentQc = async (
    experiment: LabExperiment,
    decision: 'APPROVED' | 'REJECTED' | 'RETEST'
  ) => {
    if (!allowQcGate || qcExpGuard.isSubmitting) return;
    const res = await qcExpGuard.runGuarded({
      guardKey: `qc_exp_${experiment.experimentNo}_${decision}`,
      operationType: 'APPROVE_INVOICE',
      userId: userProfile?.username || actorName,
      documentReference: experiment.experimentNo,
      targetCollection: 'lab_experiments',
      payloadSummary: `قرار الجودة ${decision} للتجربة ${experiment.experimentNo}`,
      execute: async () => {
        const updated = await ErpDataService.decideLabExperimentQc({
          experiment,
          decision,
          actor: actorName
        });
        return { resultReference: updated.experimentNo, result: updated };
      }
    });
    if (res.ok) {
      showNotice('ok', `تم تثبيت قرار الجودة (${decision}) للتجربة ${experiment.experimentNo}`);
    } else {
      showNotice('err', res.message);
    }
  };

  const handleSaveMaterialPrice = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!priceEditItem || !allowMaterialRegistry || priceGuard.isSubmitting) return;
    const parsedPrice = Number(newMatPrice);
    if (!Number.isFinite(parsedPrice) || parsedPrice < 0) {
      showNotice('err', 'أدخل سعراً صالحاً للمادة الخام.');
      return;
    }
    const res = await priceGuard.runGuarded({
      guardKey: `rm_price_${priceEditItem.id}_${parsedPrice}`,
      operationType: 'SAVE',
      userId: userProfile?.username || actorName,
      documentReference: `RM-${priceEditItem.id}`,
      targetCollection: 'raw_material_price_history',
      payloadSummary: `تحديث سعر الخام ${priceEditItem.name}`,
      execute: async () => {
        const hist = await ErpDataService.updateRawMaterialRegistryPrice({
          item: priceEditItem,
          newPrice: parsedPrice,
          supplierName: newMatSupplier,
          sourceType: 'MANUAL_LAB',
          sourceDocNo: `LAB-UPD-${String(Date.now()).slice(-4)}`,
          actor: actorName
        });
        return { resultReference: hist.syncUuid, result: hist };
      }
    });
    if (res.ok) {
      setPriceEditItem(null);
      showNotice('ok', 'تم تحديث سعر المادة الخام وتسجيله في سجل الأسعار التاريخي دون تعديل تكلفة التركيبات المعتمدة سابقاً.');
    } else {
      showNotice('err', res.message);
    }
  };

  const handleDeleteFormula = async (f: LabFormulation) => {
    if (!canDelete || deleteGuard.isSubmitting) return;
    if (!window.confirm(`هل تريد بالتأكيد حذف التركيبة الكيميائية (${f.formulaNo || `FORM-${f.id}`}) نهائياً؟`)) {
      return;
    }
    const docId = (f as any)._docId || safeId(f.formulaNo, `FORM-${f.id}`);
    const res = await deleteGuard.runGuarded({
      guardKey: `lab_delete_formula_${docId}`,
      operationType: 'DELETE',
      userId: userProfile?.username || actorName,
      documentReference: docId,
      targetCollection: 'lab_formulations',
      payloadSummary: `حذف التركيبة ${f.formulaNo}`,
      execute: async () => {
        await ErpDataService.deleteRecord('lab_formulations', docId, f.id, userProfile?.username);
        await ErpDataService.deleteRecord('lab_formula_summaries', docId, f.id, userProfile?.username).catch(() => {});
        return { resultReference: docId };
      }
    });
    if (res.ok) {
      showNotice('ok', `تم حذف التركيبة ${f.formulaNo}`);
    } else {
      showNotice('err', res.message);
    }
  };

  const handleSaveSafety = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!allowFormulaEdit || !safetyForm.chemicalName.trim() || sdsGuard.isSubmitting) return;
    const chemName = safetyForm.chemicalName.trim();
    const res = await sdsGuard.runGuarded({
      guardKey: `lab_save_sds_${chemName}`,
      operationType: 'SAVE',
      userId: userProfile?.username || actorName,
      documentReference: `SDS-${chemName}`,
      targetCollection: 'chemical_safety',
      payloadSummary: `بطاقة سلامة كيميائية ${chemName}`,
      execute: async ({ idempotencyKey }) => {
        const id = Date.now();
        const docId = `SAFE-${idempotencyKey}`;
        const record: ChemicalSafety = {
          id,
          chemicalName: chemName,
          commonName: chemName,
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
        await ErpDataService.saveRecord('chemical_safety', docId, record, userProfile?.username);
        return { resultReference: docId };
      }
    });

    if (res.ok) {
      setSafetyForm({
        chemicalName: '',
        casNumber: '',
        signalWord: 'تحذير',
        hazardStatements: '',
        storageConditions: '',
        firstAidMeasures: ''
      });
      showNotice('ok', res.replayed ? res.message : 'تم حفظ بطاقة السلامة الكيميائية (SDS)');
    } else {
      showNotice('err', res.message);
    }
  };

  const handleExportCsv = () => {
    if (!allowExportSecret) {
      showNotice('err', 'تصدير التركيبات السرية مقصور على المصرح لهم بحماية الملكية الفكرية.');
      return;
    }
    const headers = ['كود التركيبة', 'الإصدار', 'الحالة', 'المنتج', 'النوع', 'PVC %', 'الكثافة', 'الجوامد %', 'التكلفة/كجم'];
    const rows = filtered.map((f) => [
      f.formulaNo,
      `v${f.versionNumber || 1}`,
      f.approvalStatus || (f.approvedForProduction ? 'APPROVED' : 'NEEDS_REVIEW'),
      f.productName,
      f.paintType,
      f.calculatedPvc,
      f.calculatedDensity,
      f.totalSolidsWeight,
      allowCostView ? f.costPerKg : 'محجوب'
    ]);
    ReportExporter.exportToCsv('pmp_chemical_formulations', headers, rows);
  };

  const handlePrint = () => {
    if (!allowExportSecret) {
      showNotice('err', 'الطباعة والتصدير محجوبان لغير المصرح لهم ببيانات المختبر.');
      return;
    }
    const headers = ['الكود', 'الإصدار', 'التركيبة', 'PVC %', 'الكثافة', 'التكلفة/كجم', 'الحالة'];
    const rows = filtered.map((f) => [
      f.formulaNo,
      `v${f.versionNumber || 1}`,
      f.productName,
      `${f.calculatedPvc}%`,
      f.calculatedDensity,
      allowCostView ? f.costPerKg : 'محجوب',
      statusBadgeMeta(f).label
    ]);
    ReportExporter.exportToPrint('التركيبات الكيميائية ومعادلات PVC - مختبر PMP', headers, rows);
  };

  const historyForSelected = useMemo(() => {
    if (!historyFormula) return [];
    return versions
      .filter((v) => v.formulaNo === historyFormula.formulaNo || Number(v.formulaId) === Number(historyFormula.id))
      .map((v) => redactVersionForUser(v, userProfile))
      .sort((a, b) => b.versionNumber - a.versionNumber || b.createdAt - a.createdAt);
  }, [historyFormula, versions, userProfile]);

  return (
    <div className="space-y-6">
      <GlassCard className="p-5 sm:p-6">
        <div className="flex flex-col sm:flex-row justify-between items-start sm:items-center gap-4 pb-4 border-b border-theme">
          <div>
            <div className="flex flex-wrap items-center gap-2">
              <FlaskConical className="w-5 h-5 text-pmp-primary" />
              <h2 className="text-xl font-black text-theme-primary">
                {language === 'ar' ? 'المختبر الكيميائي وهندسة الطلاء (PVC Engine)' : 'Chemistry Lab & PVC Engine'}
              </h2>
              <GlassBadge variant="cyan">{formulations.length} تركيبة</GlassBadge>
              <GlassBadge variant={allowSecretRead ? 'green' : 'gold'}>
                {allowSecretRead ? `وصول مختبري (${labRole})` : 'وضع محجوب الملكية الفكرية'}
              </GlassBadge>
            </div>
            <p className="text-xs text-theme-secondary mt-1">
              حوكمة الإصدارات غير القابلة للتعديل، ربط سجل الخامات بالمشتريات والمخازن، استيراد Excel المراجع، وبوابة الاعتماد قبل الإنتاج.
            </p>
          </div>
          <div className="flex items-center gap-2 flex-wrap">
            {allowExportSecret && (
              <>
                <GlassButton variant="secondary" icon={<FileSpreadsheet className="w-4 h-4" />} onClick={handleExportCsv} className="text-xs">
                  CSV
                </GlassButton>
                <GlassButton variant="secondary" icon={<Printer className="w-4 h-4" />} onClick={handlePrint} className="text-xs">
                  طباعة
                </GlassButton>
              </>
            )}
            {allowFormulaEdit && (
              <>
                <GlassButton
                  variant="secondary"
                  icon={<Upload className="w-4 h-4" />}
                  onClick={() => setIsExcelModalOpen(true)}
                  className="text-xs"
                >
                  استيراد وتحليل Excel
                </GlassButton>
                <GlassButton variant="primary" icon={<Plus className="w-4 h-4" />} onClick={openNewFormulaModal} className="text-xs">
                  + حاسبة وتركيبة جديدة
                </GlassButton>
              </>
            )}
          </div>
        </div>

        <div className="flex items-center gap-2 mt-4 overflow-x-auto pb-1">
          {[
            { id: 'FORMULATIONS' as LabSubTab, label: `التركيبات والإصدارات (${formulations.length})` },
            { id: 'EXPERIMENTS' as LabSubTab, label: `التجارب وبوابة QC (${experiments.length})` },
            ...(allowMaterialRegistry
              ? [{ id: 'MATERIALS_REGISTRY' as LabSubTab, label: `سجل الخامات والأسعار (${factoryItems.length})` }]
              : []),
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
        <div
          className={`p-3.5 rounded-xl border text-xs flex items-center gap-2 ${
            notice.type === 'ok'
              ? 'bg-emerald-500/10 border-emerald-500/30 text-emerald-400'
              : 'bg-red-500/10 border-red-500/30 text-red-400'
          }`}
        >
          {notice.type === 'ok' ? <CheckCircle className="w-4 h-4 shrink-0" /> : <ShieldAlert className="w-4 h-4 shrink-0" />}
          <span>{notice.text}</span>
        </div>
      )}

      {!allowSecretRead && (
        <div className="p-3.5 rounded-xl border bg-amber-500/10 border-amber-500/30 text-amber-300 text-xs flex items-center gap-2">
          <Lock className="w-4 h-4 shrink-0" />
          <span>حماية الملكية الفكرية مفعلة: نسب المكونات السرية والخلطات محجوبة عن دورك الوظيفي وتُعرض المؤشرات التشغيلية المصرح بها فقط.</span>
        </div>
      )}

      {subTab === 'FORMULATIONS' && (
        <div className="space-y-4">
          <GlassCard className="p-4 flex flex-col sm:flex-row gap-3 items-stretch sm:items-center">
            <div className="relative flex-1">
              <Search className="w-4 h-4 text-theme-muted absolute right-3 top-2.5" />
              <input
                type="text"
                placeholder="بحث باسم التركيبة، الكود، أو نوع الطلاء..."
                value={searchTerm}
                onChange={(e) => setSearchTerm(e.target.value)}
                className="w-full pr-9 pl-3 py-2 rounded-lg bg-theme-base border border-theme text-theme-primary text-xs"
              />
            </div>
            <div className="flex gap-1.5 flex-wrap text-xs">
              {[
                { key: 'ALL' as const, label: 'الكل' },
                { key: 'APPROVED' as const, label: 'المعتمدة' },
                { key: 'NEEDS_REVIEW' as const, label: 'بانتظار الاعتماد' },
                { key: 'REJECTED' as const, label: 'مرفوضة' }
              ].map((st) => (
                <button
                  key={st.key}
                  type="button"
                  onClick={() => setStatusFilter(st.key)}
                  className={`px-3 py-1.5 rounded-lg font-bold border ${
                    statusFilter === st.key
                      ? 'bg-pmp-primary text-white border-pmp-primary'
                      : 'bg-theme-base border-theme text-theme-secondary'
                  }`}
                >
                  {st.label}
                </button>
              ))}
            </div>
          </GlassCard>

          <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
            {filtered.map((f) => {
              const unsealedList = unsealedIngredientsMap[f.formulaNo];
              const ingList = allowSecretRead ? unsealedList || jsonToIngredients(f.ingredientsJson) : [];
              const badge = statusBadgeMeta(f);
              const needsUnseal = f.isConfidentialSealed && allowSecretRead && !unsealedList;
              return (
                <GlassCard key={`${f.formulaNo}-${f.id}`} className="p-5 space-y-3">
                  <div className="flex justify-between items-start gap-2">
                    <div>
                      <div className="flex items-center gap-2 flex-wrap">
                        <h4 className="font-bold text-theme-primary text-sm">{f.productName}</h4>
                        <GlassBadge variant="cyan">v{f.versionNumber || 1}</GlassBadge>
                        {f.isConfidentialSealed && <GlassBadge variant="gold">مقفولة AES-GCM</GlassBadge>}
                      </div>
                      <span className="font-mono text-[11px] text-pmp-primary font-bold">
                        {f.formulaNo} • {f.baseKind} • {f.paintType}
                      </span>
                    </div>
                    <div className="flex items-center gap-1">
                      <GlassBadge variant={badge.variant}>{badge.label}</GlassBadge>
                      {canDelete && (
                        <button
                          type="button"
                          onClick={() => handleDeleteFormula(f)}
                          title="حذف التركيبة"
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
                      <span className="text-[10px] text-theme-muted block">الوزن kg</span>
                      <span className="font-mono font-bold">{f.totalBatchWeight}</span>
                    </div>
                    <div>
                      <span className="text-[10px] text-theme-muted block">التكلفة/كجم</span>
                      <span className="font-mono font-bold text-emerald-400">
                        {allowCostView ? f.costPerKg : 'محجوب'}
                      </span>
                    </div>
                  </div>

                  {allowSecretRead && ingList.length > 0 && (
                    <div className="text-[11px] text-theme-secondary bg-theme-base/60 p-2.5 rounded-lg border border-theme/60 space-y-1">
                      <div className="font-bold text-theme-primary flex justify-between">
                        <span>المكونات ({ingList.length}):</span>
                        <span>الإجمالي التاريخي المقفل: {f.historicalTotalCost || f.totalCost}</span>
                      </div>
                      {ingList.slice(0, 4).map((ing, i) => (
                        <div key={i} className="flex justify-between font-mono text-[10px]">
                          <span className="truncate">{ing.name}</span>
                          <span>{ing.weight} كجم</span>
                        </div>
                      ))}
                      {ingList.length > 4 && (
                        <div className="text-[10px] text-theme-muted">+ {ingList.length - 4} مكونات أخرى...</div>
                      )}
                    </div>
                  )}

                  <div className="flex flex-wrap items-center justify-between gap-2 pt-2 border-t border-theme text-[11px]">
                    <div className="flex items-center gap-1.5 flex-wrap">
                      {needsUnseal && (
                        <GlassButton size="sm" variant="secondary" icon={<Unlock className="w-3 h-3" />} onClick={() => setUnsealTarget(f)}>
                          فك القفل
                        </GlassButton>
                      )}
                      {allowFormulaEdit && (
                        <GlassButton size="sm" variant="secondary" icon={<Edit3 className="w-3 h-3" />} onClick={() => openRevisionModal(f)}>
                          تعديل (v{(f.versionNumber || 1) + (f.approvedForProduction ? 1 : 0)})
                        </GlassButton>
                      )}
                      {allowSecretRead && (
                        <GlassButton size="sm" variant="secondary" icon={<GitBranch className="w-3 h-3" />} onClick={() => setHistoryFormula(f)}>
                          الإصدارات
                        </GlassButton>
                      )}
                      {allowCostView && allowSecretRead && (
                        <GlassButton size="sm" variant="secondary" icon={<Coins className="w-3 h-3" />} onClick={() => setCostModalFormula(f)}>
                          مقارنة 3 تكاليف
                        </GlassButton>
                      )}
                    </div>

                    {allowFormulaApprove && (
                      <div className="flex items-center gap-1">
                        {(!f.approvedForProduction || f.approvalStatus !== 'APPROVED') && (
                          <GlassButton
                            size="sm"
                            variant="primary"
                            icon={<CheckCircle2 className="w-3 h-3" />}
                            onClick={() => setApprovalModal({ formula: f, decision: 'APPROVED' })}
                          >
                            اعتماد
                          </GlassButton>
                        )}
                        <GlassButton
                          size="sm"
                          variant="secondary"
                          icon={<XCircle className="w-3 h-3" />}
                          onClick={() => setApprovalModal({ formula: f, decision: 'REJECTED' })}
                        >
                          رفض
                        </GlassButton>
                      </div>
                    )}
                  </div>
                </GlassCard>
              );
            })}
          </div>
        </div>
      )}

      {subTab === 'EXPERIMENTS' && (
        <div className="grid grid-cols-1 lg:grid-cols-12 gap-6">
          {allowLogExperiment && (
            <GlassCard className="p-5 lg:col-span-5">
              <h3 className="text-sm font-bold text-theme-primary mb-3 flex items-center gap-2">
                <TestTube2 className="w-4 h-4 text-pmp-primary" />
                تسجيل تجربة وفحص مخبري مرتبط بإصدار تركيبة
              </h3>
              <form onSubmit={handleRecordExperiment} className="space-y-3 text-xs">
                <select
                  value={expForm.formulaId}
                  onChange={(e) => setExpForm({ ...expForm, formulaId: Number(e.target.value) })}
                  className="w-full px-3 py-2 rounded-xl bg-theme-base border border-theme text-theme-primary"
                >
                  {formulations.map((f) => (
                    <option key={f.id} value={f.id}>
                      {f.formulaNo} (v{f.versionNumber || 1}) — {f.productName}
                    </option>
                  ))}
                </select>
                <div className="grid grid-cols-2 gap-2">
                  <div>
                    <label className="text-[10px] text-theme-muted block mb-1">الكثافة g/cm³</label>
                    <input
                      type="number"
                      step="0.001"
                      value={expForm.measuredDensity}
                      onChange={(e) => setExpForm({ ...expForm, measuredDensity: e.target.value })}
                      className="w-full px-3 py-2 rounded-xl bg-theme-base border border-theme font-mono"
                    />
                  </div>
                  <div>
                    <label className="text-[10px] text-theme-muted block mb-1">اللزوجة (KU)</label>
                    <input
                      type="text"
                      value={expForm.measuredViscosity}
                      onChange={(e) => setExpForm({ ...expForm, measuredViscosity: e.target.value })}
                      className="w-full px-3 py-2 rounded-xl bg-theme-base border border-theme font-mono"
                    />
                  </div>
                  <div>
                    <label className="text-[10px] text-theme-muted block mb-1">الأس الهيدروجيني pH</label>
                    <input
                      type="number"
                      step="0.1"
                      value={expForm.measuredPh}
                      onChange={(e) => setExpForm({ ...expForm, measuredPh: e.target.value })}
                      className="w-full px-3 py-2 rounded-xl bg-theme-base border border-theme font-mono"
                    />
                  </div>
                  <div>
                    <label className="text-[10px] text-theme-muted block mb-1">الطحن (ميكرون) / التغطية %</label>
                    <input
                      type="number"
                      step="0.1"
                      value={expForm.hidingPowerPercent}
                      onChange={(e) => setExpForm({ ...expForm, hidingPowerPercent: e.target.value })}
                      className="w-full px-3 py-2 rounded-xl bg-theme-base border border-theme font-mono"
                    />
                  </div>
                </div>
                <textarea
                  rows={2}
                  placeholder="ملاحظات المختبر..."
                  value={expForm.notes}
                  onChange={(e) => setExpForm({ ...expForm, notes: e.target.value })}
                  className="w-full px-3 py-2 rounded-xl bg-theme-base border border-theme"
                />
                <GlassButton
                  type="submit"
                  variant="primary"
                  className="w-full justify-center"
                  isLoading={expGuard.isSubmitting}
                  loadingText={OPERATION_LOADING_LABELS.CREATE}
                >
                  حفظ التجربة المخبرية
                </GlassButton>
              </form>
            </GlassCard>
          )}

          <GlassCard className={`p-5 ${allowLogExperiment ? 'lg:col-span-7' : 'lg:col-span-12'}`}>
            <h4 className="text-sm font-bold text-theme-primary mb-3">
              سجل التجارب المخبرية وقرارات بوابة الجودة ({experiments.length})
            </h4>
            <div className="space-y-2.5 text-xs">
              {experiments.map((ex) => (
                <div key={ex.syncUuid} className="p-3.5 rounded-xl bg-theme-base border border-theme space-y-2">
                  <div className="flex flex-wrap justify-between items-center gap-2">
                    <div>
                      <span className="font-mono font-bold text-pmp-primary">{ex.experimentNo}</span>
                      <span className="mx-1.5">•</span>
                      <span className="font-bold">
                        {ex.productName} ({ex.formulaNo} v{ex.versionNumber})
                      </span>
                    </div>
                    <GlassBadge
                      variant={
                        ex.decision === 'APPROVED' ? 'green' : ex.decision === 'REJECTED' ? 'red' : 'gold'
                      }
                    >
                      {ex.decision}
                    </GlassBadge>
                  </div>
                  <div className="grid grid-cols-4 gap-2 font-mono text-[11px] text-theme-secondary">
                    <span>الكثافة: {ex.measuredDensity}</span>
                    <span>اللزوجة: {ex.measuredViscosity}</span>
                    <span>pH: {ex.measuredPh}</span>
                    <span>التغطية: {ex.hidingPowerPercent}%</span>
                  </div>
                  {ex.notes && <p className="text-[11px] text-theme-muted">{ex.notes}</p>}
                  {allowQcGate && ex.decision !== 'APPROVED' && (
                    <div className="flex gap-2 pt-1">
                      <GlassButton
                        size="sm"
                        variant="primary"
                        onClick={() => handleDecideExperimentQc(ex, 'APPROVED')}
                      >
                        اعتماد QC (PASS)
                      </GlassButton>
                      <GlassButton
                        size="sm"
                        variant="secondary"
                        onClick={() => handleDecideExperimentQc(ex, 'RETEST')}
                      >
                        إعادة فحص (RETEST)
                      </GlassButton>
                      <GlassButton
                        size="sm"
                        variant="secondary"
                        onClick={() => handleDecideExperimentQc(ex, 'REJECTED')}
                      >
                        رفض (REJECT)
                      </GlassButton>
                    </div>
                  )}
                </div>
              ))}
              {experiments.length === 0 && (
                <div className="text-center text-theme-muted py-8">لا توجد تجارب مخبرية مسجلة حتى الآن.</div>
              )}
            </div>
          </GlassCard>
        </div>
      )}

      {subTab === 'MATERIALS_REGISTRY' && allowMaterialRegistry && (
        <div className="space-y-4">
          <GlassCard className="p-0 overflow-hidden">
            <div className="p-4 border-b border-theme flex justify-between items-center">
              <div className="flex items-center gap-2">
                <Database className="w-4 h-4 text-pmp-primary" />
                <h3 className="text-sm font-bold text-theme-primary">
                  سجل المواد الخام الموحد (مرتبط بالمشتريات GRN والمخازن)
                </h3>
              </div>
              <GlassBadge variant="cyan">{priceHistory.length} حركة سعرية تاريخية</GlassBadge>
            </div>
            <div className="overflow-x-auto">
              <table className="w-full text-right text-xs min-w-[680px]">
                <thead>
                  <tr className="bg-theme-surface/80 border-b border-theme text-theme-muted">
                    <th className="py-3 px-4">الكود</th>
                    <th className="py-3 px-4">المادة الخام</th>
                    <th className="py-3 px-4">الكثافة / الجوامد</th>
                    <th className="py-3 px-4">المورد</th>
                    <th className="py-3 px-4">آخر سعر شراء</th>
                    <th className="py-3 px-4">تحديث السعر</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-theme">
                  {factoryItems.map((fi) => (
                    <tr key={fi.id}>
                      <td className="py-3 px-4 font-mono">{fi.itemCode || `RAW-${fi.id}`}</td>
                      <td className="py-3 px-4 font-bold">{fi.name}</td>
                      <td className="py-3 px-4 font-mono">
                        {fi.density || 1.0} g/cm³ • {fi.solidsPercent ?? 100}%
                      </td>
                      <td className="py-3 px-4 text-theme-secondary">{fi.supplierName || '-'}</td>
                      <td className="py-3 px-4 font-mono font-bold text-emerald-400">
                        {Number(fi.lastPurchasePrice || fi.unitCostEgp || 0).toFixed(2)} {fi.defaultCurrency || 'EGP'}
                      </td>
                      <td className="py-3 px-4">
                        <GlassButton
                          size="sm"
                          variant="secondary"
                          onClick={() => {
                            setPriceEditItem(fi);
                            setNewMatPrice(String(fi.lastPurchasePrice || fi.unitCostEgp || 0));
                            setNewMatSupplier(fi.supplierName || '');
                          }}
                        >
                          تحديث + أرشفة سعر
                        </GlassButton>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </GlassCard>

          <GlassCard className="p-0 overflow-hidden">
            <div className="p-4 border-b border-theme">
              <h4 className="text-sm font-bold text-theme-primary">دليل المواصفات الفيزيائية القياسية ({PRESET_MATERIALS.length})</h4>
            </div>
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
        </div>
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
              <GlassButton
                type="submit"
                variant="primary"
                className="w-full justify-center"
                disabled={!allowFormulaEdit}
                isLoading={sdsGuard.isSubmitting}
                loadingText={OPERATION_LOADING_LABELS.SAVE}
              >
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

      {/* Formula Builder / Revision Modal */}
      <Modal
        isOpen={isFormulaModalOpen}
        onClose={() => setIsFormulaModalOpen(false)}
        title={
          editingFormula
            ? `تعديل إصدار تركيبة ${editingFormula.formulaNo} (إنشاء إصدار NEEDS_REVIEW)`
            : 'حاسبة التركيبات ومعادلة PVC (PvcEngine)'
        }
        maxWidth="max-w-4xl"
      >
        <form onSubmit={handleSaveFormula} className="space-y-4 text-xs">
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

          {editingFormula && (
            <input
              type="text"
              required
              placeholder="سبب تعديل التركيبة (مطلوب لحوكمة الإصدارات) *"
              value={formulaMeta.changeReason}
              onChange={(e) => setFormulaMeta({ ...formulaMeta, changeReason: e.target.value })}
              className="w-full px-3 py-2 rounded-lg bg-theme-surface border border-amber-500/40 text-theme-primary"
            />
          )}

          <div className="flex flex-wrap items-center gap-2 justify-between bg-theme-base p-2.5 rounded-xl border border-theme">
            <div className="flex flex-wrap gap-1.5">
              {PRESET_MATERIALS.slice(0, 8).map((pm, idx) => (
                <button
                  key={idx}
                  type="button"
                  onClick={() => handleAddPresetIngredient(idx)}
                  className="px-2 py-1 rounded-lg bg-theme-surface border border-theme hover:border-pmp-primary text-[10px] font-bold"
                >
                  + {pm.name.split('(')[0].trim()}
                </button>
              ))}
              {factoryItems.slice(0, 6).map((fi) => (
                <button
                  key={fi.id}
                  type="button"
                  onClick={() => handleAddRegistryIngredient(fi)}
                  className="px-2 py-1 rounded-lg bg-cyan-500/10 border border-cyan-500/30 text-cyan-300 text-[10px] font-bold"
                >
                  + خام: {fi.name}
                </button>
              ))}
            </div>
            <div className="flex items-center gap-1.5">
              <input
                type="number"
                step="any"
                placeholder="تحجيم kg"
                value={scaleTargetKg}
                onChange={(e) => setScaleTargetKg(e.target.value)}
                className="w-24 px-2 py-1 rounded bg-theme-surface border border-theme font-mono"
              />
              <GlassButton type="button" size="sm" variant="secondary" icon={<Scale className="w-3 h-3" />} onClick={handleScaleBatch}>
                تحجيم الدفعة
              </GlassButton>
            </div>
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
                    value={ing.unitCost}
                    onChange={(e) => {
                      const val = Number(e.target.value);
                      setIngredients((prev) => prev.map((x, idx) => (idx === i ? { ...x, unitCost: val } : x)));
                    }}
                    title="السعر/كجم"
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

          <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
            <input
              type="password"
              placeholder="قفل سري اختياري AES-GCM (كلمة مرور ≥ 4 أحرف)"
              value={formulaMeta.sealPassphrase}
              onChange={(e) => setFormulaMeta({ ...formulaMeta, sealPassphrase: e.target.value })}
              className="px-3 py-2 rounded-lg bg-theme-surface border border-theme text-theme-primary"
            />
            <div className="p-2.5 rounded-xl bg-pmp-primary/10 border border-pmp-primary/30 font-mono text-[11px] flex flex-wrap justify-between items-center">
              <span>PVC: {pvcResult.calculatedPvc}% | الكثافة: {pvcResult.calculatedDensity}</span>
              <span className="text-emerald-400 font-bold">التكلفة/كجم: {pvcResult.costPerKg}</span>
            </div>
          </div>

          <div className="flex justify-end gap-2">
            <GlassButton type="button" variant="secondary" onClick={() => setIsFormulaModalOpen(false)}>
              إلغاء
            </GlassButton>
            <GlassButton
              type="submit"
              variant="primary"
              isLoading={formulaGuard.isSubmitting}
              loadingText={OPERATION_LOADING_LABELS.CREATE}
            >
              حفظ كمسودة للمراجعة والاعتماد
            </GlassButton>
          </div>
        </form>
      </Modal>

      {/* Version History Modal */}
      <Modal
        isOpen={Boolean(historyFormula)}
        onClose={() => setHistoryFormula(null)}
        title={`سجل إصدارات التركيبة ${historyFormula?.formulaNo || ''}`}
        maxWidth="max-w-2xl"
      >
        <div className="space-y-3 text-xs">
          {historyForSelected.map((v) => (
            <div key={v.syncUuid} className="p-3 rounded-xl bg-theme-base border border-theme space-y-1">
              <div className="flex justify-between items-center">
                <span className="font-bold">الإصدار v{v.versionNumber} ({v.status})</span>
                <span className="font-mono text-[11px] text-theme-muted">
                  {new Date(v.createdAt).toLocaleString()}
                </span>
              </div>
              <p className="text-theme-secondary">السبب: {v.changeReason || '-'}</p>
              {allowCostView && (
                <p className="font-mono text-[11px] text-emerald-400">
                  التكلفة التاريخية: {v.historicalTotalCost} | تكلفة الكيلو: {v.newCostPerKg} {v.currency}
                </p>
              )}
            </div>
          ))}
          {historyForSelected.length === 0 && (
            <div className="text-center py-6 text-theme-muted">لا توجد لقطات إصدار مسجلة بعد.</div>
          )}
        </div>
      </Modal>

      {/* 3-Cost Variance Modal */}
      <Modal
        isOpen={Boolean(costModalFormula)}
        onClose={() => setCostModalFormula(null)}
        title={`تحليل التباين الثلاثي للتكاليف — ${costModalFormula?.formulaNo || ''}`}
        maxWidth="max-w-2xl"
      >
        {costComparison && (
          <div className="space-y-4 text-xs">
            <div className="grid grid-cols-3 gap-3">
              <div className="p-3 rounded-xl bg-theme-base border border-theme">
                <span className="text-[10px] text-theme-muted block">1. التكلفة التاريخية المعتمدة</span>
                <span className="font-mono font-bold text-sm">{costComparison.historicalTotalCost}</span>
                <span className="block text-[10px] font-mono text-theme-muted">{costComparison.historicalCostPerKg} / كجم</span>
              </div>
              <div className="p-3 rounded-xl bg-theme-base border border-theme">
                <span className="text-[10px] text-theme-muted block">2. التكلفة الحية بأسعار السوق</span>
                <span className="font-mono font-bold text-sm text-cyan-400">{costComparison.currentTotalCost}</span>
                <span className="block text-[10px] font-mono text-theme-muted">{costComparison.currentCostPerKg} / كجم</span>
              </div>
              <div className="p-3 rounded-xl bg-theme-base border border-theme">
                <span className="text-[10px] text-theme-muted block">3. فرق الأسعار (الانحراف)</span>
                <span className={`font-mono font-bold text-sm ${costComparison.priceDeltaTotal > 0 ? 'text-red-400' : 'text-emerald-400'}`}>
                  {costComparison.priceDeltaTotal > 0 ? '+' : ''}{costComparison.priceDeltaTotal} ({costComparison.priceDeltaPercent}%)
                </span>
              </div>
            </div>
            {costComparison.changedMaterials.length > 0 && (
              <div className="space-y-1.5">
                <h5 className="font-bold">الخامات المتغيرة أسعارها منذ الاعتماد:</h5>
                {costComparison.changedMaterials.map((cm, i) => (
                  <div key={i} className="flex justify-between p-2 rounded-lg bg-theme-base border border-theme font-mono text-[11px]">
                    <span>{cm.name}</span>
                    <span>{cm.oldUnitCost} ← {cm.currentUnitCost} (أثر/كجم: {cm.deltaPerKgImpact})</span>
                  </div>
                ))}
              </div>
            )}
          </div>
        )}
      </Modal>

      {/* Approval Modal */}
      <Modal
        isOpen={Boolean(approvalModal)}
        onClose={() => setApprovalModal(null)}
        title={`قرار اعتماد التركيبة ${approvalModal?.formula.formulaNo || ''}`}
      >
        <form onSubmit={handleSubmitApproval} className="space-y-3 text-xs">
          <p className="text-theme-secondary">
            {approvalModal?.decision === 'APPROVED'
              ? 'سيتم تفعيل هذه التركيبة للإنتاج وتجميد تكلفتها التاريخية الحالية.'
              : 'سيتم رفض/تعليق التركيبة وحظر استخدامها في أوامر الإنتاج.'}
          </p>
          <input
            type="text"
            placeholder="ملاحظة / سبب الاعتماد أو الرفض..."
            value={approvalReason}
            onChange={(e) => setApprovalReason(e.target.value)}
            className="w-full px-3 py-2 rounded-lg bg-theme-surface border border-theme"
          />
          <div className="flex justify-end gap-2">
            <GlassButton type="button" variant="secondary" onClick={() => setApprovalModal(null)}>
              إلغاء
            </GlassButton>
            <GlassButton type="submit" variant="primary" icon={<ShieldCheck className="w-3.5 h-3.5" />} isLoading={approveGuard.isSubmitting}>
              تأكيد القرار
            </GlassButton>
          </div>
        </form>
      </Modal>

      {/* Unseal AES-GCM Modal */}
      <Modal
        isOpen={Boolean(unsealTarget)}
        onClose={() => setUnsealTarget(null)}
        title={`فك تشفير التركيبة ${unsealTarget?.formulaNo || ''}`}
      >
        <form onSubmit={handleUnsealFormula} className="space-y-3 text-xs">
          <input
            type="password"
            required
            placeholder="أدخل كلمة المرور السرية للتركيبة"
            value={unsealPassphrase}
            onChange={(e) => setUnsealPassphrase(e.target.value)}
            className="w-full px-3 py-2 rounded-lg bg-theme-surface border border-theme"
          />
          <div className="flex justify-end gap-2">
            <GlassButton type="button" variant="secondary" onClick={() => setUnsealTarget(null)}>
              إلغاء
            </GlassButton>
            <GlassButton type="submit" variant="primary">فك القفل في الجلسة الحالية</GlassButton>
          </div>
        </form>
      </Modal>

      {/* Update Raw Material Registry Price Modal */}
      <Modal
        isOpen={Boolean(priceEditItem)}
        onClose={() => setPriceEditItem(null)}
        title={`تحديث سعر المادة الخام: ${priceEditItem?.name || ''}`}
      >
        <form onSubmit={handleSaveMaterialPrice} className="space-y-3 text-xs">
          <input
            type="number"
            step="0.01"
            required
            placeholder="السعر الجديد (EGP)"
            value={newMatPrice}
            onChange={(e) => setNewMatPrice(e.target.value)}
            className="w-full px-3 py-2 rounded-lg bg-theme-surface border border-theme font-mono"
          />
          <input
            type="text"
            placeholder="اسم المورد"
            value={newMatSupplier}
            onChange={(e) => setNewMatSupplier(e.target.value)}
            className="w-full px-3 py-2 rounded-lg bg-theme-surface border border-theme"
          />
          <div className="flex justify-end gap-2">
            <GlassButton type="button" variant="secondary" onClick={() => setPriceEditItem(null)}>إلغاء</GlassButton>
            <GlassButton type="submit" variant="primary" isLoading={priceGuard.isSubmitting}>حفظ السعر</GlassButton>
          </div>
        </form>
      </Modal>

      {/* Zero-Bloat Excel Analyzer & Staged Importer Modal */}
      <Modal
        isOpen={isExcelModalOpen}
        onClose={() => setIsExcelModalOpen(false)}
        title="محلل ومستورد ملفات تركيبات Excel (.xlsx) — معاينة ومراجعة قبل الاعتماد"
        maxWidth="max-w-4xl"
      >
        <div className="space-y-4 text-xs">
          <div className="p-4 rounded-xl bg-theme-base border border-dashed border-pmp-primary/50 flex flex-col sm:flex-row justify-between items-center gap-3">
            <div>
              <p className="font-bold text-theme-primary">اختر ملف Excel (.xlsx) لتحليل أوراق العمل والمعادلات محلياً</p>
              <p className="text-[11px] text-theme-muted">
                يعالج الملف محلياً بالكامل بدون إرسال البيانات لخدمات خارجية أو ذكاء اصطناعي، ويحفظ التركيبات بحالة «تحتاج إلى مراجعة واعتماد».
              </p>
            </div>
            <input type="file" accept=".xlsx" onChange={handleExcelFileSelected} className="text-xs" />
          </div>

          {excelBusy && <p className="text-center text-pmp-primary font-bold">جارٍ فحص أوراق العمل وتقييم المعادلات...</p>}

          {excelReport && (
            <div className="space-y-3">
              <div className="grid grid-cols-2 sm:grid-cols-4 gap-2">
                <div className="p-2.5 rounded-lg bg-theme-base border border-theme">
                  <span className="text-[10px] text-theme-muted block">الأوراق</span>
                  <span className="font-mono font-bold">{excelReport.sheets.length}</span>
                </div>
                <div className="p-2.5 rounded-lg bg-theme-base border border-theme">
                  <span className="text-[10px] text-theme-muted block">تركيبات مكتشفة</span>
                  <span className="font-mono font-bold text-emerald-400">{excelReport.candidates.length}</span>
                </div>
                <div className="p-2.5 rounded-lg bg-theme-base border border-theme">
                  <span className="text-[10px] text-theme-muted block">روابط خارجية / معطلة</span>
                  <span className="font-mono font-bold text-amber-400">
                    {excelReport.externalLinksDetected} / {excelReport.brokenRefsDetected}
                  </span>
                </div>
                <div className="p-2.5 rounded-lg bg-theme-base border border-theme">
                  <span className="text-[10px] text-theme-muted block">خامات مطابقة / جديدة</span>
                  <span className="font-mono font-bold">{excelReport.materialsMatchedCount} / {excelReport.materialsToCreateCount}</span>
                </div>
              </div>

              <div className="max-h-60 overflow-y-auto space-y-2">
                {excelReport.candidates.map((cand, idx) => (
                  <label
                    key={idx}
                    className="flex items-start gap-2.5 p-3 rounded-xl bg-theme-base border border-theme cursor-pointer"
                  >
                    <input
                      type="checkbox"
                      checked={Boolean(selectedCandidates[idx])}
                      onChange={(e) => setSelectedCandidates((p) => ({ ...p, [idx]: e.target.checked }))}
                    />
                    <div className="flex-1 space-y-1">
                      <div className="flex justify-between font-bold">
                        <span>{cand.productName} ({cand.formulaNo})</span>
                        <span className="font-mono text-emerald-400">
                          {cand.ingredients.length} خامات • {cand.calculatedBatchKg} كجم • {cand.calculatedTotalCost} EGP
                        </span>
                      </div>
                      {cand.warnings.length > 0 && (
                        <p className="text-[10px] text-amber-400">{cand.warnings.join(' | ')}</p>
                      )}
                    </div>
                  </label>
                ))}
              </div>

              <div className="flex justify-end gap-2">
                <GlassButton type="button" variant="secondary" onClick={() => setIsExcelModalOpen(false)}>إغلاق</GlassButton>
                <GlassButton
                  type="button"
                  variant="primary"
                  onClick={handleCommitExcelImport}
                  isLoading={excelGuard.isSubmitting}
                >
                  استيراد المحدد للمراجعة (NEEDS_REVIEW)
                </GlassButton>
              </div>
            </div>
          )}

          {excelBatches.length > 0 && (
            <div className="pt-2 border-t border-theme space-y-1.5">
              <h5 className="font-bold">سجل استيراد Excel السابق ({excelBatches.length}):</h5>
              {excelBatches.slice(0, 4).map((b) => (
                <div key={b.syncUuid} className="flex justify-between text-[11px] text-theme-muted font-mono">
                  <span>{b.importNo} — {b.fileName}</span>
                  <span>{b.formulasImported} تركيبة • SHA256: {b.checksumSha256.slice(0, 10)}</span>
                </div>
              ))}
            </div>
          )}
        </div>
      </Modal>
    </div>
  );
};
