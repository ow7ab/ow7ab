package com.example.core.architecture

import com.example.data.local.UserAccountEntity
import com.example.isSystemManager
import com.example.util.EnterpriseRbacManager
import kotlin.math.abs
import kotlin.math.roundToInt

enum class EmployeeRoleKey {
    GENERAL_MANAGER,
    FINANCE_MANAGER_EGYPT,
    QUALITY_PROCUREMENT_MANAGER,
    PRODUCTION_MANAGER,
    PACKING_ENGINEER,
    WAREHOUSE_KEEPER_EGYPT,
    LOADING_TOOLS_SUPERVISOR,
    SUDAN_BRANCH_MANAGER,
    SALES_AGENT
}

data class RoleChecklistSeed(
    val title: String,
    val metricTarget: String
)

data class RoleModuleShortcut(
    val routeKey: String,
    val labelAr: String,
    val descriptionAr: String
)

data class EmployeeRoleProfile(
    val key: EmployeeRoleKey,
    val titleAr: String,
    val titleEn: String,
    val departmentAr: String,
    val defaultBranch: String,
    val sampleHolders: String,
    val summaryAr: String,
    val assignedModules: List<String>,
    val defaultPermissions: List<String>,
    val focusModules: List<RoleModuleShortcut>,
    val dailyChecklist: List<RoleChecklistSeed>,
    val primaryQuickAction: String
)

data class ShiftLogEvaluation(
    val achievementPercent: Double,
    val wasteExceeded: Boolean,
    val wasteDiff: Int,
    val totalDowntimeMin: Int,
    val statusLabel: String
)

data class BudgetDeviationEvaluation(
    val varianceAmount: Double,
    val deviationPercent: Double,
    val exceeded: Boolean,
    val statusLabel: String
)

data class BonusTierEvaluation(
    val bonusTier: String,
    val bonusMonths: Int
)

object EmployeeRoleCatalog {
    const val DAILY_SHIFT_CARTON_TARGET = 1000
    const val DEFAULT_MAX_WASTE_CANS_PER_LINE = 25
    const val MAX_FINANCIAL_DEVIATION_PERCENT = 5.0

