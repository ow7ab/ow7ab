# PMP Science & Chemistry Lab — مختبر العلوم الكيميائية وتركيبات الدهانات

قسم علمي وهندسي متطور داخل تطبيق PMP PAINTS للأندرويد يمثل النسخة المحمولة الذكية من مختبر **PMP Cash System V17 PRO SYNC EDITION**، مخصص لمهندسي وفنيي الكيمياء لحساب مواصفات تركيبات الدهانات (PVC، المواد الصلبة، الكثافة)، الاستعلام المباشر من قاعدة بيانات PubChem العالمية، وإدارة السلامة الكيميائية (GHS / SDS) مع الربط بأوامر تشغيل المصنع ومخازن الخامات.

---

## User Review & Critical Decisions

> [!IMPORTANT]
> تم تأكيد القرارات التصميمية والتشغيلية التالية بناءً على مخرجات مرحلة الاستيضاح والتحليل الفني:

- **Confirmed Decision 1 (موقع الوصول في التطبيق):** إضافة أيقونة مخصصة للمختبر الكيميائي (`Icons.Default.Science`) في الشريط العلوي الدائم للتطبيق (`TopAppBar`) بالإضافة إلى بند رئيسي في القائمة الجانبية (`NavigationDrawer`) لسهولة التنقل السريع للفنيين والمهندسين.
- **Confirmed Decision 2 (المكتبة الكيميائية PubChem):** اعتماد البحث المباشر الحي عبر الإنترنت عبر واجهة برمجة تطبيقات PubChem الرسمية (NCBI PUG REST API) للاستعلام بالاسم الكيميائي أو رقم CAS دون الحاجة لمكتبات ثقيلة أو بيانات تجريبية وهمية.
- **Confirmed Decision 3 (الربط مع خطوط الإنتاج والمصنع):** توفير زر مباشر داخل شاشة التركيبة بعنوان **"إنشاء أمر تشغيل وتوريد خامات للمصنع"**؛ يقوم بالتحقق من توفر المواد الخام في مخازن مصنع PMP، وحجز الكميات المطلوبة، وتوليد أمر إنتاج مصنعي مسجل برقم الصيغة المعتمدة.

---

## 1. Overview & Core Concept

- **What It Does**: يوفر بيئة متكاملة لتركيب الدهانات الصناعية والإنشائية، متضمنة:
  1. **محرك PVC Engine**: إدخال المكونات الخام (أكاسيد وملونات، ملوئات وحبيبات، راتنجات وبوليمرات رابطة، مذيبات ومساعدات تشغيل)، وحساب تركيز المادة الملونة بالحجم (PVC %)، ونسبة المواد الصلبة الكلية (Total Solids %)، والحجم الجاف للراتنج، مع إجراء تحليل فني مقارن لنوع الدهان (Gloss, Semi-Gloss, Matt, Primer/Undercoat) ومقارنته بالـ CPVC النظري.
  2. **مكتبة PubChem العالمية**: محرك استعلام سريع عن المركبات الكيميائية، الصيغة الجزيئية، الوزن المولي، وتصنيف IUPAC، مع إمكانية استيراد المادة مباشرة كـ "مادة خام معتمدة" لكتالوج خامات المصنع بضغطة زر.
  3. **السلامة الكيميائية GHS / SDS**: نظام توثيق مخاطر المواد، رموز التحذير القياسية (Flammable, Corrosive, Toxic, Irritant, Environmental)، وإرشادات التخزين والمناولة الآمنة، وربط ملفات صحائف بيانات السلامة (SDS) الخاصة بالموردين.
  4. **إدارة وحفظ الصيغ وطلبات التشغيل**: أرشفة التركيبات في قاعدة بيانات Room المحلية مع إمكانية التعديل، وإعادة الحساب، وتصدير بطاقة تقرير كيميائي ومشاركة الصيغة، وتوليد أوامر الإنتاج المصنعية.
- **Target Audience / Persona**: مهندسو الجودة والبحث والتطوير (R&D)، كيميائيو خلط الألوان وتطوير التركيبات، ومدراء الإنتاج في مصانع PMP PAINTS.
- **Key Value**: القضاء التام على أخطاء الحساب اليدوي لمعادلات الدهانات، تسريع اعتماد عينات الطلاء الجديدة، وربط القرارات المخبرية مباشرة بسلاسل التوريد والتشغيل الفعلي للمصنع.

---

## 2. User Experience & Visual Design

### Key User Flows

