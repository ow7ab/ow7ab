import React, { createContext, useContext, useState, useEffect, useRef, useCallback } from 'react';
import {
  User,
  onAuthStateChanged,
  signOut as fbSignOut,
  signInWithEmailAndPassword,
  signInWithPopup,
  signInWithRedirect,
  getRedirectResult,
  sendPasswordResetEmail
} from 'firebase/auth';
import { doc, onSnapshot } from 'firebase/firestore';
import { auth, firestore, googleAuthProvider, getProvisioningAuth } from '../config/firebaseConfig';
import { UserAccount, UserRoleType } from '../types/erp';
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
  LoginRateLimiter
} from '../services/PasswordSecurity';
import { getDesktopBridge } from '../utils/desktopBridge';
import { safeStorageGet, safeStorageRemove, safeStorageSet } from '../utils/safeStorage';
import { assertTrustedAuthDomain } from '../utils/authDomainGuard';
import {
  SESSION_STORAGE_KEY,
  AuthLoginMethod,
  DeviceSessionRecord,
  buildUsernameAuthEmailCandidates,
  createSealedSessionJson,
  deactivateDeviceSession,
  getOrCreateDeviceId,
  readDeviceSessions,
  registerOrUpdateDeviceSession,
  sanitizeAccountForSession,
  verifyAndParseStoredSession
} from '../utils/authSessionManager';

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
  currentDeviceId: string;
  deviceSessions: DeviceSessionRecord[];
  sessionExpiredNotice: string | null;
  clearSessionExpiredNotice: () => void;
  canAccessModule: (moduleName: string) => boolean;
  canPerformAction: (actionToken: string) => boolean;
  loginWithEmail: (email: string, pass: string) => Promise<void>;
  loginWithUsernamePin: (username: string, pin: string) => Promise<void>;
  loginWithGoogle: () => Promise<void>;
  switchAccount: (identifier: string, secret: string) => Promise<UserAccount>;
  revokeDeviceSession: (deviceId: string) => Promise<void>;
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
  currentDeviceId: '',
  deviceSessions: [],
  sessionExpiredNotice: null,
  clearSessionExpiredNotice: () => {},
  canAccessModule: () => false,
  canPerformAction: () => false,
  loginWithEmail: async () => {},
  loginWithUsernamePin: async () => {},
  loginWithGoogle: async () => {},
  switchAccount: async () => {
    throw new Error('AuthProvider not initialized');
  },
  revokeDeviceSession: async () => {},
  requestPasswordReset: async () => {},
  signOut: async () => {},
  updateCurrentUserProfile: async () => {}
});

