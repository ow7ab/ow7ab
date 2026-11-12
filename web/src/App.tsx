import React, { useState } from 'react';
import { ThemeProvider } from './context/ThemeContext';
import { LanguageProvider } from './context/LanguageContext';
import { AuthProvider, useAuth } from './context/AuthContext';
import { HomePage } from './pages/HomePage';
import { PortalPage } from './pages/PortalPage';

const AppContent: React.FC = () => {
  const [currentPage, setCurrentPage] = useState<'HOME' | 'PORTAL'>('HOME');
  const { userProfile } = useAuth();

  return (
    <>
      {currentPage === 'PORTAL' && userProfile ? (
        <PortalPage onBackToWebsite={() => setCurrentPage('HOME')} />
      ) : (
        <HomePage onEnterPortal={() => setCurrentPage('PORTAL')} />
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