    val ALL_PROFILES: Map<EmployeeRoleKey, EmployeeRoleProfile> = linkedMapOf(
        EmployeeRoleKey.GENERAL_MANAGER to EmployeeRoleProfile(
            key = EmployeeRoleKey.GENERAL_MANAGER,
            titleAr = "المدير العام / الإدارة العليا",
            titleEn = "General Manager",
            departmentAr = "الإدارة العامة والخزينة الرئيسية (مصر)",
            defaultBranch = "فرع السودان (SD-001)",
            sampleHolders = "م. مجدي فضل / admin",
            summaryAr = "متابعة الإنتاج اليومي (هدف 1,000 كرتونة)، الخزائن الثلاث (مصر/السودان/الدولار)، الانحراف المالي ≤5%، المكابس، ومستويات المكافآت.",
            assignedModules = listOf("المصنع", "الإنتاج", "التعبئة والتغليف", "المخازن", "الخزن", "التحويلات", "المحاسبة", "المبيعات", "العملاء", "المشتريات", "المختبر الكيميائي", "الأسعار", "التقارير", "المستخدمين", "الإعدادات"),
            defaultPermissions = listOf("عرض", "إنشاء", "تعديل", "اعتماد", "تصدير PDF", "طباعة", "عمليات مالية", "عمليات مخزنية", "التقارير"),
            focusModules = listOf(
                RoleModuleShortcut("treasury", "الخزينة الرئيسية وتحويلات العملة", "مراجعة أرصدة EGP / SDG / USD والتحويلات الواردة"),
                RoleModuleShortcut("operations", "لوحة المصنع والخطوط والمكافآت", "إنتاج الـ 5 خطوط، حالة المكابس، المهام، والمكافآت"),
                RoleModuleShortcut("reports", "القوائم المالية والربحية", "أرباح الفواتير وميزان المراجعة والمطابقة"),
                RoleModuleShortcut("users", "إدارة الموظفين والصلاحيات", "اعتماد حسابات الموظفين وتوزيع الصلاحيات")
            ),
            dailyChecklist = listOf(
                RoleChecklistSeed("مراجعة إنتاج اليوم مقابل هدف 1,000 كرتونة/وردية", "1,000 كرتونة"),
                RoleChecklistSeed("مراجعة تحويلات العملة الواردة من السودان وأرصدة الخزائن", "مطابقة يومية"),
                RoleChecklistSeed("فحص المهام المتأخرة وتجاوزات التوالف (>25 عبوة/خط)", "0 تجاوزات")
            ),
            primaryQuickAction = "EXEC_COCKPIT"
        ),
        EmployeeRoleKey.FINANCE_MANAGER_EGYPT to EmployeeRoleProfile(
            key = EmployeeRoleKey.FINANCE_MANAGER_EGYPT,
            titleAr = "المدير المالي (مصر)",
            titleEn = "Finance Manager (Egypt)",
            departmentAr = "الإدارة المالية ومصروفات التشغيل",
            defaultBranch = "فرع مصر والمصنع (EG-001)",
            sampleHolders = "أ. أحمد السيد الطلخاوي",
            summaryAr = "تطبيق الخطة المالية بحد انحراف 5%، إدارة خزينة مصروفات التشغيل، شيكات الموردين، الرواتب، وبيع البراميل الفارغة الخالية من الألكيد.",
            assignedModules = listOf("المحاسبة", "الخزن", "التحويلات", "المشتريات", "الموردين", "التقارير"),
            defaultPermissions = listOf("عرض", "إنشاء", "تعديل", "اعتماد", "تصدير PDF", "طباعة", "عمليات مالية", "التقارير"),
            focusModules = listOf(
                RoleModuleShortcut("treasury", "خزينة مصروفات التشغيل", "تسجيل تغذية التشغيل والمصروفات اليومية ومراجعة العهدة"),
                RoleModuleShortcut("purchases", "فواتير وشيكات الموردين", "مطابقة فواتير الشراء مع الموردين ومتابعة الاستحقاقات"),
                RoleModuleShortcut("operations", "الرقابة المالية والمكابس", "متابعة الانحراف المالي ≤ 5% وتجهيز المكابس القديمة"),
                RoleModuleShortcut("reports", "التقارير المالية والأستاذ العام", "كشوف الحسابات وميزان المراجعة والرواتب")
            ),
            dailyChecklist = listOf(
                RoleChecklistSeed("مراجعة الخطة المالية والانحراف (حد أقصى 5%)", "انحراف ≤ 5%"),
                RoleChecklistSeed("التأكد من خلو البراميل الفارغة من الألكيد قبل البيع", "100% مطابقة"),
                RoleChecklistSeed("مراجعة سدادات الموردين والشيكات ونظافة دور الإدارة", "يومي")
            ),
            primaryQuickAction = "BUDGET_CONTROL"
        ),
        EmployeeRoleKey.QUALITY_PROCUREMENT_MANAGER to EmployeeRoleProfile(
            key = EmployeeRoleKey.QUALITY_PROCUREMENT_MANAGER,
            titleAr = "مدير الجودة والمشتريات والمختبر",
            titleEn = "Quality & Procurement Manager",
            departmentAr = "الرقابة على الجودة والمشتريات وخط البلاستيك",
            defaultBranch = "فرع مصر والمصنع (EG-001)",
            sampleHolders = "م. رانيا سمير",
            summaryAr = "متابعة شكاوى الجودة، فحص التوريدات، ربط 3 خطوط تشغيل ومكبس، وتخطيط توريد خط البلاستيك وميزان المصنع.",
            assignedModules = listOf("المختبر الكيميائي", "المشتريات", "الموردين", "المصنع", "التعبئة والتغليف", "التقارير"),
            defaultPermissions = listOf("عرض", "إنشاء", "تعديل", "اعتماد", "تصدير PDF", "عمليات مخزنية"),
            focusModules = listOf(
                RoleModuleShortcut("chemistry_lab", "المختبر الكيميائي والتركيبات", "فحص خامات الألكيد والتيوسين والتصنيع وفق PVC"),
                RoleModuleShortcut("purchases", "أوامر الشراء واستلام الخامات", "توريد الخامات ومراقبة موردي العبوات"),
                RoleModuleShortcut("operations", "سجل جودة الورديات والخطوط", "اعتماد فحص الورديات ومطابقة التوالف والمكابس")
            ),
            dailyChecklist = listOf(
                RoleChecklistSeed("اعتماد عينات الجودة للخامات والتشغيلات الجارية", "0 شكوى جودة"),
                RoleChecklistSeed("مراجعة ملف التوريدات وأوامر الشراء الحرجة", "يومي"),
                RoleChecklistSeed("متابعة كفاءة الـ 3 خطوط والمكبس ومؤشرات خط البلاستيك", "متابعة تشغيلية")
            ),
            primaryQuickAction = "QC_PROCUREMENT"
        ),
        EmployeeRoleKey.PRODUCTION_MANAGER to EmployeeRoleProfile(
            key = EmployeeRoleKey.PRODUCTION_MANAGER,
            titleAr = "مدير الإنتاج والتعبئة والمخازن",
            titleEn = "Production Manager",
            departmentAr = "إدارة الإنتاج والتعبئة وصيانة المصنع",
            defaultBranch = "فرع مصر والمصنع (EG-001)",
            sampleHolders = "م. مصطفى محمود الصيفي",
            summaryAr = "تحقيق هدف الوردية (1,000 كرتونة)، تشغيل خط الألكيد والـ 5 خطوط، مراقبة توقفات الأعطال/الخامات وصيانة المكابس والطفايات.",
            assignedModules = listOf("المصنع", "الإنتاج", "التعبئة والتغليف", "مواد الإنتاج", "المخازن", "المختبر الكيميائي", "التقارير"),
            defaultPermissions = listOf("عرض", "إنشاء", "تعديل", "اعتماد", "طباعة", "عمليات مخزنية"),
            focusModules = listOf(
                RoleModuleShortcut("factory", "تشغيل القزانات والدفعات", "صرف الخامات للخلطات وإدارة ماكينات المصنع"),
                RoleModuleShortcut("operations", "لوحة الورديات والمكابس والعمالة", "مراقبة هدف 1,000 كرتونة، التوالف ≤25/خط، والأعطال"),
                RoleModuleShortcut("inventory", "أرصدة المواد الخام والمنتج التام", "تأمين عدم توقف الخطوط بسبب نقص الخام أو الفارغ")
            ),
            dailyChecklist = listOf(
                RoleChecklistSeed("تحقيق إنتاج يومي ≥ 1,000 كرتونة للوردية", "≥ 1,000 كرتونة"),
                RoleChecklistSeed("متابعة نسبة التوالف ≤ 25 عبوة لكل خط تعبئة", "≤ 25 عبوة/خط"),
                RoleChecklistSeed("تأكيد حضور العمالة (≥16 للـ5 خطوط) وصيانة الطفايات والمكابس", "تأمين يومي")
            ),
            primaryQuickAction = "PRODUCTION_KETTLE"
        ),
        EmployeeRoleKey.PACKING_ENGINEER to EmployeeRoleProfile(
            key = EmployeeRoleKey.PACKING_ENGINEER,
            titleAr = "مهندس التعبئة والتغليف",
            titleEn = "Packing Line Engineer",
            departmentAr = "التعبئة والتغليف والطباعة والمكابس",
            defaultBranch = "فرع مصر والمصنع (EG-001)",
            sampleHolders = "م. محمد عبدالباسط (3 خطوط) / م. زياد حسام (2 خط)",
            summaryAr = "تسجيل إنتاج الوردية على الخطوط (500 ك/يوم للخطوط الـ3 و500 ك/يوم للخطين)، حصر التوالف (حد 25 عبوة/خط) وتقارير أعطال المكن.",
            assignedModules = listOf("التعبئة والتغليف", "المصنع", "الإنتاج", "المخازن"),
            defaultPermissions = listOf("عرض", "إنشاء", "تعديل", "عمليات مخزنية"),
            focusModules = listOf(
                RoleModuleShortcut("operations", "نموذج تسجيل الوردية والتوالف", "إدخال عدد الكراتين والعبوات التالفة وزمن الأعطال الفعلي"),
                RoleModuleShortcut("factory", "ربط دفعات الإنتاج بالتعبئة", "سحب الدفعات الجاهزة للتعبئة وحساب الكراتين")
            ),
            dailyChecklist = listOf(
                RoleChecklistSeed("إدخال إنتاج خطوطي على لوحة الوردية (500 كرتونة 3 خطوط / 500 كرتونة خطين)", "500+500 كرتونة"),
                RoleChecklistSeed("حصر العبوات التالفة (حد أقصى 25 عبوة/خط)", "≤ 25 عبوة/خط"),
                RoleChecklistSeed("تسجيل أسباب الأعطال (سخانات/لحام كراتين/نقص خام) وتأكيد نظافة خطوط التعبئة", "يومي")
            ),
            primaryQuickAction = "SHIFT_ENTRY"
        ),
        EmployeeRoleKey.WAREHOUSE_KEEPER_EGYPT to EmployeeRoleProfile(
            key = EmployeeRoleKey.WAREHOUSE_KEEPER_EGYPT,
            titleAr = "أمين المخزن العام (مصر)",
            titleEn = "Warehouse Keeper (Egypt)",
            departmentAr = "إدارة المخازن (خامات + فارغ + منتج تام)",
            defaultBranch = "فرع مصر والمصنع (EG-001)",
            sampleHolders = "إبراهيم مبارك عبدالواحد",
            summaryAr = "مستندات استلام/صرف الخام والفارغ والمنتج التام، نظام الأرفف للورق والبلستر والتايلوز، ومنع الصرف بدون مستند معتمد.",
            assignedModules = listOf("المخازن", "مواد الإنتاج", "المشتريات", "التعبئة والتغليف"),
            defaultPermissions = listOf("عرض", "إنشاء", "تعديل", "طباعة", "عمليات مخزنية"),
            focusModules = listOf(
                RoleModuleShortcut("inventory", "حركات المخازن والأرصدة الفعلية", "إصدار أذون الاستلام GRN وصرف التشغيل ومطابقة الجرد"),
                RoleModuleShortcut("operations", "جرد الخام وتجهيز الشحن للسودان", "رفع تقارير النواقص وقائمة المهام المخزنية اليومية")
            ),
            dailyChecklist = listOf(
                RoleChecklistSeed("منع صرف أي خام أو فارغ أو منتج تام بدون مستند إذن صرف معتمد", "100% مستندي"),
                RoleChecklistSeed("إرسال تنبيه عند انخفاض الفارغ/الاستيكر/الألكيد لأقل من حد 7 أيام", "تنبيه مبكر"),
                RoleChecklistSeed("تحديث أرصدة الورق والغراء واللصق والمنتج التام على نظام الأرفف", "يومي")
            ),
            primaryQuickAction = "WAREHOUSE_GRN"
        ),
        EmployeeRoleKey.LOADING_TOOLS_SUPERVISOR to EmployeeRoleProfile(
            key = EmployeeRoleKey.LOADING_TOOLS_SUPERVISOR,
            titleAr = "مسؤول التحميل والعدد واللوجستيات",
            titleEn = "Loading & Tools Supervisor",
            departmentAr = "العدد والعُهد والتحميل والبلاستيك",
            defaultBranch = "فرع مصر والمصنع (EG-001)",
            sampleHolders = "منذر مزمل فضل",
            summaryAr = "متابعة شحنات الصادرات، عُهد عدد المصنع والتوكيلات (حصر وتجديد الفاقد والتالف)، وتوريد الصفيح 1 كجم مع طبالي الخشب.",
            assignedModules = listOf("المخازن", "التحويلات", "المصنع"),
            defaultPermissions = listOf("عرض", "إنشاء", "تعديل", "عمليات مخزنية"),
            focusModules = listOf(
                RoleModuleShortcut("operations", "عهدة العدد والتوكيلات والشحن", "صرف/إرجاع العدد وتحديث حالة التالف والمفقود وبوليصات الشحن"),
                RoleModuleShortcut("inventory", "استلام الفارغ الصفيح والطبالي", "فحص استلام شحنات الصفيح والـ99 لفة استرتش")
            ),
            dailyChecklist = listOf(
                RoleChecklistSeed("حصر العدد اليدوية والتوكيلات وإثبات المسلم والمرتجع والتالف", "جرد عهد"),
                RoleChecklistSeed("تسجيل أعداد التحميل ومراجعة جاهزية المكابس الجديدة للعمل", "يومي"),
                RoleChecklistSeed("استلام شحنات الصفيح 1 كجم والطبالي والـ99 لفة استرتش بالمواصفة", "استلام مطابق")
            ),
            primaryQuickAction = "LOADING_TOOLS"
        ),
        EmployeeRoleKey.SUDAN_BRANCH_MANAGER to EmployeeRoleProfile(
            key = EmployeeRoleKey.SUDAN_BRANCH_MANAGER,
            titleAr = "مدير فرع ومخزن السودان (حلفا / عطبرة)",
            titleEn = "Sudan Branch & Transit Manager",
            departmentAr = "إدارة مبيعات ومخازن السودان والتحويلات",
            defaultBranch = "مخزن حلفا (HF-001)",
            sampleHolders = "عمر عبدالوهاب (حلفا) / محمد صديق (عطبرة)",
            summaryAr = "إدارة مخزون حلفا وعطبرة بالكرتونة (1/16، 1 ك، جالون)، دفتر السقوفة وتحويلات الجنيه مقابل المصري (EGP/SDG)، ومتابعة البضاعة في الطريق.",
            assignedModules = listOf("المخازن", "الخزن", "التحويلات", "المبيعات", "العملاء", "الأسعار", "التقارير"),
            defaultPermissions = listOf("عرض", "إنشاء", "تعديل", "اعتماد", "تصدير PDF", "عمليات مالية", "عمليات مخزنية"),
            focusModules = listOf(
                RoleModuleShortcut("operations", "السقوفة وبضاعة في الطريق", "تسجيل وارد/منصرف السقوفة وتتبع الجمرك والنولون حتى الاستلام"),
                RoleModuleShortcut("sales", "مبيعات وفواتير فرع السودان", "إصدار فواتير العملاء وربط خزنة الفرع SDG"),
                RoleModuleShortcut("treasury", "خزائن حلفا وعطبرة والتحويلات", "تحويل السيولة إلى الإدارة بالخزينة المصرية")
            ),
            dailyChecklist = listOf(
                RoleChecklistSeed("تحديث دفتر السقوفة والتحويلات اليومية (SDG ⇄ EGP)", "مطابقة السقوفة"),
                RoleChecklistSeed("متابعة البضاعة في الطريق (الكميات 1/16، 1 كجم، جالون + الجمرك)", "تتبع يومي"),
                RoleChecklistSeed("اعتماد استلام الشحنات الواصلة لحلفا أو عطبرة فور وصولها للمخزن", "إقفال طريق")
            ),
            primaryQuickAction = "SUDAN_SEQAOFA"
        ),
        EmployeeRoleKey.SALES_AGENT to EmployeeRoleProfile(
            key = EmployeeRoleKey.SALES_AGENT,
            titleAr = "مندوب المبيعات والفواتير",
            titleEn = "Sales & Billing Agent",
            departmentAr = "المبيعات ونقاط البيع وخدمة العملاء",
            defaultBranch = "فرع السودان (SD-001)",
            sampleHolders = "مسؤول المبيعات والفواتير",
            summaryAr = "إصدار فواتير البيع النقد والآجل، تحصيل مدفوعات العملاء وفق بوستر الأسعار المعتمد للفرع.",
            assignedModules = listOf("المبيعات", "العملاء", "الأسعار", "المخازن"),
            defaultPermissions = listOf("عرض", "إنشاء", "طباعة", "تصدير PDF", "المبيعات"),
            focusModules = listOf(
                RoleModuleShortcut("sales", "إصدار الفواتير والتحصيل", "تسجيل فواتير المبيعات ومتابعة أرصدة العملاء"),
                RoleModuleShortcut("poster", "لستة الأسعار والخصومات", "عرض وتصدير بوستر الأسعار المعتمد"),
                RoleModuleShortcut("inventory", "أرصدة مخازن المبيعات", "التحقق من توفر الأصناف قبل البيع")
            ),
            dailyChecklist = listOf(
                RoleChecklistSeed("مراجعة فواتير اليوم والتحصيلات المعلقة للعملاء", "يومي"),
                RoleChecklistSeed("مطابقة الكميات المباعة مع رصيد مخزن المبيعات", "100% مطابقة")
            ),
            primaryQuickAction = "SALES_INVOICING"
        )
    )

