import {
  DailyChecklistItem,
  EmployeeRewardEvaluation,
  EmployeeRoleKey,
  EmployeeTaskItem,
  FinancialBudgetItem,
  InTransitShipment,
  PressMachine,
  SeqaofaEntry,
  ShiftProductionLog,
  ToolCustodyItem
} from '../types/erp';
import { EMPLOYEE_ROLE_PROFILES } from '../config/employeeRoles';
import { ErpDataService } from './ErpDataService';

export const DAILY_SHIFT_CARTON_TARGET = 1000;
export const DEFAULT_MAX_WASTE_CANS_PER_LINE = 25;
export const MAX_FINANCIAL_DEVIATION_PERCENT = 5;

export function todayDateKey(): string {
  return new Date().toISOString().split('T')[0];
}

export function currentPeriodKey(): string {
  return new Date().toISOString().slice(0, 7);
}

export function evaluateShiftLogMetrics(log: Pick<ShiftProductionLog, 'targetCartons' | 'producedCartons' | 'wasteCans' | 'maxWasteAllowed' | 'technicalDowntimeMin' | 'materialDowntimeMin'>) {
  const target = Math.max(1, Number(log.targetCartons) || DAILY_SHIFT_CARTON_TARGET);
  const produced = Math.max(0, Number(log.producedCartons) || 0);
  const waste = Math.max(0, Number(log.wasteCans) || 0);
  const maxWaste = Math.max(1, Number(log.maxWasteAllowed) || DEFAULT_MAX_WASTE_CANS_PER_LINE);
  const achievementPercent = Math.round((produced / target) * 1000) / 10;
  const wasteExceeded = waste > maxWaste;
  const wasteDiff = waste - maxWaste;
  const totalDowntimeMin = Math.max(0, Number(log.technicalDowntimeMin) || 0) + Math.max(0, Number(log.materialDowntimeMin) || 0);

  return {
    achievementPercent,
    wasteExceeded,
    wasteDiff,
    totalDowntimeMin,
    statusLabel: wasteExceeded ? 'تجاوز حد التوالف' : achievementPercent >= 95 ? 'ممتاز' : 'ضمن المتابعة'
  };
}

export function evaluateBudgetDeviation(plannedAmount: number, actualAmount: number, maxDeviationPercent = MAX_FINANCIAL_DEVIATION_PERCENT) {
  const planned = Number(plannedAmount) || 0;
  const actual = Number(actualAmount) || 0;
  const varianceAmount = actual - planned;
  const deviationPercent = planned > 0 ? Math.round((Math.abs(varianceAmount) / planned) * 1000) / 10 : 0;
  const exceeded = planned > 0 && deviationPercent > maxDeviationPercent;
  return {
    varianceAmount,
    deviationPercent,
    exceeded,
    statusLabel: exceeded ? 'انحراف متجاوز للحد (5%)' : 'ضمن الموازنة المعتمدة'
  };
}

/**
 * Evaluates the 3-tier seasonal reward structure from `شروط المكافئة.pdf`:
 * - Level 1 (3 months): completion >= 95%, deviation <= 5%, errors <= 5
 * - Level 2 (4 months): completion == 100%, errors == 0
 * - Level 3 (5 months): completion == 100%, errors == 0, innovationCount >= 1
 */
export function evaluateEmployeeBonusTier(params: {
  completionPercent: number;
  deviationPercent: number;
  errorCount: number;
  innovationCount: number;
}): {
  bonusTier: EmployeeRewardEvaluation['bonusTier'];
  bonusMonths: 0 | 3 | 4 | 5;
} {
  const completion = Number(params.completionPercent) || 0;
  const deviation = Math.abs(Number(params.deviationPercent) || 0);
  const errors = Math.max(0, Math.floor(Number(params.errorCount) || 0));
  const innovations = Math.max(0, Math.floor(Number(params.innovationCount) || 0));

  if (completion >= 100 && errors === 0 && innovations >= 1) {
    return { bonusTier: 'المستوى الثالث (5 شهور)', bonusMonths: 5 };
  }
  if (completion >= 100 && errors === 0) {
    return { bonusTier: 'المستوى الثاني (4 شهور)', bonusMonths: 4 };
  }
  if (completion >= 95 && deviation <= MAX_FINANCIAL_DEVIATION_PERCENT && errors <= 5) {
    return { bonusTier: 'المستوى الأول (3 شهور)', bonusMonths: 3 };
  }
  return { bonusTier: 'غير مستوفٍ للشرط', bonusMonths: 0 };
}

export function computeSeqaofaBalances(entries: SeqaofaEntry[]) {
  const map = new Map<string, { custodianName: string; inflowSdg: number; outflowSdg: number; balanceSdg: number; totalEgp: number }>();
  for (const e of entries) {
    const key = (e.custodianName || 'غير محدد').trim();
    const cur = map.get(key) || { custodianName: key, inflowSdg: 0, outflowSdg: 0, balanceSdg: 0, totalEgp: 0 };
    const amt = Number(e.amountSdg) || 0;
    if (e.direction === 'وارد') {
      cur.inflowSdg += amt;
      cur.balanceSdg += amt;
    } else {
      cur.outflowSdg += amt;
      cur.balanceSdg -= amt;
    }
    cur.totalEgp += Number(e.equivalentEgp) || 0;
    map.set(key, cur);
  }
  return Array.from(map.values());
}

