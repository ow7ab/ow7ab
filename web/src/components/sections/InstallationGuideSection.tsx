import React from 'react';
import { DownloadCloud, ShieldAlert, CheckSquare, RefreshCw, KeyRound, Smartphone } from 'lucide-react';
import { GlassCard } from '../ui/GlassCard';
import { GlassBadge } from '../ui/GlassBadge';
import { useLanguage } from '../../context/LanguageContext';

export const InstallationGuideSection: React.FC = () => {
  const { language } = useLanguage();

  const steps = [
    {
      step: '01',
      title: language === 'ar' ? 'تحميل ملف الحزمة الرسمي' : 'Download Official APK',
      desc: language === 'ar'
        ? 'انقر على زر "تحميل ملف APK المباشر" من قسم التحميل لتحميل أحدث ملف حزمة موقع بشهادة المؤسسة الرسمية.'
        : 'Click "Download APK File" to get the latest cryptographically signed build directly.',
      icon: <DownloadCloud className="w-6 h-6 text-pmp-primary" />
    },
    {
      step: '02',
      title: language === 'ar' ? 'السماح بالتثبيت من المتصفح' : 'Enable Unknown Sources',
      desc: language === 'ar'
        ? 'إذا ظهر لك تنبيه أمان أندرويد "ملف غير معروف"، ادخل إلى إعدادات الهاتف وفعل خيار "السماح بالتثبيت من هذا المصدر" لمتصفح Chrome أو متصفحك الحالي.'
        : 'If prompted by Android, go to device Settings and toggle "Allow from this source" for your browser.',
      icon: <ShieldAlert className="w-6 h-6 text-pmp-warning" />
    },
    {
      step: '03',
      title: language === 'ar' ? 'تأكيد التثبيت أو الترقية' : 'Confirm Installation or Upgrade',
      desc: language === 'ar'
        ? 'افتح ملف APK واضغط على "تثبيت" (Install) أو "تحديث" (Update). لن تفقد أي بيانات مخزنة على جهازك لأن الحزمة تحتفظ بنفس معرّف التطبيق وشهادة التوقيع.'
        : 'Open the downloaded APK and tap "Install" or "Update". Existing local data and accounts remain 100% intact.',
      icon: <CheckSquare className="w-6 h-6 text-pmp-success" />
    },
    {
      step: '04',
      title: language === 'ar' ? 'تسجيل الدخول وبدء العمل' : 'Sign In & Start Operations',
      desc: language === 'ar'
        ? 'سجل دخولك باستخدام بريد موظف PMP المعتمد أو اسم المستخدم والرمز السري (PIN) المرتبط بحسابك وقم بمزامنة بيانات فروعك فوراً.'
        : 'Sign in using your authorized enterprise email or PIN-linked username to immediately synchronize branch records.',
      icon: <KeyRound className="w-6 h-6 text-pmp-accent" />
    }
  ];

  return (
    <section id="installation" className="py-20 relative bg-theme-base/50">
      <div className="max-w-6xl mx-auto px-4 sm:px-6 lg:px-8">
        <div className="text-center max-w-3xl mx-auto mb-14">
          <GlassBadge variant="primary" className="mb-4">
            <Smartphone className="w-3.5 h-3.5 inline ml-1.5" />
            {language === 'ar' ? 'دليل التثبيت والترقية' : 'Step-by-Step Installation Guide'}
          </GlassBadge>
          <h2 className="text-3xl sm:text-4xl font-extrabold text-theme-primary tracking-tight">
            {language === 'ar' ? 'كيفية تثبيت وترقية التطبيق على أجهزة أندرويد' : 'How to Install and Upgrade on Android Devices'}
          </h2>
          <p className="mt-4 text-base sm:text-lg text-theme-secondary">
            {language === 'ar'
              ? 'خطوات بسيطة وسريعة لتثبيت التطبيق على هواتف وأجهزة التابلت الخاصة بموظفي المصانع والمستودعات.'
              : 'Simple steps to set up or update the ERP app on enterprise mobile phones and industrial warehouse tablets.'}
          </p>
        </div>

        <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-4 gap-6 mb-12">
          {steps.map((item, idx) => (
            <GlassCard key={idx} className="p-6 relative group hover:-translate-y-1 transition-all">
              <div className="flex justify-between items-start mb-4">
                <div className="p-3 rounded-xl bg-theme-surface border border-theme">
                  {item.icon}
                </div>
                <span className="text-2xl font-black text-theme-muted font-mono opacity-40 group-hover:opacity-100 group-hover:text-pmp-primary transition-opacity">
                  {item.step}
                </span>
              </div>
              <h3 className="text-lg font-bold text-theme-primary mb-2">
                {item.title}
              </h3>
              <p className="text-sm text-theme-secondary leading-relaxed">
                {item.desc}
              </p>
            </GlassCard>
          ))}
        </div>

        {/* Update note */}
        <div className="p-6 rounded-2xl border border-theme bg-theme-surface flex flex-col sm:flex-row items-center justify-between gap-4">
          <div className="flex items-center gap-4">
            <div className="p-3 rounded-full bg-pmp-accent/10 text-pmp-accent shrink-0">
              <RefreshCw className="w-6 h-6" />
            </div>
            <div>
              <h4 className="text-base font-bold text-theme-primary">
                {language === 'ar' ? 'التحديثات الهوائية التلقائية (OTA)' : 'Seamless In-App Over-The-Air (OTA) Updates'}
              </h4>
              <p className="text-xs sm:text-sm text-theme-secondary mt-0.5">
                {language === 'ar'
                  ? 'بعد التثبيت الأول، سيقوم التطبيق تلقائياً بفحص ملف version.json وتنبيهك فور توفر أي إصدار جديد للتحميل والتثبيت التلقائي بنقرة واحدة.'
                  : 'Once installed, the app automatically checks version.json and alerts you to install updates with one tap.'}
              </p>
            </div>
          </div>
        </div>
      </div>
    </section>
  );
};
