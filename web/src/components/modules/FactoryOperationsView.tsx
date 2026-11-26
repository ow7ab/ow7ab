import React, { useEffect, useMemo, useState } from 'react';
import {
  AlertTriangle,
  Award,
  CheckCircle2,
  ClipboardList,
  Coins,
  Factory,
  Plus,
  RefreshCw,
  Truck,
  Wrench
} from 'lucide-react';
import { GlassCard } from '../ui/GlassCard';
import { GlassBadge } from '../ui/GlassBadge';
import { GlassButton } from '../ui/GlassButton';
import { StatCard } from '../ui/StatCard';
import { useAuth } from '../../context/AuthContext';
import { ROLE_PRESET_LIST } from '../../config/employeeRoles';
import { ErpDataService } from '../../services/ErpDataService';
import { isSuperAdminAccount, isSystemManager } from '../../services/SecurityService';
import {
  computeInTransitSummary,
  computeSeqaofaBalances,
  currentPeriodKey,
  DAILY_SHIFT_CARTON_TARGET,
  DEFAULT_MAX_WASTE_CANS_PER_LINE,
  evaluateBudgetDeviation,
  evaluateEmployeeBonusTier,
  evaluateShiftLogMetrics,
  seedDefaultFactoryOperations,
  todayDateKey
} from '../../services/OperationsService';
import {
  EmployeeRewardEvaluation,
  EmployeeRoleKey,
  EmployeeTaskItem,
  FinancialBudgetItem,
  InTransitShipment,
  PressMachine,
  PressStatus,
  SeqaofaEntry,
  ShiftProductionLog,
  ToolCustodyItem
} from '../../types/erp';

type OpsTab = 'SHIFT_LINES' | 'PRESSES_TOOLS' | 'SUDAN_TRANSIT' | 'BUDGET_TASKS' | 'REWARDS';

