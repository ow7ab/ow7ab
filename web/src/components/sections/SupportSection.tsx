import React, { useState } from 'react';
import { Headphones, Mail, Phone, MapPin, Send, CheckCircle2, Building, ShieldCheck } from 'lucide-react';
import { GlassCard } from '../ui/GlassCard';
import { GlassButton } from '../ui/GlassButton';
import { GlassBadge } from '../ui/GlassBadge';
import { useLanguage } from '../../context/LanguageContext';
import { ErpDataService } from '../../services/ErpDataService';

export const SupportSection: React.FC = () => {
  const { language } = useLanguage();
  const [submitted, setSubmitted] = useState(false);
  const [submitError, setSubmitError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [formData, setFormData] = useState({
    name: '',
    branch: 'SD-001',
    subject: '',
    message: ''
  });

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!formData.name || !formData.message) return;
    setSubmitting(true);
    setSubmitError(null);
    try {
      await ErpDataService.logAudit(
        formData.name.trim(),
        'WEB_SUPPORT_TICKET',
        `[${formData.branch}] ${formData.subject || 'بلاغ فني'}: ${formData.message.trim()}`
      );
      setSubmitted(true);
      setFormData({ name: '', branch: 'SD-001', subject: '', message: '' });
      setTimeout(() => setSubmitted(false), 4500);
    } catch (err: any) {
      setSubmitError(err?.message || 'تعذر إرسال التذكرة للسحابة، يرجى المحاولة لاحقاً.');
    } finally {
      setSubmitting(false);
    }
  };

  const branches = [
    {
      name: language === 'ar' ? 'الإدارة العامة ومصنع الخرطوم' : 'HQ & Sudan Operations',
      code: 'SD-001',
      location: language === 'ar' ? 'السودان - الخرطوم / بحري الصناعية' : 'Khartoum Industrial Area, Sudan',
      phone: '+249 91 234 5678',
      email: 'sudan@pmppaints.com'
    },
    {
      name: language === 'ar' ? 'مصنع مصر والتصدير' : 'Egypt Factory & Export',
      code: 'EG-001',
      location: language === 'ar' ? 'مصر - المنطقة الصناعية' : 'Industrial Zone, Egypt',
      phone: '+20 10 987 6543',
      email: 'egypt@pmppaints.com'
    },
    {
      name: language === 'ar' ? 'مستودع ومركز لوجستيات وادي حلفا' : 'Halfa Transit Logistics Hub',
      code: 'HF-001',
      location: language === 'ar' ? 'السودان - ميناء وادي حلفا الجاف' : 'Wadi Halfa Logistics Hub, Sudan',
      phone: '+249 12 345 6789',
      email: 'halfa@pmppaints.com'
    },
    {
      name: language === 'ar' ? 'مستودع وتوزيع عطبرة' : 'Atbara Distribution Center',
      code: 'AT-002',
      location: language === 'ar' ? 'السودان - عطبرة' : 'Atbara Distribution, Sudan',
      phone: '+249 92 888 9900',
      email: 'atbara@pmppaints.com'
    }
  ];

  return (
    <section id="support" className="py-20 relative">
      <div className="max-w-6xl mx-auto px-4 sm:px-6 lg:px-8">
        <div className="text-center max-w-3xl mx-auto mb-14">
          <GlassBadge variant="primary" className="mb-4">
            <Headphones className="w-3.5 h-3.5 inline ml-1.5" />
            {language === 'ar' ? 'الدعم الفني ومراكز الفروع' : 'Enterprise Technical Support'}
          </GlassBadge>
          <h2 className="text-3xl sm:text-4xl font-extrabold text-theme-primary tracking-tight">
            {language === 'ar' ? 'فريق الدعم الفني وغرف العمليات المركزية' : 'Operations Center & Enterprise Technical Support'}
          </h2>
          <p className="mt-4 text-base sm:text-lg text-theme-secondary">
            {language === 'ar'
              ? 'تواصل مع الدعم الفني للأنظمة السحابية أو مراكز لوجستيات الفروع في السودان ومصر.'
              : 'Direct connection with our ERP cloud systems engineers and branch operations managers.'}
          </p>
        </div>

        <div className="grid grid-cols-1 lg:grid-cols-12 gap-8 items-start">
          {/* Branch Operations Cards */}
          <div className="lg:col-span-7 space-y-4">
            <h3 className="text-xl font-bold text-theme-primary mb-2 flex items-center gap-2">
              <Building className="w-5 h-5 text-pmp-primary" />
              {language === 'ar' ? 'مراكز الفروع والمستودعات' : 'Branch Hubs & Warehouses'}
            </h3>

            <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
              {branches.map((b, idx) => (
                <GlassCard key={idx} className="p-5">
                  <div className="flex justify-between items-start mb-2">
                    <h4 className="text-base font-bold text-theme-primary">{b.name}</h4>
                    <GlassBadge variant="secondary" className="font-mono text-xs">
                      {b.code}
                    </GlassBadge>
                  </div>
                  <div className="space-y-2 text-xs text-theme-secondary mt-3">
                    <div className="flex items-start gap-2">
                      <MapPin className="w-4 h-4 text-pmp-accent shrink-0 mt-0.5" />
                      <span>{b.location}</span>
                    </div>
                    <div className="flex items-center gap-2">
                      <Phone className="w-4 h-4 text-pmp-success shrink-0" />
                      <span className="font-mono">{b.phone}</span>
                    </div>
                    <div className="flex items-center gap-2">
                      <Mail className="w-4 h-4 text-pmp-muted shrink-0" />
                      <span className="font-mono">{b.email}</span>
                    </div>
                  </div>
                </GlassCard>
              ))}
            </div>

            {/* Cloud Status */}
            <div className="p-4 rounded-xl border border-pmp-success/30 bg-pmp-success/5 flex items-center justify-between">
              <div className="flex items-center gap-3">
                <div className="w-3 h-3 rounded-full bg-pmp-success animate-ping" />
                <span className="text-sm font-semibold text-theme-primary">
                  {language === 'ar' ? 'خوادم المزامنة السحابية وقاعدة البيانات تعمل بكفاءة 100%' : 'Cloud Sync & Firestore Clusters: 100% Operational'}
                </span>
              </div>
              <span className="text-xs font-mono text-pmp-success">PMP-ERP-MAIN-CLUSTER</span>
            </div>
          </div>

          {/* Contact Support Form */}
          <div className="lg:col-span-5">
            <GlassCard className="p-6 sm:p-8">
              <h3 className="text-lg font-bold text-theme-primary mb-1">
                {language === 'ar' ? 'إرسال بلاغ فني أو استفسار' : 'Submit Technical Ticket'}
              </h3>
              <p className="text-xs text-theme-secondary mb-6">
                {language === 'ar'
                  ? 'سيتم تحويل البلاغ مباشرة إلى فريق هندسة النظم ومسؤولي أمن البيانات.'
                  : 'Your request will be routed directly to system administration and database security.'}
              </p>

              {submitted ? (
                <div className="p-6 rounded-xl bg-pmp-success/10 border border-pmp-success text-center space-y-2">
                  <CheckCircle2 className="w-10 h-10 text-pmp-success mx-auto" />
                  <h4 className="text-base font-bold text-theme-primary">
                    {language === 'ar' ? 'تم استلام تذكرتك بنجاح' : 'Ticket Submitted Successfully'}
                  </h4>
                  <p className="text-xs text-theme-secondary">
                    {language === 'ar' ? 'سيتواصل معك مهندس النظم خلال دقائق.' : 'A systems engineer will review and respond shortly.'}
                  </p>
                </div>
              ) : (
                <form onSubmit={handleSubmit} className="space-y-4">
                  <div>
                    <label className="block text-xs font-semibold text-theme-primary mb-1">
                      {language === 'ar' ? 'الاسم والصفة الوظيفية *' : 'Name & Job Title *'}
                    </label>
                    <input
                      type="text"
                      required
                      value={formData.name}
                      onChange={(e) => setFormData({ ...formData, name: e.target.value })}
                      placeholder={language === 'ar' ? 'مثال: محمد أحمد - مدير فرع مصر' : 'e.g. John Doe - Branch Manager'}
                      className="w-full px-3.5 py-2.5 rounded-lg bg-theme-surface border border-theme text-theme-primary text-sm focus:outline-none focus:border-pmp-primary"
                    />
                  </div>

                  <div>
                    <label className="block text-xs font-semibold text-theme-primary mb-1">
                      {language === 'ar' ? 'الفرع أو المنشأة' : 'Branch or Facility'}
                    </label>
                    <select
                      value={formData.branch}
                      onChange={(e) => setFormData({ ...formData, branch: e.target.value })}
                      className="w-full px-3.5 py-2.5 rounded-lg bg-theme-surface border border-theme text-theme-primary text-sm focus:outline-none focus:border-pmp-primary"
                    >
                      <option value="SD-001">{language === 'ar' ? 'فرع السودان (SD-001)' : 'Sudan Branch (SD-001)'}</option>
                      <option value="EG-001">{language === 'ar' ? 'فرع ومصنع مصر (EG-001)' : 'Egypt Factory (EG-001)'}</option>
                      <option value="HF-001">{language === 'ar' ? 'مخزن حلفا اللوجستي (HF-001)' : 'Halfa Hub (HF-001)'}</option>
                      <option value="AT-002">{language === 'ar' ? 'مستودع عطبرة (AT-002)' : 'Atbara Hub (AT-002)'}</option>
                    </select>
                  </div>

                  <div>
                    <label className="block text-xs font-semibold text-theme-primary mb-1">
                      {language === 'ar' ? 'موضوع التذكرة' : 'Subject'}
                    </label>
                    <input
                      type="text"
                      value={formData.subject}
                      onChange={(e) => setFormData({ ...formData, subject: e.target.value })}
                      placeholder={language === 'ar' ? 'مثال: طلب صلاحية مؤقتة للمخزن' : 'e.g. Temporary RBAC access request'}
                      className="w-full px-3.5 py-2.5 rounded-lg bg-theme-surface border border-theme text-theme-primary text-sm focus:outline-none focus:border-pmp-primary"
                    />
                  </div>

                  <div>
                    <label className="block text-xs font-semibold text-theme-primary mb-1">
                      {language === 'ar' ? 'تفاصيل البلاغ *' : 'Message Details *'}
                    </label>
                    <textarea
                      required
                      rows={3}
                      value={formData.message}
                      onChange={(e) => setFormData({ ...formData, message: e.target.value })}
                      placeholder={language === 'ar' ? 'اكتب تفاصيل الاستفسار أو المشكلة هنا...' : 'Provide inquiry details...'}
                      className="w-full px-3.5 py-2.5 rounded-lg bg-theme-surface border border-theme text-theme-primary text-sm focus:outline-none focus:border-pmp-primary resize-none"
                    />
                  </div>

                  {submitError && (
                    <div className="p-2.5 rounded-lg bg-red-500/10 border border-red-500/30 text-red-400 text-xs">
                      {submitError}
                    </div>
                  )}

                  <GlassButton
                    type="submit"
                    variant="primary"
                    isLoading={submitting}
                    className="w-full justify-center shadow-md"
                    icon={<Send className="w-4 h-4" />}
                  >
                    {language === 'ar' ? 'إرسال التذكرة للسيرفر السحابي' : 'Submit Cloud Ticket'}
                  </GlassButton>
                </form>
              )}
            </GlassCard>
          </div>
        </div>
      </div>
    </section>
  );
};
