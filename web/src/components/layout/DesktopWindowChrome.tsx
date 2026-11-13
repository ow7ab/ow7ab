import React, { useState, useEffect, useCallback } from 'react';
import {
  Minus,
  Square,
  Copy,
  X,
  Maximize2,
  Minimize2,
  Wifi,
  WifiOff,
  RefreshCw,
  Download,
  Keyboard,
  Printer,
  FileDown,
  FolderOpen,
  ExternalLink,
  CheckCircle
} from 'lucide-react';
import {
  getDesktopBridge,
  DesktopWindowState,
  DesktopUpdateState,
  DESKTOP_PRINT_EVENT,
  DESKTOP_EXPORT_NOTICE_EVENT
} from '../../utils/desktopBridge';
import { resolveAssetUrl } from '../../config/appVersionContract';
import { useLanguage } from '../../context/LanguageContext';
import { ErpDataService } from '../../services/ErpDataService';

function stripAutoPrintScript(rawHtml: string): string {
  return String(rawHtml || '').replace(/<script\b[^>]*>[\s\S]*?<\/script>/gi, '');
}

export const DesktopWindowChrome: React.FC<{ children: React.ReactNode }> = ({ children }) => {
  const bridge = getDesktopBridge();
  const { language } = useLanguage();

  const [winState, setWinState] = useState<DesktopWindowState>({
    isMaximized: false,
    isFullScreen: false,
    isMinimized: false,
    isFocused: true
  });
  const [isOnline, setIsOnline] = useState<boolean>(
    typeof navigator !== 'undefined' ? navigator.onLine : true
  );
  const [isCheckingNet, setIsCheckingNet] = useState(false);
  const [updateState, setUpdateState] = useState<DesktopUpdateState | null>(null);
  const [showShortcutsModal, setShowShortcutsModal] = useState(false);

  const [printPreview, setPrintPreview] = useState<{ title: string; html: string } | null>(null);
  const [isExportingPdf, setIsExportingPdf] = useState(false);
  const [exportNotice, setExportNotice] = useState<{ filePath: string; label: string } | null>(null);

  const recheckConnectivity = useCallback(async () => {
    if (!bridge) return;
    setIsCheckingNet(true);
    try {
      const conn = await bridge.getConnectivity();
      const navOnline = typeof navigator !== 'undefined' ? navigator.onLine : true;
      if (conn.online && navOnline) {
        const health = await ErpDataService.checkServerHealth();
        setIsOnline(health.ok || navOnline);
      } else {
        setIsOnline(false);
      }
    } catch {
      setIsOnline(typeof navigator !== 'undefined' ? navigator.onLine : true);
    } finally {
      setIsCheckingNet(false);
    }
  }, [bridge]);

  useEffect(() => {
    if (!bridge) return;
    bridge.getWindowState().then(setWinState).catch(() => {});
    bridge.getAppInfo().then((info) => {
      if (info?.updateState) setUpdateState(info.updateState);
    }).catch(() => {});

    const unsubWin = bridge.onWindowStateChange(setWinState);
    const unsubUpd = bridge.onUpdateStatus(setUpdateState);

    const onBrowserOnline = () => {
      setIsOnline(true);
      void bridge.notify({
        title: 'PMP PAINTS ERP',
        body: language === 'ar' ? 'تمت استعادة الاتصال بالسحابة وتتم المزامنة الآن.' : 'Cloud connectivity restored.'
      });
    };
    const onBrowserOffline = () => {
      setIsOnline(false);
    };

    window.addEventListener('online', onBrowserOnline);
    window.addEventListener('offline', onBrowserOffline);

    const interval = setInterval(async () => {
      try {
        const c = await bridge.getConnectivity();
        setIsOnline(Boolean(c.online) && (typeof navigator === 'undefined' || navigator.onLine));
      } catch {
        // ignore transient poll error
      }
    }, 15000);

    return () => {
      unsubWin();
      unsubUpd();
      window.removeEventListener('online', onBrowserOnline);
      window.removeEventListener('offline', onBrowserOffline);
      clearInterval(interval);
    };
  }, [bridge, language]);

  useEffect(() => {
    if (!bridge) return;

    const handlePrintReq = (e: Event) => {
      const detail = (e as CustomEvent<{ title: string; html: string }>).detail;
      if (detail?.html) {
        setPrintPreview({
          title: detail.title || 'مستند رسمي — PMP PAINTS ERP',
          html: stripAutoPrintScript(detail.html)
        });
      }
    };

    const handleExportNotice = (e: Event) => {
      const detail = (e as CustomEvent<{ filePath: string; label: string }>).detail;
      if (detail?.filePath) {
        setExportNotice(detail);
        setTimeout(() => setExportNotice(null), 9000);
      }
    };

    window.addEventListener(DESKTOP_PRINT_EVENT, handlePrintReq);
    window.addEventListener(DESKTOP_EXPORT_NOTICE_EVENT, handleExportNotice);
    return () => {
      window.removeEventListener(DESKTOP_PRINT_EVENT, handlePrintReq);
      window.removeEventListener(DESKTOP_EXPORT_NOTICE_EVENT, handleExportNotice);
    };
  }, [bridge]);

  if (!bridge) {
    return <>{children}</>;
  }

  const handleSavePdf = async () => {
    if (!printPreview) return;
    setIsExportingPdf(true);
    try {
      const res = await bridge.exportHtmlToPdf({
        title: language === 'ar' ? 'حفظ التقرير كملف PDF' : 'Save Report as PDF',
        defaultFilename: `${printPreview.title.replace(/[^\w\u0600-\u06FF-]+/g, '_')}_${new Date().toISOString().slice(0, 10)}.pdf`,
        html: printPreview.html
      });
      if (!res.canceled && res.filePath) {
        setExportNotice({
          filePath: res.filePath,
          label: language === 'ar' ? 'تم تصدير ملف PDF بنجاح' : 'PDF Exported Successfully'
        });
        void bridge.notify({
          title: 'PMP PAINTS ERP',
          body: `${language === 'ar' ? 'تم حفظ التقرير PDF:' : 'Saved PDF:'} ${res.filePath}`
        });
        setPrintPreview(null);
      }
    } finally {
      setIsExportingPdf(false);
    }
  };

  const handleDirectPrint = async () => {
    if (!printPreview) return;
    await bridge.printHtml({ html: printPreview.html });
  };

  return (
    <div className="h-screen w-screen flex flex-col overflow-hidden bg-theme-base text-theme-primary">
      {/* Native-feeling Frameless Custom Desktop Titlebar */}
      <div
        className="h-9 shrink-0 bg-theme-surface border-b border-theme flex items-center justify-between px-2.5 select-none z-50"
        style={{ WebkitAppRegion: 'drag' } as React.CSSProperties}
        onDoubleClick={() => {
          void bridge.toggleMaximizeWindow();
        }}
      >
        {/* App Emblem & Title */}
        <div className="flex items-center gap-2 min-w-0">
          <img
            src={resolveAssetUrl('assets/ic_pmp_emblem.png')}
            alt="PMP"
            className="w-4 h-4 object-contain shrink-0"
          />
          <span className="text-[11.5px] font-black tracking-tight text-theme-primary truncate">
            PMP PAINTS ERP
          </span>
          <span className="text-[10px] font-bold px-1.5 py-0.5 rounded bg-pmp-primary/15 text-pmp-primary border border-pmp-primary/25 hidden sm:inline">
            Desktop
          </span>
        </div>

        {/* Center Status + Quick Controls */}
        <div
          className="flex items-center gap-2"
          style={{ WebkitAppRegion: 'no-drag' } as React.CSSProperties}
        >
          <button
            type="button"
            onClick={recheckConnectivity}
            className={`flex items-center gap-1.5 px-2 py-0.5 rounded-full text-[10.5px] font-bold border transition-all ${
              isOnline
                ? 'bg-emerald-500/10 border-emerald-500/30 text-emerald-400 hover:bg-emerald-500/20'
                : 'bg-red-500/15 border-red-500/40 text-red-400 hover:bg-red-500/25'
            }`}
            title={language === 'ar' ? 'فحص حالة الاتصال بالسحابة' : 'Check Cloud Connectivity'}
          >
            {isCheckingNet ? (
              <RefreshCw className="w-3 h-3 animate-spin" />
            ) : isOnline ? (
              <Wifi className="w-3 h-3" />
            ) : (
              <WifiOff className="w-3 h-3" />
            )}
            <span className="hidden md:inline">
              {isOnline
                ? language === 'ar'
                  ? 'متصل بالسحابة'
                  : 'Cloud Connected'
                : language === 'ar'
                ? 'غير متصل بالإنترنت'
                : 'Offline'}
            </span>
          </button>

          {(updateState?.status === 'available' || updateState?.status === 'downloaded') && (
            <button
              type="button"
              onClick={() => {
                if (updateState.status === 'downloaded') {
                  void bridge.installDesktopUpdateAndRestart();
                } else {
                  void bridge.downloadDesktopUpdate();
                }
              }}
              className="flex items-center gap-1 px-2 py-0.5 rounded-full bg-amber-500/15 border border-amber-500/35 text-amber-400 hover:bg-amber-500/25 text-[10.5px] font-bold"
            >
              <Download className="w-3 h-3" />
              <span>
                {updateState.status === 'downloaded'
                  ? language === 'ar'
                    ? 'تثبيت التحديث'
                    : 'Install Update'
                  : `${language === 'ar' ? 'تحديث' : 'Update'} ${updateState.availableVersion || ''}`}
              </span>
            </button>
          )}
        </div>

        {/* Window Controls + Shortcuts */}
        <div
          className="flex items-center gap-0.5 shrink-0"
          style={{ WebkitAppRegion: 'no-drag' } as React.CSSProperties}
        >
          <button
            type="button"
            onClick={() => setShowShortcutsModal(true)}
            className="w-8 h-7 rounded flex items-center justify-center text-theme-secondary hover:bg-theme-base hover:text-theme-primary transition-colors"
            title={language === 'ar' ? 'اختصارات لوحة المفاتيح' : 'Keyboard Shortcuts'}
          >
            <Keyboard className="w-3.5 h-3.5" />
          </button>

          <button
            type="button"
            onClick={() => void bridge.toggleFullscreenWindow()}
            className="w-8 h-7 rounded flex items-center justify-center text-theme-secondary hover:bg-theme-base hover:text-theme-primary transition-colors"
            title={winState.isFullScreen ? 'خروج من ملء الشاشة (F11)' : 'ملء الشاشة (F11)'}
          >
            {winState.isFullScreen ? <Minimize2 className="w-3.5 h-3.5" /> : <Maximize2 className="w-3.5 h-3.5" />}
          </button>

          <button
            type="button"
            onClick={() => void bridge.minimizeWindow()}
            className="w-8 h-7 rounded flex items-center justify-center text-theme-secondary hover:bg-theme-base hover:text-theme-primary transition-colors"
            title={language === 'ar' ? 'تصغير' : 'Minimize'}
          >
            <Minus className="w-3.5 h-3.5" />
          </button>

          <button
            type="button"
            onClick={() => void bridge.toggleMaximizeWindow()}
            className="w-8 h-7 rounded flex items-center justify-center text-theme-secondary hover:bg-theme-base hover:text-theme-primary transition-colors"
            title={
              winState.isMaximized
                ? language === 'ar'
                  ? 'استعادة الحجم'
                  : 'Restore'
                : language === 'ar'
                ? 'تكبير'
                : 'Maximize'
            }
          >
            {winState.isMaximized ? <Copy className="w-3 h-3" /> : <Square className="w-3 h-3" />}
          </button>

          <button
            type="button"
            onClick={() => void bridge.closeWindow()}
            className="w-8 h-7 rounded flex items-center justify-center text-theme-secondary hover:bg-red-600 hover:text-white transition-colors"
            title={language === 'ar' ? 'إغلاق التطبيق' : 'Close'}
          >
            <X className="w-3.5 h-3.5" />
          </button>
        </div>
      </div>

      {/* Offline Safety Banner */}
      {!isOnline && (
        <div className="bg-red-600/15 border-b border-red-500/35 px-4 py-2 flex items-center justify-between gap-3 text-xs shrink-0">
          <div className="flex items-center gap-2 text-red-400 font-bold">
            <WifiOff className="w-4 h-4 shrink-0" />
            <span>
              {language === 'ar'
                ? 'انقطع الاتصال بالإنترنت: عرض البيانات المؤقت فقط. لن يتم تأكيد أي فواتير أو حركات جديدة حتى عودة الاتصال بالسيرفر.'
                : 'Internet disconnected: read-only cached view. New writes require active server connection.'}
            </span>
          </div>
          <button
            type="button"
            onClick={recheckConnectivity}
            className="px-3 py-1 rounded-lg bg-red-600 text-white font-bold hover:bg-red-700 shrink-0 text-[11px]"
          >
            {language === 'ar' ? 'إعادة المحاولة' : 'Retry'}
          </button>
        </div>
      )}

      {/* Main Scrollable Application Content */}
      <div className="flex-1 min-h-0 overflow-y-auto relative">{children}</div>

      {/* Saved File Action Toast */}
      {exportNotice && (
        <div className="fixed bottom-4 left-4 z-50 max-w-md bg-theme-surface border border-emerald-500/40 rounded-2xl shadow-2xl p-3.5 flex items-center gap-3 text-xs">
          <CheckCircle className="w-5 h-5 text-emerald-400 shrink-0" />
          <div className="min-w-0 flex-1">
            <div className="font-bold text-theme-primary">{exportNotice.label}</div>
            <div className="text-[10.5px] text-theme-muted truncate font-mono">{exportNotice.filePath}</div>
          </div>
          <div className="flex items-center gap-1.5 shrink-0">
            <button
              type="button"
              onClick={() => void bridge.openPath(exportNotice.filePath)}
              className="px-2.5 py-1.5 rounded-lg bg-emerald-600/20 text-emerald-400 hover:bg-emerald-600/30 font-bold flex items-center gap-1"
            >
              <ExternalLink className="w-3.5 h-3.5" />
              <span>{language === 'ar' ? 'فتح' : 'Open'}</span>
            </button>
            <button
              type="button"
              onClick={() => void bridge.revealInFolder(exportNotice.filePath)}
              className="px-2.5 py-1.5 rounded-lg bg-theme-base border border-theme text-theme-primary hover:border-pmp-primary font-bold flex items-center gap-1"
            >
              <FolderOpen className="w-3.5 h-3.5" />
              <span>{language === 'ar' ? 'المجلد' : 'Folder'}</span>
            </button>
            <button
              type="button"
              onClick={() => setExportNotice(null)}
              className="p-1 text-theme-muted hover:text-theme-primary"
            >
              <X className="w-3.5 h-3.5" />
            </button>
          </div>
        </div>
      )}

      {/* Desktop Print & Official PDF Export Modal */}
      {printPreview && (
        <div
          className="fixed inset-0 z-50 bg-black/75 backdrop-blur-sm flex items-center justify-center p-4"
          onClick={() => setPrintPreview(null)}
        >
          <div
            className="w-full max-w-5xl h-[86vh] bg-theme-surface border border-theme rounded-2xl shadow-2xl flex flex-col overflow-hidden"
            onClick={(e) => e.stopPropagation()}
          >
            <div className="px-5 py-3.5 border-b border-theme flex items-center justify-between gap-3">
              <div className="min-w-0">
                <h3 className="text-sm font-black text-theme-primary truncate">{printPreview.title}</h3>
                <p className="text-[11px] text-theme-muted">
                  {language === 'ar'
                    ? 'معاينة الطباعة والتصدير الرسمي بصيغة PDF'
                    : 'Print Preview & Official PDF Export'}
                </p>
              </div>
              <div className="flex items-center gap-2 shrink-0">
                <button
                  type="button"
                  onClick={handleSavePdf}
                  disabled={isExportingPdf}
                  className="px-3.5 py-2 rounded-xl bg-red-600 hover:bg-red-700 text-white text-xs font-bold flex items-center gap-1.5 disabled:opacity-50"
                >
                  <FileDown className="w-4 h-4" />
                  <span>
                    {isExportingPdf
                      ? language === 'ar'
                        ? 'جاري الحفظ...'
                        : 'Saving...'
                      : language === 'ar'
                      ? 'حفظ كملف PDF'
                      : 'Save as PDF'}
                  </span>
                </button>
                <button
                  type="button"
                  onClick={handleDirectPrint}
                  className="px-3.5 py-2 rounded-xl bg-theme-base border border-theme hover:border-pmp-primary text-theme-primary text-xs font-bold flex items-center gap-1.5"
                >
                  <Printer className="w-4 h-4 text-pmp-cyan" />
                  <span>{language === 'ar' ? 'طباعة مباشرة' : 'Print'}</span>
                </button>
                <button
                  type="button"
                  onClick={() => setPrintPreview(null)}
                  className="p-2 rounded-xl bg-theme-base border border-theme text-theme-secondary hover:text-theme-primary"
                >
                  <X className="w-4 h-4" />
                </button>
              </div>
            </div>
            <div className="flex-1 bg-white">
              <iframe
                title={printPreview.title}
                srcDoc={printPreview.html}
                sandbox=""
                className="w-full h-full border-0"
              />
            </div>
          </div>
        </div>
      )}

      {/* Keyboard Shortcuts Reference Modal */}
      {showShortcutsModal && (
        <div
          className="fixed inset-0 z-50 bg-black/70 backdrop-blur-sm flex items-center justify-center p-4"
          onClick={() => setShowShortcutsModal(false)}
        >
          <div
            className="max-w-md w-full bg-theme-surface border border-theme rounded-2xl shadow-2xl p-5 space-y-4"
            onClick={(e) => e.stopPropagation()}
          >
            <div className="flex items-center justify-between">
              <h3 className="text-sm font-black text-theme-primary flex items-center gap-2">
                <Keyboard className="w-4 h-4 text-pmp-primary" />
                <span>{language === 'ar' ? 'اختصارات تطبيق سطح المكتب' : 'Desktop Keyboard Shortcuts'}</span>
              </h3>
              <button
                type="button"
                onClick={() => setShowShortcutsModal(false)}
                className="p-1.5 rounded-lg bg-theme-base border border-theme text-theme-secondary"
              >
                <X className="w-4 h-4" />
              </button>
            </div>
            <div className="space-y-2 text-xs">
              {[
                ['Ctrl + K', language === 'ar' ? 'فتح البحث الشامل في السجلات' : 'Global ERP Search'],
                ['Ctrl + P', language === 'ar' ? 'طباعة التقرير / حفظ PDF' : 'Print / Save PDF'],
                ['Ctrl + 1 .. 9', language === 'ar' ? 'التنقل السريع بين أقسام المنظومة' : 'Switch ERP Modules'],
                ['Ctrl + Shift + T', language === 'ar' ? 'تبديل المظهر الداكن / النهاري' : 'Toggle Theme'],
                ['Ctrl + Shift + L', language === 'ar' ? 'تبديل اللغة (عربي / EN)' : 'Switch Language'],
                ['F11', language === 'ar' ? 'تبديل وضع ملء الشاشة' : 'Toggle Fullscreen']
              ].map(([keys, desc]) => (
                <div key={keys} className="flex items-center justify-between p-2.5 rounded-xl bg-theme-base border border-theme">
                  <span className="text-theme-secondary font-semibold">{desc}</span>
                  <kbd className="px-2 py-0.5 rounded bg-theme-surface border border-theme font-mono text-[11px] font-bold text-pmp-primary">
                    {keys}
                  </kbd>
                </div>
              ))}
            </div>
          </div>
        </div>
      )}
    </div>
  );
};
