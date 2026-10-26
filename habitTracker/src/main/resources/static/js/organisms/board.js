// Board view: sticky header + horizontal scroller of list columns. Pure rendering; the page
// script owns fetching and passes handlers.
window.Board = (() => {
  const { esc, safeUrl, timeAgo } = Dom;

  function render(root, data, stale, h) {
    const prevScroll = root.querySelector('.board__scroller')?.scrollLeft || 0;
    const lists = (data.lists || []).filter((l) => !l.closed).sort((a, b) => a.pos - b.pos);
    const cards = (data.cards || []).filter((c) => !c.closed);
    const cardById = new Map(cards.map((c) => [c.id, c]));
    const listById = new Map(lists.map((l) => [l.id, l]));
    const url = safeUrl(data.url);

    const cols = lists.map((l) => {
      const lc = cards.filter((c) => c.idList === l.id).sort((a, b) => a.pos - b.pos);
      return `
        <section class="list-column" aria-label="${esc(l.name)}">
          <header class="list-column__header">
            <h2 class="list-column__name">${esc(l.name)}</h2>
            <span class="list-column__count">${lc.length}</span>
          </header>
          <div class="list-column__cards">
            ${lc.length ? lc.map(CardTile.html).join('') : '<div class="list-column__empty">No cards</div>'}
          </div>
        </section>`;
    }).join('');

    const el = document.createElement('div');
    el.className = 'board';
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
      <div class="board__scroller">${cols || '<div class="projects-empty">This board has no lists.</div>'}</div>`;

    el.addEventListener('click', (e) => {
      const act = e.target.closest('[data-act]');
      if (act) { (act.dataset.act === 'refresh' ? h.onRefresh : h.onPlan)(act); return; }
      const tile = e.target.closest('.card-tile');
      if (tile) openTile(tile);
    });
    el.addEventListener('keydown', (e) => {
      if (e.key !== 'Enter' && e.key !== ' ') return;
      const tile = e.target.closest('.card-tile');
      if (tile) { e.preventDefault(); openTile(tile); }
    });
    function openTile(tile) {
      const c = cardById.get(tile.dataset.cardId);
      if (c) h.onCard(c, listById.get(c.idList)?.name || '');
    }

    root.replaceChildren(el);
    el.querySelector('.board__scroller').scrollLeft = prevScroll;
    return el;
  }

  return { render };
})();
