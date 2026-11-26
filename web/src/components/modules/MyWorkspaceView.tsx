import React, { useEffect, useMemo, useState } from 'react';
import {
  AlertTriangle,
  Award,
  CheckCircle2,
  ClipboardCheck,
  Coins,
  Factory,
  FlaskConical,
  Layers,
  PackageCheck,
  Plus,
  RefreshCw,
  ShieldCheck,
  Truck,
  UserCheck,
  Wrench
} from 'lucide-react';
import { GlassCard } from '../ui/GlassCard';
import { GlassBadge } from '../ui/GlassBadge';
import { GlassButton } from '../ui/GlassButton';
import { StatCard } from '../ui/StatCard';
import { useAuth } from '../../context/AuthContext';
import { useLanguage } from '../../context/LanguageContext';
import {
  EMPLOYEE_ROLE_PROFILES,
  ROLE_PRESET_LIST,
  resolveEmployeeRoleProfile
} from '../../config/employeeRoles';
import { ErpDataService } from '../../services/ErpDataService';
import { isSuperAdminAccount, isSystemManager } from '../../services/SecurityService';
import {
  buildDailyChecklistSeedsForRole,
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
  Cashbox,
  DailyChecklistItem,
  EmployeeRewardEvaluation,
  EmployeeRoleKey,
  EmployeeTaskItem,
  FactoryItem,
  FinancialBudgetItem,
  InTransitShipment,
  PressMachine,
  SeqaofaEntry,
  ShiftProductionLog,
  ToolCustodyItem,
  TreasuryMovement
} from '../../types/erp';

interface MyWorkspaceViewProps {
  onNavigateToModule: (moduleId: string) => void;
}

