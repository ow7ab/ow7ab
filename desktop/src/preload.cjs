const { contextBridge, ipcRenderer } = require('electron');

function subscribe(channel, handler) {
  if (typeof handler !== 'function') return () => {};
  const listener = (_event, payload) => handler(payload);
  ipcRenderer.on(channel, listener);
  return () => ipcRenderer.removeListener(channel, listener);
}

contextBridge.exposeInMainWorld('pmpDesktop', {
  isDesktop: true,
  platform: process.platform,
  arch: process.arch,

  // Window Controls & State
  getWindowState: () => ipcRenderer.invoke('desktop:get-window-state'),
  minimizeWindow: () => ipcRenderer.invoke('desktop:window-minimize'),
  toggleMaximizeWindow: () => ipcRenderer.invoke('desktop:window-maximize-toggle'),
  toggleFullscreenWindow: () => ipcRenderer.invoke('desktop:window-fullscreen-toggle'),
  closeWindow: () => ipcRenderer.invoke('desktop:window-close'),
  onWindowStateChange: (cb) => subscribe('desktop:window-state', cb),

  // App Info, Connectivity & Preferences
  getAppInfo: () => ipcRenderer.invoke('desktop:get-app-info'),
  getConnectivity: () => ipcRenderer.invoke('desktop:get-connectivity'),
  getPreferences: () => ipcRenderer.invoke('desktop:get-preferences'),
  setPreferences: (prefs) => ipcRenderer.invoke('desktop:set-preferences', prefs),

  // Native File Dialogs, PDF Generation, Printing & Shell Helpers
  saveFile: (options) => ipcRenderer.invoke('desktop:save-file', options),
  openFile: (options) => ipcRenderer.invoke('desktop:open-file', options),
  exportHtmlToPdf: (options) => ipcRenderer.invoke('desktop:export-pdf', options),
  printHtml: (options) => ipcRenderer.invoke('desktop:print-html', options),
  openPath: (filePath) => ipcRenderer.invoke('desktop:open-path', filePath),
  revealInFolder: (filePath) => ipcRenderer.invoke('desktop:reveal-file', filePath),
  openExternalTrusted: (url) => ipcRenderer.invoke('desktop:open-external', url),

  // Notifications & Clipboard
  notify: (options) => ipcRenderer.invoke('desktop:notify', options),
  copyText: (text) => ipcRenderer.invoke('desktop:clipboard-write', text),

  // OS Encrypted Storage (DPAPI on Windows via safeStorage)
  secureStorageSet: (key, value) => ipcRenderer.invoke('desktop:secure-storage-set', { key, value }),
  secureStorageGet: (key) => ipcRenderer.invoke('desktop:secure-storage-get', key),
  secureStorageRemove: (key) => ipcRenderer.invoke('desktop:secure-storage-remove', key),

  // Desktop Auto-Updater (Separate from Android APK Releases)
  checkForDesktopUpdates: () => ipcRenderer.invoke('desktop:updater-check'),
  downloadDesktopUpdate: () => ipcRenderer.invoke('desktop:updater-download'),
  installDesktopUpdateAndRestart: () => ipcRenderer.invoke('desktop:updater-install'),
  onUpdateStatus: (cb) => subscribe('desktop:updater-status', cb),

  // Global Shortcuts Triggered by Main Process
  onShortcutAction: (cb) => subscribe('desktop:shortcut', cb)
});
