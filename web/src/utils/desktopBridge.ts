export interface DesktopWindowState {
  isMaximized: boolean;
  isFullScreen: boolean;
  isMinimized: boolean;
  isFocused: boolean;
}

export interface DesktopUpdateState {
  status: 'idle' | 'checking' | 'available' | 'not-available' | 'downloading' | 'downloaded' | 'error';
  currentVersion: string;
  availableVersion: string | null;
  releaseNotes: string;
  progressPercent: number;
  message: string;
}

export interface DesktopPreferences {
  openAtLogin: boolean;
  minimizeToTray: boolean;
  confirmBeforeExit: boolean;
}

export interface DesktopAppInfo {
  name: string;
  version: string;
  electronVersion: string;
  chromeVersion: string;
  platform: string;
  arch: string;
  isPackaged: boolean;
  updateState: DesktopUpdateState;
}

export interface PmpDesktopBridge {
  isDesktop: boolean;
  platform: string;
  arch: string;

  getWindowState: () => Promise<DesktopWindowState>;
  minimizeWindow: () => Promise<void>;
  toggleMaximizeWindow: () => Promise<boolean>;
  toggleFullscreenWindow: () => Promise<boolean>;
  closeWindow: () => Promise<void>;
  onWindowStateChange: (cb: (state: DesktopWindowState) => void) => () => void;

  getAppInfo: () => Promise<DesktopAppInfo>;
  getConnectivity: () => Promise<{ online: boolean; checkedAt: number }>;
  getPreferences: () => Promise<DesktopPreferences>;
  setPreferences: (prefs: Partial<DesktopPreferences>) => Promise<DesktopPreferences>;

  saveFile: (options: {
    title?: string;
    defaultFilename?: string;
    content: string;
    encoding?: 'utf8' | 'base64';
    filters?: Array<{ name: string; extensions: string[] }>;
  }) => Promise<{ canceled: boolean; filePath?: string }>;

  openFile: (options?: {
    title?: string;
    filters?: Array<{ name: string; extensions: string[] }>;
  }) => Promise<{ canceled: boolean; filePath?: string; fileName?: string; size?: number; base64?: string }>;

  exportHtmlToPdf: (options: {
    title?: string;
    defaultFilename?: string;
    html: string;
    landscape?: boolean;
  }) => Promise<{ canceled: boolean; filePath?: string }>;

  printHtml: (options: { html: string }) => Promise<{ ok: boolean }>;
  openPath: (filePath: string) => Promise<string>;
  revealInFolder: (filePath: string) => Promise<void>;
  openExternalTrusted: (url: string) => Promise<boolean>;

  notify: (options: { title: string; body: string }) => Promise<boolean>;
  copyText: (text: string) => Promise<boolean>;

  secureStorageSet: (key: string, value: string) => Promise<boolean>;
  secureStorageGet: (key: string) => Promise<string | null>;
  secureStorageRemove: (key: string) => Promise<boolean>;

  checkForDesktopUpdates: () => Promise<DesktopUpdateState>;
  downloadDesktopUpdate: () => Promise<DesktopUpdateState>;
  installDesktopUpdateAndRestart: () => Promise<boolean>;
  onUpdateStatus: (cb: (state: DesktopUpdateState) => void) => () => void;
  onShortcutAction: (cb: (action: string) => void) => () => void;
}

declare global {
  interface Window {
    pmpDesktop?: PmpDesktopBridge;
  }
}

export function getDesktopBridge(): PmpDesktopBridge | null {
  if (typeof window !== 'undefined' && window.pmpDesktop && window.pmpDesktop.isDesktop) {
    return window.pmpDesktop;
  }
  return null;
}

export function isDesktopApp(): boolean {
  return Boolean(getDesktopBridge());
}

export const DESKTOP_PRINT_EVENT = 'pmp:desktop-print-request';
export const DESKTOP_EXPORT_NOTICE_EVENT = 'pmp:desktop-export-notice';
export const DESKTOP_GLOBAL_SHORTCUT_EVENT = 'pmp:desktop-global-shortcut';

export function dispatchDesktopPrintRequest(payload: { title: string; html: string }): void {
  if (typeof window !== 'undefined') {
    window.dispatchEvent(new CustomEvent(DESKTOP_PRINT_EVENT, { detail: payload }));
  }
}

export function dispatchDesktopExportNotice(payload: { filePath: string; label: string }): void {
  if (typeof window !== 'undefined') {
    window.dispatchEvent(new CustomEvent(DESKTOP_EXPORT_NOTICE_EVENT, { detail: payload }));
  }
}

export async function copyToClipboardSmart(text: string): Promise<boolean> {
  const bridge = getDesktopBridge();
  if (bridge) {
    try {
      return await bridge.copyText(text);
    } catch {
      // Fallback below
    }
  }
  if (typeof navigator !== 'undefined' && navigator.clipboard?.writeText) {
    try {
      await navigator.clipboard.writeText(text);
      return true;
    } catch {
      return false;
    }
  }
  return false;
}
