import React from 'react';
import { useLanguage } from '../../context/LanguageContext';
import { GlassCard } from '../ui/GlassCard';
import { GlassBadge } from '../ui/GlassBadge';
import {
  Factory,
  ShoppingCart,
  FlaskConical,
  Package,
  Warehouse,
  Receipt,
  Wallet,
  ShieldAlert,
  ArrowLeft,
  ArrowRight
} from 'lucide-react';

export const OverviewSection: React.FC = () => {
  const { t, language, direction } = useLanguage();
  const ArrowIcon = direction === 'rtl' ? ArrowLeft : ArrowRight;

  const cycleSteps = [
    {
      step: '01',
      title: 'المشتريات والموردين',
      desc: 'تسجيل طلبات الاحتياج وأوامر الشراء المعتمدة، ثم استلام الخامات بمحاضر فحص رسمية (GRN) ومطابقة الكميات الفعلية مع فواتير المورد.',
      icon: <ShoppingCart size={22} color="var(--pmp-red-light)" />
    },
    {
      step: '02',
      title: 'المختبر الكيميائي وضبط الجودة',
      desc: 'إعداد التركيبات القياسية (Lab Formulas)، فحص درجات اللزوجة والكثافة ومطابقة ألوان البوهية، واختبار العينات قبل بدء التشغيل.',
      icon: <FlaskConical size={22} color="var(--pmp-cyan-light)" />
    },
    {
      step: '03',
      title: 'المصنع وأوامر الإنتاج (BOM)',
      desc: 'حساب خامات الخلط بدقة متناهية من جدول BOM، تخصيص خطوط وماكينات الإنتاج، واحتساب تكلفة التصنيع وتكاليف التشغيل.',
      icon: <Factory size={22} color="var(--pmp-gold)" />
    },
    {
      step: '04',
      title: 'التعبئة والتغليف المعياري',
      desc: 'تعبئة البوهية في علب قياسية (250 جرام = 48 علبة/كرتونة، 900 جرام = 12 علبة/كرتونة، 4000 جرام جالون = 4 علب/كرتونة).',
      icon: <Package size={22} color="var(--pmp-green)" />
    },
    {
      step: '05',
      title: 'المخازن والترحيل الحدودي',
      desc: 'إيداع المنتج التام في المخازن، صرف الخامات للمصنع، وإصدار بوالص ترحيل بين فرع مصر وحلفا والخرطوم وعطبرة.',
      icon: <Warehouse size={22} color="var(--pmp-cyan-light)" />
    },
    {
      step: '06',
      title: 'المبيعات وحسابات العملاء',
      desc: 'إصدار فواتير المبيعات الفورية بالعملة المختارة (SDG أو EGP)، تتبع مديونيات العملاء، طباعة الفواتير وتصدير كشوف الحسابات.',
      icon: <Receipt size={22} color="var(--pmp-red-light)" />
    },
    {
      step: '07',
      title: 'الخزن والسيولة النقدية',
      desc: 'إدارة تدفقات السيولة في خزنة السودان وخزنة مصر وخزنة الدولار، توثيق التحويلات البنكية والنقدية، وتوليد الإغلاقات اليومية المعتمدة.',
      icon: <Wallet size={22} color="var(--pmp-gold)" />
    },
    {
      step: '08',
      title: 'الرقابة والصلاحيات وسجل التدقيق',
      desc: 'صلاحيات دقيقة (11 نوع عملية)، منع تعارض المصالح (Segregation of Duties)، وتوثيق كامل لكل عملية في سجل غير قابل للتعديل.',
      icon: <ShieldAlert size={22} color="var(--pmp-green)" />
    }
  ];

  return (
    <section
      id="overview"
      style={{
        padding: '60px 20px',
        maxWidth: '1240px',
        margin: '0 auto'
      }}
    >
      <div style={{ textAlign: 'center', marginBottom: '40px' }}>
        <GlassBadge text="الدورة الصناعية والمالية الكاملة" variant="cyan" />
        <h2
          style={{
            fontSize: 'clamp(24px, 3.5vw, 36px)',
            fontWeight: 900,
            color: 'var(--text-primary)',
            marginTop: '12px',
            marginBottom: '12px'
          }}
        >
          {language === 'ar' ? 'كيف تدار دورة العمل في مصانع PMP Paints؟' : 'End-to-End Industrial ERP Workflow'}
        </h2>
        <p
          style={{
            fontSize: '15px',
            color: 'var(--text-secondary)',
            maxWidth: '750px',
            margin: '0 auto',
            lineHeight: 1.6
          }}
        >
          دورة متكاملة تبدأ من توريد المواد الخام وفحصها كيميائياً، مروراً بالتصنيع والتعبئة الدقيقة، وصولاً إلى الفوترة وتغذية الخزن والتقارير الرقابية.
        </p>
      </div>

      <div
        style={{
          display: 'grid',
          gridTemplateColumns: 'repeat(auto-fit, minmax(270px, 1fr))',
          gap: '20px'
        }}
      >
        {cycleSteps.map((step, idx) => (
          <GlassCard
            key={idx}
            style={{
              padding: '24px',
              display: 'flex',
              flexDirection: 'column',
              justifyContent: 'space-between',
              position: 'relative'
            }}
          >
            <div>
              <div
                style={{
                  display: 'flex',
                  alignItems: 'center',
                  justifyContent: 'space-between',
                  marginBottom: '16px'
                }}
              >
                <div
                  style={{
                    width: '44px',
                    height: '44px',
                    borderRadius: '12px',
                    background: 'var(--surface-elevated)',
                    border: '1px solid var(--border-subtle)',
                    display: 'flex',
                    alignItems: 'center',
                    justifyContent: 'center'
                  }}
                >
                  {step.icon}
                </div>
                <span
                  style={{
                    fontSize: '13px',
                    fontWeight: 900,
                    color: 'var(--text-muted)',
                    fontFamily: 'monospace'
                  }}
                >
                  STEP {step.step}
                </span>
              </div>

              <h3 style={{ fontSize: '16px', fontWeight: 800, color: 'var(--text-primary)', marginBottom: '8px' }}>
                {step.title}
              </h3>

              <p style={{ fontSize: '13px', color: 'var(--text-secondary)', lineHeight: 1.65 }}>
                {step.desc}
              </p>
            </div>
          </GlassCard>
        ))}
      </div>
    </section>
  );
};
