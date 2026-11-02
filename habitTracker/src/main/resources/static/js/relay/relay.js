// Relay page: hash router over the hub API (relay/FLOWS.md "UI").
//   #/                       devices (pair / revoke)
//   #/d/<device>             sessions on that machine (+ new session)
//   #/d/<device>/s/<session> chat
//   /relay/claim/<id>        confirm a runner's QR pairing (path, not hash: it's the link inside the QR)
// Everything goes through /relay/api; runner calls are /relay/api/devices/<id>/v1/... (shared/API.md).
(() => {
  const { $, esc, timeAgo } = Dom;
  const POLL_ACTIVE_MS = 2000;   // busy / waiting
  const POLL_IDLE_MS = 10000;    // idle / error
  const PROVIDER = 'claude';
  // Hub baked into the runner (Relay repo: runner/internal/uplink.DefaultHub); any other origin needs --hub.
  const DEFAULT_HUB = 'https://habittrackerdima.me';
  const PAIR_CMD = '/opt/relay/bin/relay-runner pair';
  const pairCmd = (extra = '') => PAIR_CMD + (location.origin === DEFAULT_HUB ? '' : ` --hub ${location.origin}`) + extra;
  const STATES = {
    busy:     ['Working', ''],
    waiting:  ['Needs you', 'badge--warning'],
    idle:     ['Idle', 'badge--success'],
    finished: ['Finished', 'badge--muted'],
    error:    ['Error', 'badge--danger'],
  };

  const view = () => $('relay-view');
  const enc = encodeURIComponent;
  const v1 = (dev, path) => `/relay/api/devices/${enc(dev)}/v1${path}`;
  let seq = 0;          // bumped on every route; async work from an older route drops its result
  let chat = null;      // open chat: {dev, sid, state, sig, notice}
  let pollTimer = null;
  const deviceNames = new Map();

  // Api.raw + hub status mapping. 401 = 2FA expired (or logged out) → verify, then come back here.
  async function call(url, method, body) {
    try {
      return (await Api.raw(url, method, body)).data;
    } catch (e) {
      if (e.status === 401) {
        location.href = '/2fa/verify?next=' + enc(location.pathname + location.hash);
        e.message = 'Two-factor check expired — redirecting…';
      } else if (e.status === 503) e.message = 'Device offline (machine asleep?)';
      else if (e.status === 504) e.message = "Device didn't answer";
      else if (!e.status) e.message = 'Network error — HabitTracker unreachable';
      throw e;
    }
  }

  const errorHtml = (e) => `<div class="relay-empty relay-empty--error">${esc(e.message)}</div>`;
  const stateBadge = (s) => {
    const [label, cls] = STATES[s] || [s || '?', 'badge--muted'];
    return `<span class="badge ${cls}">${esc(label)}</span>`;
  };
  const header = (title, back, actions = '') => `
    ${back ? `<a class="relay-back" href="${back[0]}">← ${back[1]}</a>` : ''}
    <div class="relay-head">
      <h1 class="relay-head__title">${title}</h1>
      <div class="relay-head__actions">${actions}</div>
    </div>`;

  // Markdown-ish: ``` fences → <pre>, blank lines → paragraphs, `code`, **bold**. Escape first, always.
  const inline = (s) => esc(s)
    .replace(/`([^`\n]+)`/g, '<code>$1</code>')
    .replace(/\*\*([^*\n]+)\*\*/g, '<strong>$1</strong>')
    .replace(/\n/g, '<br>');
  const md = (text) => String(text ?? '').split(/^ {0,3}```.*$/m).map((part, i) => (i % 2
    ? `<pre class="relay-pre"><code>${esc(part.replace(/^\n|\n$/g, ''))}</code></pre>`
    : part.split(/\n\s*\n/).map((p) => p.trim()).filter(Boolean).map((p) => `<p>${inline(p)}</p>`).join(''))).join('');

  // ── router ────────────────────────────────────────────────────────────
  function route() {
    const my = ++seq;
    clearTimeout(pollTimer);
    chat = null;
    $('relay-page').classList.remove('relay-page--chat');
    window.scrollTo(0, 0);
    let m;
    if ((m = location.pathname.match(/^\/relay\/claim\/([^/]+)$/))) return showClaim(decodeURIComponent(m[1]), my);
    const h = location.hash.replace(/^#/, '');
    if ((m = h.match(/^\/d\/([^/]+)\/s\/([^/]+)$/))) return showChat(decodeURIComponent(m[1]), decodeURIComponent(m[2]));
    if ((m = h.match(/^\/d\/([^/]+)$/))) return showDevice(decodeURIComponent(m[1]), my);
    return showDevices(my);
  }

  // ── devices ───────────────────────────────────────────────────────────
  async function showDevices(my) {
    view().innerHTML = header('Relay', null,
      '<button class="btn btn--ghost" data-act="refresh">Refresh</button><button class="btn btn--primary" data-act="add">Add device</button>')
      + '<div id="pair-box"></div><div class="relay-list" id="device-list"><div class="relay-empty">Loading…</div></div>';
    try {
      const list = await call('/relay/api/devices');
      if (my !== seq) return;
      list.forEach((d) => deviceNames.set(d.id, d.name));
      $('device-list').innerHTML = list.length ? list.map(deviceRow).join('')
        : '<div class="relay-empty">No devices yet. Add one, then run the pair command on that machine.</div>';
    } catch (e) {
      if (my === seq) $('device-list').innerHTML = errorHtml(e);
    }
  }

  const deviceRow = (d) => `
    <div class="relay-row">
      <a class="relay-row__main" href="#/d/${enc(d.id)}">
        <span class="relay-dot${d.online ? ' relay-dot--on' : ''}" title="${d.online ? 'online' : 'offline'}"></span>
        <span class="relay-row__body">
          <span class="relay-row__name">${esc(d.name || 'Unnamed device')}</span>
          <span class="relay-row__meta">${d.online ? 'online' : 'offline · last seen ' + esc(timeAgo(d.lastSeenAt))}</span>
        </span>
      </a>
      <button class="btn btn--ghost btn--sm" data-act="revoke" data-id="${esc(d.id)}" data-name="${esc(d.name || '')}">Revoke</button>
    </div>`;

  // Phone first (runner shows a QR, this phone confirms); the code is the fallback for a desktop browser.
  function addDevice() {
    $('pair-box').innerHTML = `
      <div class="relay-card">
        <div class="relay-card__label">Recommended · with your phone</div>
        <div class="relay-cmd"><code>${esc(pairCmd())}</code></div>
        <p class="relay-hint">Run this on the machine (a fresh install runs it for you at the end). It shows a QR code:
          scan it with your phone camera and tap Confirm. The machine comes online by itself.</p>
      </div>
      <div class="relay-card">
        <div class="relay-card__label">Desktop alternative · pairing code</div>
        <div id="code-box"><button class="btn btn--ghost btn--sm" data-act="code">Get a code</button></div>
      </div>`;
  }

  async function getCode() {
    const box = $('code-box');
    box.innerHTML = 'Getting a code…';
    try {
      const { code, expiresInSeconds } = await call('/relay/api/devices/pairing-code', 'POST');
      box.innerHTML = `
        <div class="relay-card__label">Single use · ${Math.round(expiresInSeconds / 60)} min</div>
        <div class="relay-code">${esc(code)}</div>
        <div class="relay-card__label">Run on the machine</div>
        <div class="relay-cmd"><code id="pair-cmd">${esc(pairCmd(` --code ${code}`))}</code>
          <button class="btn btn--ghost btn--sm" data-act="copy">Copy</button></div>
        <p class="relay-hint">The device appears in the list once it connects (Refresh).</p>`;
    } catch (e) {
      box.innerHTML = errorHtml(e);
    }
  }

  // ── QR pairing confirm (/relay/claim/<id>) ────────────────────────────
  const claimGone = '<div class="relay-empty relay-empty--error">This pairing link expired or was already used. Run the pair command again for a new QR.</div>';

  async function showClaim(id, my) {
    view().innerHTML = header('Add device', ['/relay#/', 'Devices']) + '<div id="claim-box"><div class="relay-empty">Loading…</div></div>';
    try {
      const { name } = await call(`/relay/api/claims/${enc(id)}`);
      if (my !== seq) return;
      $('claim-box').innerHTML = `
        <div class="relay-card">
          <div class="relay-card__label">Connect this machine to your account?</div>
          <div class="relay-code">${esc(name)}</div>
          <p class="relay-hint">Only confirm if you just ran the pair command on that machine yourself.</p>
          <div class="relay-head__actions">
            <button class="btn btn--ghost" data-act="deny" data-id="${esc(id)}">Not mine</button>
            <button class="btn btn--primary" data-act="approve" data-id="${esc(id)}">Confirm</button>
          </div>
        </div>`;
    } catch (e) {
      if (my === seq) $('claim-box').innerHTML = e.status === 404 ? claimGone : errorHtml(e);
    }
  }

  async function claimAction(id, approve) {
    const box = $('claim-box');
    box.querySelectorAll('button').forEach((b) => { b.disabled = true; });
    try {
      await call(`/relay/api/claims/${enc(id)}/${approve ? 'approve' : 'deny'}`, 'POST');
      box.innerHTML = approve
        ? `<div class="relay-card"><div class="relay-card__label">Added</div>
             <p class="relay-hint">The machine finishes pairing and comes online in a few seconds.</p>
             <a class="btn btn--primary" href="/relay#/">Go to devices</a></div>`
        : '<div class="relay-empty">Declined. The pair command on that machine stops.</div>';
    } catch (e) {
      box.innerHTML = e.status === 404 ? claimGone : errorHtml(e);
    }
  }

  async function copyCommand(btn) {
    const el = $('pair-cmd');
    try {
      await navigator.clipboard.writeText(el.textContent);
      btn.textContent = 'Copied';
    } catch (_) { // no clipboard API (http, old browser) → select it for a manual copy
      const r = document.createRange();
      r.selectNodeContents(el);
      getSelection().removeAllRanges();
      getSelection().addRange(r);
      btn.textContent = 'Selected';
    }
    setTimeout(() => { btn.textContent = 'Copy'; }, 2000);
  }

  async function revoke(id, name) {
    if (!confirm(`Revoke "${name || id}"? It disconnects now and has to be paired again.`)) return;
    try {
      await call(`/relay/api/devices/${enc(id)}`, 'DELETE');
      route();
    } catch (e) {
      Toast.show(e.message, 4000);
    }
  }

  // ── sessions on one device ────────────────────────────────────────────
  async function showDevice(dev, my) {
    if (!deviceNames.has(dev)) {
      try { (await call('/relay/api/devices')).forEach((d) => deviceNames.set(d.id, d.name)); } catch (_) { /* header falls back */ }
      if (my !== seq) return;
    }
    view().innerHTML = header(esc(deviceNames.get(dev) || 'Device'), ['#/', 'Devices'],
      '<button class="btn btn--ghost" data-act="refresh">Refresh</button>') + `
      <form class="relay-new" id="new-session">
        <select class="input" id="new-project" aria-label="Project"><option value="">Loading projects…</option></select>
        <button class="btn btn--primary" type="submit" id="new-submit" disabled>New session</button>
      </form>
      <div class="relay-list" id="session-list"><div class="relay-empty">Loading…</div></div>`;
    $('new-session').addEventListener('submit', (e) => { e.preventDefault(); newSession(dev); });

    const [projects, sessions] = await Promise.allSettled([call(v1(dev, '/projects')), call(v1(dev, '/sessions'))]);
    if (my !== seq) return;
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
    $('session-list').innerHTML = sessions.status === 'rejected' ? errorHtml(sessions.reason)
      : sessions.value.length ? sessions.value.map((s) => sessionRow(dev, s, names)).join('')
        : '<div class="relay-empty">No sessions yet.</div>';
  }

  const sessionRow = (dev, s, names) => `
    <a class="relay-row relay-row__main${s.state === 'finished' ? ' relay-row--done' : ''}" href="#/d/${enc(dev)}/s/${enc(s.id)}">
      <span class="relay-row__body">
        <span class="relay-row__name">${esc(s.title || 'New session')}</span>
        <span class="relay-row__meta">${esc(names.get(s.projectId) || s.projectId)} · ${esc(timeAgo(s.lastActiveAt || s.createdAt))}${s.preview ? ' · ' + esc(s.preview) : ''}</span>
      </span>
      ${stateBadge(s.state)}
    </a>`;

  async function newSession(dev) {
    const pid = $('new-project').value;
    if (!pid) return;
    $('new-submit').disabled = true;
    try {
      const s = await call(v1(dev, `/projects/${enc(pid)}/sessions`), 'POST', { provider: PROVIDER });
      location.hash = `#/d/${enc(dev)}/s/${enc(s.id)}`;
    } catch (e) {
      Toast.show(e.message, 4000);
      $('new-submit').disabled = false;
    }
  }

  // ── chat ──────────────────────────────────────────────────────────────
  function showChat(dev, sid) {
    $('relay-page').classList.add('relay-page--chat');
    view().innerHTML = `
      <div class="relay-chat">
        <div class="relay-chat__head">
          <a class="relay-back relay-back--inline" href="#/d/${enc(dev)}">←</a>
          <span class="relay-chat__title" id="chat-title">Loading…</span>
          <span id="chat-state"></span>
          <button class="btn btn--ghost btn--sm" data-act="cancel" id="chat-cancel" title="Stop the current turn, keep the session" hidden>Cancel</button>
          <button class="btn btn--danger btn--sm" data-act="stop" id="chat-stop" title="End the session (kills the agent process)" hidden>Stop</button>
        </div>
        <div class="relay-msgs" id="chat-msgs"></div>
        <div class="relay-perm" id="chat-perm" hidden></div>
        <div class="relay-notice" id="chat-notice" hidden></div>
        <form class="relay-composer" id="chat-form">
          <textarea class="input" id="chat-input" rows="1" placeholder="Message…" aria-label="Message"></textarea>
          <button class="btn btn--primary" type="submit" id="chat-send">Send</button>
        </form>
      </div>`;
    chat = { dev, sid, state: '', sig: null, notice: '' };
    const input = $('chat-input');
    input.addEventListener('keydown', (e) => {
      if (e.key === 'Enter' && !e.shiftKey && !e.isComposing) { e.preventDefault(); $('chat-form').requestSubmit(); }
    });
    input.addEventListener('input', () => { input.style.height = 'auto'; input.style.height = Math.min(input.scrollHeight, 160) + 'px'; });
    $('chat-form').addEventListener('submit', (e) => { e.preventDefault(); send(); });
    refresh();
  }

  const sessionUrl = (c, tail = '') => v1(c.dev, `/sessions/${enc(c.sid)}${tail}`);

  // kind: 'busy' (409, clears once the turn ends) | 'error' (clears on the next good poll)
  function notice(text, kind) {
    if (!chat) return;
    chat.notice = text ? kind : '';
    const el = $('chat-notice');
    el.hidden = !text;
    el.textContent = text || '';
    el.className = 'relay-notice' + (kind === 'error' ? ' relay-notice--error' : '');
  }

  async function refresh() {
    const c = chat;
    if (!c) return;
    clearTimeout(pollTimer);
    try {
      const s = await call(sessionUrl(c));
      if (chat !== c) return;
      render(s);
      if (c.notice === 'error' || (c.notice === 'busy' && s.state !== 'busy')) notice('');
    } catch (e) {
      if (chat !== c) return;
      notice(e.message, 'error');
    }
    schedule();
  }

  // Poll while the tab is visible: 2s while working/waiting, 10s idle, never once finished.
  function schedule() {
    clearTimeout(pollTimer);
    if (!chat || document.hidden || chat.state === 'finished') return;
    pollTimer = setTimeout(refresh, chat.state === 'busy' || chat.state === 'waiting' ? POLL_ACTIVE_MS : POLL_IDLE_MS);
  }

  function render(s) {
    const c = chat;
    c.state = s.state;
    $('chat-title').textContent = s.title || 'New session';
    $('chat-state').innerHTML = stateBadge(s.state);
    const working = s.state === 'busy' || s.state === 'waiting';
    $('chat-cancel').hidden = !working;
    $('chat-stop').hidden = s.state === 'finished';
    $('chat-input').disabled = $('chat-send').disabled = s.state === 'finished';
    if (s.state === 'finished') $('chat-input').placeholder = 'Session finished';

    const p = s.state === 'waiting' && s.pendingPermission;
    const box = $('chat-perm');
    const key = p ? JSON.stringify(p) : '';
    if (box.dataset.key !== key) {
      box.dataset.key = key;
      box.hidden = !p;
      box.innerHTML = p ? `
        <div class="relay-perm__title">Allow <strong>${esc(p.title)}</strong>?</div>
        <div class="relay-perm__actions">${(p.options || []).map((o) => `
          <button class="btn btn--sm ${String(o.kind).startsWith('allow') ? 'btn--primary' : 'btn--ghost'}"
            data-act="perm" data-option="${esc(o.optionId)}">${esc(o.name)}</button>`).join('')}</div>` : '';
    }

    const msgs = s.messages || [];
    const sig = JSON.stringify(msgs);
    if (sig === c.sig) return;
    const el = $('chat-msgs');
    const atBottom = c.sig === null || el.scrollHeight - el.scrollTop - el.clientHeight < 80;
    c.sig = sig;
    el.innerHTML = msgs.length ? msgs.map(messageHtml).join('') : '<div class="relay-empty">Say something to start.</div>';
    if (atBottom) el.scrollTop = el.scrollHeight;
  }

  function messageHtml(m) {
    if (m.role === 'tool') {
      return `<div class="relay-tool relay-tool--${esc(m.status || 'completed')}">
        <span class="relay-tool__kind">${esc(m.toolKind || 'tool')}</span><span>${esc(m.text)}</span></div>`;
    }
    const cls = m.role === 'user' ? 'relay-msg--user' : 'relay-msg--agent' + (m.kind ? ' relay-msg--alert' : '');
    return `<div class="relay-msg ${cls}">${md(m.text)}</div>`;
  }

  // POST then show the returned Session (or poll) — shared by send / permission / cancel / stop.
  async function act(tail, body, onError) {
    const c = chat;
    try {
      const res = await call(sessionUrl(c, tail), 'POST', body);
      if (chat !== c) return;
      if (res && res.messages) render(res);
      return true;
    } catch (e) {
      if (chat === c) (onError || ((err) => notice(err.message, 'error')))(e);
      return false;
    } finally {
      if (chat === c) { if (tail === '/message' && chat.state !== 'finished') chat.state = 'busy'; refresh(); }
    }
  }

  async function send() {
    const input = $('chat-input');
    const text = input.value.trim();
    if (!text || !chat) return;
    $('chat-send').disabled = true;
    const ok = await act('/message', { text }, (e) => notice(e.status === 409
      ? 'Still working on the previous message — wait for it, or Cancel.' : e.message, e.status === 409 ? 'busy' : 'error'));
    if (ok) { input.value = ''; input.style.height = 'auto'; notice(''); }
    if (chat) $('chat-send').disabled = chat.state === 'finished';
  }

  // ── events ────────────────────────────────────────────────────────────
  view().addEventListener('click', (e) => {
    const b = e.target.closest('[data-act]');
    if (!b) return;
    const a = b.dataset.act;
    if (a === 'refresh') route();
    else if (a === 'add') addDevice();
    else if (a === 'code') getCode();
    else if (a === 'copy') copyCommand(b);
    else if (a === 'approve') claimAction(b.dataset.id, true);
    else if (a === 'deny') claimAction(b.dataset.id, false);
    else if (a === 'revoke') revoke(b.dataset.id, b.dataset.name);
    else if (a === 'perm') act('/permission', { optionId: b.dataset.option });
    else if (a === 'cancel') act('/cancel');
    else if (a === 'stop' && confirm('End this session? The agent process is stopped.')) act('/stop');
  });
  window.addEventListener('hashchange', route);
  document.addEventListener('visibilitychange', () => {
    if (document.hidden) clearTimeout(pollTimer);
    else if (chat) refresh();
  });

  initTopbar(ENV.ROUTES.RELAY);
  route();
})();
