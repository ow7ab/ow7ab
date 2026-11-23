/**
 * PMP PAINTS Enterprise Cloud Account Service
 * Unified 1:1 with Android `CloudErpSyncRepository.kt` collections inside `/erp_clusters/{clusterCode}`:
 * - `user_accounts` (docId = safeId(userCode, "USR-${id}"))
 * - `pending_requests` (docId = safeId(email/userCode))
 * - `deleted_users` (tombstone IDs preventing resurrection)
 * - `permission_audit_logs` & `audit_logs`
 */

import {
  collection,
  doc,
  getDoc,
  getDocFromServer,
  getDocs,
  getDocsFromServer,
  setDoc,
  deleteDoc,
  onSnapshot,
  serverTimestamp,
  Unsubscribe
} from 'firebase/firestore';
import { auth, firestore } from '../config/firebaseConfig';
import {
  UserAccount,
  PermissionChangeAuditEntry
} from '../types/erp';
import {
  canConfigureEmployeePermissions,
  canModifyEmployeeTarget,
  isSameAccount,
  isSuperAdminAccount,
  isSystemManager,
  resolveCanonicalRbacTier,
  resolveUserRoleType,
  validatePermissionChange
} from './SecurityService';
import { hashPassword } from './PasswordSecurity';
import { CloudErrorLogger, sanitizeCloudMessage } from './CloudErrorLogger';
import {
  ErpDataService,
  PRIMARY_CLUSTER,
  safeId,
  deduplicateCollectionRecords
} from './ErpDataService';

export function buildEmailDirectoryKey(email: string): string {
  const clean = (email || '').trim().toLowerCase();
  const normalized = clean.replace(/[^a-zA-Z0-9]+/g, '_').replace(/^_+|_+$/g, '');
  return safeId(`EMAIL_${normalized}`, 'EMAIL_USER').slice(0, 100);
}

function mapFirestoreDocToUserAccount(docId: string, raw: Record<string, any>, fallbackIndex: number): UserAccount {
  const numericId =
    typeof raw.id === 'number'
      ? raw.id
      : typeof raw.id === 'string' && /^\d+$/.test(raw.id)
      ? Number(raw.id)
      : fallbackIndex;

  const account: UserAccount = {
    id: numericId,
    userCode: String(raw.userCode || docId),
    fullName: String(raw.fullName || raw.username || 'موظف PMP'),
    username: String(raw.username || docId),
    phoneOrEmail: String(raw.phoneOrEmail || ''),
    pinOrPassword: raw.pinOrPassword ? String(raw.pinOrPassword) : undefined,
    role: String(raw.role || 'موظف'),
    roleType: 'EMPLOYEE',
    branch: String(raw.branch || 'الخرطوم'),
    permissions: String(raw.permissions || ''),
    serverSyncToken: String(raw.serverSyncToken || ''),
    remoteServerId: raw.remoteServerId ? String(raw.remoteServerId) : undefined,
    syncStatus: (raw.syncStatus as UserAccount['syncStatus']) || 'APPROVED_BY_MANAGER',
    active: raw.active !== false,
    createdAt: raw.createdAt
      ? typeof raw.createdAt === 'number'
        ? new Date(raw.createdAt).toLocaleDateString('ar-EG')
        : raw.createdAt?.seconds
        ? new Date(raw.createdAt.seconds * 1000).toLocaleDateString('ar-EG')
        : String(raw.createdAt)
      : ''
  };

  account.roleType = resolveUserRoleType(account);
  return account;
}

