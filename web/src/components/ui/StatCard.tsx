import React from 'react';
import { GlassCard } from './GlassCard';

interface StatCardProps {
  title: string;
  value: string;
  subtitle?: string;
  badge?: string;
  badgeVariant?: 'red' | 'cyan' | 'green' | 'gold';
  icon?: React.ReactNode;
}

export const StatCard: React.FC<StatCardProps> = ({
  title,
  value,
  subtitle,
  badge,
  badgeVariant = 'red',
  icon
}) => {
  return (
    <GlassCard style={{ padding: '20px', flex: 1, minWidth: '220px' }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', marginBottom: '12px' }}>
        <span style={{ fontSize: '13px', fontWeight: 700, color: 'var(--text-secondary)' }}>
          {title}
        </span>
        {icon && (
          <div style={{
            width: '36px',
            height: '36px',
            borderRadius: '10px',
            background: 'var(--surface-elevated)',
            border: '1px solid var(--border-subtle)',
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
            color: 'var(--pmp-red-light)'
          }}>
            {icon}
          </div>
        )}
      </div>

      <div style={{ fontSize: '24px', fontWeight: 900, color: 'var(--text-primary)', marginBottom: '6px' }}>
        {value}
      </div>

      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
        {subtitle && (
          <span style={{ fontSize: '12px', color: 'var(--text-muted)' }}>
            {subtitle}
          </span>
        )}
        {badge && (
          <span className={`badge badge-${badgeVariant}`}>
            {badge}
          </span>
        )}
      </div>
    </GlassCard>
  );
};
