/**
 * PMP PAINTS Enterprise ERP — Unified Version Contract & Asset Resolver
 * Keeps web platform and Android release channels strictly in sync across root (/)
 * and GitHub Pages subpath (/ow7ab/) deployments.
 */
export const APP_VERSION_CONTRACT = {
  APPLICATION_ID: 'com.aistudio.pmppaints.erp',
  APP_NAME: 'PMP Paints',
  COMPANY_NAME: 'PMP Paints for Chemical Industries & Coatings',
  COMPANY_NAME_AR: 'شركة بي إم بي للدهانات والطلاءات والصناعات الكيماوية',
  CURRENT_VERSION_CODE: 18,
  CURRENT_VERSION_NAME: '18.0',
  VERSION_NAME: 'v18.0',
  RELEASE_TITLE: 'الإصدار المعتمد 18.0 (Build 18)',
  RELEASE_DATE: '2026-11-12',
  SIGNING_CERT_SHA256: '1F:F7:75:2A:F4:96:A6:A7:4F:DF:5F:62:07:10:C7:47:83:03:1E:83:4B:2D:6D:A0:5A:AD:DB:57:A1:0D:11:05',
  MIN_ANDROID_SDK: 26,
  TARGET_ANDROID_SDK: 36,
  DOWNLOAD_APK_FILENAME: 'pmp-paints-latest.apk',
  RELEASE_ASSET_NAME: 'pmp-paints-latest.apk',
  GITHUB_REPO: 'ow7ab/ow7ab',
  GITHUB_REPO_URL: 'https://github.com/ow7ab/ow7ab',
  GITHUB_RELEASES_URL: 'https://github.com/ow7ab/ow7ab/releases',
  GITHUB_LATEST_RELEASE_API_URL: 'https://api.github.com/repos/ow7ab/ow7ab/releases/latest',
  GITHUB_ALL_RELEASES_API_URL: 'https://api.github.com/repos/ow7ab/ow7ab/releases?per_page=25',
  GITHUB_LATEST_APK_URL: 'https://github.com/ow7ab/ow7ab/releases/latest/download/pmp-paints-latest.apk',
  GITHUB_PAGES_BASE_URL: 'https://ow7ab.github.io/ow7ab',
  FIREBASE_STORAGE_VERSION_URL:
    'https://firebasestorage.googleapis.com/v0/b/gen-lang-client-0220128746.firebasestorage.app/o/version.json?alt=media',
  FIRESTORE_VERSION_JSON_DOC_URL:
    'https://firestore.googleapis.com/v1/projects/gen-lang-client-0220128746/databases/ai-studio-android-glassdro-d6052420-cbc0-47dd-8cdd-8caa5e6a7248/documents/app_updates/version_json',
  FIRESTORE_LATEST_DOC_URL:
    'https://firestore.googleapis.com/v1/projects/gen-lang-client-0220128746/databases/ai-studio-android-glassdro-d6052420-cbc0-47dd-8cdd-8caa5e6a7248/documents/app_updates/latest',
  FIRESTORE_APP_UPDATES_COLLECTION_URL:
    'https://firestore.googleapis.com/v1/projects/gen-lang-client-0220128746/databases/ai-studio-android-glassdro-d6052420-cbc0-47dd-8cdd-8caa5e6a7248/documents/app_updates?pageSize=25',
  supportedArchitectures: ['arm64-v8a', 'armeabi-v7a', 'x86_64'],
  downloadSize: '31.15 MB',
  versionName: '18.0',
  versionCode: 18,
  releaseDate: '2026-11-12',
  applicationId: 'com.aistudio.pmppaints.erp',
  sha256Certificate: '1F:F7:75:2A:F4:96:A6:A7:4F:DF:5F:62:07:10:C7:47:83:03:1E:83:4B:2D:6D:A0:5A:AD:DB:57:A1:0D:11:05',
  minSdk: 26,
  minSdkAndroid: 'Android 8.0 (Oreo) وما فوق',
  repositoryOwner: 'ow7ab',
  repositoryName: 'ow7ab'
};

export const PMP_VERSION_CONTRACT = APP_VERSION_CONTRACT;

export const getReleaseDownloadUrl = (): string => {
  return APP_VERSION_CONTRACT.GITHUB_LATEST_APK_URL;
};

export const resolveBasePath = (): string => {
  try {
    const viteBase = (import.meta as unknown as { env?: Record<string, string> })?.env?.BASE_URL;
    if (viteBase && viteBase !== './') {
      return viteBase.endsWith('/') ? viteBase : `${viteBase}/`;
    }
  } catch {
    // Fallback when running under plain Node tests
  }
  if (typeof window !== 'undefined') {
    if (window.location.pathname === '/ow7ab' || window.location.pathname.startsWith('/ow7ab/')) {
      return '/ow7ab/';
    }
  }
  return '/';
};

export const resolveAssetUrl = (relativePath: string): string => {
  const clean = relativePath.replace(/^\/+/, '');
  const base = resolveBasePath();
  return `${base}${clean}`;
};
