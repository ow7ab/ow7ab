import { APP_VERSION_CONTRACT, resolveAssetUrl } from '../config/appVersionContract';

export interface StableApkRelease {
  versionCode: number;
  versionName: string;
  buildId: string;
  publishedAt: string;
  fileSizeBytes: number;
  fileSizeMb: number;
  apkSha256: string;
  signingCertSha256: string;
  downloadUrl: string;
  githubReleaseUrl: string;
  pagesDownloadUrl?: string;
  releaseNotes: string;
  releaseStatus: string;
  assetFileName: string;
  isAvailable: boolean;
  sourceLabel: string;
}

export interface ReleaseFeedResult {
  latest: StableApkRelease | null;
  history: StableApkRelease[];
  sourceLabel: string;
  apkAvailable: boolean;
  unavailableReason: string | null;
}

const VALID_PUBLISHED_STATUSES = new Set(['PUBLISHED', 'RELEASED', 'STABLE', '']);

export function isPublishedStatus(status?: string | null): boolean {
  const normalized = String(status ?? 'PUBLISHED').trim().toUpperCase();
  return VALID_PUBLISHED_STATUSES.has(normalized);
}

export function formatBytesToMb(bytes: number): number {
  if (!Number.isFinite(bytes) || bytes <= 0) return 0;
  return Math.round((bytes / (1024 * 1024)) * 100) / 100;
}

export function formatReleaseDate(rawDate?: string | null, updatedAtMs?: number): string {
  try {
    if (rawDate) {
      const parsed = new Date(rawDate);
      if (!Number.isNaN(parsed.getTime())) {
        return parsed.toISOString().slice(0, 10);
      }
    }
    if (updatedAtMs && updatedAtMs > 0) {
      const parsedMs = new Date(Number(updatedAtMs));
      if (!Number.isNaN(parsedMs.getTime())) {
        return parsedMs.toISOString().slice(0, 10);
      }
    }
  } catch {
    // ignore malformed dates
  }
  return APP_VERSION_CONTRACT.RELEASE_DATE;
}

/**
 * Ensures any legacy or unverified APK path (such as raw.githubusercontent.com or removed v3/v4 tags)
 * is replaced with the verified direct binary download stream.
 */
export function sanitizeApkDownloadUrl(rawUrl?: string | null): string {
  const clean = String(rawUrl ?? '').trim();
  if (!clean || !clean.startsWith('https://')) {
    return APP_VERSION_CONTRACT.GITHUB_LATEST_APK_URL;
  }
  if (
    clean.includes('raw.githubusercontent.com/') ||
    /\/releases\/download\/v[1-4]\./i.test(clean) ||
    /pmp-paints-v[1-4]\./i.test(clean) ||
    clean.endsWith('/releases') ||
    clean.endsWith('/releases/')
  ) {
    return APP_VERSION_CONTRACT.GITHUB_LATEST_APK_URL;
  }
  return clean;
}

/**
 * Cleans markdown and technical jargon from release notes for end-user display.
 */
export function cleanUserFacingReleaseNotes(rawNotes?: string | null, versionName = 'v18.0'): string {
  const raw = String(rawNotes ?? '').trim();
  if (!raw || raw.includes('### ') || raw.includes('SHA-256') || raw.includes('Firestore') || raw.includes('R8/ProGuard')) {
    return `• إصدار ${versionName} الرسمي المعتمد: تحسين سرعة واستقرار التطبيق وتوحيد شاشات الخزن والمخازن والمبيعات والمصنع عبر جميع الفروع.\n• ترقية سلسة ومباشرة على الهاتف مع الحفاظ الكامل على كافة البيانات والحسابات المسجلة.`;
  }
  return raw;
}

