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
  ArrowLeft,
  Search,
  Tag,
  BarChart3,
  FileSpreadsheet,
  Package
} from 'lucide-react';
import { GlassCard } from '../components/ui/GlassCard';
import { GlassBadge } from '../components/ui/GlassBadge';
import { GlassButton } from '../components/ui/GlassButton';
import { EmployeeDashboard } from '../components/employee/EmployeeDashboard';
import { EmployeeManagementCenter } from '../components/admin/EmployeeManagementCenter';
import { AdminDashboardOverview } from '../components/admin/AdminDashboardOverview';
import { FactoryModuleView } from '../components/modules/FactoryModuleView';
import { TreasuryModuleView } from '../components/modules/TreasuryModuleView';
import { WarehousesModuleView } from '../components/modules/WarehousesModuleView';
import { SalesModuleView } from '../components/modules/SalesModuleView';
import { ChemistryLabModuleView } from '../components/modules/ChemistryLabModuleView';
import { PricesModuleView } from '../components/modules/PricesModuleView';
import { HalfaTransitModuleView } from '../components/modules/HalfaTransitModuleView';
import { AdvancedAnalyticsView } from '../components/analytics/AdvancedAnalyticsView';
import { ReportsCenterView } from '../components/reports/ReportsCenterView';
import { GlobalSearchModal } from '../components/search/GlobalSearchModal';
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

  const [activeTab, setActiveTab] = useState<
    'OVERVIEW' | 'DASHBOARD' | 'EMPLOYEES' | 'FACTORY' | 'TREASURY' | 'WAREHOUSES' | 'SALES' | 'CHEMISTRY_LAB' | 'PRICES' | 'HALFA_TRANSIT' | 'ANALYTICS' | 'REPORTS' | 'SETTINGS'
  >(isSuperAdmin || isManager ? 'OVERVIEW' : 'DASHBOARD');

  const [sidebarCollapsed, setSidebarCollapsed] = useState(false);
  const [isSearchOpen, setIsSearchOpen] = useState(false);

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
    if (modId === 'FACTORY') setActiveTab('FACTORY');
    else if (modId === 'TREASURY') setActiveTab('TREASURY');
    else if (modId === 'WAREHOUSES' || modId === 'RAW_MATERIALS') setActiveTab('WAREHOUSES');
    else if (modId === 'SALES') setActiveTab('SALES');
    else if (modId === 'CHEMISTRY_LAB') setActiveTab('CHEMISTRY_LAB');
    else if (modId === 'PRICES') setActiveTab('PRICES');
    else if (modId === 'TRANSIT_HALFA') setActiveTab('HALFA_TRANSIT');
    else if (modId === 'USERS') setActiveTab('EMPLOYEES');
    else if (modId === 'REPORTS') setActiveTab('REPORTS');
    else setActiveTab('OVERVIEW');
  };

  const navMenuItems = [
    { id: 'OVERVIEW', labelAr: 'لوحة الإدارة الرئيسية', labelEn: 'Admin Overview', icon: <LayoutDashboard className="w-4 h-4" />, visible: isSuperAdmin || isManager },
    { id: 'DASHBOARD', labelAr: 'بوابة الموظف الشخصية', labelEn: 'My Portal', icon: <Building2 className="w-4 h-4" />, visible: true },
    { id: 'FACTORY', labelAr: 'المصنع وصالة الإنتاج', labelEn: 'Factory & Production', icon: <Factory className="w-4 h-4" />, visible: canAccessModule('المصنع') },
    { id: 'TREASURY', labelAr: 'الخزن والسيولة النقدية', labelEn: 'Treasury & Vaults', icon: <Coins className="w-4 h-4" />, visible: canAccessModule('الخزن') },
    { id: 'WAREHOUSES', labelAr: 'المخازن والمستودعات', labelEn: 'Warehouses & Stock', icon: <Warehouse className="w-4 h-4" />, visible: canAccessModule('المخازن') },
    { id: 'SALES', labelAr: 'المبيعات والفواتير', labelEn: 'Sales & Invoices', icon: <Receipt className="w-4 h-4" />, visible: canAccessModule('المبيعات') },
    { id: 'CHEMISTRY_LAB', labelAr: 'المختبر الكيميائي والجودة', labelEn: 'Chemistry Lab & QC', icon: <FlaskConical className="w-4 h-4" />, visible: canAccessModule('المختبر الكيميائي') },
    { id: 'PRICES', labelAr: 'قوائم الأسعار والخصم', labelEn: 'Prices & Tiers', icon: <Tag className="w-4 h-4" />, visible: canAccessModule('الأسعار') },
    { id: 'HALFA_TRANSIT', labelAr: 'بضاعة العبور وحلفا', labelEn: 'Halfa Transit Hub', icon: <Truck className="w-4 h-4" />, visible: canAccessModule('المخازن') },
    { id: 'ANALYTICS', labelAr: 'التحليلات والذكاء المالي', labelEn: 'BI Analytics', icon: <BarChart3 className="w-4 h-4" />, visible: isSuperAdmin || isManager },
    { id: 'REPORTS', labelAr: 'مركز التقارير والطباعة', labelEn: 'Reports Center', icon: <FileSpreadsheet className="w-4 h-4" />, visible: canAccessModule('التقارير') },
    { id: 'EMPLOYEES', labelAr: 'المستخدمين والصلاحيات', labelEn: 'Users & RBAC', icon: <Users className="w-4 h-4" />, visible: isSuperAdmin || isManager }
  ];

  return (
    <div className="min-h-screen bg-theme-base text-theme-primary flex flex-col selection:bg-pmp-primary selection:text-white">
      {/* Top Navbar */}
      <header className="sticky top-0 z-40 bg-theme-surface/85 backdrop-blur-md border-b border-theme px-4 sm:px-6 py-2.5">
        <div className="flex items-center justify-between gap-4">
          <div className="flex items-center gap-3">
            <button
              onClick={() => setSidebarCollapsed(!sidebarCollapsed)}
              className="p-1.5 rounded-lg bg-theme-surface border border-theme hover:border-pmp-primary transition-all text-xs"
              title={language === 'ar' ? 'طي / فتح القائمة الجانبية' : 'Toggle Sidebar'}
            >
              {language === 'ar' ? (
                sidebarCollapsed ? <ChevronLeft className="w-4 h-4" /> : <ChevronRight className="w-4 h-4" />
              ) : (
                sidebarCollapsed ? <ChevronRight className="w-4 h-4" /> : <ChevronLeft className="w-4 h-4" />
              )}
            </button>

            <button
              onClick={onBackToWebsite}
              className="p-1.5 rounded-lg bg-theme-surface border border-theme hover:border-pmp-primary transition-all text-xs flex items-center gap-1.5"
            >
              <ArrowLeft className="w-4 h-4 text-pmp-primary" />
              <span className="hidden md:inline">{language === 'ar' ? 'الموقع الرسمي' : 'Official Site'}</span>
            </button>

            <div className="h-5 w-px bg-theme border-theme" />

            <div className="flex items-center gap-2">
              <img src="/assets/ic_pmp_emblem.png" alt="PMP Logo" className="w-7 h-7 object-contain" />
              <div className="leading-tight">
                <span className="text-sm font-black text-theme-primary block">PMP PAINTS ERP</span>
                <span className="text-[10px] text-pmp-primary font-bold">
                  {language === 'ar' ? 'المنظومة السحابية الموحدة V16' : 'Enterprise Cloud Suite'}
                </span>
              </div>
            </div>
          </div>

          {/* Quick Search, Theme & User */}
          <div className="flex items-center gap-2.5">
            {/* Global Search Button */}
            <button
              onClick={() => setIsSearchOpen(true)}
              className="flex items-center gap-2 px-3 py-1.5 rounded-xl bg-theme-surface border border-theme text-theme-secondary hover:text-theme-primary hover:border-pmp-primary text-xs"
              title="Global Search"
            >
              <Search className="w-3.5 h-3.5 text-pmp-primary" />
              <span className="hidden sm:inline">{language === 'ar' ? 'بحث شامل...' : 'Search...'}</span>
              <kbd className="hidden lg:inline px-1.5 py-0.5 rounded bg-theme-base border border-theme text-[10px] font-mono">⌘K</kbd>
            </button>

            <button
              onClick={toggleLanguage}
              className="p-1.5 rounded-lg bg-theme-surface border border-theme text-theme-secondary hover:text-theme-primary text-xs font-bold"
            >
              {language === 'ar' ? 'EN' : 'عربي'}
            </button>

            <button
              onClick={toggleTheme}
              className="p-1.5 rounded-lg bg-theme-surface border border-theme text-theme-secondary hover:text-theme-primary"
            >
              {theme === 'true-black' ? <Sun className="w-4 h-4 text-pmp-warning" /> : <Moon className="w-4 h-4" />}
            </button>

            <div className="h-5 w-px bg-theme border-theme" />

            <div className="flex items-center gap-2">
              <div className="text-left hidden md:block">
                <div className="text-xs font-bold text-theme-primary">{userProfile.fullName}</div>
                <div className="text-[10px] text-pmp-primary font-mono font-bold">{userProfile.role}</div>
              </div>

              <button
                onClick={signOut}
                className="p-1.5 rounded-lg bg-red-500/10 border border-red-500/30 text-red-500 hover:bg-red-500/20 text-xs flex items-center gap-1"
                title={language === 'ar' ? 'تسجيل الخروج الآمن' : 'Sign Out'}
              >
                <LogOut className="w-4 h-4" />
                <span className="hidden sm:inline">{language === 'ar' ? 'خروج' : 'Exit'}</span>
              </button>
            </div>
          </div>
        </div>
      </header>

      {/* Main Workspace with Collapsible Sidebar */}
      <div className="flex-1 flex overflow-hidden">
        {/* Sidebar */}
        <aside
          className={`border-l border-theme bg-theme-surface/50 backdrop-blur-md transition-all duration-300 flex flex-col justify-between shrink-0 ${
            sidebarCollapsed ? 'w-16' : 'w-64'
          }`}
        >
          <div className="p-3 space-y-1 overflow-y-auto">
            {navMenuItems.filter(item => item.visible).map(item => {
              const isActive = activeTab === item.id;
              return (
                <button
                  key={item.id}
                  onClick={() => setActiveTab(item.id as any)}
                  className={`w-full flex items-center gap-3 p-2.5 rounded-xl text-xs font-bold transition-all text-right ${
                    isActive
                      ? 'bg-pmp-primary text-white shadow-md'
                      : 'text-theme-secondary hover:bg-theme-surface hover:text-theme-primary'
                  }`}
                  title={language === 'ar' ? item.labelAr : item.labelEn}
                >
                  <span className="shrink-0">{item.icon}</span>
                  {!sidebarCollapsed && (
                    <span className="truncate">{language === 'ar' ? item.labelAr : item.labelEn}</span>
                  )}
                </button>
              );
            })}
          </div>

          {/* Sidebar Footer info */}
          {!sidebarCollapsed && (
            <div className="p-4 border-t border-theme text-[10px] text-theme-muted space-y-1">
              <div className="flex justify-between items-center">
                <span>سيرفر السحابة:</span>
                <span className="text-pmp-success font-bold font-mono">PMP-ERP-V16</span>
              </div>
              <div className="flex justify-between items-center">
                <span>الفرع النشط:</span>
                <span className="text-theme-primary font-bold">{userProfile.branch.split(' ')[0]}</span>
              </div>
            </div>
          )}
        </aside>

        {/* Main Content Workspace */}
        <main className="flex-1 overflow-y-auto p-4 sm:p-6 lg:p-8 space-y-6">
          {activeTab === 'OVERVIEW' && (
            <AdminDashboardOverview onNavigateToModule={handleNavigateToModule} />
          )}

          {activeTab === 'DASHBOARD' && (
            <EmployeeDashboard onNavigateToModule={handleNavigateToModule} />
          )}

          {activeTab === 'EMPLOYEES' && (isSuperAdmin || isManager) && (
            <EmployeeManagementCenter />
          )}

          {activeTab === 'FACTORY' && (
            <FactoryModuleView />
          )}

          {activeTab === 'TREASURY' && (
            <TreasuryModuleView />
          )}

          {activeTab === 'WAREHOUSES' && (
            <WarehousesModuleView />
          )}

          {activeTab === 'SALES' && (
            <SalesModuleView />
          )}

          {activeTab === 'CHEMISTRY_LAB' && (
            <ChemistryLabModuleView />
          )}

          {activeTab === 'PRICES' && (
            <PricesModuleView />
          )}

          {activeTab === 'HALFA_TRANSIT' && (
            <HalfaTransitModuleView />
          )}

          {activeTab === 'ANALYTICS' && (
            <AdvancedAnalyticsView />
          )}

          {activeTab === 'REPORTS' && (
            <ReportsCenterView />
          )}
        </main>
      </div>

      {/* Global Search Modal */}
      <GlobalSearchModal
        isOpen={isSearchOpen}
        onClose={() => setIsSearchOpen(false)}
        onSelectResult={handleNavigateToModule}
      />
    </div>
  );
};
