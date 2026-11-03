// #/d/<dev> sessions on one machine: project select + New session, session rows with state badge.
// RelaySessions.mount(el, {dev, refresh}) → unmount (contract: js/relay/router.js).
window.RelaySessions = (() => {
  const { $, esc, timeAgo } = Dom;
  const R = Relay;
  const EP = ENV.ENDPOINTS;
  const PROVIDER = 'claude';

  const sessionRow = (dev, s, names) => `
    <a class="relay-row relay-row__main${s.state === 'finished' ? ' relay-row--done' : ''}" href="#/d/${R.enc(dev)}/s/${R.enc(s.id)}">
      <span class="relay-row__body">
        <span class="relay-row__name">${esc(s.title || 'New session')}</span>
        <span class="relay-row__meta">${esc(names.get(s.projectId) || s.projectId)} · ${esc(timeAgo(s.lastActiveAt || s.createdAt))}${s.preview ? ' · ' + esc(s.preview) : ''}</span>
      </span>
      ${R.stateBadge(s.state)}
    </a>`;

  async function load(el, dev, alive) {
    if (!R.deviceNames.has(dev)) {
      try { (await R.call(EP.RELAY_DEVICES)).forEach((d) => R.deviceNames.set(d.id, d.name)); } catch (_) { /* header falls back */ }
      if (!alive()) return;
    }
    el.innerHTML = R.header(esc(R.deviceNames.get(dev) || 'Device'), ['#/', 'Devices'],
      '<button class="btn btn--ghost" data-act="refresh">Refresh</button>') + `
      <form class="relay-new" id="new-session">
        <select class="input" id="new-project" aria-label="Project"><option value="">Loading projects…</option></select>
        <button class="btn btn--primary" type="submit" id="new-submit" disabled>New session</button>
      </form>
      <div class="relay-list" id="session-list"><div class="relay-empty">Loading…</div></div>`;
    $('new-session').addEventListener('submit', (e) => { e.preventDefault(); newSession(dev); });

    const [projects, sessions] = await Promise.allSettled([
      R.call(EP.RELAY_V1(dev, '/projects')), R.call(EP.RELAY_V1(dev, '/sessions'))]);
    if (!alive()) return;
    const names = new Map();
    if (projects.status === 'fulfilled') {
      projects.value.forEach((p) => names.set(p.id, p.name));
      $('new-project').innerHTML = projects.value.length
        ? projects.value.map((p) => `<option value="${esc(p.id)}">${esc(p.name)}</option>`).join('')
        : '<option value="">No projects on this machine</option>';
      $('new-submit').disabled = !projects.value.length;
    } else {
      $('new-project').innerHTML = `<option value="">${esc(projects.reason.message)}</option>`;
    }
    $('session-list').innerHTML = sessions.status === 'rejected' ? R.errorHtml(sessions.reason)
      : sessions.value.length ? sessions.value.map((s) => sessionRow(dev, s, names)).join('')
        : '<div class="relay-empty">No sessions yet.</div>';
  }

  async function newSession(dev) {
    const pid = $('new-project').value;
    if (!pid) return;
    $('new-submit').disabled = true;
    try {
      const s = await R.call(EP.RELAY_V1(dev, `/projects/${R.enc(pid)}/sessions`), 'POST', { provider: PROVIDER });
      location.hash = `#/d/${R.enc(dev)}/s/${R.enc(s.id)}`;
    } catch (e) {
      Toast.show(e.message, 4000);
      $('new-submit').disabled = false;
    }
  }

  function mount(el, ctx) {
    let mounted = true;
    const off = R.onAct(el, { refresh: () => ctx.refresh() });
    load(el, ctx.dev, () => mounted);
    return () => { mounted = false; off(); };
  }

  return { mount };
})();
