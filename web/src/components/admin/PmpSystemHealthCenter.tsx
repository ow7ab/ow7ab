import React, { useEffect, useState } from 'react';
import {
  Activity,
  AlertTriangle,
  CheckCircle,
  Download,
  FileText,
  Printer,
  RefreshCw,
  Shield,
  ShieldAlert,
  Wifi,
  WifiOff
} from 'lucide-react';
import { GlassCard } from '../ui/GlassCard';
import { GlassBadge } from '../ui/GlassBadge';
import { GlassButton } from '../ui/GlassButton';
import { useAuth } from '../../context/AuthContext';
import { auth } from '../../config/firebaseConfig';
import {
  BalanceAnomalyRecord,
  Cashbox,
  CloudErrorLogEntry,
  ComprehensiveDiagnosticReport,
  CrossPlatformReconciliationReport,
  ErpDataService,
  InvoicePayment,
  SalesInvoice,
  TreasuryMovement,
  UnifiedSyncSnapshot,
  UnifiedSyncStatus
} from '../../services/ErpDataService';
import {
  CloudErrorLogger,
  ServiceProbeId,
  ServiceProbeResult
} from '../../services/CloudErrorLogger';
import { ReportExporter } from '../../services/ReportExporter';

export function redactHealthTelemetry(raw: unknown): string {
  const txt = String(raw ?? '');
  return txt
    .replace(/Bearer\s+[A-Za-z0-9\-_.~+/]+=*/gi, 'Bearer [REDACTED]')
    .replace(/\beyJ[A-Za-z0-9\-_]+\.[A-Za-z0-9\-_]+\.[A-Za-z0-9\-_]+/g, '[JWT_REDACTED]')
    .replace(/AIza[0-9A-Za-z\-_]{20,}/g, '[API_KEY_REDACTED]')
    .replace(/(pinOrPassword|password|secret|idToken|refreshToken|accessToken)\s*[:=]\s*[^\s,;]+/gi, '$1=[REDACTED]');
}

function getSyncBadgeVariant(status: UnifiedSyncStatus): 'green' | 'gold' | 'red' | 'cyan' {
  switch (status) {
    case 'Synced':
      return 'green';
    case 'Syncing':
      return 'cyan';
    case 'Pending':
      return 'gold';
    case 'Failed':
    case 'Conflict Detected':
    case 'Offline':
      return 'red';
  }
}

