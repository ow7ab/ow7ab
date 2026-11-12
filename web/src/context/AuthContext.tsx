import React, { createContext, useContext, useState, useEffect } from 'react';
import { User, onAuthStateChanged, signOut as fbSignOut, signInWithEmailAndPassword, signInWithPopup } from 'firebase/auth';
import { doc, getDoc, collection, query, where, getDocs } from 'firebase/firestore';
import { auth, firestore, googleAuthProvider } from '../config/firebaseConfig';
import { UserAccount, LinkedAccountDoc } from '../types/erp';

interface AuthContextType {
  currentUser: User | null;
  linkedAccount: UserAccount | null;
  userProfile: UserAccount | null;
  isLoading: boolean;
  isAuthenticating: boolean;
  isSuperAdmin: boolean;
  isSystemManager: boolean;
  hasPermission: (permissionName: string) => boolean;
  loginWithEmail: (email: string, pass: string) => Promise<void>;
  loginWithUsernamePin: (username: string, pin: string) => Promise<void>;
  loginWithGoogle: () => Promise<void>;
  signOut: () => Promise<void>;
  simulatePortalSession: (account: UserAccount) => void;
}

const AuthContext = createContext<AuthContextType>({
  currentUser: null,
  linkedAccount: null,
  userProfile: null,
  isLoading: true,
  isAuthenticating: false,
  isSuperAdmin: false,
  isSystemManager: false,
  hasPermission: () => false,
  loginWithEmail: async () => {},
  loginWithUsernamePin: async () => {},
  loginWithGoogle: async () => {},
  signOut: async () => {},
  simulatePortalSession: () => {}
});

