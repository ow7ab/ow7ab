import React, { createContext, useContext, useState, useEffect } from 'react';
import {
  User,
  onAuthStateChanged,
  signOut as fbSignOut,
  signInWithEmailAndPassword,
  signInWithPopup,
  sendPasswordResetEmail
} from 'firebase/auth';
import { doc, getDoc, onSnapshot } from 'firebase/firestore';
import { auth, firestore, googleAuthProvider } from '../config/firebaseConfig';
import { UserAccount, LinkedAccountDoc, UserRoleType } from '../types/erp';
import {
  resolveUserRoleType,
  checkActionPermission,
  checkModuleAccess
} from '../services/SecurityService';
import { AccountService } from '../services/AccountService';
import { ErpDataService, safeId } from '../services/ErpDataService';
import {
  verifyPassword,
  needsUpgradeToPbkdf2,
  computeSessionIntegritySeal,
  verifySessionIntegritySeal,
  LoginRateLimiter
} from '../services/PasswordSecurity';
import { getDesktopBridge } from '../utils/desktopBridge';
import { safeStorageGet, safeStorageRemove, safeStorageSet } from '../utils/safeStorage';

const SESSION_STORAGE_KEY = 'pmp_web_portal_user';

function formatLockDurationAr(seconds: number): string {
  const mins = Math.max(1, Math.ceil(seconds / 60));
  return `${mins} دقيقة`;
}

interface AuthContextType {
  currentUser: User | null;
  linkedAccount: UserAccount | null;
  userProfile: UserAccount | null;
  userRoleType: UserRoleType;
  isLoading: boolean;
  isAuthenticating: boolean;
  isSuperAdmin: boolean;
  isManager: boolean;
  isEmployee: boolean;
  canAccessModule: (moduleName: string) => boolean;
  canPerformAction: (actionToken: string) => boolean;
  loginWithEmail: (email: string, pass: string) => Promise<void>;
  loginWithUsernamePin: (username: string, pin: string) => Promise<void>;
  loginWithGoogle: () => Promise<void>;
  requestPasswordReset: (email: string) => Promise<void>;
  signOut: () => Promise<void>;
  updateCurrentUserProfile: (data: Partial<UserAccount>) => Promise<void>;
}

const AuthContext = createContext<AuthContextType>({
  currentUser: null,
  linkedAccount: null,
  userProfile: null,
  userRoleType: 'EMPLOYEE',
  isLoading: true,
  isAuthenticating: false,
  isSuperAdmin: false,
  isManager: false,
  isEmployee: true,
  canAccessModule: () => false,
  canPerformAction: () => false,
  loginWithEmail: async () => {},
  loginWithUsernamePin: async () => {},
  loginWithGoogle: async () => {},
  requestPasswordReset: async () => {},
  signOut: async () => {},
  updateCurrentUserProfile: async () => {}
});

