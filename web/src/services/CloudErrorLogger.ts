/**
 * PMP PAINTS — Cloud Sync Error Telemetry, Secret Redaction & Read-Only Service Probes
 * Mirrors Android `CloudSyncErrorTracker.kt`.
 */

export type CloudErrorCategory =
  | 'PERMISSION_DENIED'
  | 'UNAUTHENTICATED'
  | 'UNAVAILABLE'
  | 'DEADLINE_EXCEEDED'
  | 'RESOURCE_EXHAUSTED'
  | 'FAILED_PRECONDITION'
  | 'NETWORK_ERROR'
  | 'DATA_CONFLICT';

export type ServiceProbeId =
  | 'NETWORK'
  | 'FIREBASE_AUTH'
  | 'FIRESTORE'
  | 'RBAC_POLICY'
  | 'APP_UPDATES';

export interface CloudErrorCategoryMeta {
  category: CloudErrorCategory;
  titleAr: string;
  remediationHintAr: string;
}

export interface CloudErrorLogEntry {
  id: string;
  operation: string;
  path: string;
  category: CloudErrorCategory;
  categoryTitleAr: string;
  code: string;
  sanitizedMessage: string;
  remediationHintAr: string;
  timestamp: number;
}

export interface ServiceProbeResult {
  id: ServiceProbeId;
  titleAr: string;
  ok: boolean;
  measured: boolean;
  latencyMs: number;
  summaryAr: string;
  remediationHintAr: string;
  checkedAtMillis: number;
}

export interface ComprehensiveDiagnosticReport {
  clusterCode: string;
  generatedAtMillis: number;
  overallOk: boolean;
  readOnlyGuarantee: true;
  probes: ServiceProbeResult[];
  totalLatencyMs: number;
}

const MAX_BUFFER = 50;

const CATEGORY_METADATA: Record<CloudErrorCategory, { titleAr: string; remediationHintAr: string }> = {
  PERMISSION_DENIED: {
    titleAr: 'رفض صلاحيات الخادم (PERMISSION_DENIED)',
    remediationHintAr:
      'تحقق من تطابق عضوية المستخدم في cluster_members ودوره المعتمد وقواعد Firestore المنشورة.'
  },
  UNAUTHENTICATED: {
    titleAr: 'جلسة المصادقة غير نشطة أو منتهية (UNAUTHENTICATED)',
    remediationHintAr: 'سجّل الدخول بحساب مصادق عبر Firebase Auth / Google SSO لتجديد رمز الجلسة.'
  },
  UNAVAILABLE: {
    titleAr: 'الخدمة السحابية غير متاحة مؤقتاً (UNAVAILABLE)',
    remediationHintAr: 'تحقق من اتصال الإنترنت أو أعد المحاولة بعد بضع ثوانٍ.'
  },
  DEADLINE_EXCEEDED: {
    titleAr: 'انتهاء مهلة استجابة الخادم (DEADLINE_EXCEEDED)',
    remediationHintAr: 'بطء الشبكة أو الاستجابة — خفّف حجم الدفعة أو أعد المحاولة على اتصال مستقر.'
  },
  RESOURCE_EXHAUSTED: {
    titleAr: 'تجاوز حصة الطلبات أو الحدود (RESOURCE_EXHAUSTED)',
    remediationHintAr: 'تم استهلاك الحصة المؤقتة لـ Firebase أو تم إرسال طلبات كثيفة متتالية.'
  },
  FAILED_PRECONDITION: {
    titleAr: 'شرط خادمي أو فهرس غير مكتمل (FAILED_PRECONDITION)',
    remediationHintAr: 'تحقق من حالة القيد أو فهارس Firestore المطلوبة قبل إعادة العملية.'
  },
  NETWORK_ERROR: {
    titleAr: 'انقطاع أو ضعف اتصال الشبكة (NETWORK_ERROR)',
    remediationHintAr: 'تأكد من اتصال الجهاز بالإنترنت وعدم حجب نطاقات *.googleapis.com.'
  },
  DATA_CONFLICT: {
    titleAr: 'تعارض أو رفض تحقق البيانات (DATA_CONFLICT)',
    remediationHintAr: 'تحقق من توازن القيود المحاسبية أو القواعد الثابتة للسجلات غير القابلة للتعديل.'
  }
};