```
[الشاشة الرئيسية / القائمة]
        │
        ▼ (أيقونة Science أو القائمة الجانبية)
┌────────────────────────────────────────────────────────┐
│     PMP Science & Chemistry Lab — واجهة المختبر        │
├────────────────────────────────────────────────────────┤
│ [ Tab 1: محرك PVC ]   [ Tab 2: PubChem ]   [ Tab 3: GHS ]
└───────────┬───────────────────┬───────────────────┬────┘
            │                   │                   │
            ▼                   ▼                   ▼
   ┌─────────────────┐ ┌─────────────────┐ ┌─────────────────┐
   │ إضافة مكونات    │ │ بحث بالاسم أو CAS│ │ بطاقات المخاطر  │
   │ حساب تلقائي حي │ │ جلب الخصائص الحية│ │ رموز GHS الدولية│
   │ تحليل نوع الدهان│ │ إضافة لخامات erp│ │ استعراض SDS     │
   │ حفظ / تصدير PDF │ └─────────────────┘ └─────────────────┘
   │ أمر تشغيل مصنع  │
   └─────────────────┘
```

### Visual Identity & Theme (الهوية البصرية)

- **Palette**:
  - **الأزرق الملكي (Royal Blue - `#1E40AF` / `#2563EB`)**: مخصص لأزرار الإجراءات الرئيسية وبطاقات نتائج الحساب الدقيقة والنسب الحيوية.
  - **الكحلي الداكن المعتمد (Deep Navy - `#0F172A` / `#1E293B`)**: للشريط العلوي وعناوين الأقسام العلمية والبطاقات التحليلية.
  - **خلفيات المحتوى (Clean Laboratory Neutral - `#F8FAFC`)**: رمادي فاتح مريح للعين مع بطاقات بيضاء ناصعة (`#FFFFFF`) وحواف ذات زوايا مستديرة (16.dp) وظلال خفيفة ناعمة.
  - **شارات التحذير والسلامة**: أصفر كهرماني (`#F59E0B`) وأحمر وقائي (`#EF4444`) وأخضر مطابق للمواصفات (`#10B981`).
- **Typography & Direction**:
  - اتجاه كامل من اليمين إلى اليسار (100% RTL Arabic).
  - خط عربي هندسي نقي وواضح متوافق مع نمط Cairo وTajawal، مع إبراز الأرقام والنسب الكيميائية والوحدات الفيزيائية بوضوح (g, kg, g/cm³, %).
- **Mobile-First Layout**:
  - تحويل الجداول الأفقية الضخمة في نسخة الحاسوب إلى بطاقات عمودية قابلة للطي والتوسيع (Expandable Cards).
  - إدخال فوري مرن مع اقتراحات تلقائية لكثافة ونسب المواد الشائعة (Titanium Dioxide, Calcium Carbonate, Pure Acrylic Resin, Water, Defoamer).

---

## 3. Key Product Decisions & Trade-Offs

- **Decision 1: الحساب الهندسي الصارم لـ PVC والـ Total Solids**
  - *Chosen Approach*: تطبيق المعادلات الهندسية الصناعية الدقيقة لعلوم الطلاء:
    $$\text{Pigment Volume} = \sum \frac{\text{Weight}_i}{\text{Density}_i}$$
    $$\text{Binder Volume} = \frac{\text{Resin Weight} \times (\text{Resin Solids \%} / 100)}{\text{Resin Dry Density}}$$
    $$\text{PVC \%} = \frac{\text{Pigment Volume}}{\text{Pigment Volume} + \text{Binder Volume}} \times 100$$
    $$\text{Total Solids \%} = \frac{\text{Sum of Dry Weights}}{\text{Total Batch Weight}} \times 100$$
  - *Why*: إعطاء نتائج مطابقة لمعايير ASTM وISO المعتمدة في مختبرات دهانات PMP، مع التحذير التلقائي عند نقص الكثافة أو تجاوز الـ CPVC (نقطة تركيز الحجم الحرج للصبغة).

- **Decision 2: تكامل PubChem عبر Online PUG REST API الخفيف**
  - *Chosen Approach*: الاتصال المباشر بـ `https://pubchem.ncbi.nlm.nih.gov/rest/pug/compound/...` باستخدام Coroutines القياسية في أندرويد دون الحاجة لأي مكتبات خارجية ثقيلة، مع تخزين مؤقت محلي (In-memory Cache) للمركبات التي تم البحث عنها.
  - *Why*: سرعة فائقة في الاستجابة واستهلاك معدوم للذاكرة ومصداقية تامة للبيانات دون أي محاكاة وهمية.

- **Decision 3: حفظ وتوريد التركيبات في Room Database (Version 8)**
  - *Chosen Approach*: ترقية قاعدة البيانات `PmpDatabase` إلى الإصدار 8 مع كائنين جديدين `FormulationEntity` و `ChemicalSafetyEntity`، وتوفير دالة تحويل مباشرة إلى `FactoryBatch` ومواد خام مصنعية.
  - *Why*: استمرارية البيانات محلياً على الجهاز ودعم العمل في بيئة المعمل والمصنع دون انقطاع.

