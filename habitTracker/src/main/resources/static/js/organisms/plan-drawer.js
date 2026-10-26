// "Plan with AI" drawer (#plan-drawer): describe a problem -> POST /plan creates cards,
// then optionally POST /apply schedules them. Right-side drawer, bottom sheet on phones.
window.PlanDrawer = (() => {
  const { $, esc } = Dom;
  let projectId = null;
  let onChange = () => {};

  const el = () => $('plan-drawer');

  function cardTitles(cards) {
    const list = Array.isArray(cards) ? cards : (cards && (cards.created || cards.cards)) || [];
    return list.map((c) => (typeof c === 'string' ? c : c.title || c.name || c.card || JSON.stringify(c)));
  }

  function reset() {
    $('plan-desc').value = '';
    $('plan-status').textContent = '';
    $('plan-status').className = 'planner__status';
    $('plan-result').hidden = true;
    $('plan-result').innerHTML = '';
  }

  function open(id) {
    if (projectId !== id) { projectId = id; reset(); }
    el().classList.add('open');
    $('plan-desc').focus();
  }
  function close() { el().classList.remove('open'); }
  function toggle(id) { (el().classList.contains('open') && projectId === id) ? close() : open(id); }

  async function draft(btn) {
    const status = $('plan-status');
    const result = $('plan-result');
    const description = $('plan-desc').value.trim();
    if (!description) return;
    btn.disabled = true;
    status.className = 'planner__status';
    status.textContent = 'Drafting… this can take a few minutes.';
    result.hidden = true;
    try {
      const { cards, proposal } = await Api.request(ENV.ENDPOINTS.PROJECT_PLAN(projectId), 'POST', { description });
      const titles = cardTitles(cards);
      const p = proposal || {};
      result.innerHTML = `
        <div class="planner__verdict">${esc(p.verdict || p.error || '')}</div>
        <div class="planner__list">${esc(titles.length ? titles.map((t) => `• ${t}`).join('\n') : 'No cards returned.')}</div>
        ${p.schedule ? `<div class="planner__schedule">${esc(p.schedule)}</div>` : ''}
        ${p.error ? '' : `
        <div class="planner__row">
          <div class="form-group"><label class="form-group__label">Deadline (optional)</label>
            <input type="date" class="input input--sm" id="plan-deadline"></div>
          <div class="form-group"><label class="form-group__label">Cards/day</label>
            <input type="number" class="input input--sm" id="plan-pace" value="2" min="0.5" step="0.5"></div>
          <button class="btn btn--primary btn--sm" data-act="apply">Apply schedule</button>
        </div>`}`;
      result.hidden = false;
      status.textContent = `${titles.length} cards created on the board.`;
      onChange();
    } catch (err) {
      status.classList.add('planner__status--error');
      status.textContent = err.message;
    } finally {
      btn.disabled = false;
    }
  }

  async function apply(btn) {
    const status = $('plan-status');
    const deadline = $('plan-deadline').value;
    const pace = parseFloat($('plan-pace').value) || 2;
    btn.disabled = true;
    status.className = 'planner__status';
    status.textContent = 'Applying…';
    try {
      await Api.request(ENV.ENDPOINTS.PROJECT_APPLY(projectId), 'POST', deadline ? { deadline, pace } : { pace });
      status.textContent = 'Schedule applied.';
      Toast.show('Schedule applied');
      onChange();
    } catch (err) {
      status.classList.add('planner__status--error');
      status.textContent = err.message;
      btn.disabled = false;
    }
  }

  function init(opts) {
    onChange = (opts && opts.onChange) || onChange;
    el().querySelectorAll('[data-close]').forEach((b) => b.addEventListener('click', close));
    $('plan-draft').addEventListener('click', (e) => draft(e.currentTarget));
    $('plan-result').addEventListener('click', (e) => {
      const b = e.target.closest('[data-act="apply"]');
      if (b) apply(b);
    });
  }

  const setOnChange = (fn) => { onChange = fn; };

  return { init, open, close, toggle, setOnChange };
})();
