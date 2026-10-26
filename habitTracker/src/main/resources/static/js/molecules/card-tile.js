// Card tile markup + shared card helpers (label color mapping, due-date state).
window.CardTile = (() => {
  const { esc } = Dom;

  // Trello color names -> semantic tag modifier (tokens only; accent is the single non-semantic hue).
  function tagClass(color) {
    switch (color) {
      case 'green': case 'lime': return 'tag--success';
      case 'red': return 'tag--danger';
      case 'yellow': case 'orange': return 'tag--warning';
      case 'blue': case 'sky': case 'purple': case 'pink': return 'tag--accent';
      default: return 'tag--muted';
    }
  }

  function dueInfo(card) {
    if (!card.due) return null;
    const t = Date.parse(card.due);
    if (!t) return null;
    const label = new Date(t).toLocaleDateString(undefined, { month: 'short', day: 'numeric' });
    let tone = 'muted';
    if (card.dueComplete) tone = 'success';
    else if (t < Date.now()) tone = 'danger';
    else if (t - Date.now() < 48 * 3600 * 1000) tone = 'warning';
    return { label, tone };
  }

  function html(card) {
    const b = card.badges || {};
    const due = dueInfo(card);
    const labels = (card.labels || []).map((l) => `<span class="tag ${tagClass(l.color)}"></span>`).join('');
    const badges = [];
    if (due) badges.push(`<span class="badge badge--${due.tone}">${Icons.clock}${esc(due.label)}</span>`);
    if (b.checkItems > 0) badges.push(`<span class="badge">${Icons.list}${esc(b.checkItemsChecked)}/${esc(b.checkItems)}</span>`);
    if (b.description) badges.push(`<span class="badge">${Icons.desc}</span>`);
    if (b.comments > 0) badges.push(`<span class="badge">${Icons.comment}${esc(b.comments)}</span>`);
    return `
      <div class="card-tile${card.done ? ' card-tile--done' : ''}" role="button" tabindex="0" data-card-id="${esc(card.id)}">
        ${labels ? `<div class="card-tile__tags">${labels}</div>` : ''}
        <div class="card-tile__title">${card.done ? `<span class="card-tile__check">${Icons.check}</span>` : ''}${esc(card.name)}</div>
        ${badges.length ? `<div class="card-tile__badges">${badges.join('')}</div>` : ''}
      </div>`;
  }

  return { html, tagClass, dueInfo };
})();