export const FactoryOperationsView: React.FC = () => {
  const { userProfile } = useAuth();
  const isManager = isSuperAdminAccount(userProfile) || isSystemManager(userProfile);

  const [tab, setTab] = useState<OpsTab>('SHIFT_LINES');
  const [shiftLogs, setShiftLogs] = useState<ShiftProductionLog[]>([]);
  const [presses, setPresses] = useState<PressMachine[]>([]);
  const [tools, setTools] = useState<ToolCustodyItem[]>([]);
  const [tasks, setTasks] = useState<EmployeeTaskItem[]>([]);
  const [seqaofaEntries, setSeqaofaEntries] = useState<SeqaofaEntry[]>([]);
  const [shipments, setShipments] = useState<InTransitShipment[]>([]);
  const [budgets, setBudgets] = useState<FinancialBudgetItem[]>([]);
  const [rewards, setRewards] = useState<EmployeeRewardEvaluation[]>([]);
  const [notice, setNotice] = useState<{ type: 'ok' | 'err'; text: string } | null>(null);
  const [busy, setBusy] = useState(false);

  const today = todayDateKey();

  const [shiftForm, setShiftForm] = useState({
    shiftDate: today,
    shiftName: 'الوردية الصباحية',
    lineNo: 1 as 1 | 2 | 3 | 4 | 5,
    batchNo: '',
    productName: 'لاكيه أبيض لامع',
    packageSize: '1/16',
    kettleWeightKg: '1000',
    producedCartons: '',
    wasteCans: '0',
    extraCans: '0',
    weightErrors: '0',
    stickerErrors: '0',
    technicalDowntimeMin: '0',
    materialDowntimeMin: '0',
    notes: ''
  });

  const [pressForm, setPressForm] = useState({
    name: '',
    location: 'صالة التعبئة - بدر',
    status: 'جاهز' as PressStatus,
    assignedSupervisor: 'منذر مزمل',
    notes: ''
  });

  const [toolForm, setToolForm] = useState({
    toolName: '',
    deliveredQty: '1',
    custodianName: 'منذر مزمل',
    countedQty: '1',
    notes: ''
  });

  const [transitForm, setTransitForm] = useState({
    invoiceRef: '',
    carrierName: 'الجبلاوي',
    destinationBranch: 'مخزن وادي حلفا',
    cartons116: '0',
    cartons1Kg: '0',
    cartonsGallon: '0',
    customsSdg: '0',
    freightEgp: '0',
    status: 'في الطريق' as InTransitShipment['status'],
    notes: ''
  });

  const [seqForm, setSeqForm] = useState({
    custodianName: 'محمد صديق',
    direction: 'وارد' as 'وارد' | 'منصرف',
    amountSdg: '',
    exchangeRate: '183.5',
    counterparty: 'تحويل للأصيل / المصنع',
    notes: ''
  });

  const [taskForm, setTaskForm] = useState({
    title: '',
    roleKey: 'PACKING_ENGINEER' as EmployeeRoleKey,
    assigneeName: '',
    dueDate: today,
    indicatorTarget: '',
    priority: 'عالية'
  });

  const [rewardForm, setRewardForm] = useState({
    employeeName: '',
    employeeUsername: '',
    roleTitle: 'مهندس التعبئة والجودة',
    completionPercent: '100',
    deviationPercent: '2',
    errorCount: '0',
    innovationCount: '0',
    notes: ''
  });

  useEffect(() => {
    const unsubs = [
      ErpDataService.subscribeToCollection<ShiftProductionLog>('shift_production_logs', setShiftLogs),
      ErpDataService.subscribeToCollection<PressMachine>('press_machines', setPresses),
      ErpDataService.subscribeToCollection<ToolCustodyItem>('tool_custodies', setTools),
      ErpDataService.subscribeToCollection<EmployeeTaskItem>('employee_tasks', setTasks),
      ErpDataService.subscribeToCollection<SeqaofaEntry>('seqaofa_entries', setSeqaofaEntries),
      ErpDataService.subscribeToCollection<InTransitShipment>('in_transit_shipments', setShipments),
      ErpDataService.subscribeToCollection<FinancialBudgetItem>('financial_budgets', setBudgets),
      ErpDataService.subscribeToCollection<EmployeeRewardEvaluation>('employee_rewards', setRewards)
    ];
    return () => unsubs.forEach((u) => u());
  }, []);

  const showNotice = (type: 'ok' | 'err', text: string) => {
    setNotice({ type, text });
    setTimeout(() => setNotice(null), 4000);
  };

  const todayLogs = useMemo(() => shiftLogs.filter((l) => l.shiftDate === today), [shiftLogs, today]);
  const todayCartons = useMemo(() => todayLogs.reduce((s, l) => s + (Number(l.producedCartons) || 0), 0), [todayLogs]);
  const seqaofaBalances = useMemo(() => computeSeqaofaBalances(seqaofaEntries), [seqaofaEntries]);
  const transitSummary = useMemo(() => computeInTransitSummary(shipments), [shipments]);

  const handleSeedDefaults = async () => {
    setBusy(true);
    try {
      const res = await seedDefaultFactoryOperations(
        userProfile?.fullName || userProfile?.username || 'المدير',
        userProfile?.branch || 'EG-001 (مصنع مصر والتصدير)'
      );
      showNotice('ok', res.createdCount > 0 ? `تمت إضافة ${res.createdCount} سجل تشغيلي` : 'البيانات الأساسية موجودة مسبقاً');
    } catch (err: any) {
      showNotice('err', err?.message || 'فشل تهيئة البيانات');
    } finally {
      setBusy(false);
    }
  };

  const handleSaveShiftLog = async (e: React.FormEvent) => {
    e.preventDefault();
    const cartons = Number(shiftForm.producedCartons) || 0;
    if (cartons <= 0) return showNotice('err', 'أدخل عدد الكراتين المنتجة');
    setBusy(true);
    try {
      const now = Date.now();
      const rec: ShiftProductionLog = {
        id: now,
        syncUuid: `SHF-${shiftForm.shiftDate}-L${shiftForm.lineNo}-${String(now).slice(-5)}`,
        shiftDate: shiftForm.shiftDate,
        shiftName: shiftForm.shiftName,
        lineNo: shiftForm.lineNo,
        engineerName: userProfile?.fullName || 'مهندس التعبئة',
        batchNo: shiftForm.batchNo.trim() || `B-${String(now).slice(-4)}`,
        productName: shiftForm.productName.trim(),
        packageSize: shiftForm.packageSize,
        kettleWeightKg: Number(shiftForm.kettleWeightKg) || 0,
        targetCartons: Math.round(DAILY_SHIFT_CARTON_TARGET / 5),
        producedCartons: cartons,
        emptyCansUsed: cartons * (shiftForm.packageSize === '1/16' ? 48 : shiftForm.packageSize === 'جالون' ? 4 : 12),
        emptyCartonsUsed: cartons,
        wasteCans: Number(shiftForm.wasteCans) || 0,
        maxWasteAllowed: DEFAULT_MAX_WASTE_CANS_PER_LINE,
        extraCans: Number(shiftForm.extraCans) || 0,
        weightErrors: Number(shiftForm.weightErrors) || 0,
        stickerErrors: Number(shiftForm.stickerErrors) || 0,
        technicalDowntimeMin: Number(shiftForm.technicalDowntimeMin) || 0,
        materialDowntimeMin: Number(shiftForm.materialDowntimeMin) || 0,
        workersCount: 6,
        notes: shiftForm.notes.trim(),
        branch: userProfile?.branch || 'EG-001 (مصنع مصر والتصدير)',
        createdBy: userProfile?.username || 'engineer',
        createdAt: now,
        updatedAt: now
      };
      await ErpDataService.saveRecord('shift_production_logs', rec.syncUuid, rec);
      setShiftForm((p) => ({ ...p, producedCartons: '', wasteCans: '0', notes: '' }));
      showNotice('ok', `تم تسجيل وردية خط ${rec.lineNo} بنجاح`);
    } catch (err: any) {
      showNotice('err', err?.message || 'تعذر الحفظ');
    } finally {
      setBusy(false);
    }
  };

  const handleSavePress = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!pressForm.name.trim()) return;
    setBusy(true);
    try {
      const now = Date.now();
      const rec: PressMachine = {
        id: now,
        syncUuid: `PRESS-${now}`,
        pressCode: `PRS-${String(now).slice(-3)}`,
        name: pressForm.name.trim(),
        location: pressForm.location.trim(),
        status: pressForm.status,
        lastMaintenanceDate: today,
        nextMaintenanceDate: today,
        assignedSupervisor: pressForm.assignedSupervisor.trim(),
        notes: pressForm.notes.trim(),
        branch: userProfile?.branch || 'EG-001 (مصنع مصر والتصدير)',
        updatedBy: userProfile?.username || 'supervisor',
        createdAt: now,
        updatedAt: now
      };
      await ErpDataService.saveRecord('press_machines', rec.syncUuid, rec);
      setPressForm((p) => ({ ...p, name: '', notes: '' }));
      showNotice('ok', `تمت إضافة ${rec.name}`);
    } catch (err: any) {
      showNotice('err', err?.message || 'تعذر الحفظ');
    } finally {
      setBusy(false);
    }
  };

  const handleSaveTool = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!toolForm.toolName.trim()) return;
    setBusy(true);
    try {
      const now = Date.now();
      const del = Number(toolForm.deliveredQty) || 0;
      const cnt = Number(toolForm.countedQty) || 0;
      const missing = Math.max(0, del - cnt);
      const rec: ToolCustodyItem = {
        id: now,
        syncUuid: `TOOL-${now}`,
        custodyNo: `T-${String(now).slice(-4)}`,
        toolName: toolForm.toolName.trim(),
        deliveredQty: del,
        deliveryCondition: 'سليم',
        custodianName: toolForm.custodianName.trim(),
        deliveryDate: today,
        monthlyAuditStatus: missing > 0 ? 'يوجد فاقد' : 'مطابق',
        countedQty: cnt,
        missingQty: missing,
        actionTaken: missing > 0 ? 'يتطلب محضر خصم/استعاضة' : '',
        notes: toolForm.notes.trim(),
        branch: userProfile?.branch || 'EG-001 (مصنع مصر والتصدير)',
        updatedBy: userProfile?.username || 'supervisor',
        createdAt: now,
        updatedAt: now
      };
      await ErpDataService.saveRecord('tool_custodies', rec.syncUuid, rec);
      setToolForm((p) => ({ ...p, toolName: '', notes: '' }));
      showNotice('ok', `تم تسجيل عهدة: ${rec.toolName}`);
    } catch (err: any) {
      showNotice('err', err?.message || 'تعذر الحفظ');
    } finally {
      setBusy(false);
    }
  };

  const handleSaveTransit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!transitForm.invoiceRef.trim()) return showNotice('err', 'أدخل رقم الفاتورة / الإرسالية');
    setBusy(true);
    try {
      const now = Date.now();
      const rec: InTransitShipment = {
        id: now,
        syncUuid: `TRN-${now}`,
        dispatchDate: today,
        invoiceRef: transitForm.invoiceRef.trim(),
        carrierName: transitForm.carrierName.trim(),
        destinationBranch: transitForm.destinationBranch.trim(),
        cartons116: Number(transitForm.cartons116) || 0,
        cartons1Kg: Number(transitForm.cartons1Kg) || 0,
        cartonsGallon: Number(transitForm.cartonsGallon) || 0,
        customsSdg: Number(transitForm.customsSdg) || 0,
        freightEgp: Number(transitForm.freightEgp) || 0,
        status: transitForm.status,
        receivedDate: transitForm.status === 'تم الاستلام' ? today : '',
        notes: transitForm.notes.trim(),
        branch: userProfile?.branch || 'HF-001 (مستودع حلفا اللوجستي)',
        createdBy: userProfile?.username || 'sudan',
        createdAt: now,
        updatedAt: now
      };
      await ErpDataService.saveRecord('in_transit_shipments', rec.syncUuid, rec);
      setTransitForm((p) => ({ ...p, invoiceRef: '', cartons116: '0', cartons1Kg: '0', cartonsGallon: '0', notes: '' }));
      showNotice('ok', `تم تسجيل شحنة الطريق (${rec.invoiceRef})`);
    } catch (err: any) {
      showNotice('err', err?.message || 'تعذر الحفظ');
    } finally {
      setBusy(false);
    }
  };

  const handleSaveSeq = async (e: React.FormEvent) => {
    e.preventDefault();
    const amt = Number(seqForm.amountSdg) || 0;
    const rate = Number(seqForm.exchangeRate) || 1;
    if (amt <= 0 || rate <= 0) return showNotice('err', 'أدخل مبلغاً وسعر صرف صحيحين');
    setBusy(true);
    try {
      const now = Date.now();
      const rec: SeqaofaEntry = {
        id: now,
        syncUuid: `SEQ-${now}`,
        entryDate: today,
        custodianName: seqForm.custodianName,
        direction: seqForm.direction,
        amountSdg: amt,
        exchangeRate: rate,
        equivalentEgp: Math.round((amt / rate) * 100) / 100,
        counterparty: seqForm.counterparty.trim(),
        referenceNo: `SQ-${String(now).slice(-5)}`,
        notes: seqForm.notes.trim(),
        branch: userProfile?.branch || 'HF-001 (مستودع حلفا اللوجستي)',
        createdBy: userProfile?.username || 'sudan',
        createdAt: now,
        updatedAt: now
      };
      await ErpDataService.saveRecord('seqaofa_entries', rec.syncUuid, rec);
      setSeqForm((p) => ({ ...p, amountSdg: '', notes: '' }));
      showNotice('ok', 'تم حفظ قيد السقوفة بنجاح');
    } catch (err: any) {
      showNotice('err', err?.message || 'تعذر الحفظ');
    } finally {
      setBusy(false);
    }
  };

  const handleSaveTask = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!taskForm.title.trim()) return;
    setBusy(true);
    try {
      const now = Date.now();
      const roleMeta = ROLE_PRESET_LIST.find((r) => r.key === taskForm.roleKey);
      const rec: EmployeeTaskItem = {
        id: now,
        syncUuid: `TSK-${now}`,
        title: taskForm.title.trim(),
        department: roleMeta?.departmentAr || 'التشغيل',
        assigneeName: taskForm.assigneeName.trim() || roleMeta?.sampleHolders || 'موظف',
        assigneeUsername: '',
        roleKey: taskForm.roleKey,
        priority: taskForm.priority,
        startDate: today,
        dueDate: taskForm.dueDate || today,
        status: 'لم يبدأ',
        completionPercent: 0,
        indicatorTarget: taskForm.indicatorTarget.trim() || '100%',
        notes: '',
        branch: roleMeta?.defaultBranch || 'EG-001 (مصنع مصر والتصدير)',
        createdBy: userProfile?.username || 'admin',
        createdAt: now,
        updatedAt: now
      };
      await ErpDataService.saveRecord('employee_tasks', rec.syncUuid, rec);
      setTaskForm((p) => ({ ...p, title: '', assigneeName: '', indicatorTarget: '' }));
      showNotice('ok', 'تم إسناد المهمة بنجاح');
    } catch (err: any) {
      showNotice('err', err?.message || 'تعذر الحفظ');
    } finally {
      setBusy(false);
    }
  };

  const handleSaveReward = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!rewardForm.employeeName.trim()) return;
    setBusy(true);
    try {
      const now = Date.now();
      const tier = evaluateEmployeeBonusTier({
        completionPercent: Number(rewardForm.completionPercent),
        deviationPercent: Number(rewardForm.deviationPercent),
        errorCount: Number(rewardForm.errorCount),
        innovationCount: Number(rewardForm.innovationCount)
      });
      const rec: EmployeeRewardEvaluation = {
        id: now,
        syncUuid: `RWD-${currentPeriodKey()}-${now}`,
        periodKey: currentPeriodKey(),
        employeeName: rewardForm.employeeName.trim(),
        employeeUsername: rewardForm.employeeUsername.trim(),
        roleTitle: rewardForm.roleTitle.trim(),
        completionPercent: Number(rewardForm.completionPercent) || 0,
        deviationPercent: Number(rewardForm.deviationPercent) || 0,
        errorCount: Number(rewardForm.errorCount) || 0,
        innovationCount: Number(rewardForm.innovationCount) || 0,
        bonusTier: tier.bonusTier,
        bonusMonths: tier.bonusMonths,
        notes: rewardForm.notes.trim(),
        branch: userProfile?.branch || 'EG-001 (مصنع مصر والتصدير)',
        evaluatedBy: userProfile?.fullName || userProfile?.username || 'المدير',
        createdAt: now,
        updatedAt: now
      };
      await ErpDataService.saveRecord('employee_rewards', rec.syncUuid, rec);
      setRewardForm((p) => ({ ...p, employeeName: '', employeeUsername: '', notes: '' }));
      showNotice('ok', `تم احتساب المكافأة: ${tier.bonusTier}`);
    } catch (err: any) {
      showNotice('err', err?.message || 'تعذر الحفظ');
    } finally {
      setBusy(false);
    }
  };

  const tabs: Array<{ id: OpsTab; label: string; icon: React.ReactNode }> = [
    { id: 'SHIFT_LINES', label: 'خطوط التعبئة (1–5)', icon: <Factory className="w-4 h-4" /> },
    { id: 'PRESSES_TOOLS', label: 'المكابس وعهدة العدد', icon: <Wrench className="w-4 h-4" /> },
    { id: 'SUDAN_TRANSIT', label: 'السقوفة وبضاعة الطريق', icon: <Truck className="w-4 h-4" /> },
    { id: 'BUDGET_TASKS', label: 'المهام والرقابة المالية', icon: <ClipboardList className="w-4 h-4" /> },
    { id: 'REWARDS', label: 'نظام المكافآت', icon: <Award className="w-4 h-4" /> }
  ];

  return (
    <div className="space-y-5">
      <div className="flex flex-col sm:flex-row justify-between items-start sm:items-center gap-3 pb-4 border-b border-theme">
        <div>
          <h2 className="text-xl font-black text-theme-primary">تشغيل المصنع والمتابعة الميدانية والمالية</h2>
          <p className="text-xs text-theme-secondary">
            إدارة خطوط التعبئة الـ 5، المكابس، عهدة العدد، السقوفة وبضاعة الطريق، الرقابة المالية (5%)، والمكافآت الموسمية.
          </p>
        </div>
        {isManager && (
          <GlassButton variant="secondary" onClick={handleSeedDefaults} disabled={busy} icon={<RefreshCw className="w-3.5 h-3.5" />}>
            تهيئة البيانات الأساسية للموسم
          </GlassButton>
        )}
      </div>

      {notice && (
        <div className={`p-3 rounded-xl border text-xs font-bold flex items-center gap-2 ${notice.type === 'ok' ? 'bg-emerald-500/10 border-emerald-500/30 text-emerald-400' : 'bg-red-500/10 border-red-500/30 text-red-400'}`}>
          {notice.type === 'ok' ? <CheckCircle2 className="w-4 h-4" /> : <AlertTriangle className="w-4 h-4" />}
          <span>{notice.text}</span>
        </div>
      )}

      <div className="grid grid-cols-1 sm:grid-cols-2 xl:grid-cols-4 gap-4">
        <StatCard title="إنتاج اليوم (5 خطوط)" value={`${todayCartons} / ${DAILY_SHIFT_CARTON_TARGET}`} subtitle="كرتونة في الوردية" icon={<Factory className="w-5 h-5" />} accent="cyan" />
        <StatCard title="المكابس الجاهزة" value={`${presses.filter((p) => p.status === 'جاهز').length} / ${presses.length}`} subtitle="حالة الصيانة والتشغيل" icon={<Wrench className="w-5 h-5" />} accent="green" />
        <StatCard title="شحنات في الطريق" value={`${transitSummary.pendingCount} إرسالية`} subtitle={`${transitSummary.totalCartons116 + transitSummary.totalCartons1Kg} كرتونة منتظرة`} icon={<Truck className="w-5 h-5" />} accent="gold" />
        <StatCard title="أرصدة السقوفة" value={`${seqaofaBalances.reduce((s, c) => s + c.balanceSdg, 0).toLocaleString()} SDG`} subtitle={`${seqaofaBalances.length} عهدة نشطة`} icon={<Coins className="w-5 h-5" />} accent="cyan" />
      </div>

      {/* Sub Tabs */}
      <div className="flex flex-wrap gap-2">
        {tabs.map((t) => (
          <button
            key={t.id}
            type="button"
            onClick={() => setTab(t.id)}
            className={`px-3.5 py-2 rounded-xl text-xs font-bold flex items-center gap-2 transition-all border ${
              tab === t.id
                ? 'bg-pmp-primary text-white border-pmp-primary shadow-md'
                : 'bg-theme-surface border-theme text-theme-secondary hover:text-theme-primary'
            }`}
          >
            {t.icon}
            <span>{t.label}</span>
          </button>
        ))}
      </div>

      {/* TAB 1: SHIFT & 5 LINES */}
      {tab === 'SHIFT_LINES' && (
        <div className="space-y-5">
          <GlassCard className="p-5 space-y-4">
            <h3 className="text-sm font-black text-theme-primary">تسجيل إنتاج نهاية الوردية (الخطوط 1–5)</h3>
            <form onSubmit={handleSaveShiftLog} className="grid grid-cols-2 sm:grid-cols-4 lg:grid-cols-6 gap-3 text-xs">
              <input type="date" value={shiftForm.shiftDate} onChange={(e) => setShiftForm({ ...shiftForm, shiftDate: e.target.value })} className="px-2.5 py-2 rounded-xl bg-theme-base border border-theme text-theme-primary" />
              <select value={shiftForm.lineNo} onChange={(e) => setShiftForm({ ...shiftForm, lineNo: Number(e.target.value) as 1 | 2 | 3 | 4 | 5 })} className="px-2.5 py-2 rounded-xl bg-theme-base border border-theme text-theme-primary">
                {[1, 2, 3, 4, 5].map((n) => <option key={n} value={n}>خط {n}</option>)}
              </select>
              <select value={shiftForm.packageSize} onChange={(e) => setShiftForm({ ...shiftForm, packageSize: e.target.value })} className="px-2.5 py-2 rounded-xl bg-theme-base border border-theme text-theme-primary">
                <option value="1/16">1/16</option>
                <option value="1 كيلو">1 كيلو</option>
                <option value="جالون">جالون</option>
                <option value="مهندسين">مهندسين</option>
              </select>
              <input type="text" value={shiftForm.productName} onChange={(e) => setShiftForm({ ...shiftForm, productName: e.target.value })} placeholder="اللون / الصنف" className="px-2.5 py-2 rounded-xl bg-theme-base border border-theme text-theme-primary" />
              <input type="text" value={shiftForm.batchNo} onChange={(e) => setShiftForm({ ...shiftForm, batchNo: e.target.value })} placeholder="رقم الباتشة" className="px-2.5 py-2 rounded-xl bg-theme-base border border-theme text-theme-primary" />
              <input type="number" required min={1} value={shiftForm.producedCartons} onChange={(e) => setShiftForm({ ...shiftForm, producedCartons: e.target.value })} placeholder="الكراتين المنتجة *" className="px-2.5 py-2 rounded-xl bg-theme-base border border-theme text-theme-primary" />
              <input type="number" min={0} value={shiftForm.wasteCans} onChange={(e) => setShiftForm({ ...shiftForm, wasteCans: e.target.value })} placeholder="الهالك (عبوة)" className="px-2.5 py-2 rounded-xl bg-theme-base border border-theme text-theme-primary" />
              <input type="number" min={0} value={shiftForm.weightErrors} onChange={(e) => setShiftForm({ ...shiftForm, weightErrors: e.target.value })} placeholder="أخطاء الوزن" className="px-2.5 py-2 rounded-xl bg-theme-base border border-theme text-theme-primary" />
              <input type="number" min={0} value={shiftForm.stickerErrors} onChange={(e) => setShiftForm({ ...shiftForm, stickerErrors: e.target.value })} placeholder="أخطاء الاستيكر" className="px-2.5 py-2 rounded-xl bg-theme-base border border-theme text-theme-primary" />
              <input type="number" min={0} value={shiftForm.technicalDowntimeMin} onChange={(e) => setShiftForm({ ...shiftForm, technicalDowntimeMin: e.target.value })} placeholder="توقف فني (د)" className="px-2.5 py-2 rounded-xl bg-theme-base border border-theme text-theme-primary" />
              <input type="number" min={0} value={shiftForm.materialDowntimeMin} onChange={(e) => setShiftForm({ ...shiftForm, materialDowntimeMin: e.target.value })} placeholder="توقف خامات (د)" className="px-2.5 py-2 rounded-xl bg-theme-base border border-theme text-theme-primary" />
              <GlassButton type="submit" variant="primary" disabled={busy} className="justify-center" icon={<Plus className="w-4 h-4" />}>تسجيل</GlassButton>
            </form>
          </GlassCard>

          <GlassCard className="p-5 overflow-x-auto">
            <table className="w-full text-xs text-right">
              <thead className="border-b border-theme text-theme-muted">
                <tr>
                  <th className="py-2 px-3">التاريخ</th>
                  <th className="py-2 px-3">الخط</th>
                  <th className="py-2 px-3">الصنف والعبوة</th>
                  <th className="py-2 px-3">الإنتاج</th>
                  <th className="py-2 px-3">الهالك (الحد 25)</th>
                  <th className="py-2 px-3">أخطاء</th>
                  <th className="py-2 px-3">توقف (د)</th>
                  <th className="py-2 px-3">الحالة</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-theme/50">
                {shiftLogs.slice(0, 30).map((log) => {
                  const ev = evaluateShiftLogMetrics(log);
                  return (
                    <tr key={log.syncUuid}>
                      <td className="py-2.5 px-3 font-mono">{log.shiftDate}</td>
                      <td className="py-2.5 px-3 font-bold">خط {log.lineNo}</td>
                      <td className="py-2.5 px-3">{log.productName} ({log.packageSize})</td>
                      <td className="py-2.5 px-3 font-black">{log.producedCartons} كرتونة ({ev.achievementPercent}%)</td>
                      <td className="py-2.5 px-3 font-bold">{log.wasteCans} عبوة</td>
                      <td className="py-2.5 px-3">{(log.weightErrors || 0) + (log.stickerErrors || 0)}</td>
                      <td className="py-2.5 px-3">{ev.totalDowntimeMin} د</td>
                      <td className="py-2.5 px-3">
                        <GlassBadge variant={ev.wasteExceeded ? 'red' : 'green'}>{ev.statusLabel}</GlassBadge>
                      </td>
                    </tr>
                  );
                })}
                {shiftLogs.length === 0 && (
                  <tr><td colSpan={8} className="text-center py-6 text-theme-muted">لا توجد سجلات ورديات مسجلة بعد.</td></tr>
                )}
              </tbody>
            </table>
          </GlassCard>
        </div>
      )}

      {/* TAB 2: PRESSES & TOOL CUSTODY */}
      {tab === 'PRESSES_TOOLS' && (
        <div className="grid grid-cols-1 lg:grid-cols-2 gap-5">
          <GlassCard className="p-5 space-y-4">
            <h3 className="text-sm font-black text-theme-primary">إدارة المكابس وحالات الصيانة</h3>
            <form onSubmit={handleSavePress} className="grid grid-cols-2 gap-2.5 text-xs">
              <input type="text" required value={pressForm.name} onChange={(e) => setPressForm({ ...pressForm, name: e.target.value })} placeholder="اسم المكبس (مثال: مكبس 5)" className="px-2.5 py-2 rounded-xl bg-theme-base border border-theme text-theme-primary" />
              <select value={pressForm.status} onChange={(e) => setPressForm({ ...pressForm, status: e.target.value as PressStatus })} className="px-2.5 py-2 rounded-xl bg-theme-base border border-theme text-theme-primary">
                <option value="جاهز">جاهز</option>
                <option value="تحت الصيانة">تحت الصيانة</option>
                <option value="متوقف">متوقف</option>
                <option value="يحتاج قطع غيار">يحتاج قطع غيار</option>
              </select>
              <input type="text" value={pressForm.assignedSupervisor} onChange={(e) => setPressForm({ ...pressForm, assignedSupervisor: e.target.value })} placeholder="المشرف" className="px-2.5 py-2 rounded-xl bg-theme-base border border-theme text-theme-primary" />
              <GlassButton type="submit" variant="primary" disabled={busy} className="justify-center">إضافة مكبس</GlassButton>
            </form>
            <div className="space-y-2">
              {presses.map((p) => (
                <div key={p.syncUuid} className="p-3 rounded-xl bg-theme-base border border-theme flex justify-between items-center text-xs">
                  <div>
                    <div className="font-black text-theme-primary">{p.name}</div>
                    <div className="text-[10px] text-theme-muted">المسؤول: {p.assignedSupervisor} • {p.lastMaintenanceDate}</div>
                  </div>
                  <GlassBadge variant={p.status === 'جاهز' ? 'green' : p.status === 'متوقف' ? 'red' : 'gold'}>{p.status}</GlassBadge>
                </div>
              ))}
            </div>
          </GlassCard>

          <GlassCard className="p-5 space-y-4">
            <h3 className="text-sm font-black text-theme-primary">صندوق العدة والجرد الشهري</h3>
            <form onSubmit={handleSaveTool} className="grid grid-cols-2 gap-2.5 text-xs">
              <input type="text" required value={toolForm.toolName} onChange={(e) => setToolForm({ ...toolForm, toolName: e.target.value })} placeholder="اسم العدة" className="px-2.5 py-2 rounded-xl bg-theme-base border border-theme text-theme-primary" />
              <input type="text" value={toolForm.custodianName} onChange={(e) => setToolForm({ ...toolForm, custodianName: e.target.value })} placeholder="المستلم (منذر)" className="px-2.5 py-2 rounded-xl bg-theme-base border border-theme text-theme-primary" />
              <input type="number" min={0} value={toolForm.deliveredQty} onChange={(e) => setToolForm({ ...toolForm, deliveredQty: e.target.value })} placeholder="المسلّم" className="px-2.5 py-2 rounded-xl bg-theme-base border border-theme text-theme-primary" />
              <input type="number" min={0} value={toolForm.countedQty} onChange={(e) => setToolForm({ ...toolForm, countedQty: e.target.value })} placeholder="عند الجرد" className="px-2.5 py-2 rounded-xl bg-theme-base border border-theme text-theme-primary" />
              <GlassButton type="submit" variant="primary" disabled={busy} className="col-span-2 justify-center">تسجيل عهدة عدة</GlassButton>
            </form>
            <div className="space-y-2">
              {tools.map((t) => (
                <div key={t.syncUuid} className="p-3 rounded-xl bg-theme-base border border-theme flex justify-between items-center text-xs">
                  <div>
                    <div className="font-black text-theme-primary">{t.toolName}</div>
                    <div className="text-[10px] text-theme-muted">المسؤول: {t.custodianName} • مسلّم: {t.deliveredQty} • جرد: {t.countedQty}</div>
                  </div>
                  <GlassBadge variant={t.missingQty > 0 ? 'red' : 'green'}>
                    {t.missingQty > 0 ? `فاقد ${t.missingQty}` : 'مطابق'}
                  </GlassBadge>
                </div>
              ))}
            </div>
          </GlassCard>
        </div>
      )}

      {/* TAB 3: SEQAOFA & IN-TRANSIT */}
      {tab === 'SUDAN_TRANSIT' && (
        <div className="grid grid-cols-1 lg:grid-cols-2 gap-5">
          <GlassCard className="p-5 space-y-4">
            <h3 className="text-sm font-black text-theme-primary">دفتر السقوفة وتحويلات العملة (محمد صديق ↔ عمر)</h3>
            <form onSubmit={handleSaveSeq} className="grid grid-cols-2 gap-2.5 text-xs">
              <select value={seqForm.custodianName} onChange={(e) => setSeqForm({ ...seqForm, custodianName: e.target.value })} className="px-2.5 py-2 rounded-xl bg-theme-base border border-theme text-theme-primary">
                <option value="محمد صديق">محمد صديق</option>
                <option value="عمر عبدالوهاب">عمر عبدالوهاب</option>
              </select>
              <select value={seqForm.direction} onChange={(e) => setSeqForm({ ...seqForm, direction: e.target.value as 'وارد' | 'منصرف' })} className="px-2.5 py-2 rounded-xl bg-theme-base border border-theme text-theme-primary">
                <option value="وارد">وارد</option>
                <option value="منصرف">منصرف</option>
              </select>
              <input type="number" required min={1} value={seqForm.amountSdg} onChange={(e) => setSeqForm({ ...seqForm, amountSdg: e.target.value })} placeholder="المبلغ SDG" className="px-2.5 py-2 rounded-xl bg-theme-base border border-theme text-theme-primary" />
              <input type="number" step="0.1" required value={seqForm.exchangeRate} onChange={(e) => setSeqForm({ ...seqForm, exchangeRate: e.target.value })} placeholder="سعر الصرف" className="px-2.5 py-2 rounded-xl bg-theme-base border border-theme text-theme-primary" />
              <input type="text" value={seqForm.counterparty} onChange={(e) => setSeqForm({ ...seqForm, counterparty: e.target.value })} placeholder="البيان / الجهة" className="col-span-2 px-2.5 py-2 rounded-xl bg-theme-base border border-theme text-theme-primary" />
              <GlassButton type="submit" variant="primary" disabled={busy} className="col-span-2 justify-center">قيد في السقوفة</GlassButton>
            </form>
            <div className="space-y-2 max-h-64 overflow-y-auto text-xs">
              {seqaofaEntries.slice(0, 20).map((s) => (
                <div key={s.syncUuid} className="p-2.5 rounded-xl bg-theme-base border border-theme flex justify-between items-center">
                  <div>
                    <div className="font-bold text-theme-primary">{s.custodianName} — {s.counterparty}</div>
                    <div className="text-[10px] text-theme-muted">{s.entryDate} • سعر الصرف: {s.exchangeRate} • يعادل {s.equivalentEgp.toLocaleString()} EGP</div>
                  </div>
                  <GlassBadge variant={s.direction === 'وارد' ? 'green' : 'gold'}>
                    {s.direction}: {s.amountSdg.toLocaleString()} SDG
                  </GlassBadge>
                </div>
              ))}
            </div>
          </GlassCard>

          <GlassCard className="p-5 space-y-4">
            <h3 className="text-sm font-black text-theme-primary">بضاعة في الطريق والجمارك (مصر → حلفا / عطبرة)</h3>
            <form onSubmit={handleSaveTransit} className="grid grid-cols-2 sm:grid-cols-3 gap-2.5 text-xs">
              <input type="text" required value={transitForm.invoiceRef} onChange={(e) => setTransitForm({ ...transitForm, invoiceRef: e.target.value })} placeholder="رقم الفاتورة" className="px-2.5 py-2 rounded-xl bg-theme-base border border-theme text-theme-primary" />
              <input type="text" value={transitForm.carrierName} onChange={(e) => setTransitForm({ ...transitForm, carrierName: e.target.value })} placeholder="الناقل (الجبلاوي)" className="px-2.5 py-2 rounded-xl bg-theme-base border border-theme text-theme-primary" />
              <select value={transitForm.status} onChange={(e) => setTransitForm({ ...transitForm, status: e.target.value as InTransitShipment['status'] })} className="px-2.5 py-2 rounded-xl bg-theme-base border border-theme text-theme-primary">
                <option value="في الطريق">في الطريق</option>
                <option value="في الجمارك">في الجمارك</option>
                <option value="تم الاستلام">تم الاستلام</option>
              </select>
              <input type="number" min={0} value={transitForm.cartons116} onChange={(e) => setTransitForm({ ...transitForm, cartons116: e.target.value })} placeholder="كراتين 1/16" className="px-2.5 py-2 rounded-xl bg-theme-base border border-theme text-theme-primary" />
              <input type="number" min={0} value={transitForm.cartons1Kg} onChange={(e) => setTransitForm({ ...transitForm, cartons1Kg: e.target.value })} placeholder="كراتين 1 كيلو" className="px-2.5 py-2 rounded-xl bg-theme-base border border-theme text-theme-primary" />
              <input type="number" min={0} value={transitForm.customsSdg} onChange={(e) => setTransitForm({ ...transitForm, customsSdg: e.target.value })} placeholder="جمارك SDG" className="px-2.5 py-2 rounded-xl bg-theme-base border border-theme text-theme-primary" />
              <GlassButton type="submit" variant="primary" disabled={busy} className="col-span-2 sm:col-span-3 justify-center">تسجيل شحنة عابرة</GlassButton>
            </form>
            <div className="space-y-2 max-h-64 overflow-y-auto text-xs">
              {shipments.slice(0, 20).map((sh) => (
                <div key={sh.syncUuid} className="p-2.5 rounded-xl bg-theme-base border border-theme flex justify-between items-center">
                  <div>
                    <div className="font-bold text-theme-primary">فاتورة {sh.invoiceRef} — {sh.carrierName}</div>
                    <div className="text-[10px] text-theme-muted">1/16: {sh.cartons116} • كيلو: {sh.cartons1Kg} • جمارك: {sh.customsSdg.toLocaleString()} SDG</div>
                  </div>
                  <GlassBadge variant={sh.status === 'تم الاستلام' ? 'green' : sh.status === 'في الجمارك' ? 'gold' : 'cyan'}>{sh.status}</GlassBadge>
                </div>
              ))}
            </div>
          </GlassCard>
        </div>
      )}

      {/* TAB 4: BUDGET CONTROL & TASKS */}
      {tab === 'BUDGET_TASKS' && (
        <div className="grid grid-cols-1 lg:grid-cols-2 gap-5">
          <GlassCard className="p-5 space-y-4">
            <h3 className="text-sm font-black text-theme-primary">الرقابة المالية (المخطط مقابل الفعلي — حد الانحراف 5%)</h3>
            <div className="space-y-2.5 text-xs">
              {budgets.map((b) => {
                const ev = evaluateBudgetDeviation(b.plannedAmount, b.actualAmount, b.maxDeviationPercent);
                return (
                  <div key={b.syncUuid} className="p-3 rounded-xl bg-theme-base border border-theme flex justify-between items-center gap-2">
                    <div>
                      <div className="font-black text-theme-primary">{b.category} ({b.responsibleName})</div>
                      <div className="text-[10px] text-theme-muted">
                        مخطط: {b.plannedAmount.toLocaleString()} | فعلي: {b.actualAmount.toLocaleString()} {b.currency}
                      </div>
                    </div>
                    <GlassBadge variant={ev.exceeded ? 'red' : 'green'}>
                      {ev.deviationPercent}% ({ev.statusLabel})
                    </GlassBadge>
                  </div>
                );
              })}
              {budgets.length === 0 && <div className="text-center py-6 text-theme-muted">لا توجد بنود موازنة مسجلة بعد.</div>}
            </div>
          </GlassCard>

          <GlassCard className="p-5 space-y-4">
            <h3 className="text-sm font-black text-theme-primary">إسناد ومتابعة مهام الموظفين</h3>
            <form onSubmit={handleSaveTask} className="grid grid-cols-2 gap-2.5 text-xs">
              <input type="text" required value={taskForm.title} onChange={(e) => setTaskForm({ ...taskForm, title: e.target.value })} placeholder="عنوان المهمة / التكليف" className="col-span-2 px-2.5 py-2 rounded-xl bg-theme-base border border-theme text-theme-primary" />
              <select value={taskForm.roleKey} onChange={(e) => setTaskForm({ ...taskForm, roleKey: e.target.value as EmployeeRoleKey })} className="px-2.5 py-2 rounded-xl bg-theme-base border border-theme text-theme-primary">
                {ROLE_PRESET_LIST.map((r) => <option key={r.key} value={r.key}>{r.titleAr}</option>)}
              </select>
              <input type="text" value={taskForm.assigneeName} onChange={(e) => setTaskForm({ ...taskForm, assigneeName: e.target.value })} placeholder="اسم المسؤول" className="px-2.5 py-2 rounded-xl bg-theme-base border border-theme text-theme-primary" />
              <input type="text" value={taskForm.indicatorTarget} onChange={(e) => setTaskForm({ ...taskForm, indicatorTarget: e.target.value })} placeholder="المؤشر / الحد" className="px-2.5 py-2 rounded-xl bg-theme-base border border-theme text-theme-primary" />
              <GlassButton type="submit" variant="primary" disabled={busy} className="justify-center">إسناد مهمة</GlassButton>
            </form>
            <div className="space-y-2 max-h-64 overflow-y-auto text-xs">
              {tasks.map((t) => (
                <div key={t.syncUuid} className="p-2.5 rounded-xl bg-theme-base border border-theme flex justify-between items-center">
                  <div>
                    <div className="font-bold text-theme-primary">{t.title}</div>
                    <div className="text-[10px] text-theme-muted">{t.assigneeName} • {t.department} • {t.indicatorTarget}</div>
                  </div>
                  <GlassBadge variant={t.status === 'مكتمل' ? 'green' : t.status === 'متأخر' ? 'red' : 'cyan'}>{t.status}</GlassBadge>
                </div>
              ))}
            </div>
          </GlassCard>
        </div>
      )}

      {/* TAB 5: 3-TIER SEASONAL REWARDS */}
      {tab === 'REWARDS' && (
        <div className="space-y-5">
          <GlassCard className="p-5 space-y-4">
            <h3 className="text-sm font-black text-theme-primary">تقييم المكافآت الموسمية (3 / 4 / 5 شهور من الراتب)</h3>
            <p className="text-xs text-theme-secondary">
              المستوى الأول (3 شهور): إنجاز ≥ 95% وانحراف ≤ 5% وبحد 5 أخطاء | المستوى الثاني (4 شهور): 100% بلا أخطاء | المستوى الثالث (5 شهور): 100% + إضافات تطويرية.
            </p>
            <form onSubmit={handleSaveReward} className="grid grid-cols-2 sm:grid-cols-4 gap-3 text-xs">
              <input type="text" required value={rewardForm.employeeName} onChange={(e) => setRewardForm({ ...rewardForm, employeeName: e.target.value })} placeholder="اسم الموظف *" className="px-2.5 py-2 rounded-xl bg-theme-base border border-theme text-theme-primary" />
              <input type="text" value={rewardForm.roleTitle} onChange={(e) => setRewardForm({ ...rewardForm, roleTitle: e.target.value })} placeholder="الدور الوظيفي" className="px-2.5 py-2 rounded-xl bg-theme-base border border-theme text-theme-primary" />
              <input type="number" min={0} max={100} value={rewardForm.completionPercent} onChange={(e) => setRewardForm({ ...rewardForm, completionPercent: e.target.value })} placeholder="نسبة التنفيذ %" className="px-2.5 py-2 rounded-xl bg-theme-base border border-theme text-theme-primary" />
              <input type="number" min={0} step="0.1" value={rewardForm.deviationPercent} onChange={(e) => setRewardForm({ ...rewardForm, deviationPercent: e.target.value })} placeholder="الانحراف %" className="px-2.5 py-2 rounded-xl bg-theme-base border border-theme text-theme-primary" />
              <input type="number" min={0} value={rewardForm.errorCount} onChange={(e) => setRewardForm({ ...rewardForm, errorCount: e.target.value })} placeholder="عدد الأخطاء" className="px-2.5 py-2 rounded-xl bg-theme-base border border-theme text-theme-primary" />
              <input type="number" min={0} value={rewardForm.innovationCount} onChange={(e) => setRewardForm({ ...rewardForm, innovationCount: e.target.value })} placeholder="إضافات تطويرية" className="px-2.5 py-2 rounded-xl bg-theme-base border border-theme text-theme-primary" />
              <input type="text" value={rewardForm.notes} onChange={(e) => setRewardForm({ ...rewardForm, notes: e.target.value })} placeholder="ملاحظات" className="px-2.5 py-2 rounded-xl bg-theme-base border border-theme text-theme-primary" />
              <GlassButton type="submit" variant="primary" disabled={busy} className="justify-center">احتساب وحفظ التقييم</GlassButton>
            </form>
          </GlassCard>

          <GlassCard className="p-5 overflow-x-auto">
            <table className="w-full text-xs text-right">
              <thead className="border-b border-theme text-theme-muted">
                <tr>
                  <th className="py-2 px-3">الفترة</th>
                  <th className="py-2 px-3">الموظف</th>
                  <th className="py-2 px-3">الدور</th>
                  <th className="py-2 px-3">التنفيذ %</th>
                  <th className="py-2 px-3">الانحراف %</th>
                  <th className="py-2 px-3">الأخطاء</th>
                  <th className="py-2 px-3">التطوير</th>
                  <th className="py-2 px-3">مستوى المكافأة</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-theme/50">
                {rewards.map((r) => (
                  <tr key={r.syncUuid}>
                    <td className="py-2.5 px-3 font-mono">{r.periodKey}</td>
                    <td className="py-2.5 px-3 font-bold">{r.employeeName}</td>
                    <td className="py-2.5 px-3">{r.roleTitle}</td>
                    <td className="py-2.5 px-3 font-bold">{r.completionPercent}%</td>
                    <td className="py-2.5 px-3">{r.deviationPercent}%</td>
                    <td className="py-2.5 px-3">{r.errorCount}</td>
                    <td className="py-2.5 px-3">{r.innovationCount}</td>
                    <td className="py-2.5 px-3">
                      <GlassBadge variant={r.bonusMonths >= 4 ? 'green' : r.bonusMonths === 3 ? 'gold' : 'red'}>{r.bonusTier}</GlassBadge>
                    </td>
                  </tr>
                ))}
                {rewards.length === 0 && (
                  <tr><td colSpan={8} className="text-center py-6 text-theme-muted">لا توجد تقييمات مكافآت محفوظة بعد.</td></tr>
                )}
              </tbody>
            </table>
          </GlassCard>
        </div>
      )}
    </div>
  );
};
