// Merge mode: two boards side by side (source left/top, target right/bottom). Drag a list header from
// one side into the other to move the list (with its cards) between the Trello boards; dragging inside a
// side reorders. The per-list menu is the non-drag fallback. Optimistic with per-move rollback.
// MergeView.open(root, { source:{id,name,data}, target:{id,name,data} }, { onExit, onDeleteSource })
// Reuses Board.fail / Board.posBetween; the normal board view is rebuilt by the page on exit.
window.MergeView = (() => {
  const { esc } = Dom;
  const EP = ENV.ENDPOINTS;
  let M = null;   // { sides: {src, tgt}, el, h }

  const other = (k) => (k === 'src' ? 'tgt' : 'src');
  const openLists = (k) => M.sides[k].data.lists.filter((l) => !l.closed).sort((a, b) => a.pos - b.pos);
  const cardsOf = (k, listId) => M.sides[k].data.cards.filter((c) => !c.closed && c.idList === listId).sort((a, b) => a.pos - b.pos);
  const sideOf = (el) => el.closest('[data-side]').dataset.side;
  const scroller = (k) => M.el.querySelector(`.board__scroller[data-side="${k}"]`);

  function columnHtml(k, l) {
    return `
      <section class="list-column" data-list-id="${esc(l.id)}" aria-label="${esc(l.name)}">
        <header class="list-column__header">
          <h2 class="list-column__name">${esc(l.name)}</h2>
          <div class="list-column__tools">
            <span class="list-column__count">${cardsOf(k, l.id).length}</span>
            <button type="button" class="list-column__menu" data-act="list-menu" aria-label="List actions">${Icons.kebab}</button>
          </div>
        </header>
        <div class="list-column__cards">${cardsOf(k, l.id).map(CardTile.html).join('')}</div>
      </section>`;
  }

  function renderSide(k) {
    const sc = scroller(k);
    const left = sc.scrollLeft;
    sc.innerHTML = openLists(k).map((l) => columnHtml(k, l)).join('');
    sc.scrollLeft = left;
    updateBanner();
  }

  function updateBanner() {
    M.el.querySelector('[data-empty]').hidden = openLists('src').length > 0;
  }

  function open(root, cfg, h) {
    const el = document.createElement('div');
    el.className = 'board merge';
    M = { el, h, sides: { src: cfg.source, tgt: cfg.target } };
    ['src', 'tgt'].forEach((k) => {
      const d = M.sides[k].data;
      d.lists = d.lists || [];
      d.cards = d.cards || [];
    });
    el.innerHTML = `
      <header class="board__header">
        <button class="btn btn--ghost board__back" data-act="exit" aria-label="Exit merge">${Icons.back}</button>
        <h1 class="board__name">Merge ${esc(cfg.source.name)} into ${esc(cfg.target.name)}</h1>
        <div class="board__tools">
          <button class="btn btn--ghost" data-act="exit">Exit merge</button>
        </div>
        <div class="board__banner board__banner--info" data-empty hidden>
          <span>Everything moved. Delete this board?</span>
          <button class="btn btn--danger btn--sm" data-act="delete-source">Delete board</button>
        </div>
      </header>
      <div class="merge__panes">
        ${['src', 'tgt'].map((k) => `
          <section class="merge__pane" data-side="${k}">
            <h2 class="merge__title">${esc(M.sides[k].name)}</h2>
            <div class="board__scroller merge__scroller" data-side="${k}"></div>
          </section>`).join('')}
      </div>`;
    root.replaceChildren(el);
    renderSide('src');
    renderSide('tgt');

    el.addEventListener('click', (e) => {
      const act = e.target.closest('[data-act]');
      if (!act) return;
      if (act.dataset.act === 'exit') h.onExit();
      else if (act.dataset.act === 'delete-source') h.onDeleteSource();
      else if (act.dataset.act === 'list-menu') listMenu(act);
    });

    Dnd.init(el, {
      itemSel: '.list-column[data-list-id]', handleSel: '.list-column__header', zoneSel: '.merge__scroller',
      scrollerSel: '.merge__scroller', axis: 'x', ignoreSel: 'button,a,input',
      cfg: {
        holdMs: ENV.TIMING.DRAG_HOLD_MS, startPx: ENV.BOARD.DRAG_START_PX, holdSlopPx: ENV.BOARD.DRAG_HOLD_SLOP_PX,
        edgePx: ENV.BOARD.AUTOSCROLL_EDGE_PX, maxPx: ENV.BOARD.AUTOSCROLL_MAX_PX,
      },
      onDrop,
    });
  }

  function listMenu(btn) {
    const from = sideOf(btn);
    const list = M.sides[from].data.lists.find((l) => l.id === btn.closest('.list-column').dataset.listId);
    if (!list) return;
    const to = other(from);
    Menu.open(btn, [{ label: `Move to “${M.sides[to].name}”`, onClick: () => moveList(list, from, to, lastPos(to)) }]);
  }

  const lastPos = (k) => {
    const ls = openLists(k);
    return Board.posBetween(ls.length ? ls[ls.length - 1] : null, null);
  };

  function onDrop(d) {
    const from = sideOf(d.fromContainer);
    const to = sideOf(d.toContainer);
    const find = (k, el) => (el ? M.sides[k].data.lists.find((l) => l.id === el.dataset.listId) : null);
    const list = find(from, d.item);
    if (!list) return;
    const prev = find(to, d.prevEl);
    const next = find(to, d.nextEl);
    if (from === to) {
      if ((!prev || prev.pos < list.pos) && (!next || list.pos < next.pos)) return;   // dropped where it sits
      reorder(list, from, Board.posBetween(prev, next));
    } else {
      moveList(list, from, to, Board.posBetween(prev, next));
    }
  }

  async function reorder(list, k, pos) {
    const old = list.pos;
    list.pos = pos;
    renderSide(k);
    try { Object.assign(list, await Api.request(EP.PROJECT_LIST(M.sides[k].id, list.id), 'PATCH', { pos })); }
    catch (e) { list.pos = old; renderSide(k); Board.fail(e, 'Could not move list'); }
  }

  async function moveList(list, from, to, pos) {
    const src = M.sides[from];
    const dst = M.sides[to];
    const cards = src.data.cards.filter((c) => c.idList === list.id);
    const oldPos = list.pos;
    src.data.lists = src.data.lists.filter((l) => l !== list);
    src.data.cards = src.data.cards.filter((c) => c.idList !== list.id);
    list.pos = pos;
    dst.data.lists.push(list);
    dst.data.cards.push(...cards);
    renderSide(from);
    renderSide(to);
    try {
      const snap = await Api.request(EP.PROJECT_LIST_MOVE(src.id, list.id), 'POST', { targetProjectId: dst.id, pos });
      if (snap && M && M.sides[to] === dst) {   // destination snapshot from the server replaces the optimistic copy
        dst.data.lists = snap.lists || [];
        dst.data.cards = snap.cards || [];
        renderSide(to);
      }
    } catch (e) {
      if (!M) return;
      list.pos = oldPos;
      dst.data.lists = dst.data.lists.filter((l) => l !== list);
      dst.data.cards = dst.data.cards.filter((c) => c.idList !== list.id);
      src.data.lists.push(list);
      src.data.cards.push(...cards);
      renderSide(from);
      renderSide(to);
      Board.fail(e, 'Could not move list');
    }
  }

  const close = () => { M = null; };

  return { open, close };
})();
