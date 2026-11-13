import React, { useState } from 'react';
import { Navbar } from '../components/layout/Navbar';
import { Footer } from '../components/layout/Footer';
import { DownloadApkSection } from '../components/sections/DownloadApkSection';
import { WhatsNewSection } from '../components/sections/WhatsNewSection';
import { InstallationGuideSection } from '../components/sections/InstallationGuideSection';
import { SupportSection } from '../components/sections/SupportSection';
import { LoginModal } from '../components/sections/LoginModal';
import { AppRoute } from '../utils/routing';
import { useLanguage } from '../context/LanguageContext';
import { ArrowRight, ArrowLeft, ShieldCheck } from 'lucide-react';

interface DownloadPageProps {
  onNavigateRoute: (route: AppRoute) => void;
  onEnterPortal: () => void;
}

/**
 * Dedicated public /download route merging Project A (Official APK Download Website)
 * directly inside the unified PMP PAINTS ERP web shell.
 * Accessible without authentication; exposes zero private ERP records.
 */
export const DownloadPage: React.FC<DownloadPageProps> = ({
  onNavigateRoute,
  onEnterPortal
}) => {
  const { language, direction } = useLanguage();
  const [isLoginOpen, setIsLoginOpen] = useState(false);
  const BackArrow = direction === 'rtl' ? ArrowRight : ArrowLeft;

  const scrollToSection = (id: string) => {
    const el = document.getElementById(id);
    if (el) {
      el.scrollIntoView({ behavior: 'smooth' });
    }
  };

  return (
    <div className="min-h-screen bg-theme-base text-theme-primary selection:bg-pmp-primary selection:text-white transition-colors duration-200">
      <Navbar
        currentRoute="/download"
        onNavigateRoute={onNavigateRoute}
        onOpenLogin={() => onNavigateRoute('/login')}
        onOpenPortal={onEnterPortal}
        onNavigateSection={(id) => {
          if (id === 'download' || id === 'whats-new' || id === 'installation' || id === 'support') {
            scrollToSection(id);
          } else {
            onNavigateRoute('/');
            setTimeout(() => {
              const el = document.getElementById(id);
              if (el) el.scrollIntoView({ behavior: 'smooth' });
            }, 80);
          }
        }}
      />

      <div className="max-w-6xl mx-auto px-4 sm:px-6 lg:px-8 pt-6">
        <div className="flex flex-wrap items-center justify-between gap-3 pb-4 border-b border-theme">
          <button
            type="button"
            onClick={() => onNavigateRoute('/')}
            className="inline-flex items-center gap-2 text-xs font-bold text-theme-secondary hover:text-pmp-primary transition-colors min-h-[40px]"
          >
            <BackArrow className="w-4 h-4" />
            <span>
              {language === 'ar' ? 'العودة إلى الرئيسية (منظومة ERP)' : 'Back to ERP Homepage'}
            </span>
          </button>

          <div className="inline-flex items-center gap-2 px-3 py-1.5 rounded-full bg-emerald-500/10 border border-emerald-500/25 text-emerald-400 text-xs font-bold">
            <ShieldCheck className="w-4 h-4 shrink-0" />
            <span>
              {language === 'ar'
                ? 'بوابة التحميل الرسمية — متصلة مباشرة بإصدارات GitHub المستقرة'
                : 'Official Download Portal — Live Stable GitHub Releases'}
            </span>
          </div>
        </div>
      </div>

      <main>
        <DownloadApkSection />
        <WhatsNewSection />
        <InstallationGuideSection />
        <SupportSection />
      </main>

      <Footer onNavigateRoute={onNavigateRoute} />

      <LoginModal
        isOpen={isLoginOpen}
        onClose={() => setIsLoginOpen(false)}
        onLoginSuccess={onEnterPortal}
        onNavigateRoute={onNavigateRoute}
      />
    </div>
  );
};
