// Hand-rolled service worker (no build step/bundler in this app, so no Workbox injectManifest).
// Bump VERSION whenever SHELL_URLS or the routing logic below changes, so the new SW installs.
const VERSION = 'v4';
const SHELL_CACHE = `habittracker-shell-${VERSION}`;
const API_CACHE = `habittracker-api-${VERSION}`;

const SHELL_URLS = [
  '/js/env.js',
  '/js/topbar.js',
  '/styles/tokens.css',
  '/styles/reset.css',
  '/styles/atoms/button.css',
  '/styles/organisms/topbar.css',
  '/styles/pages/dashboard.css',
  '/index.html',
  '/habits-list.html',
  '/habit-table.html',
  '/kpi-list.html',
  '/kpi-dashboard.html',
  '/connect-drive.html',
  '/install.html',
  '/site.webmanifest',
];

// Same-origin API GETs worth serving stale while a fresh copy loads in the background —
// this is what makes "what's due today" / KPI values visible offline as last-known-state.
const API_PREFIXES = ['/api/today', '/api/habits', '/api/kpis'];

self.addEventListener('install', (event) => {
  event.waitUntil(
    caches.open(SHELL_CACHE).then((cache) =>
      Promise.all(SHELL_URLS.map((url) => cache.add(url).catch(() => {})))
    ).then(() => self.skipWaiting())
  );
});

self.addEventListener('activate', (event) => {
  event.waitUntil(
    caches.keys().then((keys) =>
      Promise.all(keys
        .filter((k) => k !== SHELL_CACHE && k !== API_CACHE)
        .map((k) => caches.delete(k)))
    ).then(() => self.clients.claim())
  );
});

function isApiGet(request) {
  if (request.method !== 'GET') return false;
  const url = new URL(request.url);
  return API_PREFIXES.some((p) => url.pathname === p || url.pathname.startsWith(p + '/'));
}

// Network-first with a short timeout; on failure/timeout/non-ok response, fall back to the
// cached shell page — so a sleeping server (or a Cloudflare 5xx for a down tunnel) doesn't
// leak a raw error page, it just opens the last-known UI instead.
async function handleNavigate(request) {
  try {
    const response = await Promise.race([
      fetch(request),
      new Promise((_, reject) => setTimeout(() => reject(new Error('timeout')), 4000)),
    ]);
    if (response && response.ok) return response;
    throw new Error('non-ok response');
  } catch (e) {
    const cache = await caches.open(SHELL_CACHE);
    const cached = await cache.match(request) || await cache.match(new URL(request.url).pathname)
      || await cache.match('/index.html');
    if (cached) return cached;
    throw e;
  }
}

// Stale-while-revalidate for the today/habits/kpi read endpoints.
// A down origin behind the tunnel answers with a real HTTP 530 (the fetch RESOLVES, it does not
// throw), and its body is a Cloudflare HTML error page — surfacing that to the caller would break
// its `.json()`. So only an OK network response is ever returned/cached; any non-ok status is
// treated as unreachable (→ null) so we fall back to the cached copy, or a friendly offline JSON
// the pages know how to read, instead of leaking the broken 530.
async function handleApiGet(request) {
  const cache = await caches.open(API_CACHE);
  const cached = await cache.match(request);
  const network = fetch(request).then((response) => {
    if (response && response.ok) {
      cache.put(request, response.clone());
      return response;
    }
    return null; // 5xx / 530 / any non-ok = unreachable → let cache or offline JSON win
  }).catch(() => null);

  return cached || (await network) || new Response(JSON.stringify({ offline: true }), {
    status: 503,
    headers: { 'Content-Type': 'application/json' },
  });
}

// Auth-handshake paths (form login, Google OAuth2 initiate + callback, logout) must never be
// answered with a substituted cached page. Two independent reasons: (1) the OAuth callback URL
// carries a single-use `code` — Google's token exchange only tolerates one exchange of it, so if
// the SW's fetch is abandoned (timeout race) but continues in the background, a page reload or
// retry would hit the same code twice and fail; (2) more importantly, a Set-Cookie header on a
// response the SW decided to discard in favor of a cached fallback is a Set-Cookie the browser
// never applies — the session that Google-login just established server-side silently never
// reaches the client, which is exactly "click Google login, land on a blank/logged-out page."
const AUTH_PATH_PREFIXES = ['/login', '/logout', '/oauth2', '/register'];

function isAuthPath(request) {
  return AUTH_PATH_PREFIXES.some((p) => new URL(request.url).pathname.startsWith(p));
}

self.addEventListener('fetch', (event) => {
  const { request } = event;
  // GET navigations only, and never on an auth-handshake path — those must always go straight
  // to the network untouched, same as if no service worker existed.
  if (request.mode === 'navigate' && request.method === 'GET' && !isAuthPath(request)) {
    event.respondWith(handleNavigate(request));
    return;
  }
  if (isApiGet(request)) {
    event.respondWith(handleApiGet(request));
    return;
  }
  if (request.method === 'GET' && SHELL_URLS.some((u) => request.url.endsWith(u))) {
    event.respondWith(
      caches.match(request).then((cached) => cached || fetch(request))
    );
  }
});
