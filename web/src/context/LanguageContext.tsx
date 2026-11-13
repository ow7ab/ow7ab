import React, { createContext, useContext, useState, useEffect } from 'react';

export type Language = 'ar' | 'en';

interface LanguageContextType {
  language: Language;
  direction: 'rtl' | 'ltr';
  isRtl: boolean;
  setLanguage: (lang: Language) => void;
  toggleLanguage: () => void;
  t: (key: string) => string;
}

const translations: Record<Language, Record<string, string>> = {
  ar: {
    app_title: 'PMP PAINTS ERP',
    app_subtitle: 'المنظومة الصناعية والمالية السحابية المتكاملة',
    tagline: 'نظام إدارة الحسابات، الخزن، المصنع، والمخازن لشركة PMP Paints للصناعات الكيماوية والطلاءات',
    nav_home: 'الرئيسية',
    nav_overview: 'نظرة عامة',
    nav_modules: 'الأقسام والوحدات',
    nav_download: 'تنزيل التطبيق APK',
    nav_whats_new: 'ما الجديد',
    nav_install: 'دليل التثبيت',
    nav_support: 'الدعم الفني',
    nav_portal: 'بوابة الموظفين',
    nav_login: 'تسجيل الدخول',
    btn_download_now: 'تنزيل الإصدار المعتمد الآن (APK)',
    btn_open_portal: 'الدخول إلى بوابة ERP السحابية',
    latest_version: 'الإصدار المعتمد',
    build_code: 'رقم البناء',
    verified_security: 'نسخة رسمية آمنة ومعتمدة',
    system_status_online: 'السيرفر السحابي متصل ويعمل بكفاءة',
    hero_badge: 'منظومة إدارة صناعة الدهانات والطلاءات الكيماوية',
    factory_title: 'المصنع وخطوط الإنتاج',
    factory_desc: 'إدارة أوامر التصنيع، نسب الخلط الدقيقة للتركيبات (BOM)، تكلفة العلبة والكرتونة، ومتابعة هدر الخامات.',
    treasury_title: 'الخزن والسيولة النقدية',
    treasury_desc: 'إدارة الخزن المتعددة (السودان SDG، مصر EGP، الدولار USD)، التحويلات اللحظية، وتوثيق الإغلاقات اليومية.',
    sales_title: 'المبيعات والفواتير',
    sales_desc: 'إصدار الفواتير الفورية، إدارة ديون وأرصدة العملاء، طباعة إيصالات السداد، وتصدير التقارير بصيغة PDF.',
    inventory_title: 'المخازن والمواد الخام',
    inventory_desc: 'جرد مستمر لبراميل الخامات والمنتج التام (250جم، 900جم، 4000جم)، تنبيهات حد الطلب، وبطاقات حركة الصنف.',
    purchases_title: 'المشتريات والموردين',
    purchases_desc: 'أوامر الشراء المعتمدة، محاضر استلام وفحص الجودة (GRN)، وإدارة حسابات الموردين بدقة.',
    qc_title: 'المختبر الكيميائي والجودة',
    qc_desc: 'معادلات التركيب الكيميائي، فحص اللزوجة والكثافة، وضمان مطابقة درجات الألوان والمعايير القياسية.',
    users_title: 'الأمان والرقابة وصلاحيات الموظفين',
    users_desc: 'فصل صارم للمسؤوليات (11 صلاحية تفصيلية)، صلاحيات مؤقتة تلقائية الانتهاء، وسجل تدقيق غير قابل للتعديل.',
    reports_title: 'التقارير التحليلية والذكاء المالي',
    reports_desc: 'مؤشرات الأداء اللحظية، هوامش الربح، تقارير الاستهلاك، والتصدير المباشر لجداول Excel وPDF.',
    footer_rights: 'جميع الحقوق محفوظة © PMP Paints للصناعات الكيماوية والطلاءات.'
  },
  en: {
    app_title: 'PMP PAINTS ERP',
    app_subtitle: 'Integrated Enterprise Industrial & Financial Cloud Platform',
    tagline: 'Enterprise Accounting, Treasury, Factory, and Warehouse ERP System for PMP Paints Chemical Industries & Coatings',
    nav_home: 'Home',
    nav_overview: 'Overview',
    nav_modules: 'Modules',
    nav_download: 'Download APK',
    nav_whats_new: "What's New",
    nav_install: 'Installation Guide',
    nav_support: 'Technical Support',
    nav_portal: 'Staff Portal',
    nav_login: 'Login',
    btn_download_now: 'Download Official APK Now',
    btn_open_portal: 'Open Cloud ERP Portal',
    latest_version: 'Official Release',
    build_code: 'Build Code',
    verified_security: 'Official Verified Release',
    system_status_online: 'Cloud Server Online & Operational',
    hero_badge: 'Paint Manufacturing & Chemical Coatings ERP',
    factory_title: 'Factory & Manufacturing Lines',
    factory_desc: 'Manage production batches, formula BOM ratios, cost accounting per can & carton, and material loss tracking.',
    treasury_title: 'Treasury & Cash Vaults',
    treasury_desc: 'Multi-currency vaults (Sudan SDG, Egypt EGP, Dollar USD), instant transfers, and signed daily balance closings.',
    sales_title: 'Sales & Invoicing',
    sales_desc: 'Instant invoice generation, customer credit tracking, installment vouchers, receipt printing, and PDF export.',
    inventory_title: 'Warehouses & Raw Materials',
    inventory_desc: 'Perpetual inventory for raw drums & finished goods (250g, 900g, 4000g), reorder alerts, and item ledger cards.',
    purchases_title: 'Purchases & Suppliers',
    purchases_desc: 'Approved supplier purchase orders, goods receipt & inspection notes (GRN), and vendor ledger reconciliation.',
    qc_title: 'Chemistry Lab & Quality Control',
    qc_desc: 'Formulation chemical recipes, viscosity & density test suites, and strict color consistency standards.',
    users_title: 'Security & Staff Permissions',
    users_desc: 'Strict segregation of duties (11 granular permissions), expiring temporary permissions, and immutable audit logs.',
    reports_title: 'Executive Analytics & BI Reports',
    reports_desc: 'Real-time KPIs, gross & net margins, raw consumption rates, and direct export to Excel & PDF.',
    footer_rights: 'All rights reserved © PMP Paints Chemical Industries & Coatings.'
  }
};

