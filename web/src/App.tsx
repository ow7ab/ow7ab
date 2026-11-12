import React from 'react';
import { ThemeProvider } from './context/ThemeContext';
import { LanguageProvider } from './context/LanguageContext';
import { AuthProvider } from './context/AuthContext';
import { HomePage } from './pages/HomePage';

export const App: React.FC = () => {
  return (
    <ThemeProvider>
      <LanguageProvider>
        <AuthProvider>
          <HomePage />
        </AuthProvider>
      </LanguageProvider>
    </ThemeProvider>
  );
};

export default App;
