import React from 'react';

interface GlassButtonProps {
  children: React.ReactNode;
  variant?: 'primary' | 'secondary' | 'cyan';
  icon?: React.ReactNode;
  onClick?: () => void;
  className?: string;
  type?: 'button' | 'submit' | 'reset';
  disabled?: boolean;
  size?: 'sm' | 'md' | 'lg';
}

export const GlassButton: React.FC<GlassButtonProps> = ({
  children,
  variant = 'primary',
  icon,
  onClick,
  className = '',
  type = 'button',
  disabled = false,
  size = 'md'
}) => {
  const variantClass = {
    primary: 'btn-primary',
    secondary: 'btn-secondary',
    cyan: 'btn-cyan'
  }[variant];

  const sizeClass = size === 'lg' ? 'px-6 py-3 text-base' : size === 'sm' ? 'px-3 py-1.5 text-xs' : 'px-4 py-2 text-sm';

  return (
    <button
      type={type}
      disabled={disabled}
      onClick={onClick}
      className={`btn ${variantClass} ${sizeClass} ${className}`}
      style={{ opacity: disabled ? 0.6 : 1, cursor: disabled ? 'not-allowed' : 'pointer' }}
    >
      {icon && <span style={{ display: 'inline-flex', alignItems: 'center' }}>{icon}</span>}
      {children}
    </button>
  );
};
