# PMP PAINTS ERP — Windows Desktop Application Guide (`desktop/`)

The **PMP PAINTS ERP Desktop Edition** wraps the existing React + TypeScript + Firebase ERP application (`/web`) inside a hardened Electron runtime (`Windows 10 / 11 x64`, extensible to macOS & Linux) while connecting **1:1** to the exact same backend, database, accounts, and permissions used by Web and Android.

---

## 1. Architecture Overview

- **Unified Backend & Database**:
  - Firebase Project: `gen-lang-client-0220128746`
  - Firestore Database: `ai-studio-android-glassdro-d6052420-cbc0-47dd-8cdd-8caa5e6a7248`
  - ERP Cluster Path: `/erp_clusters/PMP-ERP-MAIN-CLUSTER/{collectionName}`
  - Authentication: Shared employee accounts (`user_accounts`, `deleted_users`, `linked_accounts`), PBKDF2-HMAC-SHA256 verification, Google SSO, live Firestore session enforcement, and Windows DPAPI (`safeStorage`) encrypted local session backup.
- **Hardened Electron Shell (`desktop/src/`)**:
  - `main.cjs`: Single-instance lock, frameless custom window with persisted bounds (`window-state.json`), loopback static server bound strictly to `127.0.0.1` with Content-Security-Policy, `will-navigate` & OAuth popup allowlists, System Tray integration, native OS file dialogs, native `printToPDF` / `print`, native notifications, clipboard, and `electron-updater`.
  - `preload.cjs`: Exposes strictly scoped `window.pmpDesktop` IPC methods via `contextBridge` (`contextIsolation: true`, `nodeIntegration: false`, `sandbox: true`).
  - `security.cjs`: Path traversal protection, trusted origin validation, popup/external URL allowlists, display bounds clamping, and `.apk` update asset exclusion.
- **Renderer Reuse (`web/src/`)**:
  - All 11 ERP modules (`Dashboard`, `Treasury`, `Purchases`, `Sales`, `Inventory`, `Factory`, `Chemistry Lab`, `Users`, `Poster Prices`, `Reports`, `Settings`) run from the shared codebase via `web/src/utils/desktopBridge.ts` and `DesktopWindowChrome.tsx`.

---

## 2. Building Locally

```bash
# 1. Install dependencies
npm ci --prefix web
npm install --prefix desktop

# 2. Run verification tests
npm test --prefix web
npm run sync:renderer --prefix desktop
npm test --prefix desktop

# 3. Build Windows x64 Installer (.exe) + Portable (.exe)
# Note: Run on Windows 10/11 (or via .github/workflows/build-desktop.yml on windows-latest)
npm run dist:win --prefix desktop
```

Generated artifacts in `desktop/dist/`:
- `pmp-paints-desktop-setup-<version>-x64.exe` (NSIS Installer: Start Menu + Desktop shortcut, standard per-user install, normal Windows uninstaller, preserves `%APPDATA%\pmp-paints-desktop` settings on upgrade)
- `pmp-paints-desktop-portable-<version>-x64.exe` (Standalone Portable executable)
- `latest.yml` + `.blockmap` (Used by `electron-updater`)

---

## 3. Automated Releases & Windows Code Signing

- Trigger `.github/workflows/build-desktop.yml` via `workflow_dispatch` or by pushing a `desktop-v*` tag (kept strictly separate from Android `v*` APK tags).
- **Windows Authenticode Code Signing (Important)**:
  - Unsigned `.exe` builds will trigger a standard Windows Defender SmartScreen warning on external PCs until signed with an OV/EV Code Signing Certificate.
  - To enable trusted Authenticode signing in GitHub Actions, add repository secrets:
    - `WINDOWS_CSC_LINK`: Base64-encoded `.pfx` / `.p12` code-signing certificate.
    - `WINDOWS_CSC_KEY_PASSWORD`: Certificate password.
  - In the interim without a paid Authenticode certificate, builds remain cleanly packaged and checksum-verified (`SHA-256`), and we do **not** claim an unsigned `.exe` as Authenticode-signed.
