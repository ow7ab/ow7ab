const {
  app,
  BrowserWindow,
  ipcMain,
  dialog,
  shell,
  clipboard,
  Notification,
  Tray,
  Menu,
  screen,
  net,
  safeStorage
} = require('electron');
const fs = require('fs');
const path = require('path');
const { autoUpdater } = require('electron-updater');
const { startLoopbackRendererServer } = require('./loopbackServer.cjs');
const {
  isTrustedLoopbackOrigin,
  isAllowedAuthPopupUrl,
  isAllowedExternalUrl,
  clampWindowBounds,
  sanitizePrintHtmlDocument,
  isValidDesktopReleaseAssetName
} = require('./security.cjs');

const PREFERRED_PORT = 47821;
const RENDERER_DIST_DIR = path.resolve(__dirname, '../renderer-dist');
const ICON_PNG_PATH = path.resolve(__dirname, '../assets/icon.png');
const ICON_ICO_PATH = path.resolve(__dirname, '../assets/icon.ico');
const TRAY_ICON_PATH = path.resolve(__dirname, '../assets/tray-icon.png');

let mainWindow = null;
let tray = null;
let localServer = null;
let trustedOrigin = '';
let isQuitting = false;
let latestUpdateState = {
  status: 'idle',
  currentVersion: app.getVersion(),
  availableVersion: null,
  releaseNotes: '',
  progressPercent: 0,
  message: ''
};

function getWindowStatePath() {
  return path.join(app.getPath('userData'), 'window-state.json');
}

function getPreferencesPath() {
  return path.join(app.getPath('userData'), 'desktop-prefs.json');
}

function getSecureVaultPath() {
  return path.join(app.getPath('userData'), 'secure-vault.json');
}

function readJsonFile(filePath, fallback) {
  try {
    if (fs.existsSync(filePath)) {
      return JSON.parse(fs.readFileSync(filePath, 'utf8'));
    }
  } catch {
    // Ignore corrupted preference file and use defaults
  }
  return fallback;
}

function writeJsonFile(filePath, data) {
  try {
    fs.mkdirSync(path.dirname(filePath), { recursive: true });
    fs.writeFileSync(filePath, JSON.stringify(data, null, 2), 'utf8');
  } catch {
    // Non-fatal preference write failure
  }
}

function getDesktopPreferences() {
  const defaults = {
    openAtLogin: false,
    minimizeToTray: false,
    confirmBeforeExit: false
  };
  const saved = readJsonFile(getPreferencesPath(), defaults);
  return {
    openAtLogin: Boolean(saved.openAtLogin),
    minimizeToTray: Boolean(saved.minimizeToTray),
    confirmBeforeExit: Boolean(saved.confirmBeforeExit)
  };
}

function saveWindowState() {
  if (!mainWindow || mainWindow.isDestroyed()) return;
  try {
    const isMaximized = mainWindow.isMaximized();
    const prev = readJsonFile(getWindowStatePath(), {});
    const bounds = isMaximized ? (prev.bounds || mainWindow.getNormalBounds()) : mainWindow.getBounds();
    writeJsonFile(getWindowStatePath(), {
      ...bounds,
      isMaximized
    });
  } catch {
    // Ignore window state write errors
  }
}

function emitWindowState() {
  if (!mainWindow || mainWindow.isDestroyed()) return;
  mainWindow.webContents.send('desktop:window-state', {
    isMaximized: mainWindow.isMaximized(),
    isFullScreen: mainWindow.isFullScreen(),
    isMinimized: mainWindow.isMinimized(),
    isFocused: mainWindow.isFocused()
  });
}

function emitUpdateStatus(patch) {
  latestUpdateState = {
    ...latestUpdateState,
    currentVersion: app.getVersion(),
    ...patch
  };
  if (mainWindow && !mainWindow.isDestroyed()) {
    mainWindow.webContents.send('desktop:updater-status', latestUpdateState);
  }
}

