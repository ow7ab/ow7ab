import React, { createContext, useContext, useState, useEffect } from 'react';
import { User, onAuthStateChanged, signOut as firebaseSignOut } from 'firebase/auth';
import { doc, getDoc } from 'firebase/firestore';
import { auth, firestore, DEFAULT_CLUSTER } from '../firebase/config';
import { UserAccount, LinkedAccountModel } from '../types/erp';

export type Language = 'ar' | 'en';
export type Theme = 'true-black' | 'daylight';
export type NavSection =
  | 'home'
  | 'overview'
  | 'features'
  | 'download'
  | 'whats-new'
  | 'install-guide'
  | 'support'
  | 'login'
  | 'dashboard';

interface AppContextType {
  lang: Language;
  setLang: (lang: Language) => void;
  theme: Theme;
  setTheme: (theme: Theme) => void;
  toggleTheme: () => void;
  currentSection: NavSection;
  setCurrentSection: (section: NavSection) => void;
  user: User | null;
  employeeProfile: UserAccount | LinkedAccountModel | null;
  isLoadingAuth: boolean;
  clusterCode: string;
  isOnline: boolean;
  uiMessage: { text: string; isError?: boolean } | null;
  showMessage: (text: string, isError?: boolean) => void;
  clearMessage: () => void;
  signOut: () => Promise<void>;
}

const AppContext = createContext<AppContextType | null>(null);

export const AppProvider: React.FC<{ children: React.ReactNode }> = ({ children }) => {
  const [lang, setLang] = useState<Language>(() => {
    return (localStorage.getItem('pmp_web_lang') as Language) || 'ar';
  });

  const [theme, setTheme] = useState<Theme>(() => {
    return (localStorage.getItem('pmp_web_theme') as Theme) || 'true-black';
  });

  const [currentSection, setCurrentSection] = useState<NavSection>('home');
  const [user, setUser] = useState<User | null>(null);
  const [employeeProfile, setEmployeeProfile] = useState<UserAccount | LinkedAccountModel | null>(null);
  const [isLoadingAuth, setIsLoadingAuth] = useState(true);
  const [clusterCode] = useState(DEFAULT_CLUSTER);
  const [isOnline, setIsOnline] = useState(navigator.onLine);
  const [uiMessage, setUiMessage] = useState<{ text: string; isError?: boolean } | null>(null);

  // Sync HTML attributes with theme and language
  useEffect(() => {
    localStorage.setItem('pmp_web_lang', lang);
    document.documentElement.lang = lang;
    document.documentElement.dir = lang === 'ar' ? 'rtl' : 'ltr';
  }, [lang]);

  useEffect(() => {
    localStorage.setItem('pmp_web_theme', theme);
    document.documentElement.setAttribute('data-theme', theme);
  }, [theme]);

  // Online / offline tracking
  useEffect(() => {
    const handleOnline = () => setIsOnline(true);
    const handleOffline = () => setIsOnline(false);
    window.addEventListener('online', handleOnline);
    window.addEventListener('offline', handleOffline);
    return () => {
      window.removeEventListener('online', handleOnline);
      window.removeEventListener('offline', handleOffline);
    };
  }, []);

  // Firebase auth state listener
  useEffect(() => {
    const unsubscribe = onAuthStateChanged(auth, async (currentUser) => {
      setUser(currentUser);
      if (currentUser) {
        try {
          // Check linked accounts
          const linkedDoc = await getDoc(doc(firestore, 'linked_accounts', currentUser.uid));
          if (linkedDoc.exists()) {
            setEmployeeProfile(linkedDoc.data() as LinkedAccountModel);
          } else {
            // Check cluster user_accounts
            const clusterUserDoc = await getDoc(
              doc(firestore, `erp_clusters/${DEFAULT_CLUSTER}/user_accounts`, currentUser.uid)
            );
            if (clusterUserDoc.exists()) {
              setEmployeeProfile(clusterUserDoc.data() as UserAccount);
            }
          }
        } catch (err) {
          console.warn('Error fetching employee profile from Firestore:', err);
        }
      } else {
        setEmployeeProfile(null);
      }
      setIsLoadingAuth(false);
    });

    return () => unsubscribe();
  }, []);

  const toggleTheme = () => {
    setTheme((prev) => (prev === 'true-black' ? 'daylight' : 'true-black'));
  };

  const showMessage = (text: string, isError = false) => {
    setUiMessage({ text, isError });
    setTimeout(() => {
      setUiMessage(null);
    }, 4500);
  };

  const clearMessage = () => setUiMessage(null);

  const signOut = async () => {
    try {
      await firebaseSignOut(auth);
      setUser(null);
      setEmployeeProfile(null);
      showMessage(lang === 'ar' ? 'تم تسجيل الخروج بنجاح' : 'Signed out successfully');
      setCurrentSection('home');
    } catch (err: unknown) {
      const message = err instanceof Error ? err.message : String(err);
      showMessage(message, true);
    }
  };

  return (
    <AppContext.Provider
      value={{
        lang,
        setLang,
        theme,
        setTheme,
        toggleTheme,
        currentSection,
        setCurrentSection,
        user,
        employeeProfile,
        isLoadingAuth,
        clusterCode,
        isOnline,
        uiMessage,
        showMessage,
        clearMessage,
        signOut
      }}
    >
      {children}
    </AppContext.Provider>
  );
};

export const useApp = () => {
  const context = useContext(AppContext);
  if (!context) {
    throw new Error('useApp must be used within an AppProvider');
  }
  return context;
};
