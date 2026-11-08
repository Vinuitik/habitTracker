// "Stop automating" flow for a KPI (kpi-list card). Needs: env.js, atoms/api.js, styles/molecules/modal.css,
// styles/organisms/kpi-retire.css.
//   KpiRetire.open(kpiId, kpiName)
//   confirm "Stop automating <KPI>?" → POST KPI_RETIRE (capability RETIRED, collectors removed from every device,
//   data kept, KPI back to manual) → if its collectors needed programs: "Also uninstall ActivityWatch from Desktop,
//   Laptop?" [Uninstall] [Keep] → POST KPI_RETIRE_UNINSTALL → one plain line per computer (done / waiting for it to
//   come back online / still needed by <kpi> / was already there). Closing the result reloads the list.
window.KpiRetire = (() => {
  const EP = ENV.ENDPOINTS;
  const esc = (s) => String(s ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
  const names = (devs) => devs.map((d) => d.deviceName || 'a computer').join(', ');

  let overlay = null;
  let changed = false;

  function shell(title) {
    if (overlay) overlay.remove();
    overlay = document.createElement('div');
    overlay.className = 'modal-overlay open';
    overlay.innerHTML = `
      <div class="modal-box kpi-retire" role="dialog" aria-modal="true" aria-label="${esc(title)}">
        <div class="modal-box__header"><span class="modal-box__title">${esc(title)}</span>
          <button class="modal-box__close" data-act="close" aria-label="Close">&#x2715;</button></div>
        <div class="kpi-retire__body"></div>
      </div>`;
    overlay.addEventListener('click', (e) => {
      if (e.target === overlay || e.target.closest('[data-act="close"]')) close();
    });
    document.body.appendChild(overlay);
    return overlay.querySelector('.kpi-retire__body');
  }

  function close() {
    if (overlay) overlay.remove();
    overlay = null;
    if (changed) window.location.reload();
  }

  function fail(body, e) {
    body.innerHTML = `<p class="kpi-retire__error">${esc(e.message || 'Something went wrong.')}</p>
      <div class="modal-box__actions"><button class="btn btn--ghost" data-act="close">Close</button></div>`;
  }

  function open(kpiId, kpiName) {
    changed = false;
    const body = shell('Stop automating');
    body.innerHTML = `
      <p class="kpi-retire__text">Stop automating <strong>${esc(kpiName)}</strong>? Your data stays. The KPI goes back to
        manual entry and its collector is removed from your computers.</p>
      <div class="modal-box__actions">
        <button class="btn btn--danger" data-act="go">Stop automating</button>
        <button class="btn btn--ghost" data-act="close">Cancel</button></div>`;
    body.querySelector('[data-act="go"]').addEventListener('click', async (ev) => {
      ev.target.disabled = true;
      try {
        const r = await Api.raw(EP.KPI_RETIRE(kpiId), 'POST');
        changed = true;
        offer(body, kpiId, kpiName, r.data);
      } catch (e) {
        fail(body, e);
      }
    });
  }

  function offer(body, kpiId, kpiName, retired) {
    const pkgs = (retired && retired.packages) || [];
    if (!pkgs.length) {
      body.innerHTML = `<p class="kpi-retire__text">${esc(kpiName)} is manual again. Its collector is being removed from your computers.</p>
        <div class="modal-box__actions"><button class="btn btn--primary" data-act="close">Done</button></div>`;
      return;
    }
    body.innerHTML = `
      <p class="kpi-retire__text">${esc(kpiName)} is manual again. Its collector is being removed from your computers.</p>
      ${pkgs.map((p, i) => `
        <div class="kpi-retire__pkg">
          <p class="kpi-retire__text">Also uninstall <strong>${esc(p.name)}</strong> from ${esc(names(p.devices))}?</p>
          ${p.why ? `<p class="kpi-retire__hint">${esc(p.why)}</p>` : ''}
        </div>`).join('')}
      <p class="kpi-retire__hint">Relay only removes programs it installed itself, and only if no other KPI uses them.</p>
      <div class="modal-box__actions">
        <button class="btn btn--primary" data-act="uninstall">Uninstall</button>
        <button class="btn btn--ghost" data-act="close">Keep</button></div>`;
    body.querySelector('[data-act="uninstall"]').addEventListener('click', async (ev) => {
      ev.target.disabled = true;
      const packages = pkgs.map((p) => ({ manager: p.manager, id: p.id, deviceIds: p.devices.map((d) => d.deviceId) }));
      try {
        const r = await Api.raw(EP.KPI_RETIRE_UNINSTALL(kpiId), 'POST', { packages });
        results(body, (r.data && r.data.results) || []);
      } catch (e) {
        fail(body, e);
      }
    });
  }

  function results(body, list) {
    body.innerHTML = `
      <ul class="kpi-retire__results">${list.map((r) =>
        `<li class="kpi-retire__result kpi-retire__result--${esc(String(r.status).toLowerCase())}">${esc(r.message)}</li>`).join('')
        || '<li class="kpi-retire__result">Nothing to uninstall.</li>'}</ul>
      <div class="modal-box__actions"><button class="btn btn--primary" data-act="close">Done</button></div>`;
  }

  return { open };
})();
