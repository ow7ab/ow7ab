import React from 'react';
import { Loader2 } from 'lucide-react';

interface GlassButtonProps {
  children: React.ReactNode;
  variant?: 'primary' | 'secondary' | 'cyan';
  icon?: React.ReactNode;
  onClick?: (e: React.MouseEvent<HTMLButtonElement>) => void;
  className?: string;
  type?: 'button' | 'submit' | 'reset';
  disabled?: boolean;
  isLoading?: boolean;
  loadingText?: string;
  size?: 'sm' | 'md' | 'lg';
  title?: string;
}

export const GlassButton: React.FC<GlassButtonProps> = ({
  children,
  variant = 'primary',
  icon,
  onClick,
  className = '',
  type = 'button',
  disabled = false,
  isLoading = false,
  loadingText,
  size = 'md',
  title
}) => {
  const variantClass = {
    primary: 'btn-primary',
    secondary: 'btn-secondary',
    cyan: 'btn-cyan'
  }[variant];

  const sizeClass = size === 'lg' ? 'px-6 py-3 text-base' : size === 'sm' ? 'px-3 py-1.5 text-xs' : 'px-4 py-2 text-sm';
  const isDisabled = disabled || isLoading;

  return (
    <button
      type={type}
      disabled={isDisabled}
      aria-busy={isLoading}
      title={title}
      onClick={(e) => {
        if (isDisabled) {
          e.preventDefault();
          return;
        }
        onClick?.(e);
      }}
      className={`btn ${variantClass} ${sizeClass} ${className}`}
      style={{ opacity: isDisabled ? 0.65 : 1, cursor: isDisabled ? 'not-allowed' : 'pointer' }}
    >
      {isLoading ? (
        <span style={{ display: 'inline-flex', alignItems: 'center' }}>
          <Loader2 className="w-4 h-4 animate-spin" />
        </span>
      ) : (
        icon && <span style={{ display: 'inline-flex', alignItems: 'center' }}>{icon}</span>
      )}
      {isLoading && loadingText ? loadingText : children}
    </button>
  );
};

