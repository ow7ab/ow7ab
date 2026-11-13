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

### Option B — Cloudflare Pages (`*.pages.dev`)
1. Connect repository `ow7ab/ow7ab` in Cloudflare Dashboard -> **Workers & Pages** -> **Pages**.
2. Configure Build Settings:
   - **Build command**: `npm run build --prefix web`
   - **Build output directory**: `web/dist`
   - **Environment variable**: `NODE_VERSION = 22`
3. SPA deep-linking (`_redirects`: `/* /index.html 200`) and security headers (`_headers`) are pre-bundled inside `web/public/`.
4. Optional automated deploy from GitHub Actions: add `CLOUDFLARE_API_TOKEN` and `CLOUDFLARE_ACCOUNT_ID` to GitHub Repository Secrets.

### Option C — Firebase Hosting
`firebase.json` is preconfigured with `"public": "web/dist"` and SPA rewrite `"** -> /index.html"`.
```bash
npm run build --prefix web
firebase deploy --only hosting
```

---

## 4. Required Firebase Console Checklist for New Public Domains

When opening the website on GitHub Pages (`https://ow7ab.github.io`), Cloudflare Pages (`https://<project>.pages.dev`), or a custom domain, add each domain to:
**Firebase Console -> Authentication -> Settings -> Authorized domains**:
- `ow7ab.github.io`
- `<your-project>.pages.dev`
- Any custom domain connected to Cloudflare / Firebase Hosting.
