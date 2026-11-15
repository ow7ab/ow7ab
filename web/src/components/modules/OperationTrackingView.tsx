import React, { useEffect, useMemo, useState } from 'react';
import { GlassCard } from '../ui/GlassCard';
import { GlassButton } from '../ui/GlassButton';
import { ErpDataService } from '../../services/ErpDataService';
import {
  IdempotencyProtectionSystem,
  IdempotencyRecord,
  IdempotencyRequestStatus,
  OPERATION_TYPE_LABELS_AR,
  REQUEST_STATUS_LABELS_AR
} from '../../services/IdempotencyService';
import {
  Activity,
  AlertTriangle,
  CheckCircle2,
  Clock,
  RefreshCw,
  Search,
  ShieldCheck,
  XCircle
} from 'lucide-react';

export const OperationTrackingView: React.FC = () => {
  const [localRecords, setLocalRecords] = useState<IdempotencyRecord[]>(() =>
    IdempotencyProtectionSystem.getRecords()
  );
  const [cloudRecords, setCloudRecords] = useState<IdempotencyRecord[]>([]);
  const [searchTerm, setSearchTerm] = useState('');
  const [statusFilter, setStatusFilter] = useState<'ALL' | IdempotencyRequestStatus>('ALL');
  const [verifyingKey, setVerifyingKey] = useState<string | null>(null);
  const [isRecoveringAll, setIsRecoveringAll] = useState(false);
  const [statusBanner, setStatusBanner] = useState<string | null>(null);

  useEffect(() => {
    const unsubLocal = IdempotencyProtectionSystem.subscribe(() => {
      setLocalRecords(IdempotencyProtectionSystem.getRecords());
    });
    const unsubCloud = ErpDataService.subscribeToCollection<IdempotencyRecord>(
      'idempotency_requests',
      (rows) => setCloudRecords(rows || []),
      () => {}
    );
    return () => {
      unsubLocal();
      unsubCloud();
    };
  }, []);

  const mergedRecords = useMemo(() => {
    const byKey = new Map<string, IdempotencyRecord>();
    for (const r of cloudRecords) {
      if (r.idempotencyKey) byKey.set(r.idempotencyKey, r);
    }
    for (const r of localRecords) {
      if (!r.idempotencyKey) continue;
      const existing = byKey.get(r.idempotencyKey);
      if (!existing || (r.updatedAt || r.createdAt) >= (existing.updatedAt || existing.createdAt)) {
        byKey.set(r.idempotencyKey, r);
      }
    }
    return Array.from(byKey.values()).sort(
      (a, b) => (b.updatedAt || b.createdAt || 0) - (a.updatedAt || a.createdAt || 0)
    );
  }, [localRecords, cloudRecords]);

  const counts = useMemo(() => {
    return {
      total: mergedRecords.length,
      completed: mergedRecords.filter((r) => r.requestStatus === 'COMPLETED').length,
      pending: mergedRecords.filter(
        (r) => r.requestStatus === 'PROCESSING' || r.requestStatus === 'PENDING_CONFIRMATION'
      ).length,
      failed: mergedRecords.filter((r) => r.requestStatus === 'FAILED').length
    };
  }, [mergedRecords]);

  const filtered = useMemo(() => {
    const q = searchTerm.trim().toLowerCase();
    return mergedRecords.filter((r) => {
      if (statusFilter !== 'ALL' && r.requestStatus !== statusFilter) return false;
      if (!q) return true;
      return (
        String(r.idempotencyKey || '').toLowerCase().includes(q) ||
        String(r.documentReference || '').toLowerCase().includes(q) ||
        String(r.resultReference || '').toLowerCase().includes(q) ||
        String(r.userId || '').toLowerCase().includes(q) ||
        String(r.payloadSummary || '').toLowerCase().includes(q)
      );
    });
  }, [mergedRecords, searchTerm, statusFilter]);

  const handleVerifyOne = async (key: string) => {
    if (verifyingKey) return;
    setVerifyingKey(key);
    setStatusBanner('الاتصال بطيء، يتم التحقق من حالة العملية...');
    try {
      const updated = await IdempotencyProtectionSystem.verifyOperationStatus(key);
      if (!updated) {
        setStatusBanner('تعذر الوصول لسجل العملية في الخادم.');
      } else if (updated.requestStatus === 'COMPLETED') {
        setStatusBanner(`العملية مسجلة مسبقاً ومكتملة بنجاح (${updated.resultReference || key}).`);
      } else if (updated.requestStatus === 'FAILED') {
        setStatusBanner('تم التأكد من الخادم أن العملية لم تُنفذ؛ يمكنك إعادة المحاولة من الشاشة المختصة بأمان.');
      } else {
        setStatusBanner('لم يتم التأكد من نتيجة العملية بعد، لا تعِد إدخالها.');
      }
    } finally {
      setVerifyingKey(null);
    }
  };

  const handleRecoverAll = async () => {
    if (isRecoveringAll) return;
    setIsRecoveringAll(true);
    setStatusBanner('جاري التحقق من جميع العمليات المعلقة مع الخادم...');
    try {
      const res = await IdempotencyProtectionSystem.recoverPendingOperations();
      setStatusBanner(
        `تم فحص ${res.checked} عملية معلقة — مؤكدة: ${res.completedCount} | لا تزال بانتظار التأكيد: ${res.pendingCount}`
      );
    } finally {
      setIsRecoveringAll(false);
    }
  };

  const statusBadge = (status: IdempotencyRequestStatus) => {
    switch (status) {
      case 'COMPLETED':
        return (
          <span className="inline-flex items-center gap-1 px-2.5 py-0.5 rounded-full text-xs font-bold bg-emerald-500/15 text-emerald-300 border border-emerald-500/30">
            <CheckCircle2 className="w-3.5 h-3.5" />
            {REQUEST_STATUS_LABELS_AR.COMPLETED}
          </span>
        );
      case 'PENDING_CONFIRMATION':
        return (
          <span className="inline-flex items-center gap-1 px-2.5 py-0.5 rounded-full text-xs font-bold bg-amber-500/15 text-amber-300 border border-amber-500/30">
            <AlertTriangle className="w-3.5 h-3.5" />
            {REQUEST_STATUS_LABELS_AR.PENDING_CONFIRMATION}
          </span>
        );
      case 'PROCESSING':
        return (
          <span className="inline-flex items-center gap-1 px-2.5 py-0.5 rounded-full text-xs font-bold bg-cyan-500/15 text-cyan-300 border border-cyan-500/30">
            <Clock className="w-3.5 h-3.5" />
            {REQUEST_STATUS_LABELS_AR.PROCESSING}
          </span>
        );
      case 'FAILED':
        return (
          <span className="inline-flex items-center gap-1 px-2.5 py-0.5 rounded-full text-xs font-bold bg-red-500/15 text-red-300 border border-red-500/30">
            <XCircle className="w-3.5 h-3.5" />
            {REQUEST_STATUS_LABELS_AR.FAILED}
          </span>
        );
      default:
        return (
          <span className="inline-flex items-center gap-1 px-2.5 py-0.5 rounded-full text-xs font-bold bg-slate-500/15 text-slate-300 border border-slate-500/30">
            {REQUEST_STATUS_LABELS_AR[status] || status}
          </span>
        );
    }
  };

  const formatTime = (ms?: number | null) => {
    if (!ms) return '—';
    try {
      return new Date(ms).toLocaleString('ar-EG', {
        year: 'numeric',
        month: '2-digit',
        day: '2-digit',
        hour: '2-digit',
        minute: '2-digit',
        second: '2-digit'
      });
    } catch {
      return String(ms);
    }
  };

  return (
    <div className="space-y-6" dir="rtl">
      <div className="grid grid-cols-2 lg:grid-cols-4 gap-4">
        <GlassCard className="p-4">
          <div className="text-xs text-slate-400">إجمالي العمليات المتتبعة</div>
          <div className="text-2xl font-bold text-white mt-1">{counts.total}</div>
        </GlassCard>
        <GlassCard className="p-4">
          <div className="text-xs text-emerald-300">مكتملة بنجاح (محمية من التكرار)</div>
          <div className="text-2xl font-bold text-emerald-400 mt-1">{counts.completed}</div>
        </GlassCard>
        <GlassCard className="p-4">
          <div className="text-xs text-amber-300">عمليات معلقة / بانتظار التأكيد</div>
          <div className="text-2xl font-bold text-amber-400 mt-1">{counts.pending}</div>
        </GlassCard>
        <GlassCard className="p-4">
          <div className="text-xs text-red-300">تعذر تنفيذها (آمنة لإعادة المحاولة)</div>
          <div className="text-2xl font-bold text-red-400 mt-1">{counts.failed}</div>
        </GlassCard>
      </div>

      <GlassCard className="p-5 space-y-4">
        <div className="flex flex-col md:flex-row md:items-center justify-between gap-3">
          <div className="flex items-center gap-2">
            <ShieldCheck className="w-5 h-5 text-cyan-400" />
            <div>
              <h3 className="text-lg font-bold text-white">سجل متابعة العمليات ومنع التكرار</h3>
              <p className="text-xs text-slate-400">
                متابعة مفاتيح الطلبات الفريدة (Idempotency Keys) وحالات التنفيذ والتحقق من العمليات المعلقة
              </p>
            </div>
          </div>
          <GlassButton
            variant="cyan"
            size="sm"
            icon={<RefreshCw className="w-4 h-4" />}
            isLoading={isRecoveringAll}
            loadingText="جاري فحص العمليات المعلقة..."
            onClick={handleRecoverAll}
          >
            التحقق من العمليات المعلقة
          </GlassButton>
        </div>

        {statusBanner && (
          <div className="p-3 rounded-xl bg-cyan-500/10 border border-cyan-500/30 text-cyan-200 text-xs flex items-center gap-2">
            <Activity className="w-4 h-4 shrink-0" />
            <span>{statusBanner}</span>
          </div>
        )}

        <div className="flex flex-col md:flex-row gap-3">
          <div className="relative flex-1">
            <Search className="w-4 h-4 text-slate-400 absolute right-3 top-1/2 -translate-y-1/2" />
            <input
              type="text"
              value={searchTerm}
              onChange={(e) => setSearchTerm(e.target.value)}
              placeholder="بحث برقم الطلب، مرجع المستند، اسم المستخدم أو البيان..."
              className="w-full pr-9 pl-3 py-2 rounded-xl bg-slate-900/60 border border-white/10 text-sm text-white placeholder-slate-400 focus:outline-none focus:border-cyan-400/50"
            />
          </div>
          <div className="flex flex-wrap gap-2">
            {(
              [
                ['ALL', 'الكل'],
                ['COMPLETED', 'مكتملة'],
                ['PENDING_CONFIRMATION', 'بانتظار التأكيد'],
                ['PROCESSING', 'قيد التنفيذ'],
                ['FAILED', 'فاشلة']
              ] as const
            ).map(([key, label]) => (
              <button
                key={key}
                type="button"
                onClick={() => setStatusFilter(key)}
                className={`px-3 py-1.5 rounded-lg text-xs font-medium transition ${
                  statusFilter === key
                    ? 'bg-cyan-500/25 text-cyan-200 border border-cyan-400/50'
                    : 'bg-white/5 text-slate-300 border border-white/10 hover:bg-white/10'
                }`}
              >
                {label}
              </button>
            ))}
          </div>
        </div>

        <div className="overflow-x-auto">
          <table className="w-full text-xs text-right border-collapse">
            <thead>
              <tr className="border-b border-white/10 text-slate-400">
                <th className="py-2.5 px-3">معرف الطلب (Idempotency Key)</th>
                <th className="py-2.5 px-3">نوع العملية</th>
                <th className="py-2.5 px-3">المستند / المرجع</th>
                <th className="py-2.5 px-3">المستخدم</th>
                <th className="py-2.5 px-3">بدء العملية / آخر تحديث</th>
                <th className="py-2.5 px-3">الحالة</th>
                <th className="py-2.5 px-3">النتيجة / التفاصيل</th>
                <th className="py-2.5 px-3">إجراء التحقق</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-white/5">
              {filtered.length === 0 ? (
                <tr>
                  <td colSpan={8} className="py-8 text-center text-slate-400">
                    لا توجد عمليات مطابقة للبحث في سجل الحماية من التكرار.
                  </td>
                </tr>
              ) : (
                filtered.map((rec) => (
                  <tr key={rec.idempotencyKey} className="hover:bg-white/5 transition-colors">
                    <td className="py-2.5 px-3 font-mono text-[11px] text-cyan-300">
                      {rec.idempotencyKey}
                    </td>
                    <td className="py-2.5 px-3 text-white font-medium">
                      {OPERATION_TYPE_LABELS_AR[rec.operationType] || rec.operationType}
                    </td>
                    <td className="py-2.5 px-3 text-slate-200">
                      <div>{rec.resultReference || rec.documentReference || '—'}</div>
                      {rec.payloadSummary && (
                        <div className="text-[11px] text-slate-400 truncate max-w-[200px]">
                          {rec.payloadSummary}
                        </div>
                      )}
                    </td>
                    <td className="py-2.5 px-3 text-slate-300">{rec.userId || '—'}</td>
                    <td className="py-2.5 px-3 text-slate-300">
                      <div>{formatTime(rec.createdAt)}</div>
                      <div className="text-[10px] text-slate-500">تحديث: {formatTime(rec.updatedAt)}</div>
                    </td>
                    <td className="py-2.5 px-3">{statusBadge(rec.requestStatus)}</td>
                    <td className="py-2.5 px-3 text-slate-300 max-w-[220px]">
                      {rec.errorDetails ? (
                        <span className="text-red-300">{rec.errorDetails}</span>
                      ) : rec.resultReference ? (
                        <span className="text-emerald-300">مرجع: {rec.resultReference}</span>
                      ) : (
                        '—'
                      )}
                    </td>
                    <td className="py-2.5 px-3">
                      {rec.requestStatus !== 'COMPLETED' ? (
                        <GlassButton
                          size="sm"
                          variant="secondary"
                          isLoading={verifyingKey === rec.idempotencyKey}
                          loadingText="جاري التحقق..."
                          onClick={() => handleVerifyOne(rec.idempotencyKey)}
                        >
                          التحقق من حالة العملية
                        </GlassButton>
                      ) : (
                        <span className="text-emerald-400 font-semibold">محمية ✓</span>
                      )}
                    </td>
                  </tr>
                ))
              )}
            </tbody>
          </table>
        </div>
      </GlassCard>
    </div>
  );
};
