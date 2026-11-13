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

const server = http.createServer((req, res) => {
  res.setHeader('Access-Control-Allow-Origin', '*');
  res.setHeader('Access-Control-Allow-Methods', 'GET, HEAD, OPTIONS');
  res.setHeader('X-Content-Type-Options', 'nosniff');
  res.setHeader('Referrer-Policy', 'strict-origin-when-cross-origin');

  if (req.method === 'OPTIONS') {
    res.writeHead(204);
    res.end();
    return;
  }

  let urlPath = (req.url || '/').split('?')[0];
  if (urlPath === '/ow7ab' || urlPath.startsWith('/ow7ab/')) {
    urlPath = urlPath.slice('/ow7ab'.length) || '/';
  }

  let safePath = path.normalize(urlPath).replace(/^(\.\.[/\\])+/, '');
  if (safePath === '/' || safePath === '') {
    safePath = '/index.html';
  }

  // Handle direct APK download requests with local fallback or 302 redirect to verified binary stream
  if (safePath.toLowerCase().endsWith('.apk') || safePath === '/download-apk') {
    const candidateApks = [
      path.join(DIST_DIR, safePath),
      path.join(ROOT_DIR, 'public', 'pmp-paints-latest.apk'),
      path.join(ROOT_DIR, 'app', 'build', 'outputs', 'apk', 'release', 'app-release.apk'),
      path.join(ROOT_DIR, 'app', 'build', 'outputs', 'apk', 'debug', 'app-debug.apk')
    ];
    const existingApk = candidateApks.find((p) => {
      try {
        return fs.statSync(p).isFile();
      } catch {
        return false;
      }
    });

    if (existingApk) {
      const stat = fs.statSync(existingApk);
      res.writeHead(200, {
        'Content-Type': 'application/vnd.android.package-archive',
        'Content-Length': stat.size,
        'Content-Disposition': 'attachment; filename="pmp-paints-latest.apk"',
        'Cache-Control': 'no-cache'
      });
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

  let filePath = path.join(DIST_DIR, safePath);

  fs.stat(filePath, (err, stats) => {
    if (err || !stats.isFile()) {
      const ext = path.extname(safePath).toLowerCase();
      if (ext && ext !== '.html') {
        res.writeHead(404, { 'Content-Type': 'text/plain; charset=utf-8' });
        res.end(`File not found: ${safePath}`);
        return;
      }
      filePath = path.join(DIST_DIR, 'index.html');
    }

    const ext = path.extname(filePath).toLowerCase();
    const contentType = MIME_TYPES[ext] || 'application/octet-stream';

    fs.readFile(filePath, (readErr, content) => {
      if (readErr) {
        res.writeHead(500, { 'Content-Type': 'text/plain; charset=utf-8' });
        res.end(`Server Error: ${readErr.message}`);
        return;
      }

      const noCache = ext === '.html' || ext === '.json';
      res.writeHead(200, {
        'Content-Type': contentType,
        'Cache-Control': noCache ? 'no-cache, no-store, must-revalidate' : 'public, max-age=31536000, immutable'
      });
      res.end(content);
    });
  });
});

server.listen(PORT, HOST, () => {
  console.log(`PMP Paints Unified ERP Web Server listening on http://${HOST}:${PORT}`);
  console.log(`Serving distribution directory: ${DIST_DIR}`);
});