export const MyWorkspaceView: React.FC<MyWorkspaceViewProps> = ({ onNavigateToModule }) => {
  const { userProfile } = useAuth();
  const { language } = useLanguage();

  const isExecutiveViewer = isSuperAdminAccount(userProfile) || isSystemManager(userProfile);
  const naturalProfile = useMemo(() => resolveEmployeeRoleProfile(userProfile), [userProfile]);
  const [previewRoleKey, setPreviewRoleKey] = useState<EmployeeRoleKey>(naturalProfile.key);

  useEffect(() => {
    setPreviewRoleKey(naturalProfile.key);
  }, [naturalProfile.key]);

  const activeProfile = isExecutiveViewer
    ? EMPLOYEE_ROLE_PROFILES[previewRoleKey]
    : naturalProfile;

  const [shiftLogs, setShiftLogs] = useState<ShiftProductionLog[]>([]);
  const [presses, setPresses] = useState<PressMachine[]>([]);
  const [tools, setTools] = useState<ToolCustodyItem[]>([]);
  const [checklists, setChecklists] = useState<DailyChecklistItem[]>([]);
  const [tasks, setTasks] = useState<EmployeeTaskItem[]>([]);
  const [seqaofaEntries, setSeqaofaEntries] = useState<SeqaofaEntry[]>([]);
  const [shipments, setShipments] = useState<InTransitShipment[]>([]);
  const [budgets, setBudgets] = useState<FinancialBudgetItem[]>([]);
  const [rewards, setRewards] = useState<EmployeeRewardEvaluation[]>([]);
  const [factoryItems, setFactoryItems] = useState<FactoryItem[]>([]);
  const [cashboxes, setCashboxes] = useState<Cashbox[]>([]);
  const [movements, setMovements] = useState<TreasuryMovement[]>([]);
  const [notice, setNotice] = useState<{ type: 'ok' | 'err'; text: string } | null>(null);
  const [busy, setBusy] = useState(false);

  // Quick Shift Entry Form (for Packing Engineers / Production / Manager)
  const [quickShift, setQuickShift] = useState({
    lineNo: 1 as 1 | 2 | 3 | 4 | 5,
    batchNo: '',
    productName: 'لاكيه أبيض لامع',
    packageSize: '1/16' as ShiftProductionLog['packageSize'],
    kettleWeightKg: '1000',
    producedCartons: '',
    wasteCans: '0',
    weightErrors: '0',
    stickerErrors: '0',
    technicalDowntimeMin: '0',
    materialDowntimeMin: '0',
    notes: ''
  });

  // Quick Seqaofa Form (for Sudan Branch)
  const [quickSeqaofa, setQuickSeqaofa] = useState({
    custodianName: 'محمد صديق',
    direction: 'وارد' as 'وارد' | 'منصرف',
    amountSdg: '',
    exchangeRate: '183.5',
    counterparty: 'تحويل للأصيل / المصنع',
    notes: ''
  });

  useEffect(() => {
    const unsubs = [
      ErpDataService.subscribeToCollection<ShiftProductionLog>('shift_production_logs', setShiftLogs),
      ErpDataService.subscribeToCollection<PressMachine>('press_machines', setPresses),
      ErpDataService.subscribeToCollection<ToolCustodyItem>('tool_custodies', setTools),
      ErpDataService.subscribeToCollection<DailyChecklistItem>('daily_checklists', setChecklists),
      ErpDataService.subscribeToCollection<EmployeeTaskItem>('employee_tasks', setTasks),
      ErpDataService.subscribeToCollection<SeqaofaEntry>('seqaofa_entries', setSeqaofaEntries),
      ErpDataService.subscribeToCollection<InTransitShipment>('in_transit_shipments', setShipments),
      ErpDataService.subscribeToCollection<FinancialBudgetItem>('financial_budgets', setBudgets),
      ErpDataService.subscribeToCollection<EmployeeRewardEvaluation>('employee_rewards', setRewards),
      ErpDataService.subscribeToCollection<FactoryItem>('factory_items', setFactoryItems),
      ErpDataService.subscribeToCollection<Cashbox>('cashboxes', setCashboxes),
      ErpDataService.subscribeToCollection<TreasuryMovement>('movements', setMovements)
    ];
    return () => unsubs.forEach((u) => u());
  }, []);

  const showNotice = (type: 'ok' | 'err', text: string) => {
    setNotice({ type, text });
    setTimeout(() => setNotice(null), 4000);
  };

  const today = todayDateKey();

  const todayShiftLogs = useMemo(
    () => shiftLogs.filter((l) => l.shiftDate === today),
    [shiftLogs, today]
  );

  const todayCartonsTotal = useMemo(
    () => todayShiftLogs.reduce((s, l) => s + (Number(l.producedCartons) || 0), 0),
    [todayShiftLogs]
  );

  const todayWasteTotal = useMemo(
    () => todayShiftLogs.reduce((s, l) => s + (Number(l.wasteCans) || 0), 0),
    [todayShiftLogs]
  );

  const todayErrorsTotal = useMemo(
    () => todayShiftLogs.reduce((s, l) => s + (Number(l.weightErrors) || 0) + (Number(l.stickerErrors) || 0), 0),
    [todayShiftLogs]
  );

  const criticalRawCount = useMemo(
    () => factoryItems.filter((i) => Number(i.actualBalance || 0) <= Number(i.reorderLevel ?? 10)).length,
    [factoryItems]
  );

  const pressesReadyCount = useMemo(
    () => presses.filter((p) => p.status === 'جاهز').length,
    [presses]
  );

  const missingToolsCount = useMemo(
    () => tools.reduce((s, t) => s + Math.max(0, Number(t.missingQty) || 0), 0),
    [tools]
  );

  const seqaofaSummary = useMemo(() => computeSeqaofaBalances(seqaofaEntries), [seqaofaEntries]);
  const inTransitSummary = useMemo(() => computeInTransitSummary(shipments), [shipments]);

  const roleTasks = useMemo(() => {
    const uName = (userProfile?.username || '').toLowerCase();
    const full = (userProfile?.fullName || '').trim();
    return tasks.filter((t) => {
      if (isExecutiveViewer && previewRoleKey === 'GENERAL_MANAGER') return true;
      if (t.roleKey === activeProfile.key) return true;
      if (uName && t.assigneeUsername && t.assigneeUsername.toLowerCase() === uName) return true;
      if (full && t.assigneeName && t.assigneeName.includes(full)) return true;
      return false;
    });
  }, [tasks, activeProfile.key, isExecutiveViewer, previewRoleKey, userProfile]);

  const todayRoleChecklist = useMemo(() => {
    return checklists.filter((c) => c.dateKey === today && c.roleKey === activeProfile.key);
  }, [checklists, today, activeProfile.key]);

  const myLatestReward = useMemo(() => {
    const period = currentPeriodKey();
    const matched = rewards.find(
      (r) =>
        r.periodKey === period &&
        ((userProfile?.username && r.employeeUsername === userProfile.username) ||
          (userProfile?.fullName && r.employeeName.includes(userProfile.fullName)))
    );
    if (matched) return matched;
    const completed = roleTasks.filter((t) => t.status === 'مكتمل').length;
    const completionPct = roleTasks.length > 0 ? Math.round((completed / roleTasks.length) * 100) : 100;
    const tier = evaluateEmployeeBonusTier({
      completionPercent: completionPct,
      deviationPercent: 0,
      errorCount: activeProfile.key === 'PACKING_ENGINEER' ? todayErrorsTotal : 0,
      innovationCount: 0
    });
    return {
      completionPercent: completionPct,
      bonusTier: tier.bonusTier,
      bonusMonths: tier.bonusMonths
    };
  }, [rewards, userProfile, roleTasks, activeProfile.key, todayErrorsTotal]);

  const distinctCashboxes = useMemo(
    () => ErpDataService.computeDistinctCashboxesWithBalances(cashboxes, movements),
    [cashboxes, movements]
  );

  const handleInitTodayChecklist = async () => {
    if (busy) return;
    setBusy(true);
    try {
      const seeds = buildDailyChecklistSeedsForRole({
        roleKey: activeProfile.key,
        assigneeName: userProfile?.fullName || activeProfile.sampleHolders,
        assigneeUsername: userProfile?.username || activeProfile.key.toLowerCase(),
        branch: userProfile?.branch || activeProfile.defaultBranch,
        dateKey: today
      });
      await Promise.all(seeds.map((item) => ErpDataService.saveRecord('daily_checklists', item.syncUuid, item)));
      showNotice('ok', 'تم تجهيز قائمة المتابعة اليومية لهذا الدور الوظيفي');
    } catch (err: any) {
      showNotice('err', err?.message || 'تعذر تجهيز قائمة المتابعة');
    } finally {
      setBusy(false);
    }
  };

  const handleToggleChecklist = async (item: DailyChecklistItem) => {
    try {
      const next: DailyChecklistItem = {
        ...item,
        completed: !item.completed,
        completedAt: !item.completed ? Date.now() : null,
        updatedAt: Date.now()
      };
      await ErpDataService.saveRecord('daily_checklists', next.syncUuid, next);
    } catch (err: any) {
      showNotice('err', err?.message || 'تعذر تحديث البند');
    }
  };

  const handleCycleTaskStatus = async (task: EmployeeTaskItem) => {
    const order: EmployeeTaskItem['status'][] = ['لم يبدأ', 'جاري', 'مكتمل'];
    const nextIdx = (order.indexOf(task.status) + 1) % order.length;
    const nextStatus = order[nextIdx];
    const nextPct = nextStatus === 'مكتمل' ? 100 : nextStatus === 'جاري' ? 50 : 0;
    try {
      await ErpDataService.saveRecord('employee_tasks', task.syncUuid, {
        ...task,
        status: nextStatus,
        completionPercent: nextPct,
        updatedAt: Date.now()
      });
      showNotice('ok', `تم تحديث حالة المهمة إلى: ${nextStatus}`);
    } catch (err: any) {
      showNotice('err', err?.message || 'تعذر تحديث المهمة');
    }
  };

  const handleQuickShiftSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    const cartons = Number(quickShift.producedCartons) || 0;
    if (cartons <= 0) {
      showNotice('err', 'أدخل عدد الكراتين المنتجة');
      return;
    }
    setBusy(true);
    try {
      const now = Date.now();
      const record: ShiftProductionLog = {
        id: now,
        syncUuid: `SHF-${today}-L${quickShift.lineNo}-${String(now).slice(-4)}`,
        shiftDate: today,
        shiftName: 'الوردية الصباحية',
        lineNo: quickShift.lineNo,
        engineerName: userProfile?.fullName || activeProfile.sampleHolders,
        batchNo: quickShift.batchNo.trim() || `B-${today.replace(/-/g, '').slice(-4)}`,
        productName: quickShift.productName.trim() || 'منتج دهان PMP',
        packageSize: quickShift.packageSize,
        kettleWeightKg: Number(quickShift.kettleWeightKg) || 0,
        targetCartons: Math.round(DAILY_SHIFT_CARTON_TARGET / 5),
        producedCartons: cartons,
        emptyCansUsed: cartons * (quickShift.packageSize === '1/16' ? 48 : quickShift.packageSize === 'جالون' ? 4 : 12),
        emptyCartonsUsed: cartons,
        wasteCans: Number(quickShift.wasteCans) || 0,
        maxWasteAllowed: DEFAULT_MAX_WASTE_CANS_PER_LINE,
        extraCans: 0,
        weightErrors: Number(quickShift.weightErrors) || 0,
        stickerErrors: Number(quickShift.stickerErrors) || 0,
        technicalDowntimeMin: Number(quickShift.technicalDowntimeMin) || 0,
        materialDowntimeMin: Number(quickShift.materialDowntimeMin) || 0,
        workersCount: 6,
        notes: quickShift.notes.trim(),
        branch: userProfile?.branch || 'EG-001 (مصنع مصر والتصدير)',
        createdBy: userProfile?.username || 'packing_eng',
        createdAt: now,
        updatedAt: now
      };
      await ErpDataService.saveRecord('shift_production_logs', record.syncUuid, record);
      setQuickShift((p) => ({ ...p, producedCartons: '', wasteCans: '0', weightErrors: '0', stickerErrors: '0', notes: '' }));
      const evalRes = evaluateShiftLogMetrics(record);
      showNotice(
        evalRes.wasteExceeded ? 'err' : 'ok',
        evalRes.wasteExceeded
          ? `تم التسجيل، تنبيه: هالك خط ${record.lineNo} (${record.wasteCans}) تجاوز الحد المسموح (${record.maxWasteAllowed})!`
          : `تم تسجيل إنتاج خط ${record.lineNo}: ${cartons} كرتونة بنجاح`
      );
    } catch (err: any) {
      showNotice('err', err?.message || 'تعذر حفظ تقرير الوردية');
    } finally {
      setBusy(false);
    }
  };

  const handleQuickSeqaofaSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    const amt = Number(quickSeqaofa.amountSdg) || 0;
    const rate = Number(quickSeqaofa.exchangeRate) || 1;
    if (amt <= 0 || rate <= 0) {
      showNotice('err', 'أدخل المبلغ بالسوداني وسعر الصرف بشكل صحيح');
      return;
    }
    setBusy(true);
    try {
      const now = Date.now();
      const record: SeqaofaEntry = {
        id: now,
        syncUuid: `SEQ-${now}`,
        entryDate: today,
        custodianName: quickSeqaofa.custodianName,
        direction: quickSeqaofa.direction,
        amountSdg: amt,
        exchangeRate: rate,
        equivalentEgp: Math.round((amt / rate) * 100) / 100,
        counterparty: quickSeqaofa.counterparty.trim() || 'حركة سقوفة',
        referenceNo: `SQ-${String(now).slice(-5)}`,
        notes: quickSeqaofa.notes.trim(),
        branch: userProfile?.branch || 'HF-001 (مستودع حلفا اللوجستي)',
        createdBy: userProfile?.username || 'sudan_acc',
        createdAt: now,
        updatedAt: now
      };
      await ErpDataService.saveRecord('seqaofa_entries', record.syncUuid, record);
      setQuickSeqaofa((p) => ({ ...p, amountSdg: '', notes: '' }));
      showNotice('ok', `تم قيد حركة السقوفة (${record.custodianName}) بقيمة ${amt.toLocaleString()} SDG`);
    } catch (err: any) {
      showNotice('err', err?.message || 'تعذر حفظ قيد السقوفة');
    } finally {
      setBusy(false);
    }
  };

  const handleQuickPressToggle = async (press: PressMachine) => {
    const order: PressMachine['status'][] = ['جاهز', 'تحت الصيانة', 'متوقف', 'يحتاج قطع غيار'];
    const nextStatus = order[(order.indexOf(press.status) + 1) % order.length];
    try {
      await ErpDataService.saveRecord('press_machines', press.syncUuid, {
        ...press,
        status: nextStatus,
        updatedBy: userProfile?.fullName || userProfile?.username || 'مشرف الصيانة',
        updatedAt: Date.now()
      });
      showNotice('ok', `تم تحديث ${press.name} إلى: ${nextStatus}`);
    } catch (err: any) {
      showNotice('err', err?.message || 'تعذر تحديث حالة المكبس');
    }
  };

  const handleSeedSeason = async () => {
    if (busy) return;
    setBusy(true);
    try {
      const res = await seedDefaultFactoryOperations(
        userProfile?.fullName || userProfile?.username || 'المدير العام',
        userProfile?.branch || 'EG-001 (مصنع مصر والتصدير)'
      );
      showNotice(
        'ok',
        res.createdCount > 0
          ? `تمت تهيئة ${res.createdCount} سجل تشغيلي (المكابس، العدة، الموازنة، والمهام)`
          : 'البيانات التشغيلية الأساسية مهيأة مسبقاً بالكامل'
      );
    } catch (err: any) {
      showNotice('err', err?.message || 'تعذر تهيئة البيانات الأساسية');
    } finally {
      setBusy(false);
    }
  };

  const targetProgressPct = Math.min(100, Math.round((todayCartonsTotal / DAILY_SHIFT_CARTON_TARGET) * 100));

  return (
    <div className="space-y-5">
      {/* Header + Role Switcher for Executive Admin */}
      <GlassCard className="p-5 bg-gradient-to-l from-theme-surface to-pmp-primary/10">
        <div className="flex flex-col lg:flex-row justify-between items-start lg:items-center gap-4">
          <div className="space-y-1.5">
            <div className="flex flex-wrap items-center gap-2">
              <GlassBadge variant="cyan">{activeProfile.departmentAr}</GlassBadge>
              <GlassBadge variant="gold">{activeProfile.titleAr}</GlassBadge>
              <GlassBadge variant="green">{userProfile?.branch || activeProfile.defaultBranch}</GlassBadge>
            </div>
            <h2 className="text-xl sm:text-2xl font-black text-theme-primary">
              {language === 'ar'
                ? `مكتب العمل المخصص: ${activeProfile.titleAr}`
                : `Role Workspace: ${activeProfile.titleEn}`}
            </h2>
            <p className="text-xs text-theme-secondary max-w-3xl">{activeProfile.summaryAr}</p>
          </div>

          <div className="flex flex-wrap items-center gap-2 shrink-0">
            {isExecutiveViewer && (
              <div className="flex items-center gap-2 bg-theme-base border border-theme rounded-xl px-3 py-1.5">
                <UserCheck className="w-4 h-4 text-pmp-primary shrink-0" />
                <select
                  value={previewRoleKey}
                  onChange={(e) => setPreviewRoleKey(e.target.value as EmployeeRoleKey)}
                  className="bg-transparent text-xs font-bold text-theme-primary focus:outline-none"
                  aria-label="معاينة مكتب الموظف"
                >
                  {ROLE_PRESET_LIST.map((r) => (
                    <option key={r.key} value={r.key} className="bg-theme-surface text-theme-primary">
                      {r.titleAr} ({r.sampleHolders})
                    </option>
                  ))}
                </select>
              </div>
            )}
            {isExecutiveViewer && (
              <GlassButton
                variant="secondary"
                onClick={handleSeedSeason}
                disabled={busy}
                icon={<RefreshCw className={`w-3.5 h-3.5 ${busy ? 'animate-spin' : ''}`} />}
              >
                تهيئة قوالب المصنع
              </GlassButton>
            )}
          </div>
        </div>
      </GlassCard>

      {notice && (
        <div
          className={`p-3 rounded-xl border text-xs font-bold flex items-center gap-2 ${
            notice.type === 'ok'
              ? 'bg-emerald-500/10 border-emerald-500/30 text-emerald-400'
              : 'bg-red-500/10 border-red-500/30 text-red-400'
          }`}
        >
          {notice.type === 'ok' ? <CheckCircle2 className="w-4 h-4 shrink-0" /> : <AlertTriangle className="w-4 h-4 shrink-0" />}
          <span>{notice.text}</span>
        </div>
      )}

      {/* Role-Aware KPI Cards */}
      <div className="grid grid-cols-1 sm:grid-cols-2 xl:grid-cols-4 gap-4">
        <StatCard
          title="إنتاج اليوم / الهدف"
          value={`${todayCartonsTotal.toLocaleString()} / ${DAILY_SHIFT_CARTON_TARGET}`}
          subtitle={`نسبة الإنجاز: ${targetProgressPct}% عبر الـ 5 خطوط`}
          icon={<Factory className="w-5 h-5" />}
          badgeVariant={targetProgressPct >= 80 ? 'green' : 'gold'}
        />
        <StatCard
          title="التوالف وأخطاء اليوم"
          value={`${todayWasteTotal} عبوة`}
          subtitle={`أخطاء الوزن والاستيكر: ${todayErrorsTotal} (الحد 25/خط)`}
          icon={<AlertTriangle className="w-5 h-5" />}
          badgeVariant={todayWasteTotal > DEFAULT_MAX_WASTE_CANS_PER_LINE * 5 ? 'red' : 'cyan'}
        />
        <StatCard
          title="الخامات والمكابس والعدة"
          value={`${pressesReadyCount}/${presses.length || 0} مكبس جاهز`}
          subtitle={`خامات عند حد الطلب: ${criticalRawCount} • عجز عدة: ${missingToolsCount}`}
          icon={<Wrench className="w-5 h-5" />}
          badgeVariant={criticalRawCount > 0 || missingToolsCount > 0 ? 'gold' : 'green'}
        />
        <StatCard
          title="مؤشر المكافأة الموسمية"
          value={myLatestReward.bonusTier}
          subtitle={`إنجاز المهام: ${myLatestReward.completionPercent}%`}
          icon={<Award className="w-5 h-5" />}
          badgeVariant={myLatestReward.bonusMonths >= 4 ? 'green' : myLatestReward.bonusMonths === 3 ? 'gold' : 'red'}
        />
      </div>

      {/* Main Role Grid: Focus Modules + Interactive Daily Checklist */}
      <div className="grid grid-cols-1 lg:grid-cols-12 gap-5">
        {/* Left: Role-Specific Assigned Modules & Quick Operational Form */}
        <div className="lg:col-span-7 space-y-5">
          <GlassCard className="p-5 space-y-4">
            <div className="flex items-center justify-between gap-2">
              <h3 className="text-sm font-black text-theme-primary flex items-center gap-2">
                <Layers className="w-4 h-4 text-pmp-primary" />
                <span>أقسامك المخصصة في دولاب العمل</span>
              </h3>
              <GlassBadge variant="cyan">{activeProfile.focusModules.length} أقسام</GlassBadge>
            </div>
            <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
              {activeProfile.focusModules.map((mod) => (
                <button
                  key={`${mod.moduleId}_${mod.labelAr}`}
                  type="button"
                  onClick={() => onNavigateToModule(mod.moduleId)}
                  className="text-right p-3.5 rounded-xl bg-theme-base border border-theme hover:border-pmp-primary/60 transition-all space-y-1 group"
                >
                  <div className="text-xs font-black text-theme-primary group-hover:text-pmp-primary transition-colors">
                    {mod.labelAr}
                  </div>
                  <div className="text-[11px] text-theme-secondary leading-relaxed">{mod.descriptionAr}</div>
                </button>
              ))}
            </div>
          </GlassCard>

          {/* Role-Specific Quick Action Widget */}
          {(activeProfile.key === 'PACKING_ENGINEER' ||
            activeProfile.key === 'PRODUCTION_MANAGER' ||
            activeProfile.key === 'GENERAL_MANAGER') && (
            <GlassCard className="p-5 space-y-4">
              <div className="flex items-center justify-between">
                <h3 className="text-sm font-black text-theme-primary flex items-center gap-2">
                  <Factory className="w-4 h-4 text-pmp-primary" />
                  <span>تسجيل إنتاج نهاية الوردية السريع (خطوط 1–5)</span>
                </h3>
                <GlassBadge variant="gold">حد التوالف ≤ 25 عبوة/خط</GlassBadge>
              </div>
              <form onSubmit={handleQuickShiftSubmit} className="grid grid-cols-2 sm:grid-cols-4 gap-3 text-xs">
                <div>
                  <label className="block font-bold text-theme-secondary mb-1">الخط</label>
                  <select
                    value={quickShift.lineNo}
                    onChange={(e) => setQuickShift({ ...quickShift, lineNo: Number(e.target.value) as 1 | 2 | 3 | 4 | 5 })}
                    className="w-full px-2.5 py-2 rounded-xl bg-theme-base border border-theme text-theme-primary"
                  >
                    {[1, 2, 3, 4, 5].map((n) => (
                      <option key={n} value={n}>خط {n}</option>
                    ))}
                  </select>
                </div>
                <div>
                  <label className="block font-bold text-theme-secondary mb-1">العبوة</label>
                  <select
                    value={quickShift.packageSize}
                    onChange={(e) => setQuickShift({ ...quickShift, packageSize: e.target.value })}
                    className="w-full px-2.5 py-2 rounded-xl bg-theme-base border border-theme text-theme-primary"
                  >
                    <option value="1/16">1/16 (48 عبوة)</option>
                    <option value="1 كيلو">1 كيلو (12 عبوة)</option>
                    <option value="جالون">جالون (4 عبوات)</option>
                    <option value="مهندسين">مهندسين</option>
                  </select>
                </div>
                <div>
                  <label className="block font-bold text-theme-secondary mb-1">اللون / الصنف</label>
                  <input
                    type="text"
                    value={quickShift.productName}
                    onChange={(e) => setQuickShift({ ...quickShift, productName: e.target.value })}
                    className="w-full px-2.5 py-2 rounded-xl bg-theme-base border border-theme text-theme-primary"
                  />
                </div>
                <div>
                  <label className="block font-bold text-theme-secondary mb-1">رقم الباتشة</label>
                  <input
                    type="text"
                    value={quickShift.batchNo}
                    onChange={(e) => setQuickShift({ ...quickShift, batchNo: e.target.value })}
                    placeholder="B-101"
                    className="w-full px-2.5 py-2 rounded-xl bg-theme-base border border-theme text-theme-primary"
                  />
                </div>
                <div>
                  <label className="block font-bold text-theme-secondary mb-1">الكراتين المنتجة *</label>
                  <input
                    type="number"
                    min={1}
                    required
                    value={quickShift.producedCartons}
                    onChange={(e) => setQuickShift({ ...quickShift, producedCartons: e.target.value })}
                    placeholder="200"
                    className="w-full px-2.5 py-2 rounded-xl bg-theme-base border border-theme text-theme-primary"
                  />
                </div>
                <div>
                  <label className="block font-bold text-theme-secondary mb-1">التوالف (عبوة)</label>
                  <input
                    type="number"
                    min={0}
                    value={quickShift.wasteCans}
                    onChange={(e) => setQuickShift({ ...quickShift, wasteCans: e.target.value })}
                    className="w-full px-2.5 py-2 rounded-xl bg-theme-base border border-theme text-theme-primary"
                  />
                </div>
                <div>
                  <label className="block font-bold text-theme-secondary mb-1">أخطاء وزن/استيكر</label>
                  <input
                    type="number"
                    min={0}
                    value={quickShift.stickerErrors}
                    onChange={(e) => setQuickShift({ ...quickShift, stickerErrors: e.target.value })}
                    className="w-full px-2.5 py-2 rounded-xl bg-theme-base border border-theme text-theme-primary"
                  />
                </div>
                <div className="flex items-end">
                  <GlassButton type="submit" variant="primary" disabled={busy} className="w-full justify-center" icon={<Plus className="w-4 h-4" />}>
                    حفظ الوردية
                  </GlassButton>
                </div>
              </form>
            </GlassCard>
          )}

          {activeProfile.key === 'SUDAN_BRANCH_MANAGER' && (
            <GlassCard className="p-5 space-y-4">
              <div className="flex items-center justify-between flex-wrap gap-2">
                <h3 className="text-sm font-black text-theme-primary flex items-center gap-2">
                  <Coins className="w-4 h-4 text-pmp-primary" />
                  <span>ملخص السقوفة وبضاعة في الطريق (السودان)</span>
                </h3>
                <div className="flex gap-2">
                  <GlassBadge variant="cyan">في الطريق: {inTransitSummary.pendingCount} شحنة</GlassBadge>
                  <GlassBadge variant="gold">
                    {inTransitSummary.totalCartons116 + inTransitSummary.totalCartons1Kg + inTransitSummary.totalCartonsGallon} كرتونة عابرة
                  </GlassBadge>
                </div>
              </div>

              {seqaofaSummary.length > 0 && (
                <div className="grid grid-cols-1 sm:grid-cols-2 gap-2.5 text-xs">
                  {seqaofaSummary.map((c) => (
                    <div key={c.custodianName} className="p-3 rounded-xl bg-theme-base border border-theme flex justify-between items-center">
                      <div>
                        <div className="font-black text-theme-primary">{c.custodianName}</div>
                        <div className="text-[10px] text-theme-muted">وارد: {c.inflowSdg.toLocaleString()} • منصرف: {c.outflowSdg.toLocaleString()}</div>
                      </div>
                      <div className="text-left font-black text-emerald-400">
                        {c.balanceSdg.toLocaleString()} SDG
                      </div>
                    </div>
                  ))}
                </div>
              )}

              <form onSubmit={handleQuickSeqaofaSubmit} className="grid grid-cols-2 sm:grid-cols-5 gap-2.5 text-xs">
                <select
                  value={quickSeqaofa.custodianName}
                  onChange={(e) => setQuickSeqaofa({ ...quickSeqaofa, custodianName: e.target.value })}
                  className="px-2.5 py-2 rounded-xl bg-theme-base border border-theme text-theme-primary"
                >
                  <option value="محمد صديق">محمد صديق</option>
                  <option value="عمر عبدالوهاب">عمر عبدالوهاب</option>
                </select>
                <select
                  value={quickSeqaofa.direction}
                  onChange={(e) => setQuickSeqaofa({ ...quickSeqaofa, direction: e.target.value as 'وارد' | 'منصرف' })}
                  className="px-2.5 py-2 rounded-xl bg-theme-base border border-theme text-theme-primary"
                >
                  <option value="وارد">وارد</option>
                  <option value="منصرف">منصرف</option>
                </select>
                <input
                  type="number"
                  required
                  min={1}
                  value={quickSeqaofa.amountSdg}
                  onChange={(e) => setQuickSeqaofa({ ...quickSeqaofa, amountSdg: e.target.value })}
                  placeholder="المبلغ SDG"
                  className="px-2.5 py-2 rounded-xl bg-theme-base border border-theme text-theme-primary"
                />
                <input
                  type="number"
                  step="0.1"
                  required
                  value={quickSeqaofa.exchangeRate}
                  onChange={(e) => setQuickSeqaofa({ ...quickSeqaofa, exchangeRate: e.target.value })}
                  placeholder="سعر الصرف"
                  className="px-2.5 py-2 rounded-xl bg-theme-base border border-theme text-theme-primary"
                />
                <GlassButton type="submit" variant="primary" disabled={busy} className="justify-center">
                  قيد سقوفة
                </GlassButton>
              </form>
            </GlassCard>
          )}

          {activeProfile.key === 'LOADING_TOOLS_SUPERVISOR' && (
            <GlassCard className="p-5 space-y-3">
              <div className="flex items-center justify-between">
                <h3 className="text-sm font-black text-theme-primary flex items-center gap-2">
                  <Truck className="w-4 h-4 text-pmp-primary" />
                  <span>بوابة التحميل بعد الوردية وحالة المكابس</span>
                </h3>
                <GlassBadge variant="red">ممنوع التحميل أثناء الوردية الرسمية</GlassBadge>
              </div>
              <div className="grid grid-cols-1 sm:grid-cols-2 gap-2.5 text-xs">
                {presses.map((p) => (
                  <div key={p.syncUuid} className="p-3 rounded-xl bg-theme-base border border-theme flex items-center justify-between gap-2">
                    <div>
                      <div className="font-black text-theme-primary">{p.name}</div>
                      <div className="text-[10px] text-theme-muted">{p.location}</div>
                    </div>
                    <button
                      type="button"
                      onClick={() => handleQuickPressToggle(p)}
                      className="px-2.5 py-1 rounded-lg bg-pmp-primary/15 border border-pmp-primary/30 text-pmp-primary font-bold hover:bg-pmp-primary/25"
                    >
                      {p.status}
                    </button>
                  </div>
                ))}
                {presses.length === 0 && (
                  <div className="col-span-2 text-center py-4 text-theme-muted">
                    لا توجد مكابس مسجلة بعد — اضغط "تهيئة قوالب المصنع" أو أضفها من تبويب التشغيل.
                  </div>
                )}
              </div>
            </GlassCard>
          )}

          {(activeProfile.key === 'FINANCE_MANAGER_EGYPT' || activeProfile.key === 'QUALITY_PROCUREMENT_MANAGER' || activeProfile.key === 'WAREHOUSE_KEEPER_EGYPT') && (
            <GlassCard className="p-5 space-y-3">
              <h3 className="text-sm font-black text-theme-primary flex items-center gap-2">
                {activeProfile.key === 'FINANCE_MANAGER_EGYPT' ? (
                  <Coins className="w-4 h-4 text-pmp-primary" />
                ) : activeProfile.key === 'QUALITY_PROCUREMENT_MANAGER' ? (
                  <FlaskConical className="w-4 h-4 text-pmp-primary" />
                ) : (
                  <PackageCheck className="w-4 h-4 text-pmp-primary" />
                )}
                <span>
                  {activeProfile.key === 'FINANCE_MANAGER_EGYPT'
                    ? 'مؤشرات الرقابة المالية (حد الانحراف 5%) والخزائن'
                    : activeProfile.key === 'QUALITY_PROCUREMENT_MANAGER'
                    ? 'خامات وصلت لحد الطلب وتنبيهات الشراء'
                    : 'حالة المخازن والالتزام اليومي'}
                </span>
              </h3>
              {activeProfile.key === 'FINANCE_MANAGER_EGYPT' ? (
                <div className="space-y-2 text-xs">
                  {budgets.map((b) => {
                    const ev = evaluateBudgetDeviation(b.plannedAmount, b.actualAmount, b.maxDeviationPercent);
                    return (
                      <div key={b.syncUuid} className="p-3 rounded-xl bg-theme-base border border-theme flex items-center justify-between gap-2">
                        <div>
                          <div className="font-bold text-theme-primary">{b.category} — {b.responsibleName}</div>
                          <div className="text-[10px] text-theme-muted">
                            المخطط: {b.plannedAmount.toLocaleString()} • الفعلي: {b.actualAmount.toLocaleString()} {b.currency}
                          </div>
                        </div>
                        <GlassBadge variant={ev.exceeded ? 'red' : 'green'}>
                          انحراف {ev.deviationPercent}%
                        </GlassBadge>
                      </div>
                    );
                  })}
                  {distinctCashboxes.slice(0, 4).map((cb) => (
                    <div key={cb.name} className="p-2.5 rounded-xl bg-theme-base border border-theme flex justify-between">
                      <span className="font-bold text-theme-primary">{cb.name}</span>
                      <span className="font-black text-pmp-cyan">{cb.primaryBalance.toLocaleString()} {cb.primaryCurrency}</span>
                    </div>
                  ))}
                </div>
              ) : (
                <div className="space-y-2 text-xs">
                  {factoryItems
                    .filter((i) => Number(i.actualBalance || 0) <= Number(i.reorderLevel ?? 10))
                    .slice(0, 5)
                    .map((item) => (
                      <div key={item.id} className="p-3 rounded-xl bg-amber-500/10 border border-amber-500/30 flex justify-between items-center">
                        <span className="font-bold text-theme-primary">{item.name}</span>
                        <span className="font-black text-amber-400">
                          الرصيد: {item.actualBalance || 0} (حد الطلب: {item.reorderLevel ?? 10})
                        </span>
                      </div>
                    ))}
                  {criticalRawCount === 0 && (
                    <div className="p-3 rounded-xl bg-emerald-500/10 border border-emerald-500/30 text-emerald-400 font-bold flex items-center gap-2">
                      <ShieldCheck className="w-4 h-4" />
                      <span>جميع الخامات المسجلة أعلى من حد الطلب المعتمد.</span>
                    </div>
                  )}
                </div>
              )}
            </GlassCard>
          )}
        </div>

        {/* Right: Daily Checklist + Assigned Tasks */}
        <div className="lg:col-span-5 space-y-5">
          <GlassCard className="p-5 space-y-3">
            <div className="flex items-center justify-between gap-2">
              <h3 className="text-sm font-black text-theme-primary flex items-center gap-2">
                <ClipboardCheck className="w-4 h-4 text-pmp-primary" />
                <span>قائمة المتابعة اليومية ({today})</span>
              </h3>
              <GlassButton variant="secondary" onClick={handleInitTodayChecklist} disabled={busy}>
                تجهيز اليوم
              </GlassButton>
            </div>

            {todayRoleChecklist.length === 0 ? (
              <div className="p-4 rounded-xl bg-theme-base border border-theme text-xs text-theme-secondary space-y-2">
                <p>لم يتم توليد قائمة متابعة اليوم لهذا الدور بعد. البنود القياسية:</p>
                <ul className="list-disc pr-5 space-y-1">
                  {activeProfile.dailyChecklist.map((c, idx) => (
                    <li key={idx}>
                      <span className="font-bold text-theme-primary">{c.title}</span> ({c.metricTarget})
                    </li>
                  ))}
                </ul>
              </div>
            ) : (
              <div className="space-y-2">
                {todayRoleChecklist.map((chk) => (
                  <button
                    key={chk.syncUuid}
                    type="button"
                    onClick={() => handleToggleChecklist(chk)}
                    className={`w-full text-right p-3 rounded-xl border transition-all flex items-start justify-between gap-3 text-xs ${
                      chk.completed
                        ? 'bg-emerald-500/10 border-emerald-500/30 text-theme-primary'
                        : 'bg-theme-base border-theme text-theme-primary hover:border-pmp-primary/50'
                    }`}
                  >
                    <div className="space-y-0.5">
                      <div className={`font-bold ${chk.completed ? 'line-through text-emerald-400' : ''}`}>{chk.title}</div>
                      <div className="text-[10px] text-theme-muted">المعيار: {chk.metricTarget}</div>
                    </div>
                    <GlassBadge variant={chk.completed ? 'green' : 'gold'}>
                      {chk.completed ? 'تم اليوم' : 'قيد التنفيذ'}
                    </GlassBadge>
                  </button>
                ))}
              </div>
            )}
          </GlassCard>

          <GlassCard className="p-5 space-y-3">
            <div className="flex items-center justify-between gap-2">
              <h3 className="text-sm font-black text-theme-primary">المهام المسندة ({roleTasks.length})</h3>
              <button
                type="button"
                onClick={() => onNavigateToModule('OPERATIONS')}
                className="text-[11px] font-bold text-pmp-primary hover:underline"
              >
                فتح سجل العمليات الكامل
              </button>
            </div>
            <div className="space-y-2 max-h-80 overflow-y-auto">
              {roleTasks.map((t) => (
                <div
                  key={t.syncUuid}
                  className="p-3 rounded-xl bg-theme-base border border-theme flex items-start justify-between gap-2 text-xs"
                >
                  <div className="space-y-0.5 min-w-0">
                    <div className="font-bold text-theme-primary">{t.title}</div>
                    <div className="text-[10px] text-theme-muted">
                      {t.assigneeName} • المؤشر: {t.indicatorTarget} • الاستحقاق: {t.dueDate}
                    </div>
                  </div>
                  <button
                    type="button"
                    onClick={() => handleCycleTaskStatus(t)}
                    className="shrink-0"
                    title="اضغط لتغيير الحالة"
                  >
                    <GlassBadge
                      variant={
                        t.status === 'مكتمل' ? 'green' : t.status === 'متأخر' ? 'red' : t.status === 'جاري' ? 'cyan' : 'gold'
                      }
                    >
                      {t.status} ({t.completionPercent}%)
                    </GlassBadge>
                  </button>
                </div>
              ))}
              {roleTasks.length === 0 && (
                <div className="text-center py-4 text-xs text-theme-muted">لا توجد مهام مسجلة لهذا القسم حالياً.</div>
              )}
            </div>
          </GlassCard>
        </div>
      </div>
    </div>
  );
};
