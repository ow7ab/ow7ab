import React, { useState } from 'react';
import { Navbar } from '../components/layout/Navbar';
import { Footer } from '../components/layout/Footer';
import { HeroSection } from '../components/sections/HeroSection';
import { OverviewSection } from '../components/sections/OverviewSection';
import { ModulesShowcase } from '../components/sections/ModulesShowcase';
import { DownloadApkSection } from '../components/sections/DownloadApkSection';
import { WhatsNewSection } from '../components/sections/WhatsNewSection';
import { InstallationGuideSection } from '../components/sections/InstallationGuideSection';
import { SupportSection } from '../components/sections/SupportSection';
import { LoginModal } from '../components/sections/LoginModal';

interface HomePageProps {
  onEnterPortal: () => void;
}

export const HomePage: React.FC<HomePageProps> = ({ onEnterPortal }) => {
  const [isLoginOpen, setIsLoginOpen] = useState(false);

  const scrollToSection = (id: string) => {
    const el = document.getElementById(id);
    if (el) {
      el.scrollIntoView({ behavior: 'smooth' });
    }
  };

  return (
    <div className="min-h-screen bg-theme-base text-theme-primary selection:bg-pmp-primary selection:text-white transition-colors duration-200">
      {/* Navigation */}
      <Navbar onOpenLogin={() => setIsLoginOpen(true)} onOpenPortal={onEnterPortal} />

      {/* Main Content Sections */}
      <main>
        {/* 1. Hero */}
        <HeroSection
          onOpenLogin={onEnterPortal}
          onScrollToDownload={() => scrollToSection('download')}
          onScrollToOverview={() => scrollToSection('overview')}
        />

        {/* 2. Overview & Key Stats */}
        <OverviewSection />

        {/* 3. ERP Modules Showcase (11 Modules) */}
        <ModulesShowcase />

        {/* 4. Download Latest APK */}
        <DownloadApkSection />

        {/* 5. What's New & Changelog */}
        <WhatsNewSection />

        {/* 6. Step-by-Step Installation Guide */}
        <InstallationGuideSection />

        {/* 7. Official Support & Branches */}
        <SupportSection />
      </main>

      {/* Footer */}
      <Footer />

      {/* Auth Modal */}
      <LoginModal
        isOpen={isLoginOpen}
        onClose={() => setIsLoginOpen(false)}
        onLoginSuccess={onEnterPortal}
      />
    </div>
  );
};
