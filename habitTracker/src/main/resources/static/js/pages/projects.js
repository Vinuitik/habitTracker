// Projects page: hash router (#/ index, #/b/<id> board), index grid, create / import / delete.
(() => {
  const { $, esc } = Dom;
  const view = () => $('projects-view');
  let seq = 0;          // guards against out-of-order async renders on fast navigation
  let projects = [];
  let firstRender = true;

  const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
  const isCurrent = (my) => my === seq;

  // Trello not connected: show the connect panel, retry on success.
  function showConnect(err, retry) {
    $('projects-page').classList.remove('projects-page--board');
    ConnectPanel.render(view(), err, retry);
  }

  // ── router ────────────────────────────────────────────────────────────
  async function route() {
    const my = ++seq;
    const m = location.hash.match(/^#\/b\/(.+)$/);
    const target = m ? () => showBoard(decodeURIComponent(m[1]), my) : () => showIndex(my);
    const v = view();
    if (!firstRender) {
      v.classList.add('projects-view--fading');
      await sleep(ENV.TIMING.VIEW_FADE_MS);
      if (!isCurrent(my)) return;
    }
    firstRender = false;
    stopPoll();
    PlanDrawer.close();
    $('projects-page').classList.toggle('projects-page--board', !!m);
    v.innerHTML = '<div class="projects-empty">Loading…</div>';
    window.scrollTo(0, 0);
    v.classList.remove('projects-view--fading');
    await target();
  }

  // ── index ─────────────────────────────────────────────────────────────
  function tileHtml(p) {
    const has = p.cardCount != null;
    const done = p.doneCount || 0;
    return `
      <div class="board-tile" role="link" tabindex="0" data-id="${esc(p.id)}">
        <div class="board-tile__top">
          <div class="board-tile__name">${esc(p.name)}</div>
          ${p.imported ? '<span class="badge">imported</span>' : ''}
        </div>
        <div class="board-tile__meta">${has ? `${p.cardCount} cards · ${done} done` : 'not synced yet'}</div>
        <progress class="progress board-tile__progress" max="${has ? Math.max(p.cardCount, 1) : 1}" value="${has ? done : 0}"></progress>
        <button class="board-tile__menu" data-menu aria-label="Board actions">${Icons.kebab}</button>
      </div>`;
  }

  function renderIndex() {
    view().innerHTML = `
      <div class="projects-page__header">
        <h1 class="projects-page__title">Projects</h1>
        <div class="projects-page__actions">
          <button class="btn btn--ghost" id="import-btn">Import from Trello</button>
          <button class="btn btn--primary" id="new-project-btn">New board</button>
        </div>
      </div>
      <div class="board-grid">
        ${projects.map(tileHtml).join('')}
        <button class="board-tile board-tile--new" id="new-tile">${Icons.plus}Create new board</button>
      </div>`;
    $('import-btn').addEventListener('click', openImport);
    $('new-project-btn').addEventListener('click', openCreate);
    $('new-tile').addEventListener('click', openCreate);
  }

  async function showIndex(my) {
    try {
      const data = await Api.request(ENV.ENDPOINTS.PROJECTS);
      if (!isCurrent(my)) return;
      projects = data || [];
    } catch (e) {
      if (!isCurrent(my)) return;
      if (Api.isTrelloAuth(e)) return showConnect(e, route);
      view().innerHTML = `<div class="projects-empty">${esc(e.message)}</div>`;
      return;
    }
    renderIndex();
  }

  const refreshIndex = () => showIndex(++seq);

  function tileFrom(e) {
    const t = e.target.closest('.board-tile[data-id]');
    return t ? { tile: t, project: projects.find((p) => p.id === t.dataset.id) } : null;
  }

  function onViewClick(e) {
    const hit = tileFrom(e);
    if (!hit || !hit.project) return;
    const kebab = e.target.closest('[data-menu]');
    if (kebab) {
      e.stopPropagation();
      Menu.open(kebab, [{ label: 'Delete', danger: true, onClick: () => startDelete(hit.project) }]);
      return;
    }
    location.hash = `#/b/${encodeURIComponent(hit.project.id)}`;
  }
  function onViewKey(e) {
    if (e.key !== 'Enter' || e.target.closest('[data-menu]')) return;
    const hit = tileFrom(e);
    if (hit && hit.project && e.target === hit.tile) location.hash = `#/b/${encodeURIComponent(hit.project.id)}`;
  }

  // ── board ─────────────────────────────────────────────────────────────
  async function showBoard(id, my, refresh = false) {
    let data; let stale = false;
    try {
      const r = await Api.raw(ENV.ENDPOINTS.PROJECT_BOARD(id, refresh));
      data = r.data;
      stale = r.res.headers.get('X-Stale') === 'true';
    } catch (e) {
      if (!isCurrent(my)) return;
      if (Api.isTrelloAuth(e)) return showConnect(e, route);
      view().innerHTML = `<div class="projects-empty">${esc(e.message)}<br><a class="link" href="#/">Back to boards</a></div>`;
      return;
    }
    if (!isCurrent(my)) return;
    Board.render(view(), data, stale, {
      projectId: id,
      onAuth: (err) => showConnect(err, route),
      onRefresh: async (btn) => {
        btn.disabled = true;
        await showBoard(id, seq, true);
        Toast.show('Board refreshed');
      },
      onPlan: () => PlanDrawer.toggle(id),
      onUnlink: () => unlinkMissing(id),
      onCard: (card) => CardModal.open(card),
    });
    startPoll(id, my);
    PlanDrawer.setOnChange(() => { if (location.hash === `#/b/${encodeURIComponent(id)}`) showBoard(id, seq, true); });
  }

  // Missing board: reuse the normal two-step delete (imported projects are only unlinked).
  async function unlinkMissing(id) {
    try {
      const list = await Api.request(ENV.ENDPOINTS.PROJECTS);
      const p = (list || []).find((x) => x.id === id);
      if (p) startDelete(p);
    } catch (e) {
      Toast.show(e.message);
    }
  }

  // ── live sync: poll the open board for changes made directly in Trello ──
  let poll = null; // { id, my, timer, busy }

  function stopPoll() {
    if (poll) clearInterval(poll.timer);
    poll = null;
  }

  function startPoll(id, my) {
    stopPoll();
    poll = { id, my, busy: false, timer: setInterval(pollBoard, ENV.TIMING.BOARD_POLL_MS) };
  }

  async function pollBoard() {
    const p = poll;
    if (!p || p.busy || !isCurrent(p.my) || document.visibilityState !== 'visible' || Board.isBusy()) return;
    p.busy = true;
    const mark = Board.stamp();
    try {
      const r = await Api.raw(ENV.ENDPOINTS.PROJECT_BOARD(p.id, false));
      // Drop the result if we navigated away, or a write/drag/edit started while the request was in flight.
      if (poll !== p || !isCurrent(p.my) || mark !== Board.stamp() || Board.isBusy()) return;
      const changed = Board.apply(r.data, r.res.headers.get('X-Stale') === 'true');
      CardModal.onRemote(changed);
    } catch (e) {
      if (poll === p && Api.isTrelloAuth(e)) { stopPoll(); showConnect(e, route); }
      // other errors: stay silent, next tick retries
    } finally {
      p.busy = false;
    }
  }

  // ── create ────────────────────────────────────────────────────────────
  function openCreate() {
    $('create-form').reset();
    $('create-error').hidden = true;
    Dom.open('create-modal');
    $('p-name').focus();
  }

  $('create-form').addEventListener('submit', async (e) => {
    e.preventDefault();
    const btn = $('create-submit');
    btn.disabled = true;
    btn.textContent = 'Creating…';
    $('create-error').hidden = true;
    try {
      await Api.request(ENV.ENDPOINTS.PROJECTS, 'POST', {
        name: $('p-name').value.trim(),
        description: $('p-desc').value.trim(),
        trelloBoardId: $('p-board').value.trim(),
      });
      Dom.close('create-modal');
      Toast.show('Project created');
      refreshIndex();
    } catch (err) {
      if (Api.isTrelloAuth(err)) { Dom.close('create-modal'); showConnect(err, route); }
      else { $('create-error').textContent = err.message; $('create-error').hidden = false; }
    } finally {
      btn.disabled = false;
      btn.textContent = 'Create';
    }
  });

  // ── import ────────────────────────────────────────────────────────────
  async function openImport() {
    const body = $('import-list');
    const submit = $('import-submit');
    submit.disabled = true;
    $('import-error').hidden = true;
    body.innerHTML = '<div class="projects-empty">Loading boards…</div>';
    Dom.open('import-modal');
    try {
      const boards = await Api.request(ENV.ENDPOINTS.PROJECTS_TRELLO_BOARDS);
      if (!boards.length) { body.innerHTML = '<div class="projects-empty">No Trello boards found.</div>'; return; }
      body.innerHTML = boards.map((b) => `
        <label class="check-row${b.imported ? ' check-row--disabled' : ''}">
          <input type="checkbox" value="${esc(b.id)}" ${b.imported ? 'disabled' : ''}>
          <span class="check-row__name">${esc(b.name)}</span>
          ${b.imported ? '<span class="badge">imported</span>' : ''}
        </label>`).join('');
    } catch (err) {
      if (Api.isTrelloAuth(err)) { Dom.close('import-modal'); showConnect(err, route); return; }
      body.innerHTML = `<div class="projects-empty">${esc(err.message)}</div>`;
    }
  }

  $('import-list').addEventListener('change', () => {
    $('import-submit').disabled = !$('import-list').querySelector('input:checked');
  });

  $('import-submit').addEventListener('click', async () => {
    const ids = [...$('import-list').querySelectorAll('input:checked')].map((i) => i.value);
    if (!ids.length) return;
    const btn = $('import-submit');
    btn.disabled = true;
    $('import-error').hidden = true;
    try {
      await Api.request(ENV.ENDPOINTS.PROJECTS_IMPORT, 'POST', { trelloBoardIds: ids });
      Dom.close('import-modal');
      Toast.show(`Imported ${ids.length} board${ids.length > 1 ? 's' : ''}`);
      refreshIndex();
    } catch (err) {
      $('import-error').textContent = err.message;
      $('import-error').hidden = false;
      btn.disabled = false;
    }
  });

  // ── two-step delete ───────────────────────────────────────────────────
  let del = null; // { project, token, timer }

  function resetDelete() {
    if (del && del.timer) clearInterval(del.timer);
    del = null;
  }

  function startDelete(project) {
    resetDelete();
    del = { project, token: null, timer: null };
    $('delete-title').textContent = `Delete “${project.name}”`;
    $('delete-warning').innerHTML = project.imported
      ? 'Unlinks the project; <strong>your Trello board is kept</strong>.'
      : 'This deletes the project <strong>and its Trello board</strong>. All cards on the board are lost. This cannot be undone.';
    $('delete-submit').textContent = project.imported ? 'Unlink' : 'Delete forever';
    $('delete-step-1').hidden = false;
    $('delete-step-2').hidden = true;
    $('delete-error').hidden = true;
    $('delete-confirm-name').value = '';
    $('delete-submit').disabled = true;
    Dom.open('delete-modal');
  }

  $('delete-continue').addEventListener('click', async () => {
    const btn = $('delete-continue');
    btn.disabled = true;
    try {
      const { token, expiresInSeconds } = await Api.request(ENV.ENDPOINTS.PROJECT_DELETE_REQUEST(del.project.id), 'POST');
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
    const imported = del.project.imported;
    try {
      await Api.request(ENV.ENDPOINTS.PROJECT_DELETE(del.project.id, del.token), 'DELETE');
      resetDelete();
      Dom.close('delete-modal');
      Toast.show(imported ? 'Project unlinked' : 'Project and board deleted');
      if (/^#\/b\//.test(location.hash)) location.hash = '#/'; else refreshIndex();
    } catch (err) {
      $('delete-error').textContent = err.message;
      $('delete-error').hidden = false;
    }
  });

  // ── wiring ────────────────────────────────────────────────────────────
  document.querySelectorAll('.modal-overlay').forEach((m) => {
    m.querySelectorAll('[data-close]').forEach((b) => b.addEventListener('click', () => m.classList.remove('open')));
  });
  document.querySelectorAll('#delete-modal [data-close]').forEach((b) => b.addEventListener('click', resetDelete));

  (async () => {
    if (!(await checkAuth())) return;
    initTopbar(ENV.ROUTES.PROJECTS);
    CardModal.init();
    PlanDrawer.init();
    view().addEventListener('click', onViewClick);
    view().addEventListener('keydown', onViewKey);
    window.addEventListener('hashchange', route);
    window.addEventListener('focus', pollBoard);
    document.addEventListener('visibilitychange', pollBoard);
    route();
  })();
})();
