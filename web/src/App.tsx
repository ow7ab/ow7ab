import React, { useState, useEffect, useCallback } from 'react';
import { ThemeProvider } from './context/ThemeContext';
import { LanguageProvider } from './context/LanguageContext';
import { AuthProvider, useAuth } from './context/AuthContext';
import { HomePage } from './pages/HomePage';
import { PortalPage } from './pages/PortalPage';
import { LoginPage } from './pages/LoginPage';
import { DownloadPage } from './pages/DownloadPage';
import {
  AppRoute,
  buildBrowserPath,
  normalizeAppRoute,
  resolveInitialRouteFromLocation
} from './utils/routing';

const AppContent: React.FC = () => {
  const { userProfile } = useAuth();
  const [currentRoute, setCurrentRoute] = useState<AppRoute>(() => resolveInitialRouteFromLocation());

  const navigateRoute = useCallback((targetRoute: AppRoute) => {
    setCurrentRoute(targetRoute);
    if (typeof window !== 'undefined') {
      const nextUrl = buildBrowserPath(targetRoute);
      if (window.location.pathname !== nextUrl) {
        window.history.pushState({ route: targetRoute }, '', nextUrl);
      }
      window.scrollTo({ top: 0, behavior: 'smooth' });
    }
  }, []);

  useEffect(() => {
    const handlePopState = () => {
      setCurrentRoute(normalizeAppRoute(window.location.pathname));
    };
    window.addEventListener('popstate', handlePopState);
    return () => window.removeEventListener('popstate', handlePopState);
  }, []);

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

export const App: React.FC = () => {
  return (
    <ThemeProvider>
      <LanguageProvider>
        <AuthProvider>
          <AppContent />
        </AuthProvider>
      </LanguageProvider>
    </ThemeProvider>
  );
};

export default App;