export const DEFAULT_STABLE_RELEASE: StableApkRelease = {
  versionCode: APP_VERSION_CONTRACT.CURRENT_VERSION_CODE,
  versionName: `v${APP_VERSION_CONTRACT.CURRENT_VERSION_NAME}`,
  buildId: `PMP-v${APP_VERSION_CONTRACT.CURRENT_VERSION_NAME}-b${APP_VERSION_CONTRACT.CURRENT_VERSION_CODE}`,
  publishedAt: '2026-11-12T10:15:00Z',
  fileSizeBytes: 32663762,
  fileSizeMb: 31.15,
  apkSha256: '5445d7d226aeb1480f6b75499254cb2019d8d46daa2f2cea79bd80ee1de2e57c',
  signingCertSha256: APP_VERSION_CONTRACT.SIGNING_CERT_SHA256,
  downloadUrl: APP_VERSION_CONTRACT.GITHUB_LATEST_APK_URL,
  githubReleaseUrl: APP_VERSION_CONTRACT.GITHUB_LATEST_APK_URL,
  pagesDownloadUrl: APP_VERSION_CONTRACT.GITHUB_LATEST_APK_URL,
  releaseNotes:
    '• إصدار v18.0 الرسمي المعتمد: تحسين سرعة واستقرار التطبيق وتوحيد شاشات الخزن والمخازن والمبيعات والمصنع عبر جميع الفروع.\n• ترقية سلسة ومباشرة على الهاتف مع الحفاظ الكامل على كافة البيانات والحسابات المسجلة.',
  releaseStatus: 'PUBLISHED',
  assetFileName: APP_VERSION_CONTRACT.DOWNLOAD_APK_FILENAME,
  isAvailable: true,
  sourceLabel: 'الإصدار الرسمي المعتمد'
};

/**
 * Parses a GitHub Release API response strictly excluding drafts, prereleases,
 * and releases without a valid .apk asset.
 */
