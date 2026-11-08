// "KPI credentials" section on the runner home (#/d/<dev>, mounted by RelayShell.mountEmpty).
// API credentials that KPI collectors' http sources use (docs/designs/runner-only-kpis.md, Relay shared/HUB.md
// "KPI credentials"). They live ONLY on this runner: the browser PUTs the secret through the hub proxy
// (ENV.ENDPOINTS.RELAY_CREDENTIALS / RELAY_CREDENTIAL → /relay/api/devices/<dev>/v1/credentials[/<name>]), the hub
// never stores or logs it, and GET returns {name, host, auth, updatedAt} only — values are never rendered.
// RelayCredentials.mount(el, {dev}) → unmount (contract: js/relay/router.js).
//   list → Add / Replace (same name = replace) → Save → secret fields cleared → list reloaded
//   Remove → confirm → DELETE
// auth: bearer (Authorization: Bearer) | header (<headerName>: secret) | query (one or more ?param=value, e.g.
// Trello key + token; always sent as secret {param: value}).
window.RelayCredentials = (() => {
  const { $, esc, timeAgo } = Dom;
  const R = Relay;
  const EP = ENV.ENDPOINTS;
  const NAME = /^[a-z0-9_-]{1,32}$/;
  const HOST = /^(?=.{1,253}$)([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?\.)+[a-z]{2,63}$/;
  const AUTH_LABEL = { bearer: 'Bearer token', header: 'Custom header', query: 'Query parameters' };

  const row = (c) => `
    <div class="relay-row">
      <span class="relay-row__body">
        <span class="relay-row__name">${esc(c.name)}</span>
        <span class="relay-row__meta">${esc(c.host)} · ${esc(AUTH_LABEL[c.auth] || c.auth || '?')}${
          c.updatedAt ? ' · updated ' + esc(timeAgo(c.updatedAt)) : ''}</span>
      </span>
      <button class="btn btn--ghost btn--sm" data-act="cred-replace" data-name="${esc(c.name)}" data-host="${esc(c.host)}"
        data-auth="${esc(c.auth || 'bearer')}">Replace</button>
      <button class="btn btn--ghost btn--sm" data-act="cred-remove" data-name="${esc(c.name)}">Remove</button>
    </div>`;

  const pairRow = () => `
    <div class="cred-form__pair">
      <input class="input input--sm" data-f="param" placeholder="parameter, e.g. key" autocomplete="off" spellcheck="false">
      <input class="input input--sm" data-f="value" type="password" placeholder="value" autocomplete="new-password">
      <button type="button" class="btn btn--ghost btn--sm" data-act="cred-pair-remove" aria-label="Remove parameter">✕</button>
    </div>`;

  const formHtml = () => `
    <form class="cred-form" id="cred-form" autocomplete="off" novalidate>
      <div class="cred-form__grid">
        <label class="form-group"><span class="form-group__label">Name</span>
          <input class="input" id="cred-name" placeholder="github" maxlength="32" spellcheck="false">
          <span class="form-group__hint">Lowercase letters, digits, - and _. The AI refers to it by this name.</span></label>
        <label class="form-group"><span class="form-group__label">API host</span>
          <input class="input" id="cred-host" placeholder="api.github.com" maxlength="253" spellcheck="false">
          <span class="form-group__hint">The credential is only ever sent to this host.</span></label>
        <label class="form-group"><span class="form-group__label">How it is sent</span>
          <select class="input" id="cred-auth">
            <option value="bearer">Bearer token (Authorization header)</option>
            <option value="header">Custom header</option>
            <option value="query">Query parameters (e.g. key + token)</option>
          </select></label>
        <label class="form-group" id="cred-header-group" hidden><span class="form-group__label">Header name</span>
          <input class="input" id="cred-header" placeholder="X-Api-Key" maxlength="64" spellcheck="false"></label>
      </div>
      <label class="form-group" id="cred-secret-group"><span class="form-group__label">Secret</span>
        <input class="input" id="cred-secret" type="password" autocomplete="new-password"></label>
      <div class="form-group" id="cred-query-group" hidden><span class="form-group__label">Parameters</span>
        <div class="cred-form__pairs" id="cred-pairs"></div>
        <button type="button" class="btn btn--ghost btn--sm cred-form__add" data-act="cred-pair-add">+ Add parameter</button></div>
      <p class="relay-hint">Use read-only, minimal-scope tokens: a collector can read anything this token can on its host.
        The secret goes straight to this runner and is never shown again.</p>
      <div class="cred-form__error" id="cred-error" hidden></div>
      <div class="cred-form__actions">
        <button type="button" class="btn btn--ghost" data-act="cred-cancel">Cancel</button>
        <button type="submit" class="btn btn--primary" id="cred-save">Save to runner</button>
      </div>
    </form>`;

  function mount(el, { dev }) {
    let live = true;
    el.innerHTML = `
      <div class="relay-section-label cred__head">KPI credentials
        <button class="btn btn--ghost btn--sm" data-act="cred-add">Add credential</button></div>
      <p class="relay-hint cred__intro">API tokens your automated KPIs may use. They are stored on this runner only;
        the runner makes the call, the collector script never sees the token.</p>
      <div id="cred-form-box"></div>
      <div class="relay-list" id="cred-list"><div class="relay-empty">Loading…</div></div>`;

    async function load() {
      try {
        const list = await R.call(EP.RELAY_CREDENTIALS(dev));
        if (!live) return;
        $('cred-list').innerHTML = Array.isArray(list) && list.length ? list.map(row).join('')
          : '<div class="relay-empty">No credentials yet. Add one when an automated KPI needs a web API.</div>';
      } catch (e) {
        if (!live) return;
        if (e.status === 404) e.message = 'This runner is too old for KPI credentials: update it.';
        $('cred-list').innerHTML = R.errorHtml(e);
      }
    }

    function showAuth() {
      const auth = $('cred-auth').value;
      $('cred-header-group').hidden = auth !== 'header';
      $('cred-secret-group').hidden = auth === 'query';
      $('cred-query-group').hidden = auth !== 'query';
      if (auth === 'query' && !$('cred-pairs').children.length) $('cred-pairs').insertAdjacentHTML('beforeend', pairRow() + pairRow());
    }

    function openForm(pre) {
      $('cred-form-box').innerHTML = formHtml();
      const f = $('cred-form');
      if (pre) {
        $('cred-name').value = pre.name;
        $('cred-name').readOnly = true;
        $('cred-host').value = pre.host;
        $('cred-auth').value = AUTH_LABEL[pre.auth] ? pre.auth : 'bearer';
        $('cred-save').textContent = 'Replace on runner';
      }
      showAuth();
      $('cred-auth').addEventListener('change', showAuth);
      f.addEventListener('submit', (e) => { e.preventDefault(); save(); });
      (pre ? $('cred-host') : $('cred-name')).focus();
    }

    function closeForm() {
      $('cred-form-box').innerHTML = '';   // drops every secret input with it
    }

    function fail(msg) {
      const box = $('cred-error');
      box.textContent = msg;
      box.hidden = false;
    }

    function clearSecrets() {
      document.querySelectorAll('#cred-form input[type=password]').forEach((i) => { i.value = ''; });
    }

    // → {name, body} or a string problem. Nothing here ever echoes the secret.
    function read() {
      const name = $('cred-name').value.trim().toLowerCase();
      const host = $('cred-host').value.trim().toLowerCase().replace(/^https?:\/\//, '').replace(/\/.*$/, '');
      const auth = $('cred-auth').value;
      if (!NAME.test(name)) return 'Name: 1-32 lowercase letters, digits, - or _.';
      if (!HOST.test(host)) return 'API host: a bare hostname like api.github.com (no https://, port or path).';
      const body = { host, auth };
      if (auth === 'query') {
        const secret = {};
        for (const p of document.querySelectorAll('#cred-pairs .cred-form__pair')) {
          const k = p.querySelector('[data-f=param]').value.trim();
          const v = p.querySelector('[data-f=value]').value;
          if (!k && !v) continue;
          if (!k || !v) return 'Each query parameter needs both a name and a value.';
          secret[k] = v;
        }
        if (!Object.keys(secret).length) return 'Add at least one query parameter.';
        body.secret = secret;
      } else {
        const secret = $('cred-secret').value;
        if (!secret) return 'Paste the secret.';
        body.secret = secret;
        if (auth === 'header') {
          const h = $('cred-header').value.trim();
          if (!/^[A-Za-z0-9-]{1,64}$/.test(h)) return 'Header name: letters, digits and - only (e.g. X-Api-Key).';
          body.headerName = h;
        }
      }
      return { name, body };
    }

    async function save() {
      const r = read();
      if (typeof r === 'string') { fail(r); return; }
      const btn = $('cred-save');
      btn.disabled = true;
      try {
        await R.call(EP.RELAY_CREDENTIAL(dev, r.name), 'PUT', r.body);
        r.body = null;
        if (!live) return;
        clearSecrets();
        closeForm();
        Toast.show(`Credential "${r.name}" saved on this runner`, 3000);
        load();
      } catch (e) {
        r.body = null;
        if (!live) return;
        clearSecrets();   // never keep a secret around after a failed save either
        btn.disabled = false;
        fail(e.status === 404 ? 'This runner is too old for KPI credentials: update it.' : e.message);
      }
    }

    async function remove(name) {
      if (!confirm(`Remove credential "${name}" from this runner? KPIs that use it stop collecting until you add it again.`)) return;
      try {
        await R.call(EP.RELAY_CREDENTIAL(dev, name), 'DELETE');
        if (live) load();
      } catch (e) {
        Toast.show(e.message, 4000);
      }
    }

    const off = R.onAct(el, {
      'cred-add': () => openForm(null),
      'cred-replace': (b) => openForm({ name: b.dataset.name, host: b.dataset.host, auth: b.dataset.auth }),
      'cred-remove': (b) => remove(b.dataset.name),
      'cred-cancel': closeForm,
      'cred-pair-add': () => $('cred-pairs').insertAdjacentHTML('beforeend', pairRow()),
      'cred-pair-remove': (b) => b.closest('.cred-form__pair').remove(),
    });
    load();
    return () => { live = false; off(); };
  }

  return { mount };
})();