function assertTrustedIpcSender(event) {
  const senderUrl = event?.senderFrame?.url || event?.sender?.getURL?.() || '';
  if (!isTrustedLoopbackOrigin(senderUrl, trustedOrigin)) {
    throw new Error(`Blocked untrusted IPC origin: ${senderUrl}`);
  }
}

function createSystemTray() {
  if (tray) return;
  try {
    const iconFile = fs.existsSync(TRAY_ICON_PATH) ? TRAY_ICON_PATH : ICON_PNG_PATH;
    if (!fs.existsSync(iconFile)) return;
    tray = new Tray(iconFile);
    tray.setToolTip('PMP PAINTS ERP — Desktop');
    const menu = Menu.buildFromTemplate([
      {
        label: 'فتح منظومة PMP PAINTS ERP',
        click: () => {
          if (mainWindow) {
            if (mainWindow.isMinimized()) mainWindow.restore();
            mainWindow.show();
            mainWindow.focus();
          }
        }
      },
      {
        label: 'فحص تحديثات سطح المكتب',
        click: () => {
          void triggerDesktopUpdateCheck();
        }
      },
      { type: 'separator' },
      {
        label: 'إنهاء التطبيق بالكامل',
        click: () => {
          isQuitting = true;
          app.quit();
        }
      }
    ]);
    tray.setContextMenu(menu);
    tray.on('double-click', () => {
      if (mainWindow) {
        if (mainWindow.isMinimized()) mainWindow.restore();
        mainWindow.show();
        mainWindow.focus();
      }
    });
  } catch {
    // Non-fatal if OS environment has no system tray daemon
  }
}

async function triggerDesktopUpdateCheck() {
  if (!app.isPackaged) {
    // In unpackaged dev mode, query GitHub Desktop release feed read-only without mutating files
    emitUpdateStatus({ status: 'checking', progressPercent: 0, message: 'جاري فحص أحدث إصدار رسمي لسطح المكتب...' });
    try {
      const resp = await fetch('https://api.github.com/repos/ow7ab/ow7ab/releases?per_page=15', {
        headers: { Accept: 'application/vnd.github+json', 'User-Agent': 'PMP-PAINTS-Desktop-Updater' }
      });
      if (!resp.ok) {
        emitUpdateStatus({ status: 'not-available', message: 'نسختك محدثة (بيئة التطوير).' });
        return latestUpdateState;
      }
      const releases = await resp.json();
      const desktopRelease = Array.isArray(releases)
        ? releases.find(
            (r) =>
              !r.draft &&
              !r.prerelease &&
              String(r.tag_name || '').toLowerCase().startsWith('desktop-v') &&
              Array.isArray(r.assets) &&
              r.assets.some((a) => isValidDesktopReleaseAssetName(a.name))
          )
        : null;

      if (!desktopRelease) {
        emitUpdateStatus({
          status: 'not-available',
          message: `أنت تستخدم أحدث إصدار من تطبيق سطح المكتب (${app.getVersion()}).`
        });
        return latestUpdateState;
      }

      const remoteVer = String(desktopRelease.tag_name).replace(/^desktop-v/i, '');
      if (remoteVer && remoteVer !== app.getVersion()) {
        emitUpdateStatus({
          status: 'available',
          availableVersion: remoteVer,
          releaseNotes: String(desktopRelease.body || ''),
          message: `يتوفر تحديث جديد لتطبيق سطح المكتب (v${remoteVer}).`
        });
      } else {
        emitUpdateStatus({
          status: 'not-available',
          message: `الإصدار الحالي (v${app.getVersion()}) هو الأحدث.`
        });
      }
    } catch {
      emitUpdateStatus({
        status: 'not-available',
        message: 'تعذر الوصول لخادم التحديثات حالياً. سيستمر النظام بالعمل بشكل طبيعي.'
      });
    }
    return latestUpdateState;
  }

  try {
    emitUpdateStatus({ status: 'checking', progressPercent: 0, message: 'جاري البحث عن تحديثات سطح المكتب...' });
    await autoUpdater.checkForUpdates();
  } catch (err) {
    emitUpdateStatus({
      status: 'error',
      message: err?.message || 'تعذر التحقق من التحديثات التلقائية حالياً.'
    });
  }
  return latestUpdateState;
}

