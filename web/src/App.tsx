import React, { useState } from 'react';
import { ThemeProvider } from './context/ThemeContext';
import { LanguageProvider } from './context/LanguageContext';
import { AuthProvider, useAuth } from './context/AuthContext';
import { HomePage } from './pages/HomePage';
import { PortalPage } from './pages/PortalPage';
import { LoginPage } from './pages/LoginPage';

type AppView = 'LOGIN' | 'PORTAL' | 'PUBLIC_SITE';

const AppContent: React.FC = () => {
  const { userProfile } = useAuth();
  // Default to LOGIN when not authenticated, or PORTAL if authenticated session exists
  const [currentView, setCurrentView] = useState<AppView>(() => {
    return userProfile ? 'PORTAL' : 'LOGIN';
  });

  return (
    <>
      {currentView === 'LOGIN' && (
        <LoginPage
          onLoginSuccess={() => setCurrentView('PORTAL')}
          onGoToPublicWebsite={() => setCurrentView('PUBLIC_SITE')}
        />
      )}

      {currentView === 'PORTAL' && (
        userProfile ? (
          <PortalPage
            onBackToWebsite={() => setCurrentView('PUBLIC_SITE')}
            onLogoutToLogin={() => setCurrentView('LOGIN')}
          />
        ) : (
          <LoginPage
            onLoginSuccess={() => setCurrentView('PORTAL')}
            onGoToPublicWebsite={() => setCurrentView('PUBLIC_SITE')}
          />
        )
      )}

      {currentView === 'PUBLIC_SITE' && (
        <HomePage
          onEnterPortal={() => {
            if (userProfile) {
              setCurrentView('PORTAL');
            } else {
              setCurrentView('LOGIN');
            }
          }}
        />
      )}
    </>
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