    val ROLE_PRESET_LIST: List<EmployeeRoleProfile> = ALL_PROFILES.values.toList()

    fun resolveEmployeeRoleKey(user: UserAccountEntity?): EmployeeRoleKey {
        if (user == null) return EmployeeRoleKey.SALES_AGENT
        val isScoped = EnterpriseRbacManager.isScopedAdministrator(user)
        if (user.isSystemManager() && !isScoped) return EmployeeRoleKey.GENERAL_MANAGER

        val role = user.role.trim()
        val branch = user.branch.trim()
        val perms = user.permissions.trim()

        return when {
            role.contains("المدير العام") || role.contains("مدير النظام") || role == "المدير" -> EmployeeRoleKey.GENERAL_MANAGER
            role.contains("المالي") || role.contains("طلخاوي") || role.contains("مراجع مالي") -> EmployeeRoleKey.FINANCE_MANAGER_EGYPT
            role.contains("الجودة") || role.contains("المشتريات") || role.contains("كيميائي") || role.contains("مختبر") -> EmployeeRoleKey.QUALITY_PROCUREMENT_MANAGER
            role.contains("التعبئة") -> EmployeeRoleKey.PACKING_ENGINEER
            role.contains("الإنتاج") || role.contains("الانتاج") -> EmployeeRoleKey.PRODUCTION_MANAGER
            role.contains("التحميل") || role.contains("العدد") -> EmployeeRoleKey.LOADING_TOOLS_SUPERVISOR
            (role.contains("السودان") || role.contains("حلفا") || role.contains("عطبرة") || branch.contains("HF-001") || branch.contains("AT-002")) &&
                (role.contains("خزنة") || role.contains("محاسب") || role.contains("مدير") || perms.contains("التحويلات") || perms.contains("الخزن")) -> EmployeeRoleKey.SUDAN_BRANCH_MANAGER
            role.contains("أمين مخزن") || role.contains("مخزن") -> EmployeeRoleKey.WAREHOUSE_KEEPER_EGYPT
            role.contains("محاسب") -> if (branch.contains("EG-001")) EmployeeRoleKey.FINANCE_MANAGER_EGYPT else EmployeeRoleKey.SUDAN_BRANCH_MANAGER
            role.contains("مبيعات") || role.contains("فواتير") -> EmployeeRoleKey.SALES_AGENT
            perms.contains("التعبئة والتغليف") -> EmployeeRoleKey.PACKING_ENGINEER
            perms.contains("المختبر الكيميائي") || perms.contains("المشتريات") -> EmployeeRoleKey.QUALITY_PROCUREMENT_MANAGER
            perms.contains("الإنتاج") -> EmployeeRoleKey.PRODUCTION_MANAGER
            perms.contains("المخازن") -> EmployeeRoleKey.WAREHOUSE_KEEPER_EGYPT
            perms.contains("الخزن") || perms.contains("التحويلات") -> EmployeeRoleKey.SUDAN_BRANCH_MANAGER
            else -> EmployeeRoleKey.SALES_AGENT
        }
    }

