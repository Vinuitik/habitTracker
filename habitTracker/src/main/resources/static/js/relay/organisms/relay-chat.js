// #/d/<dev>/s/<sid> chat: message list, permission prompt, Cancel / Stop, composer; polls the session.
// RelayChat.mount(el, {dev, sid}) → unmount (contract: js/relay/router.js). unmount stops polling.
window.RelayChat = (() => {
  const { $, esc } = Dom;
  const R = Relay;
  const EP = ENV.ENDPOINTS;
  const POLL_ACTIVE_MS = 2000;   // busy / waiting
  const POLL_IDLE_MS = 10000;    // idle / error
  let chat = null;      // open chat: {dev, sid, state, sig, notice}; null once unmounted
  let pollTimer = null;

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
    $('chat-state').innerHTML = R.stateBadge(s.state);
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

  function mount(el, ctx) {
    const { dev, sid } = ctx;
    $('relay-page').classList.add('relay-page--chat');
    el.innerHTML = `
      <div class="relay-chat">
        <div class="relay-chat__head">
          <a class="relay-back relay-back--inline" href="#/d/${R.enc(dev)}">←</a>
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
    const off = R.onAct(el, {
      perm: (b) => act('/permission', { optionId: b.dataset.option }),
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
      chat = null;
      clearTimeout(pollTimer);
      off();
      document.removeEventListener('visibilitychange', onVisibility);
      $('relay-page').classList.remove('relay-page--chat');
    };
  }

  return { mount };
})();