export const PmpSystemHealthCenter: React.FC = () => {
  const { userProfile, isSuperAdmin } = useAuth();
  const [syncSnap, setSyncSnap] = useState<UnifiedSyncSnapshot>(() => ErpDataService.getUnifiedSyncSnapshot());
  const [cloudErrors, setCloudErrors] = useState<CloudErrorLogEntry[]>([]);
  const [diagReport, setDiagReport] = useState<ComprehensiveDiagnosticReport | null>(null);
  const [serviceProbes, setServiceProbes] = useState<Partial<Record<ServiceProbeId, ServiceProbeResult>>>({});
  const [runningDiagnostics, setRunningDiagnostics] = useState(false);

  const [localCashboxes, setLocalCashboxes] = useState<Cashbox[]>([]);
  const [localMovements, setLocalMovements] = useState<TreasuryMovement[]>([]);
  const [localInvoices, setLocalInvoices] = useState<SalesInvoice[]>([]);
  const [localPayments, setLocalPayments] = useState<InvoicePayment[]>([]);

  const [reconReport, setReconReport] = useState<CrossPlatformReconciliationReport | null>(null);
  const [runningRecon, setRunningRecon] = useState(false);
  const [remediatingId, setRemediationId] = useState<string | null>(null);
  const [notice, setNotice] = useState<{ type: 'ok' | 'err'; text: string } | null>(null);

  useEffect(() => {
    if (!isSuperAdmin) return;
    const unsubSync = ErpDataService.subscribeUnifiedSyncState(setSyncSnap);
    const unsubErr = CloudErrorLogger.subscribe(setCloudErrors);
    const unsubCb = ErpDataService.subscribeToCollection<Cashbox>('cashboxes', setLocalCashboxes);
    const unsubMv = ErpDataService.subscribeToCollection<TreasuryMovement>('movements', setLocalMovements);
    const unsubInv = ErpDataService.subscribeToCollection<SalesInvoice>('invoices', setLocalInvoices);
    const unsubPay = ErpDataService.subscribeToCollection<InvoicePayment>('invoice_payments', setLocalPayments);
    return () => {
      unsubSync();
      unsubErr();
      unsubCb();
      unsubMv();
      unsubInv();
      unsubPay();
    };
  }, [isSuperAdmin]);

  // Strict Admin-exclusive UI & telemetry barrier
  if (!isSuperAdmin) {
    return null;
  }

  const fbUser = auth.currentUser;
  const maskedUid = fbUser?.uid ? `${fbUser.uid.slice(0, 6)}...${fbUser.uid.slice(-4)}` : 'غير مسجل';
  const maskedEmail = fbUser?.email || userProfile?.phoneOrEmail || 'غير مرتبط';
  const runtimePlatform = ErpDataService.detectRuntimePlatform();

  const handleRunComprehensiveDiagnostics = async () => {
    setRunningDiagnostics(true);
    setNotice(null);
    try {
      const report = await ErpDataService.runComprehensiveDiagnostics(userProfile);
      setDiagReport(report);
      const map: Partial<Record<ServiceProbeId, ServiceProbeResult>> = {};
      report.probes.forEach((p) => {
        map[p.id] = p;
      });
      setServiceProbes(map);
      setNotice({
        type: report.overallOk ? 'ok' : 'err',
        text: report.overallOk
          ? `اكتمل فحص البنية السحابية (${report.totalLatencyMs} ms) — جميع الخدمات سليمة`
          : 'اكتمل الفحص مع وجود ملاحظات تتطلب مراجعة الأدمن'
      });
    } finally {
      setRunningDiagnostics(false);
    }
  };

  const handleRunReconciliation = async () => {
    setRunningRecon(true);
    setNotice(null);
    try {
      const res = await ErpDataService.runCrossPlatformBalanceReconciliation({
        localCashboxes,
        localMovements,
        localInvoices,
        localPayments,
        actorUsername: userProfile?.username || 'admin'
      });
      setReconReport(res);
      setNotice({
        type: res.overallStatus === 'MATCHED' ? 'ok' : 'err',
        text: res.summaryAr
      });
    } catch (err: any) {
      setNotice({
        type: 'err',
        text: redactHealthTelemetry(err?.message || 'تعذر تنفيذ مطابقة الأرصدة حالياً')
      });
    } finally {
      setRunningRecon(false);
    }
  };

  const handleApplySafeAdjustment = async (anomaly: BalanceAnomalyRecord) => {
    if (anomaly.safeRemediationAction !== 'POST_MISSING_GL_JOURNAL') {
      await handleRunReconciliation();
      return;
    }
    setRemediationId(anomaly.id);
    try {
      const entryUuid = await ErpDataService.applySafeReconciliationJournalAdjustment({
        anomaly,
        actorUsername: userProfile?.username || 'admin',
        actorProfile: userProfile
      });
      setNotice({
        type: 'ok',
        text: `تم إنشاء قيد التسوية الآمن من واقع دفتر الحركات (${entryUuid}). جارٍ إعادة المطابقة...`
      });
      await handleRunReconciliation();
    } catch (err: any) {
      setNotice({
        type: 'err',
        text: redactHealthTelemetry(err?.message || 'تعذر تنفيذ إجراء التسوية')
      });
    } finally {
      setRemediationId(null);
    }
  };

  const buildHealthExportRows = (): { headers: string[]; rows: Array<(string | number)[]> } => {
    const headers = ['القسم', 'البند', 'الحالة / القيمة', 'التوقيت / المرجع', 'التفاصيل والمصدر'];
    const rows: Array<(string | number)[]> = [
      ['المصادقة والجلسة', 'Firebase Auth UID', maskedUid, fbUser?.emailVerified ? 'Email Verified' : 'Unverified/Local', `المنصة: ${runtimePlatform} | المستخدم: ${userProfile?.username || 'admin'}`],
      ['حالة المزامنة (6 حالات)', syncSnap.status, syncSnap.labelAr, syncSnap.lastServerAckAt ? new Date(syncSnap.lastServerAckAt).toLocaleString('ar-EG') : 'لا يوجد تأكيد خادم بعد', `معلق: ${syncSnap.pendingWritesCount} | أخطاء: ${syncSnap.failedCount} | تعارض: ${syncSnap.conflictCount}`]
    ];

    if (reconReport) {
      (['SDG', 'EGP', 'USD'] as const).forEach((cur) => {
        rows.push([
          'مطابقة الأرصدة',
          `إجمالي العملة (${cur})`,
          `المركزي: ${(reconReport.centralCurrencyTotals[cur] || 0).toLocaleString()} | المحلي: ${(reconReport.localCurrencyTotals[cur] || 0).toLocaleString()} | دفتر الأستاذ: ${(reconReport.ledgerCurrencyTotals[cur] || 0).toLocaleString()}`,
          reconReport.lastSyncedTransactionRef,
          `GL النقدية: ${(reconReport.glCashTotals[cur] || 0).toLocaleString()} | ذمم العملاء: ${(reconReport.centralArTotals[cur] || 0).toLocaleString()}`
        ]);
      });
      reconReport.anomalies.forEach((a) => {
        rows.push([
          'تباين مكتشف',
          a.affectedAccount,
          `فرق: ${a.varianceAmount.toLocaleString()} ${a.currency}`,
          `${new Date(a.detectedAtMillis).toLocaleString('ar-EG')} | آخر متزامن: ${a.lastSyncedTransactionRef}`,
          redactHealthTelemetry(`${a.rootCauseSource}: ${a.rootCauseDetailAr} -> ${a.safeRemediationDescriptionAr}`)
        ]);
      });
    }

    (Object.values(serviceProbes).filter(Boolean) as ServiceProbeResult[]).forEach((p) => {
      rows.push([
        'فحص الخادم',
        p.titleAr,
        p.ok ? `سليم (${p.latencyMs}ms)` : `فشل (${p.latencyMs}ms)`,
        new Date(p.checkedAtMillis).toLocaleString('ar-EG'),
        redactHealthTelemetry(p.summaryAr)
      ]);
    });

    cloudErrors.slice(0, 25).forEach((e) => {
      rows.push([
        'سجل الأخطاء المحجوب',
        `${e.categoryTitleAr} (${e.operation})`,
        redactHealthTelemetry(e.path),
        new Date(e.timestamp).toLocaleString('ar-EG'),
        redactHealthTelemetry(e.sanitizedMessage)
      ]);
    });

    return { headers, rows };
  };

  const handleExportPdf = () => {
    const { headers, rows } = buildHealthExportRows();
    ReportExporter.exportToPrint(
      'PMP SYSTEM HEALTH CENTER — تقرير صحة النظام ومطابقة الأرصدة',
      `تم الإنشاء حصرياً لمدير النظام (${userProfile?.username || 'admin'}) | حالة المزامنة: ${syncSnap.status} | المنصة: ${runtimePlatform}`,
      headers,
      rows
    );
  };

  const handleExportExcel = () => {
    const { headers, rows } = buildHealthExportRows();
    ReportExporter.exportToCsv('pmp_system_health_center_report', headers, rows);
  };

  return (
    <GlassCard className="p-6 space-y-5 border-2 border-red-500/30" data-testid="pmp-system-health-center">
      <div className="flex flex-col lg:flex-row justify-between items-start lg:items-center gap-4 border-b border-theme pb-4">
        <div className="flex items-start gap-3">
          <div className="w-11 h-11 rounded-2xl bg-red-600/15 border border-red-500/30 flex items-center justify-center shrink-0">
            <Shield className="w-5 h-5 text-red-500" />
          </div>
          <div>
            <div className="flex flex-wrap items-center gap-2">
              <h3 className="text-base font-black text-theme-primary tracking-wide">
                PMP SYSTEM HEALTH CENTER
              </h3>
              <GlassBadge variant="red">Admin Only</GlassBadge>
              <GlassBadge variant={getSyncBadgeVariant(syncSnap.status)}>
                {syncSnap.status} — {syncSnap.labelAr}
              </GlassBadge>
            </div>
            <p className="text-xs text-theme-secondary mt-1">
              مركز المراقبة الموحد للـ Admin فقط: مصادقة Firebase، طوابير المزامنة، مطابقة الأرصدة بين Web وDesktop وAndroid ودفتر الأستاذ، بدون أي تسريب لكلمات المرور أو Tokens.
            </p>
          </div>
        </div>

        <div className="flex flex-wrap gap-2">
          <GlassButton
            variant="primary"
            onClick={handleRunReconciliation}
            disabled={runningRecon}
            icon={<RefreshCw className={`w-3.5 h-3.5 ${runningRecon ? 'animate-spin' : ''}`} />}
            className="text-xs"
          >
            {runningRecon ? 'جاري المطابقة...' : 'مطابقة الأرصدة عبر المنصات'}
          </GlassButton>
          <GlassButton
            variant="secondary"
            onClick={handleRunComprehensiveDiagnostics}
            disabled={runningDiagnostics}
            icon={<Activity className="w-3.5 h-3.5" />}
            className="text-xs"
          >
            {runningDiagnostics ? 'فحص...' : 'فحص الخادم والشبكة'}
          </GlassButton>
          <GlassButton
            variant="secondary"
            onClick={handleExportPdf}
            icon={<Printer className="w-3.5 h-3.5" />}
            className="text-xs"
          >
            تصدير PDF
          </GlassButton>
          <GlassButton
            variant="secondary"
            onClick={handleExportExcel}
            icon={<Download className="w-3.5 h-3.5" />}
            className="text-xs"
          >
            تصدير Excel
          </GlassButton>
        </div>
      </div>

      {notice && (
        <div
          className={`p-3 rounded-xl border text-xs font-bold flex items-center gap-2 ${
            notice.type === 'ok'
              ? 'bg-emerald-500/10 border-emerald-500/30 text-emerald-400'
              : 'bg-red-500/10 border-red-500/30 text-red-400'
          }`}
        >
          {notice.type === 'ok' ? <CheckCircle className="w-4 h-4 shrink-0" /> : <AlertTriangle className="w-4 h-4 shrink-0" />}
          <span>{notice.text}</span>
        </div>
      )}

      {/* Auth / Session / 6-State Sync Grid */}
      <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-3 text-xs">
        <div className="p-3.5 rounded-xl bg-theme-base border border-theme space-y-1">
          <div className="text-theme-muted font-bold">حالة Firebase Auth</div>
          <div className="font-mono text-theme-primary font-bold">{maskedUid}</div>
          <div className="text-[11px] text-theme-secondary truncate">{redactHealthTelemetry(maskedEmail)}</div>
        </div>
        <div className="p-3.5 rounded-xl bg-theme-base border border-theme space-y-1">
          <div className="text-theme-muted font-bold flex items-center gap-1.5">
            {syncSnap.online ? <Wifi className="w-3.5 h-3.5 text-emerald-400" /> : <WifiOff className="w-3.5 h-3.5 text-red-400" />}
            <span>حالة الاتصال والمزامنة</span>
          </div>
          <div className="font-bold text-theme-primary">{syncSnap.status}</div>
          <div className="text-[11px] text-theme-secondary">
            معلق: {syncSnap.pendingWritesCount} | فاشل: {syncSnap.failedCount} | تعارض: {syncSnap.conflictCount}
          </div>
        </div>
        <div className="p-3.5 rounded-xl bg-theme-base border border-theme space-y-1">
          <div className="text-theme-muted font-bold">الجلسة النشطة</div>
          <div className="font-bold text-theme-primary">{userProfile?.username || 'admin'} ({runtimePlatform})</div>
          <div className="text-[11px] text-emerald-400">مختومة بـ HMAC + Tokens محجوبة</div>
        </div>
        <div className="p-3.5 rounded-xl bg-theme-base border border-theme space-y-1">
          <div className="text-theme-muted font-bold">أخطاء الخادم المرصودة</div>
          <div className="font-bold text-theme-primary">{cloudErrors.length} سجل مصنف</div>
          <div className="text-[11px] text-theme-secondary">
            آخر تأكيد خادم: {syncSnap.lastServerAckAt ? new Date(syncSnap.lastServerAckAt).toLocaleTimeString('ar-EG') : 'قيد الانتظار'}
          </div>
        </div>
      </div>

      {/* Cross-Platform Reconciliation Matrix */}
      {reconReport && (
        <div className="space-y-3 pt-2">
          <div className="flex flex-wrap items-center justify-between gap-2">
            <h4 className="text-xs font-extrabold text-theme-primary flex items-center gap-2">
              <FileText className="w-4 h-4 text-pmp-cyan" />
              <span>مصفوفة مطابقة الأرصدة بين القاعدة المركزية والمنصات الثلاث ودفتر الأستاذ</span>
            </h4>
            <GlassBadge variant={reconReport.overallStatus === 'MATCHED' ? 'green' : 'red'}>
              {reconReport.overallStatus === 'MATCHED' ? 'متطابق بالكامل 100%' : `${reconReport.anomalies.length} تباين مكتشف`}
            </GlassBadge>
          </div>

          <div className="overflow-x-auto rounded-xl border border-theme">
            <table className="w-full text-xs text-right">
              <thead className="bg-theme-base text-theme-muted">
                <tr>
                  <th className="p-2.5">العملة</th>
                  <th className="p-2.5">السيرفر المركزي</th>
                  <th className="p-2.5">النسخة الحالية ({runtimePlatform})</th>
                  <th className="p-2.5">مجموع دفتر الحركات</th>
                  <th className="p-2.5">دفتر الأستاذ (GL Cash)</th>
                  <th className="p-2.5">Checkpoint Android</th>
                  <th className="p-2.5">Checkpoint Desktop</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-theme">
                {(['SDG', 'EGP', 'USD'] as const).map((cur) => (
                  <tr key={cur} className="hover:bg-theme-base/50">
                    <td className="p-2.5 font-black">{cur}</td>
                    <td className="p-2.5 font-mono">{(reconReport.centralCurrencyTotals[cur] || 0).toLocaleString()}</td>
                    <td className="p-2.5 font-mono">{(reconReport.localCurrencyTotals[cur] || 0).toLocaleString()}</td>
                    <td className="p-2.5 font-mono text-emerald-400">{(reconReport.ledgerCurrencyTotals[cur] || 0).toLocaleString()}</td>
                    <td className="p-2.5 font-mono">{(reconReport.glCashTotals[cur] || 0).toLocaleString()}</td>
                    <td className="p-2.5 font-mono">
                      {reconReport.checkpoints.android
                        ? Number(reconReport.checkpoints.android.currencyTotals?.[cur] || 0).toLocaleString()
                        : 'لم يرسل بعد'}
                    </td>
                    <td className="p-2.5 font-mono">
                      {reconReport.checkpoints.desktop
                        ? Number(reconReport.checkpoints.desktop.currencyTotals?.[cur] || 0).toLocaleString()
                        : 'لم يرسل بعد'}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>

          {reconReport.anomalies.length > 0 && (
            <div className="space-y-2">
              {reconReport.anomalies.map((anom) => (
                <div key={anom.id} className="p-3 rounded-xl bg-red-500/10 border border-red-500/30 flex flex-col sm:flex-row justify-between items-start sm:items-center gap-3 text-xs">
                  <div className="space-y-1">
                    <div className="font-bold text-red-400 flex items-center gap-1.5">
                      <ShieldAlert className="w-4 h-4" />
                      <span>{anom.affectedAccount} — فرق بقيمة {anom.varianceAmount.toLocaleString()} {anom.currency}</span>
                    </div>
                    <div className="text-[11px] text-theme-secondary">
                      المصدر: {anom.rootCauseSource} • وقت الرصد: {new Date(anom.detectedAtMillis).toLocaleString('ar-EG')} • آخر عملية متزامنة: {anom.lastSyncedTransactionRef}
                    </div>
                    <div className="text-[11px] text-theme-primary">{anom.safeRemediationDescriptionAr}</div>
                  </div>
                  <GlassButton
                    variant="secondary"
                    onClick={() => handleApplySafeAdjustment(anom)}
                    disabled={remediatingId === anom.id}
                    className="text-[11px] shrink-0"
                  >
                    {anom.safeRemediationAction === 'POST_MISSING_GL_JOURNAL'
                      ? 'تسوية محاسبية آمنة من دفتر الحركات'
                      : 'إعادة مطابقة وتحديث من دفتر الحركات'}
                  </GlassButton>
                </div>
              ))}
            </div>
          )}
        </div>
      )}

      {diagReport && (
        <div className="text-[11px] text-theme-muted border-t border-theme pt-2 flex justify-between">
          <span>فحص الخدمات السحابية: {diagReport.probes.filter((p) => p.ok).length}/{diagReport.probes.length} سليمة</span>
          <span className="font-mono">{diagReport.totalLatencyMs} ms</span>
        </div>
      )}
    </GlassCard>
  );
};
