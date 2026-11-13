import { resolveBasePath } from '../config/appVersionContract';

export type AppRoute = '/' | '/login' | '/download' | '/dashboard' | '/admin' | '/employee';

export const APP_ROUTES: AppRoute[] = [
  '/',
  '/login',
  '/download',
  '/dashboard',
  '/admin',
  '/employee'
];

export function stripBasePath(pathname: string, basePath = resolveBasePath()): string {
  const normalizedBase = basePath === '/' ? '' : basePath.replace(/\/+$/, '');
  let clean = pathname || '/';
  if (normalizedBase && (clean === normalizedBase || clean.startsWith(`${normalizedBase}/`))) {
    clean = clean.slice(normalizedBase.length) || '/';
  }
  clean = clean.replace(/\/+$/, '') || '/';
  if (clean === '/index.html') return '/';
  return clean;
}

export function normalizeAppRoute(pathname: string): AppRoute {
  const stripped = stripBasePath(pathname).toLowerCase();
  switch (stripped) {
    case '/':
      return '/';
    case '/login':
    case '/auth':
    case '/signin':
      return '/login';
    case '/download':
    case '/apk':
    case '/app':
      return '/download';
    case '/dashboard':
    case '/portal':
      return '/dashboard';
    case '/admin':
    case '/admin/users':
    case '/users':
      return '/admin';
    case '/employee':
    case '/staff':
      return '/employee';
    default:
      return '/';
  }
}

export function buildBrowserPath(route: AppRoute): string {
  const base = resolveBasePath();
  if (base === '/') {
    return route;
  }
  const cleanBase = base.replace(/\/+$/, '');
  return route === '/' ? `${cleanBase}/` : `${cleanBase}${route}`;
}

/**
 * Restores SPA clean route if redirected from GitHub Pages 404.html (?p=/download)
 */
export function resolveInitialRouteFromLocation(): AppRoute {
  if (typeof window === 'undefined') return '/';

  const params = new URLSearchParams(window.location.search);
  const ghRedirectPath = params.get('p');
  if (ghRedirectPath) {
    const restoredRoute = normalizeAppRoute(ghRedirectPath);
    const cleanUrl = buildBrowserPath(restoredRoute) + (window.location.hash || '');
    window.history.replaceState(null, '', cleanUrl);
    return restoredRoute;
  }

  return normalizeAppRoute(window.location.pathname);
}