const LanguageContext = createContext<LanguageContextType>({
  language: 'ar',
  direction: 'rtl',
  isRtl: true,
  setLanguage: () => {},
  toggleLanguage: () => {},
  t: (key: string) => key
});

export const LanguageProvider: React.FC<{ children: React.ReactNode }> = ({ children }) => {
  const [language, setLanguageState] = useState<Language>(() => {
    const saved = localStorage.getItem('pmp_web_lang') as Language;
    return saved === 'en' ? 'en' : 'ar';
  });

  const direction = language === 'ar' ? 'rtl' : 'ltr';
  const isRtl = direction === 'rtl';

  useEffect(() => {
    document.documentElement.setAttribute('lang', language);
    document.documentElement.setAttribute('dir', direction);
    localStorage.setItem('pmp_web_lang', language);
  }, [language, direction]);

  const toggleLanguage = () => {
    setLanguageState(prev => (prev === 'ar' ? 'en' : 'ar'));
  };

  const setLanguage = (lang: Language) => {
    setLanguageState(lang);
  };

  const t = (key: string): string => {
    return translations[language][key] || translations['ar'][key] || key;
  };

  return (
    <LanguageContext.Provider value={{ language, direction, isRtl, setLanguage, toggleLanguage, t }}>
      {children}
    </LanguageContext.Provider>
  );
};

export const useLanguage = () => useContext(LanguageContext);
