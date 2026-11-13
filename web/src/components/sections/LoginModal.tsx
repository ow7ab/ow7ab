import React, { useState } from 'react';
import { Lock, Mail, KeyRound, AlertCircle, CheckCircle, ShieldCheck, LogIn, ArrowRight } from 'lucide-react';
import { Modal } from '../ui/Modal';
import { GlassButton } from '../ui/GlassButton';
import { GlassBadge } from '../ui/GlassBadge';
import { useAuth } from '../../context/AuthContext';
import { useLanguage } from '../../context/LanguageContext';

interface LoginModalProps {
  isOpen: boolean;
  onClose: () => void;
  onLoginSuccess?: () => void;
}

export const LoginModal: React.FC<LoginModalProps> = ({ isOpen, onClose, onLoginSuccess }) => {
  const { language } = useLanguage();
  const { loginWithEmail, loginWithUsernamePin, loginWithGoogle, requestPasswordReset, isAuthenticating } = useAuth();

  const [tab, setTab] = useState<'pin' | 'email' | 'reset'>('pin');
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [username, setUsername] = useState('');
  const [pin, setPin] = useState('');
  const [errorMsg, setErrorMsg] = useState<string | null>(null);
  const [successMsg, setSuccessMsg] = useState<string | null>(null);

  const resetForm = () => {
    setEmail('');
    setPassword('');
    setUsername('');
    setPin('');
    setErrorMsg(null);
    setSuccessMsg(null);
    setTab('pin');
  };

  const handleClose = () => {
    resetForm();
    onClose();
  };

  const handleEmailLogin = async (e: React.FormEvent) => {
    e.preventDefault();
    setErrorMsg(null);
    setSuccessMsg(null);
    try {
      await loginWithEmail(email, password);
      setSuccessMsg(language === 'ar' ? 'تم التحقق بنجاح! جاري تحويلك للبوابة...' : 'Authentication successful! Redirecting...');
      setTimeout(() => {
        handleClose();
        if (onLoginSuccess) onLoginSuccess();
      }, 1200);
    } catch (err: any) {
      setErrorMsg(err.message || (language === 'ar' ? 'فشل تسجيل الدخول. تأكد من صحة البيانات.' : 'Login failed. Check your credentials.'));
    }
  };

  const handlePinLogin = async (e: React.FormEvent) => {
    e.preventDefault();
    setErrorMsg(null);
    setSuccessMsg(null);
    try {
      await loginWithUsernamePin(username, pin);
      setSuccessMsg(language === 'ar' ? 'تم تسجيل الدخول بالرمز بنجاح!' : 'PIN verified successfully!');
      setTimeout(() => {
        handleClose();
        if (onLoginSuccess) onLoginSuccess();
      }, 1200);
    } catch (err: any) {
      setErrorMsg(err.message || (language === 'ar' ? 'اسم المستخدم أو الرمز السري غير صحيح.' : 'Invalid username or PIN.'));
    }
  };

  const handleGoogleLogin = async () => {
    setErrorMsg(null);
    setSuccessMsg(null);
    try {
      await loginWithGoogle();
      setSuccessMsg(language === 'ar' ? 'تم التحقق بحساب Google بنجاح!' : 'Google authentication verified!');
      setTimeout(() => {
        handleClose();
        if (onLoginSuccess) onLoginSuccess();
      }, 1200);
    } catch (err: any) {
      setErrorMsg(err.message || (language === 'ar' ? 'تعذر إتمام الدخول بحساب Google.' : 'Google sign-in error.'));
    }
  };

  const handleResetPassword = async (e: React.FormEvent) => {
    e.preventDefault();
    setErrorMsg(null);
    setSuccessMsg(null);
    if (!email) {
      setErrorMsg(language === 'ar' ? 'الرجاء إدخال البريد الإلكتروني' : 'Please provide your email');
      return;
    }
    try {
      await requestPasswordReset(email);
      setSuccessMsg(language === 'ar' ? 'تم إرسال رابط استعادة كلمة المرور إلى بريدك الإلكتروني.' : 'Password reset link sent to your email.');
      setTimeout(() => setTab('email'), 3000);
    } catch (err: any) {
      setErrorMsg(err.message || (language === 'ar' ? 'تعذر إرسال رابط الاستعادة.' : 'Failed to send reset link.'));
    }
  };

  return (
    <Modal
      isOpen={isOpen}
      onClose={handleClose}
      title={
        tab === 'reset'
          ? (language === 'ar' ? 'استعادة كلمة المرور' : 'Password Recovery')
          : (language === 'ar' ? 'بوابة دخول موظفي PMP PAINTS' : 'PMP PAINTS Enterprise Portal')
      }
      maxWidth="max-w-md"
    >
      <div className="space-y-6">
        {/* Security Header Banner */}
        <div className="p-3.5 rounded-xl bg-pmp-primary/10 border border-pmp-primary/30 flex items-center gap-3">
          <ShieldCheck className="w-5 h-5 text-pmp-primary shrink-0" />
          <div className="text-xs">
            <span className="font-bold text-theme-primary block">
              {language === 'ar' ? 'اتصال سحابي آمن ومشفر' : 'Secure Encrypted Connection'}
            </span>
            <span className="text-theme-secondary">
              {language === 'ar' ? 'مرتبط مباشرة بنظام الصلاحيات وقاعدة بيانات الفروع.' : 'Directly integrated with Firestore Enterprise RBAC.'}
            </span>
          </div>
        </div>

        {tab !== 'reset' && (
          /* Tab switch */
          <div className="grid grid-cols-2 p-1 rounded-xl bg-theme-surface border border-theme">
            <button
              type="button"
              onClick={() => { setTab('pin'); setErrorMsg(null); }}
              className={`py-2 text-xs font-bold rounded-lg transition-all ${
                tab === 'pin'
                  ? 'bg-pmp-primary text-white shadow-md'
                  : 'text-theme-secondary hover:text-theme-primary'
              }`}
            >
              {language === 'ar' ? 'اسم المستخدم والرمز (PIN)' : 'Username & PIN'}
            </button>
            <button
              type="button"
              onClick={() => { setTab('email'); setErrorMsg(null); }}
              className={`py-2 text-xs font-bold rounded-lg transition-all ${
                tab === 'email'
                  ? 'bg-pmp-primary text-white shadow-md'
                  : 'text-theme-secondary hover:text-theme-primary'
              }`}
            >
              {language === 'ar' ? 'البريد الإلكتروني' : 'Enterprise Email'}
            </button>
          </div>
        )}

        {errorMsg && (
          <div className="p-3 rounded-lg bg-red-500/10 border border-red-500/30 flex items-center gap-2 text-xs text-red-500">
            <AlertCircle className="w-4 h-4 shrink-0" />
            <span>{errorMsg}</span>
          </div>
        )}

        {successMsg && (
          <div className="p-3 rounded-lg bg-green-500/10 border border-green-500/30 flex items-center gap-2 text-xs text-green-500">
            <CheckCircle className="w-4 h-4 shrink-0" />
            <span>{successMsg}</span>
          </div>
        )}

        {tab === 'pin' && (
          <form onSubmit={handlePinLogin} className="space-y-4">
            <div>
              <label className="block text-xs font-semibold text-theme-primary mb-1">
                {language === 'ar' ? 'اسم المستخدم الوظيفي' : 'Username'}
              </label>
              <input
                type="text"
                required
                value={username}
                onChange={(e) => setUsername(e.target.value)}
                placeholder={language === 'ar' ? 'مثال: admin أو ahmed_acc' : 'e.g. admin or ahmed_acc'}
                className="w-full px-3.5 py-2.5 rounded-lg bg-theme-surface border border-theme text-theme-primary text-sm focus:outline-none focus:border-pmp-primary"
              />
            </div>

            <div>
              <label className="block text-xs font-semibold text-theme-primary mb-1">
                {language === 'ar' ? 'الرمز السري المشفر (PIN)' : 'Secret PIN'}
              </label>
              <input
                type="password"
                required
                maxLength={12}
                value={pin}
                onChange={(e) => setPin(e.target.value)}
                placeholder="••••"
                className="w-full px-3.5 py-2.5 rounded-lg bg-theme-surface border border-theme text-theme-primary text-sm focus:outline-none focus:border-pmp-primary tracking-widest"
              />
            </div>

            <GlassButton
              type="submit"
              variant="primary"
              disabled={isAuthenticating}
              className="w-full justify-center shadow-md mt-2"
              icon={<LogIn className="w-4 h-4" />}
            >
              {isAuthenticating
                ? (language === 'ar' ? 'جاري التحقق...' : 'Authenticating...')
                : (language === 'ar' ? 'دخول بوابة الموظف' : 'Enter Portal')}
            </GlassButton>
          </form>
        )}

        {tab === 'email' && (
          <form onSubmit={handleEmailLogin} className="space-y-4">
            <div>
              <label className="block text-xs font-semibold text-theme-primary mb-1">
                {language === 'ar' ? 'البريد الإلكتروني المعتمد' : 'Corporate Email'}
              </label>
              <input
                type="email"
                required
                value={email}
                onChange={(e) => setEmail(e.target.value)}
                placeholder="name@pmppaints.com"
                className="w-full px-3.5 py-2.5 rounded-lg bg-theme-surface border border-theme text-theme-primary text-sm focus:outline-none focus:border-pmp-primary"
              />
            </div>

            <div>
              <div className="flex justify-between items-center mb-1">
                <label className="text-xs font-semibold text-theme-primary">
                  {language === 'ar' ? 'كلمة المرور' : 'Password'}
                </label>
                <button
                  type="button"
                  onClick={() => { setTab('reset'); setErrorMsg(null); }}
                  className="text-[11px] text-pmp-accent hover:underline focus:outline-none"
                >
                  {language === 'ar' ? 'نسيت كلمة المرور؟' : 'Forgot Password?'}
                </button>
              </div>
              <input
                type="password"
                required
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                placeholder="••••••••"
                className="w-full px-3.5 py-2.5 rounded-lg bg-theme-surface border border-theme text-theme-primary text-sm focus:outline-none focus:border-pmp-primary"
              />
            </div>

            <GlassButton
              type="submit"
              variant="primary"
              disabled={isAuthenticating}
              className="w-full justify-center shadow-md mt-2"
              icon={<LogIn className="w-4 h-4" />}
            >
              {isAuthenticating
                ? (language === 'ar' ? 'جاري التحقق...' : 'Authenticating...')
                : (language === 'ar' ? 'تسجيل الدخول' : 'Sign In')}
            </GlassButton>
          </form>
        )}

        {tab === 'reset' && (
          <form onSubmit={handleResetPassword} className="space-y-4">
            <p className="text-xs text-theme-secondary leading-relaxed">
              {language === 'ar'
                ? 'أدخل بريدك الإلكتروني المعتمد وسنرسل لك رابطاً مشفراً لإعادة تعيين كلمة المرور فوراً.'
                : 'Enter your registered enterprise email and a secure reset link will be sent.'}
            </p>

            <div>
              <label className="block text-xs font-semibold text-theme-primary mb-1">
                {language === 'ar' ? 'البريد الإلكتروني المسجل' : 'Registered Email'}
              </label>
              <input
                type="email"
                required
                value={email}
                onChange={(e) => setEmail(e.target.value)}
                placeholder="name@pmppaints.com"
                className="w-full px-3.5 py-2.5 rounded-lg bg-theme-surface border border-theme text-theme-primary text-sm focus:outline-none focus:border-pmp-primary"
              />
            </div>

            <div className="flex gap-2">
              <GlassButton
                type="button"
                variant="secondary"
                onClick={() => setTab('email')}
                className="flex-1 justify-center"
              >
                {language === 'ar' ? 'رجوع' : 'Back'}
              </GlassButton>

              <GlassButton
                type="submit"
                variant="primary"
                disabled={isAuthenticating}
                className="flex-1 justify-center"
              >
                {language === 'ar' ? 'إرسال الرابط' : 'Send Link'}
              </GlassButton>
            </div>
          </form>
        )}

        {/* Google Enterprise Sign-In */}
        {tab !== 'reset' && (
          <>
            <div className="relative pt-2">
              <div className="absolute inset-0 flex items-center">
                <div className="w-full border-t border-theme"></div>
              </div>
              <div className="relative flex justify-center text-xs">
                <span className="px-3 bg-theme-surface text-theme-muted font-medium">
                  {language === 'ar' ? 'أو عبر الحساب المعتمد' : 'Or via Verified Enterprise SSO'}
                </span>
              </div>
            </div>

            <GlassButton
              variant="secondary"
              onClick={handleGoogleLogin}
              disabled={isAuthenticating}
              className="w-full justify-center"
            >
              <img
                src="https://www.gstatic.com/firebasejs/ui/2.0.0/images/auth/google.svg"
                alt="Google"
                className="w-4 h-4 ml-2"
              />
              <span>{language === 'ar' ? 'الدخول بحساب Google المعتمد' : 'Sign in with Google Workspace'}</span>
            </GlassButton>
          </>
        )}

        <p className="text-[11px] text-center text-theme-muted leading-relaxed">
          {language === 'ar'
            ? 'بوابة الدخول مخصصة لموظفي ومسؤولي شركة PMP Paints فقط. يتم رصد كافة محاولات الدخول وحفظها في سجل المراقبة.'
            : 'Authorized personnel only. All access attempts are recorded in the central immutable audit log.'}
        </p>
      </div>
    </Modal>
  );
};
