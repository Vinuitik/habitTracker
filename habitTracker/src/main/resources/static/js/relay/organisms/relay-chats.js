// #/d/<dev>/p/<pid>/chats project Chats tab: Relay sessions + laptop / VS Code chats (resumable) + New chat.
// RelayChats.mount(el, {dev, pid, refresh}) → unmount (contract: js/relay/router.js).
window.RelayChats = (() => {
  const { $, esc, timeAgo } = Dom;
  const R = Relay;
  const EP = ENV.ENDPOINTS;
  const PROVIDER = 'claude';

  const chatUrl = (dev, sid) => `#/d/${R.enc(dev)}/s/${R.enc(sid)}`;
  const projectUrl = (dev, pid, tail = '') => EP.RELAY_V1(dev, `/projects/${R.enc(pid)}${tail}`);

  const sessionRow = (dev, s) => `
    <a class="relay-row relay-row__main${s.state === 'finished' ? ' relay-row--done' : ''}" href="${chatUrl(dev, s.id)}">
      <span class="relay-row__body">
        <span class="relay-row__name">${esc(s.title || 'New session')}</span>
        <span class="relay-row__meta">${esc(timeAgo(s.lastActiveAt || s.createdAt))}${s.preview ? ' · ' + esc(s.preview) : ''}</span>
      </span>
      ${R.stateBadge(s.state)}
    </a>`;

  // A saved agent chat: already live in Relay → plain link; otherwise a Resume button.
  const agentRow = (dev, c) => c.sessionId
    ? `<a class="relay-row relay-row__main" href="${chatUrl(dev, c.sessionId)}">
        <span class="relay-row__body"><span class="relay-row__name">${esc(c.title || 'Untitled chat')}</span>
        <span class="relay-row__meta">${esc(timeAgo(c.updatedAt))}</span></span><span class="badge badge--muted">Open</span></a>`
    : `<div class="relay-row">
        <span class="relay-row__body"><span class="relay-row__name">${esc(c.title || 'Untitled chat')}</span>
        <span class="relay-row__meta">${esc(timeAgo(c.updatedAt))} · laptop / VS Code</span></span>
        <button class="btn btn--ghost btn--sm" data-act="resume" data-agent="${esc(c.agentSessionId)}">Resume</button></div>`;

  async function load(el, ctx, alive) {
    const { dev, pid } = ctx;
    el.innerHTML = R.header('Chats', [`#/d/${R.enc(dev)}`, esc(R.deviceNames.get(dev) || 'Sessions')],
      '<button class="btn btn--ghost" data-act="refresh">Refresh</button><button class="btn btn--primary" data-act="new" id="new-chat">New chat</button>') + `
      <div class="relay-section-label">Relay chats</div>
      <div class="relay-list" id="chats-sessions"><div class="relay-empty">Loading…</div></div>
      <div class="relay-section-label">Laptop and VS Code chats</div>
      <div class="relay-list" id="chats-agent"><div class="relay-empty">Loading… (can take a couple of seconds)</div></div>`;

    // Independent: the agent list spawns the agent (~1-2s), don't hold the sessions back.
    R.call(projectUrl(dev, pid, '/sessions')).then((list) => {
      if (!alive()) return;
      $('chats-sessions').innerHTML = list.length ? list.map((s) => sessionRow(dev, s)).join('')
        : '<div class="relay-empty">No Relay chats yet.</div>';
    }, (e) => { if (alive()) $('chats-sessions').innerHTML = R.errorHtml(e); });

    R.call(projectUrl(dev, pid, `/agent-chats?provider=${PROVIDER}`)).then((res) => {
      if (!alive()) return;
      const chats = res.chats || [];
      // Chats already open as a Relay session are in the list above.
      const rest = chats.filter((c) => !c.sessionId);
      $('chats-agent').innerHTML = rest.length ? rest.map((c) => agentRow(dev, c)).join('')
        : '<div class="relay-empty">No other saved chats for this folder.</div>';
    }, (e) => { if (alive()) $('chats-agent').innerHTML = R.errorHtml(e); });
  }

  async function open(ctx, body, btn) {
    btn.disabled = true;
    const label = btn.textContent;
    if (body.agentSessionId) btn.textContent = 'Opening…';
    try {
      const s = await R.call(projectUrl(ctx.dev, ctx.pid, '/sessions'), 'POST', body);
      location.hash = chatUrl(ctx.dev, s.id);
    } catch (e) {
      Toast.show(e.message, 4000);
      btn.disabled = false;
      btn.textContent = label;
    }
  }

  function mount(el, ctx) {
    let mounted = true;
    const off = R.onAct(el, {
      refresh: () => ctx.refresh(),
      new: (b) => open(ctx, { provider: PROVIDER }, b),
      resume: (b) => open(ctx, { provider: PROVIDER, agentSessionId: b.dataset.agent }, b),
    });
    load(el, ctx, () => mounted);
    return () => { mounted = false; off(); };
  }

  return { mount };
})();
