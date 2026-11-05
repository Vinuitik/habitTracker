// #/d/<dev>/p/<pid>/chats project Chats tab: one newest-first list of Relay sessions + laptop / VS Code chats
// (tap = Resume), paged by ENV.LIMITS.RELAY_CHATS_PAGE, + New chat.
// RelayChats.mount(el, {dev, pid, refresh}) → unmount (contract: js/relay/router.js).
window.RelayChats = (() => {
  const { $, esc, timeAgo } = Dom;
  const R = Relay;
  const EP = ENV.ENDPOINTS;
  const PROVIDER = 'claude';

  const chatUrl = (dev, sid) => `#/d/${R.enc(dev)}/s/${R.enc(sid)}`;
  const projectUrl = (dev, pid, tail = '') => EP.RELAY_V1(dev, `/projects/${R.enc(pid)}${tail}`);

  // One list, newest first: Relay sessions + laptop / VS Code chats not open in Relay yet (Resume on tap).
  // Shows CHATS_PAGE rows, then "Show N older". Sessions render at once; the agent list (~1-2s) merges in.
  const sessionRow = (dev, s) => `
    <a class="relay-row relay-row__main${s.state === 'finished' ? ' relay-row--done' : ''}" href="${chatUrl(dev, s.id)}">
      <span class="relay-row__body">
        <span class="relay-row__name">${esc(s.title || 'New session')}</span>
        <span class="relay-row__meta">${esc(timeAgo(s.lastActiveAt || s.createdAt))}${s.preview ? ' · ' + esc(s.preview) : ''}</span>
      </span>
      ${R.ctxTag(s.contextTokens)}${R.stateBadge(s.state)}
    </a>`;

  const agentRow = (c) => `
    <button type="button" class="relay-row relay-row__main relay-chats__agent" data-act="resume" data-agent="${esc(c.agentSessionId)}" data-ctx="${c.contextTokens || 0}">
      <span class="relay-row__body">
        <span class="relay-row__name">${esc(c.title || 'Untitled chat')}</span>
        <span class="relay-row__meta">${esc(timeAgo(c.updatedAt))} · from VS Code / laptop</span>
      </span>
      ${R.ctxTag(c.contextTokens)}<span class="badge badge--muted">Resume</span>
    </button>`;

  const when = (r) => String((r.kind === 's' ? (r.v.lastActiveAt || r.v.createdAt) : r.v.updatedAt) || '');

  async function load(el, ctx, alive, view) {
    const { dev, pid } = ctx;
    const page = ENV.LIMITS.RELAY_CHATS_PAGE;
    let sessions = null;
    let agents = [];
    let agentNote = '<div class="relay-hint relay-chats__note">Looking for VS Code chats…</div>';
    let shown = page;

    el.innerHTML = R.header('Chats', [`#/d/${R.enc(dev)}`, esc(R.deviceNames.get(dev) || 'Sessions')],
      '<button class="btn btn--ghost" data-act="refresh">Refresh</button><button class="btn btn--primary" data-act="new" id="new-chat">New chat</button>') + `
      <div class="relay-list" id="chats-list"><div class="relay-empty">Loading…</div></div>
      <div id="chats-more"></div>`;

    function paint() {
      if (!alive() || !sessions) return;
      const rows = sessions.map((v) => ({ kind: 's', v })).concat(agents.map((v) => ({ kind: 'a', v })))
        .sort((x, y) => when(y).localeCompare(when(x)));
      $('chats-list').innerHTML = rows.length
        ? rows.slice(0, shown).map((r) => (r.kind === 's' ? sessionRow(dev, r.v) : agentRow(r.v))).join('')
        : '<div class="relay-empty">No chats yet. Start one with New chat.</div>';
      const older = rows.length - shown;
      $('chats-more').innerHTML = (older > 0
        ? `<button type="button" class="btn btn--ghost relay-chats__more" data-act="more">Show ${Math.min(older, page)} older (${older} left)</button>` : '')
        + agentNote;
    }
    view.more = () => { shown += page; paint(); };

    R.call(projectUrl(dev, pid, '/sessions')).then((list) => {
      sessions = list || [];
      paint();
    }, (e) => { if (alive()) $('chats-list').innerHTML = R.errorHtml(e); });

    R.call(projectUrl(dev, pid, `/agent-chats?provider=${PROVIDER}`)).then((res) => {
      agents = (res.chats || []).filter((c) => !c.sessionId);   // linked ones are already Relay sessions
      agentNote = '';
      paint();
    }, (e) => {
      agentNote = `<div class="relay-hint relay-chats__note">VS Code chats unavailable: ${esc(e.message)}</div>`;
      paint();
    });
  }

  async function open(ctx, body, btn) {
    btn.disabled = true;
    const badge = btn.querySelector('.badge') || btn;   // a resume row shows progress in its badge
    const label = badge.textContent;
    if (body.agentSessionId) badge.textContent = 'Opening…';
    try {
      const s = await R.call(projectUrl(ctx.dev, ctx.pid, '/sessions'), 'POST', body);
      location.hash = chatUrl(ctx.dev, s.id);
    } catch (e) {
      Toast.show(e.message, 4000);
      btn.disabled = false;
      badge.textContent = label;
    }
  }

  function mount(el, ctx) {
    let mounted = true;
    const view = { more: () => {} };
    const off = R.onAct(el, {
      more: () => view.more(),
      refresh: () => ctx.refresh(),
      new: (b) => open(ctx, { provider: PROVIDER }, b),
      resume: (b) => {
        // Big chat: first tap only warns (resuming re-sends all of it, then every message does again).
        const n = Number(b.dataset.ctx) || 0;
        if (R.ctxLevel(n) === 'high' && b.dataset.armed !== '1') {
          b.dataset.armed = '1';
          b.querySelector('.badge').textContent = 'Tap again';
          Toast.show(`This chat re-sends ~${R.ctxText(n)} tokens per message (~2× a fresh chat). New chat is cheaper.`, 6000);
          return;
        }
        open(ctx, { provider: PROVIDER, agentSessionId: b.dataset.agent }, b);
      },
    });
    load(el, ctx, () => mounted, view);
    return () => { mounted = false; off(); };
  }

  return { mount };
})();
