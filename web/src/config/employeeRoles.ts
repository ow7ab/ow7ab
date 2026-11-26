import { EmployeeRoleKey, UserAccount } from '../types/erp';
import { GRANULAR_ACTIONS, isScopedAdministrator, isSystemManager } from '../services/SecurityService';

export interface RoleChecklistTemplate {
  title: string;
  metricTarget: string;
}

export interface EmployeeRoleProfile {
  key: EmployeeRoleKey;
  titleAr: string;
  titleEn: string;
  departmentAr: string;
  defaultBranch: string;
  sampleHolders: string;
  summaryAr: string;
  assignedModules: string[];
  defaultPermissions: string[];
  focusModules: Array<{
    moduleId: string;
    labelAr: string;
    descriptionAr: string;
  }>;
  dailyChecklist: RoleChecklistTemplate[];
  primaryQuickAction:
    | 'EXEC_COCKPIT'
    | 'BUDGET_CONTROL'
    | 'QC_PROCUREMENT'
    | 'PRODUCTION_KETTLE'
    | 'SHIFT_ENTRY'
    | 'WAREHOUSE_GRN'
    | 'LOADING_TOOLS'
    | 'SUDAN_SEQAOFA'
    | 'SALES_INVOICING';
}

export const EMPLOYEE_ROLE_PROFILES: Record<EmployeeRoleKey, EmployeeRoleProfile> = {
  GENERAL_MANAGER: {
    key: 'GENERAL_MANAGER',
    titleAr: 'المدير العام / الإدارة العليا',
    titleEn: 'General Manager',
    departmentAr: 'الإدارة العامة والخزينة الرئيسية (مصر)',
    defaultBranch: 'SD-001 (الخرطوم - الإدارة العامة)',
    sampleHolders: 'م. مجدي فضل / admin',
    summaryAr: 'متابعة الإنتاج اليومي (هدف 1,000 كرتونة)، الخزائن الثلاث (مصر/السودان/الدولار)، الانحراف المالي ≤5%، المكابس، ومستويات المكافآت.',
    assignedModules: ['المصنع', 'الإنتاج', 'التعبئة والتغليف', 'المخازن', 'الخزن', 'التحويلات', 'المحاسبة', 'المبيعات', 'العملاء', 'المشتريات', 'المختبر الكيميائي', 'الأسعار', 'التقارير', 'المستخدمين', 'الإعدادات'],
    defaultPermissions: [
      GRANULAR_ACTIONS.VIEW,
      GRANULAR_ACTIONS.CREATE,
      GRANULAR_ACTIONS.EDIT,
      GRANULAR_ACTIONS.APPROVE,
      GRANULAR_ACTIONS.EXPORT,
      GRANULAR_ACTIONS.PRINT,
      GRANULAR_ACTIONS.FINANCIAL_OPS,
      GRANULAR_ACTIONS.INVENTORY_OPS,
      'التقارير (كاملة)'
    ],
    focusModules: [
      { moduleId: 'TREASURY', labelAr: 'الخزينة الرئيسية وتحويلات العملة', descriptionAr: 'مراجعة أرصدة EGP / SDG / USD والتحويلات الواردة من السودان' },
      { moduleId: 'OPERATIONS', labelAr: 'لوحة المصنع والخطوط والمكافآت', descriptionAr: 'إنتاج الـ 5 خطوط، حالة المكابس، المهام، وتقييم المكافآت' },
      { moduleId: 'REPORTS', labelAr: 'القوائم المالية والربحية', descriptionAr: 'أرباح الفواتير، ميزان المراجعة، والمطابقة بين المنصات' },
      { moduleId: 'USERS', labelAr: 'إدارة الموظفين والصلاحيات', descriptionAr: 'اعتماد حسابات الموظفين وتوزيع الصلاحيات' }
    ],
    dailyChecklist: [
      { title: 'مراجعة إنتاج اليوم مقابل هدف 1,000 كرتونة/وردية', metricTarget: '1,000 كرتونة' },
      { title: 'مراجعة تحويلات العملة الواردة من السودان وأرصدة الخزائن', metricTarget: 'مطابقة يومية' },
      { title: 'فحص المهام المتأخرة وتجاوزات التوالف (>25 عبوة/خط)', metricTarget: '0 تجاوزات' }
    ],
    primaryQuickAction: 'EXEC_COCKPIT'
  },
  FINANCE_MANAGER_EGYPT: {
    key: 'FINANCE_MANAGER_EGYPT',
    titleAr: 'المدير المالي (مصر)',
    titleEn: 'Finance Manager (Egypt)',
    departmentAr: 'الإدارة المالية ومصروفات التشغيل',
    defaultBranch: 'EG-001 (مصنع مصر والتصدير)',
    sampleHolders: 'أ. أحمد السيد الطلخاوي',
    summaryAr: 'تطبيق الخطة المالية بحد انحراف 5%، إدارة خزينة مصروفات التشغيل، شيكات الموردين، الرواتب، وبيع البراميل الفارغة الخالية من الألكيد.',
    assignedModules: ['المحاسبة', 'الخزن', 'التحويلات', 'المشتريات', 'الموردين', 'التقارير'],
    defaultPermissions: [
      GRANULAR_ACTIONS.VIEW,
      GRANULAR_ACTIONS.CREATE,
      GRANULAR_ACTIONS.EDIT,
      GRANULAR_ACTIONS.APPROVE,
      GRANULAR_ACTIONS.EXPORT,
      GRANULAR_ACTIONS.PRINT,
      GRANULAR_ACTIONS.FINANCIAL_OPS,
      'التقارير (كاملة)'
    ],
    focusModules: [
      { moduleId: 'TREASURY', labelAr: 'خزينة مصروفات التشغيل', descriptionAr: 'تسجيل تغذية التشغيل والمصروفات اليومية ومراجعة العهدة' },
      { moduleId: 'PURCHASES', labelAr: 'فواتير وشيكات الموردين', descriptionAr: 'مطابقة فواتير الشراء مع الموردين ومتابعة الاستحقاقات' },
      { moduleId: 'OPERATIONS', labelAr: 'الرقابة المالية والمكابس', descriptionAr: 'متابعة الانحراف المالي ≤ 5% وتجهيز المكابس القديمة' },
      { moduleId: 'REPORTS', labelAr: 'التقارير المالية والأستاذ العام', descriptionAr: 'كشوف الحسابات وميزان المراجعة والرواتب' }
    ],
    dailyChecklist: [
      { title: 'مراجعة الخطة المالية والانحراف (حد أقصى 5%)', metricTarget: 'انحراف ≤ 5%' },
      { title: 'التأكد من خلو البراميل الفارغة من الألكيد قبل البيع', metricTarget: '100% مطابقة' },
      { title: 'مراجعة سدادات الموردين والشيكات ونظافة دور الإدارة', metricTarget: 'يومي' }
    ],
    primaryQuickAction: 'BUDGET_CONTROL'
  },
  QUALITY_PROCUREMENT_MANAGER: {
    key: 'QUALITY_PROCUREMENT_MANAGER',
    titleAr: 'مديرة الجودة والمشتريات والبحث والتطوير',
    titleEn: 'Quality, R&D & Procurement Manager',
    departmentAr: 'الشراء والبحث والتطوير والرقابة',
    defaultBranch: 'EG-001 (مصنع مصر والتصدير)',
    sampleHolders: 'م. رانيا سمير',
    summaryAr: 'التركيبات الكيميائية، تأمين خامات الوردية خلال 24 ساعة، متابعة حد الطلب لمنع النفاد، خطة النولون، مطابقة الموردين، وعينات الباتشات.',
    assignedModules: ['المختبر الكيميائي', 'المشتريات', 'الموردين', 'مواد الإنتاج', 'المخازن', 'التقارير'],
    defaultPermissions: [
      GRANULAR_ACTIONS.VIEW,
      GRANULAR_ACTIONS.CREATE,
      GRANULAR_ACTIONS.EDIT,
      GRANULAR_ACTIONS.APPROVE,
      GRANULAR_ACTIONS.EXPORT,
      GRANULAR_ACTIONS.INVENTORY_OPS,
      'تقارير المواد الخام'
    ],
    focusModules: [
      { moduleId: 'CHEMISTRY_LAB', labelAr: 'المختبر الكيميائي والتركيبات', descriptionAr: 'صياغة وتعديل واعتماد التركيبات وتكلفة الكيلو' },
      { moduleId: 'PURCHASES', labelAr: 'المشتريات والنواقص والموردين', descriptionAr: 'أوامر الشراء ومتابعة حد الطلب وتكاليف النولون' },
      { moduleId: 'FACTORY', labelAr: 'فحص الجودة وعينات الباتشات', descriptionAr: 'تسجيل عينات كل باتشة ومطابقة الكثافة واللزوجة' }
    ],
    dailyChecklist: [
      { title: 'تأمين خامات الوردية خلال 24 ساعة ومتابعة حد الطلب', metricTarget: '0 توقف خامات' },
      { title: 'سحب وتوثيق عينة من كل باتشة صناعة (التاريخ والرقم)', metricTarget: 'كل باتشة' },
      { title: 'مطابقة فواتير الشراء وحسابات الموردين وخطة النولون', metricTarget: 'أسبوعي/يومي' }
    ],
    primaryQuickAction: 'QC_PROCUREMENT'
  },
  PRODUCTION_MANAGER: {
    key: 'PRODUCTION_MANAGER',
    titleAr: 'مدير الإنتاج والتصنيع',
    titleEn: 'Production Manager',
    departmentAr: 'إدارة الإنتاج والألكيد',
    defaultBranch: 'EG-001 (مصنع مصر والتصدير)',
    sampleHolders: 'م. مصطفى الصيفي',
    summaryAr: 'مسؤولية الألكيد، تجهيز الحلل والبيست، تسليم حلة الصناعة بوزن مطابق لأمر الإنتاج، صيانة مكن الإنتاج، وتشغيل الوردية دون إهدار وقت.',
    assignedModules: ['المصنع', 'الإنتاج', 'مواد الإنتاج', 'المخازن', 'المختبر الكيميائي'],
    defaultPermissions: [
      GRANULAR_ACTIONS.VIEW,
      GRANULAR_ACTIONS.CREATE,
      GRANULAR_ACTIONS.EDIT,
      GRANULAR_ACTIONS.INVENTORY_OPS,
      'تقارير المواد الخام',
      'تقارير حركة المخزون'
    ],
    focusModules: [
      { moduleId: 'FACTORY', labelAr: 'أوامر الإنتاج وحلل الصناعة', descriptionAr: 'تنفيذ أوامر التشغيل، صرف الخامات، ومطابقة أوزان الحلل' },
      { moduleId: 'OPERATIONS', labelAr: 'جاهزية المكابس والماكينات', descriptionAr: 'متابعة صيانة مكن الإنتاج وحالة المكابس' },
      { moduleId: 'INVENTORY', labelAr: 'أرصدة مخزن اللاكيه والخامات', descriptionAr: 'متابعة مخزن 11 (اللاكيه والورنيش) قبل التعبئة' }
    ],
    dailyChecklist: [
      { title: 'التحقق من استمرارية الألكيد وتشغيل الوردية دون توقف', metricTarget: '0 توقف' },
      { title: 'تسليم حلة الصناعة بوزن مطابق لأمر الإنتاج', metricTarget: '100% مطابقة' },
      { title: 'صيانة مكن الإنتاج ونظافة مكان الإنتاج يومياً', metricTarget: 'يومي' }
    ],
    primaryQuickAction: 'PRODUCTION_KETTLE'
  },
  PACKING_ENGINEER: {
    key: 'PACKING_ENGINEER',
    titleAr: 'مهندس التعبئة والجودة (الخطوط 1–5)',
    titleEn: 'Packing & Quality Engineer',
    departmentAr: 'التعبئة والجودة',
    defaultBranch: 'EG-001 (مصنع مصر والتصدير)',
    sampleHolders: 'م. محمد عبدالباسط (3 خطوط) / م. زياد محمد (خطان)',
    summaryAr: 'الإشراف على خطوط التعبئة وطاقمها، تسجيل إنتاج نهاية الوردية، مراقبة الهالك (≤25 عبوة/خط)، منع أخطاء الوزن والاستيكر، وسلامة الطابعة.',
    assignedModules: ['التعبئة والتغليف', 'المصنع', 'الإنتاج', 'المخازن'],
    defaultPermissions: [
      GRANULAR_ACTIONS.VIEW,
      GRANULAR_ACTIONS.CREATE,
      GRANULAR_ACTIONS.EDIT,
      GRANULAR_ACTIONS.INVENTORY_OPS,
      'تقارير حركة المخزون'
    ],
    focusModules: [
      { moduleId: 'OPERATIONS', labelAr: 'تسجيل إنتاج نهاية الوردية والهالك', descriptionAr: 'إدخال الكراتين المنتجة والهالك وأخطاء الوزن والاستيكر للخطوط 1–5' },
      { moduleId: 'FACTORY', labelAr: 'شاشة المصنع والدفعات', descriptionAr: 'استعراض الباتشات المجهزة والماكينات' },
      { moduleId: 'INVENTORY', labelAr: 'أرصدة الكرتون والصاج والتام', descriptionAr: 'متابعة توفر العبوات الفارغة والكراتين' }
    ],
    dailyChecklist: [
      { title: 'تسجيل إنتاج خطوط التعبئة في نهاية الوردية', metricTarget: 'كل خط' },
      { title: 'ضبط الهالك ≤ 25 عبوة/وردية/خط ومنع أخطاء الوزن والاستيكر', metricTarget: '≤ 25 عبوة/خط' },
      { title: 'فحص سلامة الطابعة وتجهيز الاستيكرات يومياً', metricTarget: 'جاهزة' }
    ],
    primaryQuickAction: 'SHIFT_ENTRY'
  },
  WAREHOUSE_KEEPER_EGYPT: {
    key: 'WAREHOUSE_KEEPER_EGYPT',
    titleAr: 'أمين مخازن المصنع',
    titleEn: 'Factory Warehouse Keeper',
    departmentAr: 'المخازن (الخامات والصاج والكرتون والتام)',
    defaultBranch: 'EG-001 (مصنع مصر والتصدير)',
    sampleHolders: 'إبراهيم مبارك',
    summaryAr: 'استلام الصاج وعجز الغطاء، متابعة عجز الكرتون وتوالف الموردين، صرف الخامات للإنتاج، استلام المنتج التام، ومتابعة نظافة الساحة والزرع.',
    assignedModules: ['المخازن', 'مواد الإنتاج', 'المصنع', 'المشتريات'],
    defaultPermissions: [
      GRANULAR_ACTIONS.VIEW,
      GRANULAR_ACTIONS.CREATE,
      GRANULAR_ACTIONS.INVENTORY_OPS,
      'تقارير المواد الخام',
      'تقارير حركة المخزون'
    ],
    focusModules: [
      { moduleId: 'INVENTORY', labelAr: 'مخازن الخامات والكرتون والتام', descriptionAr: 'تسجيل الوارد والصادر والجرد الفعلي للمخازن' },
      { moduleId: 'PURCHASES', labelAr: 'استلام طلبات الشراء (GRN) وتسجيل العجز', descriptionAr: 'مطابقة استلام الصاج والكرتون والخامات وتوثيق توالف الموردين' },
      { moduleId: 'FACTORY', labelAr: 'صرف خامات الإنتاج واستلام التام', descriptionAr: 'صرف أوامر الإنتاج واستلام المنتج التام من خطوط التعبئة' }
    ],
    dailyChecklist: [
      { title: 'مطابقة استلام الصاج وعجز الغطاء وعجز الكرتون وتوالف الموردين', metricTarget: 'تسجيل فوري' },
      { title: 'مراجعة أرصدة الخامات ومطابقة الوارد والمنصرف اليومي', metricTarget: 'يومي' },
      { title: 'التأكد من العناية بالزرع والشجر في نهاية يوم العمل', metricTarget: 'نهاية اليوم' }
    ],
    primaryQuickAction: 'WAREHOUSE_GRN'
  },
  LOADING_TOOLS_SUPERVISOR: {
    key: 'LOADING_TOOLS_SUPERVISOR',
    titleAr: 'مشرف التحميل والعدد والخدمات',
    titleEn: 'Loading, Presses & Tools Supervisor',
    departmentAr: 'الإشراف والتحميل وصندوق العدة',
    defaultBranch: 'EG-001 (مصنع مصر والتصدير)',
    sampleHolders: 'منذر مزمل',
    summaryAr: 'التحميل بعد انتهاء الوردية فقط (العملاء ومخزن حلفا)، صيانة وتجهيز المكابس، عهدة صندوق العدة والجرد الشهري، بنزين الكلارك، ونظافة آخر 10 دقائق.',
    assignedModules: ['المخازن', 'المصنع', 'التعبئة والتغليف'],
    defaultPermissions: [
      GRANULAR_ACTIONS.VIEW,
      GRANULAR_ACTIONS.CREATE,
      GRANULAR_ACTIONS.EDIT,
      GRANULAR_ACTIONS.INVENTORY_OPS,
      'تقارير حركة المخزون'
    ],
    focusModules: [
      { moduleId: 'OPERATIONS', labelAr: 'بوابة التحميل بعد الوردية والمكابس والعدة', descriptionAr: 'تحديث المكابس، جرد صندوق العدة، وتسجيل التحميل والترحيل بعد الوردية' },
      { moduleId: 'FACTORY', labelAr: 'تسليم المنتج التام وترحيل حلفا', descriptionAr: 'إصدار شحنات العملاء وترحيلات مخزن وادي حلفا' },
      { moduleId: 'INVENTORY', labelAr: 'متابعة أرصدة المنتج التام المشحون', descriptionAr: 'التحقق من الكميات الجاهزة للتحميل' }
    ],
    dailyChecklist: [
      { title: 'تنفيذ التحميل فقط بعد انتهاء زمن الوردية الرسمي', metricTarget: '0 تحميل أثناء الوردية' },
      { title: 'تأمين بنزين الكلارك وتحديث حالة المكابس وصندوق العدة', metricTarget: 'يومي/شهري' },
      { title: 'إتمام نظافة المصنع في آخر 10 دقائق من الوردية', metricTarget: 'آخر 10 دقائق' }
    ],
    primaryQuickAction: 'LOADING_TOOLS'
  },
  SUDAN_BRANCH_MANAGER: {
    key: 'SUDAN_BRANCH_MANAGER',
    titleAr: 'محاسب ومبيعات السودان (حلفا / عطبرة)',
    titleEn: 'Sudan Branch Accountant & Sales',
    departmentAr: 'إدارة السودان (خزنة حلفا وعطبرة والسقوفة)',
    defaultBranch: 'HF-001 (مستودع حلفا اللوجستي)',
    sampleHolders: 'عمر عبدالوهاب / محمد صديق',
    summaryAr: 'إدارة خزائن السودان وحلفا وعطبرة، تسعير البضاعة بالسوداني مقابل سعر الصرف، فواتير المغالق وتحصيل عملاء المصنع، بضاعة الطريق، وحساب السقوفة.',
    assignedModules: ['الخزن', 'التحويلات', 'المحاسبة', 'المبيعات', 'العملاء', 'المخازن', 'الأسعار', 'التقارير'],
    defaultPermissions: [
      GRANULAR_ACTIONS.VIEW,
      GRANULAR_ACTIONS.CREATE,
      GRANULAR_ACTIONS.EDIT,
      GRANULAR_ACTIONS.EXPORT,
      GRANULAR_ACTIONS.PRINT,
      GRANULAR_ACTIONS.FINANCIAL_OPS,
      GRANULAR_ACTIONS.INVENTORY_OPS,
      'تقارير المبيعات',
      'تقارير المديونيات',
      'تقارير حركات الخزن',
      'تقارير حركة المخزون'
    ],
    focusModules: [
      { moduleId: 'OPERATIONS', labelAr: 'السقوفة وبضاعة في الطريق', descriptionAr: 'متابعة عهدة التحويل بين محمد صديق وعمر عبدالوهاب وشحنات الجبلاوي والجمارك' },
      { moduleId: 'TREASURY', labelAr: 'خزائن السودان وتحويلات العملة لمصر', descriptionAr: 'قيد المبيعات والمصاريف والتحويل بسعر الصرف مع سداد فواتير حلفا' },
      { moduleId: 'SALES', labelAr: 'فواتير المغالق وعملاء السودان', descriptionAr: 'فواتير وليد الخضر، القمة، المدينة، حسام الدين، أبو عبيدة، وتحصيل الأصيل' },
      { moduleId: 'POSTER', labelAr: 'لستة الأسعار بالسوداني', descriptionAr: 'تحديث أسعار 1/16 والكيلو والجالون حسب سعر صرف الجنيه المصري' }
    ],
    dailyChecklist: [
      { title: 'مطابقة خزنة السودان/حلفا وإقفال اليومية', metricTarget: 'مطابقة يومية' },
      { title: 'تحديث تحويلات السقوفة والعملة المحولة للمصنع بمصر', metricTarget: 'فوري' },
      { title: 'متابعة الشحنات الواردة في الطريق والجمارك والمغالق', metricTarget: 'يومي' }
    ],
    primaryQuickAction: 'SUDAN_SEQAOFA'
  },
  SALES_AGENT: {
    key: 'SALES_AGENT',
    titleAr: 'مسؤول المبيعات والفواتير',
    titleEn: 'Sales & Invoicing Officer',
    departmentAr: 'المبيعات والتحصيل',
    defaultBranch: 'SD-001 (الخرطوم - الإدارة العامة)',
    sampleHolders: 'مندوبو المبيعات والفوترة',
    summaryAr: 'إصدار فواتير المبيعات للعملاء، متابعة المديونيات والتحصيل النقدي، وطباعة كشوف الحساب ولستة الأسعار.',
    assignedModules: ['المبيعات', 'العملاء', 'الأسعار', 'المخازن', 'التقارير'],
    defaultPermissions: [
      GRANULAR_ACTIONS.VIEW,
      GRANULAR_ACTIONS.CREATE,
      GRANULAR_ACTIONS.EXPORT,
      GRANULAR_ACTIONS.PRINT,
      'تقارير المبيعات',
      'تقارير المديونيات'
    ],
    focusModules: [
      { moduleId: 'SALES', labelAr: 'الفواتير والتحصيل وكشوف الحساب', descriptionAr: 'إصدار الفواتير وتحصيل الدفعات ومراجعة أرصدة العملاء' },
      { moduleId: 'POSTER', labelAr: 'لستة الأسعار والخصومات', descriptionAr: 'عرض وتصدير بوستر الأسعار المعتمد' },
      { moduleId: 'INVENTORY', labelAr: 'أرصدة مخازن المبيعات', descriptionAr: 'التحقق من توفر الأصناف قبل البيع' }
    ],
    dailyChecklist: [
      { title: 'مراجعة فواتير اليوم والتحصيلات المعلقة للعملاء', metricTarget: 'يومي' },
      { title: 'مطابقة الكميات المباعة مع رصيد مخزن المبيعات', metricTarget: '100% مطابقة' }
    ],
    primaryQuickAction: 'SALES_INVOICING'
  }
};

