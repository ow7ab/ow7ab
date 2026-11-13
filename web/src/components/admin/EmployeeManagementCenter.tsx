import React, { useState, useEffect } from 'react';
import {
  Users,
  Search,
  UserPlus,
  Shield,
  CheckCircle2,
  XCircle,
  Edit3,
  Lock,
  Unlock,
  Clock,
  Building,
  KeyRound,
  FileText,
  Trash2,
  UserCheck,
  AlertTriangle
} from 'lucide-react';
import { GlassCard } from '../ui/GlassCard';
import { GlassBadge } from '../ui/GlassBadge';
import { GlassButton } from '../ui/GlassButton';
import { Modal } from '../ui/Modal';
import { UserAccount, UserRoleType, PermissionChangeAuditEntry } from '../../types/erp';
import {
  GRANULAR_ACTIONS,
  ERP_MODULES,
  SCOPED_ADMIN_MARKER,
  canConfigureEmployeePermissions,
  isSuperAdminAccount,
  isScopedAdministrator
} from '../../services/SecurityService';
import { evaluatePasswordStrength } from '../../services/PasswordSecurity';
import { AccountService } from '../../services/AccountService';
import { useAuth } from '../../context/AuthContext';
import { useLanguage } from '../../context/LanguageContext';

type UsersSubTab = 'DIRECTORY' | 'CREATE' | 'PENDING';

const JOB_ROLES = [
  'مدير النظام',
  'المدير',
  'مدير فرع ومصنع مصر',
  'مسؤول نظام مخصص',
  'المحاسب',
  'محاسب خزنة ومبيعات',
  'مسؤول مبيعات وفواتير',
  'مسؤول المشتريات',
  'أمين مخزن الخام',
  'أمين مخزن المنتج التام',
  'أمين مخزن وترحيل',
  'مهندس الإنتاج',
  'مسؤول الجودة',
  'مراجع مالي وتقارير'
];

const BRANCH_OPTIONS = [
  'SD-001 (الخرطوم - الإدارة العامة)',
  'EG-001 (مصنع مصر والتصدير)',
  'HF-001 (مستودع حلفا اللوجستي)',
  'AT-002 (مستودع وتوزيع عطبرة)'
];

