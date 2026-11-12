import React, { useState } from 'react';
import {
  Users,
  Search,
  Filter,
  UserPlus,
  Shield,
  ShieldAlert,
  CheckCircle2,
  XCircle,
  Edit3,
  Lock,
  Clock,
  Eye,
  Building,
  KeyRound,
  FileText,
  AlertTriangle,
  RefreshCw
} from 'lucide-react';
import { GlassCard } from '../ui/GlassCard';
import { GlassBadge } from '../ui/GlassBadge';
import { GlassButton } from '../ui/GlassButton';
import { Modal } from '../ui/Modal';
import { UserAccount, UserRoleType } from '../../types/erp';
import { GRANULAR_ACTIONS, ERP_MODULES } from '../../services/SecurityService';
import { AccountService } from '../../services/AccountService';
import { useAuth } from '../../context/AuthContext';
import { useLanguage } from '../../context/LanguageContext';

export const EmployeeManagementCenter: React.FC = () => {
  const { language } = useLanguage();
  const { userProfile, isSuperAdmin, isManager } = useAuth();

  const [employees, setEmployees] = useState<UserAccount[]>(() => AccountService.getDefaultEmployees());
  const [searchTerm, setSearchTerm] = useState('');
  const [branchFilter, setBranchFilter] = useState('ALL');
  const [roleFilter, setRoleFilter] = useState('ALL');
  const [statusFilter, setStatusFilter] = useState('ALL');

  // Selected for Edit or View
  const [selectedEmp, setSelectedEmp] = useState<UserAccount | null>(null);
  const [isEditModalOpen, setIsEditModalOpen] = useState(false);
  const [isAddModalOpen, setIsAddModalOpen] = useState(false);
  const [auditLogsModalOpen, setAuditLogsModalOpen] = useState(false);
  const [auditLogs, setAuditLogs] = useState<any[]>([]);

  // Temporary Permission Grant Modal
  const [tempGrantModalOpen, setTempGrantModalOpen] = useState(false);
  const [tempAction, setTempAction] = useState<string>(GRANULAR_ACTIONS.APPROVE);
  const [tempDurationHours, setTempDurationHours] = useState<number>(24);
  const [tempReason, setTempReason] = useState<string>('مهمة طارئة لاعتماد الشحنات');

  // New Employee Form State
  const [newEmpData, setNewEmpData] = useState<Partial<UserAccount>>({
    fullName: '',
    username: '',
    phoneOrEmail: '',
    role: 'موظف مبيعات',
    roleType: 'EMPLOYEE',
    branch: 'SD-001 (الخرطوم - الإدارة العامة)',
    permissions: 'عرض,إنشاء,المبيعات',
    active: true
  });

  const filteredEmployees = employees.filter(emp => {
    const matchesSearch =
      emp.fullName.toLowerCase().includes(searchTerm.toLowerCase()) ||
      emp.username.toLowerCase().includes(searchTerm.toLowerCase()) ||
      emp.userCode.toLowerCase().includes(searchTerm.toLowerCase());

    const matchesBranch = branchFilter === 'ALL' || emp.branch.includes(branchFilter);
    const matchesRole = roleFilter === 'ALL' || emp.roleType === roleFilter;
    const matchesStatus =
      statusFilter === 'ALL' ||
      (statusFilter === 'ACTIVE' && emp.active) ||
      (statusFilter === 'INACTIVE' && !emp.active);

    return matchesSearch && matchesBranch && matchesRole && matchesStatus;
  });

  const handleToggleStatus = async (emp: UserAccount) => {
    if (!isSuperAdmin && emp.roleType === 'SUPER_ADMIN') {
      alert(language === 'ar' ? 'لا يمكن تعديل حالة حساب المدير التنفيذي' : 'Cannot alter Super Admin status');
      return;
    }

    const updated = { ...emp, active: !emp.active };
    setEmployees(prev => prev.map(e => (e.userCode === emp.userCode ? updated : e)));
    if (userProfile) {
      await AccountService.updateEmployee(
        updated,
        userProfile,
        emp.active ? 'تجميد حساب الموظف مؤقتاً' : 'إعادة تنشيط حساب الموظف'
      );
    }
  };

  const handleSaveEdit = async () => {
    if (!selectedEmp || !userProfile) return;
    setEmployees(prev => prev.map(e => (e.userCode === selectedEmp.userCode ? selectedEmp : e)));
    await AccountService.updateEmployee(selectedEmp, userProfile, 'تعديل الصلاحيات والدور الوظيفي');
    setIsEditModalOpen(false);
  };

  const handleCreateEmployee = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!newEmpData.fullName || !newEmpData.username || !userProfile) return;

    const created = await AccountService.createEmployee(newEmpData, userProfile);
    setEmployees(prev => [...prev, created]);
    setIsAddModalOpen(false);
    setNewEmpData({
      fullName: '',
      username: '',
      phoneOrEmail: '',
      role: 'موظف',
      roleType: 'EMPLOYEE',
      branch: 'SD-001 (الخرطوم)',
      permissions: 'عرض',
      active: true
    });
  };

  const handleOpenAuditLogs = async () => {
    const logs = await AccountService.getAuditLogs();
    setAuditLogs(logs);
    setAuditLogsModalOpen(true);
  };

  const handleApplyTemporaryGrant = async () => {
    if (!selectedEmp || !userProfile) return;
    const expiry = Date.now() + tempDurationHours * 3600 * 1000;
    const tokenStr = `TEMP[${tempAction}|${expiry}|${userProfile.username}|${tempReason}|TMP-${Date.now().toString().slice(-4)}]`;
    const newPerms = selectedEmp.permissions ? `${selectedEmp.permissions},${tokenStr}` : tokenStr;

    const updated = { ...selectedEmp, permissions: newPerms };
    setSelectedEmp(updated);
    setEmployees(prev => prev.map(e => (e.userCode === updated.userCode ? updated : e)));
    await AccountService.updateEmployee(
      updated,
      userProfile,
      `منح صلاحية مؤقتة (${tempAction}) لمدة ${tempDurationHours} ساعة: ${tempReason}`
    );
    setTempGrantModalOpen(false);
  };

  const togglePermissionInSelected = (permName: string) => {
    if (!selectedEmp) return;
    const currentList = selectedEmp.permissions.split(',').map(p => p.trim()).filter(Boolean);
    let newList: string[];
    if (currentList.includes(permName)) {
      newList = currentList.filter(p => p !== permName);
    } else {
      newList = [...currentList, permName];
    }
    setSelectedEmp({ ...selectedEmp, permissions: newList.join(',') });
  };

  return (
    <div className="space-y-6">
      {/* Header with Title and Quick Actions */}
      <div className="flex flex-col sm:flex-row justify-between items-start sm:items-center gap-4 pb-4 border-b border-theme">
        <div>
          <div className="flex items-center gap-2">
            <h2 className="text-2xl font-black text-theme-primary">
              {language === 'ar' ? 'مركز إدارة الموظفين والصلاحيات (Enterprise RBAC)' : 'Employee Management Center & Granular RBAC'}
            </h2>
            <GlassBadge variant="cyan">
              {language === 'ar' ? `${employees.length} حساب مسجل` : `${employees.length} Accounts`}
            </GlassBadge>
          </div>
          <p className="text-xs text-theme-secondary mt-1">
            {language === 'ar'
              ? 'إدارة حسابات منسوبي المصنع والمخازن والفروع، ضبط الصلاحيات الـ 11، ومنح التفويضات المؤقتة.'
              : 'Enterprise directory, role-based access control, temporary privileges, and security audit log.'}
          </p>
        </div>

        <div className="flex items-center gap-2.5 flex-wrap">
          <GlassButton
            variant="secondary"
            icon={<FileText className="w-4 h-4" />}
            onClick={handleOpenAuditLogs}
            className="text-xs"
          >
            {language === 'ar' ? 'سجل التدقيق الأمني' : 'Audit Logs'}
          </GlassButton>

          {isSuperAdmin && (
            <GlassButton
              variant="primary"
              icon={<UserPlus className="w-4 h-4" />}
              onClick={() => setIsAddModalOpen(true)}
              className="text-xs"
            >
              {language === 'ar' ? 'إضافة موظف جديد' : 'New Employee'}
            </GlassButton>
          )}
        </div>
      </div>

      {/* Filter and Search Bar */}
      <GlassCard className="p-4">
        <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-3">
          {/* Search */}
          <div className="relative">
            <Search className="w-4 h-4 text-theme-muted absolute right-3 top-3" />
            <input
              type="text"
              placeholder={language === 'ar' ? 'بحث بالاسم، الكود، أو اسم المستخدم...' : 'Search by name, code, or user...'}
              value={searchTerm}
              onChange={(e) => setSearchTerm(e.target.value)}
              className="w-full pr-9 pl-3 py-2 rounded-lg bg-theme-surface border border-theme text-theme-primary text-xs focus:outline-none focus:border-pmp-primary"
            />
          </div>

          {/* Branch Filter */}
          <div>
            <select
              value={branchFilter}
              onChange={(e) => setBranchFilter(e.target.value)}
              className="w-full px-3 py-2 rounded-lg bg-theme-surface border border-theme text-theme-primary text-xs focus:outline-none focus:border-pmp-primary"
            >
              <option value="ALL">{language === 'ar' ? 'جميع الفروع والمواقع' : 'All Branches'}</option>
              <option value="SD-001">{language === 'ar' ? 'فرع السودان (SD-001)' : 'Sudan (SD-001)'}</option>
              <option value="EG-001">{language === 'ar' ? 'مصنع مصر (EG-001)' : 'Egypt Factory (EG-001)'}</option>
              <option value="HF-001">{language === 'ar' ? 'مستودع حلفا (HF-001)' : 'Halfa Hub (HF-001)'}</option>
            </select>
          </div>

          {/* Role Filter */}
          <div>
            <select
              value={roleFilter}
              onChange={(e) => setRoleFilter(e.target.value)}
              className="w-full px-3 py-2 rounded-lg bg-theme-surface border border-theme text-theme-primary text-xs focus:outline-none focus:border-pmp-primary"
            >
              <option value="ALL">{language === 'ar' ? 'جميع الرتب الوظيفية' : 'All Roles'}</option>
              <option value="SUPER_ADMIN">{language === 'ar' ? 'المدير التنفيذي (Super Admin)' : 'Super Admin'}</option>
              <option value="MANAGER">{language === 'ar' ? 'المدراء والمشرفون (Manager)' : 'Managers'}</option>
              <option value="EMPLOYEE">{language === 'ar' ? 'الموظفون المعتمدون (Employee)' : 'Employees'}</option>
            </select>
          </div>

          {/* Status Filter */}
          <div>
            <select
              value={statusFilter}
              onChange={(e) => setStatusFilter(e.target.value)}
              className="w-full px-3 py-2 rounded-lg bg-theme-surface border border-theme text-theme-primary text-xs focus:outline-none focus:border-pmp-primary"
            >
              <option value="ALL">{language === 'ar' ? 'جميع الحالات' : 'All Statuses'}</option>
              <option value="ACTIVE">{language === 'ar' ? 'نشط ومصرح له' : 'Active Only'}</option>
              <option value="INACTIVE">{language === 'ar' ? 'مجمد / معلق' : 'Suspended Only'}</option>
            </select>
          </div>
        </div>
      </GlassCard>

      {/* Employees Directory Table */}
      <GlassCard className="p-0 overflow-hidden">
        <div className="overflow-x-auto">
          <table className="w-full text-right border-collapse text-xs">
            <thead>
              <tr className="bg-theme-surface/70 border-b border-theme text-theme-muted font-bold">
                <th className="py-3 px-4">{language === 'ar' ? 'الموظف وكود الحساب' : 'Employee & Code'}</th>
                <th className="py-3 px-4">{language === 'ar' ? 'الرتبة والمسؤولية' : 'Role & Scope'}</th>
                <th className="py-3 px-4">{language === 'ar' ? 'الفرع أو المنشأة' : 'Branch'}</th>
                <th className="py-3 px-4">{language === 'ar' ? 'الصلاحيات المعتمدة' : 'Permissions'}</th>
                <th className="py-3 px-4">{language === 'ar' ? 'الحالة' : 'Status'}</th>
                <th className="py-3 px-4 text-center">{language === 'ar' ? 'الإجراءات' : 'Actions'}</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-theme">
              {filteredEmployees.map((emp) => {
                const permsCount = emp.permissions.split(',').filter(Boolean).length;
                const hasTemp = emp.permissions.includes('TEMP[');

                return (
                  <tr key={emp.userCode} className="hover:bg-theme-surface/40 transition-colors">
                    {/* Employee Info */}
                    <td className="py-3.5 px-4">
                      <div className="flex items-center gap-2.5">
                        <div className="w-8 h-8 rounded-full bg-pmp-primary/10 border border-pmp-primary/30 flex items-center justify-center font-black text-pmp-primary text-xs">
                          {emp.fullName.slice(0, 1)}
                        </div>
                        <div>
                          <div className="font-bold text-theme-primary text-sm">{emp.fullName}</div>
                          <div className="font-mono text-[11px] text-theme-muted flex items-center gap-1.5">
                            <span>@{emp.username}</span>
                            <span>•</span>
                            <span className="text-pmp-primary">{emp.userCode}</span>
                          </div>
                        </div>
                      </div>
                    </td>

                    {/* Role */}
                    <td className="py-3.5 px-4">
                      <div>
                        <span className="font-semibold text-theme-primary block">{emp.role}</span>
                        <GlassBadge
                          variant={
                            emp.roleType === 'SUPER_ADMIN'
                              ? 'red'
                              : emp.roleType === 'MANAGER'
                              ? 'gold'
                              : 'cyan'
                          }
                          className="mt-0.5 text-[10px]"
                        >
                          {emp.roleType === 'SUPER_ADMIN'
                            ? (language === 'ar' ? 'مالك النظام' : 'Super Admin')
                            : emp.roleType === 'MANAGER'
                            ? (language === 'ar' ? 'مدير عمليات' : 'Manager')
                            : (language === 'ar' ? 'موظف تشغيل' : 'Employee')}
                        </GlassBadge>
                      </div>
                    </td>

                    {/* Branch */}
                    <td className="py-3.5 px-4 text-theme-secondary">
                      <div className="flex items-center gap-1.5">
                        <Building className="w-3.5 h-3.5 text-pmp-accent shrink-0" />
                        <span>{emp.branch}</span>
                      </div>
                    </td>

                    {/* Permissions summary */}
                    <td className="py-3.5 px-4">
                      <div className="flex items-center gap-1.5 flex-wrap">
                        <span className="text-xs font-semibold text-theme-primary">
                          {permsCount} {language === 'ar' ? 'صلاحية' : 'perms'}
                        </span>
                        {hasTemp && (
                          <GlassBadge variant="gold" className="text-[10px] animate-pulse">
                            {language === 'ar' ? 'تفويض مؤقت' : 'Temporary'}
                          </GlassBadge>
                        )}
                      </div>
                    </td>

                    {/* Status */}
                    <td className="py-3.5 px-4">
                      {emp.active ? (
                        <span className="inline-flex items-center gap-1 text-green-500 font-bold text-xs">
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

                    {/* Actions */}
                    <td className="py-3.5 px-4">
                      <div className="flex items-center justify-center gap-2">
                        {isSuperAdmin && (
                          <button
                            onClick={() => {
                              setSelectedEmp(emp);
                              setIsEditModalOpen(true);
                            }}
                            className="p-1.5 rounded-lg bg-theme-surface border border-theme text-theme-primary hover:border-pmp-primary hover:text-pmp-primary transition-all"
                            title={language === 'ar' ? 'تعديل الصلاحيات' : 'Edit Permissions'}
                          >
                            <Edit3 className="w-3.5 h-3.5" />
                          </button>
                        )}

                        {isSuperAdmin && (
                          <button
                            onClick={() => {
                              setSelectedEmp(emp);
                              setTempGrantModalOpen(true);
                            }}
                            className="p-1.5 rounded-lg bg-theme-surface border border-theme text-theme-primary hover:border-pmp-warning hover:text-pmp-warning transition-all"
                            title={language === 'ar' ? 'منح صلاحية مؤقتة' : 'Temporary Grant'}
                          >
                            <Clock className="w-3.5 h-3.5" />
                          </button>
                        )}

                        {isSuperAdmin && emp.roleType !== 'SUPER_ADMIN' && (
                          <button
                            onClick={() => handleToggleStatus(emp)}
                            className={`p-1.5 rounded-lg border text-xs font-semibold transition-all ${
                              emp.active
                                ? 'bg-red-500/10 border-red-500/30 text-red-500 hover:bg-red-500/20'
                                : 'bg-green-500/10 border-green-500/30 text-green-500 hover:bg-green-500/20'
                            }`}
                            title={emp.active ? (language === 'ar' ? 'تجميد الحساب' : 'Suspend') : (language === 'ar' ? 'تنشيط الحساب' : 'Activate')}
                          >
                            <Lock className="w-3.5 h-3.5" />
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
      </GlassCard>

      {/* Edit Permissions Modal */}
      {selectedEmp && (
        <Modal
          isOpen={isEditModalOpen}
          onClose={() => setIsEditModalOpen(false)}
          title={language === 'ar' ? `تعديل صلاحيات: ${selectedEmp.fullName}` : `Edit Permissions: ${selectedEmp.fullName}`}
          maxWidth="max-w-2xl"
        >
          <div className="space-y-6">
            <div className="grid grid-cols-1 sm:grid-cols-2 gap-4 p-4 rounded-xl bg-theme-surface border border-theme text-xs">
              <div>
                <span className="text-theme-muted block">{language === 'ar' ? 'الاسم وكود الموظف:' : 'Employee:'}</span>
                <span className="font-bold text-theme-primary text-sm">{selectedEmp.fullName} ({selectedEmp.userCode})</span>
              </div>
              <div>
                <span className="text-theme-muted block">{language === 'ar' ? 'الفرع:' : 'Branch:'}</span>
                <span className="font-bold text-theme-primary">{selectedEmp.branch}</span>
              </div>
            </div>

            {/* Granular Actions (11 Actions) */}
            <div>
              <h4 className="text-sm font-bold text-theme-primary mb-2 flex items-center gap-1.5">
                <Shield className="w-4 h-4 text-pmp-primary" />
                {language === 'ar' ? 'الصلاحيات الإجرائية الدقيقة (11 عملية)' : 'Granular Operational Actions (11 Actions)'}
              </h4>
              <div className="grid grid-cols-2 sm:grid-cols-3 gap-2">
                {Object.entries(GRANULAR_ACTIONS).map(([key, actionName]) => {
                  const isChecked = selectedEmp.permissions.includes(actionName);
                  return (
                    <label
                      key={key}
                      onClick={() => togglePermissionInSelected(actionName)}
                      className={`flex items-center gap-2 p-2.5 rounded-lg border text-xs cursor-pointer select-none transition-all ${
                        isChecked
                          ? 'bg-pmp-primary/10 border-pmp-primary text-theme-primary font-bold'
                          : 'bg-theme-surface border-theme text-theme-secondary hover:border-theme-hover'
                      }`}
                    >
                      <input
                        type="checkbox"
                        checked={isChecked}
                        onChange={() => {}}
                        className="rounded border-theme text-pmp-primary"
                      />
                      <span>{actionName}</span>
                    </label>
                  );
                })}
              </div>
            </div>

            {/* Modules Access */}
            <div>
              <h4 className="text-sm font-bold text-theme-primary mb-2 flex items-center gap-1.5">
                <Building className="w-4 h-4 text-pmp-accent" />
                {language === 'ar' ? 'الأقسام والوحدات المصرح بالدخول إليها' : 'Permitted Modules'}
              </h4>
              <div className="grid grid-cols-2 sm:grid-cols-4 gap-2">
                {ERP_MODULES.map(mod => {
                  const isChecked = selectedEmp.permissions.includes(mod.nameAr);
                  return (
                    <label
                      key={mod.id}
                      onClick={() => togglePermissionInSelected(mod.nameAr)}
                      className={`flex items-center gap-2 p-2 rounded-lg border text-xs cursor-pointer select-none transition-all ${
                        isChecked
                          ? 'bg-pmp-accent/10 border-pmp-accent text-theme-primary font-bold'
                          : 'bg-theme-surface border-theme text-theme-secondary hover:border-theme-hover'
                      }`}
                    >
                      <input
                        type="checkbox"
                        checked={isChecked}
                        onChange={() => {}}
                        className="rounded border-theme text-pmp-accent"
                      />
                      <span>{language === 'ar' ? mod.nameAr : mod.nameEn}</span>
                    </label>
                  );
                })}
              </div>
            </div>

            <div className="flex justify-end gap-3 pt-4 border-t border-theme">
              <GlassButton variant="secondary" onClick={() => setIsEditModalOpen(false)}>
                {language === 'ar' ? 'إلغاء' : 'Cancel'}
              </GlassButton>
              <GlassButton variant="primary" onClick={handleSaveEdit}>
                {language === 'ar' ? 'حفظ الصلاحيات في السحابة' : 'Save Permissions'}
              </GlassButton>
            </div>
          </div>
        </Modal>
      )}

      {/* Temporary Permission Grant Modal */}
      {selectedEmp && (
        <Modal
          isOpen={tempGrantModalOpen}
          onClose={() => setTempGrantModalOpen(false)}
          title={language === 'ar' ? `منح تفويض طارئ ومؤقت: ${selectedEmp.fullName}` : `Temporary Grant: ${selectedEmp.fullName}`}
          maxWidth="max-w-md"
        >
          <div className="space-y-4">
            <div className="p-3 rounded-lg bg-pmp-warning/10 border border-pmp-warning/30 flex items-center gap-2 text-xs text-pmp-warning">
              <Clock className="w-5 h-5 shrink-0" />
              <span>
                {language === 'ar'
                  ? 'ستنتهي هذه الصلاحية تلقائياً بمجرد انقضاء المدة المحددة مع توثيق السبب في سجل الرقابة.'
                  : 'This temporary privilege expires automatically and is recorded in the central audit trail.'}
              </span>
            </div>

            <div>
              <label className="block text-xs font-semibold text-theme-primary mb-1">
                {language === 'ar' ? 'الصلاحية المراد منحها مؤقتاً' : 'Temporary Action'}
              </label>
              <select
                value={tempAction}
                onChange={(e) => setTempAction(e.target.value)}
                className="w-full px-3 py-2 rounded-lg bg-theme-surface border border-theme text-theme-primary text-xs focus:outline-none focus:border-pmp-primary"
              >
                {Object.values(GRANULAR_ACTIONS).map(action => (
                  <option key={action} value={action}>{action}</option>
                ))}
              </select>
            </div>

            <div>
              <label className="block text-xs font-semibold text-theme-primary mb-1">
                {language === 'ar' ? 'مدة الصلاحية المؤقتة' : 'Duration'}
              </label>
              <select
                value={tempDurationHours}
                onChange={(e) => setTempDurationHours(Number(e.target.value))}
                className="w-full px-3 py-2 rounded-lg bg-theme-surface border border-theme text-theme-primary text-xs focus:outline-none focus:border-pmp-primary"
              >
                <option value={4}>{language === 'ar' ? '4 ساعات (مهمة عاجلة)' : '4 Hours (Urgent task)'}</option>
                <option value={12}>{language === 'ar' ? '12 ساعة (وردية واحدة)' : '12 Hours (One shift)'}</option>
                <option value={24}>{language === 'ar' ? '24 ساعة (يوم كامل)' : '24 Hours (Full day)'}</option>
                <option value={72}>{language === 'ar' ? '72 ساعة (إجازة المشرف)' : '72 Hours (Supervisor leave)'}</option>
                <option value={168}>{language === 'ar' ? 'أسبوع واحد (7 أيام)' : '1 Week (7 days)'}</option>
              </select>
            </div>

            <div>
              <label className="block text-xs font-semibold text-theme-primary mb-1">
                {language === 'ar' ? 'سبب المنح الاستثنائي *' : 'Reason for Exception *'}
              </label>
              <input
                type="text"
                value={tempReason}
                onChange={(e) => setTempReason(e.target.value)}
                placeholder={language === 'ar' ? 'اكتب سبب التفويض...' : 'Reason for grant...'}
                className="w-full px-3 py-2 rounded-lg bg-theme-surface border border-theme text-theme-primary text-xs focus:outline-none focus:border-pmp-primary"
              />
            </div>

            <div className="flex justify-end gap-3 pt-3 border-t border-theme">
              <GlassButton variant="secondary" onClick={() => setTempGrantModalOpen(false)}>
                {language === 'ar' ? 'إلغاء' : 'Cancel'}
              </GlassButton>
              <GlassButton variant="primary" onClick={handleApplyTemporaryGrant}>
                {language === 'ar' ? 'اعتماد التفويض المؤقت' : 'Approve Grant'}
              </GlassButton>
            </div>
          </div>
        </Modal>
      )}

      {/* Add New Employee Modal */}
      <Modal
        isOpen={isAddModalOpen}
        onClose={() => setIsAddModalOpen(false)}
        title={language === 'ar' ? 'إضافة موظف جديد واعتماد الصلاحيات' : 'Register New Enterprise Employee'}
        maxWidth="max-w-lg"
      >
        <form onSubmit={handleCreateEmployee} className="space-y-4">
          <div>
            <label className="block text-xs font-semibold text-theme-primary mb-1">
              {language === 'ar' ? 'الاسم الكامل للموظف *' : 'Full Name *'}
            </label>
            <input
              type="text"
              required
              value={newEmpData.fullName}
              onChange={(e) => setNewEmpData({ ...newEmpData, fullName: e.target.value })}
              placeholder={language === 'ar' ? 'مثال: حسام الدين محمود' : 'e.g. Hossam El Din'}
              className="w-full px-3 py-2 rounded-lg bg-theme-surface border border-theme text-theme-primary text-xs focus:outline-none focus:border-pmp-primary"
            />
          </div>

          <div className="grid grid-cols-2 gap-3">
            <div>
              <label className="block text-xs font-semibold text-theme-primary mb-1">
                {language === 'ar' ? 'اسم المستخدم للدخول *' : 'Username *'}
              </label>
              <input
                type="text"
                required
                value={newEmpData.username}
                onChange={(e) => setNewEmpData({ ...newEmpData, username: e.target.value })}
                placeholder="hossam_prod"
                className="w-full px-3 py-2 rounded-lg bg-theme-surface border border-theme text-theme-primary text-xs focus:outline-none focus:border-pmp-primary"
              />
            </div>

            <div>
              <label className="block text-xs font-semibold text-theme-primary mb-1">
                {language === 'ar' ? 'الرتبة في المنظومة' : 'Role Tier'}
              </label>
              <select
                value={newEmpData.roleType}
                onChange={(e) => setNewEmpData({ ...newEmpData, roleType: e.target.value as UserRoleType })}
                className="w-full px-3 py-2 rounded-lg bg-theme-surface border border-theme text-theme-primary text-xs focus:outline-none focus:border-pmp-primary"
              >
                <option value="EMPLOYEE">{language === 'ar' ? 'موظف تشغيل (Employee)' : 'Employee'}</option>
                <option value="MANAGER">{language === 'ar' ? 'مدير عمليات (Manager)' : 'Manager'}</option>
                <option value="SUPER_ADMIN">{language === 'ar' ? 'مدير تنفيذي (Super Admin)' : 'Super Admin'}</option>
              </select>
            </div>
          </div>

          <div>
            <label className="block text-xs font-semibold text-theme-primary mb-1">
              {language === 'ar' ? 'المسمى الوظيفي المعتمد' : 'Official Job Title'}
            </label>
            <input
              type="text"
              value={newEmpData.role}
              onChange={(e) => setNewEmpData({ ...newEmpData, role: e.target.value })}
              placeholder={language === 'ar' ? 'مثال: مهندس تشغيل خطوط الدهانات' : 'e.g. Production Engineer'}
              className="w-full px-3 py-2 rounded-lg bg-theme-surface border border-theme text-theme-primary text-xs focus:outline-none focus:border-pmp-primary"
            />
          </div>

          <div>
            <label className="block text-xs font-semibold text-theme-primary mb-1">
              {language === 'ar' ? 'الفرع أو المنشأة' : 'Branch / Facility'}
            </label>
            <select
              value={newEmpData.branch}
              onChange={(e) => setNewEmpData({ ...newEmpData, branch: e.target.value })}
              className="w-full px-3 py-2 rounded-lg bg-theme-surface border border-theme text-theme-primary text-xs focus:outline-none focus:border-pmp-primary"
            >
              <option value="SD-001 (الخرطوم - الإدارة العامة)">{language === 'ar' ? 'فرع السودان (SD-001)' : 'Sudan (SD-001)'}</option>
              <option value="EG-001 (مصنع مصر والتصدير)">{language === 'ar' ? 'فرع ومصنع مصر (EG-001)' : 'Egypt Factory (EG-001)'}</option>
              <option value="HF-001 (مستودع حلفا اللوجستي)">{language === 'ar' ? 'مستودع حلفا (HF-001)' : 'Halfa Hub (HF-001)'}</option>
              <option value="AT-002 (مستودع وتوزيع عطبرة)">{language === 'ar' ? 'مستودع عطبرة (AT-002)' : 'Atbara Hub (AT-002)'}</option>
            </select>
          </div>

          <div className="flex justify-end gap-3 pt-3 border-t border-theme">
            <GlassButton variant="secondary" onClick={() => setIsAddModalOpen(false)}>
              {language === 'ar' ? 'إلغاء' : 'Cancel'}
            </GlassButton>
            <GlassButton type="submit" variant="primary">
              {language === 'ar' ? 'إنشاء الحساب وتفعيل المزامنة' : 'Create & Activate'}
            </GlassButton>
          </div>
        </form>
      </Modal>

      {/* Security Audit Trail Modal */}
      <Modal
        isOpen={auditLogsModalOpen}
        onClose={() => setAuditLogsModalOpen(false)}
        title={language === 'ar' ? 'سجل التدقيق الأمني (Security Audit Trail)' : 'Security Audit Trail'}
        maxWidth="max-w-2xl"
      >
        <div className="space-y-3 max-h-[60vh] overflow-y-auto">
          {auditLogs.map((log, idx) => (
            <div key={idx} className="p-3.5 rounded-xl bg-theme-surface border border-theme text-xs space-y-1.5">
              <div className="flex justify-between items-center text-theme-muted">
                <span className="font-mono text-pmp-accent">{log.entryId}</span>
                <span>{log.formattedDate}</span>
              </div>
              <div className="font-bold text-theme-primary text-sm">
                {log.reason}
              </div>
              <div className="text-theme-secondary flex items-center gap-2 flex-wrap">
                <span>{language === 'ar' ? 'المستهدف:' : 'Target:'} <b className="text-theme-primary">{log.targetFullName} (@{log.targetUsername})</b></span>
                <span>•</span>
                <span>{language === 'ar' ? 'بواسطة:' : 'By:'} <b className="text-pmp-primary">@{log.actorUsername} ({log.actorRole})</b></span>
              </div>
            </div>
          ))}
        </div>
      </Modal>
    </div>
  );
};
