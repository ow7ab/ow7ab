import React, { useState, useEffect } from 'react';
import {
  Settings,
  Server,
  Shield,
  RefreshCw,
  CheckCircle,
  AlertTriangle,
  HardDrive,
  Moon,
  Sun,
  Globe,
  Smartphone
} from 'lucide-react';
import { GlassCard } from '../ui/GlassCard';
import { GlassBadge } from '../ui/GlassBadge';
import { GlassButton } from '../ui/GlassButton';
import { useLanguage } from '../../context/LanguageContext';
import { useTheme } from '../../context/ThemeContext';
import { useAuth } from '../../context/AuthContext';
import {
  ErpDataService,
  PRIMARY_CLUSTER,
  LEGACY_V16_CLUSTER,
  ClusterHealthReport
} from '../../services/ErpDataService';
import { GRANULAR_ACTIONS } from '../../services/SecurityService';
import { AuditLog } from '../../types/erp';

export const SettingsModuleView: React.FC = () => {
  const { language, toggleLanguage } = useLanguage();
  const { theme, toggleTheme } = useTheme();
  const { userProfile, isSuperAdmin, canPerformAction } = useAuth();

  const canManageSystemSettings = isSuperAdmin || canPerformAction(GRANULAR_ACTIONS.ADMIN_SETTINGS);

  const [activeCluster, setActiveCluster] = useState<string>(ErpDataService.activeCluster);
  const [auditLogs, setAuditLogs] = useState<AuditLog[]>([]);
  const [syncStatus, setSyncStatus] = useState<'IDLE' | 'SYNCING' | 'SUCCESS' | 'ERROR'>('IDLE');
  const [healthReport, setHealthReport] = useState<ClusterHealthReport | null>(null);
  const [feedback, setFeedback] = useState<{ type: 'success' | 'error'; text: string } | null>(null);
  const [apkVersionInfo, setApkVersionInfo] = useState<{ versionName?: string; versionCode?: number; sha256?: string } | null>(null);

  useEffect(() => {
    const unsubAudit = ErpDataService.subscribeToCollection<AuditLog>('audit_logs', (logs) => {
      setAuditLogs([...logs].sort((a, b) => (b.timestamp || 0) - (a.timestamp || 0)));
    });
    fetch('/version.json')
      .then((res) => (res.ok ? res.json() : null))
      .then((data) => {
        if (data) setApkVersionInfo(data);
      })
      .catch(() => {});
    return () => unsubAudit();
  }, [activeCluster]);

  const handleSwitchCluster = async (clusterCode: string) => {
    if (!canManageSystemSettings) return;
    ErpDataService.setActiveCluster(clusterCode);
    setActiveCluster(ErpDataService.activeCluster);
    await ErpDataService.logAudit(
      userProfile?.username || 'admin',
      'SWITCH_CLOUD_CLUSTER',
      `تبديل عنقود المزامنة النشط على الويب إلى ${clusterCode}`
    );
    setFeedback({
      type: 'success',
      text: `تم تبديل العنقود النشط إلى ${clusterCode}. أعد تحميل الوحدات أو افتح تبويب آخر لتطبيق المستمعات الجديدة.`
    });
    setTimeout(() => setFeedback(null), 4500);
  };

  const handleRunHealthCheck = async () => {
    setSyncStatus('SYNCING');
    const report = await ErpDataService.checkServerHealth();
    setHealthReport(report);
    if (report.ok) {
      setSyncStatus('SUCCESS');
      setFeedback({
        type: 'success',
        text: `الاتصال بـ Firestore سليم (${report.latencyMs}ms) — العنقود: ${report.cluster}`
      });
    } else {
      setSyncStatus('ERROR');
      setFeedback({
        type: 'error',
        text: report.error || 'تعذر التحقق من اتصال السيرفر السحابي'
      });
    }
    setTimeout(() => setFeedback(null), 5000);
  };

  return (
    <div className="space-y-6">
      {/* Header */}
      <GlassCard className="p-6 bg-gradient-to-r from-theme-surface to-red-600/10 border-theme">
        <div className="flex flex-col sm:flex-row justify-between items-start sm:items-center gap-4">
          <div className="flex items-center gap-3">
            <div className="w-12 h-12 rounded-2xl bg-red-600/20 text-red-500 border border-red-500/30 flex items-center justify-center shrink-0">
              <Settings className="w-6 h-6" />
            </div>
            <div>
              <div className="flex items-center gap-2 flex-wrap">
                <h2 className="text-xl font-black text-theme-primary">
                  {language === 'ar' ? 'إعدادات النظام والمزامنة السحابية' : 'System Settings & Cloud Sync'}
                </h2>
                <GlassBadge variant="red">{activeCluster}</GlassBadge>
              </div>
              <p className="text-xs text-theme-secondary mt-1">
                {language === 'ar'
                  ? 'تفضيلات الواجهة، فحص الاتصال الفعلي مع Firestore، إدارة العناقيد، وسجل التدقيق.'
                  : 'Theme/language preferences, live Firestore diagnostics, cluster config & audit logs.'}
              </p>
            </div>
          </div>

          <GlassButton
            variant="primary"
            onClick={handleRunHealthCheck}
            disabled={syncStatus === 'SYNCING'}
            icon={<RefreshCw className={`w-4 h-4 ${syncStatus === 'SYNCING' ? 'animate-spin' : ''}`} />}
            className="text-xs"
          >
            {syncStatus === 'SYNCING'
              ? language === 'ar' ? 'جاري الفحص...' : 'Checking...'
              : language === 'ar' ? 'فحص اتصال Firestore الحي' : 'Run Live Firestore Check'}
          </GlassButton>
        </div>
      </GlassCard>

      {feedback && (
        <div
          className={`p-3.5 rounded-xl border text-xs flex items-center gap-2 font-bold ${
            feedback.type === 'success'
              ? 'bg-emerald-500/10 border-emerald-500/30 text-emerald-400'
              : 'bg-red-500/10 border-red-500/30 text-red-400'
          }`}
        >
          {feedback.type === 'success' ? <CheckCircle className="w-4 h-4 shrink-0" /> : <AlertTriangle className="w-4 h-4 shrink-0" />}
          <span>{feedback.text}</span>
        </div>
      )}

      {/* User Preferences (Visible to all employees matching Android Settings screen) */}
      <GlassCard className="p-6">
        <h3 className="text-sm font-bold text-theme-primary mb-4">تفضيلات العرض وحساب الجلسة</h3>
        <div className="grid grid-cols-1 md:grid-cols-3 gap-4 text-xs">
          <div className="p-4 rounded-xl bg-theme-base border border-theme flex items-center justify-between gap-2">
            <div>
              <div className="font-bold text-theme-primary">المظهر (Theme)</div>
              <div className="text-theme-muted mt-0.5">{theme === 'true-black' ? 'الوضع الأسود الحقيقي OLED' : 'الوضع النهاري Daylight'}</div>
            </div>
            <GlassButton
              variant="secondary"
              onClick={toggleTheme}
              icon={theme === 'true-black' ? <Sun className="w-4 h-4 text-pmp-warning" /> : <Moon className="w-4 h-4" />}
              className="text-xs"
            >
              تبديل
            </GlassButton>
          </div>

          <div className="p-4 rounded-xl bg-theme-base border border-theme flex items-center justify-between gap-2">
            <div>
              <div className="font-bold text-theme-primary">لغة الواجهة</div>
              <div className="text-theme-muted mt-0.5">{language === 'ar' ? 'العربية (RTL)' : 'English (LTR)'}</div>
            </div>
            <GlassButton variant="secondary" onClick={toggleLanguage} icon={<Globe className="w-4 h-4" />} className="text-xs">
              {language === 'ar' ? 'EN' : 'عربي'}
            </GlassButton>
          </div>

          <div className="p-4 rounded-xl bg-theme-base border border-theme flex items-center justify-between gap-2">
            <div>
              <div className="font-bold text-theme-primary flex items-center gap-1">
                <Smartphone className="w-3.5 h-3.5 text-pmp-primary" />
                <span>إصدار تطبيق الأندرويد</span>
              </div>
              <div className="text-theme-muted mt-0.5 font-mono">
                {apkVersionInfo?.versionName ? `v${apkVersionInfo.versionName} (${apkVersionInfo.versionCode || ''})` : 'متاح من الموقع العام'}
              </div>
            </div>
            <GlassBadge variant="cyan">APK Sync</GlassBadge>
          </div>
        </div>
      </GlassCard>

      {/* Admin Cloud Server & Audit Log */}
      <div className="grid grid-cols-1 lg:grid-cols-12 gap-6">
        <div className="lg:col-span-6 space-y-6">
          <GlassCard className="p-6">
            <h3 className="text-sm font-bold text-theme-primary mb-4 flex items-center gap-2">
              <Server className="w-4 h-4 text-pmp-cyan" />
              <span>بيانات الخادم السحابي الموحد (Single Backend)</span>
            </h3>

            <div className="space-y-3 text-xs">
              <div className="p-3 rounded-xl bg-theme-base border border-theme flex justify-between items-center gap-2">
                <span className="text-theme-secondary">Firebase Project ID:</span>
                <span className="font-mono font-bold text-theme-primary">gen-lang-client-0220128746</span>
              </div>
              <div className="p-3 rounded-xl bg-theme-base border border-theme flex justify-between items-center gap-2">
                <span className="text-theme-secondary">Firestore Database ID:</span>
                <span className="font-mono font-bold text-theme-primary text-[11px] truncate max-w-[230px]">
                  ai-studio-android-glassdro-d6052420-cbc0-47dd-8cdd-8caa5e6a7248
                </span>
              </div>
              <div className="p-3 rounded-xl bg-theme-base border border-theme flex justify-between items-center gap-2">
                <span className="text-theme-secondary">Android Package ID:</span>
                <span className="font-mono font-bold text-pmp-primary">com.aistudio.pmppaints.erp</span>
              </div>
            </div>

            {healthReport && (
              <div className="mt-4 pt-4 border-t border-theme space-y-2 text-xs">
                <div className="flex justify-between font-bold">
                  <span>نتيجة الفحص الحي ({healthReport.latencyMs} ms):</span>
                  <span className={healthReport.ok ? 'text-emerald-400' : 'text-red-400'}>
                    {healthReport.ok ? 'متصل بنجاح' : 'فشل الاتصال'}
                  </span>
                </div>
                {healthReport.ok && (
                  <div className="grid grid-cols-2 sm:grid-cols-3 gap-2 pt-1">
                    {Object.entries(healthReport.counts).map(([col, count]) => (
                      <div key={col} className="p-2 rounded-lg bg-theme-base border border-theme flex justify-between font-mono text-[11px]">
                        <span className="text-theme-muted truncate">{col}</span>
                        <span className="font-bold text-theme-primary">{count}</span>
                      </div>
                    ))}
                  </div>
                )}
              </div>
            )}
          </GlassCard>

          {canManageSystemSettings && (
            <GlassCard className="p-6">
              <h3 className="text-sm font-bold text-theme-primary mb-3 flex items-center gap-2">
                <HardDrive className="w-4 h-4 text-pmp-gold" />
                <span>عنقود السيرفر السحابي (Cluster Selector)</span>
              </h3>
              <p className="text-xs text-theme-secondary mb-3">
                العنقود الرئيسي في Android هو {PRIMARY_CLUSTER} مع دعم النسخ الاحتياطي لعنقود {LEGACY_V16_CLUSTER}.
              </p>

              <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
                {[PRIMARY_CLUSTER, LEGACY_V16_CLUSTER].map((clusterCode) => {
                  const isSelected = activeCluster === clusterCode;
                  return (
                    <button
                      key={clusterCode}
                      type="button"
                      onClick={() => handleSwitchCluster(clusterCode)}
                      className={`p-3 rounded-xl border text-right transition-all ${
                        isSelected
                          ? 'bg-red-600/10 border-red-500 text-theme-primary'
                          : 'bg-theme-base border-theme text-theme-secondary hover:border-theme-hover'
                      }`}
                    >
                      <div className="flex justify-between items-center">
                        <span className="font-bold text-xs font-mono">{clusterCode}</span>
                        {isSelected && <CheckCircle className="w-4 h-4 text-emerald-400" />}
                      </div>
                      <span className="text-[10px] text-theme-muted block mt-1">
                        {clusterCode === PRIMARY_CLUSTER ? 'العنقود الرئيسي المعتمد في الأندرويد' : 'عنقود التوافق الراجع (Legacy V16)'}
                      </span>
                    </button>
                  );
                })}
              </div>
            </GlassCard>
          )}
        </div>

        {/* Live Audit Logs from `audit_logs` */}
        <div className="lg:col-span-6">
          <GlassCard className="p-6 h-full flex flex-col justify-between">
            <div>
              <h3 className="text-sm font-bold text-theme-primary mb-4 flex items-center gap-2">
                <Shield className="w-4 h-4 text-pmp-primary" />
                <span>سجل التدقيق والنشاط (audit_logs — {auditLogs.length})</span>
              </h3>
              <div className="space-y-2.5 max-h-[460px] overflow-y-auto pr-1">
                {auditLogs.length === 0 ? (
                  <div className="py-10 text-center text-xs text-theme-muted">
                    لا توجد قيود نشاط مسجلة في `audit_logs` لهذا العنقود حتى الآن.
                  </div>
                ) : (
                  auditLogs.slice(0, 40).map((log) => (
                    <div key={log.id} className="p-3 rounded-xl bg-theme-base border border-theme text-xs space-y-1">
                      <div className="flex justify-between items-center gap-2">
                        <span className="font-bold text-pmp-primary">{log.action}</span>
                        <span className="font-mono text-[10px] text-theme-muted">
                          {log.timestamp ? new Date(log.timestamp).toLocaleString('ar-EG') : ''}
                        </span>
                      </div>
                      <p className="text-theme-secondary text-[11px] leading-relaxed">{log.detail}</p>
                      <span className="text-[10px] text-theme-muted block font-mono">بواسطة: {log.username}</span>
                    </div>
                  ))
                )}
              </div>
            </div>

            <div className="pt-4 border-t border-theme mt-4 text-[11px] text-theme-muted">
              كافة سجلات التدقيق تُكتب مباشرة إلى `/erp_clusters/{activeCluster}/audit_logs` بالتنسيق المتوافق مع تطبيق الأندرويد.
            </div>
          </GlassCard>
        </div>
      </div>
    </div>
  );
};
