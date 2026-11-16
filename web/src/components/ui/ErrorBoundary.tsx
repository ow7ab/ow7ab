import React from 'react';
import { safeStorageRemove } from '../../utils/safeStorage';
import { isChunkLoadError } from '../../utils/lazyWithRetry';

interface ErrorBoundaryState {
  hasError: boolean;
  errorMessage: string;
  isChunkFailure: boolean;
}

export class ErrorBoundary extends React.Component<
  { children: React.ReactNode },
  ErrorBoundaryState
> {
  constructor(props: { children: React.ReactNode }) {
    super(props);
    this.state = {
      hasError: false,
      errorMessage: '',
      isChunkFailure: false
    };
  }

  static getDerivedStateFromError(error: Error): ErrorBoundaryState {
    return {
      hasError: true,
      errorMessage: error?.message || 'Unexpected rendering error',
      isChunkFailure: isChunkLoadError(error)
    };
  }

  componentDidCatch(error: Error, info: React.ErrorInfo): void {
    console.error('[PMP ERP ErrorBoundary]', error, info);
  }

  private handleReload = () => {
    this.setState({ hasError: false, errorMessage: '', isChunkFailure: false });
    if (typeof window !== 'undefined') {
      try {
        window.sessionStorage?.removeItem('pmp_chunk_reload_guard_v1');
      } catch {
        // Ignore sessionStorage restrictions
      }
      window.location.reload();
    }
  };

  private handleResetAndReload = () => {
    safeStorageRemove('pmp_erp_portal_session_v2');
    safeStorageRemove('pmp_web_theme');
    safeStorageRemove('pmp_web_lang');
    this.handleReload();
  };

  render(): React.ReactNode {
    if (!this.state.hasError) {
      return this.props.children;
    }

    return (
      <div
        dir="rtl"
        style={{
          minHeight: '100vh',
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'center',
          padding: '24px',
          background: '#050508',
          color: '#F8FAFC',
          fontFamily: "'Cairo', -apple-system, BlinkMacSystemFont, sans-serif"
        }}
      >
        <div
          style={{
            maxWidth: '520px',
            width: '100%',
            padding: '28px',
            borderRadius: '20px',
            background: '#0F1016',
            border: '1px solid rgba(220, 38, 38, 0.35)',
            boxShadow: '0 24px 48px rgba(0, 0, 0, 0.75)',
            textAlign: 'center'
          }}
        >
          <div
            style={{
              display: 'inline-flex',
              padding: '6px 14px',
              borderRadius: '9999px',
              background: 'rgba(220, 38, 38, 0.14)',
              border: '1px solid rgba(239, 68, 68, 0.35)',
              color: '#FCA5A5',
              fontSize: '12px',
              fontWeight: 700,
              marginBottom: '14px'
            }}
          >
            PMP PAINTS ERP — وضع الاسترداد الآمن
          </div>

          <h1 style={{ fontSize: '20px', fontWeight: 900, marginBottom: '10px', color: '#FFFFFF' }}>
            {this.state.isChunkFailure
              ? 'يتوفر تحديث جديد للمنصة'
              : 'حدث تعثر مؤقت أثناء عرض الواجهة'}
          </h1>
          <p style={{ fontSize: '13.5px', color: '#94A3B8', lineHeight: 1.7, marginBottom: '16px' }}>
            {this.state.isChunkFailure
              ? 'تم تحديث ملفات التشغيل على الخادم. اضغط زر التحديث لتحميل النسخة الأحدث بسلاسة دون التأثير على بياناتك.'
              : 'تم تفعيل الحماية التلقائية لمنع توقف الشاشة. يمكنك إعادة تحميل المنصة أو تصفير بيانات الجلسة المحلية بأمان دون التأثير على البيانات السحابية.'}
          </p>

          {this.state.errorMessage && (
            <div
              dir="ltr"
              style={{
                padding: '10px 12px',
                borderRadius: '10px',
                background: '#08080C',
                border: '1px solid #27272A',
                color: '#CBD5E1',
                fontSize: '12px',
                fontFamily: 'monospace',
                marginBottom: '18px',
                wordBreak: 'break-word'
              }}
            >
              {this.state.errorMessage}
            </div>
          )}

          <div style={{ display: 'flex', flexWrap: 'wrap', gap: '10px', justifyContent: 'center' }}>
            <button
              type="button"
              onClick={this.handleReload}
              style={{
                padding: '11px 22px',
                borderRadius: '12px',
                border: 'none',
                background: 'linear-gradient(135deg, #DC2626 0%, #991B1B 100%)',
                color: '#FFFFFF',
                fontWeight: 800,
                fontSize: '13.5px',
                cursor: 'pointer'
              }}
            >
              {this.state.isChunkFailure ? 'تحديث وتحميل النسخة الجديدة' : 'إعادة تحميل الصفحة'}
            </button>
            <button
              type="button"
              onClick={this.handleResetAndReload}
              style={{
                padding: '11px 18px',
                borderRadius: '12px',
                border: '1px solid #3F3F46',
                background: '#181822',
                color: '#E2E8F0',
                fontWeight: 700,
                fontSize: '13px',
                cursor: 'pointer'
              }}
            >
              تصفير الكاش المحلي والمتابعة
            </button>
          </div>
        </div>
      </div>
    );
  }
}