export const AuthProvider: React.FC<{ children: React.ReactNode }> = ({ children }) => {
  const [currentUser, setCurrentUser] = useState<User | null>(null);
  const [linkedAccount, setLinkedAccount] = useState<UserAccount | null>(null);

  const [isLoading, setIsLoading] = useState(true);
  const [isAuthenticating, setIsAuthenticating] = useState(false);

  const persistSession = (account: UserAccount | null) => {
    if (account) {
      const issuedAt = Date.now();
      const sanitized: UserAccount = {
        ...account,
        pinOrPassword: undefined, // Never persist secret hashes into localStorage
        roleType: resolveUserRoleType(account)
      };
      setLinkedAccount(sanitized);
      void computeSessionIntegritySeal({
        userCode: sanitized.userCode,
        username: sanitized.username,
        role: sanitized.role,
        roleType: sanitized.roleType,
        branch: sanitized.branch,
        permissions: sanitized.permissions,
        active: sanitized.active,
        syncStatus: sanitized.syncStatus,
        serverSyncToken: sanitized.serverSyncToken,
        issuedAt
      })
        .then((seal) => {
          const payloadStr = JSON.stringify({
            ...sanitized,
            _issuedAt: issuedAt,
            _integritySeal: seal
          });
          safeStorageSet(SESSION_STORAGE_KEY, payloadStr);
          const desktopBridge = getDesktopBridge();
          if (desktopBridge) {
            void desktopBridge.secureStorageSet(SESSION_STORAGE_KEY, payloadStr).catch(() => {});
          }
        })
        .catch(() => {
          safeStorageRemove(SESSION_STORAGE_KEY);
          const desktopBridge = getDesktopBridge();
          if (desktopBridge) {
            void desktopBridge.secureStorageRemove(SESSION_STORAGE_KEY).catch(() => {});
          }
        });
    } else {
      setLinkedAccount(null);
      safeStorageRemove(SESSION_STORAGE_KEY);
      const desktopBridge = getDesktopBridge();
      if (desktopBridge) {
        void desktopBridge.secureStorageRemove(SESSION_STORAGE_KEY).catch(() => {});
      }
    }
  };

  // Restore cached session ONLY if its cryptographic integrity seal is valid
  useEffect(() => {
    let cancelled = false;
    (async () => {
      try {
        let saved = safeStorageGet(SESSION_STORAGE_KEY);
        const desktopBridge = getDesktopBridge();
        if (!saved && desktopBridge) {
          saved = await desktopBridge.secureStorageGet(SESSION_STORAGE_KEY).catch(() => null);
        }
        if (saved) {
          const parsed = JSON.parse(saved);
          const sealOk = await verifySessionIntegritySeal(parsed);
          if (sealOk && parsed?.userCode && parsed.active !== false && !cancelled) {
            const cleanAccount: UserAccount = { ...parsed };
            delete (cleanAccount as any)._integritySeal;
            delete (cleanAccount as any)._issuedAt;
            cleanAccount.roleType = resolveUserRoleType(cleanAccount);
            setLinkedAccount(cleanAccount);
          } else {
            safeStorageRemove(SESSION_STORAGE_KEY);
            if (desktopBridge) {
              void desktopBridge.secureStorageRemove(SESSION_STORAGE_KEY).catch(() => {});
            }
          }
        }
      } catch {
        safeStorageRemove(SESSION_STORAGE_KEY);
      }
    })();
    return () => {
      cancelled = true;
    };
  }, []);

  // Start global tombstone & epoch sync ONLY when Firebase Auth session is active
  useEffect(() => {
    if (!currentUser) return;
    const unsubTomb = ErpDataService.subscribeTombstonesAndEpoch();
    return () => unsubTomb();
  }, [currentUser?.uid]);

  // Firebase Auth listener + live verification via zero-trust self-lookup when Firebase Auth is present
  useEffect(() => {
    const unsubscribe = onAuthStateChanged(auth, async (user) => {
      setCurrentUser(user);
      if (user && !linkedAccount) {
        try {
          const matched = await AccountService.findSignedInUserAccount(user);
          if (matched && matched.active && matched.syncStatus !== 'PENDING_MANAGER_APPROVAL') {
            await AccountService.ensureOwnClusterMemberRegistration(matched, user.uid, user.email).catch(() => {});
            persistSession(matched);
            setIsLoading(false);
            return;
          }
        } catch (err) {
          console.warn('Linked account sync note:', err);
        }
      }
      setIsLoading(false);
    });

    return () => unsubscribe();
  }, []);

  // Live session security listener: watches Firestore user_accounts & deleted_users so suspended/deleted/spoofed sessions terminate immediately
  useEffect(() => {
    if (!linkedAccount?.userCode || !currentUser) return;
    const cluster = ErpDataService.activeCluster;
    const docId = safeId(linkedAccount.userCode, `USR-${linkedAccount.id}`);
    const userRef = doc(firestore, `erp_clusters/${cluster}/user_accounts`, docId);
    const deletedRef = doc(firestore, `erp_clusters/${cluster}/deleted_users`, docId);

    const unsubDeleted = onSnapshot(
      deletedRef,
      (snap) => {
        if (snap.exists()) {
          persistSession(null);
        }
      },
      () => {}
    );

    const unsub = onSnapshot(
      userRef,
      (snap) => {
        // Authoritative server check: if the user doc does not exist on Firestore (except cached latency, check fromCache), terminate spoofed/deleted sessions
        if (!snap.exists()) {
          if (!snap.metadata.fromCache) {
            persistSession(null);
          }
          return;
        }
        const d = snap.data() || {};
        if (d.active === false || d.syncStatus === 'PENDING_MANAGER_APPROVAL') {
          persistSession(null);
          return;
        }
        const refreshed: UserAccount = {
          ...linkedAccount,
          fullName: d.fullName || linkedAccount.fullName,
          username: d.username || linkedAccount.username,
          phoneOrEmail: d.phoneOrEmail ?? linkedAccount.phoneOrEmail,
          role: d.role || linkedAccount.role,
          branch: d.branch || linkedAccount.branch,
          permissions: d.permissions ?? linkedAccount.permissions,
          syncStatus: d.syncStatus || 'APPROVED_BY_MANAGER',
          active: d.active !== false
        };
        refreshed.roleType = resolveUserRoleType(refreshed);
        // Always sync authoritative role/permissions/branch/name from server
        if (
          refreshed.permissions !== linkedAccount.permissions ||
          refreshed.role !== linkedAccount.role ||
          refreshed.branch !== linkedAccount.branch ||
          refreshed.fullName !== linkedAccount.fullName ||
          refreshed.username !== linkedAccount.username
        ) {
          persistSession(refreshed);
        }
      },
      () => {}
    );

    return () => {
      unsub();
      unsubDeleted();
    };
  }, [linkedAccount?.userCode, currentUser?.uid]);

  const userRoleType: UserRoleType = resolveUserRoleType(linkedAccount);
  const isSuperAdmin = userRoleType === 'SUPER_ADMIN';
  const isManager = userRoleType === 'MANAGER';
  const isEmployee = userRoleType === 'EMPLOYEE';

  const canAccessModule = (moduleName: string): boolean => {
    return checkModuleAccess(linkedAccount, moduleName);
  };

  const canPerformAction = (actionToken: string): boolean => {
    return checkActionPermission(linkedAccount, actionToken);
  };

  const validateAccountAccessState = (account: UserAccount) => {
    if (account.syncStatus === 'PENDING_MANAGER_APPROVAL') {
      throw new Error('حسابك بانتظار موافقة مدير النظام وتفعيل الصلاحيات.');
    }
    if (!account.active) {
      throw new Error('تم تجميد هذا الحساب من قبل الإدارة. يرجى مراجعة مدير النظام.');
    }
  };

  const loginWithUsernamePin = async (userVal: string, pinVal: string) => {
    setIsAuthenticating(true);
    try {
      const cleanUser = (userVal || '').replace(/[\u0000-\u001F\u007F]/g, '').trim().slice(0, 64);
      const cleanPin = (pinVal || '').replace(/[\u0000-\u001F\u007F]/g, '').trim().slice(0, 128);
      if (!cleanUser || !cleanPin) {
        throw new Error('يرجى إدخال اسم المستخدم والرمز السري / كلمة المرور.');
      }

      const lockStatus = LoginRateLimiter.checkLockStatus(cleanUser);
      if (lockStatus.locked) {
        await ErpDataService.logAudit(
          cleanUser,
          'WEB_LOGIN_LOCKED_OUT',
          `حظر مؤقت لمحاولة تسجيل دخول متكررة (${formatLockDurationAr(lockStatus.remainingSeconds)})`
        );
        throw new Error(
          `تم تعليق محاولات الدخول مؤقتاً بسبب تكرار الخطأ. يرجى المحاولة بعد ${formatLockDurationAr(lockStatus.remainingSeconds)}.`
        );
      }

      if (!auth.currentUser) {
        throw new Error(
          'لأغراض الحماية السحابية (Zero-Trust)، يرجى تسجيل الدخول عبر زر Google المعتمد أو تبويب البريد الإلكتروني وكلمة المرور أولاً.'
        );
      }

      const matched = await AccountService.findAccountForLogin(cleanUser);
      if (!matched) {
        const rl = LoginRateLimiter.recordFailure(cleanUser);
        if (rl.locked) {
          throw new Error(
            `تم قفل تسجيل الدخول مؤقتاً لمدة ${formatLockDurationAr(rl.remainingSeconds)} حمايةً للنظام.`
          );
        }
        throw new Error('اسم المستخدم أو الرمز السري غير صحيح.');
      }

      validateAccountAccessState(matched);

      const storedSecret = (matched.pinOrPassword || '').trim();
      if (!storedSecret) {
        throw new Error('لم يتم تعيين رمز سري لهذا الحساب. يرجى ضبط كلمة المرور من إدارة المستخدمين أو الدخول عبر Google.');
      }

      const verified = await verifyPassword(cleanPin, storedSecret);
      if (!verified) {
        const rl = LoginRateLimiter.recordFailure(cleanUser);
        await ErpDataService.logAudit(
          cleanUser,
          rl.locked ? 'WEB_LOGIN_LOCKED_OUT' : 'WEB_LOGIN_FAILED',
          rl.locked
            ? `تجاوز الحد الأقصى للمحاولات الفاشلة وتم الحظر لمدة ${formatLockDurationAr(rl.remainingSeconds)}`
            : `محاولة دخول برمز سري غير صحيح (متبقي ${rl.attemptsRemaining} محاولات)`
        );
        if (rl.locked) {
          throw new Error(
            `تم تجاوز الحد المسموح للمحاولات. تم إيقاف الدخول مؤقتاً لمدة ${formatLockDurationAr(rl.remainingSeconds)}.`
          );
        }
        throw new Error(`الرمز السري أو كلمة المرور غير صحيحة (متبقي ${rl.attemptsRemaining} محاولات).`);
      }

      LoginRateLimiter.recordSuccess(cleanUser);

      // Transparent upgrade from legacy SHA-256 / plaintext PIN to PBKDF2-HMAC-SHA256 using 8-key self-update whitelist
      if (needsUpgradeToPbkdf2(storedSecret)) {
        AccountService.updateOwnProfile(matched, { rawNewPassword: cleanPin }).catch(() => {});
      }

      await ErpDataService.logAudit(
        matched.username,
        'WEB_LOGIN_SUCCESS',
        `دخول ناجح للويب (${matched.role} - ${matched.branch})`
      );
      persistSession(matched);
    } finally {
      setIsAuthenticating(false);
    }
  };

  const loginWithEmail = async (emailVal: string, passVal: string) => {
    setIsAuthenticating(true);
    try {
      const cleanEmail = (emailVal || '').replace(/[\u0000-\u001F\u007F]/g, '').trim().toLowerCase().slice(0, 120);
      const cleanPass = (passVal || '').replace(/[\u0000-\u001F\u007F]/g, '').trim().slice(0, 128);
      if (!cleanEmail || !cleanPass) {
        throw new Error('يرجى إدخال البريد الإلكتروني وكلمة المرور.');
      }

      const lockStatus = LoginRateLimiter.checkLockStatus(cleanEmail);
      if (lockStatus.locked) {
        await ErpDataService.logAudit(
          cleanEmail,
          'WEB_LOGIN_LOCKED_OUT',
          `حظر مؤقت لتكرار محاولات البريد الخاطئة (${formatLockDurationAr(lockStatus.remainingSeconds)})`
        );
        throw new Error(
          `تم تعليق تسجيل الدخول مؤقتاً بسبب تكرار المحاولات الخاطئة. يرجى المحاولة بعد ${formatLockDurationAr(lockStatus.remainingSeconds)}.`
        );
      }

      // 1. Check Firebase Auth if configured for this corporate email
      let fbCredUser: User | null = null;
      let fbAuthError: any = null;
      try {
        const cred = await signInWithEmailAndPassword(auth, cleanEmail, cleanPass);
        fbCredUser = cred.user;
      } catch (err: any) {
        fbAuthError = err;
      }

      // 2. Resolve employee profile via zero-trust self-lookup when Firebase Auth succeeded
      if (fbCredUser) {
        await AccountService.ensureOwnClusterMemberRegistration(null, fbCredUser.uid, fbCredUser.email || cleanEmail).catch(() => {});
        const matched = await AccountService.findSignedInUserAccount(fbCredUser);
        if (matched) {
          validateAccountAccessState(matched);
          await AccountService.ensureOwnClusterMemberRegistration(matched, fbCredUser.uid, fbCredUser.email || cleanEmail).catch(() => {});
          LoginRateLimiter.recordSuccess(cleanEmail);
          await ErpDataService.logAudit(matched.username, 'WEB_LOGIN_EMAIL', `تسجيل دخول عبر البريد ${cleanEmail}`);
          persistSession(matched);
          return;
        }
        throw new Error('تم التحقق من البريد ولكن الحساب غير مرتبط بسجل موظف معتمد في المنظومة.');
      }

      // If Firebase Auth did not sign in, surface clear auth error without unauthenticated Firestore list
      LoginRateLimiter.recordFailure(cleanEmail);
      throw new Error(
        fbAuthError?.code === 'auth/wrong-password' || fbAuthError?.code === 'auth/invalid-credential'
          ? 'البريد الإلكتروني أو كلمة المرور غير صحيحة. يمكنك أيضاً تسجيل الدخول مباشرة عبر Google.'
          : fbAuthError?.code === 'auth/user-not-found'
          ? 'هذا البريد غير مسجل في Firebase Auth — يرجى استخدام زر تسجيل الدخول عبر Google المعتمد.'
          : 'تعذر التحقق من البريد الإلكتروني وكلمة المرور. يرجى استخدام زر تسجيل الدخول عبر Google.'
      );
    } finally {
      setIsAuthenticating(false);
    }
  };

  const loginWithGoogle = async () => {
    setIsAuthenticating(true);
    try {
      const res = await signInWithPopup(auth, googleAuthProvider);
      const fbUser = res.user;
      const email = (fbUser?.email || '').trim().toLowerCase();
      if (!email) {
        throw new Error('لم يتم استلام البريد الإلكتروني من حساب Google.');
      }

      // CRITICAL: Bootstrap/recover cluster_members/{uid} BEFORE reading user_accounts
      await AccountService.ensureOwnClusterMemberRegistration(null, fbUser.uid, email).catch(() => {});
      const matched = await AccountService.findSignedInUserAccount(fbUser);
      if (matched) {
        validateAccountAccessState(matched);
        if (fbUser?.uid) {
          await AccountService.ensureOwnClusterMemberRegistration(matched, fbUser.uid, email).catch(() => {});
        }
        LoginRateLimiter.recordSuccess(email);
        await ErpDataService.logAudit(matched.username, 'WEB_LOGIN_GOOGLE', `تسجيل دخول عبر Google (${email})`);
        persistSession(matched);
        return;
      }

      // Not yet approved in user_accounts -> submit pending request matching Android Google Sign-In flow
      await AccountService.submitPendingGoogleRequest(email, fbUser.displayName || email.split('@')[0]);
      await fbSignOut(auth).catch(() => {});
      throw new Error(
        'تم تسجيل طلب ربط حساب Google بنجاح. الحساب حالياً بانتظار موافقة مدير النظام وتحديد الصلاحيات.'
      );
    } finally {
      setIsAuthenticating(false);
    }
  };

  const requestPasswordReset = async (emailVal: string) => {
    const cleanEmail = emailVal.trim();
    if (!cleanEmail) throw new Error('يرجى إدخال البريد الإلكتروني أولاً.');
    await sendPasswordResetEmail(auth, cleanEmail);
  };

  const signOut = async () => {
    await fbSignOut(auth).catch(() => {});
    persistSession(null);
  };

  const updateCurrentUserProfile = async (data: Partial<UserAccount>) => {
    if (!linkedAccount) return;
    // SECURITY: Strict 8-key self-update whitelist matching firestore.rules (blocks role/permissions escalation)
    const updated: UserAccount = {
      ...linkedAccount,
      fullName: typeof data.fullName === 'string' && data.fullName.trim() ? data.fullName.trim().slice(0, 120) : linkedAccount.fullName,
      phoneOrEmail: typeof data.phoneOrEmail === 'string' ? data.phoneOrEmail.trim().slice(0, 120) : linkedAccount.phoneOrEmail,
      lastLoginAt: data.lastLoginAt ?? linkedAccount.lastLoginAt
    };
    updated.roleType = resolveUserRoleType(updated);
    persistSession(updated);
    await AccountService.updateOwnProfile(updated, {
      fullName: updated.fullName,
      phoneOrEmail: updated.phoneOrEmail,
      lastLoginAt: typeof updated.lastLoginAt === 'number' ? updated.lastLoginAt : undefined
    });
  };

  return (
    <AuthContext.Provider
      value={{
        currentUser,
        linkedAccount,
        userProfile: linkedAccount,
        userRoleType,
        isLoading,
        isAuthenticating,
        isSuperAdmin,
        isManager,
        isEmployee,
        canAccessModule,
        canPerformAction,
        loginWithEmail,
        loginWithUsernamePin,
        loginWithGoogle,
        requestPasswordReset,
        signOut,
        updateCurrentUserProfile
      }}
    >
      {children}
    </AuthContext.Provider>
  );
};

export const useAuth = () => useContext(AuthContext);
