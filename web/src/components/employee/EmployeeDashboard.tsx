import React, { useState } from 'react';
import {
  User,
  Shield,
  Building,
  KeyRound,
  CheckCircle2,
  Clock,
  Layers,
  ArrowUpRight,
  TrendingUp,
  FileCheck,
  Package,
  Activity,
  Calendar,
  Lock,
  Unlock,
  Settings,
  Bell
} from 'lucide-react';
import { GlassCard } from '../ui/GlassCard';
import { GlassBadge } from '../ui/GlassBadge';
import { GlassButton } from '../ui/GlassButton';
import { Modal } from '../ui/Modal';
import { useAuth } from '../../context/AuthContext';
import { useLanguage } from '../../context/LanguageContext';
import { ERP_MODULES, GRANULAR_ACTIONS } from '../../services/SecurityService';

interface EmployeeDashboardProps {
  onNavigateToModule: (moduleId: string) => void;
}

export const EmployeeDashboard: React.FC<EmployeeDashboardProps> = ({ onNavigateToModule }) => {
  const { language } = useLanguage();
  const { userProfile, userRoleType, isSuperAdmin, isManager, canAccessModule, canPerformAction, updateCurrentUserProfile } = useAuth();

  const [isSettingsOpen, setIsSettingsOpen] = useState(false);
  const [profileForm, setProfileForm] = useState({
    fullName: userProfile?.fullName || '',
    phoneOrEmail: userProfile?.phoneOrEmail || ''
  });
  const [saveSuccess, setSaveSuccess] = useState(false);

  if (!userProfile) return null;

  const permissionsList = (userProfile.permissions || '').split(',').map(p => p.trim()).filter(Boolean);
  const hasTemp = userProfile.permissions?.includes('TEMP[');

  const handleSaveProfile = async (e: React.FormEvent) => {
    e.preventDefault();
    await updateCurrentUserProfile(profileForm);
    setSaveSuccess(true);
    setTimeout(() => {
      setSaveSuccess(false);
      setIsSettingsOpen(false);
    }, 1500);
  };

  // Recent operational records relevant to the employee
  const recentActivities = [
    {
      title: language === 'ar' ? 'فحص خامات الشحنة رقم #PO-2026-88' : 'Inspection on PO #2026-88',
      module: 'المخازن والمشتريات',
      time: 'قبل 25 دقيقة',
      status: 'APPROVED',
      badge: language === 'ar' ? 'معتمد' : 'Approved'
    },
    {
      title: language === 'ar' ? 'أمر تصنيع دهان أبيض مطاطي #BAT-092' : 'Rubber White Paint Batch #BAT-092',
      module: 'المصنع والإنتاج',
      time: 'اليوم، 09:15 ص',
      status: 'IN_PRODUCTION',
      badge: language === 'ar' ? 'قيد الخلط' : 'In Progress'
    },
    {
      title: language === 'ar' ? 'إيداع نقدي بخزنة الجنيه السوداني #TR-441' : 'SDG Vault Cash Deposit #TR-441',
      module: 'الخزينة والسيولة',
      time: 'أمس، 03:40 م',
      status: 'COMPLETED',
      badge: language === 'ar' ? 'مكتمل' : 'Completed'
    }
  ];

  return (
    <div className="space-y-6">
      {/* 1. Welcome Hero Section */}
      <GlassCard className="p-6 relative overflow-hidden bg-gradient-to-r from-theme-surface to-pmp-primary/10">
        <div className="flex flex-col sm:flex-row justify-between items-start sm:items-center gap-4">
          <div className="flex items-center gap-4">
            <div className="w-14 h-14 rounded-2xl bg-pmp-primary/20 border border-pmp-primary/40 flex items-center justify-center font-black text-2xl text-pmp-primary">
              {userProfile.fullName.slice(0, 1)}
            </div>
            <div>
              <div className="flex items-center gap-2">
                <h2 className="text-2xl font-black text-theme-primary">
                  {language === 'ar' ? `مرحباً بك، ${userProfile.fullName}` : `Welcome back, ${userProfile.fullName}`}
                </h2>
                <GlassBadge
                  variant={
                    userRoleType === 'SUPER_ADMIN'
                      ? 'red'
                      : userRoleType === 'MANAGER'
                      ? 'gold'
                      : 'cyan'
                  }
                >
                  {userRoleType === 'SUPER_ADMIN'
                    ? (language === 'ar' ? 'مالك النظام' : 'Super Admin')
                    : userRoleType === 'MANAGER'
                    ? (language === 'ar' ? 'مدير عمليات' : 'Manager')
                    : (language === 'ar' ? 'موظف تشغيل' : 'Employee')}
                </GlassBadge>
              </div>

              <div className="flex items-center gap-3 text-xs text-theme-secondary mt-1 flex-wrap">
                <span className="font-mono text-pmp-primary font-bold">{userProfile.userCode}</span>
                <span>•</span>
                <span className="flex items-center gap-1">
                  <Building className="w-3.5 h-3.5 text-pmp-accent" />
                  {userProfile.branch}
                </span>
                <span>•</span>
                <span className="text-theme-muted">{userProfile.role}</span>
              </div>
            </div>
          </div>

          <div className="flex items-center gap-2">
            <GlassButton
              variant="secondary"
              icon={<Settings className="w-4 h-4" />}
              onClick={() => setIsSettingsOpen(true)}
              className="text-xs"
            >
              {language === 'ar' ? 'إعدادات الحساب' : 'Account Settings'}
            </GlassButton>
          </div>
        </div>
      </GlassCard>

      {/* 2. Key Stats and Role Capabilities */}
      <div className="grid grid-cols-2 sm:grid-cols-4 gap-4">
        <GlassCard className="p-4">
          <div className="flex justify-between items-center text-xs text-theme-muted mb-1">
            <span>{language === 'ar' ? 'الأقسام المتاحة' : 'Accessible Modules'}</span>
            <Layers className="w-4 h-4 text-pmp-accent" />
          </div>
          <div className="text-2xl font-black text-theme-primary">
            {isSuperAdmin
              ? ERP_MODULES.length
              : isManager
              ? ERP_MODULES.length - 2
              : ERP_MODULES.filter(m => canAccessModule(m.nameAr)).length}
          </div>
          <span className="text-[10px] text-theme-secondary mt-1 block">
            {language === 'ar' ? 'من أصل 16 قسماً تشغيلياً' : 'out of 16 modules'}
          </span>
        </GlassCard>

        <GlassCard className="p-4">
          <div className="flex justify-between items-center text-xs text-theme-muted mb-1">
            <span>{language === 'ar' ? 'الصلاحيات الإجرائية' : 'Action Perms'}</span>
            <Shield className="w-4 h-4 text-pmp-primary" />
          </div>
          <div className="text-2xl font-black text-pmp-primary">
            {isSuperAdmin ? '11 / 11' : `${permissionsList.length}`}
          </div>
          <span className="text-[10px] text-theme-secondary mt-1 block">
            {hasTemp ? (language === 'ar' ? 'تشمل تفويضات مؤقتة' : 'Includes temporary') : (language === 'ar' ? 'محددة بالدور' : 'Role based')}
          </span>
        </GlassCard>

        <GlassCard className="p-4">
          <div className="flex justify-between items-center text-xs text-theme-muted mb-1">
            <span>{language === 'ar' ? 'المهام المعلقة' : 'Pending Tasks'}</span>
            <Clock className="w-4 h-4 text-pmp-warning" />
          </div>
          <div className="text-2xl font-black text-pmp-warning">
            3
          </div>
          <span className="text-[10px] text-theme-secondary mt-1 block">
            {language === 'ar' ? 'بانتظار المراجعة أو الاعتماد' : 'Pending verification'}
          </span>
        </GlassCard>

        <GlassCard className="p-4">
          <div className="flex justify-between items-center text-xs text-theme-muted mb-1">
            <span>{language === 'ar' ? 'حالة المزامنة السحابية' : 'Sync Status'}</span>
            <Activity className="w-4 h-4 text-pmp-success" />
          </div>
          <div className="text-base font-bold text-pmp-success mt-1 flex items-center gap-1.5">
            <span className="w-2.5 h-2.5 rounded-full bg-pmp-success animate-ping" />
            <span>{language === 'ar' ? 'متصل ومحدث' : 'Synced'}</span>
          </div>
          <span className="text-[10px] text-theme-muted mt-1 block font-mono">
            {userProfile.serverSyncToken}
          </span>
        </GlassCard>
      </div>

      {/* 3. Permitted Modules & Quick Launch */}
      <GlassCard className="p-6">
        <div className="flex justify-between items-center mb-4">
          <h3 className="text-base font-bold text-theme-primary flex items-center gap-2">
            <Package className="w-4 h-4 text-pmp-primary" />
            {language === 'ar' ? 'الوحدات المصرح لك بالدخول إليها' : 'Your Authorized Modules'}
          </h3>
          <span className="text-xs text-theme-muted">
            {language === 'ar' ? 'اختر الوحدة لبدء إدخال البيانات أو استخراج التقارير' : 'Select a module to view operations'}
          </span>
        </div>

        <div className="grid grid-cols-2 sm:grid-cols-3 md:grid-cols-4 gap-3">
          {ERP_MODULES.map((mod) => {
            const hasAccess = canAccessModule(mod.nameAr);

            return (
              <div
                key={mod.id}
                onClick={() => {
                  if (hasAccess) onNavigateToModule(mod.id);
                }}
                className={`p-3.5 rounded-xl border text-xs flex flex-col justify-between transition-all ${
                  hasAccess
                    ? 'bg-theme-surface border-theme hover:border-pmp-primary hover:shadow-md cursor-pointer'
                    : 'bg-theme-base/40 border-theme/30 opacity-40 cursor-not-allowed'
                }`}
              >
                <div className="flex justify-between items-start mb-2">
                  <span className="font-bold text-theme-primary text-sm">
                    {language === 'ar' ? mod.nameAr : mod.nameEn}
                  </span>
                  {hasAccess ? (
                    <ArrowUpRight className="w-4 h-4 text-pmp-primary" />
                  ) : (
                    <Lock className="w-3.5 h-3.5 text-theme-muted" />
                  )}
                </div>
                <span className="text-[11px] text-theme-muted">
                  {hasAccess
                    ? (language === 'ar' ? 'متاح ومصرح' : 'Authorized')
                    : (language === 'ar' ? 'غير مصرح' : 'Restricted')}
                </span>
              </div>
            );
          })}
        </div>
      </GlassCard>

      {/* 4. Recent Assigned Activities and Notifications */}
      <div className="grid grid-cols-1 lg:grid-cols-12 gap-6 items-start">
        {/* Recent Records */}
        <div className="lg:col-span-7">
          <GlassCard className="p-6">
            <h3 className="text-base font-bold text-theme-primary mb-4 flex items-center gap-2">
              <Activity className="w-4 h-4 text-pmp-accent" />
              {language === 'ar' ? 'أحدث العمليات والحركات المسجلة' : 'Recent Operations & Ledger Updates'}
            </h3>

            <div className="space-y-3">
              {recentActivities.map((act, idx) => (
                <div
                  key={idx}
                  className="p-3 rounded-xl bg-theme-surface border border-theme flex items-center justify-between gap-3 text-xs"
                >
                  <div className="space-y-0.5">
                    <div className="font-bold text-theme-primary text-sm">{act.title}</div>
                    <div className="text-theme-muted flex items-center gap-2">
                      <span>{act.module}</span>
                      <span>•</span>
                      <span>{act.time}</span>
                    </div>
                  </div>
                  <GlassBadge variant={act.status === 'APPROVED' ? 'green' : 'gold'}>
                    {act.badge}
                  </GlassBadge>
                </div>
              ))}
            </div>
          </GlassCard>
        </div>

        {/* Assigned Security Privileges Card */}
        <div className="lg:col-span-5">
          <GlassCard className="p-6">
            <h3 className="text-base font-bold text-theme-primary mb-4 flex items-center gap-2">
              <Shield className="w-4 h-4 text-pmp-success" />
              {language === 'ar' ? 'تفويضاتك وصلاحياتك المباشرة' : 'Your Direct Security Grants'}
            </h3>

            <div className="space-y-2">
              {Object.entries(GRANULAR_ACTIONS).map(([key, actionName]) => {
                const isGranted = canPerformAction(actionName);
                return (
                  <div
                    key={key}
                    className={`p-2.5 rounded-lg border flex items-center justify-between text-xs ${
                      isGranted
                        ? 'bg-pmp-success/5 border-pmp-success/30 text-theme-primary'
                        : 'bg-theme-surface/50 border-theme text-theme-muted opacity-50'
                    }`}
                  >
                    <span>{actionName}</span>
                    {isGranted ? (
                      <CheckCircle2 className="w-4 h-4 text-pmp-success" />
                    ) : (
                      <Lock className="w-3.5 h-3.5 text-theme-muted" />
                    )}
                  </div>
                );
              })}
            </div>
          </GlassCard>
        </div>
      </div>

      {/* Account Settings Modal */}
      <Modal
        isOpen={isSettingsOpen}
        onClose={() => setIsSettingsOpen(false)}
        title={language === 'ar' ? 'إعدادات الحساب والملف الشخصي' : 'Account & Profile Settings'}
        maxWidth="max-w-md"
      >
        <form onSubmit={handleSaveProfile} className="space-y-4">
          {saveSuccess && (
            <div className="p-3 rounded-lg bg-green-500/10 border border-green-500/30 text-green-500 text-xs">
              {language === 'ar' ? 'تم تحديث البيانات بنجاح!' : 'Profile updated successfully!'}
            </div>
          )}

          <div>
            <label className="block text-xs font-semibold text-theme-primary mb-1">
              {language === 'ar' ? 'الاسم الكامل' : 'Full Name'}
            </label>
            <input
              type="text"
              value={profileForm.fullName}
              onChange={(e) => setProfileForm({ ...profileForm, fullName: e.target.value })}
              className="w-full px-3 py-2 rounded-lg bg-theme-surface border border-theme text-theme-primary text-xs focus:outline-none focus:border-pmp-primary"
            />
          </div>

          <div>
            <label className="block text-xs font-semibold text-theme-primary mb-1">
              {language === 'ar' ? 'البريد الإلكتروني أو الهاتف' : 'Email or Phone'}
            </label>
            <input
              type="text"
              value={profileForm.phoneOrEmail}
              onChange={(e) => setProfileForm({ ...profileForm, phoneOrEmail: e.target.value })}
              className="w-full px-3 py-2 rounded-lg bg-theme-surface border border-theme text-theme-primary text-xs focus:outline-none focus:border-pmp-primary"
            />
          </div>

          <div>
            <label className="block text-xs font-semibold text-theme-muted mb-1">
              {language === 'ar' ? 'كود الموظف (ثابت)' : 'Employee Code (Immutable)'}
            </label>
            <input
              type="text"
              disabled
              value={userProfile.userCode}
              className="w-full px-3 py-2 rounded-lg bg-theme-base/60 border border-theme text-theme-muted text-xs cursor-not-allowed font-mono"
            />
          </div>

          <div className="flex justify-end gap-3 pt-3 border-t border-theme">
            <GlassButton variant="secondary" onClick={() => setIsSettingsOpen(false)}>
              {language === 'ar' ? 'إلغاء' : 'Cancel'}
            </GlassButton>
            <GlassButton type="submit" variant="primary">
              {language === 'ar' ? 'حفظ التعديلات' : 'Save Changes'}
            </GlassButton>
          </div>
        </form>
      </Modal>
    </div>
  );
};