export function sanitizeCloudMessage(raw: unknown): string {
  const str = typeof raw === 'string' ? raw : raw instanceof Error ? raw.message : String(raw ?? '');
  if (!str) return 'خطأ غير محدد أثناء الاتصال بالسيرفر';
  return str
    .replace(/Bearer\s+[A-Za-z0-9\-_.~+/]+=*/gi, 'Bearer ***REDACTED***')
    .replace(/AIza[0-9A-Za-z_-]{15,}/g, 'AIza***REDACTED***')
    .replace(/\beyJ[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]*/g, '***JWT_REDACTED***')
    .replace(
      /\b(pinOrPassword|password|secret|token|apiKey|idToken|accessToken|private_key)\b\s*[:=]\s*[^\s,;}"']+/gi,
      '$1=***REDACTED***'
    )
    .slice(0, 600);
}

export function classifyCloudError(err: unknown): {
  category: CloudErrorCategory;
  code: string;
  categoryTitleAr: string;
  remediationHintAr: string;
  sanitizedMessage: string;
} {
  const errObj = err && typeof err === 'object' ? (err as Record<string, unknown>) : {};
  const rawCode = String(errObj.code ?? '').trim();
  const sanitizedMessage = sanitizeCloudMessage(errObj.message ?? err);
  const haystack = `${rawCode} ${sanitizedMessage}`.toLowerCase();

  let category: CloudErrorCategory = 'DATA_CONFLICT';

  if (
    haystack.includes('permission-denied') ||
    haystack.includes('permission_denied') ||
    haystack.includes('missing or insufficient permissions') ||
    haystack.includes('صلاحية')
  ) {
    category = 'PERMISSION_DENIED';
  } else if (
    haystack.includes('unauthenticated') ||
    haystack.includes('auth/') ||
    haystack.includes('invalid-credential') ||
    haystack.includes('id token') ||
    haystack.includes('unauthorized')
  ) {
    category = 'UNAUTHENTICATED';
  } else if (
    haystack.includes('unavailable') ||
    haystack.includes('service is currently unavailable') ||
    haystack.includes('503')
  ) {
    category = 'UNAVAILABLE';
  } else if (haystack.includes('deadline-exceeded') || haystack.includes('deadline_exceeded') || haystack.includes('timeout')) {
    category = 'DEADLINE_EXCEEDED';
  } else if (
    haystack.includes('resource-exhausted') ||
    haystack.includes('resource_exhausted') ||
    haystack.includes('quota') ||
    haystack.includes('429')
  ) {
    category = 'RESOURCE_EXHAUSTED';
  } else if (haystack.includes('failed-precondition') || haystack.includes('failed_precondition') || haystack.includes('index')) {
    category = 'FAILED_PRECONDITION';
  } else if (
    haystack.includes('network') ||
    haystack.includes('offline') ||
    haystack.includes('failed to fetch') ||
    haystack.includes('client is offline') ||
    haystack.includes('err_internet_disconnected')
  ) {
    category = 'NETWORK_ERROR';
  } else if (
    haystack.includes('already-exists') ||
    haystack.includes('aborted') ||
    haystack.includes('conflict') ||
    haystack.includes('not-found') ||
    haystack.includes('محصن')
  ) {
    category = 'DATA_CONFLICT';
  }

  const meta = CATEGORY_METADATA[category];
  return {
    category,
    code: rawCode || category,
    categoryTitleAr: meta.titleAr,
    remediationHintAr: meta.remediationHintAr,
    sanitizedMessage
  };
}

class CloudErrorLoggerImpl {
  private entries: CloudErrorLogEntry[] = [];
  private listeners: Set<(entries: CloudErrorLogEntry[]) => void> = new Set();

  recordError(operation: string, path: string, err: unknown): CloudErrorLogEntry {
    const classified = classifyCloudError(err);
    const entry: CloudErrorLogEntry = {
      id: `ERR-${Date.now()}-${Math.floor(Math.random() * 10000)}`,
      operation: operation || 'CLOUD_OP',
      path: path || '/',
      category: classified.category,
      categoryTitleAr: classified.categoryTitleAr,
      code: classified.code,
      sanitizedMessage: classified.sanitizedMessage,
      remediationHintAr: classified.remediationHintAr,
      timestamp: Date.now()
    };

    this.entries = [entry, ...this.entries].slice(0, MAX_BUFFER);
    this.notify();
    return entry;
  }

  getEntries(): CloudErrorLogEntry[] {
    return [...this.entries];
  }

  subscribe(listener: (entries: CloudErrorLogEntry[]) => void): () => void {
    this.listeners.add(listener);
    listener([...this.entries]);
    return () => {
      this.listeners.delete(listener);
    };
  }

  clear(): void {
    this.entries = [];
    this.notify();
  }

  private notify(): void {
    const snapshot = [...this.entries];
    this.listeners.forEach((cb) => {
      try {
        cb(snapshot);
      } catch {
        // Ignore subscriber failure
      }
    });
  }
}

export const CloudErrorLogger = new CloudErrorLoggerImpl();
