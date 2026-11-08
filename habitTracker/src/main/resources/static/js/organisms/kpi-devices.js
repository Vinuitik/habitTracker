// Per-device breakdown on a KPI dashboard card. Needs: env.js, atoms/api.js, styles/organisms/kpi-devices.css,
// styles/atoms/badge.css.
//   KpiDevices.update(card, kpiId, data)   (called by kpi-dashboard.js after each chart load)
//   data = the chart's KPIData points; the newest one's devicesReported / devicesExpected (null for a single-device or
//   manual KPI) → "2/3 devices" warning badge when a day is partial. The default view stays the combined total;
//   "Per device" expands the last ENV.LIMITS.KPI_DEVICE_DAYS days from GET KPI_DEVICE_READINGS, grouped by device
//   (latest value first). Nothing is shown for a KPI without per-device readings.
window.KpiDevices = (() => {
  const EP = ENV.ENDPOINTS;
  const esc = (s) => String(s ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
  const iso = (d) => `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
  const num = (v) => (v == null || !isFinite(v) ? '-' : Number(v.toFixed(2)).toLocaleString('en-US'));

  function slot(card) {
    let el = card.querySelector('.kpi-devices');
    if (!el) {
      el = document.createElement('div');
      el.className = 'kpi-devices';
      card.querySelector('.chart-footer').before(el);
    }
    return el;
  }

  function update(card, kpiId, data) {
    if (!card || !kpiId) return;
    const last = (data || []).length ? data[data.length - 1] : null;
    const expected = last && last.devicesExpected;
    if (!expected || expected < 2) {
      const old = card.querySelector('.kpi-devices');
      if (old) old.remove();
      return;
    }
    const reported = last.devicesReported == null ? 0 : last.devicesReported;
    const partial = reported < expected;
    const el = slot(card);
    el.innerHTML = `
      <span class="badge ${partial ? 'badge--warning' : 'badge--muted'}" title="${partial
        ? 'Some computers have not reported for this day yet, so the total may be low.' : 'Every computer reported.'}">${reported}/${expected} devices</span>
      <button class="btn btn--ghost btn--sm" data-act="toggle" aria-expanded="false">Per device</button>
      <div class="kpi-devices__list" hidden></div>`;
    const btn = el.querySelector('[data-act="toggle"]');
    const list = el.querySelector('.kpi-devices__list');
    btn.addEventListener('click', async () => {
      const open = list.hidden;
      list.hidden = !open;
      btn.setAttribute('aria-expanded', String(open));
      btn.textContent = open ? 'Hide per device' : 'Per device';
      if (open) await load(list, kpiId);
    });
  }

  async function load(list, kpiId) {
    list.textContent = 'Loading…';
    const to = new Date();
    const from = new Date(to.getTime() - ENV.LIMITS.KPI_DEVICE_DAYS * 86400000);
    try {
      const r = await Api.raw(EP.KPI_DEVICE_READINGS(kpiId, iso(from), iso(to)));
      render(list, r.data || []);
    } catch (e) {
      list.innerHTML = `<p class="kpi-devices__empty">${esc(e.message)}</p>`;
    }
  }

  function render(list, readings) {
    if (!readings.length) {
      list.innerHTML = '<p class="kpi-devices__empty">No per-device values in this period yet.</p>';
      return;
    }
    const byDevice = new Map();
    for (const r of readings) {
      if (!byDevice.has(r.deviceId)) byDevice.set(r.deviceId, { name: r.deviceName, unit: r.unit, days: [] });
      byDevice.get(r.deviceId).days.push(r);
    }
    list.innerHTML = [...byDevice.values()].map((d) => {
      d.days.sort((a, b) => String(b.date).localeCompare(String(a.date)));
      const unit = d.unit ? ' ' + esc(d.unit) : '';
      return `<details class="kpi-devices__device">
        <summary><span class="kpi-devices__name">${esc(d.name)}</span>
          <span class="kpi-devices__value">${num(d.days[0].value)}${unit} on ${esc(d.days[0].date)}</span></summary>
        <ul class="kpi-devices__days">${d.days.map((x) =>
          `<li><span>${esc(x.date)}</span><span>${num(x.value)}${unit}</span></li>`).join('')}</ul>
      </details>`;
    }).join('');
  }

  return { update };
})();
