import React, { ComponentType } from 'react';

const CHUNK_RELOAD_SESSION_KEY = 'pmp_chunk_reload_guard_v1';

export function isChunkLoadError(error: unknown): boolean {
  const msg = String((error as { message?: string })?.message || error || '').toLowerCase();
  const name = String((error as { name?: string })?.name || '').toLowerCase();
  return (
    name.includes('chunkloaderror') ||
    msg.includes('failed to fetch dynamically imported module') ||
    msg.includes('error loading dynamically imported module') ||
    msg.includes('importing a module script failed') ||
    msg.includes('loading chunk') ||
    msg.includes('dynamically imported module')
  );
}

/**
 * Wraps dynamic ES module imports with a transient retry and one-time safe cache-busting
 * recovery when a deployment updates hashed Vite chunk filenames on the server.
 */
export function lazyWithRetry<T extends ComponentType<any>>(
  importer: () => Promise<{ default: T }>,
  moduleLabel = 'module'
): React.LazyExoticComponent<T> {
  return React.lazy(async () => {
    try {
      const mod = await importer();
      try {
        if (typeof window !== 'undefined' && window.sessionStorage) {
          window.sessionStorage.removeItem(CHUNK_RELOAD_SESSION_KEY);
        }
      } catch {
        // Ignore restricted sessionStorage contexts
      }
      return mod;
    } catch (firstError) {
      // Transient retry once after short jitter before giving up
      await new Promise((resolve) => setTimeout(resolve, 250));
      try {
        const retryMod = await importer();
        return retryMod;
      } catch (secondError) {
        if (isChunkLoadError(secondError) && typeof window !== 'undefined') {
          try {
            const alreadyReloaded = window.sessionStorage?.getItem(CHUNK_RELOAD_SESSION_KEY) === '1';
            if (!alreadyReloaded) {
              window.sessionStorage?.setItem(CHUNK_RELOAD_SESSION_KEY, '1');
              window.location.reload();
              return new Promise<never>(() => {});
            }
          } catch {
            // Fall through to ErrorBoundary UI
          }
        }
        console.error(`[PMP LazyLoader] Failed loading ${moduleLabel}:`, firstError, secondError);
        throw secondError;
      }
    }
  });
}