export function parseGitHubRelease(releaseJson: unknown): StableApkRelease | null {
  if (!releaseJson || typeof releaseJson !== 'object') return null;
  const rel = releaseJson as Record<string, unknown>;

  if (rel.draft === true || rel.prerelease === true) return null;

  const tagName = String(rel.tag_name ?? '').trim();
  const releaseName = String(rel.name ?? '').trim();
  const body = String(rel.body ?? '').trim();
  const publishedAt = String(rel.published_at ?? rel.created_at ?? '').trim();

  const assets = Array.isArray(rel.assets) ? rel.assets : [];
  let chosenAsset: Record<string, unknown> | null = null;

  for (const rawAsset of assets) {
    if (!rawAsset || typeof rawAsset !== 'object') continue;
    const asset = rawAsset as Record<string, unknown>;
    const name = String(asset.name ?? '');
    const dlUrl = String(asset.browser_download_url ?? '');
    if (!name.toLowerCase().endsWith('.apk') || !dlUrl.startsWith('https://')) continue;

    // Prefer pmp-paints-latest.apk or versioned binary name (pmp-paints-v*-b*.apk)
    if (!chosenAsset || name === 'pmp-paints-latest.apk' || /-b\d+\.apk$/i.test(name)) {
      chosenAsset = asset;
      if (name === 'pmp-paints-latest.apk') break;
    }
  }

  if (!chosenAsset) return null;

  const apkFileName = String(chosenAsset.name ?? 'pmp-paints-latest.apk');
  const apkUrl = sanitizeApkDownloadUrl(String(chosenAsset.browser_download_url ?? ''));
  const fileSizeBytes = Number(chosenAsset.size ?? 0);

  const codeMatch =
    releaseName.match(/(?:Build|b|#)\s*(\d+)/i) ||
    apkFileName.match(/-b(\d+)\.apk/i) ||
    body.match(/versionCode[^0-9]*(\d+)/i) ||
    tagName.match(/v?(\d+)/i);
  const versionCode = codeMatch ? parseInt(codeMatch[1], 10) : 0;
  if (!versionCode || versionCode <= 0) return null;

  // GitHub API v2025+ provides asset.digest = "sha256:<hex>" directly
  const assetDigest = String(chosenAsset.digest ?? '').trim();
  const digestHexMatch = assetDigest.match(/^sha256:([a-fA-F0-9]{64})$/i);
  const bodyShaMatch = body.match(/\b([a-fA-F0-9]{64})\b/);
  const apkSha256 = (digestHexMatch?.[1] || bodyShaMatch?.[1] || '').toLowerCase();

  const buildIdMatch = body.match(/(PMP-v[^\s`)]+)/);
  const cleanV = tagName.replace(/^v/i, '') || `${versionCode}.0`;
  const buildId = buildIdMatch ? buildIdMatch[1] : `PMP-v${cleanV}-b${versionCode}`;
  const normalizedVersionName = tagName
    ? tagName.startsWith('v')
      ? tagName
      : `v${tagName}`
    : `v${cleanV}`;

  return {
    versionCode,
    versionName: normalizedVersionName,
    buildId,
    publishedAt,
    fileSizeBytes,
    fileSizeMb: formatBytesToMb(fileSizeBytes),
    apkSha256,
    signingCertSha256: APP_VERSION_CONTRACT.SIGNING_CERT_SHA256,
    downloadUrl: apkUrl,
    githubReleaseUrl: apkUrl,
    pagesDownloadUrl: APP_VERSION_CONTRACT.GITHUB_LATEST_APK_URL,
    releaseNotes: cleanUserFacingReleaseNotes(body, normalizedVersionName),
    releaseStatus: 'PUBLISHED',
    assetFileName: apkFileName,
    isAvailable: true,
    sourceLabel: 'الإصدار الرسمي المعتمد'
  };
}

export function parseVersionJsonRecord(raw: unknown, sourceLabel: string): StableApkRelease | null {
  if (!raw || typeof raw !== 'object') return null;
  const data = raw as Record<string, unknown>;
  const releaseStatus = String(data.releaseStatus ?? 'PUBLISHED').trim().toUpperCase();
  if (!isPublishedStatus(releaseStatus)) return null;

  const versionCode = Number(data.versionCode ?? 0);
  if (!Number.isFinite(versionCode) || versionCode <= 0) return null;

  const rawUrl = String(data.githubReleaseUrl || data.downloadUrl || data.apkUrl || '').trim();
  const validDownloadUrl = sanitizeApkDownloadUrl(rawUrl);

  const rawName = String(data.versionName || `v${versionCode}.0`).trim();
  const versionName = rawName.startsWith('v') ? rawName : `v${rawName}`;
  const fileSizeBytes = Number(data.apkSize ?? data.fileSize ?? 0);
  const fileSizeMb =
    Number(data.fileSizeMb ?? 0) > 0
      ? Number(data.fileSizeMb)
      : formatBytesToMb(fileSizeBytes);
  const apkSha256 = String(data.apkSha256 || data.sha256Checksum || data.sha256 || '')
    .trim()
    .toLowerCase();

  return {
    versionCode,
    versionName,
    buildId: String(data.buildId || `PMP-${versionName}-b${versionCode}`),
    publishedAt: String(data.publishedAt || ''),
    fileSizeBytes,
    fileSizeMb,
    apkSha256,
    signingCertSha256: String(data.signingCertSha256 || APP_VERSION_CONTRACT.SIGNING_CERT_SHA256),
    downloadUrl: validDownloadUrl,
    githubReleaseUrl: sanitizeApkDownloadUrl(String(data.githubReleaseUrl || validDownloadUrl)),
    pagesDownloadUrl: APP_VERSION_CONTRACT.GITHUB_LATEST_APK_URL,
    releaseNotes: cleanUserFacingReleaseNotes(String(data.releaseNotes || ''), versionName),
    releaseStatus,
    assetFileName: APP_VERSION_CONTRACT.DOWNLOAD_APK_FILENAME,
    isAvailable: validDownloadUrl.startsWith('https://'),
    sourceLabel
  };
}

export function parseFirestoreUpdateDocument(docJson: unknown, sourceLabel: string): StableApkRelease | null {
  if (!docJson || typeof docJson !== 'object') return null;
  const docObj = docJson as { fields?: Record<string, Record<string, unknown>> };
  const f = docObj.fields;
  if (!f) return null;

  if (f.rawVersionJson?.stringValue) {
    try {
      const parsedRaw = JSON.parse(String(f.rawVersionJson.stringValue));
      const converted = parseVersionJsonRecord(parsedRaw, sourceLabel);
      if (converted) return converted;
    } catch {
      // fall through to individual fields
    }
  }

  const getStr = (k: string) => String(f[k]?.stringValue ?? '');
  const getInt = (k: string) => parseInt(String(f[k]?.integerValue ?? '0'), 10) || 0;
  const getDouble = (k: string) => {
    if (f[k]?.doubleValue !== undefined) return parseFloat(String(f[k].doubleValue)) || 0;
    return getInt(k);
  };

  return parseVersionJsonRecord(
    {
      versionCode: getInt('versionCode') || getInt('version_code'),
      versionName: getStr('versionName') || getStr('version_name'),
      buildId: getStr('buildId') || getStr('build_id'),
      publishedAt: getStr('publishedAt') || getStr('published_at'),
      fileSize: getInt('apkSize') || getInt('fileSize'),
      fileSizeMb: getDouble('fileSizeMb'),
      apkSha256: getStr('apkSha256') || getStr('sha256Checksum') || getStr('sha256'),
      signingCertSha256: getStr('signingCertSha256'),
      releaseNotes: getStr('releaseNotes') || getStr('release_notes'),
      downloadUrl: getStr('downloadUrl') || getStr('apkUrl'),
      githubReleaseUrl: getStr('githubReleaseUrl'),
      releaseStatus: getStr('releaseStatus') || getStr('release_status') || 'PUBLISHED'
    },
    sourceLabel
  );
}

/**
 * Selects the highest published, non-draft, non-prerelease release.
 */
export function selectBestStableRelease(candidates: StableApkRelease[]): StableApkRelease | null {
  const valid = candidates.filter(
    (c) => c.versionCode > 0 && isPublishedStatus(c.releaseStatus) && c.isAvailable
  );
  if (valid.length === 0) return null;
  return [...valid].sort((a, b) => {
    if (b.versionCode !== a.versionCode) return b.versionCode - a.versionCode;
    const aIsGh = a.downloadUrl.includes('github.com/') ? 1 : 0;
    const bIsGh = b.downloadUrl.includes('github.com/') ? 1 : 0;
    return bIsGh - aIsGh;
  })[0];
}

async function fetchJsonWithTimeout(url: string, timeoutMs = 6000): Promise<unknown | null> {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeoutMs);
  try {
    const res = await fetch(url, {
      signal: controller.signal
    });
    if (!res.ok) return null;
    return await res.json();
  } catch {
    return null;
  } finally {
    clearTimeout(timer);
  }
}

export async function fetchReleaseFeed(): Promise<ReleaseFeedResult> {
  const [
    ghAllJson,
    ghLatestJson,
    localVersionJson,
    localHistoryJson
  ] = await Promise.all([
    fetchJsonWithTimeout(APP_VERSION_CONTRACT.GITHUB_ALL_RELEASES_API_URL),
    fetchJsonWithTimeout(APP_VERSION_CONTRACT.GITHUB_LATEST_RELEASE_API_URL),
    fetchJsonWithTimeout(resolveAssetUrl('version.json')),
    fetchJsonWithTimeout(resolveAssetUrl('releases-history.json'))
  ]);

  const candidates: StableApkRelease[] = [DEFAULT_STABLE_RELEASE];
  const historyByVersion = new Map<number, StableApkRelease>();

  const registerHistoryItem = (item: StableApkRelease | null) => {
    if (!item || !isPublishedStatus(item.releaseStatus)) return;
    const prev = historyByVersion.get(item.versionCode);
    if (!prev || (!prev.apkSha256 && item.apkSha256)) {
      historyByVersion.set(item.versionCode, {
        ...prev,
        ...item,
        releaseNotes: item.releaseNotes || prev?.releaseNotes || '',
        apkSha256: item.apkSha256 || prev?.apkSha256 || '',
        downloadUrl: sanitizeApkDownloadUrl(item.downloadUrl)
      });
    }
  };

  registerHistoryItem(DEFAULT_STABLE_RELEASE);

  // 1. Parse all live releases
  if (Array.isArray(ghAllJson)) {
    for (const rel of ghAllJson) {
      const parsed = parseGitHubRelease(rel);
      if (parsed) {
        candidates.push(parsed);
        registerHistoryItem(parsed);
      }
    }
  }

  // 2. Parse latest release
  const latestGh = parseGitHubRelease(ghLatestJson);
  if (latestGh) {
    candidates.push(latestGh);
    registerHistoryItem(latestGh);
  }

  // 3. Parse local version.json
  const localVer = parseVersionJsonRecord(localVersionJson, 'الإصدار الرسمي المعتمد');
  if (localVer) {
    candidates.push(localVer);
    registerHistoryItem(localVer);
  }

  // 4. Merge static releases-history.json baseline
  if (localHistoryJson && typeof localHistoryJson === 'object') {
    const releasesArr = (localHistoryJson as { releases?: unknown[] }).releases;
    if (Array.isArray(releasesArr)) {
      for (const item of releasesArr) {
        registerHistoryItem(parseVersionJsonRecord(item, 'أرشيف الإصدارات المعتمدة'));
      }
    }
  }

  const latest = selectBestStableRelease(candidates) || DEFAULT_STABLE_RELEASE;
  const history = Array.from(historyByVersion.values()).sort((a, b) => b.versionCode - a.versionCode);

  return {
    latest,
    history: history.length > 0 ? history : [latest],
    sourceLabel: latest.sourceLabel,
    apkAvailable: true,
    unavailableReason: null
  };
}

export async function computeFileSha256Hex(file: File): Promise<string> {
  const buffer = await file.arrayBuffer();
  const digest = await crypto.subtle.digest('SHA-256', buffer);
  return Array.from(new Uint8Array(digest))
    .map((b) => b.toString(16).padStart(2, '0'))
    .join('')
    .toLowerCase();
}
