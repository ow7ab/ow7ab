const http = require('http');
const fs = require('fs');
const path = require('path');
const { resolveSafeRendererPath } = require('./security.cjs');

const MIME_TYPES = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'application/javascript; charset=utf-8',
  '.mjs': 'application/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.png': 'image/png',
  '.jpg': 'image/jpeg',
  '.jpeg': 'image/jpeg',
  '.svg': 'image/svg+xml',
  '.ico': 'image/x-icon',
  '.woff': 'font/woff',
  '.woff2': 'font/woff2',
  '.ttf': 'font/ttf',
  '.map': 'application/json'
};

function buildContentSecurityPolicy(originUrl) {
  return [
    `default-src 'self' ${originUrl}`,
    `script-src 'self' 'unsafe-inline' ${originUrl} https://apis.google.com https://www.gstatic.com`,
    `style-src 'self' 'unsafe-inline' ${originUrl} https://fonts.googleapis.com`,
    `font-src 'self' data: https://fonts.gstatic.com`,
    `img-src 'self' data: blob: https:`,
    `connect-src 'self' ${originUrl} https://*.googleapis.com https://*.firebaseio.com https://firestore.googleapis.com https://identitytoolkit.googleapis.com https://securetoken.googleapis.com https://api.github.com https://github.com wss://*.firebaseio.com`,
    `frame-src 'self' https://accounts.google.com https://gen-lang-client-0220128746.firebaseapp.com https://gen-lang-client-0220128746.web.app`
  ].join('; ');
}

/**
 * Starts a hardened 127.0.0.1 HTTP server serving renderer-dist with SPA fallback and strict CSP.
 */
function startLoopbackRendererServer({ rendererDistDir, preferredPort = 47821 }) {
  return new Promise((resolve, reject) => {
    if (!fs.existsSync(rendererDistDir)) {
      reject(new Error(`Missing renderer build directory at ${rendererDistDir}. Run npm run sync:renderer.`));
      return;
    }

    let boundOrigin = '';

    const requestHandler = (req, res) => {
      if (req.method !== 'GET' && req.method !== 'HEAD') {
        res.writeHead(405, { 'Content-Type': 'text/plain; charset=utf-8' });
        res.end('Method Not Allowed');
        return;
      }

      let candidate = resolveSafeRendererPath(rendererDistDir, req.url || '/');
      if (!candidate) {
        res.writeHead(403, { 'Content-Type': 'text/plain; charset=utf-8' });
        res.end('Forbidden');
        return;
      }

      if (!fs.existsSync(candidate) || fs.statSync(candidate).isDirectory()) {
        const ext = path.extname(candidate).toLowerCase();
        if (ext && ext !== '.html') {
          res.writeHead(404, { 'Content-Type': 'text/plain; charset=utf-8' });
          res.end('Not Found');
          return;
        }
        candidate = path.join(rendererDistDir, 'index.html');
      }

      const ext = path.extname(candidate).toLowerCase();
      const mimeType = MIME_TYPES[ext] || 'application/octet-stream';
      const headers = {
        'Content-Type': mimeType,
        'X-Content-Type-Options': 'nosniff',
        'X-Frame-Options': 'SAMEORIGIN',
        'Referrer-Policy': 'strict-origin-when-cross-origin',
        'Cache-Control': ext === '.html' || ext === '.json' ? 'no-cache, no-store, must-revalidate' : 'public, max-age=31536000'
      };
      if (ext === '.html') {
        headers['Content-Security-Policy'] = buildContentSecurityPolicy(boundOrigin);
      }

      fs.readFile(candidate, (err, content) => {
        if (err) {
          res.writeHead(500, { 'Content-Type': 'text/plain; charset=utf-8' });
          res.end('Internal Error');
          return;
        }
        res.writeHead(200, headers);
        if (req.method === 'HEAD') {
          res.end();
        } else {
          res.end(content);
        }
      });
    };

    const listenOnPort = (port) => {
      const server = http.createServer(requestHandler);
      server.once('error', (err) => {
        if (err.code === 'EADDRINUSE' && port !== 0) {
          listenOnPort(0);
        } else {
          reject(err);
        }
      });
      server.listen(port, '127.0.0.1', () => {
        const addr = server.address();
        boundOrigin = `http://localhost:${addr.port}`;
        resolve({ server, origin: boundOrigin, port: addr.port });
      });
    };

    listenOnPort(preferredPort);
  });
}

module.exports = {
  MIME_TYPES,
  buildContentSecurityPolicy,
  startLoopbackRendererServer
};
