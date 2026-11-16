import http from 'http';
import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);
const DIST_DIR = path.join(__dirname, 'dist');
const ROOT_DIR = path.resolve(__dirname, '..');
const PORT = Number(process.env.PORT || 3000);
const HOST = '0.0.0.0';
const DIRECT_GITHUB_APK_URL = 'https://github.com/ow7ab/ow7ab/releases/latest/download/pmp-paints-latest.apk';

const MIME_TYPES = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'application/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.png': 'image/png',
  '.jpg': 'image/jpeg',
  '.jpeg': 'image/jpeg',
  '.gif': 'image/gif',
  '.svg': 'image/svg+xml',
  '.ico': 'image/x-icon',
  '.woff': 'font/woff',
  '.woff2': 'font/woff2',
  '.ttf': 'font/ttf',
  '.apk': 'application/vnd.android.package-archive',
  '.map': 'application/json'
};

function findExistingFile(candidates) {
  for (const candidate of candidates) {
    try {
      const stat = fs.statSync(candidate);
      if (stat.isFile()) {
        return { filePath: candidate, stat };
      }
    } catch {
      // Try next candidate
    }
  }
  return null;
}

const server = http.createServer((req, res) => {
  res.setHeader('Access-Control-Allow-Origin', '*');
  res.setHeader('Access-Control-Allow-Methods', 'GET, HEAD, OPTIONS');
  res.setHeader('X-Content-Type-Options', 'nosniff');
  res.setHeader('X-Frame-Options', 'SAMEORIGIN');
  res.setHeader('Referrer-Policy', 'strict-origin-when-cross-origin');
  res.setHeader('Permissions-Policy', 'camera=(), microphone=(), geolocation=()');

  if (req.method === 'OPTIONS') {
    res.writeHead(204);
    res.end();
    return;
  }

  if (req.method !== 'GET' && req.method !== 'HEAD') {
    res.writeHead(405, { 'Content-Type': 'text/plain; charset=utf-8', Allow: 'GET, HEAD, OPTIONS' });
    res.end('Method Not Allowed');
    return;
  }

  const isHead = req.method === 'HEAD';
  let urlPath = (req.url || '/').split('?')[0];
  if (urlPath === '/ow7ab' || urlPath.startsWith('/ow7ab/')) {
    urlPath = urlPath.slice('/ow7ab'.length) || '/';
  }

  let safePath = path.normalize(urlPath).replace(/^(\.\.[/\\])+/, '');
  if (safePath === '/' || safePath === '') {
    safePath = '/index.html';
  }

  // Handle direct APK download requests with local Range-capable fallback or 302 redirect to verified binary stream
  if (safePath.toLowerCase().endsWith('.apk') || safePath === '/download-apk') {
    const resolvedApk = findExistingFile([
      path.join(DIST_DIR, safePath),
      path.join(ROOT_DIR, 'public', 'pmp-paints-latest.apk'),
      path.join(ROOT_DIR, 'app', 'build', 'outputs', 'apk', 'release', 'app-release.apk'),
      path.join(ROOT_DIR, 'app', 'build', 'outputs', 'apk', 'debug', 'app-debug.apk')
    ]);

    if (resolvedApk) {
      const { filePath: existingApk, stat } = resolvedApk;
      const totalSize = stat.size;
      const baseHeaders = {
        'Content-Type': 'application/vnd.android.package-archive',
        'Accept-Ranges': 'bytes',
        'Content-Disposition': 'attachment; filename="pmp-paints-latest.apk"',
        'Cache-Control': 'no-cache'
      };

      const rangeHeader = req.headers.range;
      if (rangeHeader && /^bytes=\d*-\d*$/i.test(rangeHeader)) {
        const [startStr, endStr] = rangeHeader.replace(/^bytes=/i, '').split('-');
        const start = startStr ? parseInt(startStr, 10) : 0;
        const end = endStr ? Math.min(parseInt(endStr, 10), totalSize - 1) : totalSize - 1;
        if (!Number.isFinite(start) || !Number.isFinite(end) || start > end || start >= totalSize) {
          res.writeHead(416, { 'Content-Range': `bytes */${totalSize}` });
          res.end();
          return;
        }
        const chunkSize = end - start + 1;
        res.writeHead(206, {
          ...baseHeaders,
          'Content-Range': `bytes ${start}-${end}/${totalSize}`,
          'Content-Length': chunkSize
        });
        if (isHead) {
          res.end();
          return;
        }
        fs.createReadStream(existingApk, { start, end }).pipe(res);
        return;
      }

      res.writeHead(200, {
        ...baseHeaders,
        'Content-Length': totalSize
      });
      if (isHead) {
        res.end();
        return;
      }
      fs.createReadStream(existingApk).pipe(res);
      return;
    }

    res.writeHead(302, {
      Location: DIRECT_GITHUB_APK_URL,
      'Cache-Control': 'no-cache'
    });
    res.end();
    return;
  }

  // Manifest JSON fallback (serves from dist first, then project public/root manifests)
  if (safePath === '/version.json' || safePath === '/releases-history.json') {
    const manifestName = path.basename(safePath);
    const resolvedJson = findExistingFile([
      path.join(DIST_DIR, manifestName),
      path.join(ROOT_DIR, 'public', manifestName),
      path.join(ROOT_DIR, manifestName)
    ]);
    if (resolvedJson) {
      res.writeHead(200, {
        'Content-Type': 'application/json; charset=utf-8',
        'Content-Length': resolvedJson.stat.size,
        'Cache-Control': 'no-cache, no-store, must-revalidate'
      });
      if (isHead) {
        res.end();
        return;
      }
      fs.createReadStream(resolvedJson.filePath).pipe(res);
      return;
    }
  }

  let filePath = path.join(DIST_DIR, safePath);

  fs.stat(filePath, (err, stats) => {
    let activeStats = stats;
    if (err || !stats.isFile()) {
      const ext = path.extname(safePath).toLowerCase();
      if (ext && ext !== '.html') {
        res.writeHead(404, { 'Content-Type': 'text/plain; charset=utf-8' });
        res.end(isHead ? undefined : `File not found: ${safePath}`);
        return;
      }
      filePath = path.join(DIST_DIR, 'index.html');
      try {
        activeStats = fs.statSync(filePath);
      } catch {
        res.writeHead(404, { 'Content-Type': 'text/plain; charset=utf-8' });
        res.end(isHead ? undefined : 'Application bundle not built yet.');
        return;
      }
    }

    const ext = path.extname(filePath).toLowerCase();
    const contentType = MIME_TYPES[ext] || 'application/octet-stream';
    const noCache = ext === '.html' || ext === '.json';
    const headers = {
      'Content-Type': contentType,
      'Content-Length': activeStats.size,
      'Cache-Control': noCache ? 'no-cache, no-store, must-revalidate' : 'public, max-age=31536000, immutable'
    };

    if (isHead) {
      res.writeHead(200, headers);
      res.end();
      return;
    }

    res.writeHead(200, headers);
    fs.createReadStream(filePath).pipe(res);
  });
});

server.listen(PORT, HOST, () => {
  console.log(`PMP Paints Unified ERP Web Server listening on http://${HOST}:${PORT}`);
  console.log(`Serving distribution directory: ${DIST_DIR}`);
});
