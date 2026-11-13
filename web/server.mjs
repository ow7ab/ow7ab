import http from 'http';
import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);
const DIST_DIR = path.join(__dirname, 'dist');
const PORT = Number(process.env.PORT || 3000);
const HOST = '0.0.0.0';

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
