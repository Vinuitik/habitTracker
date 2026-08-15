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
// that matters: ONLY a real 401/403 means the server was reached and said "you're not logged in" —
// that's a genuine redirect-to-login. Everything else is "we couldn't tell", so KEEP the session.
//
// Why the >=400-that-isn't-401 branch exists (this is the whole "server down breaks the app" bug):
// when the origin is down behind the Cloudflare tunnel it ANSWERS with an HTTP 530 — the fetch
// RESOLVES with a non-ok Response, it does NOT throw. The old code did `if (res.ok) return true;
// else redirect-to-login`, so a 530 sent the user to /login — which the service worker
// deliberately never serves from cache (auth-handshake path) — landing them on a raw blank error
// page. navigator.onLine is true the whole time (Wi-Fi is fine, only the origin is dead), so
// nothing else caught it either. Treat 5xx/530 exactly like a thrown fetch: keep the session and
// let the page render from whatever the service worker cached.
async function checkAuth() {
  try {
    const res = await fetch(ENV.ENDPOINTS.AUTH_ME, { credentials: 'include' });
    if (res.ok) return true;
    if (res.status === 401 || res.status === 403) {
      window.location.href = ENV.ROUTES.LOGIN;
      return false;
    }
    // 5xx / 530 (tunnel up, origin down) / any other status = "couldn't tell" → keep session.
    console.warn('[auth] /auth/me returned', res.status, '— treating as unreachable, rendering offline');
    return true;
  } catch (e) {
    console.warn('[auth] /auth/me unreachable — assuming still logged in, rendering offline');
    return true;
  }
}

// Persistent "new version available" control, injected into every page's topbar so there's
// always somewhere to look/click — no more relying on catching a one-shot toast. registerSW.js
// calls TopbarUpdate.markAvailable() the moment it detects a new worker has taken over; if that
// fires before initTopbar() has run yet (race — SW check happens on script load, topbar renders
// inside each page's own init()), the pending flag below makes the button render already-lit.
let updateAvailablePending = false;
let updateButtonEl = null;

function applyUpdateButtonState() {
  if (!updateButtonEl) return;
  updateButtonEl.hidden = !updateAvailablePending;
}

window.TopbarUpdate = {
  markAvailable() {
    updateAvailablePending = true;
    applyUpdateButtonState();
  },
};

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

  // Inject the update button once, right before the sign-out form.
  if (!document.querySelector('.topbar__update')) {
    const inner = document.querySelector('.topbar__inner');
    const signoutForm = document.getElementById('logout-form');
    if (inner) {
      updateButtonEl = document.createElement('button');
      updateButtonEl.type = 'button';
      updateButtonEl.className = 'topbar__update';
      updateButtonEl.hidden = true;
      updateButtonEl.title = 'A new version is ready — click to reload';
      updateButtonEl.innerHTML = '<span class="topbar__update-dot"></span>Update';
      updateButtonEl.addEventListener('click', () => window.location.reload());
      inner.insertBefore(updateButtonEl, signoutForm || null);
      applyUpdateButtonState();
    }
  }
}
