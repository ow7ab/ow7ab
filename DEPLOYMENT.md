# PMP PAINTS ENTERPRISE ERP — UNIFIED WEB & ANDROID DEPLOYMENT GUIDE

This guide documents the unified **PMP Paints Enterprise Cloud Suite**, merging Project A (Official Android App Download Website) into Project B (Main ERP Web Application) inside `/web`.

---

## 1. Unified Architecture Overview

| Component | Technology | Production Endpoint / Repository |
|---|---|---|
| **Main Unified Website** | React 18 / TypeScript / Vite (`/web`) | Serves `/`, `/login`, `/download`, `/dashboard`, `/admin`, `/employee` |
| **Android Client** | Kotlin / Jetpack Compose | `com.aistudio.pmppaints.erp` |
| **Shared Database** | Firebase Cloud Firestore | Project: `gen-lang-client-0220128746`<br/>DB ID: `ai-studio-android-glassdro-d6052420-cbc0-47dd-8cdd-8caa5e6a7248`<br/>Cluster: `/erp_clusters/PMP-ERP-MAIN-CLUSTER/` |
| **Identity & SSO** | Firebase Auth + PBKDF2-HMAC-SHA256 | Web Client ID: `675531579375-732a74lc8b5r1ilu568geaigi1u6rb0i.apps.googleusercontent.com` |
| **Live APK Channel** | GitHub Releases (Stable Only) | `https://github.com/ow7ab/ow7ab/releases` |

---

## 2. Unified Clean Routes & Access Control

- `/` — **Main ERP Website** (ERP platform showcase + integrated official Android app download & release notes)
- `/login` — **Staff & Admin Authentication** + Prominent **"Download Android App | تحميل تطبيق Android"** button
- `/download` — **Dedicated Public APK Download Portal** (no login required, zero private ERP records exposed, SHA-256 verification)
- `/dashboard` — **Protected ERP Dashboard** (requires authenticated active employee/manager/super-admin account)
- `/admin` — **Protected Administration Center** (RBAC enforced for Super Admin / System Manager / authorized admin accounts)
- `/employee` — **Protected Employee Operational Portal**

---

## 3. Public Hosting Options (All Free Tier with SPA Routing)

### Option A — GitHub Pages (`https://ow7ab.github.io/ow7ab/`)
Automatically deployed via `.github/workflows/deploy-web.yml` and `.github/workflows/build-release.yml`:
- Built with `VITE_BASE_PATH=/ow7ab/`
- Includes `web/dist/404.html` which transparently restores clean deep routes (`/login`, `/download`, `/dashboard`, `/admin`, `/employee`) without 404 errors.
- Ensure **Settings -> Pages -> Source** is set to **GitHub Actions** in the `ow7ab/ow7ab` repository.

### Option B — Cloudflare Workers Static Assets (`ow7ab`)
The root `wrangler.toml` is configured as a pure **Cloudflare Workers Static Assets** deployment targeting Worker **`ow7ab`** (pinned to `wrangler` v4 in root `devDependencies` and Node.js 22 via `.node-version`):
1. Connect repository `ow7ab/ow7ab` in Cloudflare Dashboard → **Workers & Pages** → Worker **`ow7ab`** → **Settings** → **Build**.
2. Configure Build & Deploy Settings:
   - **Root directory**: `/` (repository root)
   - **Build command**: `npm run build`
   - **Deploy command**: `npx wrangler deploy`
   - **Build Environment variables** *(optional — `.node-version` already pins Node 22 and `VITE_BASE_PATH` defaults to `/`)*: `NODE_VERSION = 22`, `VITE_BASE_PATH = /`
3. `wrangler.toml` declares `[build] command = "npm run build"` and `[assets] directory = "./web/dist"` with `not_found_handling = "single-page-application"`, and intentionally omits `[vars]` (Cloudflare rejects runtime `[vars]` on assets-only Workers without a `main` script) and `web/public/_redirects` (Cloudflare rejects `/* /index.html 200` with `code 10021`). `web/public/.assetsignore` excludes source maps (`*.map`) and GitHub Pages' `404.html` from Cloudflare uploads.

### Option C — Firebase Hosting
`firebase.json` is preconfigured with `"public": "web/dist"` and SPA rewrite `"** -> /index.html"`.
```bash
npm run build
firebase deploy --only hosting
```

---

## 4. Required Firebase Console Checklist for Public Domains

When opening the website on Cloudflare Workers, GitHub Pages, or a custom domain, add each hostname to:
**Firebase Console → Authentication → Settings → Authorized domains**:
- `ow7ab.<your-workers-subdomain>.workers.dev`
- `ow7ab.github.io`
- Any custom domain connected to Cloudflare Worker `ow7ab` or Firebase Hosting.
