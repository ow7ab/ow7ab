import React, { useState, useEffect } from 'react';
import {
  LayoutDashboard,
  Coins,
  ShoppingCart,
  Receipt,
  Warehouse,
  Factory,
  FlaskConical,
  Users,
  Tag,
  BarChart3,
  Settings,
  LogOut,
  ChevronRight,
  ChevronLeft,
  Moon,
  Sun,
  Globe,
  Lock,
  Search,
  Menu,
  X,
  Download,
  AlertTriangle
} from 'lucide-react';
import { GlassCard } from '../components/ui/GlassCard';
import { GlassButton } from '../components/ui/GlassButton';
import { AdminDashboardOverview } from '../components/admin/AdminDashboardOverview';
import { TreasuryModuleView } from '../components/modules/TreasuryModuleView';
import { PurchasesModuleView } from '../components/modules/PurchasesModuleView';
import { SalesModuleView } from '../components/modules/SalesModuleView';
import { WarehousesModuleView } from '../components/modules/WarehousesModuleView';
import { FactoryModuleView } from '../components/modules/FactoryModuleView';
import { ChemistryLabModuleView } from '../components/modules/ChemistryLabModuleView';
import { EmployeeManagementCenter } from '../components/admin/EmployeeManagementCenter';
import { PricesModuleView } from '../components/modules/PricesModuleView';
import { ReportsCenterView } from '../components/reports/ReportsCenterView';
import { SettingsModuleView } from '../components/modules/SettingsModuleView';
import { GlobalSearchModal } from '../components/search/GlobalSearchModal';
import { useAuth } from '../context/AuthContext';
import { useTheme } from '../context/ThemeContext';
import { useLanguage } from '../context/LanguageContext';
import { ErpDataService } from '../services/ErpDataService';
import {
  canConfigureEmployeePermissions,
  isSystemManager
} from '../services/SecurityService';
import { resolveAssetUrl } from '../config/appVersionContract';
import { AppRoute } from '../utils/routing';

export type PmpScreenRoute =
  | 'DASHBOARD'
  | 'TREASURY'
  | 'PURCHASES'
  | 'SALES'
  | 'INVENTORY'
  | 'FACTORY'
  | 'CHEMISTRY_LAB'
  | 'USERS'
  | 'POSTER'
  | 'REPORTS'
  | 'SETTINGS';

interface PortalPageProps {
  portalRoute?: '/dashboard' | '/admin' | '/employee';
  onNavigateRoute?: (route: AppRoute) => void;
  onBackToWebsite: () => void;
  onLogoutToLogin?: () => void;
}

