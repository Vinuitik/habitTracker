// #/d/<dev>/p/<pid>/git  Git tab: status, per-file diff, stage / unstage, commit, branches, push / pull / fetch, history.
// RelayGit.mount(el, {dev, pid, refresh}) → unmount (contract: js/relay/router.js). API: shared/API.md "Git".
// Network ops answer 202 and run in the background: pollOp() re-reads GET /git until operation === "".
window.RelayGit = (() => {
  const { $, esc, timeAgo } = Dom;
  const R = Relay;
  const EP = ENV.ENDPOINTS;
  const LOG_LIMIT = 30;
  const DIFF_MAX_CHARS = 200000;   // proxy caps bodies at 4 MiB; a diff this long is unreadable on a phone anyway
  const POLL_MS = 1500;
  const POLL_MAX = 200;            // ~5 min
  const OPS = { push: ['Pushing', 'Pushed'], pull: ['Pulling', 'Pulled'], fetch: ['Fetching', 'Fetched'] };
  const OP_OF = { pushing: 'push', pulling: 'pull', fetching: 'fetch' };

  function mount(el, ctx) {
    const { dev, pid } = ctx;
    const url = (path) => EP.RELAY_V1(dev, `/projects/${R.enc(pid)}/git${path}`);
    let alive = true;
    let st = null;                 // last GitStatus
    let branches = [];
    let log = [];
    let diffOf = null;             // {path, staged} currently shown
    let pollTimer = null;
    let tickTimer = null;
    let opSince = 0;
    let opName = '';

    const g = (id) => $(id);
    const toast = (m) => Toast.show(m, 4000);
    const fail = (e) => toast(e.message);

    el.innerHTML = R.header('Git', [`#/d/${R.enc(dev)}`, 'Sessions'],
      '<button class="btn btn--ghost" data-act="refresh">Refresh</button>')
      + '<div id="git-body"><div class="relay-empty">Loading…</div></div>';
    const offActs = R.onAct(el, {
      refresh: () => loadAll(),
      fetch: () => startOp('fetch'),
      pull: () => startOp('pull'),
      push: () => startOp('push'),   // one tap: a plain push (never --force) is safe to run straight away
      stage: (b) => change('/stage', { paths: [b.dataset.path] }),
      unstage: (b) => change('/unstage', { paths: [b.dataset.path] }),
      'stage-all': () => change('/stage', { all: true }),
      'unstage-all': () => change('/unstage', { all: true }),
      diff: (b) => showDiff(b.dataset.path, b.dataset.staged === '1'),
      'diff-close': () => { diffOf = null; hideDiff(); },
      'switch-open': (b) => openSwitch(b),
      'create-cancel': (b) => b.closest('form').remove(),
    });

    // ---- loading -------------------------------------------------------------------------
    async function loadAll() {
      try {
        st = await R.call(url(''), 'GET');
        if (!alive) return;
        if (st.isRepo) await reloadLists();
        if (!alive) return;
        render();
        if (st.isRepo && st.operation) resumeOp();
      } catch (e) {
        if (alive) g('git-body').innerHTML = R.errorHtml(e);
      }
    }

    async function reloadLists() {
      const [b, l] = await Promise.allSettled([R.call(url('/branches'), 'GET'), R.call(url(`/log?limit=${LOG_LIMIT}`), 'GET')]);
      if (b.status === 'fulfilled') branches = b.value;
      if (l.status === 'fulfilled') log = l.value;
    }

    // ---- render --------------------------------------------------------------------------
    const isStaged = (f) => !f.untracked && f.index && f.index !== '.' && f.index !== '?';
    const isUnstaged = (f) => f.untracked || f.conflicted || (f.worktree && f.worktree !== '.');
    const letter = (f, staged) => (f.untracked ? 'U' : f.conflicted ? '!' : (staged ? f.index : f.worktree));

    function fileRow(f, staged) {
      const act = staged ? 'unstage' : 'stage';
      const code = letter(f, staged);
      return `<div class="git-file">
        <button class="git-file__open" data-act="diff" data-path="${esc(f.path)}" data-staged="${staged ? 1 : 0}" title="Show diff">
          <span class="git-file__code git-file__code--${f.conflicted ? 'x' : esc(code)}">${esc(code)}</span>
          <span class="git-file__path">${esc(f.origPath ? `${f.origPath} → ${f.path}` : f.path)}</span>
        </button>
        <button class="btn btn--ghost btn--sm" data-act="${act}" data-path="${esc(f.path)}" aria-label="${act} ${esc(f.path)}">${staged ? '−' : '+'}</button>
      </div>`;
    }

    const section = (title, files, staged, allAct, allLabel) => `
      <section class="git-section">
        <div class="git-section__head">
          <span class="relay-card__label">${title} · ${files.length}</span>
          ${files.length ? `<button class="btn btn--ghost btn--sm" data-act="${allAct}">${allLabel}</button>` : ''}
        </div>
        ${files.length ? files.map((f) => fileRow(f, staged)).join('') : '<div class="relay-hint">Nothing here.</div>'}
      </section>`;

    function syncLine() {
      const bits = [];
      if (st.upstream) bits.push(`${esc(st.upstream)} · ↑${st.ahead} ↓${st.behind}`);
      else if (!st.detached) bits.push('Not published yet');
      if (st.remoteUrl) bits.push(esc(st.remoteUrl));
      return `<div class="relay-hint">${bits.join(' · ')}</div>`;
    }

    function resultLine() {
      if (st.operation || !st.lastOp) return '';
      if (st.lastError) {
        const auth = st.authFailed
          ? `<div class="relay-hint">Sign-in needed${st.needsAuth ? ` (${esc(st.needsAuth)})` : ''}: re-authenticate the runner, then retry.</div>` : '';
        return `<div class="git-result git-result--error"><strong>${esc(st.lastOp)} failed</strong><pre class="git-result__out">${esc(st.lastError)}</pre>${auth}</div>`;
      }
      return st.lastOutput
        ? `<div class="git-result"><strong>Last ${esc(st.lastOp)}</strong><pre class="git-result__out">${esc(st.lastOutput)}</pre></div>` : '';
    }

    const logRow = (c) => `<div class="git-commit-row">
      <span class="git-commit-row__hash">${esc(c.short)}</span>
      <span class="git-commit-row__subject">${esc(c.subject)}</span>
      <span class="git-commit-row__meta">${esc(c.author)} · ${esc(timeAgo(new Date(c.time * 1000).toISOString()))}</span>
    </div>`;

    function render() {
      const body = g('git-body');
      if (!st.isRepo) { body.innerHTML = '<div class="relay-empty">This project is not a git repository.</div>'; return; }
      const staged = st.files.filter(isStaged);
      const unstaged = st.files.filter(isUnstaged);
      const dis = st.operation ? 'disabled' : '';
      body.innerHTML = `
        <div class="relay-card git-branch">
          <div class="git-branch__row">
            <span class="git-branch__name">${st.detached ? 'detached HEAD' : esc(st.branch)}</span>
            <button class="btn btn--ghost btn--sm" data-act="switch-open">Branches</button>
          </div>
          ${syncLine()}
          <div class="git-ops">
            <button class="btn btn--ghost btn--sm" data-act="fetch" ${dis}>Fetch</button>
            <button class="btn btn--ghost btn--sm" data-act="pull" ${dis}>Pull</button>
            <button class="btn btn--primary btn--sm" data-act="push" ${dis}>Push</button>
          </div>
          <div id="git-op"></div>
          <div id="git-result">${resultLine()}</div>
        </div>
        <div class="relay-card git-diffbox" id="git-diff" hidden></div>
        ${section('Staged', staged, true, 'unstage-all', 'Unstage all')}
        ${section('Changes', unstaged, false, 'stage-all', 'Stage all')}
        ${st.truncated ? '<div class="relay-hint">More than 500 changed paths; list truncated.</div>' : ''}
        <form class="git-commit" id="git-commit-form">
          <textarea class="input git-commit__msg" id="git-msg" rows="3" placeholder="Commit message" aria-label="Commit message"></textarea>
          <button class="btn btn--primary" type="submit" ${staged.length ? '' : 'disabled'}>Commit${staged.length ? ` (${staged.length})` : ''}</button>
        </form>
        <section class="git-section">
          <div class="git-section__head"><span class="relay-card__label">History</span></div>
          ${log.length ? log.map(logRow).join('') : '<div class="relay-hint">No commits yet.</div>'}
        </section>`;
      g('git-commit-form').addEventListener('submit', (e) => { e.preventDefault(); commit(); });
      const stillThere = diffOf && (diffOf.staged ? staged : unstaged).some((f) => f.path === diffOf.path);
      if (stillThere) showDiff(diffOf.path, diffOf.staged); else diffOf = null;
      if (st.operation || opName) renderOp();
    }

    // ---- diff ----------------------------------------------------------------------------
    function hideDiff() {
      const box = g('git-diff');
      if (box) box.hidden = true;
    }

    function diffHtml(text) {
      return text.split('\n').map((line) => {
        let cls = '';
        if (/^(\+\+\+|---|diff |index )/.test(line)) cls = 'git-diff__meta';
        else if (line.startsWith('@@')) cls = 'git-diff__hunk';
        else if (line.startsWith('+')) cls = 'git-diff__add';
        else if (line.startsWith('-')) cls = 'git-diff__del';
        return `<span class="git-diff__line ${cls}">${esc(line) || ' '}</span>`;
      }).join('');
    }

    async function showDiff(path, staged) {
      diffOf = { path, staged };
      const box = g('git-diff');
      box.hidden = false;
      const head = `<div class="git-section__head"><span class="git-file__path">${esc(path)} · ${staged ? 'staged' : 'unstaged'}</span>
        <button class="btn btn--ghost btn--sm" data-act="diff-close">Close</button></div>`;
      box.innerHTML = `${head}<div class="relay-hint">Loading diff…</div>`;
      try {
        const d = await R.call(url(`/diff?path=${R.enc(path)}&staged=${staged}`), 'GET');
        if (!alive || !diffOf || diffOf.path !== path || diffOf.staged !== staged) return;
        let text = d.diff || '';
        let note = d.truncated ? 'Diff too large; the runner cut it short.' : '';
        if (text.length > DIFF_MAX_CHARS) { text = text.slice(0, DIFF_MAX_CHARS); note = 'Diff too large; showing the start only.'; }
        box.innerHTML = head
          + (text ? `<pre class="git-diff">${diffHtml(text)}</pre>` : '<div class="relay-hint">No textual changes.</div>')
          + (note ? `<div class="relay-hint">${note}</div>` : '');
        box.scrollIntoView({ block: 'nearest', behavior: 'smooth' });
      } catch (e) {
        if (alive && diffOf && diffOf.path === path) box.innerHTML = head + R.errorHtml(e);
      }
    }

    // ---- local changes -------------------------------------------------------------------
    async function change(path, body) {
      try {
        st = await R.call(url(path), 'POST', body);
        if (alive) render();
      } catch (e) { fail(e); }
    }

    async function commit() {
      const msg = g('git-msg').value.trim();
      if (!msg) { toast('Write a commit message first'); return; }
      const btn = g('git-commit-form').querySelector('button');
      btn.disabled = true;
      try {
        st = await R.call(url('/commit'), 'POST', { message: msg });
        await reloadLists();
        if (!alive) return;
        render();
        toast('Committed');
      } catch (e) {
        fail(e);
        btn.disabled = false;
      }
    }

    // ---- branches ------------------------------------------------------------------------
    function openSwitch(anchor) {
      const dirty = st.files.length > 0;
      const items = branches.filter((b) => !b.current).map((b) => ({
        label: b.remote ? `${b.name} (remote)` : b.name,
        confirm: dirty ? 'You have uncommitted changes; git may refuse or carry them along. Switch?' : undefined,
        onClick: () => switchTo({ branch: b.name, remote: !!b.remote }),
      }));
      items.push({ label: '+ New branch…', onClick: promptNewBranch });
      Menu.open(anchor, items);
    }

    function promptNewBranch() {
      const form = document.createElement('form');
      form.className = 'relay-card git-newbranch';
      form.innerHTML = `<input class="input" id="git-newname" placeholder="new-branch-name" aria-label="New branch name" autocomplete="off">
        <button class="btn btn--primary" type="submit">Create</button>
        <button class="btn btn--ghost" type="button" data-act="create-cancel">Cancel</button>`;
      form.addEventListener('submit', (e) => {
        e.preventDefault();
        const name = g('git-newname').value.trim();
        if (name) switchTo({ branch: name, create: true });
      });
      g('git-body').prepend(form);
      g('git-newname').focus();
    }

    async function switchTo(body) {
      try {
        st = await R.call(url('/switch'), 'POST', body);
        await reloadLists();
        if (!alive) return;
        render();
        toast(`On ${st.branch}`);
      } catch (e) { fail(e); }   // 409 {error} = git's own refusal text
    }

    // ---- network ops (202 → poll) --------------------------------------------------------
    function renderOp() {
      const box = g('git-op');
      if (!box) return;
      const label = OPS[opName] ? OPS[opName][0] : 'Working';
      box.innerHTML = StepList.html([{ kind: 'step', text: `${label}…`, t: opSince }], true);
    }

    async function startOp(name) {
      opName = name;
      opSince = Date.now();
      try {
        st = await R.call(url(`/${name}`), 'POST');
        if (!alive) return;
        render();
        resumeOp();
      } catch (e) {
        opName = '';
        fail(e);
      }
    }

    function resumeOp() {
      if (!opName) { opName = OP_OF[st.operation] || ''; opSince = Date.now(); renderOp(); }
      clearInterval(tickTimer);
      tickTimer = setInterval(() => { const box = g('git-op'); if (box) StepList.tick(box); }, 1000);
      pollOp(0);
    }

    function pollOp(n) {
      clearTimeout(pollTimer);
      pollTimer = setTimeout(async () => {
        try {
          st = await R.call(url(''), 'GET');
          if (!alive) return;
          if (st.operation && n < POLL_MAX) { pollOp(n + 1); return; }
          finishOp(!!st.operation);
        } catch (e) {
          if (!alive) return;
          if (n < POLL_MAX) pollOp(n + 1);   // transient (runner blip): keep trying
          else { finishOp(true); fail(e); }
        }
      }, POLL_MS);
    }

    async function finishOp(timedOut) {
      clearInterval(tickTimer);
      const name = opName;
      opName = '';
      await reloadLists();
      if (!alive) return;
      render();
      if (timedOut) toast('Still running; use Refresh to check');
      else if (st.lastError) toast(`${name || 'Operation'} failed: ${st.lastError.split('\n')[0]}`);
      else toast(OPS[name] ? OPS[name][1] : 'Done');
    }

    loadAll();
    return () => {
      alive = false;
      clearTimeout(pollTimer);
      clearInterval(tickTimer);
      offActs();
      Menu.close();
    };
  }

  return { mount };
})();
