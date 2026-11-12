/**
 * PMP PAINTS Enterprise ERP — Unified Version Contract
 * Keeps web platform and Android release channels strictly in sync.
 */
export const APP_VERSION_CONTRACT = {
  APPLICATION_ID: 'com.aistudio.pmppaints.erp',
  APP_NAME: 'PMP Paints',
  COMPANY_NAME: 'PMP Paints for Chemical Industries & Coatings',
  COMPANY_NAME_AR: 'شركة بي إم بي للدهانات والطلاءات والصناعات الكيماوية',
  CURRENT_VERSION_CODE: 18,
  CURRENT_VERSION_NAME: '18.0',
  RELEASE_TITLE: 'الإصدار المعتمد 18.0 (Build 18)',
  RELEASE_DATE: '2026-11-12',
  SIGNING_CERT_SHA256: '1F:F7:75:2A:F4:96:A6:A7:4F:DF:5F:62:07:10:C7:47:83:03:1E:83:4B:2D:6D:A0:5A:AD:DB:57:A1:0D:11:05',
  MIN_ANDROID_SDK: 26,
  TARGET_ANDROID_SDK: 34,
  DOWNLOAD_APK_FILENAME: 'pmp-paints-latest.apk',
  GITHUB_REPO_URL: 'https://github.com/ow7ab/ow7ab',
  GITHUB_RELEASES_URL: 'https://github.com/ow7ab/ow7ab/releases',
  LATEST_APK_RAW_URL: 'https://raw.githubusercontent.com/ow7ab/ow7ab/main/public/pmp-paints-latest.apk',
  supportedArchitectures: ['arm64-v8a', 'armeabi-v7a', 'x86_64'],
  downloadSize: '~14.8 MB',
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

export const getReleaseDownloadUrl = () => {
  return APP_VERSION_CONTRACT.LATEST_APK_RAW_URL;
};
