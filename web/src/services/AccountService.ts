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
  getDocs,
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
import { resolveUserRoleType } from './SecurityService';
import { hashPassword } from './PasswordSecurity';
import {
  ErpDataService,
  PRIMARY_CLUSTER,
  LEGACY_V16_CLUSTER,
  safeId
} from './ErpDataService';

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
        onUpdate(list);
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
        onUpdate(list);
      },
      () => {}
    );
  },

  /**
   * Fetches all employees from active cluster (falling back to LEGACY_V16_CLUSTER only if active has 0 records).
   */
  async getAllEmployees(clusterOverride?: string): Promise<UserAccount[]> {
    const targetCluster = clusterOverride || ErpDataService.activeCluster;
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

    if (list.length === 0 && !clusterOverride) {
      const fallbackCluster = targetCluster === PRIMARY_CLUSTER ? LEGACY_V16_CLUSTER : PRIMARY_CLUSTER;
      return this.getAllEmployees(fallbackCluster);
    }

    return list;
  },

  /**
   * Looks up a user account in Firestore by username, userCode, or phoneOrEmail for authentication.
   */
  async findAccountForLogin(identifier: string): Promise<UserAccount | null> {
    const clean = (identifier || '').trim().toLowerCase();
    if (!clean) return null;

    const clustersToSearch =
      ErpDataService.activeCluster === PRIMARY_CLUSTER
        ? [PRIMARY_CLUSTER, LEGACY_V16_CLUSTER]
        : [ErpDataService.activeCluster, PRIMARY_CLUSTER];

    for (const cluster of clustersToSearch) {
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
    await setDoc(doc(firestore, `erp_clusters/${cluster}/pending_requests`, docId), payload, {
      merge: true
    });
    await setDoc(doc(firestore, `erp_clusters/${cluster}/user_accounts`, safeId(userCode, docId)), payload, {
      merge: true
    });

    return mapFirestoreDocToUserAccount(docId, payload, Number(payload.id));
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

    await setDoc(
      doc(firestore, `erp_clusters/${cluster}/user_accounts`, userDocId),
      {
        id: updated.id,
        userCode: updated.userCode,
        fullName: updated.fullName,
        username: updated.username,
        phoneOrEmail: updated.phoneOrEmail,
        pinOrPassword: updated.pinOrPassword || '',
        role: updated.role,
        branch: updated.branch,
        permissions: updated.permissions,
        serverSyncToken: updated.serverSyncToken || `SYNC_${Date.now()}`,
        remoteServerId: updated.remoteServerId || `CLOUD-${updated.userCode}`,
        syncStatus: 'APPROVED_BY_MANAGER',
        serverEndpoint: cluster,
        active: true,
        serverUpdatedAt: serverTimestamp()
      },
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
   * Updates an employee account directly in `/erp_clusters/{activeCluster}/user_accounts`
   * matching Android schema.
   */
  async updateEmployee(
    updatedAccount: UserAccount,
    actorAccount: UserAccount,
    reason: string = 'تحديث الصلاحيات وبيانات الحساب',
    rawNewPassword?: string
  ): Promise<boolean> {
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

    if (passwordHash !== undefined) {
      payload.pinOrPassword = passwordHash;
    }

    await setDoc(doc(firestore, `erp_clusters/${cluster}/user_accounts`, docId), payload, {
      merge: true
    });

    // Mirror to secondary cluster silently
    const otherCluster = cluster === PRIMARY_CLUSTER ? LEGACY_V16_CLUSTER : PRIMARY_CLUSTER;
    setDoc(doc(firestore, `erp_clusters/${otherCluster}/user_accounts`, docId), payload, {
      merge: true
    }).catch(() => {});

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

    await setDoc(doc(firestore, `erp_clusters/${cluster}/user_accounts`, docId), payload, {
      merge: true
    });

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
    if (targetAccount.username.toLowerCase() === 'admin') {
      throw new Error('لا يمكن حذف الحساب الرئيسي لمدير النظام admin');
    }
    const cluster = ErpDataService.activeCluster;
    const docId = safeId(targetAccount.userCode, `USR-${targetAccount.id}`);
    const tombId = safeId(`DEL-${targetAccount.userCode}`, `DEL-${targetAccount.id}`);

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
      console.warn('Permission audit log write note:', err);
    }
  },

  /**
   * Fetches latest permission change audit entries from Firestore.
   */
  async getAuditLogs(limitCount: number = 30): Promise<PermissionChangeAuditEntry[]> {
    const cluster = ErpDataService.activeCluster;
    const snap = await getDocs(collection(firestore, `erp_clusters/${cluster}/permission_audit_logs`));
    const logs: PermissionChangeAuditEntry[] = [];
    snap.forEach((d) => {
      logs.push(d.data() as PermissionChangeAuditEntry);
    });
    return logs
      .sort((a, b) => (b.changedAtMillis || 0) - (a.changedAtMillis || 0))
      .slice(0, limitCount);
  }
};