---

## 4. Technical Architecture & Data Strategy

### System Architecture Diagram

```
┌────────────────────────────────────────────────────────────────────────┐
│                        Jetpack Compose UI Layer                        │
├────────────────────────────────────────────────────────────────────────┤
│ [TopAppBar (Science Icon)]  [MainActivity Navigation]  [Drawer Item]   │
│                                    │                                   │
│                        ChemistryLabScreen.kt                           │
│     ┌───────────────────┬───────────────────┬────────────────────┐     │
│     │   PvcEngineTab    │   PubChemTab      │    GhsSafetyTab    │     │
│     └─────────┬─────────┴─────────┬─────────┴──────────┬─────────┘     │
└───────────────┼───────────────────┼────────────────────┼───────────────┘
                ▼                   ▼                    ▼
┌────────────────────────────────────────────────────────────────────────┐
│                    ViewModel Layer: PmpViewModel                       │
├────────────────────────────────────────────────────────────────────────┤
│ • formulations: StateFlow<List<FormulationEntity>>                     │
│ • chemicalSafetyList: StateFlow<List<ChemicalSafetyEntity>>            │
│ • pubChemSearchState: StateFlow<PubChemSearchState>                    │
│ • calculatePvcFormula(ingredients) -> PvcCalculationResult             │
│ • createProductionOrderFromFormula(formulaId, batchSize)               │
└───────────────┬───────────────────┬────────────────────┬───────────────┘
                ▼                   ▼                    ▼
┌───────────────────────────────────┐ ┌──────────────────────────────────┐
│       Calculation & Service       │ │        Persistence Layer         │
├───────────────────────────────────┤ ├──────────────────────────────────┤
│ • PvcEngine.kt (Math & Chemistry) │ │ • PmpDatabase (v8 Migration)     │
│ • PubChemService.kt (NCBI REST)   │ │ • FormulationEntity & SafetyDao  │
│ • FormulationPdfExporter.kt       │ │ • FactoryOrder Cross-Linkage     │
└───────────────────────────────────┘ └──────────────────────────────────┘
```

### Data Models & Room Schema

1. **`FormulationEntity`**:
   - `id: Long` (Primary Key, autoGenerate)
   - `formulaNo: String` (e.g. "PMP-LAB-2026-001")
   - `productName: String`
   - `category: String` (Matt, Gloss, Semi-Gloss, Primer, Texture, Sealer)
   - `targetPvc: Double`
   - `calculatedPvc: Double`
   - `totalSolidsWeightPercent: Double`
   - `totalSolidsVolumePercent: Double`
   - `totalBatchWeight: Double`
   - `costPerKg: Double`
   - `ingredientsJson: String` (List of RawMaterialItem with weight, density, solids%, cost)
   - `notes: String`
   - `approvedForProduction: Boolean`
   - `createdAt: Long`

2. **`ChemicalSafetyEntity`**:
   - `id: Long` (Primary Key, autoGenerate)
   - `chemicalName: String`
   - `casNumber: String`
   - `ghsPictograms: String` (Comma-separated: GHS02_FLAME, GHS05_CORROSION, etc.)
   - `signalWord: String` (خطر / Danger, تحذير / Warning)
   - `hazardStatements: String`
   - `precautionaryStatements: String`
   - `storageConditions: String`
   - `sdsAttachmentName: String?`

3. **`PvcEngine.kt`**:
   - فئة هندسية مستقلة خالية من أي واجهات مستخدم تقوم بالتحقق من المدخلات، حساب الحجوم الجافة، حساب الـ PVC، وتصنيف المنتج تقنياً وتقديم توصيات كيميائية دقيقة (مثل: انخفاض لمعان الدهان عند تجاوز PVC 45%، أو زيادة مسامية الغشاء عند اقتراب PVC من CPVC).

---

## 5. Implementation Roadmap (Post-Approval)

1. **Database & Models**: إضافة جداول الصيغ والسلامة الكيميائية وترقية Room Database إلى الإصدار 8 مع Migration آمن.
2. **PvcEngine & PubChem Services**: إنشاء محرك الحسابات الكيميائية وخدمة PubChem REST API.
3. **PmpViewModel Extensions**: ربط حالات المختبر وحفظ التركيبات وتوليد أوامر التشغيل للمصنع.
4. **ChemistryLabScreen & UI Components**: بناء الشاشة المتجاوبة بتبويباتها الثلاثة وتصميم البطاقات والرسوم البيانية المصغرة لنسبة الـ PVC.
5. **App Navigation & TopBar Linkage**: إدراج شاشة المختبر في شريط التطبيق العلوي والقائمة الجانبية.
6. **Verification**: تشغيل `compile_applet` والتحقق من سلامة البناء وخلوه من أي أخطاء.
