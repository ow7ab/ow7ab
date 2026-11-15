import React, { createContext, useContext, useState, useEffect } from 'react';
import { safeStorageGet, safeStorageSet } from '../utils/safeStorage';

export type ThemeMode = 'true-black' | 'daylight';

interface ThemeContextType {
  theme: ThemeMode;
  toggleTheme: () => void;
  setTheme: (theme: ThemeMode) => void;
}

const ThemeContext = createContext<ThemeContextType>({
  theme: 'true-black',
  toggleTheme: () => {},
  setTheme: () => {}
});

export const ThemeProvider: React.FC<{ children: React.ReactNode }> = ({ children }) => {
  const [theme, setThemeState] = useState<ThemeMode>(() => {
    const saved = safeStorageGet('pmp_web_theme') as ThemeMode | null;
    return saved === 'daylight' ? 'daylight' : 'true-black';
  });

  useEffect(() => {
    try {
      document.documentElement.setAttribute('data-theme', theme);
    } catch {
      // Ignore restricted DOM access
    }
    safeStorageSet('pmp_web_theme', theme);
  }, [theme]);

  const toggleTheme = () => {
    setThemeState(prev => (prev === 'true-black' ? 'daylight' : 'true-black'));
  };

  const setTheme = (newTheme: ThemeMode) => {
    setThemeState(newTheme);
  };

  return (
    <ThemeContext.Provider value={{ theme, toggleTheme, setTheme }}>
      {children}
    </ThemeContext.Provider>
  );
};

export const useTheme = () => useContext(ThemeContext);
