// Per-KPI deployment status row on the KPI card: "Desktop ✓ · Laptop ⏳ waiting for it to come online · Mac ⚠ <error> [Retry]".
// KpiDeployments.mount(parentEl, kpiId) → fetches GET ENV.ENDPOINTS.KPI_DEPLOYMENTS(kpiId), renders one item per device
// (newest collector version), refreshes every ENV.TIMING.KPI_DEPLOY_POLL_MS while any device is not done, stops when the
// card leaves the page. Retry → POST KPI_DEPLOYMENT_RETRY. Styles: styles/organisms/kpi-deployments.css. Text via textContent.
window.KpiDeployments = (() => {
  const EP = ENV.ENDPOINTS;

  const el = (tag, cls, text) => {
    const e = document.createElement(tag);
    if (cls) e.className = cls;
    if (text != null) e.textContent = text;
    return e;
  };

  // Server error text can be raw runner output; only short, human-looking lines are shown as-is.
  const plainError = (s) => (!s || /^[[{<]/.test(s.trim()) || s.length > 160) ? 'Something went wrong on that computer.' : s.trim();

  // One row per device: its newest version that is not already removed.
  function latestPerDevice(rows) {
    const best = new Map();
    rows.filter((d) => d.status !== 'REMOVED').forEach((d) => {
      const cur = best.get(d.deviceId);
      if (!cur || (d.capabilityVersion || 0) >= (cur.capabilityVersion || 0)) best.set(d.deviceId, d);
    });
    return [...best.values()];
  }

  // → {mark, tone, text, retry?, hint?}
  function describe(d) {
    if (d.target === 'REMOVED') return { mark: '⏳', tone: 'wait', text: 'being removed', retry: d.status === 'FAILED' };
    switch (d.status) {
      case 'ACTIVE': return { mark: '✓', tone: 'ok', text: '' };
      case 'PENDING': return { mark: '⏳', tone: 'wait', text: d.online ? 'setting up' : 'waiting for it to come online' };
      case 'INSTALLING': return { mark: '⏳', tone: 'wait', text: 'installing' };
      case 'REMOVING': return { mark: '⏳', tone: 'wait', text: 'being removed' };
      case 'AWAITING_CONFIRM': return {
        mark: '⏳', tone: 'wait', text: 'waiting for your OK',
        hint: `Confirm the install on ${d.deviceName || 'that computer'} (open ${ENV.KPI_DEVICES.LOCAL_CONFIRM_URL} there)`,
      };
      case 'FAILED': return { mark: '⚠', tone: 'bad', text: plainError(d.lastError), retry: true };
      default: return { mark: '⏳', tone: 'wait', text: 'working on it' };
    }
  }

  // Returns true while any device still has something to wait for (drives the refresh timer).
  function render(host, kpiId, rows, reload) {
    host.replaceChildren();
    const devs = latestPerDevice(rows);
    host.hidden = !devs.length;
    devs.forEach((d, i) => {
      const info = describe(d);
      const item = el('span', `kpi-deploy__item kpi-deploy__item--${info.tone}`);
      item.append(el('span', 'kpi-deploy__name', d.deviceName || 'Unknown device'),
        el('span', 'kpi-deploy__mark', info.mark));
      if (info.text) item.append(el('span', 'kpi-deploy__text', info.text));
      if (info.retry) {
        const b = el('button', 'btn btn--ghost btn--sm kpi-deploy__retry', 'Retry');
        b.type = 'button';
        b.addEventListener('click', async () => {
          b.disabled = true;
          try {
            await fetch(EP.KPI_DEPLOYMENT_RETRY(kpiId, d.id), {
              method: 'POST', credentials: 'include', headers: { 'X-XSRF-TOKEN': getCsrfToken() },
            });
          } catch (e) { /* the refresh below shows the real state */ }
          reload();
        });
        item.append(b);
      }
      if (i) host.append(el('span', 'kpi-deploy__sep', '·'));
      host.append(item);
      if (info.hint) host.append(el('div', 'kpi-deploy__hint', info.hint));
    });
    return devs.some((d) => describe(d).tone !== 'ok');
  }

  function mount(parent, kpiId) {
    const host = el('div', 'kpi-deploy');
    host.hidden = true;
    parent.append(host);
    let timer = null;
    const load = async () => {
      clearTimeout(timer);
      if (!host.isConnected) return;
      let busy = false;
      try {
        const res = await fetch(EP.KPI_DEPLOYMENTS(kpiId), { credentials: 'include' });
        if (res.ok) busy = render(host, kpiId, await res.json(), load);
      } catch (e) { busy = true; }
      if (busy) timer = setTimeout(load, ENV.TIMING.KPI_DEPLOY_POLL_MS);
    };
    load();
  }

  return { mount, describe, latestPerDevice };
})();
