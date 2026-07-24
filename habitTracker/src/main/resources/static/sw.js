// Hand-rolled service worker (no build step/bundler in this app, so no Workbox injectManifest).
// Bump VERSION whenever SHELL_URLS or the routing logic below changes, so the new SW installs.
const VERSION = 'v1';
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
async function handleApiGet(request) {
  const cache = await caches.open(API_CACHE);
  const cached = await cache.match(request);
  const network = fetch(request).then((response) => {
    if (response && response.ok) cache.put(request, response.clone());
    return response;
  }).catch(() => null);

  return cached || (await network) || new Response(JSON.stringify({ offline: true }), {
    status: 503,
    headers: { 'Content-Type': 'application/json' },
  });
}

self.addEventListener('fetch', (event) => {
  const { request } = event;
  if (request.mode === 'navigate') {
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
