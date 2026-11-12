import React from 'react';

interface GlassBadgeProps {
  children?: React.ReactNode;
  text?: string;
  variant?: 'red' | 'cyan' | 'green' | 'gold' | 'default' | 'primary' | 'secondary' | 'accent' | 'success';
  icon?: React.ReactNode;
  className?: string;
}

export const GlassBadge: React.FC<GlassBadgeProps> = ({
  children,
  text,
  variant = 'red',
  icon,
  className = ''
}) => {
  const variantClass = {
    red: 'badge-red',
    primary: 'badge-red',
    cyan: 'badge-cyan',
    accent: 'badge-cyan',
    green: 'badge-green',
    success: 'badge-green',
    gold: 'badge-gold',
    secondary: 'badge-cyan',
    default: 'badge-red'
  }[variant] || 'badge-red';

  return (
    <span className={`badge ${variantClass} ${className}`}>
      {icon && <span style={{ display: 'inline-flex', alignItems: 'center' }}>{icon}</span>}
      {text || children}
    </span>
  );
};
