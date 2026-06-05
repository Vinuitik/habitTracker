const NAV_ITEMS = [
  { label: 'Today',         route: 'HOME' },
  { label: 'My Habits',     route: 'HABITS_LIST' },
  { label: 'Overview',      route: 'HABITS_TABLE' },
  { label: 'Rules',         route: 'HABITS_RULES' },
  { label: 'KPIs',          route: 'KPI_LIST' },
  { label: 'KPI Dashboard', route: 'KPI_DASHBOARD' },
];

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
