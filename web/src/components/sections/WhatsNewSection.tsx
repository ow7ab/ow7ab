import React from 'react';
import {
  Sparkles,
  CheckCircle2,
  Calendar,
  Download
} from 'lucide-react';
import { GlassCard } from '../ui/GlassCard';
import { GlassBadge } from '../ui/GlassBadge';
import { useLanguage } from '../../context/LanguageContext';
import { APP_VERSION_CONTRACT } from '../../config/appVersionContract';
import { useLatestRelease } from '../../hooks/useLatestRelease';
import { formatReleaseDate } from '../../services/ReleaseService';

export const WhatsNewSection: React.FC = () => {
  const { language } = useLanguage();
  const { latestRelease } = useLatestRelease();

  const directDownloadUrl =
    latestRelease?.downloadUrl || APP_VERSION_CONTRACT.GITHUB_LATEST_APK_URL;
  const activeVersionLabel =
    latestRelease?.versionName || `v${APP_VERSION_CONTRACT.CURRENT_VERSION_NAME}`;
  const activeReleaseDate = formatReleaseDate(latestRelease?.publishedAt);

  const highlights = [
    {
      title: language === 'ar' ? 'إدارة الخزن والحسابات متعددة العملات' : 'Multi-Currency Treasury & Accounts',
      items: [
        'متابعة فورية لأرصدة خزنة السودان (SDG) وخزنة مصر (EGP) وخزنة الدولار (USD)',
        'تسجيل سندات القبض والصرف والتحويلات المالية بين الخزن بسهولة',
        'مطابقة وإقفال الجرد اليومي واستخراج كشوف الحساب المعتمدة'
      ]
    },
    {
      title: language === 'ar' ? 'تشغيل المصنع والمختبر الكيميائي' : 'Factory & Quality Lab Operations',
      items: [
        'إدارة خلطات الدهانات ومعايير الجودة واللزوجة والكثافة بدقة',
        'متابعة أوامر التشغيل اليومية وربطها بخطوط الإنتاج والماكينات',
        'دعم أوزان التعبئة القياسية (250 جرام، 900 جرام، جالون 4 كجم)'
      ]
    },
    {
      title: language === 'ar' ? 'المبيعات والمشتريات والمخازن' : 'Sales, Purchases & Warehouses',
      items: [
        'إصدار فواتير المبيعات النقدية والآجلة ومتابعة مديونيات العملاء',
        'تنظيم فواتير المشتريات ومحاضر استلام الخامات وحسابات الموردين',
        'جرد مستمر للمخازن في الخرطوم ومصر ووادي حلفا وعطبرة'
      ]
    }
  ];

  return (
    <section id="whats-new" className="py-16 relative">
      <div className="max-w-6xl mx-auto px-4 sm:px-6 lg:px-8">
        <div className="text-center max-w-3xl mx-auto mb-12">
          <GlassBadge variant="accent" className="mb-4">
            <Sparkles className="w-3.5 h-3.5 inline ml-1.5" />
            {language === 'ar'
              ? `الجديد في الإصدار ${activeVersionLabel}`
              : `What's New in ${activeVersionLabel}`}
          </GlassBadge>
          <h2 className="text-3xl sm:text-4xl font-extrabold text-theme-primary tracking-tight">
            {language === 'ar'
              ? 'تحديثات مصممة لتسهيل العمل اليومي في كافة الفروع'
              : 'Updates Designed for Smooth Daily Operations Across Branches'}
          </h2>
          <div className="mt-4 flex flex-wrap items-center justify-center gap-4 text-xs sm:text-sm text-theme-secondary">
            <span className="flex items-center gap-1.5">
              <Calendar className="w-4 h-4 text-pmp-primary" />
              {language === 'ar' ? 'تاريخ التحديث:' : 'Release Date:'}{' '}
              <strong className="text-theme-primary">{activeReleaseDate}</strong>
            </span>
            <span>•</span>
            <a
              href={directDownloadUrl}
              download={APP_VERSION_CONTRACT.DOWNLOAD_APK_FILENAME}
              className="inline-flex items-center gap-1.5 text-pmp-primary font-bold hover:underline"
            >
              <Download className="w-4 h-4" />
              <span>{language === 'ar' ? 'تنزيل النسخة المحدثة مباشرة' : 'Direct Download Latest Version'}</span>
            </a>
          </div>
        </div>

        {/* Feature Cards */}
        <div className="grid grid-cols-1 lg:grid-cols-3 gap-6">
          {highlights.map((group, idx) => (
            <GlassCard key={idx} className="p-6 flex flex-col justify-between">
              <div>
                <h3 className="text-lg font-bold text-theme-primary mb-4 pb-3 border-b border-theme">
                  {group.title}
                </h3>
                <ul className="space-y-3">
                  {group.items.map((item, i) => (
                    <li key={i} className="flex items-start gap-2.5 text-sm text-theme-secondary">
                      <CheckCircle2 className="w-4 h-4 text-pmp-success shrink-0 mt-1" />
                      <span>{item}</span>
                    </li>
                  ))}
                </ul>
              </div>
            </GlassCard>
          ))}
        </div>
      </div>
    </section>
  );
};
