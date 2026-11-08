// #/ devices: list (online dot, last seen), Add device (→ #/install), Revoke,
// trusted-browsers footer. RelayDevices.mount(el, ctx) → unmount (contract: js/relay/router.js).
window.RelayDevices = (() => {
  const { $, esc, timeAgo } = Dom;
  const R = Relay;
  const EP = ENV.ENDPOINTS;

  const deviceRow = (d) => `
    <div class="relay-row">
      <a class="relay-row__main" href="#/d/${R.enc(d.id)}">
        <span class="relay-dot${d.online ? ' relay-dot--on' : ''}" title="${d.online ? 'online' : 'offline'}"></span>
        <span class="relay-row__body">
          <span class="relay-row__name">${esc(d.name || 'Unnamed device')}</span>
          <span class="relay-row__meta">${d.online ? 'online' : 'offline · last seen ' + esc(timeAgo(d.lastSeenAt))}</span>
        </span>
      </a>
      <button class="btn btn--ghost btn--sm" data-act="revoke" data-id="${esc(d.id)}" data-name="${esc(d.name || '')}">Revoke</button>
    </div>`;

  async function loadDevices(alive) {
    try {
      const list = await R.call(EP.RELAY_DEVICES);
      if (!alive()) return;
      list.forEach((d) => R.deviceNames.set(d.id, d.name));
      $('device-list').innerHTML = list.length ? list.map(deviceRow).join('')
        : `<div class="relay-empty">No machines yet. A runner lets Relay start agent sessions on your computer.
            <a class="btn btn--primary relay-empty__cta" href="#/install">Install your first runner</a>
            <span class="relay-hint">Takes about a minute. No terminal on Windows.</span></div>`;
    } catch (e) {
      if (alive()) $('device-list').innerHTML = R.errorHtml(e);
    }
  }

  // Footer: browsers that skip the 2FA code ("Trust this device", TrustedDeviceService). Hidden when none / feature off.
  async function showTrusted(alive) {
    try {
      const { days, count } = await R.call(EP.RELAY_TRUSTED);
      if (!alive() || !days || !count) return;
      $('trust-box').innerHTML = `<div class="relay-foot">
        <span class="relay-hint">${count} trusted ${count === 1 ? 'browser skips' : 'browsers skip'} the two-factor code (up to ${days} days).</span>
        <button class="btn btn--ghost btn--sm" data-act="forget-trusted">Forget trusted devices</button></div>`;
    } catch (_) { /* footer is optional */ }
  }

  async function forgetTrusted() {
    if (!confirm('Forget all trusted devices? Every browser, this one included, will ask for a code next time.')) return;
    try {
      await R.call(EP.RELAY_TRUSTED, 'DELETE');
      $('trust-box').innerHTML = '';
      Toast.show('Trusted devices forgotten', 3000);
    } catch (e) {
      Toast.show(e.message, 4000);
    }
  }

  // One onboarding flow: Add device = the install page (download / one-liner, then wait for the machine).
  const addDevice = () => { location.hash = '#/install'; };

  async function revoke(id, name, refresh) {
    if (!confirm(`Revoke "${name || id}"? It disconnects now and has to be paired again.`)) return;
    try {
      await R.call(EP.RELAY_DEVICE(id), 'DELETE');
      refresh();
    } catch (e) {
      Toast.show(e.message, 4000);
    }
  }

  function mount(el, ctx) {
    let mounted = true;
    const alive = () => mounted;
    el.innerHTML = R.header('Relay', null,
      '<button class="btn btn--ghost" data-act="refresh">Refresh</button><button class="btn btn--primary" data-act="add">Add device</button>')
      + '<div class="relay-list" id="device-list"><div class="relay-empty">Loading…</div></div>'
      + '<div id="trust-box"></div>';
    const off = R.onAct(el, {
      refresh: () => ctx.refresh(),
      add: addDevice,
      revoke: (b) => revoke(b.dataset.id, b.dataset.name, ctx.refresh),
      'forget-trusted': forgetTrusted,
    });
    showTrusted(alive);
    loadDevices(alive);
    return () => { mounted = false; off(); };
  }

  return { mount };
})();