export const ROLE_PRESET_LIST: EmployeeRoleProfile[] = Object.values(EMPLOYEE_ROLE_PROFILES);

export function resolveEmployeeRoleKey(user: Partial<UserAccount> | null | undefined): EmployeeRoleKey {
  if (!user) return 'SALES_AGENT';
  if (isSystemManager(user) && !isScopedAdministrator(user)) {
    return 'GENERAL_MANAGER';
  }
  const role = (user.role || '').trim();
  const branch = (user.branch || '').trim();
  const perms = (user.permissions || '').trim();

  if (role.includes('المدير العام') || role.includes('مدير النظام') || role === 'المدير') {
    return 'GENERAL_MANAGER';
  }
  if (role.includes('المالي') || role.includes('طلخاوي') || role.includes('مراجع مالي')) {
    return 'FINANCE_MANAGER_EGYPT';
  }
  if (role.includes('الجودة') || role.includes('المشتريات') || role.includes('كيميائي') || role.includes('مختبر')) {
    return 'QUALITY_PROCUREMENT_MANAGER';
  }
  if (role.includes('التعبئة')) {
    return 'PACKING_ENGINEER';
  }
  if (role.includes('الإنتاج') || role.includes('الانتاج')) {
    return 'PRODUCTION_MANAGER';
  }
  if (role.includes('التحميل') || role.includes('العدد')) {
    return 'LOADING_TOOLS_SUPERVISOR';
  }
  if (role.includes('السودان') || role.includes('حلفا') || role.includes('عطبرة') || branch.includes('HF-001') || branch.includes('AT-002')) {
    if (role.includes('خزنة') || role.includes('محاسب') || perms.includes('التحويلات') || perms.includes('الخزن')) {
      return 'SUDAN_BRANCH_MANAGER';
    }
  }
  if (role.includes('أمين مخزن') || role.includes('مخزن')) {
    return 'WAREHOUSE_KEEPER_EGYPT';
  }
  if (role.includes('محاسب')) {
    return branch.includes('EG-001') ? 'FINANCE_MANAGER_EGYPT' : 'SUDAN_BRANCH_MANAGER';
  }
  if (role.includes('مبيعات') || role.includes('فواتير')) {
    return 'SALES_AGENT';
  }
  if (perms.includes('التعبئة والتغليف')) return 'PACKING_ENGINEER';
  if (perms.includes('المختبر الكيميائي') || perms.includes('المشتريات')) return 'QUALITY_PROCUREMENT_MANAGER';
  if (perms.includes('الإنتاج')) return 'PRODUCTION_MANAGER';
  if (perms.includes('المخازن')) return 'WAREHOUSE_KEEPER_EGYPT';
  if (perms.includes('الخزن') || perms.includes('التحويلات')) return 'SUDAN_BRANCH_MANAGER';
  return 'SALES_AGENT';
}

export function resolveEmployeeRoleProfile(user: Partial<UserAccount> | null | undefined): EmployeeRoleProfile {
  const key = resolveEmployeeRoleKey(user);
  return EMPLOYEE_ROLE_PROFILES[key];
}
