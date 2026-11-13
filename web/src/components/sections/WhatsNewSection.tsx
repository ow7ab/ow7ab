import React from 'react';
import { Sparkles, Download, Calendar, HardDrive, ShieldCheck } from 'lucide-react';
import { GlassCard } from '../ui/GlassCard';
import { GlassBadge } from '../ui/GlassBadge';
import { useLanguage } from '../../context/LanguageContext';
import { useLatestRelease } from '../../hooks/useLatestRelease';
import { formatReleaseDate } from '../../services/ReleaseService';

export const WhatsNewSection: React.FC = () => {
  const { language } = useLanguage();
  const { releaseHistory, isLoading } = useLatestRelease();

  return (
    <section id="whats-new" className="py-20 relative">
      <div className="max-w-6xl mx-auto px-4 sm:px-6 lg:px-8">
        <div className="text-center max-w-3xl mx-auto mb-14">
          <GlassBadge variant="accent" className="mb-4">
            <Sparkles className="w-3.5 h-3.5 inline ml-1.5" />
            {language === 'ar' ? 'سجل الإصدارات الرسمية المستقرة' : 'Live Stable Release Archive'}
          </GlassBadge>
          <h2 className="text-3xl sm:text-4xl font-extrabold text-theme-primary tracking-tight">
            {language === 'ar' ? 'ما الجديد وسجل الإصدارات السابقة' : "What's New & Release History"}
          </h2>
          <p className="mt-4 text-base sm:text-lg text-theme-secondary">
            {language === 'ar'
              ? 'تحديثات حية متزامنة مباشرة مع مستودع GitHub Releases وقاعدة بيانات Firestore (تُستبعد النسخ التجريبية والمسودات تلقائياً).'
              : 'Live release history synchronized directly from GitHub Releases and Firestore (drafts and prereleases automatically excluded).'}
          </p>
        </div>

        {isLoading && releaseHistory.length === 0 ? (
          <div className="text-center text-sm text-theme-secondary py-10">
            {language === 'ar' ? 'جاري تحميل سجل الإصدارات من GitHub Releases...' : 'Loading live releases from GitHub...'}
          </div>
        ) : (
          <div className="space-y-6">
            {releaseHistory.slice(0, 6).map((release, idx) => {
              const isLatest = idx === 0;
              const dateLabel = formatReleaseDate(release.publishedAt);
              return (
                <GlassCard
                  key={`${release.versionCode}-${release.versionName}`}
                  variant={isLatest ? 'highlight' : 'default'}
                  className="p-6"
                >
                  <div className="flex flex-wrap items-center justify-between gap-4 mb-4">
                    <div className="flex flex-wrap items-center gap-3">
                      <h3 className="text-xl font-black text-theme-primary">
                        {release.versionName} (Build {release.versionCode})
                      </h3>
                      <GlassBadge variant={isLatest ? 'success' : 'secondary'}>
                        {isLatest
                          ? language === 'ar'
                            ? 'الإصدار المستقر الأحدث'
                            : 'Latest Stable'
                          : language === 'ar'
                          ? 'إصدار سابق'
                          : 'Previous Release'}
                      </GlassBadge>
                    </div>

                    <div className="flex flex-wrap items-center gap-4 text-xs text-theme-secondary">
                      <span className="inline-flex items-center gap-1.5">
                        <Calendar className="w-3.5 h-3.5 text-pmp-cyan" />
                        {dateLabel}
                      </span>
                      {release.fileSizeMb > 0 && (
                        <span className="inline-flex items-center gap-1.5">
                          <HardDrive className="w-3.5 h-3.5 text-pmp-gold" />
                          {release.fileSizeMb} MB
                        </span>
                      )}
                    </div>
                  </div>

                  <p className="text-sm text-theme-secondary whitespace-pre-line leading-relaxed mb-4">
                    {release.releaseNotes ||
                      (language === 'ar'
                        ? 'تحديث استقرار وأمان وتحسينات عامة في الأداء والمزامنة السحابية.'
                        : 'Stability, security, and cloud synchronization improvements.')}
                  </p>

                  <div className="flex flex-wrap items-center justify-between gap-3 pt-3 border-t border-theme text-xs">
                    <div className="inline-flex items-center gap-1.5 text-theme-muted font-mono">
                      <ShieldCheck className="w-4 h-4 text-pmp-success shrink-0" />
                      <span>
                        {release.apkSha256
                          ? `SHA-256: ${release.apkSha256.slice(0, 14)}...${release.apkSha256.slice(-8)}`
                          : release.buildId}
                      </span>
                    </div>

                    {release.isAvailable && (
                      <a
                        href={release.downloadUrl}
                        download={release.assetFileName}
                        target="_blank"
                        rel="noopener noreferrer"
                        className="inline-flex items-center gap-1.5 px-3.5 py-2 rounded-lg bg-pmp-primary/15 hover:bg-pmp-primary/25 border border-pmp-primary/35 text-pmp-primary font-bold transition-all"
                      >
                        <Download className="w-3.5 h-3.5" />
                        <span>
                          {language === 'ar'
                            ? `تنزيل APK (${release.versionName})`
                            : `Download APK (${release.versionName})`}
                        </span>
                      </a>
                    )}
                  </div>
                </GlassCard>
              );
            })}
          </div>
        )}
      </div>
    </section>
  );
};
