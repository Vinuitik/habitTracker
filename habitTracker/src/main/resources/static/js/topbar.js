// Top-level nav: one entry per area. `href` is where the link goes; `match` lists every route that
// lights it up as active (an area owns several pages — see TAB_GROUPS).
// `icon` is the inner markup of a 24×24 stroke SVG, used by the phone tab bar.
const NAV_ITEMS = [
  { label: 'Today',    href: 'HOME',          match: ['HOME'],
    icon: '<circle cx="12" cy="12" r="9"/><polyline points="8,12.5 11,15.5 16.5,9.5"/>' },
  { label: 'Habits',   href: 'HABITS_LIST',   match: ['HABITS_LIST', 'HABITS_TABLE', 'HABITS_RULES', 'HABITS_ADD', 'HABIT_EDIT', 'HABIT_INFO'],
    icon: '<line x1="9" y1="6" x2="20" y2="6"/><line x1="9" y1="12" x2="20" y2="12"/><line x1="9" y1="18" x2="20" y2="18"/><circle cx="4.5" cy="6" r="1"/><circle cx="4.5" cy="12" r="1"/><circle cx="4.5" cy="18" r="1"/>' },
  { label: 'KPIs',     href: 'KPI_LIST',      match: ['KPI_LIST', 'KPI_DASHBOARD', 'KPI_CREATE'],
    icon: '<polyline points="3,17 9,11 13,15 21,7"/><polyline points="15,7 21,7 21,13"/>' },
  { label: 'Projects', href: 'PROJECTS',      match: ['PROJECTS'],
    icon: '<rect x="3" y="4" width="18" height="16" rx="2"/><line x1="9" y1="4" x2="9" y2="20"/><line x1="15" y1="4" x2="15" y2="20"/>' },
  { label: 'Settings', href: 'CONNECT_DRIVE', match: ['CONNECT_DRIVE', 'INSTALL'],
    icon: '<line x1="4" y1="7" x2="20" y2="7"/><line x1="4" y1="17" x2="20" y2="17"/><circle cx="9" cy="7" r="2.5" fill="var(--color-bg-surface)"/><circle cx="15" cy="17" r="2.5" fill="var(--color-bg-surface)"/>' },
];

// Matches a route value against ENV.ROUTES[key]; function routes (HABIT_EDIT(id)) match by prefix.
function routeMatches(key, route) {
  const r = ENV.ROUTES[key];
  return typeof r === 'function' ? route.startsWith(r('')) : r === route;
}

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
  const activeItem = NAV_ITEMS.find(i => i.match.some(r => routeMatches(r, activeRoute)));
  const nav = document.querySelector('.topbar__nav');
  if (nav) {
    nav.innerHTML = NAV_ITEMS.map(item => {
      const current = item === activeItem ? ' active" aria-current="page' : '';
      return `<a href="${ENV.ROUTES[item.href]}" class="topbar__link${current}">${item.label}</a>`;
    }).join('');
  }

  // Keep brand link in sync; on phones the brand gives way to the area title
  const brand = document.querySelector('.topbar__brand');
  if (brand) {
    brand.setAttribute('href', ENV.ROUTES.HOME);
    if (activeItem && !document.querySelector('.topbar__title')) {
      const title = document.createElement('span');
      title.className = 'topbar__title';
      title.textContent = activeItem.label;
      brand.after(title);
    }
  }

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

  renderTabbar(activeItem);
  initSectionTabs(activeItem, activeRoute);
}

// Phone navigation: fixed bottom tab bar (CSS hides it above the mobile breakpoint — see
// styles/organisms/tabbar.css). Same NAV_ITEMS as the desktop top nav.
function renderTabbar(activeItem) {
  if (document.querySelector('.tabbar')) return;
  const bar = document.createElement('nav');
  bar.className = 'tabbar';
  bar.setAttribute('aria-label', 'Main');
  bar.innerHTML = NAV_ITEMS.map(item => {
    const current = item === activeItem ? ' active" aria-current="page' : '';
    return `<a href="${ENV.ROUTES[item.href]}" class="tabbar__item${current}">` +
      `<svg viewBox="0 0 24 24" aria-hidden="true">${item.icon}</svg>${item.label}</a>`;
  }).join('');
  document.body.appendChild(bar);
}

// Prepends the area's section tabs (e.g. My Habits / Overview / Rules) to <main>.
function initSectionTabs(activeItem, activeRoute) {
  const tabs = activeItem && TAB_GROUPS[activeItem.label];
  // Only on the area's own top-level pages — a form like Add Habit gets no tab strip.
  if (tabs && !tabs.some(t => ENV.ROUTES[t.route] === activeRoute)) return;
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
