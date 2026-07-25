const NAV_ITEMS = [
  { label: 'Today',         route: 'HOME' },
  { label: 'My Habits',     route: 'HABITS_LIST' },
  { label: 'Overview',      route: 'HABITS_TABLE' },
  { label: 'Rules',         route: 'HABITS_RULES' },
  { label: 'KPIs',          route: 'KPI_LIST' },
  { label: 'KPI Dashboard', route: 'KPI_DASHBOARD' },
  { label: 'Connect Drive', route: 'CONNECT_DRIVE' },
  { label: 'Install App',   route: 'INSTALL' },
];

// Auth gate used by every page's init(), in place of a bare `fetch(AUTH_ME)`. The distinction
// that matters: a real 401/403 means the server was reached and said "you're not logged in" —
// that's a genuine redirect-to-login. A thrown fetch (offline, server unreachable) is NOT the
// same thing — the session cookie is still sitting in the browser regardless of connectivity,
// so we assume it's still valid and let the page render from whatever the service worker has
// cached. Without this, every page's init() died on the auth check the instant the server was
// unreachable, before ever rendering the cached data that was sitting there the whole time.
async function checkAuth() {
  try {
    const res = await fetch(ENV.ENDPOINTS.AUTH_ME, { credentials: 'include' });
    if (res.ok) return true;
    window.location.href = ENV.ROUTES.LOGIN;
    return false;
  } catch (e) {
    console.warn('[auth] /auth/me unreachable — assuming still logged in, rendering offline');
    return true;
  }
}

/* Call initTopbar(activeRoute) after DOM is ready.
   activeRoute: one of the ENV.ROUTES values, e.g. ENV.ROUTES.HABITS_LIST */
function initTopbar(activeRoute) {
  const csrf = (() => {
    const m = document.cookie.split('; ').find(r => r.startsWith('XSRF-TOKEN='));
    return m ? decodeURIComponent(m.split('=')[1]) : '';
  })();

  const csrfInput = document.getElementById('logout-csrf');
  if (csrfInput) csrfInput.value = csrf;

  if (!window.ENV) return;

  // Render nav links from ENV.ROUTES — single source of truth
  const nav = document.querySelector('.topbar__nav');
  if (nav) {
    nav.innerHTML = NAV_ITEMS.map(({ label, route }) => {
      const href = ENV.ROUTES[route];
      const active = href === activeRoute ? ' active' : '';
      return `<a href="${href}" class="topbar__link${active}">${label}</a>`;
    }).join('');
  }

  // Keep brand link in sync
  const brand = document.querySelector('.topbar__brand');
  if (brand) brand.setAttribute('href', ENV.ROUTES.HOME);
}
