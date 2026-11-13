import React from 'react';
import { useLanguage } from '../../context/LanguageContext';
import { APP_VERSION_CONTRACT, resolveAssetUrl } from '../../config/appVersionContract';
import { useLatestRelease } from '../../hooks/useLatestRelease';
import { GlassCard } from '../ui/GlassCard';
import { GlassBadge } from '../ui/GlassBadge';
import { GlassButton } from '../ui/GlassButton';
import {
  Download,
  ShieldCheck,
  CheckCircle2,
  Sparkles,
  Server,
  Smartphone,
  ExternalLink,
  ChevronLeft,
  ChevronRight
} from 'lucide-react';

interface HeroSectionProps {
  onOpenLogin: () => void;
  onScrollToDownload: () => void;
  onScrollToOverview: () => void;
}

export const HeroSection: React.FC<HeroSectionProps> = ({
  onOpenLogin,
  onScrollToDownload,
  onScrollToOverview
}) => {
  const { t, language, direction } = useLanguage();
  const { latestRelease } = useLatestRelease();
  const ChevronIcon = direction === 'rtl' ? ChevronLeft : ChevronRight;

  return (
    <section
      id="home"
      style={{
        padding: '50px 20px 40px 20px',
        maxWidth: '1240px',
        margin: '0 auto'
      }}
    >
      <div
        style={{
          display: 'grid',
          gridTemplateColumns: 'repeat(auto-fit, minmax(320px, 1fr))',
          gap: '40px',
          alignItems: 'center'
        }}
      >
        {/* Left Column: Headings & Call to Actions */}
        <div>
          <div style={{ marginBottom: '16px' }}>
            <GlassBadge
              text={t('hero_badge')}
              variant="red"
              icon={<Sparkles size={14} color="var(--pmp-red-light)" />}
            />
          </div>

          <h1
            style={{
              fontSize: 'clamp(28px, 4.5vw, 46px)',
              fontWeight: 900,
              lineHeight: 1.25,
              marginBottom: '18px',
              color: 'var(--text-primary)'
            }}
          >
            {language === 'ar' ? (
              <>
                المنصة السحابية المتكاملة لإدارة <span style={{ color: 'var(--pmp-red)' }}>مصانع الدهانات</span> والعمليات المالية والمخزنية
              </>
            ) : (
              <>
                The Integrated Cloud ERP Platform for <span style={{ color: 'var(--pmp-red)' }}>Paint Industries</span> & Operations
              </>
            )}
          </h1>

          <p
            style={{
              fontSize: '15.5px',
              color: 'var(--text-secondary)',
              lineHeight: 1.7,
              marginBottom: '28px',
              maxWidth: '600px'
            }}
          >
            {t('tagline')}. نظام متكامل يربط خطوط إنتاج المصنع، مخازن الخامات والمنتج التام، دورة المشتريات، وفواتير المبيعات مع الخزن متعددة العملات (SDG, EGP, USD) بسجل تدقيق مشفر ولحظي.
          </p>

          {/* CTA Buttons */}
          <div
            style={{
              display: 'flex',
              gap: '14px',
              flexWrap: 'wrap',
              marginBottom: '32px'
            }}
          >
            <GlassButton
              variant="primary"
              icon={<Download size={18} />}
              onClick={onScrollToDownload}
              className="pulse-btn"
            >
              {t('btn_download_now')}
            </GlassButton>

            <GlassButton
              variant="secondary"
              icon={<Server size={18} />}
              onClick={onOpenLogin}
            >
              {t('btn_open_portal')}
            </GlassButton>
          </div>

          {/* Verification Chips */}
          <div
            style={{
              display: 'flex',
              gap: '12px',
              flexWrap: 'wrap',
              fontSize: '12.5px',
              color: 'var(--text-secondary)'
            }}
          >
            <div style={{ display: 'flex', alignItems: 'center', gap: '6px' }}>
              <CheckCircle2 size={16} color="var(--pmp-green)" />
              <span>
                {latestRelease
                  ? `${latestRelease.versionName} (Build ${latestRelease.versionCode})`
                  : APP_VERSION_CONTRACT.RELEASE_TITLE}
              </span>
            </div>
            <div style={{ display: 'flex', alignItems: 'center', gap: '6px' }}>
              <ShieldCheck size={16} color="var(--pmp-cyan)" />
              <span>{t('verified_security')}</span>
            </div>
            <div style={{ display: 'flex', alignItems: 'center', gap: '6px' }}>
              <Server size={16} color="var(--pmp-gold)" />
              <span>{t('system_status_online')}</span>
            </div>
          </div>
        </div>

        {/* Right Column: Interactive Live App Showcase Card */}
        <div>
          <GlassCard
            style={{
              padding: '28px',
              background: 'var(--gradient-card)',
              border: '1px solid var(--border-highlight)',
              position: 'relative',
              overflow: 'hidden'
            }}
          >
            <div
              style={{
                position: 'absolute',
                top: '-40px',
                right: '-40px',
                width: '180px',
                height: '180px',
                borderRadius: '50%',
                background: 'rgba(220, 38, 38, 0.12)',
                filter: 'blur(40px)',
                pointerEvents: 'none'
              }}
            />

            {/* Header in Card */}
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '20px' }}>
              <div style={{ display: 'flex', alignItems: 'center', gap: '10px' }}>
                <img
                  src={resolveAssetUrl('assets/ic_pmp_emblem.png')}
                  alt="PMP Logo"
                  style={{ width: '38px', height: '38px', objectFit: 'contain' }}
                />
                <div>
                  <h3 style={{ fontSize: '15px', fontWeight: 800 }}>PMP PAINTS ERP</h3>
                  <span style={{ fontSize: '11px', color: 'var(--pmp-green)' }}>● سحابة الإنتاج متصلة (Active)</span>
                </div>
              </div>
              <GlassBadge text="الإصدار 18.0 المعتمد" variant="cyan" />
            </div>

            {/* Quick Live Stats Grid */}
            <div
              style={{
                display: 'grid',
                gridTemplateColumns: 'repeat(2, 1fr)',
                gap: '12px',
                marginBottom: '20px'
              }}
            >
              <div
                style={{
                  padding: '12px',
                  borderRadius: '12px',
                  background: 'var(--surface-primary)',
                  border: '1px solid var(--border-subtle)'
                }}
              >
                <div style={{ fontSize: '11px', color: 'var(--text-muted)' }}>الفروع المتصلة بالشبكة</div>
                <div style={{ fontSize: '18px', fontWeight: 900, color: 'var(--pmp-cyan-light)', marginTop: '2px' }}>
                  4 فروع ومخازن
                </div>
                <div style={{ fontSize: '10px', color: 'var(--text-secondary)', marginTop: '2px' }}>
                  السودان • مصر • حلفا • عطبرة
                </div>
              </div>

              <div
                style={{
                  padding: '12px',
                  borderRadius: '12px',
                  background: 'var(--surface-primary)',
                  border: '1px solid var(--border-subtle)'
                }}
              >
                <div style={{ fontSize: '11px', color: 'var(--text-muted)' }}>أوزان التعبئة المعتمدة</div>
                <div style={{ fontSize: '18px', fontWeight: 900, color: 'var(--pmp-red-light)', marginTop: '2px' }}>
                  250g • 900g • 4Kg
                </div>
                <div style={{ fontSize: '10px', color: 'var(--text-secondary)', marginTop: '2px' }}>
                  علب مفردة وكواتين مقفلة
                </div>
              </div>

              <div
                style={{
                  padding: '12px',
                  borderRadius: '12px',
                  background: 'var(--surface-primary)',
                  border: '1px solid var(--border-subtle)'
                }}
              >
                <div style={{ fontSize: '11px', color: 'var(--text-muted)' }}>الخزن المعتمدة</div>
                <div style={{ fontSize: '18px', fontWeight: 900, color: 'var(--pmp-gold)', marginTop: '2px' }}>
                  SDG • EGP • USD
                </div>
                <div style={{ fontSize: '10px', color: 'var(--text-secondary)', marginTop: '2px' }}>
                  تسويات وتحويلات لحظية
                </div>
              </div>

              <div
                style={{
                  padding: '12px',
                  borderRadius: '12px',
                  background: 'var(--surface-primary)',
                  border: '1px solid var(--border-subtle)'
                }}
              >
                <div style={{ fontSize: '11px', color: 'var(--text-muted)' }}>صلاحيات الموظفين والرقابة</div>
                <div style={{ fontSize: '18px', fontWeight: 900, color: 'var(--pmp-green)', marginTop: '2px' }}>
                  11 صلاحية تفصيلية
                </div>
                <div style={{ fontSize: '10px', color: 'var(--text-secondary)', marginTop: '2px' }}>
                  عزل كامل وصلاحيات مؤقتة
                </div>
              </div>
            </div>

            {/* Quick Action in Card */}
            <div
              onClick={onScrollToOverview}
              style={{
                padding: '12px 16px',
                borderRadius: '12px',
                background: 'var(--surface-elevated)',
                border: '1px solid var(--border-subtle)',
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'space-between',
                cursor: 'pointer'
              }}
            >
              <span style={{ fontSize: '13px', fontWeight: 700, color: 'var(--text-primary)' }}>
                استكشف خريطة الوحدات والدورة الصناعية الكاملة
              </span>
              <ChevronIcon size={18} color="var(--pmp-red-light)" />
            </div>
          </GlassCard>
        </div>
      </div>
    </section>
  );
};