function configureDesktopAutoUpdater() {
  autoUpdater.autoDownload = false;
  autoUpdater.autoInstallOnAppQuit = true;
  autoUpdater.allowPrerelease = false;
  autoUpdater.allowDowngrade = false;

  autoUpdater.on('checking-for-update', () => {
    emitUpdateStatus({ status: 'checking', progressPercent: 0, message: 'جاري البحث عن تحديثات Windows Desktop...' });
  });

  autoUpdater.on('update-available', (info) => {
    const notesText = Array.isArray(info?.releaseNotes)
      ? info.releaseNotes.map((n) => n?.note || '').filter(Boolean).join('\n')
      : String(info?.releaseNotes || '');
    emitUpdateStatus({
      status: 'available',
      availableVersion: info?.version || null,
      releaseNotes: notesText,
      progressPercent: 0,
      message: `يتوفر إصدار جديد لسطح المكتب: v${info?.version}`
    });
    if (Notification.isSupported()) {
      new Notification({
        title: 'تحديث جديد متاح — PMP PAINTS ERP',
        body: `الإصدار v${info?.version} جاهز للتنزيل عبر مركز الإعدادات.`,
        icon: fs.existsSync(ICON_PNG_PATH) ? ICON_PNG_PATH : undefined
      }).show();
    }
  });

  autoUpdater.on('update-not-available', () => {
    emitUpdateStatus({
      status: 'not-available',
      progressPercent: 100,
      message: `تطبيق سطح المكتب محدث (${app.getVersion()}).`
    });
  });

  autoUpdater.on('download-progress', (progress) => {
    emitUpdateStatus({
      status: 'downloading',
      progressPercent: Math.round(progress?.percent || 0),
      message: `جاري تنزيل التحديث (${Math.round(progress?.percent || 0)}%)...`
    });
  });

  autoUpdater.on('update-downloaded', (info) => {
    emitUpdateStatus({
      status: 'downloaded',
      availableVersion: info?.version || latestUpdateState.availableVersion,
      progressPercent: 100,
      message: 'تم تنزيل التحديث والتحقق من سلامته. اضغط تثبيت وإعادة التشغيل عندما تنتهي من عملياتك الحالية.'
    });
  });

  autoUpdater.on('error', (err) => {
    emitUpdateStatus({
      status: 'error',
      message: err?.message || 'حدث خطأ أثناء التحقق من تحديث سطح المكتب.'
    });
  });
}

