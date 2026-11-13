import React, { useState } from 'react';
import { useLanguage } from '../../context/LanguageContext';
import { GlassCard } from '../ui/GlassCard';
import { GlassBadge } from '../ui/GlassBadge';
import {
  Factory,
  FlaskConical,
  Warehouse,
  ShoppingCart,
  Receipt,
  Wallet,
  Tag,
  Users,
  Truck,
  BarChart3,
  Settings,
  CheckCircle,
  ShieldCheck
} from 'lucide-react';

interface ModuleItem {
  id: string;
  nameAr: string;
  nameEn: string;
  category: 'production' | 'commercial' | 'admin';
  icon: React.ReactNode;
  badge: string;
  features: string[];
  permissions: string;
}

export const ModulesShowcase: React.FC = () => {
  const { language } = useLanguage();
  const [activeFilter, setActiveFilter] = useState<'all' | 'production' | 'commercial' | 'admin'>('all');

  const modules: ModuleItem[] = [
    {
      id: 'factory',
      nameAr: 'المصنع وخطوط الإنتاج',
      nameEn: 'Factory & Production Lines',
      category: 'production',
      icon: <Factory size={24} color="var(--pmp-red-light)" />,
      badge: 'العمليات الصناعية',
      features: [
        'إصدار أوامر الإنتاج وحساب خامات التركيبات (BOM)',
        'تخصيص الماكينات والمشرفين ومهندسي الإنتاج',
        'احتساب تكلفة العلبة والكرتونة وإهلاك التشغيل',
        'متابعة هدر الخامات وتحديد الفاقد'
      ],
      permissions: 'المصنع, مواد الإنتاج, التعبئة والتغليف'
    },
    {
      id: 'lab',
      nameAr: 'المختبر الكيميائي وفحص الجودة',
      nameEn: 'Chemistry Lab & Quality Control',
      category: 'production',
      icon: <FlaskConical size={24} color="var(--pmp-cyan-light)" />,
      badge: 'الجودة والتركيبات',
      features: [
        'معادلات التركيب الكيميائي ونسب الخلط الدقيقة',
        'فحص اللزوجة والكثافة ومطابقة درجات الألوان',
        'اختبارات الصلاحية وتحمل العوامل الجوية',
        'شهادات فحص الجودة (QC Inspection)'
      ],
      permissions: 'المختبر الكيميائي, المصنع'
    },
    {
      id: 'inventory',
      nameAr: 'المخازن والمستودعات',
      nameEn: 'Warehouses & Stock Control',
      category: 'production',
      icon: <Warehouse size={24} color="var(--pmp-gold)" />,
      badge: 'المخزون والجرد',
      features: [
        'جرد دائم لبراميل الخامات والعلب الفارغة والكراتين',
        'مخزن المنتج التام بالأوزان القياسية (250g, 900g, 4000g)',
        'تنبيهات تلقائية عند وصول الصنف لحد إعادة الطلب',
        'سجل حركة الصنف (Stock Ledger Card)'
      ],
      permissions: 'المخازن, عمليات المخزون, التحويلات'
    },
    {
      id: 'purchases',
      nameAr: 'المشتريات والموردين',
      nameEn: 'Purchases & Supplier Ledger',
      category: 'commercial',
      icon: <ShoppingCart size={24} color="var(--pmp-green)" />,
      badge: 'سلاسل الإمداد',
      features: [
        'أوامر الشراء المعتمدة (Purchase Orders)',
        'محاضر استلام ومطابقة الشحنات (GRN)',
        'إدارة أرصدة وفواتير موردي الخامات ومواد التعبئة',
        'تسجيل أسعار الشراء بالجنيه المصري والدولار'
      ],
      permissions: 'المشتريات, الموردين, مواد الإنتاج'
    },
    {
      id: 'sales',
      nameAr: 'المبيعات والفواتير',
      nameEn: 'Sales & Customer Invoices',
      category: 'commercial',
      icon: <Receipt size={24} color="var(--pmp-red-light)" />,
      badge: 'الإيرادات والعملاء',
      features: [
        'فواتير مبيعات سريعة مع خصومات الأصناف الفورية',
        'دعم الدفع النقدي والأجل والتقسيط وبوالص التسليم',
        'إدارة مديونيات العملاء والحدود الائتمانية',
        'طباعة إيصالات الدفع وتصدير الفاتورة بصيغة PDF'
      ],
      permissions: 'المبيعات, العملاء, تصدير PDF'
    },
    {
      id: 'treasury',
      nameAr: 'الخزن والسيولة النقدية',
      nameEn: 'Treasury & Cash Vaults',
      category: 'commercial',
      icon: <Wallet size={24} color="var(--pmp-cyan-light)" />,
      badge: 'السيولة والعملات',
      features: [
        'إدارة خزن متعددة (خزنة السودان SDG، مصر EGP، الدولار USD)',
        'تحويلات فورية بين الخزن وتثبيت أسعار الصرف',
        'سندات قبض وصرف يومية مع تصنيفات دقيقة',
        'إغلاق يومي معتمد (Daily Closing) يمنع التلاعب'
      ],
      permissions: 'الخزن, التحويلات, العمليات المالية'
    },
    {
      id: 'prices',
      nameAr: 'قوائم الأسعار والبوسترات',
      nameEn: 'Official Price Posters',
      category: 'commercial',
      icon: <Tag size={24} color="var(--pmp-gold)" />,
      badge: 'الأسعار والوزن',
      features: [
        'بوستر الأسعار المعتمد للعلب وزن 250جم، 900جم، 4000جم',
        'تسعير تلقائي بالجنيه السوداني والجنيه المصري',
        'تعديل أسعار الجملة والتجزئة وتعميمها لحظياً',
        'مزامنة الأسعار فورياً مع تطبيق الهاتف والأجهزة'
      ],
      permissions: 'الأسعار, المبيعات'
    },
    {
      id: 'dashboard',
      nameAr: 'لوحة القيادة الرئيسية والمزامنة اللحظية',
      nameEn: 'Main Executive Dashboard & Cloud Sync',
      category: 'admin',
      icon: <Truck size={24} color="var(--pmp-green)" />,
      badge: 'المركز الموحد',
      features: [
        'مراقبة لحظية لأرصدة الخزن (SDG / EGP / USD) والمخازن',
        'متابعة خطوط الإنتاج ومبيعات وبوالص حلفا والخرطوم ومصر',
        'تنبيهات نواقص المواد الخام وحد إعادة الطلب المباشرة',
        'مزامنة ثنائية لحظية 1:1 بين تطبيق الأندرويد والويب'
      ],
      permissions: 'الرئيسية, المراقبة الحية'
    },
    {
      id: 'users',
      nameAr: 'المستخدمين والأمان RBAC',
      nameEn: 'Users & Enterprise RBAC',
      category: 'admin',
      icon: <Users size={24} color="var(--pmp-red-light)" />,
      badge: 'الأمان والرقابة',
      features: [
        'تحكم دقيق بـ 11 صلاحية تفصيلية لكل موظف',
        'صلاحيات مؤقتة تلقائية الانتهاء (24h, 72h, 168h)',
        'سجل تغييرات الصلاحيات (RBAC Change History)',
        'عزل كامل للمسؤولين المخصصين وحماية حساب Admin'
      ],
      permissions: 'المستخدمين, إدارة المستخدمين'
    },
    {
      id: 'reports',
      nameAr: 'التقارير التحليلية والمالية',
      nameEn: 'Analytics & Financial BI',
      category: 'admin',
      icon: <BarChart3 size={24} color="var(--pmp-cyan-light)" />,
      badge: 'الذكاء المالي',
      features: [
        'تقارير مبيعات تفصيلية وشهرية وتحليل ربحية الأصناف',
        'تقارير استهلاك المواد الخام وتكلفة التصنيع',
        'تقارير أرصدة ومديونيات العملاء والموردين',
        'تصدير مباشر لجداول Excel (XLSX) وملفات PDF'
      ],
      permissions: 'التقارير, التقارير (كاملة)'
    },
    {
      id: 'settings',
      nameAr: 'إعدادات النظام والتحديثات OTA',
      nameEn: 'System Settings & OTA Updates',
      category: 'admin',
      icon: <Settings size={24} color="var(--pmp-gold)" />,
      badge: 'المنظومة والنظام',
      features: [
        'تخصيص المظهر (الوضع المظلم الاحترافي True-Black)',
        'اختيار العملة الافتراضية للمعاملات (SDG, EGP, USD)',
        'إدارة التحديثات السحابية المباشرة وحزم APK الموقعة',
        'ربط السيرفر السحابي وتشخيص صحة الاتصال بالشبكة'
      ],
      permissions: 'الإعدادات, الإعدادات الإدارية'
    }
  ];

  const filteredModules = modules.filter(m => {
    if (activeFilter === 'all') return true;
    return m.category === activeFilter;
  });

  return (
    <section
      id="modules"
      style={{
        padding: '60px 20px',
        maxWidth: '1240px',
        margin: '0 auto'
      }}
    >
      <div style={{ textAlign: 'center', marginBottom: '32px' }}>
        <GlassBadge text="الأقسام والوحدات التشغيلية المعتمدة" variant="red" />
        <h2
          style={{
            fontSize: 'clamp(24px, 3.5vw, 36px)',
            fontWeight: 900,
            color: 'var(--text-primary)',
            marginTop: '12px',
            marginBottom: '12px'
          }}
        >
          {language === 'ar' ? 'منظومة متكاملة تغطي كافة متطلبات مصانع الدهانات' : 'Complete Modules for Paint Manufacturing'}
        </h2>
        <p style={{ fontSize: '15px', color: 'var(--text-secondary)', maxWidth: '700px', margin: '0 auto' }}>
          تم تصميم كل وحدة لتعمل باستقلالية تامة مع الربط اللحظي بقاعدة بيانات Google Cloud المركزية.
        </p>
      </div>

      {/* Filter Tabs */}
      <div
        style={{
          display: 'flex',
          justifyContent: 'center',
          gap: '10px',
          flexWrap: 'wrap',
          marginBottom: '36px'
        }}
      >
        <button
          onClick={() => setActiveFilter('all')}
          style={{
            padding: '8px 18px',
            borderRadius: '999px',
            border: '1px solid var(--border-subtle)',
            background: activeFilter === 'all' ? 'var(--pmp-red)' : 'var(--surface-elevated)',
            color: '#FFFFFF',
            fontWeight: 700,
            fontSize: '13px',
            cursor: 'pointer'
          }}
        >
          {language === 'ar' ? 'كافة الوحدات (11 وحدة)' : 'All Modules (11)'}
        </button>

        <button
          onClick={() => setActiveFilter('production')}
          style={{
            padding: '8px 18px',
            borderRadius: '999px',
            border: '1px solid var(--border-subtle)',
            background: activeFilter === 'production' ? 'var(--pmp-red)' : 'var(--surface-elevated)',
            color: '#FFFFFF',
            fontWeight: 700,
            fontSize: '13px',
            cursor: 'pointer'
          }}
        >
          {language === 'ar' ? 'المصنع والمخازن والتركيبات' : 'Factory & Production'}
        </button>

        <button
          onClick={() => setActiveFilter('commercial')}
          style={{
            padding: '8px 18px',
            borderRadius: '999px',
            border: '1px solid var(--border-subtle)',
            background: activeFilter === 'commercial' ? 'var(--pmp-red)' : 'var(--surface-elevated)',
            color: '#FFFFFF',
            fontWeight: 700,
            fontSize: '13px',
            cursor: 'pointer'
          }}
        >
          {language === 'ar' ? 'المبيعات والمشتريات والخزن' : 'Sales & Treasury'}
        </button>

        <button
          onClick={() => setActiveFilter('admin')}
          style={{
            padding: '8px 18px',
            borderRadius: '999px',
            border: '1px solid var(--border-subtle)',
            background: activeFilter === 'admin' ? 'var(--pmp-red)' : 'var(--surface-elevated)',
            color: '#FFFFFF',
            fontWeight: 700,
            fontSize: '13px',
            cursor: 'pointer'
          }}
        >
          {language === 'ar' ? 'الرقابة والصلاحيات والتقارير' : 'Security & Analytics'}
        </button>
      </div>

      {/* Grid of Modules */}
      <div
        style={{
          display: 'grid',
          gridTemplateColumns: 'repeat(auto-fit, minmax(320px, 1fr))',
          gap: '24px'
        }}
      >
        {filteredModules.map((item) => (
          <GlassCard
            key={item.id}
            style={{
              padding: '24px',
              display: 'flex',
              flexDirection: 'column',
              justifyContent: 'space-between',
              background: 'var(--surface-card)',
              position: 'relative'
            }}
          >
            <div>
              <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '16px' }}>
                <div
                  style={{
                    width: '46px',
                    height: '46px',
                    borderRadius: '12px',
                    background: 'var(--surface-elevated)',
                    border: '1px solid var(--border-subtle)',
                    display: 'flex',
                    alignItems: 'center',
                    justifyContent: 'center'
                  }}
                >
                  {item.icon}
                </div>
                <GlassBadge text={item.badge} variant="cyan" />
              </div>

              <h3 style={{ fontSize: '17px', fontWeight: 800, color: 'var(--text-primary)', marginBottom: '4px' }}>
                {language === 'ar' ? item.nameAr : item.nameEn}
              </h3>

              <div style={{ fontSize: '11px', color: 'var(--text-muted)', marginBottom: '14px' }}>
                {item.nameEn}
              </div>

              <ul style={{ listStyle: 'none', display: 'flex', flexDirection: 'column', gap: '8px', marginBottom: '18px' }}>
                {item.features.map((feat, fIdx) => (
                  <li key={fIdx} style={{ display: 'flex', alignItems: 'flex-start', gap: '8px', fontSize: '12.5px', color: 'var(--text-secondary)' }}>
                    <CheckCircle size={15} color="var(--pmp-green)" style={{ flexShrink: 0, marginTop: '2px' }} />
                    <span>{feat}</span>
                  </li>
                ))}
              </ul>
            </div>

            <div
              style={{
                paddingTop: '12px',
                borderTop: '1px solid var(--border-subtle)',
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'space-between',
                fontSize: '11.5px',
                color: 'var(--text-muted)'
              }}
            >
              <div style={{ display: 'flex', alignItems: 'center', gap: '6px' }}>
                <ShieldCheck size={14} color="var(--pmp-gold)" />
                <span>الصلاحيات المطلوبة:</span>
              </div>
              <span style={{ color: 'var(--pmp-cyan-light)', fontWeight: 600 }}>{item.permissions}</span>
            </div>
          </GlassCard>
        ))}
      </div>
    </section>
  );
};