    fun resolveEmployeeRoleProfile(user: UserAccountEntity?): EmployeeRoleProfile {
        return ALL_PROFILES[resolveEmployeeRoleKey(user)] ?: ALL_PROFILES.getValue(EmployeeRoleKey.SALES_AGENT)
    }

    fun evaluateShiftMetrics(
        targetCartons: Int,
        producedCartons: Int,
        wasteCans: Int,
        maxWasteAllowed: Int,
        technicalDowntimeMin: Int,
        materialDowntimeMin: Int
    ): ShiftLogEvaluation {
        val target = targetCartons.coerceAtLeast(1)
        val produced = producedCartons.coerceAtLeast(0)
        val waste = wasteCans.coerceAtLeast(0)
        val maxWaste = maxWasteAllowed.coerceAtLeast(1)
        val pct = ((produced.toDouble() / target.toDouble()) * 1000.0).roundToInt() / 10.0
        val wasteExceeded = waste > maxWaste
        val statusLabel = when {
            wasteExceeded -> "تجاوز حد التوالف"
            pct >= 95.0 -> "ممتاز"
            else -> "ضمن المتابعة"
        }
        return ShiftLogEvaluation(
            achievementPercent = pct,
            wasteExceeded = wasteExceeded,
            wasteDiff = waste - maxWaste,
            totalDowntimeMin = technicalDowntimeMin.coerceAtLeast(0) + materialDowntimeMin.coerceAtLeast(0),
            statusLabel = statusLabel
        )
    }