function createMainWindow() {
  const savedState = readJsonFile(getWindowStatePath(), null);
  const targetDisplay = savedState && Number.isFinite(savedState.x)
    ? screen.getDisplayNearestPoint({ x: savedState.x, y: savedState.y })
    : screen.getPrimaryDisplay();

  const bounds = clampWindowBounds(savedState, targetDisplay.workArea);
  const useCustomFrameless = process.platform === 'win32' || process.platform === 'linux';

  mainWindow = new BrowserWindow({
    x: bounds.x,
    y: bounds.y,
    width: bounds.width,
    height: bounds.height,
    minWidth: 1024,
    minHeight: 680,
    title: 'PMP PAINTS ERP',
    icon: fs.existsSync(ICON_ICO_PATH) && process.platform === 'win32' ? ICON_ICO_PATH : ICON_PNG_PATH,
    frame: !useCustomFrameless,
    backgroundColor: '#000000',
    show: false,
    autoHideMenuBar: true,
    webPreferences: {
      preload: path.resolve(__dirname, 'preload.cjs'),
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: true,
      webSecurity: true,
      allowRunningInsecureContent: false,
      devTools: !app.isPackaged
    }
  });

  if (bounds.isMaximized) {
    mainWindow.maximize();
  }

  mainWindow.once('ready-to-show', () => {
    if (!mainWindow) return;
    mainWindow.show();
    emitWindowState();
  });

  mainWindow.on('maximize', emitWindowState);
  mainWindow.on('unmaximize', emitWindowState);
  mainWindow.on('enter-full-screen', emitWindowState);
  mainWindow.on('leave-full-screen', emitWindowState);
  mainWindow.on('minimize', emitWindowState);
  mainWindow.on('restore', emitWindowState);
  mainWindow.on('resize', saveWindowState);
  mainWindow.on('move', saveWindowState);

  mainWindow.on('close', (event) => {
    saveWindowState();
    const prefs = getDesktopPreferences();
    if (!isQuitting && prefs.minimizeToTray && tray) {
      event.preventDefault();
      mainWindow.hide();
    }
  });

  // Security: Lock down navigation inside primary window strictly to our trusted loopback origin
  mainWindow.webContents.on('will-navigate', (event, targetUrl) => {
    if (!isTrustedLoopbackOrigin(targetUrl, trustedOrigin)) {
      event.preventDefault();
      if (isAllowedExternalUrl(targetUrl)) {
        void shell.openExternal(targetUrl);
      }
    }
  });

  // Security: Permit ONLY Google/Firebase Auth popups in hardened child window; delegate whitelisted links to external browser
  mainWindow.webContents.setWindowOpenHandler(({ url }) => {
    if (isAllowedAuthPopupUrl(url)) {
      return {
        action: 'allow',
        overrideBrowserWindowOptions: {
          width: 520,
          height: 680,
          parent: mainWindow,
          modal: false,
          autoHideMenuBar: true,
          title: 'تسجيل الدخول الآمن — PMP PAINTS',
          icon: fs.existsSync(ICON_PNG_PATH) ? ICON_PNG_PATH : undefined,
          webPreferences: {
            contextIsolation: true,
            nodeIntegration: false,
            sandbox: true,
            webSecurity: true
          }
        }
      };
    }

    if (isAllowedExternalUrl(url)) {
      void shell.openExternal(url);
    }
    return { action: 'deny' };
  });

  // Keyboard shortcuts at window level
  mainWindow.webContents.on('before-input-event', (event, input) => {
    if (input.type !== 'keyDown') return;
    if (input.key === 'F11') {
      event.preventDefault();
      mainWindow.setFullScreen(!mainWindow.isFullScreen());
      emitWindowState();
    } else if ((input.ctrlKey || input.metaKey) && input.key.toLowerCase() === 'k') {
      event.preventDefault();
      mainWindow.webContents.send('desktop:shortcut', 'GLOBAL_SEARCH');
    } else if ((input.ctrlKey || input.metaKey) && input.key.toLowerCase() === 'p') {
      event.preventDefault();
      mainWindow.webContents.send('desktop:shortcut', 'PRINT_REPORT');
    }
  });

  void mainWindow.loadURL(`${trustedOrigin}/`);
}