export const PortalPage: React.FC<PortalPageProps> = ({
  portalRoute = '/dashboard',
  onNavigateRoute,
  onBackToWebsite,
  onLogoutToLogin
}) => {
  const { language, toggleLanguage } = useLanguage();
  const { theme, toggleTheme } = useTheme();
  const { userProfile, isSuperAdmin, canAccessModule, signOut } = useAuth();

  const hasSystemManagerAccess = isSystemManager(userProfile);
  const canAccessAdminArea =
    Boolean(userProfile) &&
    (isSuperAdmin ||
      hasSystemManagerAccess ||
      canConfigureEmployeePermissions(userProfile) ||
      canAccessModule('المستخدمين'));

  const [activeTab, setActiveTab] = useState<PmpScreenRoute>(() =>
    portalRoute === '/admin' && canAccessAdminArea ? 'USERS' : 'DASHBOARD'
  );
  const [mobileDrawerOpen, setMobileDrawerOpen] = useState(false);
  const [sidebarCollapsed, setSidebarCollapsed] = useState(false);
  const [isSearchOpen, setIsSearchOpen] = useState(false);

  useEffect(() => {
    if (portalRoute === '/admin') {
      setActiveTab(canAccessAdminArea ? 'USERS' : 'DASHBOARD');
    } else if (portalRoute === '/employee' || portalRoute === '/dashboard') {
      setActiveTab((prev) => (prev === 'USERS' ? 'DASHBOARD' : prev));
    }
  }, [portalRoute, canAccessAdminArea]);

  const handleSignOut = async () => {
    await signOut();
    if (onLogoutToLogin) {
      onLogoutToLogin();
    } else {
      onBackToWebsite();
    }
  };

  /**
   * EXACT 11 Android Navigation Tabs matching `enum class PmpScreen` and `resolveAllowedScreens()` in `MainActivity.kt`
   */
  const navMenuItems: { id: PmpScreenRoute; labelAr: string; labelEn: string; icon: React.ReactNode; visible: boolean }[] = [
    { id: 'DASHBOARD', labelAr: 'الرئيسية', labelEn: 'Dashboard', icon: <LayoutDashboard className="w-4 h-4" />, visible: true },
    {
      id: 'TREASURY',
      labelAr: 'الخزن',
      labelEn: 'Treasury',
      icon: <Coins className="w-4 h-4" />,
      visible: canAccessModule('الخزن') || canAccessModule('التحويلات') || canAccessModule('المحاسبة')
    },
    {
      id: 'PURCHASES',
      labelAr: 'المشتريات',
      labelEn: 'Purchases',
      icon: <ShoppingCart className="w-4 h-4" />,
      visible:
        canAccessModule('المشتريات') ||
        canAccessModule('الموردين') ||
        canAccessModule('مواد الإنتاج') ||
        canAccessModule('المصنع') ||
        hasSystemManagerAccess
    },
    {
      id: 'SALES',
      labelAr: 'المبيعات',
      labelEn: 'Sales',
      icon: <Receipt className="w-4 h-4" />,
      visible: canAccessModule('المبيعات') || canAccessModule('العملاء') || canAccessModule('المحاسبة')
    },
    {
      id: 'INVENTORY',
      labelAr: 'المخازن',
      labelEn: 'Inventory',
      icon: <Warehouse className="w-4 h-4" />,
      visible: canAccessModule('المخازن')
    },
    {
      id: 'FACTORY',
      labelAr: 'المصنع',
      labelEn: 'Factory',
      icon: <Factory className="w-4 h-4" />,
      visible:
        canAccessModule('المصنع') ||
        canAccessModule('الإنتاج') ||
        canAccessModule('مواد الإنتاج') ||
        canAccessModule('التعبئة والتغليف')
    },
    {
      id: 'CHEMISTRY_LAB',
      labelAr: 'المختبر الكيميائي',
      labelEn: 'Chemistry Lab',
      icon: <FlaskConical className="w-4 h-4" />,
      visible: canAccessModule('المختبر الكيميائي') || canAccessModule('المصنع') || hasSystemManagerAccess
    },
    {
      id: 'USERS',
      labelAr: 'المستخدمين',
      labelEn: 'Users & RBAC',
      icon: <Users className="w-4 h-4" />,
      visible:
        canAccessModule('المستخدمين') ||
        canConfigureEmployeePermissions(userProfile) ||
        hasSystemManagerAccess ||
        isSuperAdmin
    },
    {
      id: 'POSTER',
      labelAr: 'الأسعار',
      labelEn: 'Poster Prices',
      icon: <Tag className="w-4 h-4" />,
      visible: canAccessModule('الأسعار')
    },
    {
      id: 'REPORTS',
      labelAr: 'التقارير',
      labelEn: 'Reports',
      icon: <BarChart3 className="w-4 h-4" />,
      visible: canAccessModule('التقارير')
    },
    {
      id: 'SETTINGS',
      labelAr: 'الإعدادات',
      labelEn: 'Settings',
      icon: <Settings className="w-4 h-4" />,
      visible: true
    }
  ];

  // Live security guard: if activeTab becomes unauthorized on real-time permission update, fall back to DASHBOARD
  useEffect(() => {
    const activeItem = navMenuItems.find((m) => m.id === activeTab);
    if (activeItem && !activeItem.visible) {
      setActiveTab('DASHBOARD');
    }
  }, [userProfile?.permissions, userProfile?.role, activeTab]);

  if (!userProfile) {
    return (
      <div className="min-h-screen bg-theme-base flex items-center justify-center p-4">
        <GlassCard className="p-8 max-w-md w-full text-center space-y-4">
          <Lock className="w-12 h-12 text-pmp-primary mx-auto" />
          <h2 className="text-xl font-bold text-theme-primary">
            {language === 'ar' ? 'جلسة العمل غير نشطة' : 'Session Inactive'}
          </h2>
          <p className="text-xs text-theme-secondary">
            {language === 'ar'
              ? 'الرجاء تسجيل الدخول للوصول إلى المنظومة السحابية.'
              : 'Please sign in to access the cloud portal.'}
          </p>
          <GlassButton variant="primary" onClick={handleSignOut} className="w-full justify-center">
            {language === 'ar' ? 'الذهاب لشاشة تسجيل الدخول' : 'Go to Login'}
          </GlassButton>
        </GlassCard>
      </div>
    );
  }

  const handleNavigateToModule = (modId: string) => {
    const targetMap: Record<string, PmpScreenRoute> = {
      FACTORY: 'FACTORY',
      TREASURY: 'TREASURY',
      PURCHASES: 'PURCHASES',
      INVENTORY: 'INVENTORY',
      WAREHOUSES: 'INVENTORY',
      RAW_MATERIALS: 'FACTORY',
      SALES: 'SALES',
      CHEMISTRY_LAB: 'CHEMISTRY_LAB',
      USERS: 'USERS',
      POSTER: 'POSTER',
      PRICES: 'POSTER',
      REPORTS: 'REPORTS',
      SETTINGS: 'SETTINGS'
    };
    const desired = targetMap[modId] || 'DASHBOARD';
    const item = navMenuItems.find((m) => m.id === desired);
    if (item && item.visible) {
      setActiveTab(desired);
    } else {
      setActiveTab('DASHBOARD');
    }
    setMobileDrawerOpen(false);
  };

  return (
    <div className="min-h-screen bg-theme-base text-theme-primary flex flex-col selection:bg-pmp-primary selection:text-white">
      {/* Top Main Navbar — Clean, Responsive, Zero Overlap */}
      <header className="sticky top-0 z-40 bg-theme-surface/95 backdrop-blur-md border-b border-theme px-3 sm:px-6 py-2.5">
        <div className="max-w-7xl mx-auto flex items-center justify-between gap-2 sm:gap-4">
          {/* Brand + Drawer Toggle */}
          <div className="flex items-center gap-2 sm:gap-3 min-w-0">
            <button
              type="button"
              onClick={() => setMobileDrawerOpen(true)}
              className="p-2 rounded-xl bg-theme-base border border-theme text-theme-primary md:hidden min-h-[40px] min-w-[40px] flex items-center justify-center shrink-0"
              aria-label={language === 'ar' ? 'فتح القائمة' : 'Open Menu'}
            >
              <Menu className="w-5 h-5" />
            </button>

            <button
              type="button"
              onClick={() => setSidebarCollapsed(!sidebarCollapsed)}
              className="hidden md:flex p-2 rounded-lg bg-theme-base border border-theme hover:border-pmp-primary transition-all text-xs shrink-0"
              title={language === 'ar' ? 'طي / فتح القائمة الجانبية' : 'Toggle Sidebar'}
            >
              {language === 'ar' ? (
                sidebarCollapsed ? <ChevronLeft className="w-4 h-4" /> : <ChevronRight className="w-4 h-4" />
              ) : sidebarCollapsed ? (
                <ChevronRight className="w-4 h-4" />
              ) : (
                <ChevronLeft className="w-4 h-4" />
              )}
            </button>

            <div className="flex items-center gap-2 min-w-0">
              <div className="w-8 h-8 rounded-lg bg-white border border-red-500/40 p-0.5 flex items-center justify-center shrink-0">
                <img src={resolveAssetUrl('assets/ic_pmp_emblem.png')} alt="PMP Logo" className="w-full h-full object-contain" />
              </div>
              <div className="leading-tight min-w-0">
                <span className="text-xs sm:text-sm font-black text-theme-primary block truncate">
                  PMP PAINTS ERP
                </span>
                <span className="text-[10px] text-pmp-primary font-bold block truncate">
                  {language === 'ar'
                    ? `${userProfile.role} • ${userProfile.branch}`
                    : `${userProfile.role} • ${userProfile.branch}`}
                </span>
              </div>
            </div>
          </div>

          {/* Center Status Pill (Desktop only, read-only authentic user context) */}
          <div className="hidden lg:flex items-center gap-2.5 bg-theme-base border border-theme px-3 py-1.5 rounded-xl text-[11px]">
            <span className="w-2 h-2 rounded-full bg-emerald-500 shrink-0" />
            <span className="font-bold text-theme-primary">{userProfile.fullName}</span>
            <span className="text-theme-muted">|</span>
            <span className="font-mono text-pmp-cyan font-bold">{ErpDataService.activeCluster}</span>
          </div>

          {/* Right Toolbar */}
          <div className="flex items-center gap-1.5 sm:gap-2 shrink-0">
            <button
              type="button"
              onClick={() => setIsSearchOpen(true)}
              className="p-2 sm:px-2.5 sm:py-1.5 rounded-xl bg-theme-base border border-theme text-theme-secondary hover:text-theme-primary text-xs flex items-center gap-1.5 min-h-[38px]"
              title={language === 'ar' ? 'البحث الشامل' : 'Global Search'}
            >
              <Search className="w-4 h-4 text-pmp-primary" />
              <span className="hidden md:inline font-bold">{language === 'ar' ? 'بحث' : 'Search'}</span>
            </button>

            {onNavigateRoute && (
              <button
                type="button"
                onClick={() => onNavigateRoute('/download')}
                className="hidden sm:flex items-center gap-1 px-2.5 py-1.5 rounded-xl bg-emerald-600/15 border border-emerald-500/30 text-emerald-400 hover:bg-emerald-600/25 text-xs min-h-[38px]"
                title={language === 'ar' ? 'تحميل تطبيق Android' : 'Download Android APK'}
              >
                <Download className="w-3.5 h-3.5" />
                <span className="hidden lg:inline font-bold">{language === 'ar' ? 'تطبيق Android' : 'Android App'}</span>
              </button>
            )}

            <button
              type="button"
              onClick={onBackToWebsite}
              className="hidden sm:flex items-center gap-1 px-2.5 py-1.5 rounded-xl bg-theme-base border border-theme text-theme-secondary hover:text-theme-primary text-xs min-h-[38px]"
              title={language === 'ar' ? 'الموقع العام وتحميل APK' : 'Public Site & APK'}
            >
              <Globe className="w-3.5 h-3.5 text-pmp-cyan" />
              <span className="hidden lg:inline font-bold">{language === 'ar' ? 'الموقع العام' : 'Public Site'}</span>
            </button>

            <button
              type="button"
              onClick={toggleLanguage}
              className="px-2.5 py-1.5 rounded-xl bg-theme-base border border-theme text-theme-secondary hover:text-theme-primary text-xs font-bold min-h-[38px]"
            >
              {language === 'ar' ? 'EN' : 'عربي'}
            </button>

            <button
              type="button"
              onClick={toggleTheme}
              className="p-2 rounded-xl bg-theme-base border border-theme text-theme-secondary hover:text-theme-primary min-h-[38px] min-w-[38px] flex items-center justify-center"
              title={language === 'ar' ? 'تبديل المظهر' : 'Toggle Theme'}
            >
              {theme === 'true-black' ? <Sun className="w-4 h-4 text-pmp-warning" /> : <Moon className="w-4 h-4" />}
            </button>

            <button
              type="button"
              onClick={handleSignOut}
              className="p-2 sm:px-3 sm:py-1.5 rounded-xl bg-red-500/10 border border-red-500/30 text-red-500 hover:bg-red-500/20 text-xs flex items-center gap-1.5 min-h-[38px]"
              title={language === 'ar' ? 'تسجيل الخروج' : 'Sign Out'}
            >
              <LogOut className="w-4 h-4" />
              <span className="hidden sm:inline font-bold">{language === 'ar' ? 'خروج' : 'Exit'}</span>
            </button>
          </div>
        </div>
      </header>

      {/* Main Workspace */}
      <div className="flex-1 flex overflow-hidden">
        {/* Desktop Collapsible Sidebar (EXACT 11 ANDROID TABS) */}
        <aside
          className={`hidden md:flex border-l border-theme bg-theme-surface/70 backdrop-blur-md transition-all duration-300 flex-col justify-between shrink-0 ${
            sidebarCollapsed ? 'w-16' : 'w-60'
          }`}
        >
          <div className="p-2 space-y-1 overflow-y-auto">
            {navMenuItems
              .filter((item) => item.visible)
              .map((item) => {
                const isActive = activeTab === item.id;
                return (
                  <button
                    key={item.id}
                    type="button"
                    onClick={() => setActiveTab(item.id)}
                    className={`w-full flex items-center gap-3 p-2.5 rounded-xl text-xs font-bold transition-all text-right min-h-[42px] ${
                      isActive
                        ? 'bg-pmp-primary text-white shadow-md'
                        : 'text-theme-secondary hover:bg-theme-base hover:text-theme-primary'
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

          {!sidebarCollapsed && (
            <div className="p-3 border-t border-theme text-[10px] text-theme-muted space-y-1">
              <div className="flex justify-between items-center gap-1">
                <span>المستخدم:</span>
                <span className="text-theme-primary font-bold truncate max-w-[115px]">{userProfile.fullName}</span>
              </div>
              <div className="flex justify-between items-center gap-1">
                <span>السيرفر:</span>
                <span className="text-emerald-400 font-bold font-mono truncate max-w-[125px]">
                  {ErpDataService.activeCluster}
                </span>
              </div>
              <div className="flex justify-between items-center gap-1">
                <span>الفرع:</span>
                <span className="text-theme-primary font-bold truncate max-w-[115px]">{userProfile.branch}</span>
              </div>
            </div>
          )}
        </aside>

        {/* Mobile Slide-Over Drawer */}
        {mobileDrawerOpen && (
          <div className="fixed inset-0 z-50 md:hidden flex">
            <div
              className="fixed inset-0 bg-black/75 backdrop-blur-sm transition-opacity"
              onClick={() => setMobileDrawerOpen(false)}
            />

            <div className="relative w-72 max-w-[85vw] bg-theme-surface border-l border-theme flex flex-col justify-between h-full p-4 z-10 shadow-2xl overflow-y-auto">
              <div className="space-y-4">
                <div className="flex items-center justify-between pb-3 border-b border-theme">
                  <div className="flex items-center gap-2 min-w-0">
                    <img src={resolveAssetUrl('assets/ic_pmp_emblem.png')} alt="PMP" className="w-6 h-6 object-contain shrink-0" />
                    <div className="min-w-0">
                      <span className="font-black text-xs text-theme-primary block truncate">
                        {userProfile.fullName}
                      </span>
                      <span className="text-[10px] text-pmp-primary font-bold block truncate">
                        {userProfile.role} • {userProfile.branch}
                      </span>
                    </div>
                  </div>
                  <button
                    type="button"
                    onClick={() => setMobileDrawerOpen(false)}
                    className="p-1.5 rounded-lg bg-theme-base border border-theme text-theme-secondary shrink-0"
                  >
                    <X className="w-4 h-4" />
                  </button>
                </div>

                {/* Nav Items */}
                <div className="space-y-1">
                  {navMenuItems
                    .filter((item) => item.visible)
                    .map((item) => {
                      const isActive = activeTab === item.id;
                      return (
                        <button
                          key={item.id}
                          type="button"
                          onClick={() => {
                            setActiveTab(item.id);
                            setMobileDrawerOpen(false);
                          }}
                          className={`w-full flex items-center gap-3 p-2.5 rounded-xl text-xs font-bold transition-all text-right min-h-[44px] ${
                            isActive
                              ? 'bg-pmp-primary text-white shadow-md'
                              : 'text-theme-secondary hover:bg-theme-base hover:text-theme-primary'
                          }`}
                        >
                          <span className="shrink-0">{item.icon}</span>
                          <span className="truncate">{language === 'ar' ? item.labelAr : item.labelEn}</span>
                        </button>
                      );
                    })}
                </div>
              </div>

              {/* Drawer Footer Actions */}
              <div className="pt-4 border-t border-theme space-y-2">
                <div className="text-[10px] text-theme-muted font-mono text-center pb-1">
                  {ErpDataService.activeCluster}
                </div>
                {onNavigateRoute && (
                  <button
                    type="button"
                    onClick={() => {
                      onNavigateRoute('/download');
                      setMobileDrawerOpen(false);
                    }}
                    className="w-full py-2.5 px-3 rounded-xl bg-emerald-600/15 border border-emerald-500/30 text-xs font-bold text-emerald-400 flex items-center justify-center gap-2"
                  >
                    <Download className="w-4 h-4" />
                    <span>{language === 'ar' ? 'تحميل تطبيق Android' : 'Download Android APK'}</span>
                  </button>
                )}
                <button
                  type="button"
                  onClick={() => {
                    onBackToWebsite();
                    setMobileDrawerOpen(false);
                  }}
                  className="w-full py-2.5 px-3 rounded-xl bg-theme-base border border-theme text-xs font-bold text-theme-primary flex items-center justify-center gap-2"
                >
                  <Globe className="w-4 h-4 text-pmp-cyan" />
                  <span>{language === 'ar' ? 'الموقع العام والتحميل' : 'Public Site'}</span>
                </button>

                <button
                  type="button"
                  onClick={handleSignOut}
                  className="w-full py-2.5 px-3 rounded-xl bg-red-500/10 border border-red-500/30 text-xs font-bold text-red-500 flex items-center justify-center gap-2"
                >
                  <LogOut className="w-4 h-4" />
                  <span>{language === 'ar' ? 'تسجيل الخروج' : 'Sign Out'}</span>
                </button>
              </div>
            </div>
          </div>
        )}

        {/* Main Content Workspace — EXACT 11 MODULE VIEWS */}
        <main className="flex-1 overflow-y-auto p-3 sm:p-6 lg:p-8 space-y-4 sm:space-y-6">
          {portalRoute === '/admin' && !canAccessAdminArea && (
            <div className="p-3.5 rounded-xl bg-amber-500/10 border border-amber-500/30 text-xs text-amber-400 flex items-center gap-2 font-bold">
              <AlertTriangle className="w-4 h-4 shrink-0" />
              <span>
                {language === 'ar'
                  ? 'مسار الإدارة (/admin) مخصص للمسؤولين والمدراء فقط. تم تقييد العرض بالوحدات المصرح بها لحسابك.'
                  : 'Admin route (/admin) is restricted to authorized administrators. Showing only permitted modules for your account.'}
              </span>
            </div>
          )}

          {activeTab === 'DASHBOARD' && (
            <AdminDashboardOverview onNavigateToModule={handleNavigateToModule} />
          )}
          {activeTab === 'TREASURY' && <TreasuryModuleView />}
          {activeTab === 'PURCHASES' && <PurchasesModuleView />}
          {activeTab === 'SALES' && <SalesModuleView />}
          {activeTab === 'INVENTORY' && <WarehousesModuleView />}
          {activeTab === 'FACTORY' && <FactoryModuleView />}
          {activeTab === 'CHEMISTRY_LAB' && <ChemistryLabModuleView />}
          {activeTab === 'USERS' && canAccessAdminArea && <EmployeeManagementCenter />}
          {activeTab === 'POSTER' && <PricesModuleView />}
          {activeTab === 'REPORTS' && <ReportsCenterView />}
          {activeTab === 'SETTINGS' && <SettingsModuleView />}
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