    fun evaluateBudgetDeviation(
        plannedAmount: Double,
        actualAmount: Double,
        maxDeviationPercent: Double = MAX_FINANCIAL_DEVIATION_PERCENT
    ): BudgetDeviationEvaluation {
        val variance = actualAmount - plannedAmount
        val devPct = if (plannedAmount > 0.0) {
            ((abs(variance) / plannedAmount) * 1000.0).roundToInt() / 10.0
        } else 0.0
        val exceeded = plannedAmount > 0.0 && devPct > maxDeviationPercent
        return BudgetDeviationEvaluation(
            varianceAmount = variance,
            deviationPercent = devPct,
            exceeded = exceeded,
            statusLabel = if (exceeded) "انحراف متجاوز للحد (5%)" else "ضمن الموازنة المعتمدة"
        )
    }

    fun evaluateEmployeeBonusTier(
        completionPercent: Double,
        deviationPercent: Double,
        errorCount: Int,
        innovationCount: Int
    ): BonusTierEvaluation {
        val dev = abs(deviationPercent)
        val err = errorCount.coerceAtLeast(0)
        val innov = innovationCount.coerceAtLeast(0)
        return when {
            completionPercent >= 100.0 && err == 0 && innov >= 1 ->
                BonusTierEvaluation("المستوى الثالث (5 شهور)", 5)
            completionPercent >= 100.0 && err == 0 ->
                BonusTierEvaluation("المستوى الثاني (4 شهور)", 4)
            completionPercent >= 95.0 && dev <= MAX_FINANCIAL_DEVIATION_PERCENT && err <= 5 ->
                BonusTierEvaluation("المستوى الأول (3 شهور)", 3)
            else ->
                BonusTierEvaluation("غير مستوفٍ للشرط", 0)
        }
    }
}
