// Read-only card detail modal (#card-modal).
window.CardModal = (() => {
  const { $, esc, safeUrl } = Dom;

  // Descriptions may carry a machine ```meta fenced block (written by the planner); hide it.
  const stripMeta = (d) => String(d || '').replace(/```meta[\s\S]*?```/gi, '').trim();

  function open(card, listName) {
    const due = CardTile.dueInfo(card);
    const b = card.badges || {};
    const labels = (card.labels || []).map((l) =>
      `<span class="tag tag--chip ${CardTile.tagClass(l.color)}">${esc(l.name || l.color || 'label')}</span>`).join('');
    const desc = stripMeta(card.desc);
    const url = safeUrl(card.url);
    $('card-modal-title').textContent = card.name;
    $('card-modal-body').innerHTML = `
      <div class="card-detail__list">in list <strong>${esc(listName)}</strong>${card.done ? ' <span class="badge badge--success">done</span>' : ''}</div>
      ${labels ? `<div class="card-detail__labels">${labels}</div>` : ''}
      <div class="card-detail__meta">
        ${due ? `<span class="badge badge--${due.tone}">${Icons.clock}Due ${esc(due.label)}</span>` : ''}
        ${b.checkItems > 0 ? `<span class="badge">${Icons.list}${esc(b.checkItemsChecked)}/${esc(b.checkItems)} checklist</span>` : ''}
      </div>
      <div class="card-detail__section">
        <div class="form-group__label">Description</div>
        ${desc ? `<div class="card-detail__desc">${esc(desc)}</div>` : '<div class="card-detail__empty">No description.</div>'}
      </div>
      ${url ? `<a class="btn btn--ghost" href="${esc(url)}" target="_blank" rel="noopener noreferrer">Open in Trello</a>` : ''}`;
    Dom.open('card-modal');
  }

  function init() {
    const m = $('card-modal');
    m.querySelectorAll('[data-close]').forEach((x) => x.addEventListener('click', () => Dom.close('card-modal')));
    m.addEventListener('click', (e) => { if (e.target === m) Dom.close('card-modal'); });
  }

  return { open, init };
})();