function registerIpcHandlers() {
  ipcMain.handle('desktop:get-window-state', (event) => {
    assertTrustedIpcSender(event);
    if (!mainWindow || mainWindow.isDestroyed()) {
      return { isMaximized: false, isFullScreen: false, isMinimized: false, isFocused: false };
    }
    return {
      isMaximized: mainWindow.isMaximized(),
      isFullScreen: mainWindow.isFullScreen(),
      isMinimized: mainWindow.isMinimized(),
      isFocused: mainWindow.isFocused()
    };
  });

  ipcMain.handle('desktop:window-minimize', (event) => {
    assertTrustedIpcSender(event);
    mainWindow?.minimize();
  });

  ipcMain.handle('desktop:window-maximize-toggle', (event) => {
    assertTrustedIpcSender(event);
    if (!mainWindow) return false;
    if (mainWindow.isMaximized()) {
      mainWindow.unmaximize();
    } else {
      mainWindow.maximize();
    }
    emitWindowState();
    return mainWindow.isMaximized();
  });

  ipcMain.handle('desktop:window-fullscreen-toggle', (event) => {
    assertTrustedIpcSender(event);
    if (!mainWindow) return false;
    mainWindow.setFullScreen(!mainWindow.isFullScreen());
    emitWindowState();
    return mainWindow.isFullScreen();
  });

  ipcMain.handle('desktop:window-close', (event) => {
    assertTrustedIpcSender(event);
    mainWindow?.close();
  });

  ipcMain.handle('desktop:get-app-info', (event) => {
    assertTrustedIpcSender(event);
    return {
      name: app.getName(),
      version: app.getVersion(),
      electronVersion: process.versions.electron,
      chromeVersion: process.versions.chrome,
      platform: process.platform,
      arch: process.arch,
      isPackaged: app.isPackaged,
      updateState: latestUpdateState
    };
  });

  ipcMain.handle('desktop:get-connectivity', (event) => {
    assertTrustedIpcSender(event);
    return {
      online: net.isOnline(),
      checkedAt: Date.now()
    };
  });

  ipcMain.handle('desktop:get-preferences', (event) => {
    assertTrustedIpcSender(event);
    return getDesktopPreferences();
  });

  ipcMain.handle('desktop:set-preferences', (event, prefs) => {
    assertTrustedIpcSender(event);
    const current = getDesktopPreferences();
    const next = {
      openAtLogin: prefs?.openAtLogin !== undefined ? Boolean(prefs.openAtLogin) : current.openAtLogin,
      minimizeToTray: prefs?.minimizeToTray !== undefined ? Boolean(prefs.minimizeToTray) : current.minimizeToTray,
      confirmBeforeExit: prefs?.confirmBeforeExit !== undefined ? Boolean(prefs.confirmBeforeExit) : current.confirmBeforeExit
    };
    writeJsonFile(getPreferencesPath(), next);
    try {
      app.setLoginItemSettings({ openAtLogin: next.openAtLogin });
    } catch {
      // Non-fatal in unpackaged dev mode
    }
    return next;
  });

  ipcMain.handle('desktop:save-file', async (event, options) => {
    assertTrustedIpcSender(event);
    const defaultName = String(options?.defaultFilename || `pmp_export_${Date.now()}.csv`).replace(/[<>:"/\\|?*]+/g, '_');
    const encoding = options?.encoding === 'base64' ? 'base64' : 'utf8';
    const filters = Array.isArray(options?.filters) && options.filters.length > 0
      ? options.filters
      : [{ name: 'CSV / Excel', extensions: ['csv'] }, { name: 'All Files', extensions: ['*'] }];

    const result = await dialog.showSaveDialog(mainWindow, {
      title: String(options?.title || 'حفظ الملف — PMP PAINTS ERP'),
      defaultPath: path.join(app.getPath('documents'), defaultName),
      filters
    });

    if (result.canceled || !result.filePath) {
      return { canceled: true };
    }

    const content = encoding === 'base64'
      ? Buffer.from(String(options?.content || ''), 'base64')
      : String(options?.content || '');

    fs.writeFileSync(result.filePath, content, encoding === 'base64' ? undefined : 'utf8');
    return { canceled: false, filePath: result.filePath };
  });

  ipcMain.handle('desktop:open-file', async (event, options) => {
    assertTrustedIpcSender(event);
    const result = await dialog.showOpenDialog(mainWindow, {
      title: String(options?.title || 'اختيار ملف — PMP PAINTS ERP'),
      properties: ['openFile'],
      filters: Array.isArray(options?.filters) ? options.filters : undefined
    });
    if (result.canceled || !result.filePaths?.length) {
      return { canceled: true };
    }
    const chosen = result.filePaths[0];
    const stat = fs.statSync(chosen);
    if (stat.size > 20 * 1024 * 1024) {
      throw new Error('حجم الملف يتجاوز الحد الأقصى المسموح (20 ميجابايت).');
    }
    const base64 = fs.readFileSync(chosen).toString('base64');
    return {
      canceled: false,
      filePath: chosen,
      fileName: path.basename(chosen),
      size: stat.size,
      base64
    };
  });

  ipcMain.handle('desktop:export-pdf', async (event, options) => {
    assertTrustedIpcSender(event);
    const defaultName = String(options?.defaultFilename || `pmp_report_${Date.now()}.pdf`)
      .replace(/[<>:"/\\|?*]+/g, '_')
      .replace(/(\.pdf)?$/i, '.pdf');

    const saveRes = await dialog.showSaveDialog(mainWindow, {
      title: String(options?.title || 'حفظ المستند كملف PDF — PMP PAINTS ERP'),
      defaultPath: path.join(app.getPath('documents'), defaultName),
      filters: [{ name: 'PDF Document', extensions: ['pdf'] }]
    });

    if (saveRes.canceled || !saveRes.filePath) {
      return { canceled: true };
    }

    const cleanHtml = sanitizePrintHtmlDocument(options?.html || '');
    const printWin = new BrowserWindow({
      show: false,
      width: 1080,
      height: 850,
      webPreferences: {
        contextIsolation: true,
        nodeIntegration: false,
        sandbox: true
      }
    });

    try {
      await printWin.loadURL(`data:text/html;charset=utf-8,${encodeURIComponent(cleanHtml)}`);
      await new Promise((r) => setTimeout(r, 350));
      const pdfBuffer = await printWin.webContents.printToPDF({
        printBackground: true,
        pageSize: 'A4',
        landscape: Boolean(options?.landscape)
      });
      fs.writeFileSync(saveRes.filePath, pdfBuffer);
      return { canceled: false, filePath: saveRes.filePath };
    } finally {
      if (!printWin.isDestroyed()) printWin.destroy();
    }
  });

  ipcMain.handle('desktop:print-html', async (event, options) => {
    assertTrustedIpcSender(event);
    const cleanHtml = sanitizePrintHtmlDocument(options?.html || '');
    const printWin = new BrowserWindow({
      show: false,
      width: 1080,
      height: 850,
      webPreferences: {
        contextIsolation: true,
        nodeIntegration: false,
        sandbox: true
      }
    });

    try {
      await printWin.loadURL(`data:text/html;charset=utf-8,${encodeURIComponent(cleanHtml)}`);
      await new Promise((r) => setTimeout(r, 350));
      await new Promise((resolve, reject) => {
        printWin.webContents.print({ silent: false, printBackground: true }, (success, failureReason) => {
          if (!success && failureReason && failureReason !== 'cancelled') {
            reject(new Error(failureReason));
          } else {
            resolve({ printed: success });
          }
        });
      });
      return { ok: true };
    } finally {
      if (!printWin.isDestroyed()) printWin.destroy();
    }
  });

  ipcMain.handle('desktop:open-path', async (event, filePath) => {
    assertTrustedIpcSender(event);
    if (!filePath || typeof filePath !== 'string') return '';
    return shell.openPath(filePath);
  });

  ipcMain.handle('desktop:reveal-file', (event, filePath) => {
    assertTrustedIpcSender(event);
    if (filePath && typeof filePath === 'string') {
      shell.showItemInFolder(filePath);
    }
  });

  ipcMain.handle('desktop:open-external', async (event, targetUrl) => {
    assertTrustedIpcSender(event);
    if (!isAllowedExternalUrl(targetUrl)) {
      throw new Error('Blocked untrusted external URL');
    }
    await shell.openExternal(targetUrl);
    return true;
  });

  ipcMain.handle('desktop:notify', (event, options) => {
    assertTrustedIpcSender(event);
    if (!Notification.isSupported()) return false;
    new Notification({
      title: String(options?.title || 'PMP PAINTS ERP').slice(0, 120),
      body: String(options?.body || '').slice(0, 400),
      icon: fs.existsSync(ICON_PNG_PATH) ? ICON_PNG_PATH : undefined
    }).show();
    return true;
  });

  ipcMain.handle('desktop:clipboard-write', (event, text) => {
    assertTrustedIpcSender(event);
    clipboard.writeText(String(text ?? ''));
    return true;
  });

  ipcMain.handle('desktop:secure-storage-set', (event, { key, value }) => {
    assertTrustedIpcSender(event);
    const cleanKey = String(key || '').replace(/[^a-zA-Z0-9_-]/g, '');
    if (!cleanKey || !safeStorage.isEncryptionAvailable()) return false;
    const vault = readJsonFile(getSecureVaultPath(), {});
    vault[cleanKey] = safeStorage.encryptString(String(value ?? '')).toString('base64');
    writeJsonFile(getSecureVaultPath(), vault);
    return true;
  });

  ipcMain.handle('desktop:secure-storage-get', (event, key) => {
    assertTrustedIpcSender(event);
    const cleanKey = String(key || '').replace(/[^a-zA-Z0-9_-]/g, '');
    if (!cleanKey || !safeStorage.isEncryptionAvailable()) return null;
    const vault = readJsonFile(getSecureVaultPath(), {});
    const encoded = vault[cleanKey];
    if (!encoded) return null;
    try {
      return safeStorage.decryptString(Buffer.from(encoded, 'base64'));
    } catch {
      return null;
    }
  });

  ipcMain.handle('desktop:secure-storage-remove', (event, key) => {
    assertTrustedIpcSender(event);
    const cleanKey = String(key || '').replace(/[^a-zA-Z0-9_-]/g, '');
    if (!cleanKey) return false;
    const vault = readJsonFile(getSecureVaultPath(), {});
    if (cleanKey in vault) {
      delete vault[cleanKey];
      writeJsonFile(getSecureVaultPath(), vault);
    }
    return true;
  });

  ipcMain.handle('desktop:updater-check', async (event) => {
    assertTrustedIpcSender(event);
    return triggerDesktopUpdateCheck();
  });

  ipcMain.handle('desktop:updater-download', async (event) => {
    assertTrustedIpcSender(event);
    if (!app.isPackaged) {
      emitUpdateStatus({ status: 'error', message: 'تنزيل التحديثات التلقائي متاح في النسخة المثبتة الرسمية (.exe) فقط.' });
      return latestUpdateState;
    }
    await autoUpdater.downloadUpdate();
    return latestUpdateState;
  });

  ipcMain.handle('desktop:updater-install', (event) => {
    assertTrustedIpcSender(event);
    if (app.isPackaged && latestUpdateState.status === 'downloaded') {
      isQuitting = true;
      autoUpdater.quitAndInstall(false, true);
      return true;
    }
    return false;
  });
}

const gotSingleInstanceLock = app.requestSingleInstanceLock();
if (!gotSingleInstanceLock) {
  app.quit();
} else {
  app.on('second-instance', () => {
    if (mainWindow) {
      if (mainWindow.isMinimized()) mainWindow.restore();
      mainWindow.show();
      mainWindow.focus();
    }
  });

  app.setAppUserModelId('com.aistudio.pmppaints.erp.desktop');

  app.whenReady().then(async () => {
    const started = await startLoopbackRendererServer({
      rendererDistDir: RENDERER_DIST_DIR,
      preferredPort: PREFERRED_PORT
    });
    localServer = started.server;
    trustedOrigin = started.origin;
    registerIpcHandlers();
    configureDesktopAutoUpdater();
    createSystemTray();
    createMainWindow();

    setTimeout(() => {
      void triggerDesktopUpdateCheck();
    }, 8000);
  });

  app.on('before-quit', () => {
    isQuitting = true;
    saveWindowState();
    if (localServer) {
      try {
        localServer.close();
      } catch {
        // ignore close errors
      }
    }
  });

  app.on('window-all-closed', () => {
    if (process.platform !== 'darwin') {
      app.quit();
    }
  });

  app.on('activate', () => {
    if (BrowserWindow.getAllWindows().length === 0) {
      createMainWindow();
    }
  });
}
