export interface AuthDomainCheckResult {
  hostname: string;
  protocol: string;
  origin: string;
  isLocalhost: boolean;
  isTrustedOrigin: boolean;
  isSecureContext: boolean;
  canStartFirebaseAuth: boolean;
  isDefaultFirebaseHostedDomain: boolean;
  requiresFirebaseAuthorizedDomainEntry: boolean;
  firebaseSettingsUrl: string;
  reasonAr: string;
  reasonEn: string;
}

export const FIREBASE_PROJECT_ID = 'gen-lang-client-0220128746';
export const FIREBASE_AUTH_SETTINGS_URL =
  `https://console.firebase.google.com/project/${FIREBASE_PROJECT_ID}/authentication/settings`;

const EXACT_TRUSTED_HOSTS = new Set<string>([
  'localhost',
  '127.0.0.1',
  '[::1]',
  'ow7ab.github.io',
  'ow7ab.workers.dev',
  'pmp-paints.com',
  'www.pmp-paints.com',
  `${FIREBASE_PROJECT_ID}.firebaseapp.com`,
  `${FIREBASE_PROJECT_ID}.web.app`
]);

const DEFAULT_FIREBASE_HOSTS = new Set<string>([
  'localhost',
  '127.0.0.1',
  '[::1]',
  `${FIREBASE_PROJECT_ID}.firebaseapp.com`,
  `${FIREBASE_PROJECT_ID}.web.app`
]);

function isTrustedHostname(rawHost: string): boolean {
  const host = (rawHost || '').trim().toLowerCase().replace(/\.$/, '');
  if (!host) return false;
  if (EXACT_TRUSTED_HOSTS.has(host)) return true;

  // Official subdomains of pmp-paints.com
  if (/^[a-z0-9-]+\.pmp-paints\.com$/.test(host)) return true;

  // Official Cloudflare Workers / Pages deployments for ow7ab or pmp-paints
  if (/^ow7ab(\.[a-z0-9-]+)?\.workers\.dev$/.test(host)) return true;
  if (/^[a-z0-9-]+\.ow7ab\.workers\.dev$/.test(host)) return true;
  if (/^(pmp-paints|ow7ab)(-[a-z0-9-]+)?\.pages\.dev$/.test(host)) return true;

  return false;
}

/**
 * Evaluates whether the current browser domain & protocol are trusted
 * BEFORE initiating any Firebase Authentication flow.
 */
export function evaluateAuthDomain(
  hostnameOverride?: string,
  protocolOverride?: string
): AuthDomainCheckResult {
  const hasWindow = typeof window !== 'undefined' && Boolean(window.location);
  const rawHost =
    hostnameOverride !== undefined
      ? hostnameOverride
      : hasWindow
      ? window.location.hostname
      : 'localhost';
  const rawProto =
    protocolOverride !== undefined
      ? protocolOverride
      : hasWindow
      ? window.location.protocol
      : 'https:';

  const hostname = (rawHost || '').trim().toLowerCase();
  const protocol = (rawProto || '').trim().toLowerCase();
  const origin = hostname ? `${protocol}//${hostname}` : protocol;

  const isLocalhost =
    hostname === 'localhost' || hostname === '127.0.0.1' || hostname === '[::1]';
  const isSecureContext = protocol === 'https:' || (isLocalhost && protocol === 'http:');
  const isTrustedOrigin = isTrustedHostname(hostname);
  const isDefaultFirebaseHostedDomain = DEFAULT_FIREBASE_HOSTS.has(hostname);
  const canStartFirebaseAuth = isTrustedOrigin && isSecureContext;
  const requiresFirebaseAuthorizedDomainEntry =
    canStartFirebaseAuth && !isDefaultFirebaseHostedDomain;

  if (!isTrustedOrigin) {
    return {
      hostname,
      protocol,
      origin,
      isLocalhost,
      isTrustedOrigin: false,
      isSecureContext,
      canStartFirebaseAuth: false,
      isDefaultFirebaseHostedDomain: false,
      requiresFirebaseAuthorizedDomainEntry: false,
      firebaseSettingsUrl: FIREBASE_AUTH_SETTINGS_URL,
      reasonAr: `تم إيقاف المصادقة قبل الاتصال بـ Firebase: النطاق الحالي (${hostname || 'غير معروف'}) غير مدرج ضمن نطاقات PMP PAINTS الموثوقة.`,
      reasonEn: `Authentication blocked before Firebase Auth: current domain (${hostname || 'unknown'}) is not a trusted PMP PAINTS origin.`
    };
  }

  if (!isSecureContext) {
    return {
      hostname,
      protocol,
      origin,
      isLocalhost,
      isTrustedOrigin: true,
      isSecureContext: false,
      canStartFirebaseAuth: false,
      isDefaultFirebaseHostedDomain,
      requiresFirebaseAuthorizedDomainEntry: false,
      firebaseSettingsUrl: FIREBASE_AUTH_SETTINGS_URL,
      reasonAr: `تم إيقاف المصادقة: يجب فتح النطاق (${hostname}) عبر اتصال مشفر HTTPS قبل بدء تسجيل الدخول عبر Firebase Auth.`,
      reasonEn: `Authentication blocked: domain (${hostname}) must be loaded over HTTPS before starting Firebase Auth.`
    };
  }

  return {
    hostname,
    protocol,
    origin,
    isLocalhost,
    isTrustedOrigin: true,
    isSecureContext: true,
    canStartFirebaseAuth: true,
    isDefaultFirebaseHostedDomain,
    requiresFirebaseAuthorizedDomainEntry,
    firebaseSettingsUrl: FIREBASE_AUTH_SETTINGS_URL,
    reasonAr: requiresFirebaseAuthorizedDomainEntry
      ? `نطاق رسمي موثوق (${hostname}) — تأكد من إدراجه في Authorized Domains بلوحة Firebase لتفعيل دخول Google.`
      : `نطاق موثوق ومعتمد بالكامل (${hostname})`,
    reasonEn: requiresFirebaseAuthorizedDomainEntry
      ? `Verified official origin (${hostname}) — ensure it is listed under Firebase Auth Authorized Domains for Google Sign-In.`
      : `Verified trusted origin (${hostname})`
  };
}

/**
 * Throws a descriptive bilingual error BEFORE Firebase Auth starts
 * if the current domain is untrusted or insecure.
 */
export function assertTrustedAuthDomain(
  hostnameOverride?: string,
  protocolOverride?: string
): AuthDomainCheckResult {
  const result = evaluateAuthDomain(hostnameOverride, protocolOverride);
  if (!result.canStartFirebaseAuth) {
    throw new Error(result.reasonAr);
  }
  return result;
}
