import React, { Suspense, useState, useEffect } from 'react';
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
  AlertTriangle,
  ShieldCheck,
  History
} from 'lucide-react';
import { GlassCard } from '../components/ui/GlassCard';
import { GlassButton } from '../components/ui/GlassButton';
import { ErrorBoundary } from '../components/ui/ErrorBoundary';
import { useAuth } from '../context/AuthContext';
import { useTheme } from '../context/ThemeContext';
import { useLanguage } from '../context/LanguageContext';
import { IdempotencyProtectionSystem } from '../services/IdempotencyService';
import type { ReportTab } from '../components/reports/ReportsCenterView';
import {
  canConfigureEmployeePermissions,
  isSystemManager
} from '../services/SecurityService';
import { resolveAssetUrl } from '../config/appVersionContract';
import { AppRoute } from '../utils/routing';
import { getDesktopBridge } from '../utils/desktopBridge';
import { lazyWithRetry } from '../utils/lazyWithRetry';

const AdminDashboardOverview = lazyWithRetry(
  () => import('../components/admin/AdminDashboardOverview').then((m) => ({ default: m.AdminDashboardOverview })),
  'AdminDashboardOverview'
);
const TreasuryModuleView = lazyWithRetry(
  () => import('../components/modules/TreasuryModuleView').then((m) => ({ default: m.TreasuryModuleView })),
  'TreasuryModuleView'
);
const PurchasesModuleView = lazyWithRetry(
  () => import('../components/modules/PurchasesModuleView').then((m) => ({ default: m.PurchasesModuleView })),
  'PurchasesModuleView'
);
const SalesModuleView = lazyWithRetry(
  () => import('../components/modules/SalesModuleView').then((m) => ({ default: m.SalesModuleView })),
  'SalesModuleView'
);
const WarehousesModuleView = lazyWithRetry(
  () => import('../components/modules/WarehousesModuleView').then((m) => ({ default: m.WarehousesModuleView })),
  'WarehousesModuleView'
);
const FactoryModuleView = lazyWithRetry(
  () => import('../components/modules/FactoryModuleView').then((m) => ({ default: m.FactoryModuleView })),
  'FactoryModuleView'
);
const ChemistryLabModuleView = lazyWithRetry(
  () => import('../components/modules/ChemistryLabModuleView').then((m) => ({ default: m.ChemistryLabModuleView })),
  'ChemistryLabModuleView'
);
const EmployeeManagementCenter = lazyWithRetry(
  () => import('../components/admin/EmployeeManagementCenter').then((m) => ({ default: m.EmployeeManagementCenter })),
  'EmployeeManagementCenter'
);
const PricesModuleView = lazyWithRetry(
  () => import('../components/modules/PricesModuleView').then((m) => ({ default: m.PricesModuleView })),
  'PricesModuleView'
);
const ReportsCenterView = lazyWithRetry(
  () => import('../components/reports/ReportsCenterView').then((m) => ({ default: m.ReportsCenterView })),
  'ReportsCenterView'
);
const SettingsModuleView = lazyWithRetry(
  () => import('../components/modules/SettingsModuleView').then((m) => ({ default: m.SettingsModuleView })),
  'SettingsModuleView'
);
const GlobalSearchModal = lazyWithRetry(
  () => import('../components/search/GlobalSearchModal').then((m) => ({ default: m.GlobalSearchModal })),
  'GlobalSearchModal'
);