export const AuthProvider: React.FC<{ children: React.ReactNode }> = ({ children }) => {
  const [currentUser, setCurrentUser] = useState<User | null>(null);
  const [linkedAccount, setLinkedAccount] = useState<UserAccount | null>(null);
  const [isLoading, setIsLoading] = useState(true);
  const [isAuthenticating, setIsAuthenticating] = useState(false);
  const [currentDeviceId] = useState<string>(() => getOrCreateDeviceId());
  const [deviceSessions, setDeviceSessions] = useState<DeviceSessionRecord[]>(() => readDeviceSessions());
  const [sessionExpiredNotice, setSessionExpiredNotice] = useState<string | null>(null);

  // Refs eliminate stale closures in onAuthStateChanged & prevent race conditions on switch/logout/double-submit
  const linkedAccountRef = useRef<UserAccount | null>(null);
  const explicitSignOutRef = useRef<boolean>(false);
  const isSubmittingRef = useRef<boolean>(false);
  const isSwitchingAccountRef = useRef<boolean>(false);

  const clearSessionExpiredNotice = useCallback(() => {
    setSessionExpiredNotice(null);
  }, []);

  const persistSession = useCallback(
    (account: UserAccount | null, loginMethod: AuthLoginMethod = 'USERNAME_PIN') => {
      if (account) {
        explicitSignOutRef.current = false;
        const issuedAt = Date.now();
        const sanitized = sanitizeAccountForSession(account);
        linkedAccountRef.current = sanitized;
        setLinkedAccount(sanitized);
        setSessionExpiredNotice(null);
        setDeviceSessions(registerOrUpdateDeviceSession(sanitized, loginMethod, currentDeviceId, issuedAt));

        void createSealedSessionJson(sanitized, issuedAt, loginMethod, currentDeviceId)
          .then((payloadStr) => {
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
        linkedAccountRef.current = null;
        setLinkedAccount(null);
        safeStorageRemove(SESSION_STORAGE_KEY);
        const desktopBridge = getDesktopBridge();
        if (desktopBridge) {
          void desktopBridge.secureStorageRemove(SESSION_STORAGE_KEY).catch(() => {});
        }
      }
    },
    [currentDeviceId]
  );

  // Restore cached session ONLY if its cryptographic integrity seal & TTL are valid
  useEffect(() => {
    let cancelled = false;
    const validateStoredSession = async () => {
      try {
        let saved = safeStorageGet(SESSION_STORAGE_KEY);
        const desktopBridge = getDesktopBridge();
        if (!saved && desktopBridge) {
          saved = await desktopBridge.secureStorageGet(SESSION_STORAGE_KEY).catch(() => null);
        }
        const restored = await verifyAndParseStoredSession(saved);
        if (cancelled) return;

        if (restored.status === 'VALID' && restored.account) {
          linkedAccountRef.current = restored.account;
          setLinkedAccount(restored.account);
          setDeviceSessions(
            registerOrUpdateDeviceSession(
              restored.account,
              restored.loginMethod || 'USERNAME_PIN',
              restored.deviceId || currentDeviceId
            )
          );
        } else if (restored.status === 'EXPIRED') {
          safeStorageRemove(SESSION_STORAGE_KEY);
          if (desktopBridge) {
            void desktopBridge.secureStorageRemove(SESSION_STORAGE_KEY).catch(() => {});
          }
          if (linkedAccountRef.current) {
            linkedAccountRef.current = null;
            setLinkedAccount(null);
          }
          explicitSignOutRef.current = true;
          void fbSignOut(auth).catch(() => {});
          setDeviceSessions(deactivateDeviceSession(currentDeviceId));
          setSessionExpiredNotice('انتهت صلاحية جلسة العمل الحالية لأسباب أمنية. يرجى تسجيل الدخول مجدداً.');
        } else if (restored.status === 'INVALID') {
          safeStorageRemove(SESSION_STORAGE_KEY);
          if (desktopBridge) {
            void desktopBridge.secureStorageRemove(SESSION_STORAGE_KEY).catch(() => {});
          }
          if (linkedAccountRef.current) {
            linkedAccountRef.current = null;
            setLinkedAccount(null);
          }
          explicitSignOutRef.current = true;
          void fbSignOut(auth).catch(() => {});
        }
      } catch {
        safeStorageRemove(SESSION_STORAGE_KEY);
      }
    };

    void validateStoredSession();

    // Re-check session expiry when tab regains focus or visibility
    const onVisibilityOrFocus = () => {
      if (typeof document !== 'undefined' && document.visibilityState === 'hidden') return;
      if (linkedAccountRef.current) {
        void validateStoredSession();
      }
    };
    const intervalId = setInterval(() => {
      if (linkedAccountRef.current) {
        void validateStoredSession();
      }
    }, 5 * 60 * 1000);

    if (typeof window !== 'undefined') {
      window.addEventListener('focus', onVisibilityOrFocus);
      document.addEventListener('visibilitychange', onVisibilityOrFocus);
    }

    return () => {
      cancelled = true;
      clearInterval(intervalId);
      if (typeof window !== 'undefined') {
        window.removeEventListener('focus', onVisibilityOrFocus);
        document.removeEventListener('visibilitychange', onVisibilityOrFocus);
      }
    };
  }, [currentDeviceId]);

  // Start global tombstone & epoch sync ONLY when Firebase Auth session is active
  useEffect(() => {
    if (!currentUser) return;
    const unsubTomb = ErpDataService.subscribeTombstonesAndEpoch();
    return () => unsubTomb();
  }, [currentUser?.uid]);

  // Firebase Auth listener + optional Google redirect completion + zero-trust session sync
  // Uses linkedAccountRef, explicitSignOutRef & isSwitchingAccountRef so switching or signing out NEVER bounces back
  useEffect(() => {
    void getRedirectResult(auth).catch(() => null);
    const unsubscribe = onAuthStateChanged(auth, async (user) => {
      if (isSwitchingAccountRef.current) {
        return;
      }
      if (user?.isAnonymous) {
        await fbSignOut(auth).catch(() => {});
        setCurrentUser(null);
        setIsLoading(false);
        return;
      }
      setCurrentUser(user);
      if (!user) {
        // If Firebase session vanished while not explicitly suppressed offline, clear cached privilege only when explicit sign-out happened
        if (explicitSignOutRef.current && linkedAccountRef.current) {
          persistSession(null);
        }
        setIsLoading(false);
        return;
      }
      if (!linkedAccountRef.current && !explicitSignOutRef.current) {
        try {
          const matched = await AccountService.findSignedInUserAccount(user);
          if (
            matched &&
            matched.active &&
            matched.syncStatus !== 'PENDING_MANAGER_APPROVAL' &&
            !linkedAccountRef.current &&
            !explicitSignOutRef.current &&
            !isSwitchingAccountRef.current
          ) {
            await AccountService.ensureOwnClusterMemberRegistration(matched, user.uid, user.email).catch(() => {});
            if (resolveUserRoleType(matched) === 'SUPER_ADMIN') {
              void AccountService.syncExistingLinkedAccountsMembership();
            }
            void ErpDataService.flushPendingAuditLogs();
            persistSession(matched, 'EMAIL_PASSWORD');
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
  }, [persistSession]);

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
          setSessionExpiredNotice('تم إلغاء هذا الحساب من قبل مدير النظام. تم إنهاء الجلسة.');
          persistSession(null);
        }
      },
      () => {}
    );

    const unsub = onSnapshot(
      userRef,
      (snap) => {
        if (!snap.exists()) {
          if (!snap.metadata.fromCache) {
            persistSession(null);
          }
          return;
        }
        const d = snap.data() || {};
        if (d.active === false || d.syncStatus === 'PENDING_MANAGER_APPROVAL') {
          setSessionExpiredNotice('تم تجميد الحساب أو تعليق صلاحياته من قبل الإدارة.');
          persistSession(null);
          return;
        }
        const currentAcc = linkedAccountRef.current;
        if (!currentAcc || currentAcc.userCode !== linkedAccount.userCode) return;

        const refreshed: UserAccount = {
          ...currentAcc,
          fullName: d.fullName || currentAcc.fullName,
          username: d.username || currentAcc.username,
          phoneOrEmail: d.phoneOrEmail ?? currentAcc.phoneOrEmail,
          role: d.role || currentAcc.role,
          branch: d.branch || currentAcc.branch,
          permissions: d.permissions ?? currentAcc.permissions,
          syncStatus: d.syncStatus || 'APPROVED_BY_MANAGER',
          active: d.active !== false
        };
        refreshed.roleType = resolveUserRoleType(refreshed);
        if (
          refreshed.permissions !== currentAcc.permissions ||
          refreshed.role !== currentAcc.role ||
          refreshed.branch !== currentAcc.branch ||
          refreshed.fullName !== currentAcc.fullName ||
          refreshed.username !== currentAcc.username
        ) {
          persistSession(refreshed, 'USERNAME_PIN');
        }
      },
      () => {}
    );

    return () => {
      unsub();
      unsubDeleted();
    };
  }, [linkedAccount?.userCode, currentUser?.uid, persistSession]);

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

  /**
   * Primary original login flow: Username / Employee Code + Password or PIN.
   * Works independently without forcing Google Sign-In first.
   */
  const loginWithUsernamePin = async (userVal: string, pinVal: string) => {
    if (isSubmittingRef.current) return;
    isSubmittingRef.current = true;
    setIsAuthenticating(true);
    try {
      const normalizeDigits = (val: string) =>
        (val || '')
          .replace(/[\u0660-\u0669]/g, (ch) => String(ch.charCodeAt(0) - 0x0660))
          .replace(/[\u06F0-\u06F9]/g, (ch) => String(ch.charCodeAt(0) - 0x06f0))
          .replace(/[\u0000-\u001F\u007F\u200B-\u200F\u202A-\u202E\u2066-\u2069\uFEFF]/g, '')
          .trim();
      const cleanUser = normalizeDigits(userVal).slice(0, 64);
      const cleanPin = normalizeDigits(pinVal).slice(0, 128);
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

      // 1. Always enforce clean session isolation before Firebase authentication (never retain anonymous/prior UID)
      assertTrustedAuthDomain();
      if (auth.currentUser) {
        isSwitchingAccountRef.current = true;
        try {
          await fbSignOut(auth).catch(() => {});
        } finally {
          isSwitchingAccountRef.current = false;
        }
      }

      const candidateEmails = buildUsernameAuthEmailCandidates(cleanUser);
      let signedInFbUser: User | null = null;
      let lastFbErr: any = null;
      for (const candidateEmail of candidateEmails) {
        try {
          const cred = await signInWithEmailAndPassword(auth, candidateEmail, cleanPin);
          signedInFbUser = cred.user;
          break;
        } catch (err: any) {
          lastFbErr = err;
        }
      }

      const errText = `${lastFbErr?.code || ''} ${lastFbErr?.message || ''}`.toLowerCase();
      if (!signedInFbUser && (errText.includes('api-key-not-valid') || errText.includes('api key not valid'))) {
        throw new Error(
          'مفتاح ربط Firebase API غير صالح أو مقيد حالياً في Google Cloud Console. يرجى تحديث المفتاح الصالح في إعدادات Firebase.'
        );
      }
      if (!signedInFbUser && lastFbErr?.code === 'auth/unauthorized-domain') {
        const host = typeof window !== 'undefined' ? window.location.hostname : '';
        throw new Error(
          `النطاق الحالي (${host}) غير مضاف في النطاقات المصرح بها في Firebase Auth (Authorized Domains). يرجى إضافته من لوحة Firebase.`
        );
      }

      if (signedInFbUser) {
        await signedInFbUser.getIdToken(true).catch(() => {});
        await AccountService.ensureOwnClusterMemberRegistration(
          null,
          signedInFbUser.uid,
          signedInFbUser.email || candidateEmails[0]
        ).catch(() => {});
        const matchedViaFb = await AccountService.findSignedInUserAccount(signedInFbUser);
        if (matchedViaFb) {
          validateAccountAccessState(matchedViaFb);
          await AccountService.ensureOwnClusterMemberRegistration(
            matchedViaFb,
            signedInFbUser.uid,
            signedInFbUser.email || candidateEmails[0]
          ).catch(() => {});
          if (resolveUserRoleType(matchedViaFb) === 'SUPER_ADMIN') {
            void AccountService.syncExistingLinkedAccountsMembership();
          }
          void ErpDataService.flushPendingAuditLogs();
          LoginRateLimiter.recordSuccess(cleanUser);
          await ErpDataService.logAudit(
            matchedViaFb.username,
            'WEB_LOGIN_SUCCESS',
            `دخول ناجح (${matchedViaFb.role} - ${matchedViaFb.branch})`
          );
          persistSession(matchedViaFb, cleanUser.includes('@') ? 'EMAIL_PASSWORD' : 'USERNAME_PIN');
          return;
        }
        await fbSignOut(auth).catch(() => {});
        throw new Error('تم التحقق من كلمة المرور لكن هذا الحساب غير مربوط بسجل موظف معتمد في المنظومة.');
      }

      const rl = LoginRateLimiter.recordFailure(cleanUser);
      await ErpDataService.logAudit(
        cleanUser,
        rl.locked ? 'WEB_LOGIN_LOCKED_OUT' : 'WEB_LOGIN_FAILED',
        rl.locked
          ? `تجاوز الحد الأقصى للمحاولات الفاشلة وتم الحظر لمدة ${formatLockDurationAr(rl.remainingSeconds)}`
          : `فشل التحقق السحابي للمستخدم (${cleanUser}): ${lastFbErr?.code || 'invalid-credentials'}`
      ).catch(() => {});
      if (rl.locked) {
        throw new Error(
          `تم تجاوز الحد المسموح للمحاولات. تم إيقاف الدخول مؤقتاً لمدة ${formatLockDurationAr(rl.remainingSeconds)}.`
        );
      }
      throw new Error(
        `بيانات الدخول غير صحيحة أو لم يتم تفعيل كلمة مرور سحابية لهذا الحساب بعد (متبقي ${rl.attemptsRemaining} محاولات).`
      );
    } finally {
      isSubmittingRef.current = false;
      setIsAuthenticating(false);
    }
  };

  /**
   * Standard Email + Password login flow (completely independent of Google Sign-In).
   */
  const loginWithEmail = async (emailVal: string, passVal: string) => {
    if (isSubmittingRef.current) return;
    isSubmittingRef.current = true;
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

      let fbCredUser: User | null = null;
      let fbAuthError: any = null;
      const emailCandidates = buildUsernameAuthEmailCandidates(cleanEmail);

      if (emailCandidates.length === 0) {
        throw new Error('يرجى إدخال بريد إلكتروني صحيح أو استخدام تبويب اسم المستخدم والرمز السري.');
      }

      assertTrustedAuthDomain();
      if (auth.currentUser) {
        isSwitchingAccountRef.current = true;
        try {
          await fbSignOut(auth).catch(() => {});
        } finally {
          isSwitchingAccountRef.current = false;
        }
      }

      for (const candidate of emailCandidates) {
        try {
          const cred = await signInWithEmailAndPassword(auth, candidate, cleanPass);
          fbCredUser = cred.user;
          break;
        } catch (err: any) {
          fbAuthError = err;
        }
      }

      if (fbCredUser) {
        await fbCredUser.getIdToken(true).catch(() => {});
        await AccountService.ensureOwnClusterMemberRegistration(null, fbCredUser.uid, fbCredUser.email || cleanEmail).catch(() => {});
        const matched = await AccountService.findSignedInUserAccount(fbCredUser);
        if (matched) {
          validateAccountAccessState(matched);
          await AccountService.ensureOwnClusterMemberRegistration(matched, fbCredUser.uid, fbCredUser.email || cleanEmail).catch(() => {});
          if (resolveUserRoleType(matched) === 'SUPER_ADMIN') {
            void AccountService.syncExistingLinkedAccountsMembership();
          }
          void ErpDataService.flushPendingAuditLogs();
          LoginRateLimiter.recordSuccess(cleanEmail);
          await ErpDataService.logAudit(matched.username, 'WEB_LOGIN_EMAIL', `تسجيل دخول عبر البريد ${fbCredUser.email || cleanEmail}`);
          persistSession(matched, 'EMAIL_PASSWORD');
          return;
        }
        await fbSignOut(auth).catch(() => {});
        throw new Error('تم التحقق من كلمة المرور لكن هذا البريد غير مرتبط بسجل موظف معتمد في المنظومة.');
      }

      const rl = LoginRateLimiter.recordFailure(cleanEmail);
      await ErpDataService.logAudit(
        cleanEmail,
        rl.locked ? 'WEB_LOGIN_LOCKED_OUT' : 'WEB_LOGIN_FAILED',
        `فشل تسجيل الدخول بالبريد ${cleanEmail}`
      ).catch(() => {});
      if (rl.locked) {
        throw new Error(
          `تم تجاوز الحد المسموح للمحاولات. تم إيقاف الدخول مؤقتاً لمدة ${formatLockDurationAr(rl.remainingSeconds)}.`
        );
      }
      throw new Error(
        fbAuthError?.code === 'auth/wrong-password' || fbAuthError?.code === 'auth/invalid-credential'
          ? 'بيانات البريد الإلكتروني أو كلمة المرور غير صحيحة. يرجى التحقق والمحاولة مرة أخرى.'
          : fbAuthError?.code === 'auth/user-not-found'
          ? 'لا يوجد حساب مفعّل بهذا البريد الإلكتروني. يرجى التأكد من البيانات أو مراجعة مدير النظام.'
          : 'تعذر التحقق من البريد الإلكتروني وكلمة المرور. يرجى التحقق من البيانات والمحاولة مجدداً.'
      );
    } finally {
      isSubmittingRef.current = false;
      setIsAuthenticating(false);
    }
  };

  /**
   * Safe account switching for shared workstations / managers switching roles
   * without bouncing back to the previous account on reload or auth callbacks.
   */
  const switchAccount = async (identifier: string, secret: string): Promise<UserAccount> => {
    if (isSubmittingRef.current) {
      throw new Error('جاري تنفيذ عملية مصادقة حالياً، يرجى الانتظار.');
    }
    isSubmittingRef.current = true;
    setIsAuthenticating(true);
    try {
      const cleanId = (identifier || '').replace(/[\u0000-\u001F\u007F]/g, '').trim().slice(0, 120);
      const cleanSecret = (secret || '').replace(/[\u0000-\u001F\u007F]/g, '').trim().slice(0, 128);
      if (!cleanId || !cleanSecret) {
        throw new Error('يرجى إدخال اسم المستخدم أو الكود والرمز السري للتبديل.');
      }

      const lockStatus = LoginRateLimiter.checkLockStatus(cleanId);
      if (lockStatus.locked) {
        throw new Error(
          `تم تعليق محاولات الدخول مؤقتاً لهذا الحساب لمدة ${formatLockDurationAr(lockStatus.remainingSeconds)}.`
        );
      }

      assertTrustedAuthDomain();
      const previousActor = linkedAccountRef.current?.username || 'UNKNOWN';
      const candidates = buildUsernameAuthEmailCandidates(cleanId);
      if (candidates.length === 0) {
        throw new Error('يرجى إدخال البريد الإلكتروني أو اسم المستخدم الصحيح للتبديل.');
      }

      // Pre-verify target account on isolated provisioning Auth instance so a failed switch NEVER breaks current session
      const provAuth = getProvisioningAuth();
      let verifiedEmail: string | null = null;
      for (const emailCand of candidates) {
        try {
          await signInWithEmailAndPassword(provAuth, emailCand, cleanSecret);
          verifiedEmail = emailCand;
          break;
        } catch {
          // Try next candidate
        } finally {
          await fbSignOut(provAuth).catch(() => {});
        }
      }

      if (!verifiedEmail) {
        const rl = LoginRateLimiter.recordFailure(cleanId);
        throw new Error(
          rl.locked
            ? `تم قفل المحاولات مؤقتاً لمدة ${formatLockDurationAr(rl.remainingSeconds)}.`
            : `تعذر التحقق من بيانات الحساب المستهدف (متبقي ${rl.attemptsRemaining} محاولات).`
        );
      }

      // Now atomically transition primary Firebase Auth & session state without leaking User A's UID/token
      isSwitchingAccountRef.current = true;
      let targetAccount: UserAccount | null = null;
      try {
        explicitSignOutRef.current = false;
        await fbSignOut(auth).catch(() => {});
        const cred = await signInWithEmailAndPassword(auth, verifiedEmail, cleanSecret);
        await cred.user.getIdToken(true).catch(() => {});
        setCurrentUser(cred.user);
        await AccountService.ensureOwnClusterMemberRegistration(null, cred.user.uid, cred.user.email).catch(() => {});
        targetAccount = await AccountService.findSignedInUserAccount(cred.user);
        if (!targetAccount) {
          throw new Error('الحساب غير مرتبط بسجل موظف معتمد في المنظومة.');
        }
        validateAccountAccessState(targetAccount);
        await AccountService.ensureOwnClusterMemberRegistration(targetAccount, cred.user.uid, cred.user.email).catch(() => {});
        if (resolveUserRoleType(targetAccount) === 'SUPER_ADMIN') {
          void AccountService.syncExistingLinkedAccountsMembership();
        }
        void ErpDataService.flushPendingAuditLogs();
      } finally {
        isSwitchingAccountRef.current = false;
      }

      LoginRateLimiter.recordSuccess(cleanId);
      persistSession(targetAccount, 'ACCOUNT_SWITCH');
      await ErpDataService.logAudit(
        targetAccount.username,
        'WEB_ACCOUNT_SWITCH',
        `تبديل معزول للجلسة من (${previousActor}) إلى (${targetAccount.username} - ${targetAccount.role})`
      );
      return sanitizeAccountForSession(targetAccount);
    } finally {
      isSubmittingRef.current = false;
      setIsAuthenticating(false);
    }
  };

  /**
   * Optional secondary Google Sign-In flow (never required before Username/Email login).
   */
  const loginWithGoogle = async () => {
    if (isSubmittingRef.current) return;
    isSubmittingRef.current = true;
    setIsAuthenticating(true);
    try {
      assertTrustedAuthDomain();
      googleAuthProvider.setCustomParameters({ prompt: 'select_account' });
      let res;
      try {
        res = await signInWithPopup(auth, googleAuthProvider);
      } catch (popupErr: any) {
        const code = String(popupErr?.code || '');
        if (code === 'auth/popup-blocked') {
          await signInWithRedirect(auth, googleAuthProvider);
          return;
        }
        if (code === 'auth/popup-closed-by-user' || code === 'auth/cancelled-popup-request') {
          throw new Error('تم إلغاء نافذة تسجيل الدخول عبر Google. يمكنك الدخول باسم المستخدم أو البريد الإلكتروني.');
        }
        if (code === 'auth/unauthorized-domain') {
          const host = typeof window !== 'undefined' ? window.location.hostname : '';
          throw new Error(
            `النطاق الحالي (${host}) غير مضاف في النطاقات المصرح بها في Firebase Auth (Authorized Domains). يمكنك الدخول بالبريد الإلكتروني أو إضافة النطاق من لوحة Firebase.`
          );
        }
        throw popupErr;
      }

      const fbUser = res.user;
      const email = (fbUser?.email || '').trim().toLowerCase();
      if (!email) {
        throw new Error('لم يتم استلام البريد الإلكتروني من حساب Google.');
      }

      await AccountService.ensureOwnClusterMemberRegistration(null, fbUser.uid, email).catch(() => {});
      const matched = await AccountService.findSignedInUserAccount(fbUser);
      if (matched) {
        validateAccountAccessState(matched);
        if (fbUser?.uid) {
          await AccountService.ensureOwnClusterMemberRegistration(matched, fbUser.uid, email).catch(() => {});
        }
        if (resolveUserRoleType(matched) === 'SUPER_ADMIN') {
          void AccountService.syncExistingLinkedAccountsMembership();
        }
        void ErpDataService.flushPendingAuditLogs();
        LoginRateLimiter.recordSuccess(email);
        await ErpDataService.logAudit(matched.username, 'WEB_LOGIN_GOOGLE', `تسجيل دخول اختياري عبر Google (${email})`);
        persistSession(matched, 'GOOGLE_OPTIONAL');
        return;
      }

      await AccountService.submitPendingGoogleRequest(email, fbUser.displayName || email.split('@')[0]);
      await fbSignOut(auth).catch(() => {});
      throw new Error(
        'تم تسجيل طلب ربط حساب Google بنجاح. الحساب حالياً بانتظار موافقة مدير النظام وتحديد الصلاحيات.'
      );
    } finally {
      isSubmittingRef.current = false;
      setIsAuthenticating(false);
    }
  };

  const requestPasswordReset = async (emailVal: string) => {
    assertTrustedAuthDomain();
    const cleanInput = (emailVal || '').trim().toLowerCase();
    if (!cleanInput) throw new Error('يرجى إدخال البريد الإلكتروني أو اسم المستخدم أولاً.');
    const candidates = cleanInput.includes('@')
      ? [cleanInput]
      : buildUsernameAuthEmailCandidates(cleanInput);
    if (candidates.length === 0) {
      throw new Error('تعذر تحديد بريد إلكتروني مرتبط بهذا الحساب. يرجى مراجعة مدير النظام.');
    }
    let lastErr: any = null;
    for (const targetEmail of candidates) {
      try {
        await sendPasswordResetEmail(auth, targetEmail);
        return;
      } catch (err: any) {
        lastErr = err;
      }
    }
    throw lastErr || new Error('تعذر إرسال رابط استعادة كلمة المرور.');
  };

  const signOut = async () => {
    explicitSignOutRef.current = true;
    const prevUser = linkedAccountRef.current?.username;
    if (prevUser) {
      await ErpDataService.logAudit(prevUser, 'WEB_LOGOUT', `تسجيل خروج من الجهاز ${currentDeviceId}`).catch(() => {});
    }
    setDeviceSessions(deactivateDeviceSession(currentDeviceId));
    persistSession(null);
    await fbSignOut(auth).catch(() => {});
  };

  const revokeDeviceSession = async (deviceId: string) => {
    setDeviceSessions(deactivateDeviceSession(deviceId));
    if (deviceId === currentDeviceId) {
      await signOut();
    }
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
    persistSession(updated, 'USERNAME_PIN');
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
        currentDeviceId,
        deviceSessions,
        sessionExpiredNotice,
        clearSessionExpiredNotice,
        canAccessModule,
        canPerformAction,
        loginWithEmail,
        loginWithUsernamePin,
        loginWithGoogle,
        switchAccount,
        revokeDeviceSession,
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
