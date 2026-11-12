import React from 'react';

interface GlassCardProps {
  children: React.ReactNode;
  className?: string;
  style?: React.CSSProperties;
  onClick?: () => void;
  hoverEffect?: boolean;
  variant?: 'default' | 'highlight' | 'subtle';
}

export const GlassCard: React.FC<GlassCardProps> = ({
  children,
  className = '',
  style,
  onClick,
  hoverEffect = true,
  variant = 'default'
}) => {
  const variantClass = variant === 'highlight' ? 'border-pmp-primary/40 shadow-lg' : '';

  return (
    <div
      onClick={onClick}
      style={style}
      className={`glass-panel ${variantClass} ${hoverEffect ? 'hover:scale-[1.01]' : ''} ${className}`}
    >
      {children}
    </div>
  );
};
