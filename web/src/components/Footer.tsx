import React from 'react';
import { useApp } from '../context/AppContext';
import { ShieldCheck, Cloud, GitBranch, KeyRound, ExternalLink } from 'lucide-react';

export const Footer: React.FC = () => {
  const { lang, setCurrentSection } = useApp();
  const isAr = lang === 'ar';

  return (
    <footer style={{
      borderTop: '1px solid var(--border-subtle)',
      background: 'var(--surface-primary)',
      padding: '48px 24px 28px',
      marginTop: 64
    }}>
      <div style={{
        maxWidth: 1300,
        margin: '0 auto',
        display: 'grid',
        gridTemplateColumns: 'repeat(auto-fit, minmax(260px, 1fr))',
        gap: 36,
        marginBottom: 40
      }}>
        {/* Column 1: Brand & Overview */}
        <div>
          <div style={{ display: 'flex', alignItems: 'center', gap: 12, marginBottom: 16 }}>
            <div style={{
              width: 38,
              height: 38,
              borderRadius: 10,
              background: '#FFFFFF',
              border: '1px solid rgba(220, 38, 38, 0.45)',
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'center'
            }}>
              <img src="/ic_pmp_emblem.png" alt="PMP Logo" style={{ width: 30, height: 30, objectFit: 'contain' }} />
            </div>
            <div>
              <div style={{ fontSize: 16, fontWeight: 900 }}>
                PMP <span style={{ color: 'var(--pmp-red)' }}>PAINTS</span> ERP
              </div>
              <div style={{ fontSize: 11, color: 'var(--text-muted)' }}>
                {isAr ? 'الصناعات الكيماوية والطلاءات' : 'Chemicals & Coatings Industries'}
              </div>
            </div>
          </div>
          <p style={{ fontSize: 13, color: 'var(--text-muted)', lineHeight: 1.7, marginBottom: 16 }}>
            {isAr
              ? 'المنظومة السحابية المتكاملة لإدارة الحسابات، الخزن النقدية، المصانع والمخازن لشركة PMP Paints في السودان ومصر. تدعم العمل المتسلسل غير المنقطع مع حماية البيانات ومزامنة فورية.'
              : 'Enterprise ERP cloud platform managing treasury, factories, chemistry lab, supply chains, and warehouses across Sudan and Egypt with real-time replication.'}
          </p>
          <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
            <span className="glass-badge" style={{ background: 'var(--pmp-green-soft)', color: 'var(--pmp-green)', border: '1px solid var(--pmp-green)' }}>
              <ShieldCheck size={12} /> {isAr ? 'نظام مشفر ومؤمن' : 'Encrypted & Audited'}
            </span>
            <span className="glass-badge" style={{ background: 'var(--pmp-cyan-soft)', color: 'var(--pmp-cyan-light)', border: '1px solid var(--pmp-cyan)' }}>
              <Cloud size={12} /> Google Cloud Firestore
            </span>
          </div>
        </div>

        {/* Column 2: Quick Links */}
        <div>
          <h4 style={{ fontSize: 14, fontWeight: 800, color: 'var(--text-primary)', marginBottom: 14 }}>
            {isAr ? 'أقسام المنصة السحابية' : 'Platform Navigation'}
          </h4>
          <ul style={{ listStyle: 'none', display: 'flex', flexDirection: 'column', gap: 10, fontSize: 13 }}>
            <li>
              <button
                onClick={() => setCurrentSection('home')}
                style={{ background: 'none', border: 'none', color: 'var(--text-secondary)', cursor: 'pointer', textAlign: isAr ? 'right' : 'left' }}
              >
                {isAr ? '• الصفحة الرئيسية' : '• Home Page'}
              </button>
            </li>
            <li>
              <button
                onClick={() => setCurrentSection('features')}
                style={{ background: 'none', border: 'none', color: 'var(--text-secondary)', cursor: 'pointer', textAlign: isAr ? 'right' : 'left' }}
              >
                {isAr ? '• دليل الوحدات الـ 12 المعتمدة' : '• 12 Enterprise Modules'}
              </button>
            </li>
            <li>
              <button
                onClick={() => setCurrentSection('download')}
                style={{ background: 'none', border: 'none', color: 'var(--text-secondary)', cursor: 'pointer', textAlign: isAr ? 'right' : 'left' }}
              >
                {isAr ? '• تحميل حزمة APK المعتمدة (v18.0)' : '• Download Certified APK (v18.0)'}
              </button>
            </li>
            <li>
              <button
                onClick={() => setCurrentSection('whats-new')}
                style={{ background: 'none', border: 'none', color: 'var(--text-secondary)', cursor: 'pointer', textAlign: isAr ? 'right' : 'left' }}
              >
                {isAr ? '• سجل الإصدارات والتحديثات السحابية' : '• Releases & Changelog'}
              </button>
            </li>
            <li>
              <button
                onClick={() => setCurrentSection('install-guide')}
                style={{ background: 'none', border: 'none', color: 'var(--text-secondary)', cursor: 'pointer', textAlign: isAr ? 'right' : 'left' }}
              >
                {isAr ? '• دليل التثبيت وإعدادات الأجهزة' : '• Device Setup Guide'}
              </button>
            </li>
          </ul>
        </div>

        {/* Column 3: Architecture & Security Badges */}
        <div>
          <h4 style={{ fontSize: 14, fontWeight: 800, color: 'var(--text-primary)', marginBottom: 14 }}>
            {isAr ? 'الهوية البرمجية والتوقيع الرقمي' : 'Release Contract & Integrity'}
          </h4>
          <div style={{ display: 'flex', flexDirection: 'column', gap: 10, fontSize: 12 }}>
            <div style={{
              background: 'var(--surface-elevated)',
              border: '1px solid var(--border-subtle)',
              borderRadius: 'var(--radius-md)',
              padding: '10px 12px'
            }}>
              <div style={{ color: 'var(--text-muted)', fontSize: 11, marginBottom: 2 }}>
                {isAr ? 'معرّف التطبيق المعتمد (Application ID):' : 'Application Package ID:'}
              </div>
              <div style={{ fontWeight: 800, color: 'var(--pmp-cyan-light)', fontFamily: 'monospace' }}>
                com.aistudio.pmppaints.erp
              </div>
            </div>

            <div style={{
              background: 'var(--surface-elevated)',
              border: '1px solid var(--border-subtle)',
              borderRadius: 'var(--radius-md)',
              padding: '10px 12px'
            }}>
              <div style={{ color: 'var(--text-muted)', fontSize: 11, marginBottom: 2 }}>
                {isAr ? 'بصمة شهادة التوقيع الرسمية (SHA-256):' : 'Signing Certificate SHA-256:'}
              </div>
              <div style={{ fontSize: 10.5, color: 'var(--pmp-gold)', wordBreak: 'break-all', fontFamily: 'monospace' }}>
                1F:F7:75:2A:F4:96:A6:A7:4F:DF:5F:62:07:10:C7:47:83:03:1E:83:4B:2D:6D:A0:5A:AD:DB:57:A1:0D:11:05
              </div>
            </div>

            <div style={{ display: 'flex', gap: 8 }}>
              <a
                href="https://github.com/ow7ab/ow7ab"
                target="_blank"
                rel="noreferrer"
                className="glass-btn glass-btn-secondary"
                style={{ flex: 1, padding: '6px 10px', fontSize: 11.5 }}
              >
                <GitBranch size={13} /> GitHub Repo <ExternalLink size={11} />
              </a>
              <button
                onClick={() => setCurrentSection('support')}
                className="glass-btn glass-btn-secondary"
                style={{ flex: 1, padding: '6px 10px', fontSize: 11.5 }}
              >
                <KeyRound size={13} /> {isAr ? 'إجراءات الأمان' : 'Security'}
              </button>
            </div>
          </div>
        </div>
      </div>

      {/* Copyright Bar */}
      <div style={{
        maxWidth: 1300,
        margin: '0 auto',
        paddingTop: 20,
        borderTop: '1px solid var(--border-subtle)',
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'space-between',
        flexWrap: 'wrap',
        gap: 12,
        fontSize: 12,
        color: 'var(--text-muted)'
      }}>
        <div>
          © {new Date().getFullYear()} PMP PAINTS FOR CHEMICAL INDUSTRIES. {isAr ? 'جميع الحقوق محفوظة.' : 'All rights reserved.'}
        </div>
        <div>
          {isAr
            ? 'المنظومة السحابية الرسمية — متصلة مباشرة بسيرفر Google Cloud المركزي'
            : 'Official Cloud Enterprise Hub — Directly Connected to Google Cloud'}
        </div>
      </div>
    </footer>
  );
};
