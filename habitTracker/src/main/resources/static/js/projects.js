(() => {
  const $ = (id) => document.getElementById(id);
  const esc = (s) => String(s ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
  const csrf = () => {
    const m = document.cookie.split('; ').find((r) => r.startsWith('XSRF-TOKEN='));
    return m ? decodeURIComponent(m.split('=')[1]) : '';
  };

  async function api(url, method = 'GET', body) {
    const res = await fetch(url, {
      method,
      credentials: 'include',
      headers: { 'Content-Type': 'application/json', 'X-XSRF-TOKEN': csrf() },
      body: body === undefined ? undefined : JSON.stringify(body),
    });
    const data = res.status === 204 ? null : await res.json().catch(() => null);
    if (!res.ok) throw new Error((data && data.error) || `Request failed (${res.status})`);
    return data;
  }

  const open = (id) => $(id).classList.add('open');
  const close = (id) => $(id).classList.remove('open');
  document.querySelectorAll('.modal-overlay').forEach((m) => {
    m.querySelectorAll('[data-close]').forEach((b) => b.addEventListener('click', () => m.classList.remove('open')));
  });

  let projects = [];

  // ── list ──────────────────────────────────────────────────────────────
  function render() {
    const list = $('projects-list');
    if (!projects.length) {
      list.innerHTML = '<div class="projects-empty">No projects yet. Create one to get a Trello board and an AI planner.</div>';
      return;
    }
    list.innerHTML = projects.map((p) => `
      <article class="project" data-id="${esc(p.id)}">
        <div class="project__head">
          <div class="project__info">
            <div class="project__name">${esc(p.name)}</div>
            ${p.description ? `<div class="project__desc">${esc(p.description)}</div>` : ''}
            <div class="project__meta">board ${esc(p.trelloBoardId || '—')}</div>
          </div>
          <div class="project__actions">
            <button class="btn btn--ghost btn--sm" data-act="plan">Plan</button>
            <button class="btn btn--ghost btn--sm" data-act="delete">Delete</button>
          </div>
        </div>
        <div class="planner" hidden>
          <div class="form-group">
            <label class="form-group__label">Describe a problem</label>
            <textarea class="input planner__desc" rows="3" placeholder="What do you want to build or solve? The agent breaks it into cards."></textarea>
          </div>
          <div class="planner__row">
            <button class="btn btn--primary btn--sm" data-act="draft">Draft plan</button>
            <span class="planner__status"></span>
          </div>
          <div class="planner__result" hidden></div>
        </div>
      </article>`).join('');
  }

  async function load() {
    try {
      projects = await api(ENV.ENDPOINTS.PROJECTS);
    } catch (e) {
      $('projects-list').innerHTML = `<div class="projects-empty">${esc(e.message)}</div>`;
      return;
    }
    render();
  }

  // ── create ────────────────────────────────────────────────────────────
  $('new-project-btn').addEventListener('click', () => {
    $('create-form').reset();
    $('create-error').hidden = true;
    open('create-modal');
    $('p-name').focus();
  });

  $('create-form').addEventListener('submit', async (e) => {
    e.preventDefault();
    const btn = $('create-submit');
    btn.disabled = true;
    btn.textContent = 'Creating…';
    $('create-error').hidden = true;
    try {
      await api(ENV.ENDPOINTS.PROJECTS, 'POST', {
        name: $('p-name').value.trim(),
        description: $('p-desc').value.trim(),
        trelloBoardId: $('p-board').value.trim(),
      });
      close('create-modal');
      Toast.show('Project created');
      await load();
    } catch (err) {
      $('create-error').textContent = err.message;
      $('create-error').hidden = false;
    } finally {
      btn.disabled = false;
      btn.textContent = 'Create';
    }
  });

  // ── card actions (delegated) ──────────────────────────────────────────
  $('projects-list').addEventListener('click', (e) => {
    const btn = e.target.closest('[data-act]');
    if (!btn) return;
    const card = btn.closest('.project');
    const project = projects.find((p) => p.id === card.dataset.id);
    if (!project) return;
    ({ plan: togglePlanner, draft: draftPlan, apply: applyPlan, delete: startDelete })[btn.dataset.act]?.(card, project, btn);
  });

  function togglePlanner(card) {
    const panel = card.querySelector('.planner');
    panel.hidden = !panel.hidden;
    if (!panel.hidden) panel.querySelector('.planner__desc').focus();
  }

  // ── plan / apply ──────────────────────────────────────────────────────
  function cardTitles(cards) {
    const list = Array.isArray(cards) ? cards : (cards && (cards.created || cards.cards)) || [];
    return list.map((c) => (typeof c === 'string' ? c : c.title || c.name || c.card || JSON.stringify(c)));
  }

  async function draftPlan(card, project, btn) {
    const panel = card.querySelector('.planner');
    const status = panel.querySelector('.planner__status');
    const result = panel.querySelector('.planner__result');
    const description = panel.querySelector('.planner__desc').value.trim();
    if (!description) return;
    btn.disabled = true;
    status.className = 'planner__status';
    status.textContent = 'Drafting… this can take a few minutes.';
    result.hidden = true;
    try {
      const { cards, proposal } = await api(`${ENV.ENDPOINTS.PROJECT(project.id)}/plan`, 'POST', { description });
      const titles = cardTitles(cards);
      const p = proposal || {};
      result.innerHTML = `
        <div class="planner__verdict">${esc(p.verdict || p.error || '')}</div>
        <div class="planner__list">${esc(titles.length ? titles.map((t) => `• ${t}`).join('\n') : 'No cards returned.')}</div>
        ${p.schedule ? `<div class="planner__schedule">${esc(p.schedule)}</div>` : ''}
        ${p.error ? '' : `
        <div class="planner__row">
          <div class="form-group"><label class="form-group__label">Deadline (optional)</label>
            <input type="date" class="input input--sm planner__deadline"></div>
          <div class="form-group"><label class="form-group__label">Cards/day</label>
            <input type="number" class="input input--sm planner__pace" value="2" min="0.5" step="0.5"></div>
          <button class="btn btn--primary btn--sm" data-act="apply">Apply schedule</button>
        </div>`}`;
      result.hidden = false;
      status.textContent = `${titles.length} cards created on the board.`;
    } catch (err) {
      status.classList.add('planner__status--error');
      status.textContent = err.message;
    } finally {
      btn.disabled = false;
    }
  }

  async function applyPlan(card, project, btn) {
    const panel = card.querySelector('.planner');
    const status = panel.querySelector('.planner__status');
    const deadline = panel.querySelector('.planner__deadline').value;
    const pace = parseFloat(panel.querySelector('.planner__pace').value) || 2;
    btn.disabled = true;
    status.className = 'planner__status';
    status.textContent = 'Applying…';
    try {
      await api(`${ENV.ENDPOINTS.PROJECT(project.id)}/apply`, 'POST', deadline ? { deadline, pace } : { pace });
      status.textContent = 'Schedule applied.';
      Toast.show('Schedule applied');
    } catch (err) {
      status.classList.add('planner__status--error');
      status.textContent = err.message;
      btn.disabled = false;
    }
  }

  // ── two-step delete ───────────────────────────────────────────────────
  let del = null; // { project, token, timer }

  function resetDelete() {
    if (del && del.timer) clearInterval(del.timer);
    del = null;
  }
  document.querySelectorAll('#delete-modal [data-close]').forEach((b) => b.addEventListener('click', resetDelete));

  function startDelete(card, project) {
    resetDelete();
    del = { project, token: null, timer: null };
    $('delete-title').textContent = `Delete “${project.name}”`;
    $('delete-step-1').hidden = false;
    $('delete-step-2').hidden = true;
    $('delete-error').hidden = true;
    $('delete-confirm-name').value = '';
    $('delete-submit').disabled = true;
    open('delete-modal');
  }

  $('delete-continue').addEventListener('click', async () => {
    const btn = $('delete-continue');
    btn.disabled = true;
    try {
      const { token, expiresInSeconds } = await api(`${ENV.ENDPOINTS.PROJECT(del.project.id)}/delete-request`, 'POST');
      del.token = token;
      let left = expiresInSeconds || 60;
      const tick = () => {
        $('delete-countdown').textContent = left > 0 ? `Confirmation expires in ${left}s` : 'Expired — close and start again.';
        if (left <= 0) { clearInterval(del.timer); $('delete-submit').disabled = true; }
        left -= 1;
      };
      tick();
      del.timer = setInterval(tick, 1000);
      $('delete-step-1').hidden = true;
      $('delete-step-2').hidden = false;
      $('delete-confirm-name').focus();
    } catch (err) {
      Toast.show(err.message);
    } finally {
      btn.disabled = false;
    }
  });

  $('delete-confirm-name').addEventListener('input', () => {
    $('delete-submit').disabled = !del || $('delete-confirm-name').value.trim() !== del.project.name;
  });

  $('delete-step-2').addEventListener('submit', async (e) => {
    e.preventDefault();
    if (!del) return;
    $('delete-submit').disabled = true;
    try {
      await api(`${ENV.ENDPOINTS.PROJECT(del.project.id)}?token=${encodeURIComponent(del.token)}`, 'DELETE');
      resetDelete();
      close('delete-modal');
      Toast.show('Project and board deleted');
      await load();
    } catch (err) {
      $('delete-error').textContent = err.message;
      $('delete-error').hidden = false;
    }
  });

  (async () => {
    if (!(await checkAuth())) return;
    initTopbar(ENV.ROUTES.PROJECTS);
    load();
  })();
})();
