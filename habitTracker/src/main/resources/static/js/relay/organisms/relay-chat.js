// #/d/<dev>/s/<sid> chat: message list, blocking cards (permission / quota / login), Cancel / Stop,
// model+effort chip and permission-mode select above the composer, per-session draft; polls the session.
// Prefs: the last mode / config the user picked (Relay.store 'chatPrefs', per user) are applied once to a
// freshly opened session that is idle with no messages yet (the runner starts new sessions in its static
// default mode). Sessions with messages are never touched.
// Composer: polling renders never replace #chat-input and only write its disabled/placeholder on a change,
// so a re-render can't drop focus or the IME (keyboard language switch).
// #chat-agents is an empty slot above the messages: relay-agents.js mounts into it.
// RelayChat.mount(el, {dev, sid}) → unmount (contract: js/relay/router.js). unmount stops polling.
window.RelayChat = (() => {
  const { $, esc } = Dom;
  const R = Relay;
  const EP = ENV.ENDPOINTS;
  const POLL_BUSY_MS = 1000;
  const POLL_WAITING_MS = 3000;
  const POLL_IDLE_MS = 10000;    // idle / error
  const CHIP_CATEGORIES = ['model', 'thought_level'];   // shown in the chip label, in this order
  const draftKey = (sid) => `relay.draft.${sid}`;
  const PREFS = 'chatPrefs';   // {mode?: modeId, config?: {configId: value}}
  let chat = null;      // open chat: {dev, sid, state, sig, notice}; null once unmounted
  let pollTimer = null;
  let agentsOff = null;    // unmounts the subagents panel (relay-agents.js)
  let popoverOff = null;   // closes the config popover (removes its document listeners)

  const sessionUrl = (c, tail = '') => EP.RELAY_V1(c.dev, `/sessions/${R.enc(c.sid)}${tail}`);

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
      const s = await R.call(sessionUrl(c));
      if (chat !== c) return;
      render(s);
      if (!c.prefsDone) { c.prefsDone = true; applyPrefs(c, s); }
      if (c.notice === 'error' || (c.notice === 'busy' && s.state !== 'busy')) notice('');
    } catch (e) {
      if (chat !== c) return;
      notice(e.message, 'error');
    }
    schedule();
  }

  // Poll while the tab is visible: 1s busy, 3s waiting, 10s idle, never once finished.
  function schedule() {
    clearTimeout(pollTimer);
    if (!chat || document.hidden || chat.state === 'finished') return;
    pollTimer = setTimeout(refresh, chat.state === 'busy' ? POLL_BUSY_MS : chat.state === 'waiting' ? POLL_WAITING_MS : POLL_IDLE_MS);
  }

  // ---- model / effort chip + mode select ----
  const choiceName = (o) => (o.options.find((c) => c.value === o.currentValue) || {}).name || o.currentValue;
  const chipLabel = (opts) => {
    const parts = CHIP_CATEGORIES.map((cat) => opts.find((o) => o.category === cat)).filter(Boolean).map(choiceName);
    return parts.join(' · ') || (opts[0] ? choiceName(opts[0]) : '');
  };

  function closePopover() {
    if (popoverOff) { const f = popoverOff; popoverOff = null; f(); }
    const pop = $('chat-config-pop');
    if (pop) pop.hidden = true;
  }

  // Groups: model, thought_level, then everything else (fast, model_config…); the `mode` option has its own select.
  function openPopover() {
    const opts = (chat.configOptions || []).filter((o) => o.category !== 'mode' && o.id !== 'mode');
    const rank = (o) => { const i = CHIP_CATEGORIES.indexOf(o.category); return i < 0 ? CHIP_CATEGORIES.length : i; };
    const pop = $('chat-config-pop');
    pop.innerHTML = opts.slice().sort((a, b) => rank(a) - rank(b)).map((o) => `
      <div class="chip-pop__group" role="group" aria-label="${esc(o.name)}">
        <div class="chip-pop__label">${esc(o.name)}</div>
        ${(o.options || []).map((c) => `
          <button type="button" class="chip-pop__item${c.value === o.currentValue ? ' chip-pop__item--on' : ''}"
            data-act="config" data-config="${esc(o.id)}" data-value="${esc(c.value)}" title="${esc(c.description || '')}">${esc(c.name)}</button>`).join('')}
      </div>`).join('');
    pop.hidden = false;
    const onDoc = (e) => { if (!pop.contains(e.target) && !$('chat-config').contains(e.target)) closePopover(); };
    const onKey = (e) => { if (e.key === 'Escape') closePopover(); };
    document.addEventListener('click', onDoc, true);
    document.addEventListener('keydown', onKey, true);
    popoverOff = () => { document.removeEventListener('click', onDoc, true); document.removeEventListener('keydown', onKey, true); };
  }

  function renderConfig(s, locked) {
    const opts = s.configOptions || [];
    chat.configOptions = opts;
    const chip = $('chat-config');
    const label = chipLabel(opts);
    chip.hidden = !label;
    chip.textContent = label ? label + ' ▾' : '';
    chip.disabled = locked;
    if (locked) closePopover();
    const modes = s.modes || [];
    const sel = $('chat-mode');
    const key = JSON.stringify([modes, s.mode]);
    if (sel.dataset.key !== key) {
      sel.dataset.key = key;
      sel.hidden = !modes.length;
      sel.innerHTML = modes.map((m) => `<option value="${esc(m.id)}" title="${esc(m.description || '')}"${m.id === s.mode ? ' selected' : ''}>${esc(m.name)}</option>`).join('');
    }
    sel.disabled = locked;
  }

  function render(s) {
    const c = chat;
    c.state = s.state;
    $('chat-title').textContent = s.title || 'New session';
    $('chat-state').innerHTML = R.stateBadge(s.state);
    $('chat-ctx').innerHTML = R.ctxTag(s.contextTokens);
    const working = s.state === 'busy' || s.state === 'waiting';
    renderConfig(s, working || s.state === 'finished');
    $('chat-cancel').hidden = !working;
    $('chat-stop').hidden = s.state === 'finished';
    const done = s.state === 'finished';
    $('chat-send').disabled = done;
    const input = $('chat-input');
    if (input.disabled !== done) {
      input.disabled = done;
      input.placeholder = done ? 'Session finished' : 'Message…';
    }

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
    const atBottom = c.sig === null || nearBottom(el);
    track(c, msgs, c.sig === null);
    c.sig = sig;
    el.innerHTML = msgs.length
      ? msgs.map((m, i) => (c.rv && c.rv.idx === i ? streamHtml(c.rv) : messageHtml(m))).join('')
      : '<div class="relay-empty">Say something to start.</div>';
    if (atBottom) el.scrollTop = el.scrollHeight;
  }

  const nearBottom = (el) => el.scrollHeight - el.scrollTop - el.clientHeight < 80;

  // ---- smooth streaming ----
  // The transcript only grows once per poll and claude-agent-acp sends whole text blocks, so new agent text
  // would land as a wall. The last agent message is revealed word by word instead (#chat-stream), fast enough
  // to catch up in ENV.TIMING.RELAY_STREAM_CATCHUP_MS; each poll re-aims at the new end. Text already there
  // when the chat opens, or a message that stops being last, shows in full. rv = {key, idx, text, shown, pos, cps, raf, last}.
  function track(c, msgs, first) {
    const idx = msgs.length - 1;
    const m = msgs[idx];
    if (!m || m.role !== 'agent' || m.kind || !m.text) { stopReveal(c); return; }
    const key = `${idx}|${m.at}`;
    if (!c.rv || c.rv.key !== key) {
      stopReveal(c);
      const shown = first ? m.text.length : 0;
      c.rv = { key, idx, text: m.text, shown, pos: shown, cps: 0, raf: 0, last: 0 };
    }
    const rv = c.rv;
    rv.text = m.text;
    rv.shown = Math.min(rv.shown, rv.text.length);
    rv.pos = Math.min(rv.pos, rv.text.length);
    const left = rv.text.length - rv.shown;
    rv.cps = Math.max(ENV.TIMING.RELAY_STREAM_MIN_CPS, (left * 1000) / ENV.TIMING.RELAY_STREAM_CATCHUP_MS);
    if (left > 0 && !rv.raf) rv.raf = requestAnimationFrame(step);
  }

  function stopReveal(c) {
    if (c.rv) cancelAnimationFrame(c.rv.raf);
    c.rv = null;
  }

  const streamHtml = (rv) => `<div class="relay-msg relay-msg--agent" id="chat-stream">${R.md(rv.text.slice(0, rv.shown))}</div>`;

  function step(ts) {
    const rv = chat && chat.rv;
    if (!rv) return;
    rv.raf = 0;
    const dt = rv.last ? Math.min(ts - rv.last, 100) : 16;
    rv.last = ts;
    rv.pos = Math.min(rv.text.length, rv.pos + (rv.cps * dt) / 1000);
    const cut = Math.floor(rv.pos);
    const sp = rv.text.slice(cut).search(/\s/);   // finish the current word
    const next = sp < 0 ? rv.text.length : cut + sp;
    if (next > rv.shown) {
      rv.shown = next;
      const node = $('chat-stream');
      if (node) {
        const el = $('chat-msgs');
        const stick = nearBottom(el);
        node.innerHTML = R.md(rv.text.slice(0, rv.shown));
        if (stick) el.scrollTop = el.scrollHeight;
      }
    }
    if (rv.shown < rv.text.length) rv.raf = requestAnimationFrame(step);
    else rv.last = 0;
  }

  function messageHtml(m) {
    if (m.role === 'tool') {
      return `<div class="relay-tool relay-tool--${esc(m.status || 'completed')}">
        <span class="relay-tool__kind">${esc(m.toolKind || 'tool')}</span><span>${esc(m.text)}</span></div>`;
    }
    if (m.role === 'agent' && (m.kind === 'quota' || m.kind === 'auth')) {
      const quota = m.kind === 'quota';
      return `<div class="relay-block"><div class="relay-block__title">${quota ? 'Quota exhausted' : 'Claude login expired'}</div>
        <div class="relay-block__text">${quota ? 'Your Claude subscription limit is used up. Sessions work again once it resets.'
          : 'Sign in to Claude again on the runner machine, then send your message again.'}</div>
        ${m.text ? `<div class="relay-block__detail">${R.md(m.text)}</div>` : ''}</div>`;
    }
    const cls = m.role === 'user' ? 'relay-msg--user' : 'relay-msg--agent' + (m.kind ? ' relay-msg--alert' : '');
    return `<div class="relay-msg ${cls}">${R.md(m.text)}</div>`;
  }

  // POST then show the returned Session (or poll) — shared by send / permission / cancel / stop.
  async function act(tail, body, onError) {
    const c = chat;
    try {
      const res = await R.call(sessionUrl(c, tail), 'POST', body);
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

  function saveDraft(text) {
    try { if (text) localStorage.setItem(draftKey(chat.sid), text); else localStorage.removeItem(draftKey(chat.sid)); } catch (_) { /* storage blocked */ }
  }
  function loadDraft(sid) {
    try { return localStorage.getItem(draftKey(sid)) || ''; } catch (_) { return ''; }
  }

  async function savePrefs(update) {
    const p = (await R.store.get(PREFS)) || {};
    update(p);
    R.store.set(PREFS, p);
  }

  // A new, idle, empty session that differs from the remembered choices → POST /mode, /config (once per open).
  async function applyPrefs(c, s) {
    if (s.state !== 'idle' || (s.messages || []).length) return;
    const p = (await R.store.get(PREFS)) || {};
    if (chat !== c) return;
    if (p.mode && p.mode !== s.mode && (s.modes || []).some((m) => m.id === p.mode)) {
      await act('/mode', { modeId: p.mode }, () => {});
    }
    for (const [configId, value] of Object.entries(p.config || {})) {
      if (chat !== c || c.state !== 'idle') return;
      const o = (c.configOptions || []).find((x) => x.id === configId && x.category !== 'mode');
      if (o && o.currentValue !== value && (o.options || []).some((x) => x.value === value)) {
        await act('/config', { configId, value }, () => {});
      }
    }
  }

  async function setMode(modeId) {
    if (await act('/mode', { modeId }) === false) refresh();
    else savePrefs((p) => { p.mode = modeId; });
  }

  async function setConfig(configId, value) {
    if (await act('/config', { configId, value })) savePrefs((p) => { p.config = { ...(p.config || {}), [configId]: value }; });
  }

  async function send() {
    const input = $('chat-input');
    const text = input.value.trim();
    if (!text || !chat) return;
    $('chat-send').disabled = true;
    const ok = await act('/message', { text }, (e) => notice(e.status === 409
      ? 'Still working on the previous message — wait for it, or Cancel.' : e.message, e.status === 409 ? 'busy' : 'error'));
    if (ok) { input.value = ''; input.style.height = 'auto'; saveDraft(''); notice(''); }
    if (chat) $('chat-send').disabled = chat.state === 'finished';
  }

  function mount(el, ctx) {
    const { dev, sid } = ctx;
    $('relay-page').classList.add('relay-page--chat');
    el.innerHTML = `
      <div class="relay-chat">
        <div class="relay-chat__head">
          <a class="relay-back relay-back--inline" href="#/d/${R.enc(dev)}">←</a>
          <span class="relay-chat__title" id="chat-title">Loading…</span>
          <span id="chat-ctx"></span>
          <span id="chat-state"></span>
          <button class="btn btn--ghost btn--sm" data-act="cancel" id="chat-cancel" title="Stop the current turn, keep the session" hidden>Cancel</button>
          <button class="btn btn--danger btn--sm" data-act="stop" id="chat-stop" title="End the session (kills the agent process)" hidden>Stop</button>
        </div>
        <div class="relay-agents" id="chat-agents"></div>
        <div class="relay-msgs" id="chat-msgs"></div>
        <div class="relay-perm" id="chat-perm" hidden></div>
        <div class="relay-notice" id="chat-notice" hidden></div>
        <div class="relay-chatbar">
          <button type="button" class="btn btn--ghost btn--sm" id="chat-config" data-act="config-open" aria-haspopup="true" hidden></button>
          <select class="input relay-chatbar__mode" id="chat-mode" aria-label="Permission mode" hidden></select>
          <div class="chip-pop" id="chat-config-pop" hidden></div>
        </div>
        <form class="relay-composer" id="chat-form">
          <textarea class="input" id="chat-input" rows="1" placeholder="Message…" aria-label="Message"></textarea>
          <button class="btn btn--primary" type="submit" id="chat-send">Send</button>
        </form>
      </div>`;
    chat = { dev, sid, state: '', sig: null, notice: '', configOptions: [], prefsDone: false, rv: null };
    agentsOff = RelayAgents.mount($('chat-agents'), { dev, sid });
    const input = $('chat-input');
    input.value = loadDraft(sid);
    input.addEventListener('input', () => saveDraft(input.value));
    $('chat-mode').addEventListener('change', (e) => setMode(e.target.value));
    input.addEventListener('keydown', (e) => {
      if (e.key === 'Enter' && !e.shiftKey && !e.isComposing) { e.preventDefault(); $('chat-form').requestSubmit(); }
    });
    input.addEventListener('input', () => { input.style.height = 'auto'; input.style.height = Math.min(input.scrollHeight, 160) + 'px'; });
    $('chat-form').addEventListener('submit', (e) => { e.preventDefault(); send(); });
    const off = R.onAct(el, {
      perm: (b) => act('/permission', { optionId: b.dataset.option }),
      'config-open': () => ($('chat-config-pop').hidden ? openPopover() : closePopover()),
      config: (b) => { closePopover(); setConfig(b.dataset.config, b.dataset.value); },
      cancel: () => act('/cancel'),
      stop: () => { if (confirm('End this session? The agent process is stopped.')) act('/stop'); },
    });
    const onVisibility = () => {
      if (document.hidden) clearTimeout(pollTimer);
      else if (chat) refresh();
    };
    document.addEventListener('visibilitychange', onVisibility);
    refresh();
    return () => {
      if (chat) stopReveal(chat);
      chat = null;
      closePopover();
      if (agentsOff) { agentsOff(); agentsOff = null; }
      clearTimeout(pollTimer);
      off();
      document.removeEventListener('visibilitychange', onVisibility);
      $('relay-page').classList.remove('relay-page--chat');
    };
  }

  return { mount };
})();
