# PMP PAINTS ENTERPRISE ERP — PRODUCTION DEPLOYMENT GUIDE

This guide documents the production deployment architecture for the **PMP Paints Enterprise Cloud Suite** uniting:
1. The **Android ERP Application** (`com.aistudio.pmppaints.erp`)
2. The **Official Public Download Website & Staff Portal** (`/web`)
3. The **Shared Firebase Backend & Central Cluster** (`PMP-ERP-V16-CLUSTER`)
4. **Cloudflare Pages / GitHub Pages** Web Hosting

---

## 1. Production Architecture Overview

| Component | Technology | Production Endpoint / Repository |
|---|---|---|
| **Android Client** | Kotlin / Jetpack Compose | `com.aistudio.pmppaints.erp` (v18.0, Build 18) |
| **Web Platform** | React 18 / TypeScript / Vite | Subdirectory `/web` |
| **Backend & Database** | Firebase Cloud Firestore | Project: `gen-lang-client-0220128746`<br/>Cluster: `/erp_clusters/PMP-ERP-V16-CLUSTER/` |
| **Identity & SSO** | Firebase Auth & PBKDF2 | Web Client ID: `675531579375-732a74lc8b5r1ilu568geaigi1u6rb0i.apps.googleusercontent.com` |
| **Release Channel** | GitHub Releases & OTA | `https://github.com/ow7ab/ow7ab/releases` |

---

## 2. Cloudflare Pages Deployment Configuration

To deploy the web platform for free on Cloudflare Pages:

1. **Log in to Cloudflare Dashboard** -> Navigate to **Workers & Pages** -> **Create application** -> **Pages** -> **Connect to Git**.
2. Select repository: `ow7ab/ow7ab`.
3. Configure **Build Settings**:
   - **Framework preset**: `Vite`
   - **Build command**: `npm run build --prefix web`
   - **Build output directory**: `web/dist`
   - **Root directory**: `/` (or leave empty)
4. Configure **Environment Variables** in Cloudflare Pages:
   - `NODE_VERSION`: `22`
5. Click **Save and Deploy**. Cloudflare Pages automatically provides a global CDN URL (e.g. `https://pmp-paints.pages.dev`).

---

## 3. GitHub Actions Release Separation

The workflows are strictly separated to prevent redundant cross-builds:
1. `.github/workflows/build-release.yml`: Builds, tests, signs, and publishes the Android APK when Android source files (`app/**`) or version tags (`v*`) are modified. It skips APK builds if only web files are changed.
2. `.github/workflows/deploy-web.yml`: Validates, runs automated tests, and bundles the web platform when `/web/**` or `/public/**` are pushed.

---

## 4. Security Audit Verification

- **API Keys & Secrets**: No private keys or service account credentials committed to git.
- **Rules Protection**: `firestore.rules` enforces deny-by-default, owner-matching on `/linked_accounts`, and denies unauthorized role or permission escalation.
- **Audit Logging**: All permission changes and critical transactions are recorded immutably in `/permission_audit_logs`.