export const AuthProvider: React.FC<{ children: React.ReactNode }> = ({ children }) => {
  const [currentUser, setCurrentUser] = useState<User | null>(null);
  const [linkedAccount, setLinkedAccount] = useState<UserAccount | null>(() => {
    const saved = localStorage.getItem('pmp_web_portal_user');
    return saved ? JSON.parse(saved) : null;
  });
  const [isLoading, setIsLoading] = useState(true);
  const [isAuthenticating, setIsAuthenticating] = useState(false);

  useEffect(() => {
    const unsubscribe = onAuthStateChanged(auth, async (user) => {
      setCurrentUser(user);
      if (user) {
        try {
          const docRef = doc(firestore, 'linked_accounts', user.uid);
          const docSnap = await getDoc(docRef);
          if (docSnap.exists()) {
            const data = docSnap.data() as LinkedAccountDoc;
            const mapped: UserAccount = {
              id: 1,
              userCode: data.userCode,
              fullName: data.fullName,
              username: data.username,
              phoneOrEmail: user.email || '',
              role: data.role,
              branch: data.branch,
              permissions: data.permissions,
              serverSyncToken: data.serverSyncToken,
              syncStatus: 'APPROVED_BY_MANAGER',
              active: data.active
            };
            setLinkedAccount(mapped);
            localStorage.setItem('pmp_web_portal_user', JSON.stringify(mapped));
          }
        } catch (e) {
          console.warn('Note: Linked account query in cloud:', e);
        }
      }
      setIsLoading(false);
    });

    return () => unsubscribe();
  }, []);

  const loginWithEmail = async (emailVal: string, passVal: string) => {
    setIsAuthenticating(true);
    try {
      const cred = await signInWithEmailAndPassword(auth, emailVal, passVal);
      const docRef = doc(firestore, 'linked_accounts', cred.user.uid);
      const docSnap = await getDoc(docRef);
      if (docSnap.exists()) {
        const data = docSnap.data() as LinkedAccountDoc;
        const mapped: UserAccount = {
          id: 1,
          userCode: data.userCode,
          fullName: data.fullName,
          username: data.username,
          phoneOrEmail: cred.user.email || '',
          role: data.role,
          branch: data.branch,
          permissions: data.permissions,
          serverSyncToken: data.serverSyncToken,
          syncStatus: 'APPROVED_BY_MANAGER',
          active: data.active
        };
        setLinkedAccount(mapped);
        localStorage.setItem('pmp_web_portal_user', JSON.stringify(mapped));
      }
    } finally {
      setIsAuthenticating(false);
    }
  };

  const loginWithUsernamePin = async (userVal: string, pinVal: string) => {
    setIsAuthenticating(true);
    try {
      // Query linked account or staff profile by username
      const q = query(
        collection(firestore, 'linked_accounts'),
        where('username', '==', userVal.trim().toLowerCase())
      );
      const snap = await getDocs(q);
      if (snap.empty) {
        // Fallback for default superadmin during initial configuration
        if (userVal.trim().toLowerCase() === 'admin' && pinVal === '1234') {
          const defaultAdmin: UserAccount = {
            id: 1,
            userCode: 'USR-ADMIN',
            fullName: 'مدير النظام التنفيذي',
            username: 'admin',
            phoneOrEmail: 'admin@pmppaints.com',
            role: 'مدير النظام (كامل الصلاحيات)',
            branch: 'SD-001',
            permissions: 'عرض,إنشاء,تعديل,حذف,اعتماد,تصدير,طباعة,عمليات مالية,حركات مخازن,إدارة المستخدمين,الإعدادات الإدارية',
            serverSyncToken: 'SUPER_ADMIN_TOKEN',
            syncStatus: 'APPROVED_BY_MANAGER',
            active: true
          };
          setLinkedAccount(defaultAdmin);
          localStorage.setItem('pmp_web_portal_user', JSON.stringify(defaultAdmin));
          return;
        }
        throw new Error('اسم المستخدم غير مسجل في منظومة PMP');
      }

      const docData = snap.docs[0].data() as LinkedAccountDoc;
      const mapped: UserAccount = {
        id: 1,
        userCode: docData.userCode,
        fullName: docData.fullName,
        username: docData.username,
        phoneOrEmail: '',
        role: docData.role,
        branch: docData.branch,
        permissions: docData.permissions,
        serverSyncToken: docData.serverSyncToken,
        syncStatus: 'APPROVED_BY_MANAGER',
        active: docData.active
      };
      setLinkedAccount(mapped);
      localStorage.setItem('pmp_web_portal_user', JSON.stringify(mapped));
    } finally {
      setIsAuthenticating(false);
    }
  };

  const loginWithGoogle = async () => {
    setIsAuthenticating(true);
    try {
      await signInWithPopup(auth, googleAuthProvider);
    } finally {
      setIsAuthenticating(false);
    }
  };

  const isSuperAdmin = Boolean(
    linkedAccount?.username?.toLowerCase() === 'admin' ||
    linkedAccount?.userCode === 'USR-ADMIN'
  );

  const isSystemManager = Boolean(
    isSuperAdmin || linkedAccount?.role?.includes('مدير النظام')
  );

  const hasPermission = (permissionName: string): boolean => {
    if (isSuperAdmin) return true;
    if (isSystemManager && permissionName !== 'إدارة المستخدمين' && permissionName !== 'الإعدادات الإدارية') {
      return true;
    }
    if (!linkedAccount || !linkedAccount.active) return false;
    const list = linkedAccount.permissions.split(',').map(p => p.trim());
    return list.includes(permissionName);
  };

  const signOut = async () => {
    await fbSignOut(auth).catch(() => {});
    setLinkedAccount(null);
    localStorage.removeItem('pmp_web_portal_user');
  };

  const simulatePortalSession = (account: UserAccount) => {
    setLinkedAccount(account);
    localStorage.setItem('pmp_web_portal_user', JSON.stringify(account));
  };

  return (
    <AuthContext.Provider
      value={{
        currentUser,
        linkedAccount,
        userProfile: linkedAccount,
        isLoading,
        isAuthenticating,
        isSuperAdmin,
        isSystemManager,
        hasPermission,
        loginWithEmail,
        loginWithUsernamePin,
        loginWithGoogle,
        signOut,
        simulatePortalSession
      }}
    >
      {children}
    </AuthContext.Provider>
  );
};

export const useAuth = () => useContext(AuthContext);
