import React, { useState } from 'react';
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
  FileCheck,
  KeyRound,
  FileSearch
} from 'lucide-react';
import { GlassCard } from '../ui/GlassCard';
import { GlassButton } from '../ui/GlassButton';
import { GlassBadge } from '../ui/GlassBadge';
import { APP_VERSION_CONTRACT, resolveAssetUrl } from '../../config/appVersionContract';
import { useLanguage } from '../../context/LanguageContext';
import { useLatestRelease } from '../../hooks/useLatestRelease';
import { computeFileSha256Hex, formatReleaseDate } from '../../services/ReleaseService';

export const DownloadApkSection: React.FC = () => {
  const { language } = useLanguage();
  const { latestRelease, apkAvailable, unavailableReason, isLoading, refresh } = useLatestRelease();

  const [copiedHash, setCopiedHash] = useState(false);
  const [copiedCert, setCopiedCert] = useState(false);
  const [localVerifyState, setLocalVerifyState] = useState<{
    status: 'IDLE' | 'CHECKING' | 'MATCH' | 'MISMATCH' | 'ERROR';
    message: string;
  }>({ status: 'IDLE', message: '' });

  const displayVersionName = latestRelease?.versionName || APP_VERSION_CONTRACT.versionName;
  const displayVersionCode = latestRelease?.versionCode || APP_VERSION_CONTRACT.versionCode;
  const displayDate = formatReleaseDate(latestRelease?.publishedAt);
  const displaySize = latestRelease?.fileSizeMb
    ? `${latestRelease.fileSizeMb} MB`
    : APP_VERSION_CONTRACT.downloadSize;
  const displayApkSha256 = latestRelease?.apkSha256 || '';
  const displayCertSha256 = latestRelease?.signingCertSha256 || APP_VERSION_CONTRACT.SIGNING_CERT_SHA256;
  const displayDownloadUrl = latestRelease?.downloadUrl || APP_VERSION_CONTRACT.GITHUB_LATEST_APK_URL;
  const displayBuildId = latestRelease?.buildId || `PMP-${displayVersionName}-b${displayVersionCode}`;

  const handleCopyApkHash = () => {
    const val = displayApkSha256 || displayCertSha256;
    navigator.clipboard.writeText(val);
    setCopiedHash(true);
    setTimeout(() => setCopiedHash(false), 2500);
  };

  const handleCopyCertHash = () => {
    navigator.clipboard.writeText(displayCertSha256);
    setCopiedCert(true);
    setTimeout(() => setCopiedCert(false), 2500);
  };

  const handleVerifyLocalFile = async (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (!file) return;

    setLocalVerifyState({
      status: 'CHECKING',
      message:
        language === 'ar'
          ? `جاري حساب بصمة SHA-256 للملف (${file.name})...`
          : `Computing SHA-256 digest for ${file.name}...`
    });

    try {
      const computedHex = await computeFileSha256Hex(file);
      if (displayApkSha256 && computedHex === displayApkSha256.toLowerCase()) {
        setLocalVerifyState({
          status: 'MATCH',
          message:
            language === 'ar'
              ? `✅ الملف سليم ومطابق 100% لبصمة الإصدار الرسمي (${computedHex}).`
              : `✅ Verified! File SHA-256 matches the official stable release (${computedHex}).`
        });
      } else if (!displayApkSha256) {
        setLocalVerifyState({
          status: 'MATCH',
          message:
            language === 'ar'
              ? `تم احتساب بصمة الملف: ${computedHex} (يمكنك مطابقتها مع سجل GitHub Release).`
              : `Computed file SHA-256: ${computedHex}`
        });
      } else {
        setLocalVerifyState({
          status: 'MISMATCH',
          message:
            language === 'ar'
              ? `❌ تحذير: بصمة الملف المختار (${computedHex}) لا تطابق بصمة الإصدار الرسمي (${displayApkSha256}). يرجى إعادة التحميل.`
              : `❌ Mismatch! Computed SHA-256 (${computedHex}) does not match official release (${displayApkSha256}).`
        });
      }
    } catch (err: any) {
      setLocalVerifyState({
        status: 'ERROR',
        message: err?.message || 'Verification error'
      });
    }
  };

  const brandVisuals = [
    {
      titleAr: 'واجهة منظومة المصنع والسيولة',
      titleEn: 'Enterprise Brand Poster',
      src: resolveAssetUrl('assets/pmp_brand_poster.jpg')
    },
    {
      titleAr: 'الواجهة المرجعية وتقارير التشغيل',
      titleEn: 'Reference ERP Preview',
      src: resolveAssetUrl('assets/pmp_reference_poster.jpg')
    },
    {
      titleAr: 'الشعار الرسمي المعتمد',
      titleEn: 'Official PMP Identity',
      src: resolveAssetUrl('assets/pmp_brand_logo.jpg')
    }
  ];

  return (
    <section id="download" className="py-20 relative">
      <div className="max-w-6xl mx-auto px-4 sm:px-6 lg:px-8">
        <div className="text-center max-w-3xl mx-auto mb-14">
          <GlassBadge variant="primary" className="mb-4">
            {language === 'ar' ? 'التحميل الرسمي المعتمد — متصل بـ GitHub Releases' : 'Official Verified Release — Live GitHub Feed'}
          </GlassBadge>
          <h2 className="text-3xl sm:text-4xl font-extrabold text-theme-primary tracking-tight">
            {language === 'ar' ? 'حمّل أحدث إصدار من تطبيق PMP Paints للآندرويد' : 'Download the Official PMP Paints Android APK'}
          </h2>
          <p className="mt-4 text-base sm:text-lg text-theme-secondary">
            {language === 'ar'
              ? 'إصدارات مستقرة وموقّعة آلياً عبر GitHub Actions (تُستبعد النسخ التجريبية والمسودات تلقائياً) مع فحص كامل لبصمة SHA-256.'
              : 'Stable releases automatically built, signed, and verified via GitHub Actions (drafts & prereleases excluded) with SHA-256 verification.'}
          </p>
        </div>

        <div className="grid grid-cols-1 lg:grid-cols-12 gap-8 items-start">
          {/* Main Download Card */}
          <div className="lg:col-span-7 space-y-6">
            <GlassCard variant="highlight" className="p-6 sm:p-8 relative overflow-hidden">
              <div className="flex flex-wrap items-center justify-between gap-4 mb-6 pb-6 border-b border-theme">
                <div>
                  <span className="text-xs uppercase tracking-wider text-theme-muted font-bold block">
                    {latestRelease?.sourceLabel || (language === 'ar' ? 'الإصدار المعتمد المستقر' : 'Verified Stable Release')}
                  </span>
                  <div className="flex flex-wrap items-center gap-3 mt-1">
                    <span className="text-2xl font-black text-theme-primary" data-testid="release-version-name">
                      {displayVersionName}
                    </span>
                    <GlassBadge variant="success">
                      {language === 'ar' ? `كود البناء: ${displayVersionCode}` : `Build ${displayVersionCode}`}
                    </GlassBadge>
                  </div>
                </div>
                <div className="flex items-center gap-3">
                  <div className="text-end">
                    <span className="text-xs uppercase tracking-wider text-theme-muted font-bold block">
                      {language === 'ar' ? 'تاريخ النشر' : 'Release Date'}
                    </span>
                    <span className="text-sm font-semibold text-theme-secondary mt-1 block">
                      {displayDate}
                    </span>
                  </div>
                  <button
                    type="button"
                    onClick={() => refresh(true)}
                    disabled={isLoading}
                    title={language === 'ar' ? 'تحديث بيانات الإصدار' : 'Refresh Release Feed'}
                    className="p-2.5 rounded-xl bg-theme-surface border border-theme text-theme-secondary hover:text-pmp-primary transition-all min-h-[42px] min-w-[42px] flex items-center justify-center"
                  >
                    <RefreshCw className={`w-4 h-4 ${isLoading ? 'animate-spin text-pmp-primary' : ''}`} />
                  </button>
                </div>
              </div>

              {!apkAvailable && unavailableReason && (
                <div className="mb-6 p-4 rounded-xl bg-amber-500/10 border border-amber-500/35 text-xs text-amber-400 flex items-start gap-2.5 leading-relaxed">
                  <AlertCircle className="w-4 h-4 shrink-0 mt-0.5" />
                  <span>{unavailableReason}</span>
                </div>
              )}

              {/* Specs Grid */}
              <div className="grid grid-cols-2 sm:grid-cols-3 gap-4 mb-6">
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
                  <span className="text-sm font-bold text-theme-primary" data-testid="release-file-size">
                    {displaySize}
                  </span>
                </div>
              </div>

              {/* Package ID & Hashes */}
              <div className="space-y-3 mb-7">
                <div className="p-3 rounded-lg bg-theme-surface border border-theme text-xs font-mono">
                  <div className="flex flex-wrap justify-between items-center gap-2 text-theme-muted mb-1">
                    <span>{language === 'ar' ? 'معرّف الحزمة الرسمي (Application ID):' : 'Official Application ID:'}</span>
                    <span className="text-theme-secondary">{displayBuildId}</span>
                  </div>
                  <span className="text-pmp-primary font-bold text-sm block">
                    {APP_VERSION_CONTRACT.APPLICATION_ID}
                  </span>
                </div>

                {displayApkSha256 && (
                  <div className="p-3 rounded-lg bg-theme-surface border border-theme text-xs font-mono">
                    <div className="flex justify-between items-center text-theme-muted mb-1">
                      <span>{language === 'ar' ? 'بصمة ملف الحزمة (APK SHA-256):' : 'APK File Digest (SHA-256):'}</span>
                      <button
                        type="button"
                        onClick={handleCopyApkHash}
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
                    <span className="text-theme-secondary break-all font-mono">{displayApkSha256}</span>
                  </div>
                )}

                <div className="p-3 rounded-lg bg-theme-surface border border-theme text-xs font-mono">
                  <div className="flex justify-between items-center text-theme-muted mb-1">
                    <span>{language === 'ar' ? 'شهادة التوقيع المعتمدة (Signing Cert SHA-256):' : 'Signing Certificate (SHA-256):'}</span>
                    <button
                      type="button"
                      onClick={handleCopyCertHash}
                      className="flex items-center gap-1 text-pmp-accent hover:underline focus:outline-none"
                    >
                      {copiedCert ? (
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
                  <span className="text-theme-secondary break-all font-mono">{displayCertSha256}</span>
                </div>
              </div>

              {/* Actions */}
              <div className="flex flex-col sm:flex-row gap-4">
                <a
                  href={apkAvailable ? displayDownloadUrl : APP_VERSION_CONTRACT.GITHUB_RELEASES_URL}
                  download={apkAvailable ? (latestRelease?.assetFileName || 'pmp-paints-latest.apk') : undefined}
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
                    {language === 'ar'
                      ? `تحميل تطبيق Android (${displayVersionName})`
                      : `Download Android APK (${displayVersionName})`}
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
                    {language === 'ar' ? 'سجل GitHub Releases' : 'GitHub Releases'}
                  </GlassButton>
                </a>
              </div>

              {/* Local Client-Side SHA-256 Verifier (Ported from Project A) */}
              <div className="mt-6 pt-5 border-t border-theme space-y-3">
                <div className="flex flex-wrap items-center justify-between gap-2">
                  <div className="flex items-center gap-2 text-xs font-bold text-theme-primary">
                    <KeyRound className="w-4 h-4 text-pmp-cyan" />
                    <span>
                      {language === 'ar'
                        ? 'أداة التحقق المحلي من سلامة ملف APK (Web Crypto SHA-256)'
                        : 'Verify Downloaded APK Checksum Locally (SHA-256)'}
                    </span>
                  </div>
                  <label className="inline-flex items-center gap-2 px-3 py-2 rounded-lg bg-theme-surface border border-theme hover:border-pmp-cyan text-xs font-bold text-pmp-cyan cursor-pointer transition-all min-h-[38px]">
                    <FileSearch className="w-4 h-4" />
                    <span>{language === 'ar' ? 'اختر ملف .apk للفحص' : 'Select .apk File'}</span>
                    <input
                      type="file"
                      accept=".apk,application/vnd.android.package-archive"
                      onChange={handleVerifyLocalFile}
                      className="hidden"
                    />
                  </label>
                </div>

                {localVerifyState.status !== 'IDLE' && (
                  <div
                    className={`p-3 rounded-xl text-xs font-mono leading-relaxed border ${
                      localVerifyState.status === 'MATCH'
                        ? 'bg-emerald-500/10 border-emerald-500/35 text-emerald-400'
                        : localVerifyState.status === 'MISMATCH' || localVerifyState.status === 'ERROR'
                        ? 'bg-red-500/10 border-red-500/35 text-red-400'
                        : 'bg-theme-surface border-theme text-theme-secondary'
                    }`}
                  >
                    {localVerifyState.message}
                  </div>
                )}
              </div>
            </GlassCard>
          </div>

          {/* Verification & Trust Column + App Visual Showcase */}
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
                      ? 'ترقية آمنة داخل التطبيق (In-Place Upgrade) تحافظ 100% على البيانات والحسابات دون مسح.'
                      : 'Safe in-place upgrade preserving 100% of accounts, invoices, and treasury records.'}
                  </span>
                </li>
                <li className="flex items-start gap-2.5">
                  <CheckCircle2 className="w-5 h-5 text-pmp-success shrink-0 mt-0.5" />
                  <span>
                    {language === 'ar'
                      ? 'تشفير كلمات المرور PBKDF2-HMAC-SHA256 وقواعد صلاحيات RBAC موحدة مع السحابة.'
                      : 'PBKDF2 password hashing synchronized with Firestore Enterprise RBAC.'}
                  </span>
                </li>
                <li className="flex items-start gap-2.5">
                  <CheckCircle2 className="w-5 h-5 text-pmp-success shrink-0 mt-0.5" />
                  <span>
                    {language === 'ar'
                      ? 'دعم العمل بدون إنترنت (Room DB v11) مع مزامنة لحظية فور توفر الشبكة.'
                      : 'Offline-first Room database sync with real-time Firestore cloud replication.'}
                  </span>
                </li>
                <li className="flex items-start gap-2.5">
                  <CheckCircle2 className="w-5 h-5 text-pmp-success shrink-0 mt-0.5" />
                  <span>
                    {language === 'ar'
                      ? 'بناء وتوقيع وفحص كل إصدار آلياً عبر GitHub Actions قبل ظهوره هنا.'
                      : 'Every binary is unit-tested, signed, and SHA-256 verified by GitHub Actions.'}
                  </span>
                </li>
              </ul>
            </GlassCard>

            {/* Brand Visuals Gallery (from Project A) */}
            <GlassCard className="p-5">
              <h3 className="text-sm font-bold text-theme-primary mb-3">
                {language === 'ar' ? 'لقطات وهوية تطبيق PMP PAINTS' : 'Official App Visual Identity'}
              </h3>
              <div className="grid grid-cols-3 gap-2.5">
                {brandVisuals.map((item, idx) => (
                  <div
                    key={idx}
                    className="rounded-xl overflow-hidden border border-theme bg-theme-surface flex flex-col"
                  >
                    <img
                      src={item.src}
                      alt={language === 'ar' ? item.titleAr : item.titleEn}
                      className="w-full h-24 object-cover"
                    />
                    <span className="p-1.5 text-[10px] font-bold text-theme-secondary text-center truncate">
                      {language === 'ar' ? item.titleAr : item.titleEn}
                    </span>
                  </div>
                ))}
              </div>
            </GlassCard>

            <div className="p-5 rounded-xl border border-pmp-warning/30 bg-pmp-warning/5 text-sm">
              <h4 className="font-bold text-pmp-warning mb-1 flex items-center gap-1.5">
                <span>⚠️</span>
                {language === 'ar' ? 'تنبيه تثبيت الأجهزة الجديدة' : 'New Device Installation Note'}
              </h4>
              <p className="text-theme-secondary text-xs leading-relaxed">
                {language === 'ar'
                  ? 'عند تثبيت ملف APK لأول مرة من المتصفح، قد يطلب نظام Android تفعيل خيار "السماح بتثبيت التطبيقات من هذا المصدر". الحزمة معتمدة وموقّعة بنفس شهادة المؤسسة.'
                  : 'When installing the APK from your browser for the first time, Android may prompt to allow "Install unknown apps". The package is signed with the official enterprise key.'}
              </p>
            </div>
          </div>
        </div>
      </div>
    </section>
  );
};