export function computeInTransitSummary(shipments: InTransitShipment[]) {
  const pending = shipments.filter((s) => s.status !== 'تم الاستلام');
  return {
    pendingCount: pending.length,
    receivedCount: shipments.length - pending.length,
    totalCartons116: pending.reduce((s, r) => s + (Number(r.cartons116) || 0), 0),
    totalCartons1Kg: pending.reduce((s, r) => s + (Number(r.cartons1Kg) || 0), 0),
    totalCartonsGallon: pending.reduce((s, r) => s + (Number(r.cartonsGallon) || 0), 0),
    totalCustomsSdg: pending.reduce((s, r) => s + (Number(r.customsSdg) || 0), 0),
    totalFreightEgp: pending.reduce((s, r) => s + (Number(r.freightEgp) || 0), 0)
  };
}

export function buildDailyChecklistSeedsForRole(params: {
  roleKey: EmployeeRoleKey;
  assigneeName: string;
  assigneeUsername: string;
  branch: string;
  dateKey?: string;
}): DailyChecklistItem[] {
  const dateKey = params.dateKey || todayDateKey();
  const profile = EMPLOYEE_ROLE_PROFILES[params.roleKey];
  const now = Date.now();
  return profile.dailyChecklist.map((tpl, idx) => {
    const id = now + idx + Math.floor(Math.random() * 100);
    return {
      id,
      syncUuid: `CHK-${dateKey}-${params.roleKey}-${idx + 1}-${(params.assigneeUsername || 'emp').replace(/[^a-zA-Z0-9_-]/g, '')}`,
      dateKey,
      roleKey: params.roleKey,
      assigneeName: params.assigneeName,
      assigneeUsername: params.assigneeUsername,
      title: tpl.title,
      metricTarget: tpl.metricTarget,
      completed: false,
      completedAt: null,
      note: '',
      branch: params.branch || profile.defaultBranch,
      createdAt: now,
      updatedAt: now
    };
  });
}

