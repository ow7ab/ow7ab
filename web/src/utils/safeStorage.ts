/**
 * Safe Storage & Browser History Utilities
 * Prevents synchronous SecurityError / QuotaExceededError crashes in sandboxed iframes,
 * strict privacy modes, or restricted WebViews where localStorage/history access throws.
 */

const inMemoryFallback = new Map<string, string>();

export function safeStorageGet(key: string): string | null {
  try {
    if (typeof window !== 'undefined' && window.localStorage) {
      return window.localStorage.getItem(key);
    }
    if (typeof globalThis !== 'undefined' && (globalThis as unknown as { localStorage?: Storage }).localStorage) {
      return (globalThis as unknown as { localStorage: Storage }).localStorage.getItem(key);
    }
  } catch {
    // Fallback to in-memory store
  }
  return inMemoryFallback.has(key) ? (inMemoryFallback.get(key) ?? null) : null;
}

export function safeStorageSet(key: string, value: string): void {
  inMemoryFallback.set(key, value);
  try {
    if (typeof window !== 'undefined' && window.localStorage) {
      window.localStorage.setItem(key, value);
      return;
    }
    if (typeof globalThis !== 'undefined' && (globalThis as unknown as { localStorage?: Storage }).localStorage) {
      (globalThis as unknown as { localStorage: Storage }).localStorage.setItem(key, value);
    }
  } catch {
    // Keep value in memory safely
  }
}

export function safeStorageRemove(key: string): void {
  inMemoryFallback.delete(key);
  try {
    if (typeof window !== 'undefined' && window.localStorage) {
      window.localStorage.removeItem(key);
      return;
    }
    if (typeof globalThis !== 'undefined' && (globalThis as unknown as { localStorage?: Storage }).localStorage) {
      (globalThis as unknown as { localStorage: Storage }).localStorage.removeItem(key);
    }
  } catch {
    // Ignore storage access errors
  }
}

export function safeHistoryReplace(state: unknown, title: string, url: string): void {
  try {
    if (typeof window !== 'undefined' && window.history?.replaceState) {
      window.history.replaceState(state, title, url);
    }
  } catch {
    // Ignore restricted history mutations (e.g., srcdoc / sandboxed environments)
  }
}

export function safeHistoryPush(state: unknown, title: string, url: string): void {
  try {
    if (typeof window !== 'undefined' && window.history?.pushState) {
      window.history.pushState(state, title, url);
    }
  } catch {
    // Ignore restricted history mutations
  }
}
