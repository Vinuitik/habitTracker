// Top-level nav: one entry per area. `href` is where the link goes; `match` lists every route that
// lights it up as active (an area owns several pages — see TAB_GROUPS).
const NAV_ITEMS = [
  { label: 'Today',    href: 'HOME',          match: ['HOME'] },
  { label: 'Habits',   href: 'HABITS_LIST',   match: ['HABITS_LIST', 'HABITS_TABLE', 'HABITS_RULES', 'HABITS_ADD'] },
  { label: 'KPIs',     href: 'KPI_LIST',      match: ['KPI_LIST', 'KPI_DASHBOARD', 'KPI_CREATE'] },
  { label: 'Projects', href: 'PROJECTS',      match: ['PROJECTS'] },
  { label: 'Settings', href: 'CONNECT_DRIVE', match: ['CONNECT_DRIVE', 'INSTALL'] },
];

// Section tabs shown at the top of <main> on pages that belong to a multi-page area.
// Keyed by the NAV_ITEMS label that owns them.
const TAB_GROUPS = {
  Habits:   [{ label: 'My Habits', route: 'HABITS_LIST' }, { label: 'Overview', route: 'HABITS_TABLE' }, { label: 'Rules', route: 'HABITS_RULES' }],
  KPIs:     [{ label: 'List', route: 'KPI_LIST' }, { label: 'Dashboard', route: 'KPI_DASHBOARD' }],
  Settings: [{ label: 'Connect Drive', route: 'CONNECT_DRIVE' }, { label: 'Install App', route: 'INSTALL' }],
};

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
    if (res.ok) {
      // Session confirmed → have the SW cache any pages it couldn't fetch while logged out.
      if (navigator.serviceWorker && navigator.serviceWorker.controller) {
        navigator.serviceWorker.controller.postMessage('PRECACHE_PAGES');
      }
      return true;
    }
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

// Always-visible refresh control in every page's topbar (ObsidianOptimizer's RefreshButton pattern):
// idle it's a ↻ icon that checks for a new deploy and reloads; once a new worker is waiting it
// expands to a lit "Update" pill that hands control to it. registerSW.js
// calls TopbarUpdate.markAvailable() the moment it detects a new worker has taken over; if that
// fires before initTopbar() has run yet (race — SW check happens on script load, topbar renders
// inside each page's own init()), the pending flag below makes the button render already-lit.
let updateAvailablePending = false;
let updateButtonEl = null;

function applyUpdateButtonState() {
  if (!updateButtonEl) return;
  updateButtonEl.classList.toggle('topbar__update--ready', updateAvailablePending);
  updateButtonEl.innerHTML = updateAvailablePending
    ? '<span class="topbar__update-dot"></span>Update'
    : '<span class="topbar__update-icon" aria-hidden="true">↻</span>';
  updateButtonEl.title = updateAvailablePending ? 'A new version is ready — click to reload' : 'Check for updates and reload';
  updateButtonEl.setAttribute('aria-label', updateAvailablePending ? 'Update available, reload' : 'Refresh');
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
  const activeItem = NAV_ITEMS.find(i => i.match.some(r => ENV.ROUTES[r] === activeRoute));
  const nav = document.querySelector('.topbar__nav');
  if (nav) {
    nav.id = 'topbar-nav';
    nav.innerHTML = NAV_ITEMS.map(item => {
      const active = item === activeItem ? ' active' : '';
      return `<a href="${ENV.ROUTES[item.href]}" class="topbar__link${active}">${item.label}</a>`;
    }).join('') + '<button type="button" class="topbar__link topbar__link--signout">Sign out</button>';
    nav.querySelector('.topbar__link--signout').addEventListener('click', () => {
      const form = document.getElementById('logout-form');
      if (form) form.requestSubmit();
    });
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
      updateButtonEl.addEventListener('click', () => {
        if (updateAvailablePending && window.applyUpdate) window.applyUpdate();
        else if (window.checkForUpdateAndReload) window.checkForUpdateAndReload();
        else window.location.reload();
      });
      inner.insertBefore(updateButtonEl, signoutForm || null);
      applyUpdateButtonState();
    }
  }

  initBurger();
  initSectionTabs(activeItem, activeRoute);
}

// Burger toggle for narrow screens (CSS hides it above the mobile breakpoint). The nav itself is
// the dropdown panel: `.topbar--open` on the header shows it.
function initBurger() {
  const topbar = document.querySelector('.topbar');
  const inner = document.querySelector('.topbar__inner');
  if (!topbar || !inner || inner.querySelector('.topbar__burger')) return;

  const burger = document.createElement('button');
  burger.type = 'button';
  burger.className = 'topbar__burger';
  burger.setAttribute('aria-label', 'Menu');
  burger.setAttribute('aria-controls', 'topbar-nav');
  burger.setAttribute('aria-expanded', 'false');
  burger.innerHTML = '<span></span><span></span><span></span>';
  inner.insertBefore(burger, document.getElementById('logout-form') || null);

  const setOpen = (open) => {
    topbar.classList.toggle('topbar--open', open);
    burger.setAttribute('aria-expanded', String(open));
  };
  burger.addEventListener('click', () => setOpen(!topbar.classList.contains('topbar--open')));
  document.addEventListener('click', (e) => {
    if (!topbar.contains(e.target)) setOpen(false);
  });
  document.addEventListener('keydown', (e) => { if (e.key === 'Escape') setOpen(false); });
}

// Prepends the area's section tabs (e.g. My Habits / Overview / Rules) to <main>.
function initSectionTabs(activeItem, activeRoute) {
  const tabs = activeItem && TAB_GROUPS[activeItem.label];
  const main = document.querySelector('main');
  if (!tabs || !main || main.querySelector('.section-tabs')) return;

  const bar = document.createElement('nav');
  bar.className = 'section-tabs';
  bar.setAttribute('aria-label', `${activeItem.label} sections`);
  bar.innerHTML = tabs.map(({ label, route }) => {
    const active = ENV.ROUTES[route] === activeRoute ? ' active' : '';
    return `<a href="${ENV.ROUTES[route]}" class="section-tabs__tab${active}">${label}</a>`;
  }).join('');
  main.prepend(bar);
}
