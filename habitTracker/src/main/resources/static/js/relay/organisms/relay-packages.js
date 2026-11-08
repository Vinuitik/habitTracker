// "Installed by KPIs" section on the runner home (#/d/<dev>, mounted by RelayShell.mountEmpty).
// Programs Relay installed on this computer for automated KPIs (docs/designs/kpi-multidevice.md "Runner: requires +
// package ledger"). The hub joins the runner's ledger with KPI names: GET ENV.ENDPOINTS.RELAY_KPI_PACKAGES(dev).
// RelayPackages.mount(el, {dev}) → unmount (contract: js/relay/router.js).
//   list → row "ActivityWatch · winget · used by Coding hours" | "orphaned" → Uninstall (disabled + reason when a KPI
//   still uses it or Relay did not install it) → confirm → POST uninstall → list reloaded a few seconds later
window.RelayPackages = (() => {
  const { $, esc } = Dom;
  const R = Relay;
  const EP = ENV.ENDPOINTS;
  const MANAGER_LABEL = { relay: 'Relay', winget: 'winget', brew: 'Homebrew', flatpak: 'Flatpak', pip: 'pip' };
  const STATE_LABEL = { installing: 'installing…', failed: 'install failed', awaiting_confirm: 'waiting for your OK on that computer' };

  const usedBy = (p) => (p.usedByKpis && p.usedByKpis.length ? 'used by ' + p.usedByKpis.join(', ') : 'orphaned');

  const row = (p) => {
    const state = STATE_LABEL[p.state] ? ` · ${STATE_LABEL[p.state]}` : '';
    const note = p.canUninstall ? '' : `<span class="pkg-row__why">${esc(p.reason || '')}</span>`;
    return `
    <div class="relay-row pkg-row">
      <span class="relay-row__body">
        <span class="relay-row__name">${esc(p.name)}</span>
        <span class="relay-row__meta">${esc(MANAGER_LABEL[p.manager] || p.manager)} · ${esc(usedBy(p))}${esc(state)}</span>
        ${note}
      </span>
      ${confirming === key(p)
        ? `<span class="pkg-row__confirm">Uninstall ${esc(p.name)} from this computer?
             <button class="btn btn--danger btn--sm" data-act="pkg-yes">Uninstall</button>
             <button class="btn btn--ghost btn--sm" data-act="pkg-no">Keep</button></span>`
        : `<button class="btn btn--ghost btn--sm" data-act="pkg-uninstall" data-manager="${esc(p.manager)}"
             data-id="${esc(p.id)}"${p.canUninstall ? '' : ' disabled'}>Uninstall</button>`}
    </div>`;
  };

  const key = (p) => p.manager + '/' + p.id;
  let confirming = null;

  function mount(el, { dev }) {
    let live = true;
    let rows = [];
    let timer = null;
    confirming = null;
    el.innerHTML = `
      <div class="relay-section-label">Installed by KPIs</div>
      <p class="relay-hint pkg__intro">Programs Relay installed on this computer so an automated KPI can work.
        You can remove one once no KPI uses it. Programs that were already here are never removed.</p>
      <div class="relay-list" id="pkg-list"><div class="relay-empty">Loading…</div></div>`;

    function render() {
      $('pkg-list').innerHTML = rows.length ? rows.map(row).join('')
        : '<div class="relay-empty">Nothing installed by KPIs on this computer.</div>';
    }

    async function load() {
      try {
        const list = await R.call(EP.RELAY_KPI_PACKAGES(dev));
        if (!live) return;
        rows = Array.isArray(list) ? list : [];
        render();
      } catch (e) {
        if (!live) return;
        if (e.status === 404) e.message = 'This computer\'s runner is too old to list installed programs: update it.';
        $('pkg-list').innerHTML = R.errorHtml(e);
      }
    }

    async function uninstall() {
      const [manager, ...rest] = confirming.split('/');
      const id = rest.join('/');
      confirming = null;
      try {
        const r = await R.call(EP.RELAY_KPI_PACKAGE_UNINSTALL(dev), 'POST', { manager, id });
        Toast.show(r.message, 4500);
      } catch (e) {
        Toast.show(e.message, 4500);
      }
      if (!live) return;
      render();
      clearTimeout(timer);
      timer = setTimeout(load, ENV.TIMING.RELAY_INSTALL_POLL_MS);   // the runner removes it in the background
    }

    const off = R.onAct(el, {
      'pkg-uninstall': (b) => { confirming = b.dataset.manager + '/' + b.dataset.id; render(); },
      'pkg-no': () => { confirming = null; render(); },
      'pkg-yes': uninstall,
    });
    load();
    return () => { live = false; clearTimeout(timer); off(); };
  }

  return { mount };
})();
