// Board view: sticky header + horizontal scroller of list columns, plus all board writes.
// State lives here (S). Every write is optimistic: mutate local state + DOM now, call the API,
// merge the server response on success, restore the touched fields and toast on failure.
// The page script owns fetching/routing and passes handlers {onRefresh, onPlan, onCard, onAuth, projectId}.
window.Board = (() => {
  const { esc, safeUrl, timeAgo } = Dom;
  const EP = ENV.ENDPOINTS;
  const STEP = ENV.BOARD.POS_STEP;
  const RATE_MSG = 'Trello is rate limiting, try again in a moment';

  let S = null;           // { data, el, h }
  let tmpSeq = 0;
  const pend = new Map(); // cardId -> in-flight write count (server response applied only at 0)

  const isTmp = (id) => String(id).startsWith('tmp-');
  const openLists = () => S.data.lists.filter((l) => !l.closed).sort((a, b) => a.pos - b.pos);
  const cardsOf = (listId) => S.data.cards.filter((c) => !c.closed && c.idList === listId).sort((a, b) => a.pos - b.pos);
  const getCard = (id) => (S ? S.data.cards.find((c) => c.id === id) : null);
  const getList = (id) => S.data.lists.find((l) => l.id === id);
  const listName = (id) => (getList(id) ? getList(id).name : '');
  const lastPos = (listId, exceptId) => {
    const cs = cardsOf(listId).filter((c) => c.id !== exceptId);
    return cs.length ? cs[cs.length - 1].pos : 0;
  };
  const colEl = (listId) => [...S.el.querySelectorAll('.list-column[data-list-id]')].find((c) => c.dataset.listId === listId);

  function fail(e, fallback) {
    if (Api.isTrelloAuth(e)) { if (S.h.onAuth) S.h.onAuth(e); return; }
    if (e.status === 429 || e.code === 'trello_rate_limited') Toast.show(RATE_MSG);
    else Toast.show(e.message || fallback || 'Something went wrong');
  }

  // ── rendering ─────────────────────────────────────────────────────────
  const cardsHtml = (listId) => cardsOf(listId).map(CardTile.html).join('');

  function columnHtml(l) {
    return `
      <section class="list-column" data-list-id="${esc(l.id)}" aria-label="${esc(l.name)}">
        <header class="list-column__header">
          <h2 class="list-column__name">${esc(l.name)}</h2>
          <div class="list-column__tools">
            <span class="list-column__count">${cardsOf(l.id).length}</span>
            <button type="button" class="list-column__menu" data-act="list-menu" aria-label="List actions">${Icons.kebab}</button>
          </div>
        </header>
        <div class="list-column__cards">${cardsHtml(l.id)}</div>
        <footer class="list-column__footer">${Composer.html({ trigger: 'Add a card', placeholder: 'Enter a title for this card…', submit: 'Add card' })}</footer>
      </section>`;
  }

  const addColHtml = () => `
    <section class="list-column list-column--add" data-add-list>
      ${Composer.html({ trigger: 'Add another list', placeholder: 'Enter list name…', submit: 'Add list' })}
    </section>`;

  function wireColumn(col) {
    Composer.bind(col.querySelector('.composer'), (text) => addCard(col.dataset.listId, text));
  }

  function renderColumns() {
    const sc = S.el.querySelector('.board__scroller');
    const left = sc.scrollLeft;
    sc.innerHTML = openLists().map(columnHtml).join('') + addColHtml();
    sc.querySelectorAll('.list-column[data-list-id]').forEach(wireColumn);
    Composer.bind(sc.querySelector('[data-add-list] .composer'), addList);
    sc.scrollLeft = left;
  }

  function refreshList(listId) {
    const col = colEl(listId);
    if (!col) return;
    const box = col.querySelector('.list-column__cards');
    const top = box.scrollTop;
    box.innerHTML = cardsHtml(listId);
    box.scrollTop = top;
    col.querySelector('.list-column__count').textContent = cardsOf(listId).length;
  }
  const refreshLists = (...ids) => [...new Set(ids)].forEach((id) => id && refreshList(id));

  function render(root, data, stale, h) {
    const prevScroll = root.querySelector('.board__scroller')?.scrollLeft || 0;
    data.lists = data.lists || [];
    data.cards = data.cards || [];
    const el = document.createElement('div');
    el.className = 'board';
    S = { data, el, h };
    pend.clear();
    const url = safeUrl(data.url);
    el.innerHTML = `
      <header class="board__header">
        <a class="btn btn--ghost board__back" href="#/" aria-label="Back to boards">${Icons.back}</a>
        <h1 class="board__name">${esc(data.name)}</h1>
        <div class="board__tools">
          ${stale ? '<span class="badge badge--warning">Trello unreachable, showing cached data</span>' : ''}
          <span class="board__synced">synced ${esc(timeAgo(data.syncedAt))}</span>
          ${url ? `<a class="btn btn--ghost" href="${esc(url)}" target="_blank" rel="noopener noreferrer">Open in Trello</a>` : ''}
          <button class="btn btn--ghost" data-act="refresh">Refresh</button>
          <button class="btn btn--primary" data-act="plan">Plan with AI</button>
        </div>
      </header>
      <div class="board__scroller"></div>`;
    root.replaceChildren(el);
    renderColumns();
    el.querySelector('.board__scroller').scrollLeft = prevScroll;

    el.addEventListener('click', (e) => {
      const act = e.target.closest('[data-act]');
      if (act && act.dataset.act === 'refresh') { h.onRefresh(act); return; }
      if (act && act.dataset.act === 'plan') { h.onPlan(act); return; }
      if (act && act.dataset.act === 'list-menu') { listMenu(act); return; }
      const tile = e.target.closest('.card-tile');
      if (!tile) return;
      const c = getCard(tile.dataset.cardId);
      if (!c || isTmp(c.id)) return;
      if (act && act.dataset.act === 'quick') { e.stopPropagation(); (c.done ? reopenCard : completeCard)(c.id); return; }
      openTile(c);
    });
    el.addEventListener('keydown', (e) => {
      if (e.key !== 'Enter' && e.key !== ' ') return;
      const tile = e.target.closest('.card-tile');
      if (!tile || e.target !== tile) return;
      const c = getCard(tile.dataset.cardId);
      if (c && !isTmp(c.id)) { e.preventDefault(); openTile(c); }
    });
    const openTile = (c) => h.onCard(c, listName(c.idList));

    Dnd.init(el, {
      itemSel: '.card-tile', zoneSel: '.list-column[data-list-id]', containerSel: '.list-column__cards',
      scrollerSel: '.board__scroller', ignoreSel: 'button,a,input,textarea,.card-tile--pending',
      cfg: {
        holdMs: ENV.TIMING.DRAG_HOLD_MS, startPx: ENV.BOARD.DRAG_START_PX, holdSlopPx: ENV.BOARD.DRAG_HOLD_SLOP_PX,
        edgePx: ENV.BOARD.AUTOSCROLL_EDGE_PX, maxPx: ENV.BOARD.AUTOSCROLL_MAX_PX,
      },
      onDrop: onDrop,
    });
    return el;
  }

  // ── card writes ───────────────────────────────────────────────────────
  // local = fields applied immediately; call() returns the server card. Only the touched fields
  // roll back on failure (so concurrent edits to other fields survive).
  async function mutate(card, local, call) {
    const prev = {};
    Object.keys(local).forEach((k) => { prev[k] = card[k]; });
    const oldList = card.idList;
    Object.assign(card, local);
    pend.set(card.id, (pend.get(card.id) || 0) + 1);
    refreshLists(oldList, card.idList);
    try {
      const resp = await call();
      const n = pend.get(card.id) - 1;
      pend.set(card.id, n);
      if (!n && resp) Object.assign(card, resp);
      refreshLists(oldList, card.idList);
      return card;
    } catch (e) {
      pend.set(card.id, pend.get(card.id) - 1);
      const cur = card.idList;
      Object.assign(card, prev);
      refreshLists(oldList, cur, card.idList);
      fail(e, 'Could not update card');
      return null;
    }
  }

  const patchCard = (id, body) => Api.request(EP.PROJECT_CARD(S.h.projectId, id), 'PATCH', body);

  function updateCard(id, patch) {
    const c = getCard(id);
    return c ? mutate(c, patch, () => patchCard(id, patch)) : Promise.resolve(null);
  }
  function completeCard(id) {
    const c = getCard(id);
    return c ? mutate(c, { done: true }, () => Api.request(EP.PROJECT_CARD_COMPLETE(S.h.projectId, id), 'POST')) : Promise.resolve(null);
  }
  function reopenCard(id) {
    const c = getCard(id);
    return c ? mutate(c, { done: false }, () => Api.request(EP.PROJECT_CARD_REOPEN(S.h.projectId, id), 'POST')) : Promise.resolve(null);
  }
  function moveCardToList(id, listId) {
    const c = getCard(id);
    if (!c || c.idList === listId) return Promise.resolve(c);
    if (isTmp(listId)) { Toast.show('Still creating that list…'); return Promise.resolve(null); }
    return mutate(c, { idList: listId, pos: lastPos(listId, id) + STEP }, () => patchCard(id, { idList: listId, pos: 'bottom' }));
  }
  function archiveCard(id) {
    const c = getCard(id);
    if (!c) return Promise.resolve(null);
    const p = mutate(c, { closed: true }, () => patchCard(id, { closed: true }));
    Toast.show('Card archived', ENV.TIMING.UNDO_TOAST_MS, {
      label: 'Undo',
      onClick: () => mutate(c, { closed: false }, () => patchCard(id, { closed: false })),
    });
    return p;
  }

  async function addCard(listId, name) {
    if (isTmp(listId)) { Toast.show('Still creating that list…'); return; }
    const tmp = { id: `tmp-${++tmpSeq}`, idList: listId, name, desc: '', pos: lastPos(listId) + STEP, labels: [], badges: {}, closed: false, done: false };
    S.data.cards.push(tmp);
    refreshList(listId);
    const box = colEl(listId)?.querySelector('.list-column__cards');
    if (box) box.scrollTop = box.scrollHeight;
    try {
      const resp = await Api.request(EP.PROJECT_CARDS(S.h.projectId), 'POST', { idList: listId, name, pos: tmp.pos });
      Object.assign(tmp, resp);
      refreshLists(listId, tmp.idList);
    } catch (e) {
      S.data.cards = S.data.cards.filter((c) => c !== tmp);
      refreshList(listId);
      fail(e, 'Could not add card');
    }
  }

  // Pos for a drop between prev and next neighbours (null = none on that side).
  function posBetween(prev, next) {
    if (!prev && !next) return STEP;
    if (!prev) return next.pos / 2;
    if (!next) return prev.pos + STEP;
    return (prev.pos + next.pos) / 2;
  }

  function onDrop(d) {
    const card = getCard(d.item.dataset.cardId);
    const toId = d.toContainer.closest('.list-column').dataset.listId;
    const fromId = card ? card.idList : null;
    if (!card) return;
    if (isTmp(toId)) { Toast.show('Still creating that list…'); refreshLists(fromId, toId); return; }
    const prev = d.prevEl ? getCard(d.prevEl.dataset.cardId) : null;
    const next = d.nextEl ? getCard(d.nextEl.dataset.cardId) : null;
    const inPlace = toId === fromId && (!prev || prev.pos < card.pos) && (!next || card.pos < next.pos);
    if (inPlace) { refreshList(fromId); return; }
    const pos = posBetween(prev, next);
    mutate(card, { idList: toId, pos }, () => patchCard(card.id, { idList: toId, pos }));
  }

  // ── list writes ───────────────────────────────────────────────────────
  async function addList(name) {
    const ls = openLists();
    const tmp = { id: `tmp-${++tmpSeq}`, name, pos: (ls.length ? ls[ls.length - 1].pos : 0) + STEP, closed: false };
    S.data.lists.push(tmp);
    const sc = S.el.querySelector('.board__scroller');
    sc.querySelector('[data-add-list]').insertAdjacentHTML('beforebegin', columnHtml(tmp));
    const col = colEl(tmp.id);
    wireColumn(col);
    sc.scrollLeft = sc.scrollWidth;
    try {
      const resp = await Api.request(EP.PROJECT_LISTS(S.h.projectId), 'POST', { name, pos: tmp.pos });
      Object.assign(tmp, resp);
      col.dataset.listId = tmp.id;
    } catch (e) {
      S.data.lists = S.data.lists.filter((l) => l !== tmp);
      col.remove();
      fail(e, 'Could not add list');
    }
  }

  const patchList = (id, body) => Api.request(EP.PROJECT_LIST(S.h.projectId, id), 'PATCH', body);

  async function renameList(list, name) {
    const old = list.name;
    list.name = name;
    syncName(list);
    try { Object.assign(list, await patchList(list.id, { name })); syncName(list); }
    catch (e) { list.name = old; syncName(list); fail(e, 'Could not rename list'); }
  }
  function syncName(list) {
    const col = colEl(list.id);
    if (!col) return;
    col.setAttribute('aria-label', list.name);
    const h2 = col.querySelector('.list-column__name');
    if (h2) h2.textContent = list.name;
  }

  async function archiveList(list) {
    list.closed = true;
    colEl(list.id)?.remove();
    try {
      await patchList(list.id, { closed: true });
      Toast.show('List archived', ENV.TIMING.UNDO_TOAST_MS, {
        label: 'Undo',
        onClick: async () => {
          list.closed = false; renderColumns();
          try { await patchList(list.id, { closed: false }); }
          catch (e) { list.closed = true; renderColumns(); fail(e, 'Could not restore list'); }
        },
      });
    } catch (e) {
      list.closed = false;
      renderColumns();
      fail(e, 'Could not archive list');
    }
  }

  function startRename(col, list) {
    const h2 = col.querySelector('.list-column__name');
    const input = document.createElement('input');
    input.className = 'input input--sm list-column__name-input';
    input.maxLength = 512;
    input.value = list.name;
    input.setAttribute('aria-label', 'List name');
    h2.replaceWith(input);
    input.focus();
    input.select();
    let done = false;
    const finish = (save) => {
      if (done) return;
      done = true;
      const v = input.value.trim();
      input.replaceWith(h2);
      if (save && v && v !== list.name) renameList(list, v);
    };
    input.addEventListener('blur', () => finish(true));
    input.addEventListener('keydown', (e) => {
      if (e.key === 'Enter') { e.preventDefault(); finish(true); }
      else if (e.key === 'Escape') { e.stopPropagation(); finish(false); }
    });
  }

  function listMenu(btn) {
    const col = btn.closest('.list-column');
    const list = getList(col.dataset.listId);
    if (!list) return;
    if (isTmp(list.id)) { Toast.show('Still creating that list…'); return; }
    Menu.open(btn, [
      { label: 'Rename', onClick: () => startRename(col, list) },
      { label: 'Archive list', danger: true, confirm: 'Archive this list and hide its cards?', onClick: () => archiveList(list) },
    ]);
  }

  return {
    render, getCard, listName, openLists: () => openLists().map((l) => ({ id: l.id, name: l.name })),
    updateCard, completeCard, reopenCard, moveCardToList, archiveCard,
  };
})();
