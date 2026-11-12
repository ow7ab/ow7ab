import React, { useState, useEffect } from 'react';
import {
  Download,
  ShieldCheck,
  Cpu,
  Smartphone,
  CheckCircle2,
  Copy,
  Check,
  ExternalLink,
  RefreshCw,
  AlertCircle,
  FileCheck
} from 'lucide-react';
import { GlassCard } from '../ui/GlassCard';
import { GlassButton } from '../ui/GlassButton';
import { GlassBadge } from '../ui/GlassBadge';
import { APP_VERSION_CONTRACT, getReleaseDownloadUrl } from '../../config/appVersionContract';
import { useLanguage } from '../../context/LanguageContext';

export const DownloadApkSection: React.FC = () => {
  const { language } = useLanguage();
  const [copiedHash, setCopiedHash] = useState(false);
  const [liveRelease, setLiveRelease] = useState({
    versionName: APP_VERSION_CONTRACT.versionName,
    versionCode: APP_VERSION_CONTRACT.versionCode,
    releaseDate: APP_VERSION_CONTRACT.releaseDate,
    downloadSize: APP_VERSION_CONTRACT.downloadSize,
    sha256: APP_VERSION_CONTRACT.SIGNING_CERT_SHA256,
    downloadUrl: getReleaseDownloadUrl(),
    isVerified: true
  });
  const [isFetchingRelease, setIsFetchingRelease] = useState(false);

  useEffect(() => {
    // Attempt dynamic fetch from /version.json or GitHub API
    const fetchLatestVerifiedMetadata = async () => {
      try {
        setIsFetchingRelease(true);
        const res = await fetch('/version.json');
        if (res.ok) {
          const data = await res.json();
          if (data && data.versionCode) {
            setLiveRelease({
              versionName: data.versionName || APP_VERSION_CONTRACT.versionName,
              versionCode: data.versionCode || APP_VERSION_CONTRACT.versionCode,
              releaseDate: data.publishedAt ? data.publishedAt.split('T')[0] : APP_VERSION_CONTRACT.releaseDate,
              downloadSize: data.fileSizeMb ? `~${data.fileSizeMb} MB` : APP_VERSION_CONTRACT.downloadSize,
              sha256: data.sha256 || data.signingCertSha256 || APP_VERSION_CONTRACT.SIGNING_CERT_SHA256,
              downloadUrl: data.rawPublicApkUrl || data.downloadUrl || getReleaseDownloadUrl(),
              isVerified: Boolean(data.releaseStatus === 'PUBLISHED' || !data.releaseStatus)
            });
          }
        }
      } catch (err) {
        // Fallback to static verified contract
      } finally {
        setIsFetchingRelease(false);
      }
    };

    fetchLatestVerifiedMetadata();
  }, []);

  const handleCopyHash = () => {
    navigator.clipboard.writeText(liveRelease.sha256);
    setCopiedHash(true);
    setTimeout(() => setCopiedHash(false), 2500);
  };

  return (
    <section id="download" className="py-20 relative">
      <div className="max-w-6xl mx-auto px-4 sm:px-6 lg:px-8">
        <div className="text-center max-w-3xl mx-auto mb-14">
          <GlassBadge variant="primary" className="mb-4">
            {language === 'ar' ? 'التحميل الرسمي المعتمد' : 'Official Verified Release'}
          </GlassBadge>
          <h2 className="text-3xl sm:text-4xl font-extrabold text-theme-primary tracking-tight">
            {language === 'ar' ? 'حمّل أحدث إصدار من تطبيق PMP Paints للآندرويد' : 'Download the Official PMP Paints Android APK'}
          </h2>
          <p className="mt-4 text-base sm:text-lg text-theme-secondary">
            {language === 'ar'
              ? 'تطبيق إدارة المصنع والمستودعات والمبيعات المحمي برقم إصدار رسمي وحزمة موثقة بشهادة تشفير معتمدة.'
              : 'Enterprise factory, warehouse, and sales management app verified with official certificate signing.'}
          </p>
        </div>

        <div className="grid grid-cols-1 lg:grid-cols-12 gap-8 items-start">
          {/* Main Download Card */}
          <div className="lg:col-span-7">
            <GlassCard variant="highlight" className="p-8 relative overflow-hidden">
              <div className="flex flex-wrap items-center justify-between gap-4 mb-6 pb-6 border-b border-theme">
                <div>
                  <span className="text-xs uppercase tracking-wider text-theme-muted font-bold block">
                    {language === 'ar' ? 'الإصدار المعتمد المستقر' : 'Verified Stable Release'}
                  </span>
                  <div className="flex items-center gap-3 mt-1">
                    <span className="text-2xl font-black text-theme-primary">
                      {liveRelease.versionName}
                    </span>
                    <GlassBadge variant="success">
                      {language === 'ar' ? `كود الإصدار: ${liveRelease.versionCode}` : `Build ${liveRelease.versionCode}`}
                    </GlassBadge>
                  </div>
                </div>
                <div className="text-left">
                  <span className="text-xs uppercase tracking-wider text-theme-muted font-bold block">
                    {language === 'ar' ? 'تاريخ النشر' : 'Release Date'}
                  </span>
                  <span className="text-sm font-semibold text-theme-secondary mt-1 block">
                    {liveRelease.releaseDate}
                  </span>
                </div>
              </div>

              {/* Specs Grid */}
              <div className="grid grid-cols-2 sm:grid-cols-3 gap-4 mb-8">
                <div className="p-3 rounded-lg bg-theme-surface border border-theme">
                  <div className="flex items-center gap-2 text-theme-muted mb-1 text-xs">
                    <Smartphone className="w-4 h-4 text-pmp-primary" />
                    <span>{language === 'ar' ? 'الحد الأدنى للنظام' : 'Min Android'}</span>
                  </div>
                  <span className="text-sm font-bold text-theme-primary">
                    Android 8.0+ (API {APP_VERSION_CONTRACT.MIN_ANDROID_SDK})
                  </span>
                </div>

                <div className="p-3 rounded-lg bg-theme-surface border border-theme">
                  <div className="flex items-center gap-2 text-theme-muted mb-1 text-xs">
                    <Cpu className="w-4 h-4 text-pmp-accent" />
                    <span>{language === 'ar' ? 'المعمارية' : 'Architecture'}</span>
                  </div>
                  <span className="text-sm font-bold text-theme-primary">
                    {APP_VERSION_CONTRACT.supportedArchitectures.join(', ')}
                  </span>
                </div>

                <div className="p-3 rounded-lg bg-theme-surface border border-theme col-span-2 sm:col-span-1">
                  <div className="flex items-center gap-2 text-theme-muted mb-1 text-xs">
                    <FileCheck className="w-4 h-4 text-pmp-success" />
                    <span>{language === 'ar' ? 'حجم الملف' : 'File Size'}</span>
                  </div>
                  <span className="text-sm font-bold text-theme-primary">
                    {liveRelease.downloadSize}
                  </span>
                </div>
              </div>

              {/* Package ID & Hash */}
              <div className="space-y-3 mb-8">
                <div className="p-3 rounded-lg bg-theme-surface border border-theme text-xs font-mono">
                  <div className="flex justify-between items-center text-theme-muted mb-1">
                    <span>{language === 'ar' ? 'معرّف الحزمة الرسمي (Application ID):' : 'Official Application ID:'}</span>
                  </div>
                  <span className="text-pmp-primary font-bold text-sm block">
                    {APP_VERSION_CONTRACT.APPLICATION_ID}
                  </span>
                </div>

                <div className="p-3 rounded-lg bg-theme-surface border border-theme text-xs font-mono">
                  <div className="flex justify-between items-center text-theme-muted mb-1">
                    <span>{language === 'ar' ? 'بصمة الشهادة الرقمية المعتمدة (SHA-256):' : 'Certificate Fingerprint (SHA-256):'}</span>
                    <button
                      onClick={handleCopyHash}
                      className="flex items-center gap-1 text-pmp-accent hover:underline focus:outline-none"
                    >
                      {copiedHash ? (
                        <>
                          <Check className="w-3.5 h-3.5 text-pmp-success" />
                          <span className="text-pmp-success">{language === 'ar' ? 'تم النسخ' : 'Copied'}</span>
                        </>
                      ) : (
                        <>
                          <Copy className="w-3.5 h-3.5" />
                          <span>{language === 'ar' ? 'نسخ' : 'Copy'}</span>
                        </>
                      )}
                    </button>
                  </div>
                  <span className="text-theme-secondary break-all font-mono">
                    {liveRelease.sha256}
                  </span>
                </div>
              </div>

              {/* Actions */}
              <div className="flex flex-col sm:flex-row gap-4">
                <a
                  href={liveRelease.downloadUrl}
                  className="flex-1"
                  target="_blank"
                  rel="noopener noreferrer"
                >
                  <GlassButton
                    variant="primary"
                    size="lg"
                    className="w-full justify-center shadow-lg shadow-pmp-primary/25"
                    icon={<Download className="w-5 h-5" />}
                  >
                    {language === 'ar' ? 'تحميل ملف APK المباشر' : 'Download Verified APK'}
                  </GlassButton>
                </a>

                <a
                  href={APP_VERSION_CONTRACT.GITHUB_RELEASES_URL}
                  target="_blank"
                  rel="noopener noreferrer"
                >
                  <GlassButton
                    variant="secondary"
                    size="lg"
                    className="w-full sm:w-auto justify-center"
                    icon={<ExternalLink className="w-4 h-4" />}
                  >
                    {language === 'ar' ? 'سجل الإصدارات في GitHub' : 'GitHub Releases'}
                  </GlassButton>
                </a>
              </div>
            </GlassCard>
          </div>

          {/* Verification & Trust Column */}
          <div className="lg:col-span-5 space-y-6">
            <GlassCard className="p-6">
              <h3 className="text-lg font-bold text-theme-primary mb-4 flex items-center gap-2">
                <ShieldCheck className="w-5 h-5 text-pmp-success" />
                {language === 'ar' ? 'معايير الأمان والتوافق' : 'Security & Integrity Checks'}
              </h3>
              <ul className="space-y-3.5 text-sm text-theme-secondary">
                <li className="flex items-start gap-2.5">
                  <CheckCircle2 className="w-5 h-5 text-pmp-success shrink-0 mt-0.5" />
                  <span>
                    {language === 'ar'
                      ? 'موقع بشهادة إصدار رسمية ثابتة لمنع تكرار أو استبدال الحزمة.'
                      : 'Signed with a permanent verified release key preventing unauthorized package replacement.'}
                  </span>
                </li>
                <li className="flex items-start gap-2.5">
                  <CheckCircle2 className="w-5 h-5 text-pmp-success shrink-0 mt-0.5" />
                  <span>
                    {language === 'ar'
                      ? 'تشفير كامل لكلمات المرور وقواعد وصول صارمة (Granular RBAC) بـ 11 صلاحية.'
                      : 'Military-grade PBKDF2 password hashing with 11 granular RBAC operation permissions.'}
                  </span>
                </li>
                <li className="flex items-start gap-2.5">
                  <CheckCircle2 className="w-5 h-5 text-pmp-success shrink-0 mt-0.5" />
                  <span>
                    {language === 'ar'
                      ? 'دعم كامل للعمل بدون اتصال (Offline-first) مع مزامنة لحظية فور توفر الشبكة.'
                      : 'Complete offline-first Room database sync with live Firestore cloud updates.'}
                  </span>
                </li>
                <li className="flex items-start gap-2.5">
                  <CheckCircle2 className="w-5 h-5 text-pmp-success shrink-0 mt-0.5" />
                  <span>
                    {language === 'ar'
                      ? 'تحديث تلقائي مدمج داخل التطبيق عبر مدير التحديثات الذكي OTA UpdateManager.'
                      : 'Built-in in-app APK auto-updater via enterprise OTA UpdateManager.'}
                  </span>
                </li>
              </ul>
            </GlassCard>

            <div className="p-5 rounded-xl border border-pmp-warning/30 bg-pmp-warning/5 text-sm">
              <h4 className="font-bold text-pmp-warning mb-1 flex items-center gap-1.5">
                <span>⚠️</span>
                {language === 'ar' ? 'تنبيه تثبيت الأجهزة الجديدة' : 'New Device Installation Note'}
              </h4>
              <p className="text-theme-secondary text-xs leading-relaxed">
                {language === 'ar'
                  ? 'عند تثبيت ملف APK لأول مرة من المتصفح، قد يطلب نظام Android تفعيل خيار "السماح بتثبيت التطبيقات من هذا المصدر". التطبيق معتمد وآمن تماماً ويحتوي على شهادة موثقة.'
                  : 'When installing the APK manually for the first time, Android may prompt to allow "Install unknown apps" for your browser. This application is secure and verified.'}
              </p>
            </div>
          </div>
        </div>
      </div>
    </section>
  );
};
