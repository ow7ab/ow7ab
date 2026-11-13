import React from 'react';
import { useLanguage } from '../../context/LanguageContext';
import { APP_VERSION_CONTRACT, resolveAssetUrl } from '../../config/appVersionContract';
import { useLatestRelease } from '../../hooks/useLatestRelease';
import { formatReleaseDate } from '../../services/ReleaseService';
import { GlassCard } from '../ui/GlassCard';
import { GlassBadge } from '../ui/GlassBadge';
import {
  Download,
  CheckCircle2,
  Smartphone,
  ShieldCheck,
  Calendar,
  HardDrive,
  RefreshCw
} from 'lucide-react';

export const DownloadApkSection: React.FC = () => {
  const { language } = useLanguage();
  const {
    latestRelease,
    isLoading,
    refreshReleases
  } = useLatestRelease();

  const directDownloadUrl =
    latestRelease?.downloadUrl || APP_VERSION_CONTRACT.GITHUB_LATEST_APK_URL;
  const displayVersionName = latestRelease?.versionName || `v${APP_VERSION_CONTRACT.CURRENT_VERSION_NAME}`;
  const displayReleaseDate = formatReleaseDate(latestRelease?.publishedAt);
  const displaySizeMb =
    latestRelease && latestRelease.fileSizeMb > 0
      ? `${latestRelease.fileSizeMb} MB`
      : APP_VERSION_CONTRACT.downloadSize;
  const displayNotes =
    latestRelease?.releaseNotes ||
    '• النسخة الرسمية المعتمدة لتطبيق PMP PAINTS لأجهزة أندرويد.\n• تحديث مباشر وسلس مع الحفاظ الكامل على كافة البيانات والحسابات.';

  const previewScreens = [
    {
      src: resolveAssetUrl('assets/preview_dashboard.png'),
      title: language === 'ar' ? 'لوحة القيادة الرئيسية' : 'Executive Dashboard',
      subtitle: language === 'ar' ? 'متابعة فورية لأرصدة الخزن والمخازن والمبيعات' : 'Real-time overview of vaults, inventory & sales'
    },
    {
      src: resolveAssetUrl('assets/preview_factory.png'),
      title: language === 'ar' ? 'المصنع والتعبئة القياسية' : 'Factory & Standard Packaging',
      subtitle: language === 'ar' ? 'إدارة أوامر الإنتاج والأوزان (250جم • 900جم • 4كجم)' : 'Production orders & standard packaging weights'
    },
    {
      src: resolveAssetUrl('assets/preview_treasury.png'),
      title: language === 'ar' ? 'الخزن والتحويلات المالية' : 'Multi-Currency Treasury',
      subtitle: language === 'ar' ? 'خزنة السودان ومصر والدولار وإقفال اليومية' : 'Sudan, Egypt & USD vaults with daily closings'
    },
    {
      src: resolveAssetUrl('assets/preview_users.png'),
      title: language === 'ar' ? 'إدارة الموظفين والفروع' : 'Staff & Branch Management',
      subtitle: language === 'ar' ? 'تنظيم صلاحيات العمل لكل موظف وفرع بدقة' : 'Organized employee roles and branch permissions'
    }
  ];

  return (
    <section
      id="download"
      style={{
        padding: '60px 20px',
        maxWidth: '1080px',
        margin: '0 auto'
      }}
    >
      {/* Header */}
      <div style={{ textAlign: 'center', marginBottom: '36px' }}>
        <GlassBadge
          text={language === 'ar' ? 'التحميل المباشر لتطبيق الأندرويد' : 'Direct Android App Download'}
          variant="red"
        />
        <h2
          style={{
            fontSize: 'clamp(24px, 3.5vw, 36px)',
            fontWeight: 900,
            color: 'var(--text-primary)',
            marginTop: '12px',
            marginBottom: '10px'
          }}
        >
          {language === 'ar'
            ? 'تنزيل تطبيق PMP PAINTS الرسمي لأجهزة أندرويد'
            : 'Download Official PMP PAINTS Android Application'}
        </h2>
        <p style={{ fontSize: '15px', color: 'var(--text-secondary)', maxWidth: '680px', margin: '0 auto' }}>
          {language === 'ar'
            ? 'احصل على النسخة الرسمية المعتمدة لهواتف وأجهزة التابلت الخاصة بمنسوبي وفروع شركة PMP Paints بنقرة واحدة مباشرة.'
            : 'Get the official Android application for PMP Paints branches and staff with a single direct tap.'}
        </p>
      </div>

      {/* Main Direct Download Card */}
      <GlassCard
        style={{
          padding: '32px 28px',
          background: 'var(--gradient-card)',
          border: '1px solid var(--border-highlight)',
          marginBottom: '36px'
        }}
      >
        <div
          style={{
            display: 'flex',
            flexDirection: 'column',
            alignItems: 'center',
            textAlign: 'center',
            gap: '24px'
          }}
        >
          {/* App Identity Header */}
          <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', gap: '12px' }}>
            <div
              style={{
                width: '76px',
                height: '76px',
                borderRadius: '20px',
                background: '#FFFFFF',
                border: '2px solid var(--pmp-red)',
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'center',
                boxShadow: '0 10px 28px rgba(220, 38, 38, 0.28)'
              }}
            >
              <img
                src={resolveAssetUrl('assets/ic_pmp_emblem.png')}
                alt="PMP App Icon"
                style={{ width: '58px', height: '58px', objectFit: 'contain' }}
              />
            </div>
            <div>
              <div style={{ display: 'inline-flex', alignItems: 'center', gap: '8px', flexWrap: 'wrap', justifyContent: 'center' }}>
                <h3 style={{ fontSize: '24px', fontWeight: 900, color: 'var(--text-primary)' }}>
                  PMP PAINTS
                </h3>
                <GlassBadge
                  text={language === 'ar' ? 'نسخة رسمية معتمدة' : 'Official Verified App'}
                  variant="green"
                />
              </div>
              <p style={{ fontSize: '13.5px', color: 'var(--text-secondary)', marginTop: '4px' }}>
                {APP_VERSION_CONTRACT.COMPANY_NAME_AR}
              </p>
            </div>
          </div>

          {/* Simple User-Facing Release Info Grid */}
          <div
            style={{
              display: 'grid',
              gridTemplateColumns: 'repeat(auto-fit, minmax(170px, 1fr))',
              gap: '12px',
              width: '100%',
              maxWidth: '760px'
            }}
          >
            <div
              style={{
                padding: '14px',
                borderRadius: '14px',
                background: 'var(--surface-primary)',
                border: '1px solid var(--border-subtle)'
              }}
            >
              <div style={{ fontSize: '11.5px', color: 'var(--text-muted)', marginBottom: '4px' }}>
                {language === 'ar' ? 'الإصدار الحالي' : 'Current Version'}
              </div>
              <div
                data-testid="apk-version-name"
                style={{ fontSize: '17px', fontWeight: 900, color: 'var(--pmp-green)' }}
              >
                {displayVersionName}
              </div>
            </div>

            <div
              style={{
                padding: '14px',
                borderRadius: '14px',
                background: 'var(--surface-primary)',
                border: '1px solid var(--border-subtle)'
              }}
            >
              <div style={{ fontSize: '11.5px', color: 'var(--text-muted)', marginBottom: '4px', display: 'flex', alignItems: 'center', justifyContent: 'center', gap: '4px' }}>
                <HardDrive size={13} color="var(--pmp-cyan)" />
                <span>{language === 'ar' ? 'حجم التحميل' : 'File Size'}</span>
              </div>
              <div
                data-testid="apk-file-size"
                style={{ fontSize: '17px', fontWeight: 900, color: 'var(--text-primary)' }}
              >
                {displaySizeMb}
              </div>
            </div>

            <div
              style={{
                padding: '14px',
                borderRadius: '14px',
                background: 'var(--surface-primary)',
                border: '1px solid var(--border-subtle)'
              }}
            >
              <div style={{ fontSize: '11.5px', color: 'var(--text-muted)', marginBottom: '4px', display: 'flex', alignItems: 'center', justifyContent: 'center', gap: '4px' }}>
                <Calendar size={13} color="var(--pmp-gold)" />
                <span>{language === 'ar' ? 'تاريخ التحديث' : 'Updated On'}</span>
              </div>
              <div style={{ fontSize: '16px', fontWeight: 800, color: 'var(--text-primary)' }}>
                {displayReleaseDate}
              </div>
            </div>

            <div
              style={{
                padding: '14px',
                borderRadius: '14px',
                background: 'var(--surface-primary)',
                border: '1px solid var(--border-subtle)'
              }}
            >
              <div style={{ fontSize: '11.5px', color: 'var(--text-muted)', marginBottom: '4px', display: 'flex', alignItems: 'center', justifyContent: 'center', gap: '4px' }}>
                <Smartphone size={13} color="var(--pmp-red-light)" />
                <span>{language === 'ar' ? 'النظام المدعوم' : 'Compatibility'}</span>
              </div>
              <div style={{ fontSize: '15px', fontWeight: 800, color: 'var(--text-primary)' }}>
                {language === 'ar' ? 'أندرويد 8.0 فما فوق' : 'Android 8.0+'}
              </div>
            </div>
          </div>

          {/* Primary Direct Download CTA Button */}
          <div style={{ width: '100%', maxWidth: '520px', display: 'flex', flexDirection: 'column', gap: '12px' }}>
            <a
              href={directDownloadUrl}
              download={APP_VERSION_CONTRACT.DOWNLOAD_APK_FILENAME}
              data-testid="primary-apk-download-btn"
              style={{
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'center',
                gap: '12px',
                padding: '18px 28px',
                borderRadius: '16px',
                background: 'var(--gradient-red)',
                color: '#FFFFFF',
                fontWeight: 900,
                fontSize: '17px',
                textDecoration: 'none',
                boxShadow: '0 10px 30px rgba(220, 38, 38, 0.4)',
                transition: 'transform 0.2s ease'
              }}
            >
              <Download size={22} />
              <span>
                {language === 'ar'
                  ? `تنزيل تطبيق الأندرويد مباشرة (${displayVersionName})`
                  : `Direct Download Android App (${displayVersionName})`}
              </span>
            </a>

            <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'center', gap: '16px', flexWrap: 'wrap', fontSize: '12.5px', color: 'var(--text-secondary)' }}>
              <span style={{ display: 'inline-flex', alignItems: 'center', gap: '5px' }}>
                <CheckCircle2 size={15} color="var(--pmp-green)" />
                {language === 'ar' ? 'تنزيل مباشر وفوري بدون تحويل' : 'Instant Direct Download'}
              </span>
              <span style={{ display: 'inline-flex', alignItems: 'center', gap: '5px' }}>
                <ShieldCheck size={15} color="var(--pmp-cyan)" />
                {language === 'ar' ? 'يدعم الترقية مع حفظ البيانات' : 'Preserves All Existing Data'}
              </span>
              <button
                type="button"
                onClick={() => refreshReleases()}
                disabled={isLoading}
                style={{
                  background: 'none',
                  border: 'none',
                  color: 'var(--pmp-cyan-light)',
                  fontSize: '12px',
                  fontWeight: 700,
                  cursor: 'pointer',
                  display: 'inline-flex',
                  alignItems: 'center',
                  gap: '4px'
                }}
              >
                <RefreshCw size={13} className={isLoading ? 'animate-spin' : ''} />
                <span>{language === 'ar' ? 'تحديث الحالة' : 'Refresh'}</span>
              </button>
            </div>
          </div>

          {/* Clean Release Summary */}
          <div
            style={{
              width: '100%',
              maxWidth: '760px',
              padding: '16px 20px',
              borderRadius: '14px',
              background: 'var(--surface-primary)',
              border: '1px solid var(--border-subtle)',
              textAlign: 'start'
            }}
          >
            <div style={{ fontSize: '13px', fontWeight: 800, color: 'var(--text-primary)', marginBottom: '6px' }}>
              {language === 'ar' ? 'مميزات هذا الإصدار:' : 'Release Highlights:'}
            </div>
            <div
              style={{
                fontSize: '13px',
                color: 'var(--text-secondary)',
                lineHeight: 1.75,
                whiteSpace: 'pre-line'
              }}
            >
              {displayNotes}
            </div>
          </div>
        </div>
      </GlassCard>

      {/* Real Application Screenshots Showcase */}
      <div>
        <div style={{ textAlign: 'center', marginBottom: '24px' }}>
          <h3 style={{ fontSize: '20px', fontWeight: 900, color: 'var(--text-primary)' }}>
            {language === 'ar'
              ? 'صور من داخل تطبيق الأندرويد'
              : 'Inside the Android Application'}
          </h3>
          <p style={{ fontSize: '13.5px', color: 'var(--text-secondary)', marginTop: '4px' }}>
            {language === 'ar'
              ? 'واجهات عمل واضحة وسريعة مصممة لخدمة الفروع والمصنع والمخازن'
              : 'Clean, fast interfaces designed for branches, factory, and warehouses'}
          </p>
        </div>

        <div
          style={{
            display: 'grid',
            gridTemplateColumns: 'repeat(auto-fit, minmax(220px, 1fr))',
            gap: '20px'
          }}
        >
          {previewScreens.map((item, idx) => (
            <GlassCard
              key={idx}
              style={{
                padding: '14px',
                background: 'var(--surface-card)',
                display: 'flex',
                flexDirection: 'column',
                justifyContent: 'space-between'
              }}
            >
              <div
                style={{
                  borderRadius: '12px',
                  overflow: 'hidden',
                  border: '1px solid var(--border-subtle)',
                  marginBottom: '12px',
                  background: '#000000'
                }}
              >
                <img
                  src={item.src}
                  alt={item.title}
                  loading="lazy"
                  style={{
                    width: '100%',
                    height: 'auto',
                    display: 'block',
                    objectFit: 'cover'
                  }}
                />
              </div>
              <div>
                <h4 style={{ fontSize: '14px', fontWeight: 800, color: 'var(--text-primary)', marginBottom: '4px' }}>
                  {item.title}
                </h4>
                <p style={{ fontSize: '12px', color: 'var(--text-secondary)', lineHeight: 1.5 }}>
                  {item.subtitle}
                </p>
              </div>
            </GlassCard>
          ))}
        </div>
      </div>
    </section>
  );
};
