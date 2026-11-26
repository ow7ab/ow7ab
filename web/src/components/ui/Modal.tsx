import React, { useEffect, useRef } from 'react';
import { createPortal } from 'react-dom';
import { X } from 'lucide-react';

interface ModalProps {
  isOpen: boolean;
  onClose: () => void;
  title: string;
  subtitle?: string;
  children: React.ReactNode;
  maxWidth?: string;
}

export const MODAL_MAX_WIDTH_MAP: Record<string, string> = {
  'max-w-xs': '20rem',
  'max-w-sm': '24rem',
  'max-w-md': '28rem',
  'max-w-lg': '32rem',
  'max-w-xl': '36rem',
  'max-w-2xl': '42rem',
  'max-w-3xl': '48rem',
  'max-w-4xl': '56rem',
  'max-w-5xl': '64rem',
  'max-w-6xl': '72rem',
  'max-w-7xl': '80rem',
  'max-w-full': '100%'
};

export function resolveModalMaxWidth(maxWidth?: string): string {
  const token = (maxWidth || '').trim();
  if (!token) return '540px';
  return MODAL_MAX_WIDTH_MAP[token] ?? token;
}

/**
 * Renders overlay nodes inside `document.body` so ancestor `transform`,
 * `backdrop-filter`, or `overflow` containers never trap `position: fixed`.
 */
export function renderInBodyPortal(node: React.ReactNode): React.ReactNode {
  if (typeof document === 'undefined' || !document.body) {
    return node;
  }
  return createPortal(node, document.body);
}

let activeModalLockCount = 0;
let previousBodyOverflow: string | null = null;
let nextModalId = 1;
const openModalStack: number[] = [];

export const Modal: React.FC<ModalProps> = ({
  isOpen,
  onClose,
  title,
  subtitle,
  children,
  maxWidth = '540px'
}) => {
  const onCloseRef = useRef(onClose);
  onCloseRef.current = onClose;

  useEffect(() => {
    if (!isOpen || typeof document === 'undefined') return;

    const modalId = nextModalId++;
    openModalStack.push(modalId);

    if (activeModalLockCount === 0) {
      previousBodyOverflow = document.body.style.overflow;
      document.body.style.overflow = 'hidden';
    }
    activeModalLockCount += 1;

    const handleKeyDown = (e: KeyboardEvent) => {
      if (e.key === 'Escape' && openModalStack[openModalStack.length - 1] === modalId) {
        onCloseRef.current();
      }
    };
    window.addEventListener('keydown', handleKeyDown);

    return () => {
      const idx = openModalStack.lastIndexOf(modalId);
      if (idx !== -1) {
        openModalStack.splice(idx, 1);
      }

      activeModalLockCount = Math.max(0, activeModalLockCount - 1);
      if (activeModalLockCount === 0) {
        document.body.style.overflow = previousBodyOverflow ?? '';
        previousBodyOverflow = null;
      }

      window.removeEventListener('keydown', handleKeyDown);
    };
  }, [isOpen]);

  if (!isOpen) return null;

  const resolvedMaxWidth = resolveModalMaxWidth(maxWidth);

  const modalMarkup = (
    <div
      data-pmp-modal-backdrop="true"
      style={{
        position: 'fixed',
        inset: 0,
        background: 'rgba(0, 0, 0, 0.78)',
        backdropFilter: 'blur(8px)',
        WebkitBackdropFilter: 'blur(8px)',
        zIndex: 9999,
        display: 'flex',
        alignItems: 'flex-start',
        justifyContent: 'center',
        overflowY: 'auto',
        overflowX: 'hidden',
        overscrollBehavior: 'contain',
        WebkitOverflowScrolling: 'touch',
        padding: '40px 16px 24px'
      }}
      onClick={() => onCloseRef.current()}
    >
      <div
        role="dialog"
        aria-modal="true"
        aria-label={title}
        data-pmp-modal-dialog="true"
        style={{
          width: '100%',
          maxWidth: resolvedMaxWidth,
          maxHeight: 'calc(100vh - 64px)',
          margin: 'auto',
          background: 'var(--surface-primary)',
          border: '1px solid var(--border-highlight)',
          borderRadius: 'var(--radius-lg)',
          boxShadow: 'var(--shadow-lg)',
          display: 'flex',
          flexDirection: 'column',
          overflow: 'hidden',
          animation: 'pmpModalEnter 180ms cubic-bezier(0.22, 1, 0.36, 1)'
        }}
        onClick={(e) => e.stopPropagation()}
      >
        <div
          style={{
            padding: '16px 22px',
            borderBottom: '1px solid var(--border-subtle)',
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'space-between',
            gap: '12px',
            background: 'var(--surface-elevated)',
            flexShrink: 0
          }}
        >
          <div style={{ minWidth: 0 }}>
            <h3 style={{ fontSize: '17px', fontWeight: 800, color: 'var(--text-primary)' }}>
              {title}
            </h3>
            {subtitle && (
              <p style={{ fontSize: '12px', color: 'var(--text-secondary)', marginTop: '2px' }}>
                {subtitle}
              </p>
            )}
          </div>
          <button
            type="button"
            aria-label="Close modal"
            onClick={() => onCloseRef.current()}
            style={{
              background: 'transparent',
              border: 'none',
              color: 'var(--text-muted)',
              cursor: 'pointer',
              display: 'flex',
              flexShrink: 0,
              padding: '6px',
              borderRadius: '8px'
            }}
          >
            <X size={20} />
          </button>
        </div>

        <div
          data-pmp-modal-body="true"
          style={{
            padding: '20px 24px',
            flex: '1 1 auto',
            minHeight: 0,
            overflowY: 'auto',
            overflowX: 'hidden',
            overscrollBehavior: 'contain',
            WebkitOverflowScrolling: 'touch'
          }}
        >
          {children}
        </div>
      </div>
    </div>
  );

  return renderInBodyPortal(modalMarkup);
};
