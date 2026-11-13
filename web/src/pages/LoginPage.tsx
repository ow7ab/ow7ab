import React, { useState } from 'react';
import {
  Lock,
  Mail,
  User,
  LogIn,
  Sun,
  Moon,
  AlertCircle,
  CheckCircle,
  ExternalLink,
  KeyRound,
  ShieldCheck,
  Download,
  Smartphone,
  Home
} from 'lucide-react';
import { useAuth } from '../context/AuthContext';
import { useLanguage } from '../context/LanguageContext';
import { useTheme } from '../context/ThemeContext';
import { ErpDataService } from '../services/ErpDataService';
import { APP_VERSION_CONTRACT, resolveAssetUrl } from '../config/appVersionContract';
import { useLatestRelease } from '../hooks/useLatestRelease';
import { AppRoute } from '../utils/routing';

interface LoginPageProps {
  onLoginSuccess: () => void;
  onGoToPublicWebsite: () => void;
  onNavigateRoute?: (route: AppRoute) => void;
}

export const LoginPage: React.FC<LoginPageProps> = ({
  onLoginSuccess,
  onGoToPublicWebsite,
  onNavigateRoute
}) => {
  const { language, toggleLanguage } = useLanguage();
  const { theme, toggleTheme } = useTheme();
  const {
    loginWithEmail,
    loginWithUsernamePin,
    loginWithGoogle,
    requestPasswordReset,
    isAuthenticating
  } = useAuth();
  const { latestRelease, apkAvailable, unavailableReason, isLoading: isReleaseLoading } = useLatestRelease();

  const [tab, setTab] = useState<'pin' | 'email' | 'reset'>('pin');
  const [username, setUsername] = useState('');
  const [pin, setPin] = useState('');
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [errorMsg, setErrorMsg] = useState<string | null>(null);
  const [successMsg, setSuccessMsg] = useState<string | null>(null);
  const [downloadNotice, setDownloadNotice] = useState<string | null>(null);

  const goTo = (route: AppRoute) => {
    if (onNavigateRoute) {
      onNavigateRoute(route);
    } else if (route === '/') {
      onGoToPublicWebsite();
    }
  };

  const handlePinSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setErrorMsg(null);
    setSuccessMsg(null);

    try {
      await loginWithUsernamePin(username, pin);
      setSuccessMsg(
        language === 'ar'
          ? 'تم التحقق من بياناتك السحابية! جاري الدخول للمنظومة...'
          : 'Cloud verification successful! Entering ERP...'
      );
      setTimeout(() => {
        onLoginSuccess();
      }, 350);
    } catch (err: any) {
      setErrorMsg(
        err.message ||
          (language === 'ar'
            ? 'اسم المستخدم أو الرمز السري غير صحيح.'
            : 'Invalid username or PIN.')
      );
    }
  };

  const handleEmailSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setErrorMsg(null);
    setSuccessMsg(null);

    try {
      await loginWithEmail(email, password);
      setSuccessMsg(language === 'ar' ? 'تم تسجيل الدخول بنجاح!' : 'Login successful!');
      setTimeout(() => {
        onLoginSuccess();
      }, 350);
    } catch (err: any) {
      setErrorMsg(
        err.message ||
          (language === 'ar'
            ? 'فشل تسجيل الدخول بالبريد الإلكتروني.'
            : 'Email login failed.')
      );
    }
  };

  const handleResetSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setErrorMsg(null);
    setSuccessMsg(null);

    try {
      await requestPasswordReset(email);
      setSuccessMsg(
        language === 'ar'
          ? 'تم إرسال رابط إعادة تعيين كلمة المرور إلى بريدك الإلكتروني المسجل.'
          : 'Password reset link has been sent to your registered email.'
      );
      setTimeout(() => setTab('email'), 2500);
    } catch (err: any) {
      setErrorMsg(
        err.message ||
          (language === 'ar'
            ? 'تعذر إرسال رابط استعادة كلمة المرور.'
            : 'Failed to send password reset link.')
      );
    }
  };

  const handleGoogleSubmit = async () => {
    setErrorMsg(null);
    setSuccessMsg(null);
    try {
      await loginWithGoogle();
      setSuccessMsg(
        language === 'ar' ? 'تم التحقق عبر حساب Google!' : 'Google authentication verified!'
      );
      setTimeout(() => {
        onLoginSuccess();
      }, 350);
    } catch (err: any) {
      setErrorMsg(
        err.message ||
          (language === 'ar'
            ? 'تعذر إتمام تسجيل الدخول عبر Google.'
            : 'Google sign-in error.')
      );
    }
  };

  const handleDownloadAndroidApp = (e: React.MouseEvent<HTMLAnchorElement>) => {
    if (!apkAvailable || !latestRelease?.downloadUrl) {
      e.preventDefault();
      setDownloadNotice(
        unavailableReason ||
          (language === 'ar'
            ? 'ملف APK غير متاح حالياً للتحميل المباشر. يمكنك فحص سجل الإصدارات عبر صفحة التحميل الرسمية.'
            : 'APK download is temporarily unavailable. Check the official Download page or GitHub Releases.')
      );
      return;
    }
    setDownloadNotice(null);
  };

  const versionMetaLabel = isReleaseLoading
    ? language === 'ar'
      ? 'جاري جلب أحدث إصدار مستقر...'
      : 'Checking latest stable release...'
    : latestRelease
    ? `${latestRelease.versionName} (Build ${latestRelease.versionCode})${
        latestRelease.fileSizeMb > 0 ? ` • ${latestRelease.fileSizeMb} MB` : ''
      }`
    : language === 'ar'
    ? 'التحقق من توفر الإصدار...'
    : 'Checking release availability';

  return (
    <div className="min-h-screen bg-theme-base text-theme-primary flex flex-col justify-between p-4 sm:p-6 selection:bg-pmp-primary selection:text-white">
      {/* Top Header Toolbar */}
      <header className="max-w-6xl w-full mx-auto flex items-center justify-between py-2 gap-3">
        <button
          type="button"
          onClick={() => goTo('/')}
          className="flex items-center gap-3 min-w-0 text-start hover:opacity-90 transition-opacity"
        >
          <div className="w-10 h-10 rounded-xl bg-white border border-red-500/40 p-1 flex items-center justify-center shadow-lg shadow-red-600/20 shrink-0">
            <img
              src={resolveAssetUrl('assets/ic_pmp_emblem.png')}
              alt="PMP Paints"
              className="w-full h-full object-contain"
            />
          </div>
          <div className="min-w-0">
            <span className="font-black text-sm tracking-tight block truncate">PMP PAINTS ERP</span>
            <span className="text-[10px] text-pmp-primary font-bold block truncate">
              {language === 'ar' ? 'المنظومة السحابية الموحدة (أندرويد + ويب)' : 'Unified Enterprise Cloud ERP'}
            </span>
          </div>
        </button>

        <div className="flex items-center gap-2 shrink-0">
          <button
            type="button"
            onClick={() => goTo('/')}
            className="hidden sm:inline-flex items-center gap-1.5 px-3 py-1.5 rounded-lg bg-theme-surface border border-theme text-xs font-bold text-theme-secondary hover:text-theme-primary transition-all min-h-[36px]"
          >
            <Home className="w-3.5 h-3.5" />
            <span>{language === 'ar' ? 'الموقع الرئيسي' : 'Main Site'}</span>
          </button>

          <button
            type="button"
            onClick={toggleLanguage}
            className="px-3 py-1.5 rounded-lg bg-theme-surface border border-theme text-xs font-bold text-theme-secondary hover:text-theme-primary transition-all min-h-[36px]"
          >
            {language === 'ar' ? 'English' : 'عربي'}
          </button>

          <button
            type="button"
            onClick={toggleTheme}
            className="p-2 rounded-lg bg-theme-surface border border-theme text-theme-secondary hover:text-theme-primary transition-all min-h-[36px] min-w-[36px] flex items-center justify-center"
            title={language === 'ar' ? 'تبديل المظهر' : 'Toggle Theme'}
          >
            {theme === 'true-black' ? <Sun className="w-4 h-4 text-pmp-warning" /> : <Moon className="w-4 h-4" />}
          </button>
        </div>
      </header>

      {/* Main Login Card + Official Android App Download CTA */}
      <main className="max-w-md w-full mx-auto my-auto py-6 sm:py-8 space-y-4">
        <div className="bg-theme-surface/95 border border-theme rounded-2xl p-6 sm:p-8 shadow-2xl backdrop-blur-xl space-y-5">
          {/* Emblem & Lock Header */}
          <div className="text-center space-y-2">
            <div className="w-14 h-14 rounded-2xl bg-red-600/10 border border-red-600/30 text-red-500 mx-auto flex items-center justify-center shadow-inner">
              <Lock className="w-7 h-7" />
            </div>
            <h1 className="text-xl font-black text-theme-primary">
              {tab === 'reset'
                ? language === 'ar'
                  ? 'استعادة كلمة المرور'
                  : 'Password Recovery'
                : language === 'ar'
                ? 'بوابة دخول منسوبي PMP PAINTS'
                : 'Enterprise Employee Portal'}
            </h1>
            <p className="text-xs text-theme-secondary leading-relaxed">
              {language === 'ar'
                ? 'سجّل الدخول بنفس حسابك المعتمد في تطبيق الأندرويد للوصول المباشر إلى بيانات السيرفر السحابي.'
                : 'Sign in with your verified Android ERP employee account to access live cloud operations.'}
            </p>
          </div>

          {/* Security Info Strip */}
          <div className="p-2.5 rounded-xl bg-pmp-primary/10 border border-pmp-primary/25 flex items-center gap-2.5 text-[11px] text-theme-secondary">
            <ShieldCheck className="w-4 h-4 text-pmp-primary shrink-0" />
            <span>
              {language === 'ar'
                ? 'تحقق مشفر بنظام PBKDF2-HMAC-SHA256 وصلاحيات RBAC موحدة مع الأندرويد.'
                : 'PBKDF2-HMAC-SHA256 verification synchronized with Android RBAC.'}
            </span>
          </div>

          {/* Tab Selector */}
          {tab !== 'reset' && (
            <div className="grid grid-cols-2 p-1 rounded-xl bg-theme-base border border-theme gap-1">
              <button
                type="button"
                onClick={() => {
                  setTab('pin');
                  setErrorMsg(null);
                  setSuccessMsg(null);
                }}
                className={`py-2 text-xs font-bold rounded-lg transition-all ${
                  tab === 'pin'
                    ? 'bg-pmp-primary text-white shadow-md'
                    : 'text-theme-secondary hover:text-theme-primary'
                }`}
              >
                {language === 'ar' ? 'اسم المستخدم والرمز' : 'Username & PIN'}
              </button>
              <button
                type="button"
                onClick={() => {
                  setTab('email');
                  setErrorMsg(null);
                  setSuccessMsg(null);
                }}
                className={`py-2 text-xs font-bold rounded-lg transition-all ${
                  tab === 'email'
                    ? 'bg-pmp-primary text-white shadow-md'
                    : 'text-theme-secondary hover:text-theme-primary'
                }`}
              >
                {language === 'ar' ? 'البريد الإلكتروني' : 'Email & Password'}
              </button>
            </div>
          )}

          {/* Alerts */}
          {errorMsg && (
            <div className="p-3 rounded-xl bg-red-500/10 border border-red-500/30 flex items-center gap-2 text-xs text-red-500 leading-relaxed">
              <AlertCircle className="w-4 h-4 shrink-0" />
              <span>{errorMsg}</span>
            </div>
          )}

          {successMsg && (
            <div className="p-3 rounded-xl bg-emerald-500/10 border border-emerald-500/30 flex items-center gap-2 text-xs text-emerald-400 leading-relaxed">
              <CheckCircle className="w-4 h-4 shrink-0" />
              <span>{successMsg}</span>
            </div>
          )}

          {/* PIN / Username Form */}
          {tab === 'pin' && (
            <form onSubmit={handlePinSubmit} className="space-y-4">
              <div>
                <label className="block text-xs font-bold text-theme-primary mb-1.5">
                  {language === 'ar' ? 'اسم المستخدم أو الكود الوظيفي' : 'Username or User Code'}
                </label>
                <div className="relative">
                  <User className="w-4 h-4 absolute right-3 top-3 text-theme-secondary" />
                  <input
                    type="text"
                    required
                    autoComplete="username"
                    value={username}
                    onChange={(e) => setUsername(e.target.value)}
                    placeholder={language === 'ar' ? 'أدخل اسم المستخدم المسجل' : 'Enter your username'}
                    className="w-full pr-9 pl-3 py-2.5 rounded-xl bg-theme-base border border-theme text-theme-primary text-sm focus:outline-none focus:border-pmp-primary transition-all font-mono"
                  />
                </div>
              </div>

              <div>
                <label className="block text-xs font-bold text-theme-primary mb-1.5">
                  {language === 'ar' ? 'الرمز السري / كلمة المرور' : 'PIN / Password'}
                </label>
                <div className="relative">
                  <KeyRound className="w-4 h-4 absolute right-3 top-3 text-theme-secondary" />
                  <input
                    type="password"
                    required
                    autoComplete="current-password"
                    value={pin}
                    onChange={(e) => setPin(e.target.value)}
                    placeholder="••••••"
                    className="w-full pr-9 pl-3 py-2.5 rounded-xl bg-theme-base border border-theme text-theme-primary text-sm focus:outline-none focus:border-pmp-primary transition-all font-mono tracking-widest"
                  />
                </div>
              </div>

              <button
                type="submit"
                disabled={isAuthenticating}
                className="w-full py-3 rounded-xl bg-red-600 hover:bg-red-700 text-white font-bold text-sm shadow-lg shadow-red-600/30 transition-all flex items-center justify-center gap-2 disabled:opacity-50 min-h-[48px]"
              >
                <LogIn className="w-4 h-4" />
                <span>
                  {isAuthenticating
                    ? language === 'ar'
                      ? 'جاري التحقق السحابي...'
                      : 'Authenticating...'
                    : language === 'ar'
                    ? 'دخول المنظومة السحابية'
                    : 'Sign In to Portal'}
                </span>
              </button>
            </form>
          )}

          {/* Email Form */}
          {tab === 'email' && (
            <form onSubmit={handleEmailSubmit} className="space-y-4">
              <div>
                <label className="block text-xs font-bold text-theme-primary mb-1.5">
                  {language === 'ar' ? 'البريد الإلكتروني المعتمد' : 'Corporate Email'}
                </label>
                <div className="relative">
                  <Mail className="w-4 h-4 absolute right-3 top-3 text-theme-secondary" />
                  <input
                    type="email"
                    required
                    autoComplete="email"
                    value={email}
                    onChange={(e) => setEmail(e.target.value)}
                    placeholder="employee@pmppaints.com"
                    className="w-full pr-9 pl-3 py-2.5 rounded-xl bg-theme-base border border-theme text-theme-primary text-sm focus:outline-none focus:border-pmp-primary transition-all"
                  />
                </div>
              </div>

              <div>
                <div className="flex items-center justify-between mb-1.5">
                  <label className="text-xs font-bold text-theme-primary">
                    {language === 'ar' ? 'كلمة المرور' : 'Password'}
                  </label>
                  <button
                    type="button"
                    onClick={() => {
                      setTab('reset');
                      setErrorMsg(null);
                      setSuccessMsg(null);
                    }}
                    className="text-[11px] text-pmp-cyan hover:underline font-bold"
                  >
                    {language === 'ar' ? 'نسيت كلمة المرور؟' : 'Forgot password?'}
                  </button>
                </div>
                <div className="relative">
                  <Lock className="w-4 h-4 absolute right-3 top-3 text-theme-secondary" />
                  <input
                    type="password"
                    required
                    autoComplete="current-password"
                    value={password}
                    onChange={(e) => setPassword(e.target.value)}
                    placeholder="••••••••"
                    className="w-full pr-9 pl-3 py-2.5 rounded-xl bg-theme-base border border-theme text-theme-primary text-sm focus:outline-none focus:border-pmp-primary transition-all"
                  />
                </div>
              </div>

              <button
                type="submit"
                disabled={isAuthenticating}
                className="w-full py-3 rounded-xl bg-red-600 hover:bg-red-700 text-white font-bold text-sm shadow-lg shadow-red-600/30 transition-all flex items-center justify-center gap-2 disabled:opacity-50 min-h-[48px]"
              >
                <LogIn className="w-4 h-4" />
                <span>
                  {isAuthenticating
                    ? language === 'ar'
                      ? 'جاري التحقق...'
                      : 'Authenticating...'
                    : language === 'ar'
                    ? 'دخول بالبريد الإلكتروني'
                    : 'Sign In with Email'}
                </span>
              </button>
            </form>
          )}

          {/* Reset Password Form */}
          {tab === 'reset' && (
            <form onSubmit={handleResetSubmit} className="space-y-4">
              <div>
                <label className="block text-xs font-bold text-theme-primary mb-1.5">
                  {language === 'ar' ? 'البريد الإلكتروني المسجل' : 'Registered Email'}
                </label>
                <div className="relative">
                  <Mail className="w-4 h-4 absolute right-3 top-3 text-theme-secondary" />
                  <input
                    type="email"
                    required
                    value={email}
                    onChange={(e) => setEmail(e.target.value)}
                    placeholder="name@pmppaints.com"
                    className="w-full pr-9 pl-3 py-2.5 rounded-xl bg-theme-base border border-theme text-theme-primary text-sm focus:outline-none focus:border-pmp-primary transition-all"
                  />
                </div>
              </div>

              <div className="flex gap-2">
                <button
                  type="button"
                  onClick={() => {
                    setTab('email');
                    setErrorMsg(null);
                  }}
                  className="flex-1 py-2.5 rounded-xl bg-theme-base border border-theme text-xs font-bold text-theme-secondary hover:text-theme-primary min-h-[44px]"
                >
                  {language === 'ar' ? 'رجوع' : 'Back'}
                </button>
                <button
                  type="submit"
                  disabled={isAuthenticating}
                  className="flex-1 py-2.5 rounded-xl bg-red-600 hover:bg-red-700 text-white text-xs font-bold shadow-md disabled:opacity-50 min-h-[44px]"
                >
                  {language === 'ar' ? 'إرسال الرابط' : 'Send Link'}
                </button>
              </div>
            </form>
          )}

          {/* Google SSO */}
          {tab !== 'reset' && (
            <div className="pt-1">
              <button
                type="button"
                onClick={handleGoogleSubmit}
                disabled={isAuthenticating}
                className="w-full py-2.5 rounded-xl bg-theme-base border border-theme hover:border-pmp-primary text-theme-primary font-bold text-xs transition-all flex items-center justify-center gap-2 min-h-[44px]"
              >
                <img
                  src="https://www.gstatic.com/firebasejs/ui/2.0.0/images/auth/google.svg"
                  alt="Google"
                  className="w-4 h-4"
                />
                <span>{language === 'ar' ? 'تسجيل الدخول عبر Google Workspace' : 'Sign in with Google'}</span>
              </button>
            </div>
          )}

          {/* Mandatory Official Android App Download Button on Login Screen */}
          <div className="pt-4 border-t border-theme space-y-3" data-testid="login-android-download-box">
            <a
              href={apkAvailable && latestRelease?.downloadUrl ? latestRelease.downloadUrl : APP_VERSION_CONTRACT.GITHUB_RELEASES_URL}
              download={apkAvailable && latestRelease ? latestRelease.assetFileName : undefined}
              target="_blank"
              rel="noopener noreferrer"
              onClick={handleDownloadAndroidApp}
              data-testid="login-download-android-btn"
              className="w-full flex items-center justify-between gap-3 px-4 py-3.5 rounded-xl bg-emerald-600/15 hover:bg-emerald-600/25 border border-emerald-500/40 text-theme-primary transition-all shadow-md min-h-[54px] group"
            >
              <div className="flex items-center gap-3 min-w-0">
                <div className="w-10 h-10 rounded-xl bg-emerald-500/20 border border-emerald-500/40 text-emerald-400 flex items-center justify-center shrink-0">
                  <Download className="w-5 h-5 group-hover:translate-y-0.5 transition-transform" />
                </div>
                <div className="text-start min-w-0">
                  <span className="text-xs sm:text-sm font-extrabold text-theme-primary block truncate">
                    {language === 'ar'
                      ? 'تحميل تطبيق Android | Download Android App'
                      : 'Download Android App | تحميل تطبيق Android'}
                  </span>
                  <span className="text-[11px] text-emerald-400 font-bold block truncate">
                    {versionMetaLabel}
                  </span>
                </div>
              </div>
              <Smartphone className="w-5 h-5 text-emerald-400 shrink-0" />
            </a>

            {downloadNotice && (
              <div className="p-2.5 rounded-xl bg-amber-500/10 border border-amber-500/30 text-[11px] text-amber-400 flex items-start gap-2 leading-relaxed">
                <AlertCircle className="w-4 h-4 shrink-0 mt-0.5" />
                <span>{downloadNotice}</span>
              </div>
            )}

            <div className="flex flex-wrap items-center justify-center gap-3 text-xs pt-1">
              <button
                type="button"
                onClick={() => goTo('/download')}
                className="text-pmp-cyan hover:underline font-bold inline-flex items-center gap-1"
              >
                <ShieldCheck className="w-3.5 h-3.5" />
                <span>
                  {language === 'ar'
                    ? 'صفحة التحميل والتحقق SHA-256'
                    : 'Official Download & SHA-256 Page'}
                </span>
              </button>
              <span className="text-theme-muted">•</span>
              <button
                type="button"
                onClick={() => goTo('/')}
                className="text-pmp-primary hover:underline font-bold inline-flex items-center gap-1"
              >
                <ExternalLink className="w-3.5 h-3.5" />
                <span>
                  {language === 'ar' ? 'تصفح الموقع الرئيسي' : 'Main ERP Website'}
                </span>
              </button>
            </div>
          </div>
        </div>
      </main>

      {/* Footer Info */}
      <footer className="max-w-6xl w-full mx-auto text-center py-3 text-[11px] text-theme-muted">
        <div className="flex flex-wrap items-center justify-center gap-3 sm:gap-4">
          <span>
            {language === 'ar'
              ? `عنقود السحابة النشط: ${ErpDataService.activeCluster}`
              : `Active Cluster: ${ErpDataService.activeCluster}`}
          </span>
          <span>•</span>
          <span>{language === 'ar' ? 'قاعدة البيانات: Cloud Firestore' : 'Database: Cloud Firestore'}</span>
          <span>•</span>
          <span>
            {language === 'ar' ? 'جميع الحقوق محفوظة لشركة PMP Paints © 2026' : 'All Rights Reserved © 2026 PMP Paints'}
          </span>
        </div>
      </footer>
    </div>
  );
};
