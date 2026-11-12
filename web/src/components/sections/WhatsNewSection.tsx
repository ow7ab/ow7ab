import React from 'react';
import { Sparkles, Shield, Users, Layers, Zap, Database, ArrowUpRight } from 'lucide-react';
import { GlassCard } from '../ui/GlassCard';
import { GlassBadge } from '../ui/GlassBadge';
import { useLanguage } from '../../context/LanguageContext';

export const WhatsNewSection: React.FC = () => {
  const { language } = useLanguage();

  const releaseHighlights = [
    {
      version: 'v18.0 (Build 18)',
      date: 'نوفمبر 2026',
      badge: language === 'ar' ? 'الإصدار الحالي المستقر' : 'Current Stable Release',
      badgeVariant: 'success' as const,
      features: [
        {
          title: language === 'ar' ? 'نظام صلاحيات RBAC دقيق وشامل (11 عملية)' : 'Granular Enterprise RBAC (11 Actions)',
          desc: language === 'ar'
            ? 'فصل كامل بين صلاحيات العرض، الإنشاء، التعديل، الحذف، الاعتماد، التصدير، الطباعة، العمليات المالية، حركات المخازن، إدارة المستخدمين، والإعدادات.'
            : 'Granular segregation of duties: View, Create, Edit, Delete, Approve, Export, Print, Financial Ops, Warehouse Ops, User Mgmt, and System Settings.',
          icon: <Shield className="w-5 h-5 text-pmp-accent" />
        },
        {
          title: language === 'ar' ? 'صلاحيات طوارئ مؤقتة ومحددة زمنياً' : 'Temporary Expiring Permissions',
          desc: language === 'ar'
            ? 'إمكانية منح الموظف تفويضاً مؤقتاً بصلاحية معينة تنتهي تلقائياً بعد فترة زمنية محددة مع تسجيل كامل في سجل التدقيق.'
            : 'Grant staff time-boxed authorization with automatic revocation and immutable audit logging.',
          icon: <Zap className="w-5 h-5 text-pmp-warning" />
        },
        {
          title: language === 'ar' ? 'سجل تدقيق أمني غير قابل للتعديل (Audit Trail)' : 'Tamper-Evident Audit Trail',
          desc: language === 'ar'
            ? 'توثيق فوري لجميع حركات المنح، السحب، محاولات الدخول، والعمليات الحساسة في كوليكشن /audit_logs.'
            : 'Instant tracking of permission grants, revokes, auth attempts, and sensitive operational changes.',
          icon: <Layers className="w-5 h-5 text-pmp-primary" />
        },
        {
          title: language === 'ar' ? 'مزامنة سحابية فائقة السرعة مع الوضع الليلي الفاحم' : 'Ultra-Fast Cloud Sync & True-Black Dark Mode',
          desc: language === 'ar'
            ? 'تحديث محرك المزامنة في Android مع تحسين شامل لشاشات OLED بالوضع الليلي True-Black لراحة العين واستهلاك البطارية.'
            : 'Upgraded Android sync engine with pure True-Black OLED dark styling for energy efficiency and eye comfort.',
          icon: <Database className="w-5 h-5 text-pmp-accent" />
        }
      ]
    },
    {
      version: 'v17.0 (Build 17)',
      date: 'أكتوبر 2026',
      badge: language === 'ar' ? 'إصدار سابق' : 'Previous Release',
      badgeVariant: 'secondary' as const,
      features: [
        {
          title: language === 'ar' ? 'تحديث معايير التعبئة الثلاثية وربط مصنع مصر ومخزن حلفا' : 'Triple Packaging Standards & Halfa Transit',
          desc: language === 'ar'
            ? 'اعتماد حسابات التعبئة بدقة لعلب 250جم (48 علبة)، 900جم (12 علبة)، والجالون 4كجم (4 علب) مع شاشات تتبع بضاعة العبور.'
            : 'Production and carton tracking for 250g, 900g, and 4kg sizes with dedicated Halfa transit logistics.',
          icon: <Users className="w-5 h-5 text-pmp-muted" />
        }
      ]
    }
  ];

  return (
    <section id="whats-new" className="py-20 relative">
      <div className="max-w-6xl mx-auto px-4 sm:px-6 lg:px-8">
        <div className="text-center max-w-3xl mx-auto mb-14">
          <GlassBadge variant="accent" className="mb-4">
            <Sparkles className="w-3.5 h-3.5 inline ml-1.5" />
            {language === 'ar' ? 'سجل التحديثات والتطوير' : 'Release Notes & Changelog'}
          </GlassBadge>
          <h2 className="text-3xl sm:text-4xl font-extrabold text-theme-primary tracking-tight">
            {language === 'ar' ? 'ما الجديد في PMP Paints ERP؟' : 'What is New in PMP Paints ERP?'}
          </h2>
          <p className="mt-4 text-base sm:text-lg text-theme-secondary">
            {language === 'ar'
              ? 'نحرص على ترقية النظام بصفة مستمرة لضمان أعلى معايير الأمان المالي والرقابي واللوجستي لمصانعنا وفروعنا.'
              : 'Continuous updates ensuring enterprise-grade security, auditing, and multi-currency factory operations.'}
          </p>
        </div>

        <div className="space-y-12">
          {releaseHighlights.map((release, idx) => (
            <div key={idx} className="relative">
              <div className="flex flex-wrap items-center justify-between gap-4 mb-6">
                <div className="flex items-center gap-3">
                  <h3 className="text-2xl font-bold text-theme-primary">{release.version}</h3>
                  <GlassBadge variant={release.badgeVariant}>{release.badge}</GlassBadge>
                </div>
                <span className="text-sm text-theme-muted font-medium">{release.date}</span>
              </div>

              <div className="grid grid-cols-1 md:grid-cols-2 gap-6">
                {release.features.map((feature, fIdx) => (
                  <GlassCard key={fIdx} className="p-6 transition-all hover:border-theme-hover">
                    <div className="flex items-start gap-4">
                      <div className="p-2.5 rounded-xl bg-theme-surface border border-theme shrink-0">
                        {feature.icon}
                      </div>
                      <div>
                        <h4 className="text-base font-bold text-theme-primary mb-1.5">
                          {feature.title}
                        </h4>
                        <p className="text-sm text-theme-secondary leading-relaxed">
                          {feature.desc}
                        </p>
                      </div>
                    </div>
                  </GlassCard>
                ))}
              </div>
            </div>
          ))}
        </div>
      </div>
    </section>
  );
};
