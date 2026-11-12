import React, { useState } from 'react';
import { useLanguage } from '../../context/LanguageContext';
import { useTheme } from '../../context/ThemeContext';
import { useAuth } from '../../context/AuthContext';
import { APP_VERSION_CONTRACT } from '../../config/appVersionContract';
import {
  Sun,
  Moon,
  Globe,
  Download,
  LogIn,
  Menu,
  X,
  ShieldCheck,
  UserCheck
} from 'lucide-react';

interface NavbarProps {
  onOpenLogin: () => void;
  onNavigateSection?: (sectionId: string) => void;
}

export const Navbar: React.FC<NavbarProps> = ({ onOpenLogin, onNavigateSection }) => {
  const { t, language, toggleLanguage } = useLanguage();
  const { theme, toggleTheme } = useTheme();
  const { linkedAccount, signOut } = useAuth();
  const [mobileMenuOpen, setMobileMenuOpen] = useState(false);

  const handleNavClick = (id: string) => {
    setMobileMenuOpen(false);
    if (onNavigateSection) {
      onNavigateSection(id);
    } else {
      const el = document.getElementById(id);
      if (el) el.scrollIntoView({ behavior: 'smooth' });
    }
  };

  return (
    <header
      style={{
        position: 'sticky',
        top: 0,
        zIndex: 100,
        background: theme === 'true-black' ? 'rgba(0, 0, 0, 0.88)' : 'rgba(255, 255, 255, 0.90)',
        backdropFilter: 'blur(18px)',
        WebkitBackdropFilter: 'blur(18px)',
        borderBottom: '1px solid var(--border-subtle)',
        padding: '12px 20px'
      }}
    >
      <div
        style={{
          maxWidth: '1240px',
          margin: '0 auto',
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'space-between',
          gap: '16px'
        }}
      >
        {/* Brand identity */}
        <div
          onClick={() => handleNavClick('home')}
          style={{ display: 'flex', alignItems: 'center', gap: '12px', cursor: 'pointer' }}
        >
          <div
            style={{
              width: '42px',
              height: '42px',
              borderRadius: '12px',
              background: '#FFFFFF',
              border: '1.5px solid rgba(220, 38, 38, 0.5)',
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'center',
              boxShadow: '0 4px 14px rgba(220, 38, 38, 0.25)',
              overflow: 'hidden',
              flexShrink: 0
            }}
          >
            <img
              src="/assets/ic_pmp_emblem.png"
              alt="PMP Emblem"
              style={{ width: '34px', height: '34px', objectFit: 'contain' }}
              onError={(e) => {
                // Fallback to text initials if image path varies
                (e.target as HTMLElement).style.display = 'none';
              }}
            />
          </div>

          <div>
            <div style={{ fontSize: '18px', fontWeight: 900, letterSpacing: '-0.3px', lineHeight: 1.2 }}>
              PMP <span style={{ color: 'var(--pmp-red)' }}>PAINTS</span>
            </div>
            <div style={{ fontSize: '11px', color: 'var(--text-secondary)', fontWeight: 600 }}>
              {language === 'ar' ? 'منظومة إدارة الدهانات والصناعات الكيماوية' : 'Industrial ERP & Coatings Platform'}
            </div>
          </div>
        </div>

        {/* Desktop Nav Links */}
        <nav
          style={{
            display: 'flex',
            alignItems: 'center',
            gap: '18px'
          }}
          className="desktop-nav"
        >
          <button
            onClick={() => handleNavClick('home')}
            style={{ background: 'none', border: 'none', color: 'var(--text-secondary)', fontWeight: 700, fontSize: '13.5px', cursor: 'pointer' }}
          >
            {t('nav_home')}
          </button>
          <button
            onClick={() => handleNavClick('overview')}
            style={{ background: 'none', border: 'none', color: 'var(--text-secondary)', fontWeight: 700, fontSize: '13.5px', cursor: 'pointer' }}
          >
            {t('nav_overview')}
          </button>
          <button
            onClick={() => handleNavClick('modules')}
            style={{ background: 'none', border: 'none', color: 'var(--text-secondary)', fontWeight: 700, fontSize: '13.5px', cursor: 'pointer' }}
          >
            {t('nav_modules')}
          </button>
          <button
            onClick={() => handleNavClick('download')}
            style={{ background: 'none', border: 'none', color: 'var(--text-secondary)', fontWeight: 700, fontSize: '13.5px', cursor: 'pointer' }}
          >
            {t('nav_download')}
          </button>
          <button
            onClick={() => handleNavClick('whats-new')}
            style={{ background: 'none', border: 'none', color: 'var(--text-secondary)', fontWeight: 700, fontSize: '13.5px', cursor: 'pointer' }}
          >
            {t('nav_whats_new')}
          </button>
          <button
            onClick={() => handleNavClick('install-guide')}
            style={{ background: 'none', border: 'none', color: 'var(--text-secondary)', fontWeight: 700, fontSize: '13.5px', cursor: 'pointer' }}
          >
            {t('nav_install')}
          </button>
          <button
            onClick={() => handleNavClick('support')}
            style={{ background: 'none', border: 'none', color: 'var(--text-secondary)', fontWeight: 700, fontSize: '13.5px', cursor: 'pointer' }}
          >
            {t('nav_support')}
          </button>
        </nav>

        {/* Action Buttons */}
        <div style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
          {/* Theme Toggle */}
          <button
            onClick={toggleTheme}
            title={theme === 'true-black' ? 'Daylight Mode' : 'True Black Mode'}
            style={{
              width: '38px',
              height: '38px',
              borderRadius: '10px',
              background: 'var(--surface-elevated)',
              border: '1px solid var(--border-subtle)',
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'center',
              cursor: 'pointer',
              color: 'var(--pmp-gold)'
            }}
          >
            {theme === 'true-black' ? <Sun size={18} /> : <Moon size={18} />}
          </button>

          {/* Language Toggle */}
          <button
            onClick={toggleLanguage}
            title={language === 'ar' ? 'English Interface' : 'الواجهة العربية'}
            style={{
              padding: '6px 12px',
              borderRadius: '10px',
              background: 'var(--surface-elevated)',
              border: '1px solid var(--border-subtle)',
              display: 'flex',
              alignItems: 'center',
              gap: '6px',
              fontSize: '12px',
              fontWeight: 700,
              cursor: 'pointer',
              color: 'var(--text-primary)'
            }}
          >
            <Globe size={15} color="var(--pmp-cyan)" />
            <span>{language === 'ar' ? 'EN' : 'عربي'}</span>
          </button>

          {/* Login or Staff Portal */}
          {linkedAccount ? (
            <div style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
              <div
                style={{
                  display: 'flex',
                  alignItems: 'center',
                  gap: '6px',
                  padding: '6px 12px',
                  borderRadius: '10px',
                  background: 'var(--pmp-green-soft)',
                  border: '1px solid rgba(16, 185, 129, 0.3)',
                  color: 'var(--pmp-green)',
                  fontSize: '12px',
                  fontWeight: 700
                }}
              >
                <UserCheck size={14} />
                <span>{linkedAccount.fullName}</span>
              </div>
              <button
                onClick={() => signOut()}
                style={{
                  background: 'transparent',
                  border: '1px solid var(--border-subtle)',
                  borderRadius: '8px',
                  padding: '6px 10px',
                  fontSize: '11px',
                  color: 'var(--text-muted)',
                  cursor: 'pointer'
                }}
              >
                خروج
              </button>
            </div>
          ) : (
            <button
              onClick={onOpenLogin}
              style={{
                display: 'inline-flex',
                alignItems: 'center',
                gap: '6px',
                padding: '8px 16px',
                borderRadius: '10px',
                background: 'var(--gradient-red)',
                color: '#FFFFFF',
                border: 'none',
                fontWeight: 700,
                fontSize: '13px',
                cursor: 'pointer',
                boxShadow: '0 4px 14px rgba(220, 38, 38, 0.35)'
              }}
            >
              <LogIn size={15} />
              <span>{t('nav_login')}</span>
            </button>
          )}

          {/* Mobile hamburger button */}
          <button
            onClick={() => setMobileMenuOpen(!mobileMenuOpen)}
            style={{
              display: 'none',
              width: '38px',
              height: '38px',
              borderRadius: '10px',
              background: 'var(--surface-elevated)',
              border: '1px solid var(--border-subtle)',
              alignItems: 'center',
              justifyContent: 'center',
              color: 'var(--text-primary)',
              cursor: 'pointer'
            }}
            className="mobile-hamburger"
          >
            {mobileMenuOpen ? <X size={20} /> : <Menu size={20} />}
          </button>
        </div>
      </div>

      {/* Mobile Drawer */}
      {mobileMenuOpen && (
        <div
          style={{
            padding: '16px 20px',
            borderTop: '1px solid var(--border-subtle)',
            marginTop: '12px',
            display: 'flex',
            flexDirection: 'column',
            gap: '12px',
            background: 'var(--surface-primary)'
          }}
        >
          <button onClick={() => handleNavClick('home')} style={{ textAlign: 'start', padding: '8px 0', background: 'none', border: 'none', color: 'var(--text-primary)', fontWeight: 700 }}>
            {t('nav_home')}
          </button>
          <button onClick={() => handleNavClick('overview')} style={{ textAlign: 'start', padding: '8px 0', background: 'none', border: 'none', color: 'var(--text-primary)', fontWeight: 700 }}>
            {t('nav_overview')}
          </button>
          <button onClick={() => handleNavClick('modules')} style={{ textAlign: 'start', padding: '8px 0', background: 'none', border: 'none', color: 'var(--text-primary)', fontWeight: 700 }}>
            {t('nav_modules')}
          </button>
          <button onClick={() => handleNavClick('download')} style={{ textAlign: 'start', padding: '8px 0', background: 'none', border: 'none', color: 'var(--text-primary)', fontWeight: 700 }}>
            {t('nav_download')}
          </button>
          <button onClick={() => handleNavClick('whats-new')} style={{ textAlign: 'start', padding: '8px 0', background: 'none', border: 'none', color: 'var(--text-primary)', fontWeight: 700 }}>
            {t('nav_whats_new')}
          </button>
          <button onClick={() => handleNavClick('install-guide')} style={{ textAlign: 'start', padding: '8px 0', background: 'none', border: 'none', color: 'var(--text-primary)', fontWeight: 700 }}>
            {t('nav_install')}
          </button>
          <button onClick={() => handleNavClick('support')} style={{ textAlign: 'start', padding: '8px 0', background: 'none', border: 'none', color: 'var(--text-primary)', fontWeight: 700 }}>
            {t('nav_support')}
          </button>
        </div>
      )}

      <style>{`
        @media (max-width: 900px) {
          .desktop-nav {
            display: none !important;
          }
          .mobile-hamburger {
            display: flex !important;
          }
        }
      `}</style>
    </header>
  );
};