export const EmployeeManagementCenter: React.FC = () => {
  const { language } = useLanguage();
  const { userProfile, isSuperAdmin } = useAuth();

  const canManageUsers = isSuperAdmin || canConfigureEmployeePermissions(userProfile);

  const [activeSubTab, setActiveSubTab] = useState<UsersSubTab>('DIRECTORY');
  const [employees, setEmployees] = useState<UserAccount[]>([]);
  const [pendingRequests, setPendingRequests] = useState<UserAccount[]>([]);
  const [loading, setLoading] = useState(true);
  const [feedback, setFeedback] = useState<{ type: 'success' | 'error'; text: string } | null>(null);

  const [searchTerm, setSearchTerm] = useState('');
  const [branchFilter, setBranchFilter] = useState('ALL');
  const [roleFilter, setRoleFilter] = useState('ALL');
  const [statusFilter, setStatusFilter] = useState('ALL');

  // Edit / Password Reset / Temp Grant Modals
  const [selectedEmp, setSelectedEmp] = useState<UserAccount | null>(null);
  const [isEditModalOpen, setIsEditModalOpen] = useState(false);
  const [resetPasswordInput, setResetPasswordInput] = useState('');
  const [editReason, setEditReason] = useState('تحديث الصلاحيات والدور الوظيفي');

  const [auditLogsModalOpen, setAuditLogsModalOpen] = useState(false);
  const [auditLogs, setAuditLogs] = useState<PermissionChangeAuditEntry[]>([]);
  const [auditLoading, setAuditLoading] = useState(false);

  const [tempGrantModalOpen, setTempGrantModalOpen] = useState(false);
  const [tempAction, setTempAction] = useState<string>(GRANULAR_ACTIONS.APPROVE);
  const [tempDurationHours, setTempDurationHours] = useState<number>(24);
  const [tempReason, setTempReason] = useState<string>('تفويض مؤقت لمهمة تشغيلية');

  // Create Employee Form State
  const [newEmpData, setNewEmpData] = useState<{
    fullName: string;
    username: string;
    phoneOrEmail: string;
    rawPassword: string;
    role: string;
    branch: string;
    permissions: string[];
    isScopedAdmin: boolean;
  }>({
    fullName: '',
    username: '',
    phoneOrEmail: '',
    rawPassword: '',
    role: 'مسؤول مبيعات وفواتير',
    branch: BRANCH_OPTIONS[0],
    permissions: [GRANULAR_ACTIONS.VIEW, GRANULAR_ACTIONS.CREATE, 'المبيعات'],
    isScopedAdmin: false
  });
  const [isSubmitting, setIsSubmitting] = useState(false);

  // Approve Pending Request Modal
  const [approvingReq, setApprovingReq] = useState<UserAccount | null>(null);
  const [approveRole, setApproveRole] = useState('مسؤول مبيعات وفواتير');
  const [approveBranch, setApproveBranch] = useState(BRANCH_OPTIONS[0]);
  const [approvePerms, setApprovePerms] = useState<string[]>([GRANULAR_ACTIONS.VIEW, 'المبيعات']);

  useEffect(() => {
    setLoading(true);
    const unsubEmployees = AccountService.subscribeEmployees(
      (list) => {
        setEmployees(list);
        setLoading(false);
      },
      () => setLoading(false)
    );
    const unsubPending = AccountService.subscribePendingRequests((reqs) => {
      setPendingRequests(reqs);
    });
    return () => {
      unsubEmployees();
      unsubPending();
    };
  }, []);

  const showMessage = (type: 'success' | 'error', text: string) => {
    setFeedback({ type, text });
    setTimeout(() => setFeedback(null), 4500);
  };

  const filteredEmployees = employees.filter((emp) => {
    const q = searchTerm.trim().toLowerCase();
    const matchesSearch =
      !q ||
      emp.fullName.toLowerCase().includes(q) ||
      emp.username.toLowerCase().includes(q) ||
      emp.userCode.toLowerCase().includes(q) ||
      emp.phoneOrEmail.toLowerCase().includes(q);

    const matchesBranch = branchFilter === 'ALL' || emp.branch.includes(branchFilter);
    const matchesRole = roleFilter === 'ALL' || emp.roleType === roleFilter;
    const matchesStatus =
      statusFilter === 'ALL' ||
      (statusFilter === 'ACTIVE' && emp.active) ||
      (statusFilter === 'INACTIVE' && !emp.active);

    return matchesSearch && matchesBranch && matchesRole && matchesStatus;
  });

  const canModifyTarget = (target: UserAccount): boolean => {
    if (!canManageUsers || !userProfile) return false;
    if (isSuperAdminAccount(target) && !isSuperAdminAccount(userProfile)) return false;
    return true;
  };

  const handleToggleStatus = async (emp: UserAccount) => {
    if (!userProfile) return;
    if (isSuperAdminAccount(emp)) {
      showMessage('error', language === 'ar' ? 'لا يمكن تجميد الحساب الرئيسي لمدير النظام admin.' : 'Cannot suspend primary admin account.');
      return;
    }
    if (!canModifyTarget(emp)) {
      showMessage('error', language === 'ar' ? 'لا تملك صلاحية تعديل حالة هذا الحساب.' : 'Permission denied.');
      return;
    }

    try {
      const updated = { ...emp, active: !emp.active };
      await AccountService.updateEmployee(
        updated,
        userProfile,
        emp.active ? 'تجميد حساب الموظف' : 'إعادة تنشيط حساب الموظف'
      );
      showMessage(
        'success',
        language === 'ar'
          ? `تم ${updated.active ? 'تنشيط' : 'تجميد'} حساب ${emp.fullName} بنجاح`
          : `Updated status for ${emp.fullName}`
      );
    } catch (err: any) {
      showMessage('error', err?.message || 'فشل تحديث حالة الحساب');
    }
  };

  const handleDeleteEmployee = async (emp: UserAccount) => {
    if (!userProfile || !canModifyTarget(emp)) return;
    if (isSuperAdminAccount(emp)) {
      showMessage('error', 'لا يمكن حذف الحساب الرئيسي admin');
      return;
    }
    const ok = window.confirm(
      language === 'ar'
        ? `تأكيد حذف حساب "${emp.fullName}" (@${emp.username}) نهائياً مع تسجيل قيد في المحذوفات؟`
        : `Confirm permanent deletion of ${emp.fullName} (@${emp.username})?`
    );
    if (!ok) return;

    try {
      await AccountService.deleteEmployee(emp, userProfile);
      showMessage('success', `تم حذف حساب @${emp.username} وتسجيله في deleted_users`);
    } catch (err: any) {
      showMessage('error', err?.message || 'فشل حذف الحساب');
    }
  };

  const handleSaveEdit = async () => {
    if (!selectedEmp || !userProfile) return;
    if (resetPasswordInput.trim().length > 0 && resetPasswordInput.trim().length < 4) {
      showMessage('error', 'كلمة المرور الجديدة يجب أن تكون 4 خانات على الأقل');
      return;
    }

    setIsSubmitting(true);
    try {
      await AccountService.updateEmployee(
        selectedEmp,
        userProfile,
        editReason.trim() || 'تعديل الصلاحيات والدور الوظيفي',
        resetPasswordInput.trim() || undefined
      );
      setIsEditModalOpen(false);
      setResetPasswordInput('');
      showMessage('success', `تم حفظ صلاحيات وبيانات ${selectedEmp.fullName} ومزامنتها سحابياً`);
    } catch (err: any) {
      showMessage('error', err?.message || 'فشل حفظ التعديلات');
    } finally {
      setIsSubmitting(false);
    }
  };

  const togglePermissionInSelected = (permName: string) => {
    if (!selectedEmp) return;
    const currentList = selectedEmp.permissions
      .split(',')
      .map((p) => p.trim())
      .filter(Boolean);
    const exists = currentList.includes(permName);
    const nextList = exists ? currentList.filter((p) => p !== permName) : [...currentList, permName];
    setSelectedEmp({ ...selectedEmp, permissions: nextList.join(',') });
  };

  const handleApplyTemporaryGrant = async () => {
    if (!selectedEmp || !userProfile) return;
    setIsSubmitting(true);
    try {
      const expiry = Date.now() + tempDurationHours * 3600 * 1000;
      const tokenStr = `TEMP[${tempAction}|${expiry}|${userProfile.username}|${tempReason}|TMP-${Date.now().toString().slice(-4)}]`;
      const existing = selectedEmp.permissions
        .split(',')
        .map((p) => p.trim())
        .filter(Boolean);
      const updated = { ...selectedEmp, permissions: [...existing, tokenStr].join(',') };
      await AccountService.updateEmployee(
        updated,
        userProfile,
        `منح صلاحية مؤقتة (${tempAction}) لمدة ${tempDurationHours} ساعة: ${tempReason}`
      );
      setSelectedEmp(updated);
      setTempGrantModalOpen(false);
      showMessage('success', `تم اعتماد التفويض المؤقت لحساب @${selectedEmp.username}`);
    } catch (err: any) {
      showMessage('error', err?.message || 'فشل منح الصلاحية المؤقتة');
    } finally {
      setIsSubmitting(false);
    }
  };

  const handleCreateEmployee = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!userProfile || !canManageUsers) return;

    const cleanName = newEmpData.fullName.trim();
    const cleanUser = newEmpData.username.trim();
    const cleanPass = newEmpData.rawPassword.trim();
    if (!cleanName || !cleanUser) {
      showMessage('error', 'الاسم الكامل واسم المستخدم مطلوبان');
      return;
    }
    const strength = evaluatePasswordStrength(cleanPass, false);
    if (!strength.isValid) {
      showMessage('error', strength.errorMessageAr || 'كلمة المرور غير صالحة (4 خانات فأكثر)');
      return;
    }
    if (employees.some((emp) => emp.username.trim().toLowerCase() === cleanUser.toLowerCase())) {
      showMessage('error', `اسم المستخدم @${cleanUser} مسجل مسبقاً`);
      return;
    }

    setIsSubmitting(true);
    try {
      const perms = newEmpData.isScopedAdmin
        ? Array.from(new Set([SCOPED_ADMIN_MARKER, ...newEmpData.permissions]))
        : newEmpData.permissions;

      await AccountService.createEmployee(
        {
          fullName: cleanName,
          username: cleanUser,
          phoneOrEmail: newEmpData.phoneOrEmail.trim(),
          rawPassword: cleanPass,
          role: newEmpData.role,
          branch: newEmpData.branch,
          permissions: perms.join(','),
          active: true
        },
        userProfile
      );

      setNewEmpData({
        fullName: '',
        username: '',
        phoneOrEmail: '',
        rawPassword: '',
        role: 'مسؤول مبيعات وفواتير',
        branch: BRANCH_OPTIONS[0],
        permissions: [GRANULAR_ACTIONS.VIEW, GRANULAR_ACTIONS.CREATE, 'المبيعات'],
        isScopedAdmin: false
      });
      setActiveSubTab('DIRECTORY');
      showMessage('success', `تم إنشاء حساب @${cleanUser} وتشفيره بـ PBKDF2 ومزامنته مع الأندرويد`);
    } catch (err: any) {
      showMessage('error', err?.message || 'تعذر إنشاء الحساب');
    } finally {
      setIsSubmitting(false);
    }
  };

  const handleApprovePending = async () => {
    if (!approvingReq || !userProfile || !canManageUsers) return;
    setIsSubmitting(true);
    try {
      await AccountService.approvePendingRequest(
        approvingReq,
        userProfile,
        approveRole,
        approvePerms.join(','),
        approveBranch
      );
      setApprovingReq(null);
      showMessage('success', `تم اعتماد حساب ${approvingReq.fullName} وتفعيل دخوله الفوري`);
    } catch (err: any) {
      showMessage('error', err?.message || 'تعذر اعتماد الطلب');
    } finally {
      setIsSubmitting(false);
    }
  };

  const handleOpenAuditLogs = async () => {
    setAuditLogsModalOpen(true);
    setAuditLoading(true);
    try {
      const logs = await AccountService.getAuditLogs(50);
      setAuditLogs(logs);
    } catch {
      setAuditLogs([]);
    } finally {
      setAuditLoading(false);
    }
  };

  const passwordCheck = evaluatePasswordStrength(newEmpData.rawPassword, false);

  return (
    <div className="space-y-5">
      {/* Header */}
      <div className="flex flex-col sm:flex-row justify-between items-start sm:items-center gap-4 pb-4 border-b border-theme">
        <div>
          <div className="flex items-center gap-2 flex-wrap">
            <h2 className="text-xl font-black text-theme-primary">
              {language === 'ar' ? 'إدارة المستخدمين والصلاحيات (RBAC)' : 'Users & Access Control (RBAC)'}
            </h2>
            <GlassBadge variant="cyan">
              {language === 'ar' ? `${employees.length} حساب سحابي` : `${employees.length} Accounts`}
            </GlassBadge>
            {pendingRequests.length > 0 && (
              <GlassBadge variant="gold">
                {language === 'ar' ? `${pendingRequests.length} طلب معلق` : `${pendingRequests.length} Pending`}
              </GlassBadge>
            )}
          </div>
          <p className="text-xs text-theme-secondary mt-1">
            {language === 'ar'
              ? 'متزامن لحظياً مع Android CloudErpSyncRepository • تشفير PBKDF2-HMAC-SHA256 وسجل تدقيق مركزي.'
              : 'Real-time sync with Android user_accounts, pending_requests, deleted_users & PBKDF2 hashing.'}
          </p>
        </div>

        <div className="flex items-center gap-2 flex-wrap">
          <GlassButton
            variant="secondary"
            icon={<FileText className="w-4 h-4" />}
            onClick={handleOpenAuditLogs}
            className="text-xs"
          >
            {language === 'ar' ? 'سجل تدقيق الصلاحيات' : 'RBAC Audit Log'}
          </GlassButton>
        </div>
      </div>

      {/* Sub-Tabs Matching Android Users Tab */}
      <div className="flex flex-wrap gap-2">
        <button
          type="button"
          onClick={() => setActiveSubTab('DIRECTORY')}
          className={`px-4 py-2 rounded-xl text-xs font-bold border transition-all flex items-center gap-1.5 min-h-[40px] ${
            activeSubTab === 'DIRECTORY'
              ? 'bg-pmp-primary text-white border-pmp-primary shadow-md'
              : 'bg-theme-surface border-theme text-theme-secondary hover:text-theme-primary'
          }`}
        >
          <Users className="w-3.5 h-3.5" />
          <span>{language === 'ar' ? `حسابات المستخدمين (${employees.length})` : `Directory (${employees.length})`}</span>
        </button>

        {canManageUsers && (
          <button
            type="button"
            onClick={() => setActiveSubTab('CREATE')}
            className={`px-4 py-2 rounded-xl text-xs font-bold border transition-all flex items-center gap-1.5 min-h-[40px] ${
              activeSubTab === 'CREATE'
                ? 'bg-pmp-primary text-white border-pmp-primary shadow-md'
                : 'bg-theme-surface border-theme text-theme-secondary hover:text-theme-primary'
            }`}
          >
            <UserPlus className="w-3.5 h-3.5" />
            <span>{language === 'ar' ? '+ إنشاء حساب موظف' : '+ Create Account'}</span>
          </button>
        )}

        {canManageUsers && (
          <button
            type="button"
            onClick={() => setActiveSubTab('PENDING')}
            className={`px-4 py-2 rounded-xl text-xs font-bold border transition-all flex items-center gap-1.5 min-h-[40px] ${
              activeSubTab === 'PENDING'
                ? 'bg-pmp-primary text-white border-pmp-primary shadow-md'
                : 'bg-theme-surface border-theme text-theme-secondary hover:text-theme-primary'
            }`}
          >
            <UserCheck className="w-3.5 h-3.5" />
            <span>
              {language === 'ar'
                ? `طلبات بانتظار الاعتماد (${pendingRequests.length})`
                : `Pending Requests (${pendingRequests.length})`}
            </span>
          </button>
        )}
      </div>

      {/* Feedback Banner */}
      {feedback && (
        <div
          className={`p-3 rounded-xl border text-xs font-bold flex items-center gap-2 ${
            feedback.type === 'success'
              ? 'bg-emerald-500/10 border-emerald-500/30 text-emerald-400'
              : 'bg-red-500/10 border-red-500/30 text-red-400'
          }`}
        >
          {feedback.type === 'success' ? <CheckCircle2 className="w-4 h-4 shrink-0" /> : <AlertTriangle className="w-4 h-4 shrink-0" />}
          <span>{feedback.text}</span>
        </div>
      )}

      {/* TAB 1: DIRECTORY */}
      {activeSubTab === 'DIRECTORY' && (
        <div className="space-y-4">
          <GlassCard className="p-4">
            <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-3">
              <div className="relative">
                <Search className="w-4 h-4 text-theme-muted absolute right-3 top-3" />
                <input
                  type="text"
                  placeholder={language === 'ar' ? 'بحث بالاسم، الكود، أو البريد...' : 'Search name, code, user...'}
                  value={searchTerm}
                  onChange={(e) => setSearchTerm(e.target.value)}
                  className="w-full pr-9 pl-3 py-2 rounded-lg bg-theme-surface border border-theme text-theme-primary text-xs focus:outline-none focus:border-pmp-primary"
                />
              </div>

              <select
                value={branchFilter}
                onChange={(e) => setBranchFilter(e.target.value)}
                className="w-full px-3 py-2 rounded-lg bg-theme-surface border border-theme text-theme-primary text-xs focus:outline-none focus:border-pmp-primary"
              >
                <option value="ALL">{language === 'ar' ? 'جميع الفروع والمواقع' : 'All Branches'}</option>
                <option value="SD-001">{language === 'ar' ? 'الخرطوم (SD-001)' : 'Khartoum (SD-001)'}</option>
                <option value="EG-001">{language === 'ar' ? 'مصنع مصر (EG-001)' : 'Egypt (EG-001)'}</option>
                <option value="HF-001">{language === 'ar' ? 'حلفا (HF-001)' : 'Halfa (HF-001)'}</option>
                <option value="AT-002">{language === 'ar' ? 'عطبرة (AT-002)' : 'Atbara (AT-002)'}</option>
              </select>

              <select
                value={roleFilter}
                onChange={(e) => setRoleFilter(e.target.value)}
                className="w-full px-3 py-2 rounded-lg bg-theme-surface border border-theme text-theme-primary text-xs focus:outline-none focus:border-pmp-primary"
              >
                <option value="ALL">{language === 'ar' ? 'جميع الرتب' : 'All Role Tiers'}</option>
                <option value="SUPER_ADMIN">{language === 'ar' ? 'مدير النظام' : 'Super Admin'}</option>
                <option value="MANAGER">{language === 'ar' ? 'مدير عمليات / فرع' : 'Manager'}</option>
                <option value="EMPLOYEE">{language === 'ar' ? 'موظف تشغيل' : 'Employee'}</option>
              </select>

              <select
                value={statusFilter}
                onChange={(e) => setStatusFilter(e.target.value)}
                className="w-full px-3 py-2 rounded-lg bg-theme-surface border border-theme text-theme-primary text-xs focus:outline-none focus:border-pmp-primary"
              >
                <option value="ALL">{language === 'ar' ? 'جميع الحالات' : 'All Statuses'}</option>
                <option value="ACTIVE">{language === 'ar' ? 'نشط ومصرح له' : 'Active'}</option>
                <option value="INACTIVE">{language === 'ar' ? 'مجمد / معلق' : 'Suspended'}</option>
              </select>
            </div>
          </GlassCard>

          <GlassCard className="p-0 overflow-hidden">
            {loading ? (
              <div className="p-10 text-center text-xs text-theme-muted">
                {language === 'ar' ? 'جاري تحميل سجل المستخدمين من Firestore...' : 'Loading employees from Firestore...'}
              </div>
            ) : filteredEmployees.length === 0 ? (
              <div className="p-10 text-center text-xs text-theme-muted">
                {language === 'ar' ? 'لا توجد حسابات مطابقة لمعايير البحث الحالية.' : 'No matching accounts found.'}
              </div>
            ) : (
              <div className="overflow-x-auto">
                <table className="w-full text-right border-collapse text-xs">
                  <thead>
                    <tr className="bg-theme-surface/70 border-b border-theme text-theme-muted font-bold">
                      <th className="py-3 px-4">{language === 'ar' ? 'الموظف وكود الحساب' : 'Employee & Code'}</th>
                      <th className="py-3 px-4">{language === 'ar' ? 'الدور الوظيفي' : 'Role'}</th>
                      <th className="py-3 px-4">{language === 'ar' ? 'الفرع' : 'Branch'}</th>
                      <th className="py-3 px-4">{language === 'ar' ? 'الصلاحيات' : 'Permissions'}</th>
                      <th className="py-3 px-4">{language === 'ar' ? 'الحالة' : 'Status'}</th>
                      <th className="py-3 px-4 text-center">{language === 'ar' ? 'الإجراءات' : 'Actions'}</th>
                    </tr>
                  </thead>
                  <tbody className="divide-y divide-theme">
                    {filteredEmployees.map((emp) => {
                      const permsList = emp.permissions.split(',').map((p) => p.trim()).filter(Boolean);
                      const hasTemp = permsList.some((p) => p.startsWith('TEMP['));
                      const isScoped = isScopedAdministrator(emp);
                      const isPrimaryAdmin = isSuperAdminAccount(emp);

                      return (
                        <tr key={emp.userCode} className="hover:bg-theme-surface/40 transition-colors">
                          <td className="py-3.5 px-4">
                            <div className="flex items-center gap-2.5">
                              <div className="w-8 h-8 rounded-full bg-pmp-primary/10 border border-pmp-primary/30 flex items-center justify-center font-black text-pmp-primary text-xs shrink-0">
                                {emp.fullName.slice(0, 1)}
                              </div>
                              <div>
                                <div className="font-bold text-theme-primary text-sm">{emp.fullName}</div>
                                <div className="font-mono text-[11px] text-theme-muted flex items-center gap-1.5 flex-wrap">
                                  <span>@{emp.username}</span>
                                  <span>•</span>
                                  <span className="text-pmp-primary">{emp.userCode}</span>
                                </div>
                              </div>
                            </div>
                          </td>

                          <td className="py-3.5 px-4">
                            <span className="font-semibold text-theme-primary block">{emp.role}</span>
                            <div className="flex items-center gap-1 mt-0.5 flex-wrap">
                              <GlassBadge
                                variant={
                                  emp.roleType === 'SUPER_ADMIN' ? 'red' : emp.roleType === 'MANAGER' ? 'gold' : 'cyan'
                                }
                                className="text-[10px]"
                              >
                                {emp.roleType}
                              </GlassBadge>
                              {isScoped && (
                                <GlassBadge variant="gold" className="text-[10px]">
                                  مقيّد النطاق
                                </GlassBadge>
                              )}
                            </div>
                          </td>

                          <td className="py-3.5 px-4 text-theme-secondary">
                            <div className="flex items-center gap-1.5">
                              <Building className="w-3.5 h-3.5 text-pmp-accent shrink-0" />
                              <span>{emp.branch}</span>
                            </div>
                          </td>

                          <td className="py-3.5 px-4">
                            <div className="flex items-center gap-1.5 flex-wrap">
                              <span className="text-xs font-semibold text-theme-primary">
                                {permsList.length} {language === 'ar' ? 'صلاحية' : 'tokens'}
                              </span>
                              {hasTemp && (
                                <GlassBadge variant="gold" className="text-[10px]">
                                  تفويض مؤقت
                                </GlassBadge>
                              )}
                            </div>
                          </td>

                          <td className="py-3.5 px-4">
                            {emp.active ? (
                              <span className="inline-flex items-center gap-1 text-emerald-400 font-bold text-xs">
                                <CheckCircle2 className="w-3.5 h-3.5" />
                                {language === 'ar' ? 'نشط' : 'Active'}
                              </span>
                            ) : (
                              <span className="inline-flex items-center gap-1 text-red-500 font-bold text-xs">
                                <XCircle className="w-3.5 h-3.5" />
                                {language === 'ar' ? 'معلق' : 'Suspended'}
                              </span>
                            )}
                          </td>

                          <td className="py-3.5 px-4">
                            <div className="flex items-center justify-center gap-1.5">
                              {canModifyTarget(emp) && (
                                <button
                                  type="button"
                                  onClick={() => {
                                    setSelectedEmp(emp);
                                    setResetPasswordInput('');
                                    setEditReason('تعديل الصلاحيات والدور الوظيفي');
                                    setIsEditModalOpen(true);
                                  }}
                                  className="p-1.5 rounded-lg bg-theme-surface border border-theme text-theme-primary hover:border-pmp-primary hover:text-pmp-primary transition-all"
                                  title={language === 'ar' ? 'تعديل الصلاحيات وكلمة المرور' : 'Edit'}
                                >
                                  <Edit3 className="w-3.5 h-3.5" />
                                </button>
                              )}

                              {canModifyTarget(emp) && !isPrimaryAdmin && (
                                <button
                                  type="button"
                                  onClick={() => {
                                    setSelectedEmp(emp);
                                    setTempGrantModalOpen(true);
                                  }}
                                  className="p-1.5 rounded-lg bg-theme-surface border border-theme text-theme-primary hover:border-pmp-warning hover:text-pmp-warning transition-all"
                                  title={language === 'ar' ? 'منح صلاحية مؤقتة' : 'Temp Grant'}
                                >
                                  <Clock className="w-3.5 h-3.5" />
                                </button>
                              )}

                              {canModifyTarget(emp) && !isPrimaryAdmin && (
                                <button
                                  type="button"
                                  onClick={() => handleToggleStatus(emp)}
                                  className={`p-1.5 rounded-lg border transition-all ${
                                    emp.active
                                      ? 'bg-amber-500/10 border-amber-500/30 text-amber-400 hover:bg-amber-500/20'
                                      : 'bg-emerald-500/10 border-emerald-500/30 text-emerald-400 hover:bg-emerald-500/20'
                                  }`}
                                  title={emp.active ? 'تجميد الحساب' : 'تنشيط الحساب'}
                                >
                                  {emp.active ? <Lock className="w-3.5 h-3.5" /> : <Unlock className="w-3.5 h-3.5" />}
                                </button>
                              )}

                              {canModifyTarget(emp) && !isPrimaryAdmin && (
                                <button
                                  type="button"
                                  onClick={() => handleDeleteEmployee(emp)}
                                  className="p-1.5 rounded-lg bg-red-500/10 border border-red-500/30 text-red-500 hover:bg-red-500/20 transition-all"
                                  title={language === 'ar' ? 'حذف الحساب' : 'Delete'}
                                >
                                  <Trash2 className="w-3.5 h-3.5" />
                                </button>
                              )}
                            </div>
                          </td>
                        </tr>
                      );
                    })}
                  </tbody>
                </table>
              </div>
            )}
          </GlassCard>
        </div>
      )}

      {/* TAB 2: CREATE ACCOUNT */}
      {activeSubTab === 'CREATE' && canManageUsers && (
        <GlassCard className="p-6 max-w-3xl">
          <form onSubmit={handleCreateEmployee} className="space-y-5">
            <h3 className="text-base font-black text-theme-primary flex items-center gap-2">
              <UserPlus className="w-5 h-5 text-pmp-primary" />
              <span>{language === 'ar' ? 'إنشاء حساب موظف جديد وتشفير الرمز السري' : 'Create Enterprise Account'}</span>
            </h3>

            <div className="grid grid-cols-1 sm:grid-cols-2 gap-4 text-xs">
              <div>
                <label className="block font-bold text-theme-primary mb-1">الاسم الكامل *</label>
                <input
                  type="text"
                  required
                  value={newEmpData.fullName}
                  onChange={(e) => setNewEmpData({ ...newEmpData, fullName: e.target.value })}
                  placeholder="مثال: حسام الدين محمود"
                  className="w-full px-3 py-2.5 rounded-xl bg-theme-base border border-theme text-theme-primary focus:outline-none focus:border-pmp-primary"
                />
              </div>

              <div>
                <label className="block font-bold text-theme-primary mb-1">اسم المستخدم للدخول *</label>
                <input
                  type="text"
                  required
                  dir="ltr"
                  value={newEmpData.username}
                  onChange={(e) => setNewEmpData({ ...newEmpData, username: e.target.value })}
                  placeholder="hossam_prod"
                  className="w-full px-3 py-2.5 rounded-xl bg-theme-base border border-theme text-theme-primary focus:outline-none focus:border-pmp-primary"
                />
              </div>

              <div>
                <label className="block font-bold text-theme-primary mb-1">كلمة المرور أو رمز PIN * (4 خانات فأكثر)</label>
                <input
                  type="password"
                  required
                  minLength={4}
                  dir="ltr"
                  value={newEmpData.rawPassword}
                  onChange={(e) => setNewEmpData({ ...newEmpData, rawPassword: e.target.value })}
                  placeholder="••••••"
                  className="w-full px-3 py-2.5 rounded-xl bg-theme-base border border-theme text-theme-primary focus:outline-none focus:border-pmp-primary"
                />
                {newEmpData.rawPassword.length > 0 && (
                  <span className={`text-[10px] mt-1 block font-bold ${passwordCheck.isValid ? 'text-emerald-400' : 'text-red-400'}`}>
                    مستوى التشفير: {passwordCheck.labelAr} (PBKDF2-HMAC-SHA256)
                  </span>
                )}
              </div>

              <div>
                <label className="block font-bold text-theme-primary mb-1">البريد الإلكتروني أو رقم الهاتف</label>
                <input
                  type="text"
                  dir="ltr"
                  value={newEmpData.phoneOrEmail}
                  onChange={(e) => setNewEmpData({ ...newEmpData, phoneOrEmail: e.target.value })}
                  placeholder="user@pmppaints.com"
                  className="w-full px-3 py-2.5 rounded-xl bg-theme-base border border-theme text-theme-primary focus:outline-none focus:border-pmp-primary"
                />
              </div>

              <div>
                <label className="block font-bold text-theme-primary mb-1">المسمى والدور الوظيفي</label>
                <select
                  value={newEmpData.role}
                  onChange={(e) => setNewEmpData({ ...newEmpData, role: e.target.value })}
                  className="w-full px-3 py-2.5 rounded-xl bg-theme-base border border-theme text-theme-primary focus:outline-none focus:border-pmp-primary"
                >
                  {JOB_ROLES.map((r) => (
                    <option key={r} value={r}>
                      {r}
                    </option>
                  ))}
                </select>
              </div>

              <div>
                <label className="block font-bold text-theme-primary mb-1">الفرع / المنشأة</label>
                <select
                  value={newEmpData.branch}
                  onChange={(e) => setNewEmpData({ ...newEmpData, branch: e.target.value })}
                  className="w-full px-3 py-2.5 rounded-xl bg-theme-base border border-theme text-theme-primary focus:outline-none focus:border-pmp-primary"
                >
                  {BRANCH_OPTIONS.map((b) => (
                    <option key={b} value={b}>
                      {b}
                    </option>
                  ))}
                </select>
              </div>
            </div>

            <label className="flex items-center gap-2 p-3 rounded-xl bg-amber-500/10 border border-amber-500/30 text-xs text-theme-primary cursor-pointer">
              <input
                type="checkbox"
                checked={newEmpData.isScopedAdmin}
                onChange={(e) => setNewEmpData({ ...newEmpData, isScopedAdmin: e.target.checked })}
              />
              <span className="font-bold">تعيين كمسؤول نظام مقيد النطاق (SCOPED_ADMIN — يمنع توريث صلاحيات المدير العام تلقائياً)</span>
            </label>

            <div>
              <h4 className="text-xs font-bold text-theme-primary mb-2">الوحدات والعمليات المصرح بها:</h4>
              <div className="grid grid-cols-2 sm:grid-cols-4 gap-2 text-xs">
                {[...Object.values(GRANULAR_ACTIONS), ...ERP_MODULES.map((m) => m.nameAr)].map((token) => {
                  const checked = newEmpData.permissions.includes(token);
                  return (
                    <label
                      key={token}
                      onClick={() => {
                        setNewEmpData((prev) => ({
                          ...prev,
                          permissions: prev.permissions.includes(token)
                            ? prev.permissions.filter((p) => p !== token)
                            : [...prev.permissions, token]
                        }));
                      }}
                      className={`flex items-center gap-2 p-2 rounded-lg border cursor-pointer select-none ${
                        checked
                          ? 'bg-pmp-primary/10 border-pmp-primary text-theme-primary font-bold'
                          : 'bg-theme-base border-theme text-theme-secondary'
                      }`}
                    >
                      <input type="checkbox" checked={checked} onChange={() => {}} />
                      <span className="truncate">{token}</span>
                    </label>
                  );
                })}
              </div>
            </div>

            <div className="flex justify-end gap-3 pt-3 border-t border-theme">
              <GlassButton type="button" variant="secondary" onClick={() => setActiveSubTab('DIRECTORY')}>
                إلغاء
              </GlassButton>
              <GlassButton type="submit" variant="primary" isLoading={isSubmitting}>
                إنشاء الحساب ومزامنته مع السيرفر
              </GlassButton>
            </div>
          </form>
        </GlassCard>
      )}

      {/* TAB 3: PENDING APPROVALS */}
      {activeSubTab === 'PENDING' && canManageUsers && (
        <GlassCard className="p-6 space-y-4">
          <h3 className="text-base font-black text-theme-primary flex items-center gap-2">
            <UserCheck className="w-5 h-5 text-pmp-gold" />
            <span>طلبات الدخول المسجلة عبر Google SSO بانتظار الاعتماد</span>
          </h3>

          {pendingRequests.length === 0 ? (
            <div className="py-10 text-center text-xs text-theme-muted">
              لا توجد طلبات انضمام معلقة حالياً.
            </div>
          ) : (
            <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
              {pendingRequests.map((req) => (
                <div key={req.userCode} className="p-4 rounded-xl bg-theme-base border border-theme space-y-3">
                  <div className="flex justify-between items-start gap-2">
                    <div>
                      <div className="font-bold text-theme-primary text-sm">{req.fullName}</div>
                      <div className="font-mono text-xs text-pmp-cyan">{req.phoneOrEmail || req.username}</div>
                      <div className="text-[11px] text-theme-muted mt-0.5">كود الطلب: {req.userCode}</div>
                    </div>
                    <GlassBadge variant="gold">معلق الاعتماد</GlassBadge>
                  </div>
                  <div className="flex justify-end">
                    <GlassButton
                      variant="primary"
                      className="text-xs"
                      onClick={() => {
                        setApprovingReq(req);
                        setApproveRole('مسؤول مبيعات وفواتير');
                        setApproveBranch(BRANCH_OPTIONS[0]);
                        setApprovePerms([GRANULAR_ACTIONS.VIEW, 'المبيعات']);
                      }}
                    >
                      مراجعة واعتماد الحساب
                    </GlassButton>
                  </div>
                </div>
              ))}
            </div>
          )}
        </GlassCard>
      )}

      {/* Edit Permissions & Password Modal */}
      {selectedEmp && (
        <Modal
          isOpen={isEditModalOpen}
          onClose={() => setIsEditModalOpen(false)}
          title={language === 'ar' ? `تعديل حساب وصلاحيات: ${selectedEmp.fullName}` : `Edit Account: ${selectedEmp.fullName}`}
          maxWidth="max-w-2xl"
        >
          <div className="space-y-5 text-xs">
            <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
              <div>
                <label className="block font-bold text-theme-primary mb-1">الاسم الكامل:</label>
                <input
                  type="text"
                  value={selectedEmp.fullName}
                  onChange={(e) => setSelectedEmp({ ...selectedEmp, fullName: e.target.value })}
                  className="w-full px-3 py-2 rounded-lg bg-theme-surface border border-theme text-theme-primary"
                />
              </div>
              <div>
                <label className="block font-bold text-theme-primary mb-1">الدور الوظيفي:</label>
                <select
                  value={selectedEmp.role}
                  onChange={(e) => setSelectedEmp({ ...selectedEmp, role: e.target.value })}
                  disabled={isSuperAdminAccount(selectedEmp)}
                  className="w-full px-3 py-2 rounded-lg bg-theme-surface border border-theme text-theme-primary disabled:opacity-60"
                >
                  {JOB_ROLES.map((r) => (
                    <option key={r} value={r}>
                      {r}
                    </option>
                  ))}
                  {!JOB_ROLES.includes(selectedEmp.role) && <option value={selectedEmp.role}>{selectedEmp.role}</option>}
                </select>
              </div>
              <div>
                <label className="block font-bold text-theme-primary mb-1">الفرع:</label>
                <select
                  value={selectedEmp.branch}
                  onChange={(e) => setSelectedEmp({ ...selectedEmp, branch: e.target.value })}
                  className="w-full px-3 py-2 rounded-lg bg-theme-surface border border-theme text-theme-primary"
                >
                  {BRANCH_OPTIONS.map((b) => (
                    <option key={b} value={b}>
                      {b}
                    </option>
                  ))}
                  {!BRANCH_OPTIONS.includes(selectedEmp.branch) && (
                    <option value={selectedEmp.branch}>{selectedEmp.branch}</option>
                  )}
                </select>
              </div>
              <div>
                <label className="block font-bold text-theme-primary mb-1 flex items-center gap-1">
                  <KeyRound className="w-3.5 h-3.5 text-pmp-warning" />
                  <span>إعادة تعيين كلمة المرور (اختياري):</span>
                </label>
                <input
                  type="password"
                  dir="ltr"
                  value={resetPasswordInput}
                  onChange={(e) => setResetPasswordInput(e.target.value)}
                  placeholder="اتركه فارغاً للإبقاء على الحالية"
                  className="w-full px-3 py-2 rounded-lg bg-theme-surface border border-theme text-theme-primary"
                />
              </div>
            </div>

            {!isSuperAdminAccount(selectedEmp) && (
              <label className="flex items-center gap-2 p-2.5 rounded-lg bg-amber-500/10 border border-amber-500/30 cursor-pointer">
                <input
                  type="checkbox"
                  checked={selectedEmp.permissions.split(',').map((s) => s.trim()).includes(SCOPED_ADMIN_MARKER)}
                  onChange={() => togglePermissionInSelected(SCOPED_ADMIN_MARKER)}
                />
                <span className="font-bold">تطبيق عزل النطاق (SCOPED_ADMIN)</span>
              </label>
            )}

            <div>
              <h4 className="font-bold text-theme-primary mb-2 flex items-center gap-1.5">
                <Shield className="w-4 h-4 text-pmp-primary" />
                <span>الصلاحيات الإجرائية الدقيقة (11 عملية)</span>
              </h4>
              <div className="grid grid-cols-2 sm:grid-cols-3 gap-2">
                {Object.entries(GRANULAR_ACTIONS).map(([key, actionName]) => {
                  const isChecked = selectedEmp.permissions.split(',').map((s) => s.trim()).includes(actionName);
                  return (
                    <label
                      key={key}
                      onClick={() => togglePermissionInSelected(actionName)}
                      className={`flex items-center gap-2 p-2 rounded-lg border cursor-pointer select-none ${
                        isChecked
                          ? 'bg-pmp-primary/10 border-pmp-primary text-theme-primary font-bold'
                          : 'bg-theme-surface border-theme text-theme-secondary'
                      }`}
                    >
                      <input type="checkbox" checked={isChecked} onChange={() => {}} />
                      <span>{actionName}</span>
                    </label>
                  );
                })}
              </div>
            </div>

            <div>
              <h4 className="font-bold text-theme-primary mb-2 flex items-center gap-1.5">
                <Building className="w-4 h-4 text-pmp-accent" />
                <span>الأقسام والوحدات المصرح بها</span>
              </h4>
              <div className="grid grid-cols-2 sm:grid-cols-4 gap-2">
                {ERP_MODULES.map((mod) => {
                  const isChecked = selectedEmp.permissions.split(',').map((s) => s.trim()).includes(mod.nameAr);
                  return (
                    <label
                      key={mod.id}
                      onClick={() => togglePermissionInSelected(mod.nameAr)}
                      className={`flex items-center gap-2 p-2 rounded-lg border cursor-pointer select-none ${
                        isChecked
                          ? 'bg-pmp-accent/10 border-pmp-accent text-theme-primary font-bold'
                          : 'bg-theme-surface border-theme text-theme-secondary'
                      }`}
                    >
                      <input type="checkbox" checked={isChecked} onChange={() => {}} />
                      <span>{language === 'ar' ? mod.nameAr : mod.nameEn}</span>
                    </label>
                  );
                })}
              </div>
            </div>

            <div>
              <label className="block font-bold text-theme-primary mb-1">سبب التعديل (يوثق في سجل الرقابة):</label>
              <input
                type="text"
                value={editReason}
                onChange={(e) => setEditReason(e.target.value)}
                className="w-full px-3 py-2 rounded-lg bg-theme-surface border border-theme text-theme-primary"
              />
            </div>

            <div className="flex justify-end gap-3 pt-3 border-t border-theme">
              <GlassButton variant="secondary" onClick={() => setIsEditModalOpen(false)}>
                إلغاء
              </GlassButton>
              <GlassButton variant="primary" onClick={handleSaveEdit} isLoading={isSubmitting}>
                حفظ التعديلات في السحابة
              </GlassButton>
            </div>
          </div>
        </Modal>
      )}

      {/* Approve Pending Request Modal */}
      {approvingReq && (
        <Modal
          isOpen={!!approvingReq}
          onClose={() => setApprovingReq(null)}
          title={`اعتماد طلب انضمام: ${approvingReq.fullName}`}
          maxWidth="max-w-lg"
        >
          <div className="space-y-4 text-xs">
            <div className="p-3 rounded-lg bg-theme-base border border-theme">
              <div>البريد / الهاتف: <span className="font-mono text-pmp-cyan">{approvingReq.phoneOrEmail}</span></div>
            </div>
            <div>
              <label className="block font-bold mb-1">الدور الممنوح:</label>
              <select
                value={approveRole}
                onChange={(e) => setApproveRole(e.target.value)}
                className="w-full px-3 py-2 rounded-lg bg-theme-surface border border-theme text-theme-primary"
              >
                {JOB_ROLES.map((r) => (
                  <option key={r} value={r}>
                    {r}
                  </option>
                ))}
              </select>
            </div>
            <div>
              <label className="block font-bold mb-1">الفرع:</label>
              <select
                value={approveBranch}
                onChange={(e) => setApproveBranch(e.target.value)}
                className="w-full px-3 py-2 rounded-lg bg-theme-surface border border-theme text-theme-primary"
              >
                {BRANCH_OPTIONS.map((b) => (
                  <option key={b} value={b}>
                    {b}
                  </option>
                ))}
              </select>
            </div>
            <div>
              <label className="block font-bold mb-1.5">الصلاحيات الممنوحة:</label>
              <div className="grid grid-cols-2 gap-2 max-h-48 overflow-y-auto">
                {[...Object.values(GRANULAR_ACTIONS), ...ERP_MODULES.map((m) => m.nameAr)].map((tok) => {
                  const checked = approvePerms.includes(tok);
                  return (
                    <label
                      key={tok}
                      onClick={() =>
                        setApprovePerms((prev) =>
                          prev.includes(tok) ? prev.filter((p) => p !== tok) : [...prev, tok]
                        )
                      }
                      className={`flex items-center gap-2 p-2 rounded-lg border cursor-pointer ${
                        checked ? 'bg-pmp-primary/10 border-pmp-primary font-bold' : 'bg-theme-surface border-theme'
                      }`}
                    >
                      <input type="checkbox" checked={checked} onChange={() => {}} />
                      <span className="truncate">{tok}</span>
                    </label>
                  );
                })}
              </div>
            </div>
            <div className="flex justify-end gap-2 pt-3 border-t border-theme">
              <GlassButton variant="secondary" onClick={() => setApprovingReq(null)}>
                إلغاء
              </GlassButton>
              <GlassButton variant="primary" onClick={handleApprovePending} isLoading={isSubmitting}>
                اعتماد وتفعيل الحساب
              </GlassButton>
            </div>
          </div>
        </Modal>
      )}

      {/* Temporary Grant Modal */}
      {selectedEmp && (
        <Modal
          isOpen={tempGrantModalOpen}
          onClose={() => setTempGrantModalOpen(false)}
          title={`تفويض مؤقت: ${selectedEmp.fullName}`}
          maxWidth="max-w-md"
        >
          <div className="space-y-4 text-xs">
            <div className="p-3 rounded-lg bg-amber-500/10 border border-amber-500/30 text-amber-400 flex items-center gap-2">
              <Clock className="w-4 h-4 shrink-0" />
              <span>تنتهي الصلاحية تلقائياً بانتهاء المدة مع تسجيل السبب في سجل الرقابة.</span>
            </div>

            <div>
              <label className="block font-bold mb-1">الصلاحية المؤقتة:</label>
              <select
                value={tempAction}
                onChange={(e) => setTempAction(e.target.value)}
                className="w-full px-3 py-2 rounded-lg bg-theme-surface border border-theme text-theme-primary"
              >
                {[...Object.values(GRANULAR_ACTIONS), ...ERP_MODULES.map((m) => m.nameAr)].map((action) => (
                  <option key={action} value={action}>
                    {action}
                  </option>
                ))}
              </select>
            </div>

            <div>
              <label className="block font-bold mb-1">المدة:</label>
              <select
                value={tempDurationHours}
                onChange={(e) => setTempDurationHours(Number(e.target.value))}
                className="w-full px-3 py-2 rounded-lg bg-theme-surface border border-theme text-theme-primary"
              >
                <option value={4}>4 ساعات</option>
                <option value={12}>12 ساعة</option>
                <option value={24}>24 ساعة (يوم كامل)</option>
                <option value={72}>72 ساعة (3 أيام)</option>
                <option value={168}>أسبوع واحد (7 أيام)</option>
              </select>
            </div>

            <div>
              <label className="block font-bold mb-1">السبب *</label>
              <input
                type="text"
                value={tempReason}
                onChange={(e) => setTempReason(e.target.value)}
                className="w-full px-3 py-2 rounded-lg bg-theme-surface border border-theme text-theme-primary"
              />
            </div>

            <div className="flex justify-end gap-2 pt-3 border-t border-theme">
              <GlassButton variant="secondary" onClick={() => setTempGrantModalOpen(false)}>
                إلغاء
              </GlassButton>
              <GlassButton variant="primary" onClick={handleApplyTemporaryGrant} isLoading={isSubmitting}>
                اعتماد التفويض المؤقت
              </GlassButton>
            </div>
          </div>
        </Modal>
      )}

      {/* Permission Audit Logs Modal */}
      <Modal
        isOpen={auditLogsModalOpen}
        onClose={() => setAuditLogsModalOpen(false)}
        title={language === 'ar' ? 'سجل تدقيق تغييرات الصلاحيات (permission_audit_logs)' : 'RBAC Permission Audit Log'}
        maxWidth="max-w-2xl"
      >
        <div className="space-y-3 max-h-[60vh] overflow-y-auto text-xs">
          {auditLoading ? (
            <div className="py-8 text-center text-theme-muted">جاري قراءة السجل من Firestore...</div>
          ) : auditLogs.length === 0 ? (
            <div className="py-8 text-center text-theme-muted">لا توجد قيود مسجلة في permission_audit_logs حتى الآن.</div>
          ) : (
            auditLogs.map((log, idx) => (
              <div key={log.entryId || idx} className="p-3.5 rounded-xl bg-theme-base border border-theme space-y-1.5">
                <div className="flex justify-between items-center text-theme-muted">
                  <span className="font-mono text-pmp-accent">{log.entryId}</span>
                  <span>{log.formattedDate || new Date(log.changedAtMillis).toLocaleString('ar-EG')}</span>
                </div>
                <div className="font-bold text-theme-primary text-sm">{log.reason}</div>
                <div className="text-theme-secondary flex items-center gap-2 flex-wrap">
                  <span>
                    المستهدف: <b className="text-theme-primary">{log.targetFullName} (@{log.targetUsername})</b>
                  </span>
                  <span>•</span>
                  <span>
                    بواسطة: <b className="text-pmp-primary">@{log.actorUsername}</b>
                  </span>
                </div>
              </div>
            ))
          )}
        </div>
      </Modal>
    </div>
  );
};
