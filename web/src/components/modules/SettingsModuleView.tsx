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
  ClusterHealthReport
} from '../../services/ErpDataService';
import { GRANULAR_ACTIONS } from '../../services/SecurityService';
import { fetchReleaseFeed } from '../../services/ReleaseService';
import { AuditLog } from '../../types/erp';
import {
  getDesktopBridge,
  DesktopAppInfo,
  DesktopPreferences,
  DesktopUpdateState
} from '../../utils/desktopBridge';

const COLLECTION_ARABIC_LABELS: Record<string, string> = {
  user_accounts: 'حسابات الموظفين',
  cashboxes: 'الخزن النقدية',
  movements: 'الحركات المالية',
  warehouses: 'المخازن والفروع',
  products: 'الأصناف والمنتجات',
  customers: 'حسابات العملاء',
  invoices: 'فواتير المبيعات'
};

export const SettingsModuleView: React.FC = () => {
  const { language, toggleLanguage } = useLanguage();
  const { theme, toggleTheme } = useTheme();
  const { userProfile, isSuperAdmin, canPerformAction } = useAuth();

  const canManageSystemSettings = isSuperAdmin || canPerformAction(GRANULAR_ACTIONS.ADMIN_SETTINGS);

  const [auditLogs, setAuditLogs] = useState<AuditLog[]>([]);
  const [syncStatus, setSyncStatus] = useState<'IDLE' | 'SYNCING' | 'SUCCESS' | 'ERROR'>('IDLE');
  const [healthReport, setHealthReport] = useState<ClusterHealthReport | null>(null);
  const [feedback, setFeedback] = useState<{ type: 'success' | 'error'; text: string } | null>(null);
  const [apkVersionInfo, setApkVersionInfo] = useState<{ versionName?: string; versionCode?: number } | null>(null);

  const desktopBridge = getDesktopBridge();
  const [desktopInfo, setDesktopInfo] = useState<DesktopAppInfo | null>(null);
  const [desktopPrefs, setDesktopPrefs] = useState<DesktopPreferences>({
    openAtLogin: false,
    minimizeToTray: false,
    confirmBeforeExit: false
  });
  const [desktopUpdate, setDesktopUpdate] = useState<DesktopUpdateState | null>(null);

  useEffect(() => {
    const unsubAudit = ErpDataService.subscribeToCollection<AuditLog>('audit_logs', (logs) => {
      setAuditLogs([...logs].sort((a, b) => (b.timestamp || 0) - (a.timestamp || 0)));
    });
    fetchReleaseFeed()
      .then((res) => {
        if (res.latest) {
          setApkVersionInfo({
            versionName: res.latest.versionName,
            versionCode: res.latest.versionCode
          });
        }
      })
      .catch(() => {});

    let unsubDesktopUpd: (() => void) | undefined;
    if (desktopBridge) {
      desktopBridge.getAppInfo().then((info) => {
        setDesktopInfo(info);
        setDesktopUpdate(info.updateState);
      }).catch(() => {});
      desktopBridge.getPreferences().then(setDesktopPrefs).catch(() => {});
      unsubDesktopUpd = desktopBridge.onUpdateStatus(setDesktopUpdate);
    }

    return () => {
      unsubAudit();
      if (unsubDesktopUpd) unsubDesktopUpd();
    };
  }, []);

  const handleRunHealthCheck = async () => {
    setSyncStatus('SYNCING');
    const report = await ErpDataService.checkServerHealth();
    setHealthReport(report);
    if (report.ok) {
      setSyncStatus('SUCCESS');
      setFeedback({
        type: 'success',
        text: `الاتصال السحابي سليم ومستقر (${report.latencyMs} مللي ثانية)`
      });
    } else {
      setSyncStatus('ERROR');
      setFeedback({
        type: 'error',
        text: report.error || 'تعذر التحقق من الاتصال السحابي حالياً'
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
                <GlassBadge variant="green">
                  {language === 'ar' ? 'متصل ومحدث لحظياً' : 'Connected'}
                </GlassBadge>
              </div>
              <p className="text-xs text-theme-secondary mt-1">
                {language === 'ar'
                  ? 'تفضيلات العرض، فحص سرعة الاتصال والمزامنة بين الفروع، وسجل النشاط الإداري.'
                  : 'Display preferences, live synchronization check across branches, and activity logs.'}
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
              : language === 'ar' ? 'فحص حالة الاتصال والمزامنة' : 'Check Sync Connection'}
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

      {/* User Preferences */}
      <GlassCard className="p-6">
        <h3 className="text-sm font-bold text-theme-primary mb-4">تفضيلات العرض وحساب الجلسة</h3>
        <div className="grid grid-cols-1 md:grid-cols-3 gap-4 text-xs">
          <div className="p-4 rounded-xl bg-theme-base border border-theme flex items-center justify-between gap-2">
            <div>
              <div className="font-bold text-theme-primary">المظهر</div>
              <div className="text-theme-muted mt-0.5">{theme === 'true-black' ? 'الوضع الليلي الداكن' : 'الوضع النهاري الفاتح'}</div>
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
              <div className="text-theme-muted mt-0.5">{language === 'ar' ? 'العربية' : 'English'}</div>
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
                {apkVersionInfo?.versionName ? `${apkVersionInfo.versionName}` : 'الإصدار 18.0 المعتمد'}
              </div>
            </div>
            <GlassBadge variant="cyan">محدث</GlassBadge>
          </div>
        </div>
      </GlassCard>

      {desktopBridge && (
        <GlassCard className="p-6 border-pmp-primary/30 space-y-4">
          <div className="flex flex-col sm:flex-row justify-between items-start sm:items-center gap-3">
            <div>
              <div className="flex items-center gap-2 flex-wrap">
                <h3 className="text-sm font-black text-theme-primary">
                  {language === 'ar'
                    ? 'تطبيق سطح المكتب (Windows Desktop) والتحديثات التلقائية'
                    : 'Windows Desktop Edition & Auto-Updates'}
                </h3>
                <GlassBadge variant="green">
                  v{desktopInfo?.version || desktopUpdate?.currentVersion || '18.0.0'}
                </GlassBadge>
              </div>
              <p className="text-xs text-theme-secondary mt-1">
                {language === 'ar'
                  ? 'قناة تحديثات سطح المكتب مستقلة تماماً عن ملفات APK الخاصة بالأندرويد، وتحفظ إعداداتك تلقائياً.'
                  : 'Dedicated desktop release channel (separate from Android APK updates) preserving local user preferences.'}
              </p>
            </div>

            <div className="flex items-center gap-2 shrink-0 flex-wrap">
              <GlassButton
                variant="secondary"
                onClick={() => void desktopBridge.checkForDesktopUpdates()}
                disabled={desktopUpdate?.status === 'checking' || desktopUpdate?.status === 'downloading'}
                icon={<RefreshCw className={`w-4 h-4 ${desktopUpdate?.status === 'checking' ? 'animate-spin' : ''}`} />}
                className="text-xs"
              >
                {desktopUpdate?.status === 'checking'
                  ? 'جاري الفحص...'
                  : language === 'ar'
                  ? 'فحص تحديثات سطح المكتب'
                  : 'Check Desktop Updates'}
              </GlassButton>

              {desktopUpdate?.status === 'available' && (
                <GlassButton
                  variant="primary"
                  onClick={() => void desktopBridge.downloadDesktopUpdate()}
                  className="text-xs"
                >
                  تنزيل التحديث (v{desktopUpdate.availableVersion})
                </GlassButton>
              )}

              {desktopUpdate?.status === 'downloaded' && (
                <GlassButton
                  variant="primary"
                  onClick={() => void desktopBridge.installDesktopUpdateAndRestart()}
                  className="text-xs"
                >
                  تثبيت وإعادة التشغيل الآن
                </GlassButton>
              )}
            </div>
          </div>

          {desktopUpdate?.message && (
            <div className="p-3 rounded-xl bg-theme-base border border-theme text-xs flex flex-col gap-1.5">
              <div className="flex items-center justify-between gap-2">
                <span className="font-bold text-theme-primary">{desktopUpdate.message}</span>
                {desktopUpdate.status === 'downloading' && (
                  <span className="font-mono font-bold text-pmp-cyan">{desktopUpdate.progressPercent}%</span>
                )}
              </div>
              {desktopUpdate.status === 'downloading' && (
                <div className="w-full h-1.5 rounded-full bg-theme-surface overflow-hidden">
                  <div
                    className="h-full bg-pmp-primary transition-all"
                    style={{ width: `${desktopUpdate.progressPercent}%` }}
                  />
                </div>
              )}
              {desktopUpdate.releaseNotes && (
                <p className="text-[11px] text-theme-muted whitespace-pre-line">{desktopUpdate.releaseNotes}</p>
              )}
            </div>
          )}

          <div className="grid grid-cols-1 md:grid-cols-3 gap-4 text-xs">
            <label className="p-3.5 rounded-xl bg-theme-base border border-theme flex items-center justify-between gap-2 cursor-pointer">
              <div>
                <div className="font-bold text-theme-primary">تشغيل تلقائي مع Windows</div>
                <div className="text-[11px] text-theme-muted mt-0.5">فتح المنظومة عند بدء تشغيل الجهاز</div>
              </div>
              <input
                type="checkbox"
                checked={desktopPrefs.openAtLogin}
                onChange={(e) => {
                  const nextVal = e.target.checked;
                  void desktopBridge.setPreferences({ openAtLogin: nextVal }).then(setDesktopPrefs);
                }}
                className="w-4 h-4 accent-red-600"
              />
            </label>

            <label className="p-3.5 rounded-xl bg-theme-base border border-theme flex items-center justify-between gap-2 cursor-pointer">
              <div>
                <div className="font-bold text-theme-primary">التصغير لشريط النظام (Tray)</div>
                <div className="text-[11px] text-theme-muted mt-0.5">إبقاء التطبيق في الخلفية عند الإغلاق</div>
              </div>
              <input
                type="checkbox"
                checked={desktopPrefs.minimizeToTray}
                onChange={(e) => {
                  const nextVal = e.target.checked;
                  void desktopBridge.setPreferences({ minimizeToTray: nextVal }).then(setDesktopPrefs);
                }}
                className="w-4 h-4 accent-red-600"
              />
            </label>

            <div className="p-3.5 rounded-xl bg-theme-base border border-theme flex items-center justify-between gap-2">
              <div>
                <div className="font-bold text-theme-primary">حفظ مقاس وموقع النافذة</div>
                <div className="text-[11px] text-theme-muted mt-0.5">يُستعاد تلقائياً عند كل تشغيل</div>
              </div>
              <GlassBadge variant="green">مفعّل</GlassBadge>
            </div>
          </div>
        </GlassCard>
      )}

      {/* Admin Sync Overview & Activity Log */}
      <div className="grid grid-cols-1 lg:grid-cols-12 gap-6">
        <div className="lg:col-span-6 space-y-6">
          <GlassCard className="p-6">
            <h3 className="text-sm font-bold text-theme-primary mb-4 flex items-center gap-2">
              <Server className="w-4 h-4 text-pmp-cyan" />
              <span>حالة الاتصال والمزامنة بين الفروع</span>
            </h3>

            <div className="space-y-3 text-xs">
              <div className="p-3 rounded-xl bg-theme-base border border-theme flex justify-between items-center gap-2">
                <span className="text-theme-secondary">حالة الربط مع تطبيق الأندرويد:</span>
                <span className="font-bold text-emerald-400">متصل ومزامن لحظياً</span>
              </div>
              <div className="p-3 rounded-xl bg-theme-base border border-theme flex justify-between items-center gap-2">
                <span className="text-theme-secondary">الفرع النشط للمستخدم:</span>
                <span className="font-bold text-theme-primary">{userProfile?.branch || 'الإدارة العامة'}</span>
              </div>
              <div className="p-3 rounded-xl bg-theme-base border border-theme flex justify-between items-center gap-2">
                <span className="text-theme-secondary">الصفة الوظيفية:</span>
                <span className="font-bold text-pmp-primary">{userProfile?.role || 'موظف'}</span>
              </div>
            </div>

            {healthReport && (
              <div className="mt-4 pt-4 border-t border-theme space-y-2 text-xs">
                <div className="flex justify-between font-bold">
                  <span>سرعة استجابة المزامنة ({healthReport.latencyMs} مللي ثانية):</span>
                  <span className={healthReport.ok ? 'text-emerald-400' : 'text-red-400'}>
                    {healthReport.ok ? 'متصل بنجاح' : 'تعذر الاتصال'}
                  </span>
                </div>
                {healthReport.ok && (
                  <div className="grid grid-cols-2 sm:grid-cols-3 gap-2 pt-1">
                    {Object.entries(healthReport.counts).map(([col, count]) => (
                      <div key={col} className="p-2 rounded-lg bg-theme-base border border-theme flex justify-between text-[11px]">
                        <span className="text-theme-muted truncate">{COLLECTION_ARABIC_LABELS[col] || col}</span>
                        <span className="font-bold text-theme-primary font-mono">{count}</span>
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
                <span>بيئة المزامنة الموحدة</span>
              </h3>
              <div className="p-3.5 rounded-xl bg-red-600/10 border border-red-500/40 text-right">
                <div className="flex justify-between items-center">
                  <span className="font-bold text-xs text-theme-primary">البيئة الرئيسية الموحدة (تطبيق الأندرويد والويب)</span>
                  <CheckCircle className="w-4 h-4 text-emerald-400" />
                </div>
                <span className="text-[11px] text-theme-secondary block mt-1">
                  جميع فروع الخرطوم والقاهرة وحلفا والمخازن متصلة وموحدة لحظياً على قاعدة بيانات واحدة معتمدة.
                </span>
              </div>
            </GlassCard>
          )}
        </div>

        {/* Live Activity Logs */}
        <div className="lg:col-span-6">
          <GlassCard className="p-6 h-full flex flex-col justify-between">
            <div>
              <h3 className="text-sm font-bold text-theme-primary mb-4 flex items-center gap-2">
                <Shield className="w-4 h-4 text-pmp-primary" />
                <span>سجل النشاط والعمليات الإدارية ({auditLogs.length})</span>
              </h3>
              <div className="space-y-2.5 max-h-[460px] overflow-y-auto pr-1">
                {auditLogs.length === 0 ? (
                  <div className="py-10 text-center text-xs text-theme-muted">
                    لا توجد عمليات مسجلة في سجل النشاط حتى الآن.
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
                      <span className="text-[10px] text-theme-muted block">بواسطة: {log.username}</span>
                    </div>
                  ))
                )}
              </div>
            </div>

            <div className="pt-4 border-t border-theme mt-4 text-[11px] text-theme-muted">
              تُحفظ كافة العمليات الإدارية والمالية تلقائياً وتتم مزامنتها فوراً مع تطبيق الأندرويد.
            </div>
          </GlassCard>
        </div>
      </div>
    </div>
  );
};