const ModuleLoadingSkeleton: React.FC<{ label?: string }> = ({ label }) => (
  <div className="space-y-4 animate-pulse" role="status" aria-live="polite">
    <div className="h-10 w-64 max-w-full rounded-xl bg-theme-surface border border-theme" />
    <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-4">
      {[1, 2, 3, 4].map((idx) => (
        <div key={idx} className="h-24 rounded-2xl bg-theme-surface border border-theme" />
      ))}
    </div>
    <div className="h-64 rounded-2xl bg-theme-surface border border-theme flex items-center justify-center text-xs font-bold text-theme-secondary">
      {label || 'جاري تحميل بيانات الوحدة...'}
    </div>
  </div>
);

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
  const [recentTabs, setRecentTabs] = useState<PmpScreenRoute[]>(() => [
    portalRoute === '/admin' && canAccessAdminArea ? 'USERS' : 'DASHBOARD'
  ]);
  const mainScrollRef = React.useRef<HTMLElement>(null);
  const [mobileDrawerOpen, setMobileDrawerOpen] = useState(false);
  const [sidebarCollapsed, setSidebarCollapsed] = useState(false);
  const [isSearchOpen, setIsSearchOpen] = useState(false);
  const [reportsInitialTab, setReportsInitialTab] = useState<ReportTab | undefined>(undefined);
  const [pendingOpsCount, setPendingOpsCount] = useState<number>(() =>
    IdempotencyProtectionSystem.getUnresolvedRecords().length
  );

  const navigateToTab = React.useCallback((nextTab: PmpScreenRoute) => {
    setActiveTab(nextTab);
    setRecentTabs((prev) => [nextTab, ...prev.filter((t) => t !== nextTab)].slice(0, 5));
    setMobileDrawerOpen(false);
    if (mainScrollRef.current) {
      mainScrollRef.current.scrollTo({ top: 0, behavior: 'smooth' });
    }
  }, []);

  useEffect(() => {
    const syncCount = () => {
      setPendingOpsCount(IdempotencyProtectionSystem.getUnresolvedRecords().length);
    };
    const unsub = IdempotencyProtectionSystem.subscribe(syncCount);
    void IdempotencyProtectionSystem.recoverPendingOperations().then(syncCount);
    const onOnline = () => {
      void IdempotencyProtectionSystem.recoverPendingOperations().then(syncCount);
    };
    if (typeof window !== 'undefined') {
      window.addEventListener('online', onOnline);
    }
    return () => {
      unsub();
      if (typeof window !== 'undefined') {
        window.removeEventListener('online', onOnline);
      }
    };
  }, [userProfile?.userCode]);

  useEffect(() => {
    if (portalRoute === '/admin') {
      navigateToTab(canAccessAdminArea ? 'USERS' : 'DASHBOARD');
    } else if (portalRoute === '/employee' || portalRoute === '/dashboard') {
      setActiveTab((prev) => (prev === 'USERS' ? 'DASHBOARD' : prev));
    }
  }, [portalRoute, canAccessAdminArea, navigateToTab]);

  useEffect(() => {
    const bridge = getDesktopBridge();
    if (!bridge) return;
    return bridge.onShortcutAction((action) => {
      if (action === 'GLOBAL_SEARCH') {
        setIsSearchOpen(true);
      } else if (action === 'PRINT_REPORT' && typeof window !== 'undefined') {
        window.print();
      }
    });
  }, []);

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
      labelEn: 'Users & Permissions',
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
      visible: canAccessModule('التقارير') || canAccessModule('المحاسبة')
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

  // Universal enterprise keyboard shortcuts (Ctrl/Cmd+K search, Ctrl+1..9 module jump, Ctrl+Shift+T/L, Escape drawer)
  useEffect(() => {
    const visibleItems = navMenuItems.filter((m) => m.visible);
    const onKeyDown = (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        setMobileDrawerOpen(false);
        return;
      }
      if (!(e.ctrlKey || e.metaKey)) return;
      const key = e.key.toLowerCase();
      if (key === 'k') {
        e.preventDefault();
        setIsSearchOpen(true);
      } else if (e.shiftKey && key === 't') {
        e.preventDefault();
        toggleTheme();
      } else if (e.shiftKey && key === 'l') {
        e.preventDefault();
        toggleLanguage();
      } else if (/^[1-9]$/.test(key) && !e.shiftKey && !e.altKey) {
        const idx = Number(key) - 1;
        const target = visibleItems[idx];
        if (target) {
          e.preventDefault();
          navigateToTab(target.id);
        }
      }
    };
    window.addEventListener('keydown', onKeyDown);
    return () => window.removeEventListener('keydown', onKeyDown);
  }, [userProfile?.permissions, userProfile?.role, toggleTheme, toggleLanguage, navigateToTab]);

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
      navigateToTab(desired);
    } else {
      navigateToTab('DASHBOARD');
    }
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
            <span className="text-emerald-400 font-bold">{language === 'ar' ? 'متصل ومحدث لحظياً' : 'Live Sync'}</span>
          </div>

          {/* Right Toolbar */}
          <div className="flex items-center gap-1.5 sm:gap-2 shrink-0">
            {pendingOpsCount > 0 && (
              <button
                type="button"
                onClick={() => {
                  setReportsInitialTab('متابعة العمليات');
                  navigateToTab('REPORTS');
                }}
                className="flex items-center gap-1.5 px-2.5 py-1.5 rounded-xl bg-amber-500/15 border border-amber-500/40 text-amber-300 hover:bg-amber-500/25 text-xs min-h-[38px] font-bold"
                title="توجد عمليات بانتظار تأكيد الخادم - لا تعِد إدخالها"
              >
                <AlertTriangle className="w-3.5 h-3.5 shrink-0" />
                <span>{pendingOpsCount} معلقة</span>
              </button>
            )}
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
                    onClick={() => navigateToTab(item.id)}
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
                <span>الحالة:</span>
                <span className="text-emerald-400 font-bold truncate max-w-[125px]">
                  {language === 'ar' ? 'متصل ومحدث لحظياً' : 'Connected'}
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
                          onClick={() => navigateToTab(item.id)}
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
                <div className="text-[10px] text-emerald-400 font-bold text-center pb-1">
                  {language === 'ar' ? 'متصل ومحدث لحظياً' : 'Live Connected'}
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
        <main ref={mainScrollRef} className="flex-1 overflow-y-auto p-3 sm:p-6 lg:p-8 space-y-4 sm:space-y-6">
          {/* Active Module Breadcrumb + Quick-Switch Recent Tabs + Security Shield Bar */}
          <div className="flex flex-wrap items-center justify-between gap-2.5 px-3.5 py-2.5 rounded-2xl bg-theme-surface/85 border border-theme backdrop-blur-md">
            <div className="flex items-center gap-2.5 min-w-0">
              {(() => {
                const currentMeta = navMenuItems.find((m) => m.id === activeTab);
                return (
                  <>
                    <span className="w-8 h-8 rounded-xl bg-pmp-primary/15 border border-pmp-primary/30 text-pmp-primary flex items-center justify-center shrink-0">
                      {currentMeta?.icon}
                    </span>
                    <div className="min-w-0">
                      <span className="text-xs sm:text-sm font-black text-theme-primary block truncate">
                        {language === 'ar' ? currentMeta?.labelAr : currentMeta?.labelEn}
                      </span>
                      <span className="text-[10px] text-theme-muted hidden sm:block">
                        {language === 'ar'
                          ? 'Ctrl+K للبحث الشامل • Ctrl+1..9 للتنقل السريع'
                          : 'Ctrl+K Global Search • Ctrl+1..9 Quick Jump'}
                      </span>
                    </div>
                  </>
                );
              })()}
            </div>

            <div className="flex items-center gap-1.5 overflow-x-auto no-scrollbar max-w-full">
              <span className="hidden lg:inline-flex items-center gap-1 px-2.5 py-1 rounded-xl bg-emerald-500/10 border border-emerald-500/25 text-emerald-400 text-[10px] font-bold shrink-0">
                <ShieldCheck className="w-3.5 h-3.5" />
                <span>{language === 'ar' ? 'حماية المعاملات المزدوجة نشطة' : 'Anti-Duplicate Guard Active'}</span>
              </span>

              {recentTabs.length > 1 && (
                <div className="flex items-center gap-1 pr-1 border-r border-theme">
                  <History className="w-3.5 h-3.5 text-theme-muted hidden sm:block shrink-0" />
                  {recentTabs
                    .map((tid) => navMenuItems.find((m) => m.id === tid && m.visible))
                    .filter((m): m is NonNullable<typeof m> => Boolean(m))
                    .map((tabMeta) => {
                      const isCur = tabMeta.id === activeTab;
                      return (
                        <button
                          key={`recent_${tabMeta.id}`}
                          type="button"
                          onClick={() => navigateToTab(tabMeta.id)}
                          className={`px-2.5 py-1 rounded-lg text-[11px] font-bold transition-all whitespace-nowrap flex items-center gap-1 min-h-[30px] ${
                            isCur
                              ? 'bg-pmp-primary text-white shadow-sm'
                              : 'bg-theme-base border border-theme text-theme-secondary hover:text-theme-primary hover:border-pmp-primary/40'
                          }`}
                        >
                          {tabMeta.icon}
                          <span>{language === 'ar' ? tabMeta.labelAr : tabMeta.labelEn}</span>
                        </button>
                      );
                    })}
                </div>
              )}
            </div>
          </div>

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

          <ErrorBoundary>
            <Suspense
              fallback={
                <ModuleLoadingSkeleton
                  label={language === 'ar' ? 'جاري تحميل بيانات الوحدة...' : 'Loading module...'}
                />
              }
            >
              <div key={activeTab} className="pmp-tab-transition">
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
                {activeTab === 'REPORTS' && <ReportsCenterView initialTab={reportsInitialTab} />}
                {activeTab === 'SETTINGS' && <SettingsModuleView />}
              </div>
            </Suspense>
          </ErrorBoundary>
        </main>
      </div>

      {/* Global Search Modal */}
      {isSearchOpen && (
        <Suspense fallback={null}>
          <GlobalSearchModal
            isOpen={isSearchOpen}
            onClose={() => setIsSearchOpen(false)}
            onSelectResult={handleNavigateToModule}
          />
        </Suspense>
      )}
    </div>
  );
};