export const AccountService = {
  async fetchDeletedUserTokens(cluster: string): Promise<Set<string>> {
    const deleted = new Set<string>();
    try {
      const snap = await getDocs(collection(firestore, `erp_clusters/${cluster}/deleted_users`));
      snap.forEach((d) => {
        const data = d.data() || {};
        if (data.userCode) deleted.add(String(data.userCode).trim().toLowerCase());
        if (data.username) deleted.add(String(data.username).trim().toLowerCase());
        if (data.phoneOrEmail) deleted.add(String(data.phoneOrEmail).trim().toLowerCase());
        deleted.add(d.id.replace(/^DEL-/i, '').trim().toLowerCase());
      });
    } catch {
      // Ignore tombstone read issues
    }
    return deleted;
  },

  isUserDeleted(acc: { userCode?: string; username?: string; phoneOrEmail?: string }, deletedSet: Set<string>): boolean {
    if (deletedSet.size === 0) return false;
    const code = (acc.userCode || '').trim().toLowerCase();
    const uname = (acc.username || '').trim().toLowerCase();
    const email = (acc.phoneOrEmail || '').trim().toLowerCase();
    return Boolean(
      (code && deletedSet.has(code)) ||
      (uname && deletedSet.has(uname)) ||
      (email && deletedSet.has(email))
    );
  },

  /**
   * Real-time subscription to `/erp_clusters/{activeCluster}/user_accounts` (filtered by `deleted_users`).
   */
  subscribeEmployees(
    onUpdate: (users: UserAccount[]) => void,
    onError?: (err: Error) => void
  ): Unsubscribe {
    const cluster = ErpDataService.activeCluster;
    let deletedSet = new Set<string>();

    const unsubDel = onSnapshot(
      collection(firestore, `erp_clusters/${cluster}/deleted_users`),
      (snap) => {
        deletedSet = new Set<string>();
        snap.forEach((d) => {
          const data = d.data() || {};
          if (data.userCode) deletedSet.add(String(data.userCode).trim().toLowerCase());
          if (data.username) deletedSet.add(String(data.username).trim().toLowerCase());
          if (data.phoneOrEmail) deletedSet.add(String(data.phoneOrEmail).trim().toLowerCase());
        });
      },
      () => {}
    );

    const unsubUsers = onSnapshot(
      collection(firestore, `erp_clusters/${cluster}/user_accounts`),
      (snap) => {
        const list: UserAccount[] = [];
        let idx = 1;
        snap.forEach((docSnap) => {
          const raw = docSnap.data() || {};
          const acc = mapFirestoreDocToUserAccount(docSnap.id, raw, idx++);
          if (!this.isUserDeleted(acc, deletedSet)) {
            list.push(acc);
          }
        });
        onUpdate(deduplicateCollectionRecords('user_accounts', list));
      },
      (err) => {
        console.error('user_accounts subscription error:', err);
        if (onError) onError(err);
      }
    );

    return () => {
      unsubDel();
      unsubUsers();
    };
  },

  /**
   * Real-time subscription to `/erp_clusters/{activeCluster}/pending_requests`.
   */
  subscribePendingRequests(onUpdate: (requests: UserAccount[]) => void): Unsubscribe {
    const cluster = ErpDataService.activeCluster;
    return onSnapshot(
      collection(firestore, `erp_clusters/${cluster}/pending_requests`),
      (snap) => {
        const list: UserAccount[] = [];
        let idx = 1000;
        snap.forEach((docSnap) => {
          const raw = docSnap.data() || {};
          const mapped = mapFirestoreDocToUserAccount(docSnap.id, raw, idx++);
          if (mapped.syncStatus === 'PENDING_MANAGER_APPROVAL' || !raw.syncStatus) {
            mapped.syncStatus = 'PENDING_MANAGER_APPROVAL';
            list.push(mapped);
          }
        });
        onUpdate(deduplicateCollectionRecords('user_accounts', list));
      },
      () => {}
    );
  },

  /**
   * Fetches all employees from active cluster.
   */
  async getAllEmployees(clusterOverride?: string): Promise<UserAccount[]> {
    const targetCluster = clusterOverride || PRIMARY_CLUSTER;
    const deletedSet = await this.fetchDeletedUserTokens(targetCluster);
    const snap = await getDocs(collection(firestore, `erp_clusters/${targetCluster}/user_accounts`));

    const list: UserAccount[] = [];
    let idx = 1;
    snap.forEach((docSnap) => {
      const raw = docSnap.data() || {};
      const acc = mapFirestoreDocToUserAccount(docSnap.id, raw, idx++);
      if (!this.isUserDeleted(acc, deletedSet)) {
        list.push(acc);
      }
    });

    return deduplicateCollectionRecords('user_accounts', list);
  },

  /**
   * Resolves the authenticated Firebase user's ERP employee profile strictly through zero-trust paths:
   * 1) Bootstrap/recover `cluster_members/{uid}` FIRST (owner recovery or `email_directory/{emailKey}`).
   * 2) Direct document lookups (`email_directory/{emailKey}` -> `user_accounts/{userCode}`, `cluster_members/{uid}` -> `user_accounts/{userCode}`, `linked_accounts/{uid}`).
   * 3) Collection-wide `user_accounts` scan ONLY when the signed-in member holds ADMIN or MANAGER role.
   */
  async findSignedInUserAccount(fbUser: { uid: string; email?: string | null }): Promise<UserAccount | null> {
    if (!fbUser?.uid || !auth.currentUser) return null;
    const cluster = ErpDataService.activeCluster || PRIMARY_CLUSTER;
    const cleanUid = safeId(fbUser.uid, fbUser.uid);
    const cleanEmail = (fbUser.email || '').trim().toLowerCase();

    // Step 1: Ensure cluster_members/{uid} exists BEFORE reading user_accounts
    await this.ensureOwnClusterMemberRegistration(null, fbUser.uid, cleanEmail).catch(() => {});

    // Read current member role & userCode if present
    const memberSnap = await getDocFromServer(
      doc(firestore, `erp_clusters/${cluster}/cluster_members`, cleanUid)
    )
      .catch(() => getDoc(doc(firestore, `erp_clusters/${cluster}/cluster_members`, cleanUid)))
      .catch(() => null);
    const memberData = memberSnap && memberSnap.exists() ? memberSnap.data() || {} : null;
    const memberRoleKey = String(memberData?.roleKey || '').toUpperCase();
    const memberUserCode = String(memberData?.userCode || '').trim();

    const tryDirectUserDoc = async (docId: string): Promise<UserAccount | null> => {
      const cleanDocId = safeId(docId, '');
      if (!cleanDocId) return null;
      const snap = await getDocFromServer(doc(firestore, `erp_clusters/${cluster}/user_accounts`, cleanDocId))
        .catch(() => getDoc(doc(firestore, `erp_clusters/${cluster}/user_accounts`, cleanDocId)))
        .catch(() => null);
      if (snap && snap.exists()) {
        return mapFirestoreDocToUserAccount(snap.id, snap.data() || {}, 1);
      }
      return null;
    };

    // Step 2: Direct lookup via email_directory/{emailKey} -> user_accounts/{userCode} (works for ALL roles)
    if (cleanEmail.includes('@')) {
      const emailKey = buildEmailDirectoryKey(cleanEmail);
      const dirSnap = await getDocFromServer(doc(firestore, `erp_clusters/${cluster}/email_directory`, emailKey))
        .catch(() => getDoc(doc(firestore, `erp_clusters/${cluster}/email_directory`, emailKey)))
        .catch(() => null);
      if (dirSnap && dirSnap.exists()) {
        const d = dirSnap.data() || {};
        const dirCode = String(d.userCode || '').trim();
        if (dirCode) {
          const direct = await tryDirectUserDoc(dirCode);
          if (direct) return direct;
        }
        // If user_accounts doc was not yet written separately, synthesize from authoritative email_directory record
        if (d.active !== false && dirCode) {
          const roleLabel =
            d.roleKey === 'ADMIN'
              ? 'مدير النظام'
              : d.roleKey === 'MANAGER'
              ? 'المدير'
              : d.roleKey === 'ACCOUNTANT'
              ? 'المحاسب'
              : d.roleKey === 'DEPUTY_ACCOUNTANT'
              ? 'نائب محاسب'
              : 'موظف';
          return mapFirestoreDocToUserAccount(
            dirCode,
            {
              id: 1,
              userCode: dirCode,
              fullName: String(d.fullName || d.username || cleanEmail.split('@')[0]),
              username: String(d.username || cleanEmail.split('@')[0]),
              phoneOrEmail: cleanEmail,
              role: roleLabel,
              branch: String(d.branch || 'الخرطوم'),
              permissions: String(d.permissions || 'صلاحية العرض'),
              syncStatus: 'APPROVED_BY_MANAGER',
              active: d.active !== false
            },
            1
          );
        }
      }
    }

    // Step 3: Direct lookup via cluster_members/{uid}.userCode -> user_accounts/{userCode}
    if (memberUserCode) {
      const byMemberCode = await tryDirectUserDoc(memberUserCode);
      if (byMemberCode) return byMemberCode;
    }

    // Step 4: Admin/Manager collection scan (strictly permitted by firestore.rules for ADMIN/MANAGER only)
    if (memberRoleKey === 'ADMIN' || memberRoleKey === 'MANAGER') {
      const deletedSet = await this.fetchDeletedUserTokens(cluster);
      const snap = await getDocs(collection(firestore, `erp_clusters/${cluster}/user_accounts`)).catch(() => null);
      if (snap) {
        let idx = 1;
        const allAccounts: UserAccount[] = [];
        for (const docSnap of snap.docs) {
          const acc = mapFirestoreDocToUserAccount(docSnap.id, docSnap.data() || {}, idx++);
          if (this.isUserDeleted(acc, deletedSet)) continue;
          allAccounts.push(acc);
          if (
            (cleanEmail && acc.phoneOrEmail.trim().toLowerCase() === cleanEmail) ||
            (memberUserCode && acc.userCode.trim().toLowerCase() === memberUserCode.toLowerCase()) ||
            (acc.remoteServerId && acc.remoteServerId === fbUser.uid)
          ) {
            return acc;
          }
        }
        // Primary cluster admin email fallback (USR-HQ-001 / USR-ADMIN / super admin)
        if (cleanEmail === 'oaw7ab@gmail.com' || cleanEmail === 'admin@pmp-paints.com' || memberRoleKey === 'ADMIN') {
          const primaryAdmin =
            allAccounts.find((a) => a.userCode === 'USR-HQ-001' || a.userCode === 'USR-ADMIN' || isSuperAdminAccount(a)) ||
            null;
          if (primaryAdmin) return primaryAdmin;
        }
      }
    }

    // Step 5: Fallback to top-level /linked_accounts/{uid}
    const linkedSnap = await getDoc(doc(firestore, 'linked_accounts', cleanUid)).catch(() => null);
    if (linkedSnap && linkedSnap.exists()) {
      const data = linkedSnap.data() || {};
      return mapFirestoreDocToUserAccount(
        String(data.userCode || cleanUid),
        {
          id: 1,
          userCode: data.userCode || cleanUid,
          fullName: data.fullName || cleanEmail.split('@')[0],
          username: data.username || cleanEmail.split('@')[0],
          phoneOrEmail: cleanEmail,
          role: data.role || 'موظف',
          branch: data.branch || 'الخرطوم',
          permissions: data.permissions || 'صلاحية العرض',
          serverSyncToken: data.serverSyncToken || '',
          syncStatus: 'APPROVED_BY_MANAGER',
          active: data.active !== false
        },
        1
      );
    }

    // Step 6: If active cluster_members/{uid} exists (e.g. primary owner before user_accounts seeded)
    if (memberData && memberData.active === true) {
      const code = memberUserCode || (memberRoleKey === 'ADMIN' ? 'USR-ADMIN' : `USR-${cleanUid.slice(0, 6).toUpperCase()}`);
      const roleLabel =
        memberRoleKey === 'ADMIN'
          ? 'مدير النظام'
          : memberRoleKey === 'MANAGER'
          ? 'المدير'
          : memberRoleKey === 'ACCOUNTANT'
          ? 'المحاسب'
          : memberRoleKey === 'DEPUTY_ACCOUNTANT'
          ? 'نائب محاسب'
          : 'موظف';
      return mapFirestoreDocToUserAccount(
        code,
        {
          id: 1,
          userCode: code,
          fullName: String(memberData.fullName || (memberRoleKey === 'ADMIN' ? 'مدير النظام' : cleanEmail.split('@')[0])),
          username: String(memberData.username || (memberRoleKey === 'ADMIN' ? 'admin' : cleanEmail.split('@')[0])),
          phoneOrEmail: cleanEmail,
          role: roleLabel,
          branch: String(memberData.branch || 'الخرطوم'),
          permissions: String(memberData.permissions || (memberRoleKey === 'ADMIN' ? 'الكل' : 'صلاحية العرض')),
          syncStatus: 'APPROVED_BY_MANAGER',
          active: true
        },
        1
      );
    }

    return null;
  },

  /**
   * Looks up a user account in Firestore by username, userCode, or phoneOrEmail for authentication.
   * Respects zero-trust Firestore rules: never fires an unauthenticated collection list on `user_accounts`.
   */
  async findAccountForLogin(identifier: string): Promise<UserAccount | null> {
    const clean = (identifier || '').trim().toLowerCase();
    if (!clean) return null;

    const fbUser = auth.currentUser;
    if (!fbUser) {
      throw new Error(
        'يتطلب التحقق السحابي جلسة مصادقة نشطة بالبريد الإلكتروني وكلمة المرور أو اسم المستخدم المعتمد (أو الدخول الاختياري عبر Google).'
      );
    }

    const cluster = PRIMARY_CLUSTER;
    await this.ensureOwnClusterMemberRegistration(null, fbUser.uid, fbUser.email || (clean.includes('@') ? clean : null)).catch(() => {});

    // 1. Direct lookup via email_directory if identifier is an email
    if (clean.includes('@')) {
      const emailKey = buildEmailDirectoryKey(clean);
      const dirSnap = await getDoc(doc(firestore, `erp_clusters/${cluster}/email_directory`, emailKey)).catch(() => null);
      if (dirSnap && dirSnap.exists()) {
        const d = dirSnap.data() || {};
        const dirCode = String(d.userCode || '').trim();
        if (dirCode) {
          const directSnap = await getDoc(doc(firestore, `erp_clusters/${cluster}/user_accounts`, safeId(dirCode, dirCode))).catch(() => null);
          if (directSnap && directSnap.exists()) {
            return mapFirestoreDocToUserAccount(directSnap.id, directSnap.data() || {}, 1);
          }
        }
      }
    }

    // 2. Direct lookup by userCode document ID if authorized
    const candidateDocId = safeId(identifier.trim().toUpperCase(), '');
    const directCandidate = candidateDocId
      ? await getDoc(doc(firestore, `erp_clusters/${cluster}/user_accounts`, candidateDocId)).catch(() => null)
      : null;
    if (directCandidate && directCandidate.exists()) {
      const acc = mapFirestoreDocToUserAccount(directCandidate.id, directCandidate.data() || {}, 1);
      if (
        acc.username.trim().toLowerCase() === clean ||
        acc.userCode.trim().toLowerCase() === clean ||
        acc.phoneOrEmail.trim().toLowerCase() === clean
      ) {
        return acc;
      }
    }

    // 3. Check caller's own signed-in profile
    const selfAcc = await this.findSignedInUserAccount(fbUser);
    if (
      selfAcc &&
      (selfAcc.username.trim().toLowerCase() === clean ||
        selfAcc.userCode.trim().toLowerCase() === clean ||
        selfAcc.phoneOrEmail.trim().toLowerCase() === clean)
    ) {
      return selfAcc;
    }

    // 4. Collection list ONLY when signed-in member is ADMIN or MANAGER
    const memberSnap = await getDoc(
      doc(firestore, `erp_clusters/${cluster}/cluster_members`, safeId(fbUser.uid, fbUser.uid))
    ).catch(() => null);
    const roleKey = String(memberSnap?.data()?.roleKey || '').toUpperCase();
    if (roleKey !== 'ADMIN' && roleKey !== 'MANAGER') {
      return null;
    }

    const deletedSet = await this.fetchDeletedUserTokens(cluster);
    const snap = await getDocs(collection(firestore, `erp_clusters/${cluster}/user_accounts`));
    let idx = 1;
    for (const docSnap of snap.docs) {
      const raw = docSnap.data() || {};
      const acc = mapFirestoreDocToUserAccount(docSnap.id, raw, idx++);
      if (this.isUserDeleted(acc, deletedSet)) continue;
      if (
        acc.username.trim().toLowerCase() === clean ||
        acc.userCode.trim().toLowerCase() === clean ||
        acc.phoneOrEmail.trim().toLowerCase() === clean
      ) {
        return acc;
      }
    }

    return null;
  },

  /**
   * Submits a Google Workspace SSO registration request to `pending_requests` & `user_accounts`
   * matching Android `CloudErpSyncRepository.submitPendingUserRequestToCloud`.
   */
  async submitPendingGoogleRequest(email: string, displayName: string): Promise<UserAccount> {
    const cleanEmail = email.trim().toLowerCase();
    const docId = safeId(cleanEmail, `PEND-${Date.now()}`);
    const userCode = `REQ-${Date.now().toString().slice(-6)}`;
    const usernamePart = cleanEmail.split('@')[0] || userCode.toLowerCase();

    const payload = {
      id: Date.now(),
      userCode,
      fullName: displayName || usernamePart,
      username: usernamePart,
      phoneOrEmail: cleanEmail,
      pinOrPassword: '',
      role: 'موظف',
      branch: 'الخرطوم',
      permissions: 'صلاحية العرض',
      serverSyncToken: `REQ_${Date.now()}`,
      remoteServerId: `CLOUD-${userCode}`,
      syncStatus: 'PENDING_MANAGER_APPROVAL',
      serverEndpoint: ErpDataService.activeCluster,
      active: false,
      createdAt: Date.now(),
      serverUpdatedAt: serverTimestamp()
    };

    const cluster = ErpDataService.activeCluster;
    try {
      await setDoc(doc(firestore, `erp_clusters/${cluster}/pending_requests`, docId), payload, {
        merge: true
      });
    } catch (err) {
      CloudErrorLogger.recordError('SUBMIT_PENDING_REQUEST', `erp_clusters/${cluster}/pending_requests/${docId}`, err);
      throw err;
    }

    return mapFirestoreDocToUserAccount(docId, payload, Number(payload.id));
  },

  /**
   * Synchronizes `email_directory` and (when Firebase UID is known) `cluster_members/{uid}`
   * so Firestore Security Rules resolve the user's authoritative role cleanly.
   */
  async syncRbacDirectoryForAccount(account: UserAccount, linkedUid?: string | null): Promise<void> {
    const cluster = ErpDataService.activeCluster;
    const roleKey = resolveCanonicalRbacTier(account);
    const email = (account.phoneOrEmail || '').trim().toLowerCase();
    const emailKey = email.includes('@') ? buildEmailDirectoryKey(email) : '';

    if (emailKey && auth.currentUser) {
      const dirPath = `erp_clusters/${cluster}/email_directory/${emailKey}`;
      try {
        await setDoc(
          doc(firestore, `erp_clusters/${cluster}/email_directory`, emailKey),
          {
            email,
            userCode: account.userCode,
            username: account.username,
            roleKey,
            branch: account.branch || 'الخرطوم',
            permissions: account.permissions || 'صلاحية العرض',
            active: account.active !== false,
            updatedAt: Date.now(),
            serverUpdatedAt: serverTimestamp()
          },
          { merge: true }
        );
      } catch (err) {
        CloudErrorLogger.recordError('SYNC_EMAIL_DIRECTORY', dirPath, err);
      }
    }

    const uid = (linkedUid || '').trim();
    if (uid && auth.currentUser) {
      const cleanUid = safeId(uid, uid);
      const memberPath = `erp_clusters/${cluster}/cluster_members/${cleanUid}`;
      try {
        await setDoc(
          doc(firestore, `erp_clusters/${cluster}/cluster_members`, cleanUid),
          {
            uid: cleanUid,
            userCode: account.userCode,
            username: account.username,
            fullName: account.fullName,
            roleKey,
            branch: account.branch || 'الخرطوم',
            permissions: account.permissions || 'صلاحية العرض',
            active: account.active !== false,
            ...(emailKey ? { emailKey } : {}),
            updatedAt: Date.now(),
            serverUpdatedAt: serverTimestamp()
          },
          { merge: true }
        );
      } catch (err) {
        CloudErrorLogger.recordError('SYNC_CLUSTER_MEMBER', memberPath, err);
      }
    }
  },

  /**
   * Ensures the signed-in Firebase Auth user has a matching `cluster_members/{uid}` document
   * (either via first-owner bootstrap or pre-provisioned `email_directory/{emailKey}`).
   */
  async ensureOwnClusterMemberRegistration(
    account: UserAccount | null,
    fbUid: string,
    fbEmail?: string | null
  ): Promise<void> {
    if (!fbUid || !auth.currentUser) return;
    const cluster = ErpDataService.activeCluster;
    const cleanUid = safeId(fbUid, fbUid);
    const memberRef = doc(firestore, `erp_clusters/${cluster}/cluster_members`, cleanUid);

    try {
      const existing = await getDocFromServer(memberRef).catch(() => getDoc(memberRef));
      if (existing.exists()) return;

      const ownerRef = doc(firestore, `erp_clusters/${cluster}/system_metadata`, 'cluster_owner');
      const ownerSnap = await getDocFromServer(ownerRef).catch(() => null);
      const ownerExists = Boolean(ownerSnap && ownerSnap.exists());
      const existingOwnerUid = ownerExists ? String(ownerSnap?.data()?.ownerUid || '') : '';
      const email = (fbEmail || account?.phoneOrEmail || '').trim().toLowerCase();
      const canBootstrapAdmin =
        (account != null && (isSuperAdminAccount(account) || isSystemManager(account))) ||
        email === 'oaw7ab@gmail.com' ||
        email === 'admin@pmp-paints.com';

      if (ownerExists && existingOwnerUid === cleanUid) {
        await setDoc(memberRef, {
          uid: cleanUid,
          userCode: account?.userCode || 'USR-ADMIN',
          roleKey: 'ADMIN',
          branch: 'ALL',
          permissions: account?.permissions || '',
          active: true,
          updatedAt: Date.now()
        });
        return;
      }

      if (!ownerExists && canBootstrapAdmin) {
        // CRITICAL: Write cluster_members/{uid} BEFORE system_metadata/cluster_owner
        // because cluster_members creation verifies !exists(cluster_owner) or ownerUid == uid.
        await setDoc(memberRef, {
          uid: cleanUid,
          userCode: account?.userCode || 'USR-ADMIN',
          roleKey: 'ADMIN',
          branch: 'ALL',
          permissions: account?.permissions || '',
          active: true,
          updatedAt: Date.now()
        });
        await setDoc(ownerRef, {
          ownerUid: cleanUid,
          userCode: account?.userCode || 'USR-ADMIN',
          createdAt: Date.now()
        });
        return;
      }

      if (email.includes('@')) {
        const emailKey = buildEmailDirectoryKey(email);
        const dirSnap = await getDoc(doc(firestore, `erp_clusters/${cluster}/email_directory`, emailKey)).catch(() => null);
        if (dirSnap && dirSnap.exists()) {
          const d = dirSnap.data() || {};
          await setDoc(memberRef, {
            uid: cleanUid,
            emailKey,
            userCode: d.userCode || account?.userCode || `USR-${cleanUid.slice(0, 6).toUpperCase()}`,
            roleKey: d.roleKey || (account ? resolveCanonicalRbacTier(account) : 'EMPLOYEE'),
            branch: d.branch || account?.branch || 'الخرطوم',
            permissions: d.permissions ?? account?.permissions ?? 'صلاحية العرض',
            active: d.active !== false,
            updatedAt: Date.now()
          });
          return;
        }
      }
    } catch (err) {
      CloudErrorLogger.recordError(
        'ENSURE_CLUSTER_MEMBER',
        `erp_clusters/${cluster}/cluster_members/${cleanUid}`,
        err
      );
    }
  },

  /**
   * Approves a pending request and activates the account across Firestore.
   */
  async approvePendingRequest(
    requestAccount: UserAccount,
    actorAccount: UserAccount,
    assignedRole: string,
    assignedPermissions: string,
    assignedBranch: string
  ): Promise<UserAccount> {
    const check = validatePermissionChange(actorAccount, requestAccount, assignedRole, assignedPermissions);
    if (!check.allowed) {
      throw new Error(check.reasonAr);
    }

    const cluster = ErpDataService.activeCluster;
    const approvedCode = requestAccount.userCode.startsWith('REQ-')
      ? `EMP-${Date.now().toString().slice(-5)}`
      : requestAccount.userCode;
    const userDocId = safeId(approvedCode, `USR-${requestAccount.id}`);

    const updated: UserAccount = {
      ...requestAccount,
      userCode: approvedCode,
      role: assignedRole || requestAccount.role,
      branch: assignedBranch || requestAccount.branch,
      permissions: assignedPermissions || 'صلاحية العرض',
      syncStatus: 'APPROVED_BY_MANAGER',
      active: true
    };
    updated.roleType = resolveUserRoleType(updated);

    const approvePayload: Record<string, any> = {
      id: updated.id,
      userCode: updated.userCode,
      fullName: updated.fullName,
      username: updated.username,
      phoneOrEmail: updated.phoneOrEmail,
      role: updated.role,
      branch: updated.branch,
      permissions: updated.permissions,
      serverSyncToken: updated.serverSyncToken || `SYNC_${Date.now()}`,
      remoteServerId: updated.remoteServerId || `CLOUD-${updated.userCode}`,
      syncStatus: 'APPROVED_BY_MANAGER',
      serverEndpoint: cluster,
      active: true,
      serverUpdatedAt: serverTimestamp()
    };
    if (updated.pinOrPassword && updated.pinOrPassword.trim().length > 0) {
      approvePayload.pinOrPassword = updated.pinOrPassword.trim();
    }

    await setDoc(
      doc(firestore, `erp_clusters/${cluster}/user_accounts`, userDocId),
      approvePayload,
      { merge: true }
    );

    // Clean pending_requests entry
    if (requestAccount.phoneOrEmail) {
      await deleteDoc(
        doc(firestore, `erp_clusters/${cluster}/pending_requests`, safeId(requestAccount.phoneOrEmail, userDocId))
      ).catch(() => {});
    }
    if (requestAccount.userCode !== approvedCode) {
      await deleteDoc(
        doc(firestore, `erp_clusters/${cluster}/user_accounts`, safeId(requestAccount.userCode, userDocId))
      ).catch(() => {});
    }

    await this.syncRbacDirectoryForAccount(updated, (requestAccount as any).linkedUid);

    await this.logAuditEntry({
      entryId: `RBAC-LOG-${Date.now()}`,
      targetUserCode: updated.userCode,
      targetUsername: updated.username,
      targetFullName: updated.fullName,
      actorUsername: actorAccount.username,
      actorRole: actorAccount.role,
      previousRole: 'PENDING',
      newRole: updated.role,
      addedPermissions: updated.permissions.split(',').filter(Boolean),
      removedPermissions: [],
      reason: 'اعتماد حساب موظف معلق وتفعيل الصلاحيات',
      changedAtMillis: Date.now(),
      formattedDate: new Date().toLocaleString('ar-EG')
    });

    return updated;
  },

  /**
   * Self-profile update adhering strictly to the 8 allowed self-update keys in `firestore.rules`.
   */
  async updateOwnProfile(
    currentAccount: UserAccount,
    changes: { fullName?: string; phoneOrEmail?: string; lastLoginAt?: number; rawNewPassword?: string }
  ): Promise<void> {
    const cluster = ErpDataService.activeCluster;
    const docId = safeId(currentAccount.userCode, `USR-${currentAccount.id}`);
    const payload: Record<string, any> = {
      updatedAt: Date.now(),
      serverUpdatedAt: serverTimestamp()
    };
    if (typeof changes.fullName === 'string' && changes.fullName.trim()) {
      payload.fullName = changes.fullName.trim().slice(0, 120);
    }
    if (typeof changes.phoneOrEmail === 'string') {
      payload.phoneOrEmail = changes.phoneOrEmail.trim().slice(0, 120);
    }
    if (typeof changes.lastLoginAt === 'number') {
      payload.lastLoginAt = changes.lastLoginAt;
    }
    if (changes.rawNewPassword && changes.rawNewPassword.trim().length > 0) {
      payload.pinOrPassword = await hashPassword(changes.rawNewPassword.trim());
    }

    const path = `erp_clusters/${cluster}/user_accounts/${docId}`;
    try {
      await setDoc(doc(firestore, `erp_clusters/${cluster}/user_accounts`, docId), payload, {
        merge: true
      });
    } catch (err) {
      CloudErrorLogger.recordError('UPDATE_OWN_PROFILE', path, err);
      throw err;
    }
  },

  /**
   * Updates an employee account directly in `/erp_clusters/{activeCluster}/user_accounts`
   * matching Android schema and enforcing RBAC anti-escalation rules.
   */
  async updateEmployee(
    updatedAccount: UserAccount,
    actorAccount: UserAccount,
    reason: string = 'تحديث الصلاحيات وبيانات الحساب',
    rawNewPassword?: string
  ): Promise<boolean> {
    const check = validatePermissionChange(
      actorAccount,
      updatedAccount,
      updatedAccount.role,
      updatedAccount.permissions
    );
    if (!check.allowed) {
      throw new Error(check.reasonAr);
    }

    const cluster = ErpDataService.activeCluster;
    const docId = safeId(updatedAccount.userCode, `USR-${updatedAccount.id}`);

    let passwordHash = updatedAccount.pinOrPassword;
    if (rawNewPassword && rawNewPassword.trim().length > 0) {
      passwordHash = await hashPassword(rawNewPassword.trim());
    }

    const payload: Record<string, any> = {
      id: updatedAccount.id,
      userCode: updatedAccount.userCode,
      fullName: updatedAccount.fullName,
      username: updatedAccount.username,
      phoneOrEmail: updatedAccount.phoneOrEmail || '',
      role: updatedAccount.role,
      branch: updatedAccount.branch,
      permissions: updatedAccount.permissions,
      serverSyncToken: updatedAccount.serverSyncToken || `SYNC_${Date.now()}`,
      remoteServerId: updatedAccount.remoteServerId || `CLOUD-${updatedAccount.userCode}`,
      syncStatus: updatedAccount.syncStatus || 'APPROVED_BY_MANAGER',
      serverEndpoint: cluster,
      active: updatedAccount.active,
      serverUpdatedAt: serverTimestamp()
    };

    if (passwordHash && passwordHash.trim().length > 0) {
      payload.pinOrPassword = passwordHash.trim();
    }

    const userPath = `erp_clusters/${cluster}/user_accounts/${docId}`;
    try {
      await setDoc(doc(firestore, `erp_clusters/${cluster}/user_accounts`, docId), payload, {
        merge: true
      });
    } catch (err) {
      CloudErrorLogger.recordError('UPDATE_EMPLOYEE', userPath, err);
      throw err;
    }

    await this.syncRbacDirectoryForAccount(updatedAccount, (updatedAccount as any).linkedUid);

    // Sync /linked_accounts only if Firebase Auth user is signed in and matches uid (per firestore.rules)
    const fbUser = auth.currentUser;
    if (fbUser && fbUser.uid === docId) {
      setDoc(
        doc(firestore, 'linked_accounts', fbUser.uid),
        {
          userCode: updatedAccount.userCode,
          ownerId: fbUser.uid,
          linkedUid: fbUser.uid,
          fullName: updatedAccount.fullName,
          username: updatedAccount.username,
          role: updatedAccount.role,
          branch: updatedAccount.branch,
          permissions: updatedAccount.permissions,
          active: updatedAccount.active,
          updatedAt: serverTimestamp()
        },
        { merge: true }
      ).catch(() => {});
    }

    await this.logAuditEntry({
      entryId: `RBAC-LOG-${Date.now()}`,
      targetUserCode: updatedAccount.userCode,
      targetUsername: updatedAccount.username,
      targetFullName: updatedAccount.fullName,
      actorUsername: actorAccount.username,
      actorRole: actorAccount.role,
      previousRole: '',
      newRole: updatedAccount.role,
      addedPermissions: [],
      removedPermissions: [],
      reason,
      changedAtMillis: Date.now(),
      formattedDate: new Date().toLocaleString('ar-EG')
    });

    await ErpDataService.logAudit(
      actorAccount.username,
      'UPDATE_USER_RBAC',
      `${reason} — المستهدف: ${updatedAccount.fullName} (@${updatedAccount.username})`
    );

    return true;
  },

  /**
   * Creates a new employee account in `/erp_clusters/{activeCluster}/user_accounts`
   * with PBKDF2-HMAC-SHA256 hashed password/PIN compatible with Android PasswordSecurityUtil.
   */
  async createEmployee(
    newAccount: Partial<UserAccount> & { rawPassword?: string },
    actorAccount: UserAccount
  ): Promise<UserAccount> {
    const check = validatePermissionChange(
      actorAccount,
      null,
      newAccount.role || 'موظف',
      newAccount.permissions || 'صلاحية العرض'
    );
    if (!check.allowed) {
      throw new Error(check.reasonAr);
    }

    const cluster = ErpDataService.activeCluster;
    const id = Date.now();
    const userCode = (newAccount.userCode || `EMP-${String(id).slice(-4)}`).trim();
    const docId = safeId(userCode, `USR-${id}`);
    const rawPass = (newAccount.rawPassword || newAccount.pinOrPassword || '').trim();
    if (!rawPass || rawPass.length < 4) {
      throw new Error('كلمة المرور أو رمز PIN يجب أن لا يقل عن 4 خانات');
    }

    const hashedSecret = await hashPassword(rawPass);

    const fullAccount: UserAccount = {
      id,
      userCode,
      fullName: (newAccount.fullName || '').trim(),
      username: (newAccount.username || userCode.toLowerCase()).trim(),
      phoneOrEmail: (newAccount.phoneOrEmail || '').trim(),
      pinOrPassword: hashedSecret,
      role: newAccount.role || 'موظف',
      roleType: 'EMPLOYEE',
      branch: newAccount.branch || 'الخرطوم',
      permissions: newAccount.permissions || 'صلاحية العرض',
      serverSyncToken: `SYNC_${id}`,
      remoteServerId: `CLOUD-${userCode}`,
      syncStatus: 'APPROVED_BY_MANAGER',
      active: newAccount.active !== false,
      createdAt: new Date().toLocaleDateString('ar-EG')
    };
    fullAccount.roleType = resolveUserRoleType(fullAccount);

    const payload = {
      id: fullAccount.id,
      userCode: fullAccount.userCode,
      fullName: fullAccount.fullName,
      username: fullAccount.username,
      phoneOrEmail: fullAccount.phoneOrEmail,
      pinOrPassword: hashedSecret,
      role: fullAccount.role,
      branch: fullAccount.branch,
      permissions: fullAccount.permissions,
      serverSyncToken: fullAccount.serverSyncToken,
      remoteServerId: fullAccount.remoteServerId,
      syncStatus: 'APPROVED_BY_MANAGER',
      serverEndpoint: cluster,
      active: fullAccount.active,
      createdAt: id,
      serverUpdatedAt: serverTimestamp()
    };

    try {
      await setDoc(doc(firestore, `erp_clusters/${cluster}/user_accounts`, docId), payload, {
        merge: true
      });
    } catch (err) {
      CloudErrorLogger.recordError('CREATE_EMPLOYEE', `erp_clusters/${cluster}/user_accounts/${docId}`, err);
      throw err;
    }

    await this.syncRbacDirectoryForAccount(fullAccount);

    // Clear any prior deleted_users tombstone if re-creating
    await deleteDoc(doc(firestore, `erp_clusters/${cluster}/deleted_users`, safeId(`DEL-${userCode}`, `DEL-${id}`))).catch(
      () => {}
    );

    await this.logAuditEntry({
      entryId: `RBAC-LOG-${Date.now()}`,
      targetUserCode: fullAccount.userCode,
      targetUsername: fullAccount.username,
      targetFullName: fullAccount.fullName,
      actorUsername: actorAccount.username,
      actorRole: actorAccount.role,
      previousRole: 'NONE',
      newRole: fullAccount.role,
      addedPermissions: fullAccount.permissions.split(',').filter(Boolean),
      removedPermissions: [],
      reason: 'إنشاء حساب موظف جديد واعتماد الصلاحيات',
      changedAtMillis: Date.now(),
      formattedDate: new Date().toLocaleString('ar-EG')
    });

    await ErpDataService.logAudit(
      actorAccount.username,
      'CREATE_USER',
      `إنشاء حساب @${fullAccount.username} (${fullAccount.userCode}) بدور ${fullAccount.role}`
    );

    return fullAccount;
  },

  /**
   * Deletes/archives a user account and writes a `deleted_users` tombstone matching Android.
   */
  async deleteEmployee(targetAccount: UserAccount, actorAccount: UserAccount): Promise<boolean> {
    if (isSuperAdminAccount(targetAccount)) {
      throw new Error('لا يمكن حذف الحساب الرئيسي لمدير النظام admin');
    }
    if (!canConfigureEmployeePermissions(actorAccount) || isSameAccount(actorAccount, targetAccount)) {
      throw new Error('لا يمكن للمستخدم حذف حسابه أو تنفيذ الحذف بدون صلاحية إدارة المستخدمين');
    }
    if (!canModifyEmployeeTarget(actorAccount, targetAccount)) {
      throw new Error('لا يمكن لغير مدير النظام الرئيسي حذف حساب مدير أو مشرف');
    }

    const cluster = ErpDataService.activeCluster;
    const docId = safeId(targetAccount.userCode, `USR-${targetAccount.id}`);
    const tombId = safeId(`DEL-${targetAccount.userCode}`, `DEL-${targetAccount.id}`);

    try {
      await deleteDoc(doc(firestore, `erp_clusters/${cluster}/user_accounts`, docId));
      await setDoc(
        doc(firestore, `erp_clusters/${cluster}/deleted_users`, tombId),
        {
          userCode: targetAccount.userCode,
          username: targetAccount.username,
          phoneOrEmail: targetAccount.phoneOrEmail || '',
          deletedBy: actorAccount.username,
          deletedAt: Date.now(),
          serverUpdatedAt: serverTimestamp()
        },
        { merge: true }
      );
    } catch (err) {
      CloudErrorLogger.recordError('DELETE_EMPLOYEE', `erp_clusters/${cluster}/user_accounts/${docId}`, err);
      throw err;
    }

    const email = (targetAccount.phoneOrEmail || '').trim().toLowerCase();
    if (email.includes('@')) {
      await deleteDoc(doc(firestore, `erp_clusters/${cluster}/email_directory`, buildEmailDirectoryKey(email))).catch(
        () => {}
      );
    }

    await this.logAuditEntry({
      entryId: `RBAC-LOG-${Date.now()}`,
      targetUserCode: targetAccount.userCode,
      targetUsername: targetAccount.username,
      targetFullName: targetAccount.fullName,
      actorUsername: actorAccount.username,
      actorRole: actorAccount.role,
      previousRole: targetAccount.role,
      newRole: 'DELETED',
      addedPermissions: [],
      removedPermissions: [],
      reason: 'حذف / إزالة حساب الموظف من المنظومة السحابية',
      changedAtMillis: Date.now(),
      formattedDate: new Date().toLocaleString('ar-EG')
    });

    return true;
  },

  /**
   * Logs an RBAC audit entry into `/erp_clusters/{activeCluster}/permission_audit_logs`.
   */
  async logAuditEntry(entry: PermissionChangeAuditEntry): Promise<void> {
    try {
      const cluster = ErpDataService.activeCluster;
      const docId = safeId(entry.entryId, `AUD-${Date.now()}`);
      await setDoc(doc(firestore, `erp_clusters/${cluster}/permission_audit_logs`, docId), {
        ...entry,
        createdAt: serverTimestamp()
      });
    } catch (err) {
      console.warn('Permission audit log write note:', sanitizeCloudMessage(err));
    }
  },

  /**
   * Fetches latest permission change audit entries from Firestore.
   */
  async getAuditLogs(limitCount: number = 30): Promise<PermissionChangeAuditEntry[]> {
    const cluster = ErpDataService.activeCluster;
    const snap = await getDocsFromServer(collection(firestore, `erp_clusters/${cluster}/permission_audit_logs`));
    const logs: PermissionChangeAuditEntry[] = [];
    snap.forEach((d) => {
      logs.push(d.data() as PermissionChangeAuditEntry);
    });
    return logs
      .sort((a, b) => (b.changedAtMillis || 0) - (a.changedAtMillis || 0))
      .slice(0, limitCount);
  }
};
