import React from 'react';
import { useLanguage } from '../../context/LanguageContext';
import { APP_VERSION_CONTRACT } from '../../config/appVersionContract';
import { ShieldCheck, GitBranch, MapPin, ExternalLink, Activity, Smartphone } from 'lucide-react';

export const Footer: React.FC = () => {
  const { t, language } = useLanguage();

  return (
    <footer
      style={{
        background: 'var(--surface-primary)',
        borderTop: '1px solid var(--border-subtle)',
        padding: '48px 20px 24px 20px',
        marginTop: '60px'
      }}
    >
      <div
        style={{
          maxWidth: '1240px',
          margin: '0 auto',
          display: 'grid',
          gridTemplateColumns: 'repeat(auto-fit, minmax(260px, 1fr))',
          gap: '32px',
          marginBottom: '40px'
        }}
      >
        {/* Col 1: Identity & Certificate */}
        <div>
          <div style={{ display: 'flex', alignItems: 'center', gap: '10px', marginBottom: '14px' }}>
            <img
              src="/assets/ic_pmp_emblem.png"
              alt="PMP Logo"
              style={{ width: '32px', height: '32px', objectFit: 'contain' }}
            />
            <span style={{ fontSize: '18px', fontWeight: 900 }}>
              PMP <span style={{ color: 'var(--pmp-red)' }}>PAINTS</span>
            </span>
          </div>

          <p style={{ fontSize: '13px', color: 'var(--text-secondary)', lineHeight: 1.6, marginBottom: '16px' }}>
            {APP_VERSION_CONTRACT.COMPANY_NAME_AR} — المنظومة السحابية المعتمدة لإدارة وتصنيع الدهانات الصناعية والإنشائية، وتتبع دورة الخامات والمبيعات والسيولة النقدية.
          </p>

          <div
            style={{
              padding: '10px 14px',
              borderRadius: '12px',
              background: 'var(--surface-elevated)',
              border: '1px solid var(--border-subtle)',
              fontSize: '11px',
              fontFamily: 'monospace',
              color: 'var(--pmp-cyan-light)',
              wordBreak: 'break-all'
            }}
          >
            <div style={{ display: 'flex', alignItems: 'center', gap: '6px', color: 'var(--text-muted)', marginBottom: '4px', fontFamily: 'var(--font-family)' }}>
              <ShieldCheck size={14} color="var(--pmp-green)" />
              <span>SHA-256 Signing Certificate:</span>
            </div>
            {APP_VERSION_CONTRACT.SIGNING_CERT_SHA256}
          </div>
        </div>

        {/* Col 2: Branches & Distribution */}
        <div>
          <h4 style={{ fontSize: '15px', fontWeight: 800, marginBottom: '16px', color: 'var(--text-primary)' }}>
            {language === 'ar' ? 'فروع ومراكز التشغيل' : 'Operational Branches'}
          </h4>
          <ul style={{ listStyle: 'none', display: 'flex', flexDirection: 'column', gap: '10px', fontSize: '13px', color: 'var(--text-secondary)' }}>
            <li style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
              <MapPin size={15} color="var(--pmp-red-light)" />
              <span>فرع السودان (SD-001) — الإدارة المركزية</span>
            </li>
            <li style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
              <MapPin size={15} color="var(--pmp-cyan)" />
              <span>فرع مصر والمصنع (EG-001) — مجمع خطوط الإنتاج الكيماوي</span>
            </li>
            <li style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
              <MapPin size={15} color="var(--pmp-gold)" />
              <span>مخزن حلفا (HF-001) — محطة الترحيل والتبادل الحدودي</span>
            </li>
            <li style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
              <MapPin size={15} color="var(--pmp-green)" />
              <span>مخزن عطبرة (AT-002) — مركز التوزيع الإقليمي</span>
            </li>
          </ul>
        </div>

        {/* Col 3: Cloud & Release Channels */}
        <div>
          <h4 style={{ fontSize: '15px', fontWeight: 800, marginBottom: '16px', color: 'var(--text-primary)' }}>
            {language === 'ar' ? 'البنية السحابية والإصدارات' : 'Cloud Architecture & Channels'}
          </h4>
          <ul style={{ listStyle: 'none', display: 'flex', flexDirection: 'column', gap: '10px', fontSize: '13px', color: 'var(--text-secondary)' }}>
            <li style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
              <Activity size={15} color="var(--pmp-green)" />
              <span>Google Cloud Firestore Cluster: <strong style={{ color: 'var(--text-primary)' }}>PMP-ERP-MAIN-CLUSTER</strong></span>
            </li>
            <li style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
              <Smartphone size={15} color="var(--pmp-cyan)" />
              <span>Application ID: <strong style={{ color: 'var(--text-primary)' }}>{APP_VERSION_CONTRACT.APPLICATION_ID}</strong></span>
            </li>
            <li style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
              <GitBranch size={15} color="var(--pmp-gold)" />
              <a
                href={APP_VERSION_CONTRACT.GITHUB_REPO_URL}
                target="_blank"
                rel="noreferrer"
                style={{ display: 'inline-flex', alignItems: 'center', gap: '4px', color: 'var(--pmp-cyan-light)', textDecoration: 'underline' }}
              >
                <span>مستودع GitHub الرسمي (ow7ab/ow7ab)</span>
                <ExternalLink size={12} />
              </a>
            </li>
            <li style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
              <ShieldCheck size={15} color="var(--pmp-red-light)" />
              <a
                href={APP_VERSION_CONTRACT.GITHUB_RELEASES_URL}
                target="_blank"
                rel="noreferrer"
                style={{ display: 'inline-flex', alignItems: 'center', gap: '4px', color: 'var(--pmp-cyan-light)', textDecoration: 'underline' }}
              >
                <span>مركز الإصدارات وحزم APK الموقعة</span>
                <ExternalLink size={12} />
              </a>
            </li>
          </ul>
        </div>
      </div>

      <div
        style={{
          maxWidth: '1240px',
          margin: '0 auto',
          paddingTop: '20px',
          borderTop: '1px solid var(--border-subtle)',
          display: 'flex',
          justifyContent: 'space-between',
          alignItems: 'center',
          flexWrap: 'wrap',
          gap: '12px',
          fontSize: '12px',
          color: 'var(--text-muted)'
        }}
      >
        <div>{t('footer_rights')}</div>
        <div>
          <span>{APP_VERSION_CONTRACT.RELEASE_TITLE} • Android 8.0+ (API 26+) & Modern Web Portal</span>
        </div>
      </div>
    </footer>
  );
};
