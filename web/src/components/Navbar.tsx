import React from 'react';
import { useApp, NavSection } from '../context/AppContext';
import {
  Sun,
  Moon,
  Globe,
  Wifi,
  WifiOff,
  Download,
  LogIn,
  LogOut,
  Layers,
  LayoutDashboard,
  ShieldCheck
} from 'lucide-react';

export const Navbar: React.FC = () => {
  const {
    lang,
    setLang,
    theme,
    toggleTheme,
    currentSection,
    setCurrentSection,
    user,
    employeeProfile,
    isOnline,
    signOut
  } = useApp();

  const isAr = lang === 'ar';

  const navItems: { id: NavSection; labelAr: string; labelEn: string; icon: React.ReactNode }[] = [
    { id: 'home', labelAr: 'الرئيسية', labelEn: 'Home', icon: <Layers size={16} /> },
    { id: 'overview', labelAr: 'نظرة عامة', labelEn: 'Overview', icon: <LayoutDashboard size={16} /> },
    { id: 'features', labelAr: 'الأقسام والوحدات', labelEn: 'Modules & Features', icon: <ShieldCheck size={16} /> },
    { id: 'download', labelAr: 'تنزيل التطبيق APK', labelEn: 'Download APK', icon: <Download size={16} /> },
    { id: 'whats-new', labelAr: 'ما الجديد', labelEn: "What's New", icon: <ShieldCheck size={16} /> },
    { id: 'install-guide', labelAr: 'دليل التثبيت', labelEn: 'Install Guide', icon: <Layers size={16} /> },
    { id: 'support', labelAr: 'الدعم والأمان', labelEn: 'Support & Security', icon: <ShieldCheck size={16} /> },
  ];

  return (
    <header className="navbar" style={{
      position: 'sticky',
      top: 0,
      zIndex: 100,
      background: theme === 'true-black' ? 'rgba(10, 10, 13, 0.88)' : 'rgba(255, 255, 255, 0.90)',
      backdropFilter: 'blur(20px)',
      WebkitBackdropFilter: 'blur(20px)',
      borderBottom: '1px solid var(--border-subtle)',
      padding: '12px 24px'
    }}>
      <div style={{
        maxWidth: 1300,
        margin: '0 auto',
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'space-between',
        gap: 16,
        flexWrap: 'wrap'
      }}>
        {/* Brand Identity */}
        <div
          onClick={() => setCurrentSection('home')}
          style={{ display: 'flex', alignItems: 'center', gap: 12, cursor: 'pointer' }}
        >
          <div style={{
            width: 44,
            height: 44,
            borderRadius: 12,
            background: '#FFFFFF',
            border: '1px solid rgba(220, 38, 38, 0.45)',
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
            boxShadow: '0 4px 14px rgba(220, 38, 38, 0.25)',
            overflow: 'hidden'
          }}>
            <img src="/ic_pmp_emblem.png" alt="PMP Paints" style={{ width: 36, height: 36, objectFit: 'contain' }} />
          </div>
          <div>
            <div style={{ fontSize: 18, fontWeight: 900, lineHeight: 1.2 }}>
              PMP <span style={{ color: 'var(--pmp-red)' }}>PAINTS</span> ERP
            </div>
            <div style={{ fontSize: 11, color: 'var(--text-muted)', fontWeight: 600 }}>
              {isAr ? 'المنظومة السحابية الموحدة للمصنع والمخازن' : 'Enterprise ERP Cloud Platform'}
            </div>
          </div>
        </div>

        {/* Navigation Links */}
        <nav style={{ display: 'flex', alignItems: 'center', gap: 14, flexWrap: 'wrap' }}>
          {navItems.map((item) => {
            const isActive = currentSection === item.id;
            return (
              <button
                key={item.id}
                onClick={() => setCurrentSection(item.id)}
                style={{
                  background: isActive ? 'var(--pmp-red-soft)' : 'transparent',
                  color: isActive ? 'var(--pmp-red)' : 'var(--text-secondary)',
                  border: isActive ? '1px solid var(--pmp-red)' : '1px solid transparent',
                  borderRadius: 'var(--radius-md)',
                  padding: '7px 12px',
                  fontSize: 13,
                  fontWeight: isActive ? 800 : 600,
                  cursor: 'pointer',
                  display: 'inline-flex',
                  alignItems: 'center',
                  gap: 6,
                  transition: 'all 0.2s ease'
                }}
              >
                {item.icon}
                <span>{isAr ? item.labelAr : item.labelEn}</span>
              </button>
            );
          })}
        </nav>

        {/* Actions Cluster: Theme, Lang, Online status, Login/Profile */}
        <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
          {/* Network Connection Pill */}
          <div style={{
            display: 'inline-flex',
            alignItems: 'center',
            gap: 5,
            padding: '5px 10px',
            borderRadius: 999,
            fontSize: 11,
            fontWeight: 700,
            background: isOnline ? 'var(--pmp-green-soft)' : 'var(--pmp-red-soft)',
            color: isOnline ? 'var(--pmp-green)' : 'var(--pmp-red)',
            border: `1px solid ${isOnline ? 'var(--pmp-green)' : 'var(--pmp-red)'}`
          }}>
            {isOnline ? <Wifi size={13} /> : <WifiOff size={13} />}
            <span>{isOnline ? (isAr ? 'متصل بالسيرفر' : 'Cloud Online') : (isAr ? 'غير متصل' : 'Offline')}</span>
          </div>

          {/* Theme Toggle */}
          <button
            onClick={toggleTheme}
            title={theme === 'true-black' ? (isAr ? 'الوضع النهاري' : 'Daylight Mode') : (isAr ? 'وضع السواد الحقيقي' : 'True Black Mode')}
            style={{
              background: 'var(--surface-elevated)',
              border: '1px solid var(--border-subtle)',
              borderRadius: 'var(--radius-md)',
              width: 38,
              height: 38,
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'center',
              color: theme === 'true-black' ? 'var(--pmp-gold)' : 'var(--pmp-navy)',
              cursor: 'pointer'
            }}
          >
            {theme === 'true-black' ? <Sun size={18} /> : <Moon size={18} />}
          </button>

          {/* Language Toggle */}
          <button
            onClick={() => setLang(lang === 'ar' ? 'en' : 'ar')}
            title={isAr ? 'Switch to English' : 'التحويل إلى العربية'}
            style={{
              background: 'var(--surface-elevated)',
              border: '1px solid var(--border-subtle)',
              borderRadius: 'var(--radius-md)',
              padding: '6px 10px',
              display: 'flex',
              alignItems: 'center',
              gap: 5,
              fontSize: 12,
              fontWeight: 800,
              color: 'var(--text-secondary)',
              cursor: 'pointer'
            }}
          >
            <Globe size={15} />
            <span>{isAr ? 'EN' : 'عربي'}</span>
          </button>

          {/* Auth Button / Profile */}
          {user ? (
            <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
              <div
                onClick={() => setCurrentSection('overview')}
                style={{
                  display: 'flex',
                  alignItems: 'center',
                  gap: 8,
                  padding: '5px 10px',
                  borderRadius: 'var(--radius-md)',
                  background: 'var(--surface-elevated)',
                  border: '1px solid var(--border-subtle)',
                  cursor: 'pointer'
                }}
              >
                <div style={{
                  width: 28,
                  height: 28,
                  borderRadius: '50%',
                  background: 'var(--pmp-red)',
                  color: '#FFFFFF',
                  display: 'flex',
                  alignItems: 'center',
                  justifyContent: 'center',
                  fontSize: 12,
                  fontWeight: 800
                }}>
                  {user.email?.[0]?.toUpperCase() || 'U'}
                </div>
                <div style={{ textAlign: isAr ? 'right' : 'left' }}>
                  <div style={{ fontSize: 12, fontWeight: 700, color: 'var(--text-primary)', maxWidth: 120, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                    {employeeProfile?.fullName || user.email?.split('@')[0]}
                  </div>
                  <div style={{ fontSize: 10, color: 'var(--pmp-cyan-light)', fontWeight: 600 }}>
                    {employeeProfile?.role || (isAr ? 'حساب معتمد' : 'Verified')}
                  </div>
                </div>
              </div>
              <button
                onClick={signOut}
                title={isAr ? 'تسجيل الخروج' : 'Sign Out'}
                style={{
                  background: 'var(--surface-elevated)',
                  border: '1px solid var(--border-subtle)',
                  borderRadius: 'var(--radius-md)',
                  width: 36,
                  height: 36,
                  display: 'flex',
                  alignItems: 'center',
                  justifyContent: 'center',
                  color: 'var(--pmp-red)',
                  cursor: 'pointer'
                }}
              >
                <LogOut size={16} />
              </button>
            </div>
          ) : (
            <button
              onClick={() => setCurrentSection('login')}
              className="glass-btn glass-btn-primary"
              style={{ padding: '8px 16px', fontSize: 12.5 }}
            >
              <LogIn size={15} />
              <span>{isAr ? 'تسجيل الدخول' : 'Sign In'}</span>
            </button>
          )}
        </div>
      </div>
    </header>
  );
};
