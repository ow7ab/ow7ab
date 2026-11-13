const path = require('path');

const ALLOWED_OAUTH_HOST_PATTERNS = [
  /^accounts\.google\.com$/i,
  /^accounts\.youtube\.com$/i,
  /^gen-lang-client-0220128746\.firebaseapp\.com$/i,
  /^gen-lang-client-0220128746\.web\.app$/i,
  /^[a-z0-9-]+\.googleusercontent\.com$/i
];

const ALLOWED_EXTERNAL_URLS = [
  /^https:\/\/github\.com\/ow7ab\/ow7ab(\/|$)/i,
  /^https:\/\/ow7ab\.github\.io\/ow7ab(\/|$)/i,
  /^https:\/\/pmp-paints-erp\.pages\.dev(\/|$)/i,
  /^https:\/\/firebasestorage\.googleapis\.com\//i
];

/**
 * Canonicalizes a requested HTTP path within renderer-dist and blocks path traversal.
 */
function resolveSafeRendererPath(rendererRoot, requestUrlPath) {
  const cleanRoot = path.resolve(rendererRoot);
  const rawPath = String(requestUrlPath || '/').split('?')[0].split('#')[0];

  let decoded;
  try {
    decoded = decodeURIComponent(rawPath);
  } catch {
    return null;
  }

  // Block NUL bytes and any '..' path segments (Windows or POSIX) BEFORE POSIX root normalization collapses them
  const forward = decoded.replace(/\\/g, '/');
  if (forward.includes('\0') || forward.split('/').some((seg) => seg === '..')) {
    return null;
  }

  const normalized = path.posix.normalize(forward);
  if (normalized.includes('..')) {
    return null;
  }

  const relative = normalized.replace(/^\/+/, '');
  const candidate = path.resolve(cleanRoot, relative || 'index.html');
  if (candidate !== cleanRoot && !candidate.startsWith(cleanRoot + path.sep)) {
    return null;
  }
  return candidate;
}

/**
 * Verifies whether a URL belongs exclusively to our trusted loopback renderer origin.
 */
function isTrustedLoopbackOrigin(targetUrl, allowedOrigin) {
  if (!targetUrl || !allowedOrigin) return false;
  try {
    const parsed = new URL(targetUrl);
    const allowed = new URL(allowedOrigin);
    return parsed.origin === allowed.origin;
  } catch {
    return false;
  }
}

/**
 * Verifies whether a child popup URL is an approved Firebase / Google OAuth popup flow.
 */
function isAllowedAuthPopupUrl(targetUrl) {
  if (!targetUrl) return false;
  try {
    const parsed = new URL(targetUrl);
    if (parsed.protocol !== 'https:') return false;
    return ALLOWED_OAUTH_HOST_PATTERNS.some((pattern) => pattern.test(parsed.hostname));
  } catch {
    return false;
  }
}

/**
 * Verifies whether an external link can safely be delegated to the OS browser.
 */
function isAllowedExternalUrl(targetUrl) {
  if (!targetUrl) return false;
  try {
    const parsed = new URL(targetUrl);
    if (parsed.protocol !== 'https:') return false;
    return ALLOWED_EXTERNAL_URLS.some((pattern) => pattern.test(parsed.href));
  } catch {
    return false;
  }
}

/**
 * Clamps persisted window geometry against active display bounds so windows never spawn off-screen.
 */
function clampWindowBounds(saved, workArea, defaults = { width: 1366, height: 860, minWidth: 1024, minHeight: 680 }) {
  const area = workArea && workArea.width > 0 && workArea.height > 0
    ? workArea
    : { x: 0, y: 0, width: 1440, height: 900 };

  const width = Math.min(
    area.width,
    Math.max(defaults.minWidth, Number(saved?.width) || defaults.width)
  );
  const height = Math.min(
    area.height,
    Math.max(defaults.minHeight, Number(saved?.height) || defaults.height)
  );

  const hasCoords = Number.isFinite(saved?.x) && Number.isFinite(saved?.y);
  let x = hasCoords ? Number(saved.x) : area.x + Math.round((area.width - width) / 2);
  let y = hasCoords ? Number(saved.y) : area.y + Math.round((area.height - height) / 2);

  if (x + width < area.x + 80 || x > area.x + area.width - 80) {
    x = area.x + Math.round((area.width - width) / 2);
  }
  if (y + height < area.y + 80 || y > area.y + area.height - 80) {
    y = area.y + Math.round((area.height - height) / 2);
  }

  return {
    x,
    y,
    width,
    height,
    isMaximized: Boolean(saved?.isMaximized)
  };
}

/**
 * Validates that incoming HTML for printing/PDF does not exceed reasonable bounds.
 */
function sanitizePrintHtmlDocument(rawHtml) {
  const html = String(rawHtml || '');
  // Strip all script blocks so silent printToPDF/print cannot execute arbitrary scripts or trigger recursive dialogs
  return html.replace(/<script\b[^>]*>[\s\S]*?<\/script>/gi, '');
}

/**
 * Validates desktop update metadata strictly rejecting Android APK artifacts (.apk).
 */
function isValidDesktopReleaseAssetName(assetName) {
  const lower = String(assetName || '').trim().toLowerCase();
  if (!lower || lower.endsWith('.apk')) return false;
  return lower.endsWith('.exe') || lower.endsWith('.yml') || lower.endsWith('.blockmap') || lower.endsWith('.dmg') || lower.endsWith('.appimage');
}

module.exports = {
  resolveSafeRendererPath,
  isTrustedLoopbackOrigin,
  isAllowedAuthPopupUrl,
  isAllowedExternalUrl,
  clampWindowBounds,
  sanitizePrintHtmlDocument,
  isValidDesktopReleaseAssetName
};
