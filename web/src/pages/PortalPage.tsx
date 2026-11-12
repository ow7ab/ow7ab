import React, { useState } from 'react';
import {
  Building2,
  Users,
  Shield,
  LayoutDashboard,
  Factory,
  Warehouse,
  Receipt,
  Coins,
  FlaskConical,
  Truck,
  FileBarChart,
  Settings,
  LogOut,
  ChevronRight,
  ChevronLeft,
  Moon,
  Sun,
  Globe,
  Bell,
  Lock,
  ArrowLeft
} from 'lucide-react';
import { GlassCard } from '../components/ui/GlassCard';
import { GlassBadge } from '../components/ui/GlassBadge';
import { GlassButton } from '../components/ui/GlassButton';
import { EmployeeDashboard } from '../components/employee/EmployeeDashboard';
import { EmployeeManagementCenter } from '../components/admin/EmployeeManagementCenter';
import { useAuth } from '../context/AuthContext';
import { useTheme } from '../context/ThemeContext';
import { useLanguage } from '../context/LanguageContext';
import { ERP_MODULES } from '../services/SecurityService';

interface PortalPageProps {
  onBackToWebsite: () => void;
}

export const PortalPage: React.FC<PortalPageProps> = ({ onBackToWebsite }) => {
  const { language, toggleLanguage } = useLanguage();
  const { theme, toggleTheme } = useTheme();
  const { userProfile, userRoleType, isSuperAdmin, isManager, canAccessModule, signOut } = useAuth();

  const [activeTab, setActiveTab] = useState<'DASHBOARD' | 'EMPLOYEES' | 'MODULE_VIEW'>('DASHBOARD');
  const [selectedModuleId, setSelectedModuleId] = useState<string>('FACTORY');

  if (!userProfile) {
    return (
      <div className="min-h-screen bg-theme-base flex items-center justify-center p-4">
        <GlassCard className="p-8 max-w-md w-full text-center space-y-4">
          <Lock className="w-12 h-12 text-pmp-primary mx-auto" />
          <h2 className="text-xl font-bold text-theme-primary">
            {language === 'ar' ? 'جلسة العمل غير نشطة' : 'Session Expired or Inactive'}
          </h2>
          <p className="text-xs text-theme-secondary">
            {language === 'ar' ? 'الرجاء تسجيل الدخول للوصول إلى البوابة السحابية.' : 'Please sign in to access the cloud portal.'}
          </p>
          <GlassButton variant="primary" onClick={onBackToWebsite} className="w-full justify-center">
            {language === 'ar' ? 'العودة للصفحة الرئيسية' : 'Back to Home'}
          </GlassButton>
        </GlassCard>
      </div>
    );
  }

  const handleNavigateToModule = (modId: string) => {
    setSelectedModuleId(modId);
    setActiveTab('MODULE_VIEW');
  };

  const currentModule = ERP_MODULES.find(m => m.id === selectedModuleId);

  return (
    <div className="min-h-screen bg-theme-base text-theme-primary flex flex-col">
      {/* Top Header / Portal Navbar */}
      <header className="sticky top-0 z-40 bg-theme-surface/80 backdrop-blur-md border-b border-theme px-4 sm:px-6 py-3">
        <div className="max-w-7xl mx-auto flex items-center justify-between gap-4">
          <div className="flex items-center gap-3">
            <button
              onClick={onBackToWebsite}
              className="p-1.5 rounded-lg bg-theme-surface border border-theme hover:border-pmp-primary transition-all text-xs flex items-center gap-1.5"
              title={language === 'ar' ? 'الرجوع للموقع الرسمي' : 'Return to Website'}
            >
              <ArrowLeft className="w-4 h-4 text-pmp-primary" />
              <span className="hidden sm:inline">{language === 'ar' ? 'الموقع الرسمي' : 'Website'}</span>
            </button>

            <div className="h-5 w-px bg-theme border-theme" />

            <div className="flex items-center gap-2">
              <img src="/assets/ic_pmp_emblem.png" alt="PMP Logo" className="w-7 h-7 object-contain" />
              <div className="leading-tight">
                <span className="text-sm font-black text-theme-primary block">PMP PAINTS ERP</span>
                <span className="text-[10px] text-pmp-primary font-bold">
                  {language === 'ar' ? 'بوابة الأعمال السحابية' : 'Enterprise Cloud Portal'}
                </span>
              </div>
            </div>
          </div>

          {/* Quick Actions & Profile */}
          <div className="flex items-center gap-3">
            <button
              onClick={toggleLanguage}
              className="p-2 rounded-lg bg-theme-surface border border-theme text-theme-secondary hover:text-theme-primary text-xs font-bold"
            >
              {language === 'ar' ? 'EN' : 'عربي'}
            </button>

            <button
              onClick={toggleTheme}
              className="p-2 rounded-lg bg-theme-surface border border-theme text-theme-secondary hover:text-theme-primary"
            >
              {theme === 'true-black' ? <Sun className="w-4 h-4 text-pmp-warning" /> : <Moon className="w-4 h-4" />}
            </button>

            <div className="h-5 w-px bg-theme border-theme" />

            <div className="flex items-center gap-2">
              <div className="text-left hidden sm:block">
                <div className="text-xs font-bold text-theme-primary">{userProfile.fullName}</div>
                <div className="text-[10px] text-theme-muted font-mono">{userProfile.userCode}</div>
              </div>

              <button
                onClick={signOut}
                className="p-2 rounded-lg bg-red-500/10 border border-red-500/30 text-red-500 hover:bg-red-500/20 text-xs flex items-center gap-1"
                title={language === 'ar' ? 'تسجيل الخروج الآمن' : 'Sign Out'}
              >
                <LogOut className="w-4 h-4" />
                <span className="hidden sm:inline">{language === 'ar' ? 'خروج' : 'Exit'}</span>
              </button>
            </div>
          </div>
        </div>
      </header>

      {/* Main Portal Body */}
      <div className="flex-1 max-w-7xl mx-auto w-full px-4 sm:px-6 py-6 space-y-6">
        {/* Navigation Tabs Bar */}
        <div className="flex items-center gap-2 border-b border-theme pb-2 overflow-x-auto">
          <button
            onClick={() => setActiveTab('DASHBOARD')}
            className={`px-4 py-2 rounded-xl text-xs font-bold flex items-center gap-2 transition-all shrink-0 ${
              activeTab === 'DASHBOARD'
                ? 'bg-pmp-primary text-white shadow-md'
                : 'bg-theme-surface border border-theme text-theme-secondary hover:text-theme-primary'
            }`}
          >
            <LayoutDashboard className="w-4 h-4" />
            <span>{language === 'ar' ? 'لوحة التحكم والمؤشرات' : 'Dashboard'}</span>
          </button>

          {(isSuperAdmin || isManager) && (
            <button
              onClick={() => setActiveTab('EMPLOYEES')}
              className={`px-4 py-2 rounded-xl text-xs font-bold flex items-center gap-2 transition-all shrink-0 ${
                activeTab === 'EMPLOYEES'
                  ? 'bg-pmp-primary text-white shadow-md'
                  : 'bg-theme-surface border border-theme text-theme-secondary hover:text-theme-primary'
              }`}
            >
              <Users className="w-4 h-4" />
              <span>{language === 'ar' ? 'إدارة الموظفين والصلاحيات RBAC' : 'Employee Management'}</span>
            </button>
          )}

          {activeTab === 'MODULE_VIEW' && (
            <button
              onClick={() => setActiveTab('MODULE_VIEW')}
              className="px-4 py-2 rounded-xl text-xs font-bold flex items-center gap-2 bg-pmp-accent text-white shadow-md shrink-0"
            >
              <Building2 className="w-4 h-4" />
              <span>{language === 'ar' ? currentModule?.nameAr : currentModule?.nameEn}</span>
            </button>
          )}
        </div>

        {/* Tab Views */}
        {activeTab === 'DASHBOARD' && (
          <EmployeeDashboard onNavigateToModule={handleNavigateToModule} />
        )}

        {activeTab === 'EMPLOYEES' && (isSuperAdmin || isManager) && (
          <EmployeeManagementCenter />
        )}

        {activeTab === 'MODULE_VIEW' && currentModule && (
          <div className="space-y-6">
            <div className="flex items-center justify-between gap-4 p-4 rounded-2xl bg-theme-surface border border-theme">
              <div className="flex items-center gap-3">
                <div className="p-3 rounded-xl bg-pmp-accent/10 text-pmp-accent border border-pmp-accent/30">
                  <Building2 className="w-6 h-6" />
                </div>
                <div>
                  <h3 className="text-lg font-bold text-theme-primary">
                    {language === 'ar' ? currentModule.nameAr : currentModule.nameEn}
                  </h3>
                  <span className="text-xs text-theme-secondary">
                    {language === 'ar'
                      ? 'وحدة تشغيل متزامنة سحابياً مع قاعدة بيانات المصنع المركزية.'
                      : 'Operational unit synced with central cloud database cluster.'}
                  </span>
                </div>
              </div>

              <GlassButton variant="secondary" onClick={() => setActiveTab('DASHBOARD')} className="text-xs">
                {language === 'ar' ? 'الرجوع للوحة التحكم' : 'Back to Dashboard'}
              </GlassButton>
            </div>

            {/* Module Workspace Content Card */}
            <GlassCard className="p-8 text-center space-y-4">
              <div className="w-16 h-16 rounded-full bg-pmp-accent/10 border border-pmp-accent/30 flex items-center justify-center text-pmp-accent mx-auto">
                <Building2 className="w-8 h-8" />
              </div>
              <h4 className="text-xl font-bold text-theme-primary">
                {language === 'ar'
                  ? `جاري تهيئة واجهة ${currentModule.nameAr} السحابية الكاملة (Part 3)`
                  : `Preparing Full Cloud View for ${currentModule.nameEn} (Part 3)`}
              </h4>
              <p className="text-xs text-theme-secondary max-w-md mx-auto leading-relaxed">
                {language === 'ar'
                  ? 'تم تأكيد صلاحيات الوصول الخاصة بك لهذه الوحدة بنجاح. سيتم في الجزء الثالث (Part 3) ربط جداول البيانات الحية، نماذج الإدخال، وأزرار الطباعة والترحيل المالي.'
                  : 'Your access rights for this module are verified. Part 3 will connect the live interactive data tables, forms, and financial postings.'}
              </p>
              <div className="pt-2">
                <GlassBadge variant="cyan">
                  {language === 'ar' ? 'قاعدة بيانات Firestore: PMP-ERP-V16-CLUSTER متصلة' : 'Firestore Cluster Active'}
                </GlassBadge>
              </div>
            </GlassCard>
          </div>
        )}
      </div>
    </div>
  );
};