export async function seedDefaultFactoryOperations(actorName: string, branch = 'EG-001 (مصنع مصر والتصدير)'): Promise<{ createdCount: number }> {
  const now = Date.now();
  const today = todayDateKey();
  const period = currentPeriodKey();
  let createdCount = 0;

  const existingPresses = await ErpDataService.fetchAll<PressMachine>('press_machines');
  if (existingPresses.length === 0) {
    const presses: PressMachine[] = [1, 2, 3, 4].map((num) => ({
      id: now + num,
      syncUuid: `PRESS-${num}`,
      pressCode: `PRS-0${num}`,
      name: `مكبس ${num} (${num <= 2 ? 'الخط الرئيسي' : 'التجهيز الجديد'})`,
      location: 'صالة التعبئة - مصنع بدر',
      status: num <= 3 ? 'جاهز' : 'تحت الصيانة',
      lastMaintenanceDate: today,
      nextMaintenanceDate: today,
      assignedSupervisor: 'منذر / أ. الطلخاوي',
      notes: 'مهيأ لخطة الموسم 2026-2027',
      branch,
      updatedBy: actorName,
      createdAt: now,
      updatedAt: now
    }));
    await Promise.all(presses.map((p) => ErpDataService.saveRecord('press_machines', p.syncUuid, p)));
    createdCount += presses.length;
  }

  const existingTools = await ErpDataService.fetchAll<ToolCustodyItem>('tool_custodies');
  if (existingTools.length === 0) {
    const tools: ToolCustodyItem[] = [
      { toolName: 'صندوق العدة الميكانيكية الكامل', qty: 1, code: 'TOOL-01' },
      { toolName: 'مفاتيح ربط وصيانة المكابس', qty: 12, code: 'TOOL-02' },
      { toolName: 'ميزان إلكتروني لخطوط التعبئة', qty: 3, code: 'TOOL-03' }
    ].map((t, idx) => ({
      id: now + 100 + idx,
      syncUuid: t.code,
      custodyNo: t.code,
      toolName: t.toolName,
      deliveredQty: t.qty,
      deliveryCondition: 'سليم وكامل',
      custodianName: 'منذر مزمل',
      deliveryDate: today,
      monthlyAuditStatus: 'مطابق',
      countedQty: t.qty,
      missingQty: 0,
      actionTaken: '',
      notes: 'عهدة معتمدة حسب خطة الموسم',
      branch,
      updatedBy: actorName,
      createdAt: now,
      updatedAt: now
    }));
    await Promise.all(tools.map((t) => ErpDataService.saveRecord('tool_custodies', t.syncUuid, t)));
    createdCount += tools.length;
  }

  const existingBudgets = await ErpDataService.fetchAll<FinancialBudgetItem>('financial_budgets');
  if (existingBudgets.length === 0) {
    const budgets: FinancialBudgetItem[] = [
      { category: 'الخطة المالية', responsibleName: 'أ. أحمد الطلخاوي', planned: 5000000, actual: 4850000, currency: 'EGP', code: `BUD-FIN-${period}` },
      { category: 'خطة النولون', responsibleName: 'م. رانيا سمير', planned: 250000, actual: 240000, currency: 'EGP', code: `BUD-NOL-${period}` },
      { category: 'الرواتب', responsibleName: 'أ. أحمد الطلخاوي', planned: 386000, actual: 386000, currency: 'EGP', code: `BUD-PAY-${period}` }
    ].map((b, idx) => ({
      id: now + 200 + idx,
      syncUuid: b.code,
      periodKey: period,
      category: b.category,
      responsibleName: b.responsibleName,
      plannedAmount: b.planned,
      actualAmount: b.actual,
      currency: b.currency as 'EGP',
      maxDeviationPercent: MAX_FINANCIAL_DEVIATION_PERCENT,
      notes: 'مؤشر رقابة مالية بحد انحراف 5%',
      branch,
      updatedBy: actorName,
      createdAt: now,
      updatedAt: now
    }));
    await Promise.all(budgets.map((b) => ErpDataService.saveRecord('financial_budgets', b.syncUuid, b)));
    createdCount += budgets.length;
  }

  const existingTasks = await ErpDataService.fetchAll<EmployeeTaskItem>('employee_tasks');
  if (existingTasks.length === 0) {
    const defaultTasks: Array<{ title: string; dept: string; assignee: string; roleKey: EmployeeRoleKey; indicator: string; code: string }> = [
      { title: 'استلام الصاج وعجز الغطاء والكرتون وتوثيق التوالف', dept: 'المخازن', assignee: 'إبراهيم مبارك', roleKey: 'WAREHOUSE_KEEPER_EGYPT', indicator: 'مطابقة يومية', code: 'TSK-IBRAHIM-01' },
      { title: 'التحميل بعد انتهاء الوردية وإدارة صندوق العدة', dept: 'الإشراف', assignee: 'منذر مزمل', roleKey: 'LOADING_TOOLS_SUPERVISOR', indicator: '0 تحميل أثناء الوردية', code: 'TSK-MONZER-01' },
      { title: 'الإشراف على 3 خطوط تعبئة وضبط الهالك ≤ 25 عبوة', dept: 'التعبئة والجودة', assignee: 'م. محمد عبدالباسط', roleKey: 'PACKING_ENGINEER', indicator: '≤ 25 عبوة/خط', code: 'TSK-ABDELBASET-01' },
      { title: 'الإشراف على خطي تعبئة وضبط الوزن والاستيكر', dept: 'التعبئة والجودة', assignee: 'م. زياد محمد', roleKey: 'PACKING_ENGINEER', indicator: '≤ 25 عبوة/خط', code: 'TSK-ZIAD-01' },
      { title: 'إدارة الألكيد وتسليم حلة الصناعة مطابقة للأمر', dept: 'الإنتاج', assignee: 'م. مصطفى الصيفي', roleKey: 'PRODUCTION_MANAGER', indicator: 'وزن مطابق 100%', code: 'TSK-MOSTAFA-01' },
      { title: 'تأمين الخامات وحد الطلب وسحب عينات الباتشات', dept: 'الجودة والمشتريات', assignee: 'م. رانيا سمير', roleKey: 'QUALITY_PROCUREMENT_MANAGER', indicator: 'خلال 24 ساعة', code: 'TSK-RANIA-01' },
      { title: 'الرقابة المالية ومطابقة الانحراف ≤ 5%', dept: 'المالية', assignee: 'أ. أحمد الطلخاوي', roleKey: 'FINANCE_MANAGER_EGYPT', indicator: 'انحراف ≤ 5%', code: 'TSK-TALKHAWY-01' },
      { title: 'متابعة خزنة السودان والسقوفة وتحويل العملة للمصنع', dept: 'فرع السودان', assignee: 'عمر عبدالوهاب / محمد صديق', roleKey: 'SUDAN_BRANCH_MANAGER', indicator: 'مطابقة يومية', code: 'TSK-SUDAN-01' }
    ];
    const records: EmployeeTaskItem[] = defaultTasks.map((t, idx) => ({
      id: now + 300 + idx,
      syncUuid: t.code,
      title: t.title,
      department: t.dept,
      assigneeName: t.assignee,
      assigneeUsername: '',
      roleKey: t.roleKey,
      priority: 'عالية',
      startDate: today,
      dueDate: today,
      status: 'جاري',
      completionPercent: 50,
      indicatorTarget: t.indicator,
      notes: 'مهمة أساسية ضمن دولاب العمل الموحد',
      branch,
      createdBy: actorName,
      createdAt: now,
      updatedAt: now
    }));
    await Promise.all(records.map((r) => ErpDataService.saveRecord('employee_tasks', r.syncUuid, r)));
    createdCount += records.length;
  }

  return { createdCount };
}
