// Editable card detail modal (#card-modal). Edits go through Board's optimistic actions; the modal
// re-renders from the (possibly rolled back) card after each one. Unsaved description text survives
// those re-renders (descDraft).
window.CardModal = (() => {
  const { $, esc, safeUrl } = Dom;
  const META_RE = /```meta[\s\S]*?```/gi;

  // Planner-written ```meta fenced blocks are hidden from the editor and re-appended unchanged on save.
  const stripMeta = (d) => String(d || '').replace(META_RE, '').trim();
  const metaBlocks = (d) => (String(d || '').match(META_RE) || []).join('\n\n');

  let cur = null; // { id, descDraft }

  const pad = (n) => String(n).padStart(2, '0');
  function dueParts(iso) {
    const t = Date.parse(iso);
    if (!t) return { date: '', time: '' };
    const d = new Date(t);
    return { date: `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`, time: `${pad(d.getHours())}:${pad(d.getMinutes())}` };
  }

  function open(card) {
    cur = { id: card.id, descDraft: null };
    render();
    Dom.open('card-modal');
  }

  function render() {
    const card = cur && Board.getCard(cur.id);
    if (!card) { Dom.close('card-modal'); return; }
    const due = CardTile.dueInfo(card);
    const b = card.badges || {};
    const labels = (card.labels || []).map((l) =>
      `<span class="tag tag--chip ${CardTile.tagClass(l.color)}">${esc(l.name || l.color || 'label')}</span>`).join('');
    const desc = cur.descDraft != null ? cur.descDraft : stripMeta(card.desc);
    const dirty = cur.descDraft != null && cur.descDraft !== stripMeta(card.desc);
    const dp = dueParts(card.due);
    const url = safeUrl(card.url);
    const lists = Board.openLists();

    $('card-modal-title').textContent = `in list ${Board.listName(card.idList)}`;
    $('card-modal-body').innerHTML = `
      <input class="input card-detail__title" id="cd-title" value="${esc(card.name)}" maxlength="16384" aria-label="Card title">
      <div class="card-detail__actions">
        <button type="button" class="btn ${card.done ? 'btn--ghost' : 'btn--primary'}" data-cd="${card.done ? 'reopen' : 'complete'}">${Icons.check}${card.done ? 'Reopen' : 'Mark complete'}</button>
        <button type="button" class="btn btn--danger" data-cd="archive">Archive</button>
      </div>
      ${labels ? `<div class="card-detail__labels">${labels}</div>` : ''}
      <div class="card-detail__meta">
        ${card.done ? '<span class="badge badge--success">done</span>' : ''}
        ${due ? `<span class="badge badge--${due.tone}">${Icons.clock}Due ${esc(due.label)}</span>` : ''}
        ${b.checkItems > 0 ? `<span class="badge">${Icons.list}${esc(b.checkItemsChecked)}/${esc(b.checkItems)} checklist</span>` : ''}
      </div>
      <div class="form-group">
        <label class="form-group__label" for="cd-date">Due</label>
        <div class="card-detail__due">
          <input class="input" type="date" id="cd-date" value="${esc(dp.date)}" aria-label="Due date">
          <input class="input" type="time" id="cd-time" value="${esc(dp.time)}" aria-label="Due time">
          <button type="button" class="btn btn--ghost" data-cd="clear-due" ${card.due ? '' : 'disabled'}>Clear</button>
        </div>
        <span class="form-group__hint">Time is optional (defaults to ${esc(ENV.BOARD.DEFAULT_DUE_TIME)}).</span>
      </div>
      <div class="form-group">
        <label class="form-group__label" for="cd-desc">Description</label>
        <textarea class="input card-detail__desc-input" id="cd-desc" rows="6" placeholder="Add a description…">${esc(desc)}</textarea>
        <div class="card-detail__desc-actions" ${dirty ? '' : 'hidden'}>
          <button type="button" class="btn btn--primary btn--sm" data-cd="save-desc">Save</button>
          <button type="button" class="btn btn--ghost btn--sm" data-cd="cancel-desc">Cancel</button>
        </div>
      </div>
      <div class="form-group">
        <label class="form-group__label" for="cd-move">Move to…</label>
        <select class="input" id="cd-move" aria-label="Move to list">
          ${lists.map((l) => `<option value="${esc(l.id)}" ${l.id === card.idList ? 'selected' : ''}>${esc(l.name)}</option>`).join('')}
        </select>
      </div>
      ${url ? `<a class="btn btn--ghost" href="${esc(url)}" target="_blank" rel="noopener noreferrer">Open in Trello</a>` : ''}`;
  }

  // Run an optimistic action: the card is already mutated when the promise is returned, so render
  // immediately, then again once the server answer (or rollback) lands.
  async function act(fn) {
    const id = cur.id;
    const p = fn();
    render();
    await p;
    if (cur && cur.id === id) render();
  }

  function saveTitle(input) {
    const card = Board.getCard(cur.id);
    const v = input.value.trim();
    if (!v) { input.value = card.name; return; }
    if (v !== card.name) act(() => Board.updateCard(cur.id, { name: v }));
  }

  function saveDue() {
    const date = $('cd-date').value;
    if (!date) return;
    const time = $('cd-time').value || ENV.BOARD.DEFAULT_DUE_TIME;
    const iso = new Date(`${date}T${time}`).toISOString();
    act(() => Board.updateCard(cur.id, { due: iso }));
  }

  function onClick(e) {
    const b = e.target.closest('[data-cd]');
    if (!b || !cur) return;
    const card = Board.getCard(cur.id);
    switch (b.dataset.cd) {
      case 'complete': act(() => Board.completeCard(cur.id)); break;
      case 'reopen': act(() => Board.reopenCard(cur.id)); break;
      case 'archive': Board.archiveCard(cur.id); close(); break;
      case 'clear-due': act(() => Board.updateCard(cur.id, { due: null, dueComplete: false })); break;
      case 'cancel-desc': cur.descDraft = null; render(); break;
      case 'save-desc': {
        const text = $('cd-desc').value.trim();
        const full = [text, metaBlocks(card.desc)].filter(Boolean).join('\n\n');
        cur.descDraft = null;
        act(() => Board.updateCard(cur.id, { desc: full }));
        break;
      }
      default:
    }
  }

  function onInput(e) {
    if (e.target.id !== 'cd-desc') return;
    const card = Board.getCard(cur.id);
    cur.descDraft = e.target.value;
    e.target.parentElement.querySelector('.card-detail__desc-actions').hidden = cur.descDraft === stripMeta(card.desc);
  }

  function onChange(e) {
    if (!cur) return;
    if (e.target.id === 'cd-move') act(() => Board.moveCardToList(cur.id, e.target.value));
    else if (e.target.id === 'cd-date' || e.target.id === 'cd-time') {
      if (e.target.id === 'cd-date' && !e.target.value) act(() => Board.updateCard(cur.id, { due: null, dueComplete: false }));
      else saveDue();
    }
  }

  /** Unsaved edits: description draft differing from the card, or a retitled-but-not-yet-saved title. */
  function isDirty() {
    if (!cur) return false;
    const card = Board.getCard(cur.id);
    if (!card) return false;
    const title = $('cd-title');
    return (cur.descDraft != null && cur.descDraft !== stripMeta(card.desc)) || (!!title && title.value.trim() !== card.name);
  }

  /** After a remote board update: re-render the open card if it changed; close it if it was deleted/archived. */
  function onRemote(changed) {
    if (!cur || !changed.has(cur.id)) return;
    const card = Board.getCard(cur.id);
    if (!card || card.closed) { close(); Toast.show('This card was deleted or archived in Trello'); return; }
    render();
  }

  function close() { Dom.close('card-modal'); cur = null; }

  function init() {
    const m = $('card-modal');
    m.querySelectorAll('[data-close]').forEach((x) => x.addEventListener('click', close));
    m.addEventListener('click', (e) => { if (e.target === m) close(); });
    const body = $('card-modal-body');
    body.addEventListener('click', onClick);
    body.addEventListener('input', onInput);
    body.addEventListener('change', onChange);
    body.addEventListener('focusout', (e) => { if (cur && e.target.id === 'cd-title') saveTitle(e.target); });
    body.addEventListener('keydown', (e) => {
      if (e.target.id !== 'cd-title') return;
      if (e.key === 'Enter') { e.preventDefault(); e.target.blur(); }
      else if (e.key === 'Escape') { e.stopPropagation(); e.target.value = Board.getCard(cur.id).name; e.target.blur(); }
    });
  }

  return { open, init, isDirty, onRemote };
})();
