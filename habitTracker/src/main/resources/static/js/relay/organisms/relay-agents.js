// Subagents panel inside a chat (#chat-agents): collapsed summary bar, expanded tabs + selected agent's read-only feed.
// RelayAgents.mount(el, {dev, sid}) → unmount. Polls the agent list every POLL_MS; the feed only while expanded.
// Endpoints: GET /sessions/{sid}/agents, GET /sessions/{sid}/agents/{id}/messages?after=n (shared/API.md).
window.RelayAgents = (() => {
  const { esc } = Dom;
  const R = Relay;
  const EP = ENV.ENDPOINTS;
  const POLL_MS = 4000;

  function mount(el, ctx) {
    const base = (tail) => EP.RELAY_V1(ctx.dev, `/sessions/${R.enc(ctx.sid)}/agents${tail}`);
    let alive = true;
    let agents = [];
    let open = false;
    let sel = '';                                   // selected agentId
    let feed = { id: '', next: 0, msgs: [], error: '' };
    let listTimer = null;
    let feedBusy = false;

    const summary = () => {
      const run = agents.filter((a) => a.status === 'running').length;
      const parts = [];
      if (run) parts.push(`${run} subagent${run === 1 ? '' : 's'} running`);
      const done = agents.length - run;
      if (done) parts.push(`${done} done`);
      return parts.join(' · ');
    };

    function feedHtml() {
      if (feed.error) return `<div class="relay-empty relay-empty--error">${esc(feed.error)}</div>`;
      if (!feed.msgs.length) return '<div class="relay-empty">No messages yet.</div>';
      return feed.msgs.map((m) => m.role === 'tool'
        ? `<div class="relay-tool relay-tool--${esc(m.status || 'completed')}"><span class="relay-tool__kind">${esc(m.toolKind || 'tool')}</span><span>${esc(m.text)}</span></div>`
        : `<div class="relay-msg ${m.role === 'user' ? 'relay-msg--user' : 'relay-msg--agent'}">${R.md(m.text)}</div>`).join('');
    }

    function render() {
      if (!agents.length) { el.innerHTML = ''; return; }
      const running = agents.some((a) => a.status === 'running');
      const bar = `<button type="button" class="agents-bar" data-act="agents-toggle" aria-expanded="${open}">
          <span class="agents-lamp${running ? ' agents-lamp--on' : ''}"></span><span>${esc(summary())}</span><span class="agents-bar__caret">${open ? '▾' : '▸'}</span></button>`;
      if (!open) { el.innerHTML = bar; return; }
      const prev = el.querySelector('.agents-feed');
      const atBottom = !prev || prev.scrollHeight - prev.scrollTop - prev.clientHeight < 60;
      const prevTop = prev ? prev.scrollTop : 0;
      el.innerHTML = `${bar}
        <div class="agents-tabs" role="tablist">${agents.map((a) => `
          <button type="button" role="tab" class="agents-tab${a.agentId === sel ? ' agents-tab--on' : ''}" data-act="agents-pick" data-id="${esc(a.agentId)}" aria-selected="${a.agentId === sel}">
            <span class="agents-lamp${a.status === 'running' ? ' agents-lamp--on' : ''}"></span><span class="agents-tab__label">${esc(a.description || a.agentId)}</span></button>`).join('')}</div>
        <div class="agents-feed">${feedHtml()}</div>`;
      const f = el.querySelector('.agents-feed');
      f.scrollTop = atBottom ? f.scrollHeight : prevTop;
    }

    async function pollList() {
      clearTimeout(listTimer);
      try {
        const list = await R.call(base(''));
        if (!alive) return;
        agents = Array.isArray(list) ? list : [];
        if (!agents.some((a) => a.agentId === sel)) sel = agents.length ? agents[agents.length - 1].agentId : '';
        render();
        if (open) loadFeed();
      } catch (_) { /* keep the last list; the chat's own poll surfaces connectivity errors */ }
      if (alive && !document.hidden) listTimer = setTimeout(pollList, POLL_MS);
    }

    // Page through the selected agent's transcript until caught up (`next` stops moving).
    async function loadFeed() {
      if (feedBusy || !open || !sel) return;
      feedBusy = true;
      const id = sel;
      if (feed.id !== id) feed = { id, next: 0, msgs: [], error: '' };
      try {
        for (let i = 0; i < 20 && alive && open && sel === id; i++) {
          const page = await R.call(base(`/${R.enc(id)}/messages?after=${feed.next}`));
          if (!alive || sel !== id) return;
          const got = page.messages || [];
          feed.error = '';
          feed.msgs = feed.msgs.concat(got);
          const moved = page.next > feed.next;
          feed.next = page.next;
          if (!got.length || !moved || feed.next >= page.total) break;
        }
      } catch (e) {
        if (alive && sel === id) feed.error = e.message;
      } finally {
        feedBusy = false;
      }
      if (alive && open && sel === id) render();
      else if (alive && open && sel !== id) loadFeed();   // tab switched mid-fetch
    }

    const off = R.onAct(el, {
      'agents-toggle': () => { open = !open; render(); if (open) loadFeed(); },
      'agents-pick': (b) => { sel = b.dataset.id; render(); loadFeed(); },
    });
    const onVisibility = () => {
      if (document.hidden) clearTimeout(listTimer); else pollList();
    };
    document.addEventListener('visibilitychange', onVisibility);
    pollList();

    return () => {
      alive = false;
      clearTimeout(listTimer);
      off();
      document.removeEventListener('visibilitychange', onVisibility);
      el.innerHTML = '';
    };
  }

  return { mount };
})();
