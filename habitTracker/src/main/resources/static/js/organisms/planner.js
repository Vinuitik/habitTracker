// Full-screen "Plan with AI" planner (#planner). Brief → AI asks questions → user answers →
// "Lock decisions & draft" → editable draft (lists, cards, importance) + week strip → "Add to board".
// Only the Add to board click writes to Trello. The AI's work steps stream in by polling
// ENV.ENDPOINTS.PLANNER_POLL while state === 'running'. ENV.FEATURES.PLANNER_MOCK swaps the API for
// js/organisms/planner-mock.js.
window.Planner = (() => {
  const { $, esc } = Dom;
  const E = ENV.ENDPOINTS;
  const FOCUSABLE = 'a[href], button:not([disabled]), input:not([disabled]), textarea:not([disabled]), select:not([disabled]), [tabindex]:not([tabindex="-1"])';
  const DAY_NAMES = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat'];
  const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];
  const MAX_DAYS = 62;  // week strip span cap

  const Real = {
    start: (pid, body) => Api.request(E.PLANNER_START(pid), 'POST', body),
    poll: (pid, conv, after) => Api.request(E.PLANNER_POLL(pid, conv, after)),
    lock: (pid, conv) => Api.request(E.PLANNER_LOCK(pid, conv), 'POST'),
    apply: (pid, conv, body) => Api.request(E.PLANNER_APPLY(pid, conv), 'POST', body),
  };
  const api = () => (ENV.FEATURES.PLANNER_MOCK && window.PlannerMock ? window.PlannerMock : Real);
  const isOffline = (e) => !!e && (e.status === 409 || e.status === 503) && e.code === 'runner_offline';

  let S = null;            // conversation state for S.projectId (survives hide(), dropped by discard())
  let gen = 0;             // bumped on discard: late responses from an old conversation are ignored
  let onChange = () => {};
  let opener = null;
  let pollTimer = null;
  let tickTimer = null;
  let keySeq = 0;

  const el = () => $('planner');
  const isOpen = () => !el().hidden;

  function fresh(projectId) {
    return {
      projectId, conv: null, state: 'idle', nextSeq: 0, messages: [], questions: [],
      jobs: [],            // [{at: messages.length when started, kind: 'chat'|'lock', events: [{seq,kind,text,t}]}]
      lastJob: null, draft: null, seen: new Set(), offline: false, applying: false,
      error: '', fieldsKey: null, pollErrors: 0,
    };
  }

  // ── open / close ────────────────────────────────────────────────────
  function open(projectId, from) {
    if (!S || S.projectId !== projectId) { discardState(); S = fresh(projectId); }
    opener = from || document.activeElement;
    el().hidden = false;
    document.body.classList.add('planner-open');
    $('planner-pace').value = $('planner-pace').value || ENV.PLANNER.DEFAULT_PACE;
    renderAll();
    if (S.state === 'running') schedulePoll(0);
    const first = $('planner-fields').querySelector('textarea:not([disabled])');
    (first || $('planner-title')).focus();
  }

  // Route change: hide without discarding (reopening the same project resumes).
  function hide() {
    if (!isOpen()) return;
    el().hidden = true;
    $('planner-confirm').hidden = true;
    document.body.classList.remove('planner-open');
    stopTick();
  }

  const hasContent = () => !!S && (S.messages.length > 0 || cardCount() > 0);

  function requestClose() {
    if (!hasContent()) { discard(); return; }
    $('planner-confirm-text').textContent = cardCount() > 0
      ? "Discard this draft? It hasn't been added to the board."
      : 'Discard this conversation?';
    $('planner-confirm').hidden = false;
    $('planner-confirm').querySelector('[data-pl="keep"]').focus();
  }

  function discardState() {
    gen += 1;
    clearTimeout(pollTimer);
    stopTick();
    S = null;
    $('planner-fields').innerHTML = '';
    $('planner-deadline').value = '';
  }

  function discard() {
    const pid = S && S.projectId;
    discardState();
    S = pid ? fresh(pid) : null;
    hide();
    if (opener && document.contains(opener)) opener.focus();
  }

  // ── conversation calls ──────────────────────────────────────────────
  async function startJob(kind, message) {
    const s = S; const my = gen;
    const prev = { state: s.state, questions: s.questions };
    if (message) s.messages.push({ role: 'user', text: message });
    s.jobs.push({ at: s.messages.length, kind, events: [] });
    Object.assign(s, { state: 'running', questions: [], lastJob: kind, offline: false, error: '', pollErrors: 0 });
    setFieldsDisabled(true);
    renderAll();
    try {
      const r = kind === 'lock'
        ? await api().lock(s.projectId, s.conv)
        : await api().start(s.projectId, s.conv ? { message, conversationId: s.conv } : { message });
      if (my !== gen) return;
      s.conv = r.conversationId || s.conv;
      $('planner-fields').querySelectorAll('textarea').forEach((t) => { t.value = ''; });
      schedulePoll();
    } catch (e) {
      if (my !== gen) return;
      // Roll back the optimistic turn; the typed text is still in the fields.
      if (message) s.messages.pop();
      s.jobs.pop();
      Object.assign(s, prev);
      if (isOffline(e)) s.offline = true; else s.error = e.message;
      renderAll();
    }
  }

  function schedulePoll(delay = ENV.TIMING.PLANNER_POLL_MS) {
    clearTimeout(pollTimer);
    pollTimer = setTimeout(poll, delay);
  }

  async function poll() {
    const s = S; const my = gen;
    if (!s || !s.conv || s.state !== 'running') return;
    let res;
    try {
      res = await api().poll(s.projectId, s.conv, s.nextSeq);
    } catch (e) {
      if (my !== gen) return;
      if (isOffline(e)) { Object.assign(s, { offline: true, state: 'failed' }); renderAll(); return; }
      s.pollErrors += 1;
      if (s.pollErrors < 5) { schedulePoll(ENV.TIMING.PLANNER_POLL_MS * 2 ** s.pollErrors); return; }
      Object.assign(s, { state: 'failed', error: e.message });
      renderAll();
      return;
    }
    if (my !== gen) return;
    s.pollErrors = 0;
    ingest(s, res || {});
    if (s.state === 'running') schedulePoll();
  }

  function ingest(s, res) {
    const job = s.jobs[s.jobs.length - 1] || (s.jobs.push({ at: s.messages.length, kind: 'chat', events: [] }), s.jobs[0]);
    (res.events || []).forEach((ev) => {
      if (ev.seq < s.nextSeq) return;
      job.events.push({ seq: ev.seq, kind: ev.kind, text: ev.text, t: Date.now() });
    });
    const maxSeq = (res.events || []).reduce((m, ev) => Math.max(m, ev.seq + 1), s.nextSeq);
    s.nextSeq = res.nextSeq != null ? res.nextSeq : maxSeq;
    if (res.usage) job.usage = res.usage;   // only on the poll that saw the job finish
    if (Array.isArray(res.messages) && res.messages.length >= s.messages.length) s.messages = res.messages;
    s.questions = res.state === 'waiting_user' ? (res.questions || []) : [];
    const was = s.state;
    s.state = res.state || s.state;
    if (res.draft && (was === 'running')) adoptDraft(s, res.draft);
    if (s.state === 'failed' && !s.error) s.error = 'Planning failed. Try again.';
    if (was !== 'drafted' && s.state === 'drafted' && window.matchMedia(ENV.PLANNER.MOBILE_QUERY).matches) setTab('draft');
    renderAll();
  }

  // ── draft model ─────────────────────────────────────────────────────
  const clampImp = (n) => Math.min(3, Math.max(1, parseInt(n, 10) || 2));

  function adoptDraft(s, d) {
    const byTitle = new Map();
    const lists = (d.lists || []).map((l) => ({
      name: String(l.name || 'Untitled list'),
      cards: (l.cards || []).map((c) => {
        const card = { k: `c${++keySeq}`, title: String(c.title || ''), desc: String(c.desc || ''), importance: clampImp(c.importance), due: c.due || '' };
        if (!byTitle.has(card.title)) byTitle.set(card.title, card);
        return card;
      }),
    }));
    const schedule = (d.schedule || []).filter((e) => /^\d{4}-\d{2}-\d{2}/.test(e.date || '')).map((e) => {
      const card = byTitle.get(String(e.title || ''));
      return { date: e.date.slice(0, 10), k: card ? card.k : null, title: String(e.title || ''), importance: card ? card.importance : clampImp(e.importance) };
    });
    s.draft = { lists, schedule };
  }

  const cardCount = () => (S && S.draft ? S.draft.lists.reduce((n, l) => n + l.cards.length, 0) : 0);
  const findCard = (k) => {
    for (const l of S.draft.lists) { const c = l.cards.find((x) => x.k === k); if (c) return { list: l, card: c }; }
    return null;
  };

  function exportDraft() {
    return {
      lists: S.draft.lists.map((l) => ({
        name: l.name,
        cards: l.cards.map((c) => (c.due ? { title: c.title.trim(), desc: c.desc, importance: c.importance, due: c.due } : { title: c.title.trim(), desc: c.desc, importance: c.importance })),
      })),
      schedule: S.draft.schedule.map((e) => ({ date: e.date, title: e.title.trim(), importance: e.importance })),
    };
  }

  // ── render ──────────────────────────────────────────────────────────
  function renderAll() {
    if (!S) return;
    renderThread();
    renderComposer();
    renderDraft();
    renderFooter();
    $('planner-offline').hidden = !S.offline;
    if (S.state === 'running' && isOpen()) startTick(); else stopTick();
  }

  function renderThread() {
    const t = $('planner-thread');
    const nearBottom = t.scrollHeight - t.scrollTop - t.clientHeight < 80;
    const parts = [];
    if (!S.messages.length && !S.jobs.length) {
      parts.push('<p class="planner__hint">Describe what you want to get done. The AI may ask a few questions, then drafts cards and a schedule you can edit before anything is added to the board.</p>');
    }
    const lastAi = S.messages.map((m) => m.role).lastIndexOf('ai');
    const showQ = S.state === 'waiting_user' && S.questions.length > 0;
    const qHtml = showQ ? `<ol class="planner-questions">${S.questions.map((q) => `<li>${esc(q)}</li>`).join('')}</ol>` : '';
    const jobsAt = (i) => S.jobs.filter((j) => j.at === i).map(jobHtml).join('');
    parts.push(jobsAt(0));
    S.messages.forEach((m, i) => {
      const role = m.role === 'user' ? 'user' : 'ai';
      const q = i === lastAi && showQ ? qHtml : '';
      parts.push(`<div class="planner-msg planner-msg--${role}">${esc(m.text)}${q}</div>`);
      parts.push(jobsAt(i + 1));
    });
    if (showQ && lastAi < 0) parts.push(`<div class="planner-msg planner-msg--ai">${qHtml}</div>`);
    if (S.error && S.state !== 'running') parts.push(StepList.error(S.error));
    t.innerHTML = parts.join('');
    if (nearBottom) t.scrollTop = t.scrollHeight;
  }

  function jobHtml(job) {
    if (!job.events.length && !(job === S.jobs[S.jobs.length - 1] && S.state === 'running')) return '';
    const live = job === S.jobs[S.jobs.length - 1] && S.state === 'running';
    return StepList.html(job.events, live, job.kind === 'lock' ? 'Starting the draft' : 'Waiting for the runner')
      + (live ? '' : StepList.usage(job.usage));
  }

  function startTick() {
    if (tickTimer) return;
    tickTimer = setInterval(() => StepList.tick(el()), 1000);
  }
  function stopTick() { clearInterval(tickTimer); tickTimer = null; }

  function renderComposer() {
    const form = $('planner-form');
    const st = S.state;
    form.hidden = st === 'drafted' || S.applying;
    // Rebuild the fields only when the questions change, so typed text survives polls and errors.
    const key = `${S.conv ? 'reply' : 'brief'}|${S.questions.join('\u0000')}`;
    if (key !== S.fieldsKey) {
      S.fieldsKey = key;
      $('planner-fields').innerHTML = S.questions.length
        ? S.questions.map((q, i) => `
          <div class="form-group">
            <label class="form-group__label" for="planner-a${i}">${i + 1}. ${esc(q)}</label>
            <textarea class="input" id="planner-a${i}" rows="2" data-q="${esc(q)}"></textarea>
          </div>`).join('')
        : `<textarea class="input" id="planner-msg" rows="${S.conv ? 2 : 4}" aria-label="${S.conv ? 'Reply' : 'Brief'}"
             placeholder="${S.conv ? 'Reply or add detail…' : 'What do you want to build or get done? Context, constraints, rough timing…'}"></textarea>`;
    }
    setFieldsDisabled(st === 'running');
    const canLock = st === 'waiting_user' || (st === 'failed' && S.lastJob === 'lock');
    $('planner-lock').hidden = !canLock;
    $('planner-lock').textContent = st === 'failed' ? 'Retry draft' : 'Lock decisions & draft';
    $('planner-send').textContent = S.questions.length ? 'Send answers' : 'Send';
  }

  function setFieldsDisabled(on) {
    $('planner-fields').querySelectorAll('textarea').forEach((t) => { t.disabled = on; });
    $('planner-send').disabled = on;
    $('planner-lock').disabled = on;
  }

  function composedMessage() {
    const qs = [...$('planner-fields').querySelectorAll('textarea[data-q]')];
    if (qs.length) {
      const answered = qs.filter((t) => t.value.trim());
      if (!answered.length) return '';
      return answered.map((t) => `${t.dataset.q}\n${t.value.trim()}`).join('\n\n');
    }
    const m = $('planner-msg');
    return m ? m.value.trim() : '';
  }

  function renderDraft() {
    const d = S.draft;
    const editable = S.state === 'drafted' && !S.applying;
    $('planner-draft-empty').hidden = !!d && d.lists.length > 0;
    $('planner-lists').innerHTML = d ? d.lists.map((l) => `
      <section class="planner-list" aria-label="List ${esc(l.name)}">
        <h3 class="planner-list__name">${esc(l.name)}</h3>
        ${l.cards.map((c) => cardHtml(l, c, editable)).join('')}
      </section>`).join('') : '';
    if (d) d.lists.forEach((l) => l.cards.forEach((c) => S.seen.add(`${l.name}\u0000${c.title}`)));
    const dot = $('planner-tab-dot');
    if (d && d.lists.length && el().dataset.tab !== 'draft') dot.hidden = false;
    renderWeek();
  }

  function cardHtml(list, c, editable) {
    const isNew = !S.seen.has(`${list.name}\u0000${c.title}`);
    const dis = editable ? '' : 'disabled';
    const pips = [1, 2, 3].map((n) => `<button type="button" class="importance__pip" data-imp="${n}" aria-pressed="${c.importance === n}" aria-label="Importance ${n}" ${dis}>${n}</button>`).join('');
    return `
      <div class="planner-card${isNew ? ' planner-card--new' : ''}" data-k="${c.k}">
        <div class="planner-card__top">
          <input class="planner-card__title" value="${esc(c.title)}" aria-label="Card title" maxlength="512" ${dis}>
          <button type="button" class="planner-card__del" data-del aria-label="Remove card ${esc(c.title)}" ${dis}>&#x2715;</button>
        </div>
        ${c.desc ? `<div class="planner-card__desc">${esc(c.desc)}</div>` : ''}
        <div class="planner-card__bottom">
          <span class="importance" role="group" aria-label="Importance">${pips}</span>
          ${c.due ? `<span class="planner-card__due">${esc(fmtDay(c.due))}</span>` : ''}
        </div>
      </div>`;
  }

  const parseDay = (s) => { const [y, m, d] = s.split('-').map(Number); return new Date(y, m - 1, d); };
  const pad = (n) => String(n).padStart(2, '0');
  const isoDay = (d) => `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
  const fmtDay = (s) => { const d = parseDay(s.slice(0, 10)); return `${DAY_NAMES[d.getDay()]} ${MONTHS[d.getMonth()]} ${d.getDate()}`; };

  function renderWeek() {
    const box = $('planner-week');
    const sched = S.draft ? S.draft.schedule : [];
    if (!sched.length) { box.hidden = true; box.innerHTML = ''; return; }
    const dates = sched.map((e) => e.date).sort();
    const deadline = $('planner-deadline').value;
    const first = parseDay(dates[0]);
    let lastIso = dates[dates.length - 1];
    if (deadline && deadline > lastIso) lastIso = deadline;
    const days = [];
    for (let d = new Date(first); isoDay(d) <= lastIso && days.length < MAX_DAYS; d.setDate(d.getDate() + 1)) days.push(new Date(d));
    const cols = days.map((d, i) => {
      const iso = isoDay(d);
      const blocks = sched.filter((e) => e.date === iso).sort((a, b) => b.importance - a.importance)
        .map((e) => `<div class="week-strip__block week-strip__block--i${e.importance}">${esc(e.title)}</div>`).join('');
      const cls = ['week-strip__day', d.getDay() % 6 === 0 ? 'week-strip__day--weekend' : '', iso === deadline ? 'week-strip__day--deadline' : ''].join(' ');
      const month = i === 0 || d.getDate() === 1 ? ` ${MONTHS[d.getMonth()]}` : '';
      return `<div class="${cls}" role="listitem" aria-label="${esc(fmtDay(iso))}${iso === deadline ? ', deadline' : ''}">
          <div class="week-strip__label"><span>${DAY_NAMES[d.getDay()]}</span><strong>${d.getDate()}${month}</strong></div>${blocks}</div>`;
    }).join('');
    box.hidden = false;
    box.innerHTML = `
      <div class="week-strip__head">
        <h3 class="week-strip__title">Schedule</h3>
        <span class="week-strip__meta">${sched.length} slots · ${esc(fmtDay(dates[0]))} – ${esc(fmtDay(dates[dates.length - 1]))}</span>
      </div>
      <div class="week-strip__days" role="list">${cols}</div>`;
  }

  function renderFooter() {
    const n = cardCount();
    const status = $('planner-status');
    let text = '';
    if (S.applying) text = 'Adding to Trello…';
    else if (S.state === 'running') text = S.lastJob === 'lock' ? 'Drafting — the draft fills in on the right as it arrives.' : 'The AI is working…';
    else if (S.state === 'waiting_user') text = 'Answer, or lock decisions to draft.';
    else if (S.state === 'drafted') text = n ? `${n} cards in ${S.draft.lists.length} lists. Edit freely — nothing is on the board yet.` : 'Every card was removed.';
    else if (S.state === 'failed') text = S.offline ? 'No runner connected.' : 'Planning failed.';
    status.textContent = text;
    status.classList.toggle('planner__status--error', S.state === 'failed');
    $('planner-apply').disabled = !(S.state === 'drafted' && n > 0 && !S.applying);
  }

  // ── draft edits (no full re-render while typing) ────────────────────
  function onDraftInput(e) {
    const inp = e.target.closest('.planner-card__title');
    if (!inp) return;
    const f = findCard(inp.closest('[data-k]').dataset.k);
    if (!f) return;
    f.card.title = inp.value;
    S.draft.schedule.forEach((s) => { if (s.k === f.card.k) s.title = inp.value; });
    S.seen.add(`${f.list.name}\u0000${inp.value}`);
    renderWeek();
  }

  function onDraftClick(e) {
    if (S.state !== 'drafted' || S.applying) return;
    const cardEl = e.target.closest('[data-k]');
    if (!cardEl) return;
    const f = findCard(cardEl.dataset.k);
    if (!f) return;
    const pip = e.target.closest('[data-imp]');
    if (pip) {
      const n = clampImp(pip.dataset.imp);
      f.card.importance = n;
      S.draft.schedule.forEach((s) => { if (s.k === f.card.k) s.importance = n; });
      cardEl.querySelectorAll('[data-imp]').forEach((b) => b.setAttribute('aria-pressed', String(+b.dataset.imp === n)));
      renderWeek();
      return;
    }
    if (e.target.closest('[data-del]')) {
      const all = [...$('planner-lists').querySelectorAll('[data-k]')].map((x) => x.dataset.k);
      const idx = all.indexOf(f.card.k);
      f.list.cards = f.list.cards.filter((c) => c !== f.card);
      S.draft.lists = S.draft.lists.filter((l) => l.cards.length);
      S.draft.schedule = S.draft.schedule.filter((s) => s.k !== f.card.k);
      renderDraft();
      renderFooter();
      const rest = $('planner-lists').querySelectorAll('.planner-card__title');
      const next = rest[Math.min(idx, rest.length - 1)];
      (next || $('planner-apply')).focus();
    }
  }

  async function applyDraft() {
    const s = S; const my = gen;
    if (s.draft.lists.some((l) => l.cards.some((c) => !c.title.trim()))) {
      $('planner-status').textContent = 'Every card needs a title.';
      $('planner-status').classList.add('planner__status--error');
      return;
    }
    const body = { draft: exportDraft() };
    const deadline = $('planner-deadline').value;
    const pace = parseFloat($('planner-pace').value);
    if (deadline) body.deadline = deadline;
    if (pace > 0) body.pace = pace;
    s.applying = true;
    renderAll();
    try {
      const r = await api().apply(s.projectId, s.conv, body);
      if (my !== gen) return;
      const created = r && r.created != null ? r.created : cardCount();
      Toast.show(`Added ${created} card${created === 1 ? '' : 's'} to the board`);
      discard();
      onChange();
    } catch (e) {
      if (my !== gen) return;
      s.applying = false;
      if (isOffline(e)) s.offline = true;
      renderAll();
      $('planner-status').textContent = isOffline(e) ? 'No runner connected.'
        : e.code === 'planner_already_applied' ? 'Already added to the board' : e.message;
      $('planner-status').classList.add('planner__status--error');
    }
  }

  // ── tabs, keyboard, focus trap ──────────────────────────────────────
  function setTab(tab) {
    el().dataset.tab = tab;
    el().querySelectorAll('.planner__tabs [data-tab]').forEach((b) => {
      const on = b.dataset.tab === tab;
      b.classList.toggle('active', on);
      b.setAttribute('aria-selected', String(on));
      b.tabIndex = on ? 0 : -1;
    });
    if (tab === 'draft') $('planner-tab-dot').hidden = true;
  }

  function onKey(e) {
    const confirmOpen = !$('planner-confirm').hidden;
    if (e.key === 'Escape') {
      e.preventDefault();
      if (confirmOpen) { $('planner-confirm').hidden = true; return; }
      requestClose();
      return;
    }
    if (e.key === 'Tab') {
      const scope = confirmOpen ? $('planner-confirm') : el();
      const items = [...scope.querySelectorAll(FOCUSABLE)].filter((n) => n.getClientRects().length && !n.closest('[hidden]') && (confirmOpen || !n.closest('#planner-confirm')));
      if (!items.length) return;
      const first = items[0]; const last = items[items.length - 1];
      if (e.shiftKey && (document.activeElement === first || !scope.contains(document.activeElement))) { e.preventDefault(); last.focus(); }
      else if (!e.shiftKey && (document.activeElement === last || !scope.contains(document.activeElement))) { e.preventDefault(); first.focus(); }
      return;
    }
    const tab = e.target.closest('.planner__tabs [data-tab]');
    if (tab && (e.key === 'ArrowLeft' || e.key === 'ArrowRight')) {
      const next = tab.dataset.tab === 'convo' ? 'draft' : 'convo';
      setTab(next);
      el().querySelector(`.planner__tabs [data-tab="${next}"]`).focus();
    }
    // Ctrl/Cmd+Enter sends from any composer field.
    if (e.key === 'Enter' && (e.ctrlKey || e.metaKey) && e.target.closest('#planner-fields')) {
      e.preventDefault();
      $('planner-form').requestSubmit();
    }
  }

  function init() {
    $('planner-install').href = ENV.ROUTES.INSTALL;
    el().addEventListener('keydown', onKey);
    el().addEventListener('click', (e) => {
      const act = e.target.closest('[data-pl]');
      if (act) {
        if (act.dataset.pl === 'close') requestClose();
        else if (act.dataset.pl === 'keep') { $('planner-confirm').hidden = true; $('planner-title').focus(); }
        else if (act.dataset.pl === 'discard') discard();
        return;
      }
      const tab = e.target.closest('.planner__tabs [data-tab]');
      if (tab) setTab(tab.dataset.tab);
    });
    $('planner-form').addEventListener('submit', (e) => {
      e.preventDefault();
      if (!S || S.state === 'running') return;
      const msg = composedMessage();
      if (msg) startJob('chat', msg);
    });
    $('planner-lock').addEventListener('click', () => {
      if (S && S.conv && S.state !== 'running') startJob('lock');
    });
    $('planner-lists').addEventListener('input', onDraftInput);
    $('planner-lists').addEventListener('click', onDraftClick);
    $('planner-deadline').addEventListener('change', () => { if (S) renderWeek(); });
    $('planner-apply').addEventListener('click', () => { if (S && S.state === 'drafted' && !S.applying) applyDraft(); });
    $('planner-title').tabIndex = -1;
    setTab('convo');
  }

  const setOnChange = (fn) => { onChange = fn; };

  return { init, open, hide, setOnChange };
})();
