import React, { Suspense, useState, useEffect, useCallback } from 'react';
import { ThemeProvider } from './context/ThemeContext';
import { LanguageProvider } from './context/LanguageContext';
import { AuthProvider, useAuth } from './context/AuthContext';
import {
  AppRoute,
  buildBrowserPath,
  normalizeAppRoute,
  resolveInitialRouteFromLocation
} from './utils/routing';
import { DesktopWindowChrome } from './components/layout/DesktopWindowChrome';
import { ErrorBoundary } from './components/ui/ErrorBoundary';
import { isDesktopApp } from './utils/desktopBridge';
import { safeHistoryPush } from './utils/safeStorage';
import { lazyWithRetry } from './utils/lazyWithRetry';

const HomePage = lazyWithRetry(
  () => import('./pages/HomePage').then((m) => ({ default: m.HomePage })),
  'HomePage'
);
const PortalPage = lazyWithRetry(
  () => import('./pages/PortalPage').then((m) => ({ default: m.PortalPage })),
  'PortalPage'
);
const LoginPage = lazyWithRetry(
  () => import('./pages/LoginPage').then((m) => ({ default: m.LoginPage })),
  'LoginPage'
);
const DownloadPage = lazyWithRetry(
  () => import('./pages/DownloadPage').then((m) => ({ default: m.DownloadPage })),
  'DownloadPage'
);

const RouteLoadingFallback: React.FC = () => (
  <div
    dir="rtl"
    className="min-h-screen bg-theme-base text-theme-primary flex items-center justify-center p-6"
    role="status"
    aria-live="polite"
  >
    <div className="flex flex-col items-center gap-3 text-center">
      <div className="w-11 h-11 rounded-2xl border-2 border-red-600/30 border-t-red-500 animate-spin" />
      <div className="text-sm font-black tracking-wide">PMP PAINTS ERP</div>
      <div className="text-xs text-theme-secondary font-semibold">جاري تجهيز الواجهة...</div>
    </div>
  </div>
);

const AppContent: React.FC = () => {
  const { userProfile } = useAuth();
  const [currentRoute, setCurrentRoute] = useState<AppRoute>(() => resolveInitialRouteFromLocation());

  const navigateRoute = useCallback((targetRoute: AppRoute) => {
    setCurrentRoute(targetRoute);
    if (typeof window !== 'undefined') {
      const nextUrl = buildBrowserPath(targetRoute);
      if (window.location.pathname !== nextUrl) {
        safeHistoryPush({ route: targetRoute }, '', nextUrl);
      }
      try {
        window.scrollTo({ top: 0, behavior: 'smooth' });
      } catch {
        // Ignore unsupported scrollTo options
      }
    }
  }, []);

  useEffect(() => {
    const handlePopState = () => {
      setCurrentRoute(normalizeAppRoute(window.location.pathname));
    };
    window.addEventListener('popstate', handlePopState);
    return () => window.removeEventListener('popstate', handlePopState);
  }, []);

  // Desktop opens directly into ERP Portal (/dashboard if authenticated, /login otherwise)
  useEffect(() => {
    if (!isDesktopApp()) return;
    if (currentRoute === '/') {
      navigateRoute(userProfile ? '/dashboard' : '/login');
    } else if (userProfile && currentRoute === '/login') {
      navigateRoute('/dashboard');
    }
  }, [userProfile, currentRoute, navigateRoute]);

  // Redirect logged-in user away from /login straight into /dashboard
  useEffect(() => {
    if (userProfile && currentRoute === '/login') {
      navigateRoute('/dashboard');
    }
  }, [userProfile, currentRoute, navigateRoute]);

  const handleEnterPortal = useCallback(() => {
    if (userProfile) {
      navigateRoute('/dashboard');
    } else {
      navigateRoute('/login');
    }
  }, [userProfile, navigateRoute]);

  const renderActiveRoute = () => {
    // 1. Dedicated Official APK Download Route (/download) — public, zero private ERP data
    if (currentRoute === '/download') {
      return (
        <DownloadPage
          onNavigateRoute={navigateRoute}
          onEnterPortal={handleEnterPortal}
        />
      );
    }

    // 2. Protected Routes (/dashboard, /admin, /employee)
    if (currentRoute === '/dashboard' || currentRoute === '/admin' || currentRoute === '/employee') {
      if (!userProfile) {
        return (
          <LoginPage
            onLoginSuccess={() => navigateRoute(currentRoute)}
            onGoToPublicWebsite={() => navigateRoute('/')}
            onNavigateRoute={navigateRoute}
          />
        );
      }

      return (
        <PortalPage
          portalRoute={currentRoute}
          onNavigateRoute={navigateRoute}
          onBackToWebsite={() => navigateRoute('/')}
          onLogoutToLogin={() => navigateRoute('/login')}
        />
      );
    }

    // 3. Employee & Admin Login Route (/login)
    if (currentRoute === '/login') {
      return (
        <LoginPage
          onLoginSuccess={() => navigateRoute('/dashboard')}
          onGoToPublicWebsite={() => navigateRoute('/')}
          onNavigateRoute={navigateRoute}
        />
      );
    }

    // 4. Default Main Route (/) — Unified PMP PAINTS ERP Website + Official App Showcase
    return (
      <HomePage
        onEnterPortal={handleEnterPortal}
        onNavigateRoute={navigateRoute}
      />
    );
  };

  return (
    <Suspense fallback={<RouteLoadingFallback />}>
      {renderActiveRoute()}
    </Suspense>
  );
};

export const App: React.FC = () => {
  return (
    <ErrorBoundary>
      <ThemeProvider>
        <LanguageProvider>
          <AuthProvider>
            <DesktopWindowChrome>
              <AppContent />
            </DesktopWindowChrome>
          </AuthProvider>
        </LanguageProvider>
      </ThemeProvider>
    </ErrorBoundary>
  );
};

export default App;
