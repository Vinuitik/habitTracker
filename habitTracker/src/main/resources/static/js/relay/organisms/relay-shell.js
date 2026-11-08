// Relay shell (layout A, docs/designs/relay-web-ui.md M1): everything under #/d/<dev>.
//   rail   runner switcher (online lamp, Menu of devices) · project list with lamps · "+ Project"
//   main   Needs-you strip · project header + tabs Chats · Git · Containers · Files · tab content
// Routes (js/relay/router.js): #/d/<dev>                 no project: "Pick a project" + recent sessions + KPI credentials (RelayCredentials)
//                              #/d/<dev>/p/<pid>/<tab>   tab content = RelayChats / RelayGit / RelayContainers / RelayFiles
//                              #/d/<dev>/s/<sid>         RelayChat, back crumb to its project's Chats tab
// RelayShell.mount(el, {dev, pid?, tab?, sid?}) → unmount; unmount.update(params) re-routes inside the
// mounted shell (rail kept) and returns false when the runner changed (router then remounts).
// Lamps: GET /v1/sessions?state=waiting,busy every ENV.TIMING.RELAY_RAIL_POLL_MS, paused while the tab is hidden.
//        + GET /projects/<pid>/containers per project every ENV.TIMING.RELAY_CONTAINERS_LAMP_MS (web-only: the
//        runner has no all-projects container summary). Priority: needs you (amber) > busy (green) > containers up (green).
// Mobile (<900px, styles/organisms/relay-shell.css): .relay-shell--detail shows main only, else the rail is the list screen.
window.RelayShell = (() => {
  const { $, esc, timeAgo } = Dom;
  const R = Relay;
  const EP = ENV.ENDPOINTS;
  const TABS = [['chats', 'Chats', RelayChats], ['git', 'Git', RelayGit],
    ['containers', 'Containers', RelayContainers], ['files', 'Files', RelayFiles]];
  const RECENT_MAX = 8;
  let pickerReady = false;

  const hDev = (dev) => `#/d/${R.enc(dev)}`;
  const hProject = (dev, pid, tab = 'chats') => `${hDev(dev)}/p/${R.enc(pid)}/${tab}`;
  const hChat = (dev, sid) => `${hDev(dev)}/s/${R.enc(sid)}`;
  const lamp = (state, extra = '') => `<span class="lamp${state ? ` lamp--${state}` : ''}${extra}" aria-hidden="true"></span>`;
  // Session state → lamp: waiting = needs you (amber), busy = working (green), the rest = off (outline).
  const lampOf = (state) => (state === 'waiting' ? 'wait' : state === 'busy' ? 'busy' : state === 'up' ? 'on' : '');
  const META = { waiting: 'needs you', busy: 'busy', up: 'running' };

  function mount(el, params) {
    const S = {
      dev: params.dev, pid: null, tab: 'chats', sid: null,
      alive: true, devices: null, projects: null, projectsError: null,
      active: [], needsSig: '', sidProject: new Map(), all: null,
      inner: null, timer: null, pollSeq: 0, path: '',
      up: new Set(), contTimer: null,   // project ids with a running container
    };
    const page = $('relay-page');
    page.classList.add('relay-page--shell');

    el.innerHTML = `
      <div class="relay-shell" id="shell">
        <aside class="relay-shell__rail">
          <div class="relay-shell__railtop">
            <a class="relay-shell__brand" href="#/">Relay</a>
            <button type="button" class="relay-runner" id="shell-runner" data-act="runner" aria-haspopup="true">
              ${lamp('')}<span class="relay-runner__name">${esc(R.deviceNames.get(S.dev) || 'Runner')}</span>
              <span class="relay-runner__state"></span>${Icons.chevron}
            </button>
          </div>
          <div class="relay-shell__railbody">
            <div class="relay-needs-rail" id="shell-needs-rail" hidden></div>
            <div class="relay-shell__sect"><span>Projects</span><span id="shell-pcount"></span></div>
            <div id="shell-projects"><div class="relay-shell__note">Loading…</div></div>
          </div>
          <div class="relay-shell__railfoot">
            <button type="button" class="btn btn--ghost relay-shell__add" data-act="add" id="shell-add">${Icons.plus}Project</button>
            <form class="relay-newproj" id="shell-new" hidden>
              <input class="input" id="shell-new-name" maxlength="80" placeholder="Project name" aria-label="Project name" autocomplete="off">
              <div class="relay-newproj__path" id="shell-new-path"></div>
              <div class="relay-newproj__actions">
                <button type="button" class="btn btn--ghost btn--sm" data-act="pick">${Icons.folder}Folder…</button>
                <button type="button" class="btn btn--ghost btn--sm" data-act="add-cancel">Cancel</button>
                <button type="submit" class="btn btn--primary btn--sm" id="shell-new-submit">Create</button>
              </div>
            </form>
          </div>
        </aside>
        <section class="relay-shell__main">
          <div class="relay-needs" id="shell-needs" hidden></div>
          <div id="shell-head"></div>
          <div class="relay-shell__content" id="shell-content"></div>
        </section>
      </div>`;

    // ── runner switcher ───────────────────────────────────────────────
    function paintRunner() {
      const d = (S.devices || []).find((x) => x.id === S.dev);
      const btn = $('shell-runner');
      btn.querySelector('.lamp').className = `lamp${d && d.online ? ' lamp--busy' : ''}`;
      btn.querySelector('.relay-runner__name').textContent = (d && d.name) || R.deviceNames.get(S.dev) || 'Runner';
      const st = btn.querySelector('.relay-runner__state');
      st.textContent = !d ? '' : d.online ? 'online' : 'offline';
      st.classList.toggle('relay-runner__state--off', !!d && !d.online);
    }

    async function loadDevices() {
      try {
        const list = await R.call(EP.RELAY_DEVICES);
        if (!S.alive) return;
        S.devices = list;
        list.forEach((d) => R.deviceNames.set(d.id, d.name));
        paintRunner();
      } catch (_) { /* the switcher keeps the cached name */ }
    }

    function runnerMenu(btn) {
      const items = (S.devices || []).map((d) => ({
        label: `${d.name || 'Unnamed runner'}${d.id === S.dev ? ' ✓' : ''}${d.online ? '' : ' (offline)'}`,
        onClick: () => { location.hash = hDev(d.id); },
      }));
      items.push({ label: 'Manage devices…', onClick: () => { location.hash = '#/'; } });
      Menu.open(btn, items);
    }

    // ── project list ──────────────────────────────────────────────────
    const projectOf = (pid) => (S.projects || []).find((p) => p.id === pid);

    function projectState(pid) {
      let st = '';
      for (const s of S.active) {
        if (s.projectId !== pid) continue;
        if (s.state === 'waiting') return 'waiting';
        if (s.state === 'busy') st = 'busy';
      }
      return st || (S.up.has(pid) ? 'up' : '');
    }

    function renderProjects() {
      const box = $('shell-projects');
      if (S.projectsError) {
        box.innerHTML = `<div class="relay-shell__note relay-shell__note--error">${esc(S.projectsError.message)}</div>`;
        $('shell-pcount').textContent = '';
        return;
      }
      const list = S.projects || [];
      $('shell-pcount').textContent = String(list.length);
      box.innerHTML = list.length ? `<ul class="relay-plist relay-stagger">${list.map((p) => `
        <li><a class="relay-prow" href="${hProject(S.dev, p.id)}" data-pid="${esc(p.id)}" title="${esc(p.path || '')}">
          ${lamp('')}<span class="relay-prow__name">${esc(p.name || p.id)}</span><span class="relay-prow__meta"></span>
        </a></li>`).join('')}</ul>`
        : '<div class="relay-shell__note">No projects on this runner yet.</div>';
      paintLamps();
      markSelected();
    }

    function paintLamps() {
      document.querySelectorAll('#shell-projects .relay-prow').forEach((a) => {
        const st = projectState(a.dataset.pid);
        a.querySelector('.lamp').className = `lamp${st ? ` lamp--${lampOf(st)}` : ''}`;
        const meta = a.querySelector('.relay-prow__meta');
        meta.textContent = META[st] || '';
        meta.className = `relay-prow__meta${st ? ` relay-prow__meta--${lampOf(st)}` : ''}`;
      });
      const pl = $('shell-plamp');
      if (pl) pl.className = `lamp${S.pid && projectState(S.pid) ? ` lamp--${lampOf(projectState(S.pid))}` : ''}`;
    }

    function markSelected() {
      document.querySelectorAll('#shell-projects .relay-prow').forEach((a) => {
        if (a.dataset.pid === S.pid) a.setAttribute('aria-current', 'page');
        else a.removeAttribute('aria-current');
      });
    }

    async function loadProjects() {
      try {
        S.projects = await R.call(EP.RELAY_V1(S.dev, '/projects'));
        S.projectsError = null;
      } catch (e) {
        S.projects = null;
        S.projectsError = e;
      }
      if (!S.alive) return;
      renderProjects();
      renderHead();
      S.needsSig = '';   // repaint the strip with project names
      renderNeeds();
    }

    // ── Needs-you strip (desktop: top of main; mobile list screen: inside the rail) ─
    function renderNeeds() {
      const sig = S.active.map((s) => `${s.id}:${s.state}:${s.title}`).join('|');
      if (sig === S.needsSig) return;
      S.needsSig = sig;
      const waiting = S.active.filter((s) => s.state === 'waiting').length;
      const name = (pid) => { const p = projectOf(pid); return p ? p.name : pid; };
      const cards = S.active.map((s) => `
        <a class="relay-ncard${s.state === 'waiting' ? ' relay-ncard--wait' : ''}" href="${hChat(S.dev, s.id)}">
          ${lamp(lampOf(s.state))}
          <span class="relay-ncard__body">
            <span class="relay-ncard__t">${esc(s.title || 'New session')}</span>
            <span class="relay-ncard__s"><span class="relay-ncard__p">${esc(name(s.projectId))}</span> · ${s.state === 'waiting' ? 'needs you' : 'working'} · ${esc(timeAgo(s.lastActiveAt || s.createdAt))}</span>
          </span>
        </a>`).join('');
      const label = `<span class="relay-needs__label${waiting ? ' relay-needs__label--wait' : ''}">${waiting ? `Needs you · ${waiting}` : 'Working'}</span>`;
      for (const id of ['shell-needs', 'shell-needs-rail']) {
        const box = $(id);
        box.hidden = !S.active.length;
        box.innerHTML = S.active.length ? `${label}<div class="relay-needs__list">${cards}</div>` : '';
      }
    }

    async function poll() {
      clearTimeout(S.timer);
      if (!S.alive || document.hidden) return;
      const my = ++S.pollSeq;
      try {
        const list = await R.call(EP.RELAY_V1(S.dev, '/sessions?state=waiting,busy'));
        if (!S.alive || my !== S.pollSeq) return;
        S.active = list || [];
        S.active.forEach((s) => S.sidProject.set(s.id, s.projectId));
        renderNeeds();
        paintLamps();
      } catch (_) { /* keep the last lamps; an offline runner already shows in the rail */ }
      if (S.alive && my === S.pollSeq && !document.hidden) S.timer = setTimeout(poll, ENV.TIMING.RELAY_RAIL_POLL_MS);
    }
    async function pollContainers() {
      clearTimeout(S.contTimer);
      if (!S.alive || document.hidden || !S.projects) return;
      const ids = S.projects.map((p) => p.id);
      const res = await Promise.allSettled(ids.map((id) => R.call(EP.RELAY_V1(S.dev, `/projects/${R.enc(id)}/containers`))));
      if (!S.alive) return;
      res.forEach((r, i) => {
        if (r.status !== 'fulfilled') return;   // keep the last known state on a blip
        if ((r.value.containers || []).some((c) => c.state === 'running')) S.up.add(ids[i]); else S.up.delete(ids[i]);
      });
      paintLamps();
      if (!document.hidden) S.contTimer = setTimeout(pollContainers, ENV.TIMING.RELAY_CONTAINERS_LAMP_MS);
    }
    const onVisibility = () => {
      if (document.hidden) { clearTimeout(S.timer); clearTimeout(S.contTimer); S.pollSeq++; } else { poll(); pollContainers(); }
    };

    // Every session on the runner (recent list + chat → project lookup); cached per mount, refetched on a miss.
    async function allSessions(force) {
      if (!S.all || force) {
        S.all = await R.call(EP.RELAY_V1(S.dev, '/sessions'));
        S.all.forEach((s) => S.sidProject.set(s.id, s.projectId));
      }
      return S.all;
    }

    // ── header (project / chat crumb) ─────────────────────────────────
    function renderHead() {
      const head = $('shell-head');
      if (S.sid) {
        const pid = S.sidProject.get(S.sid);
        const p = pid && projectOf(pid);
        head.innerHTML = `<div class="relay-phead relay-phead--chat">
          <a class="relay-phead__crumb" href="${pid ? hProject(S.dev, pid) : hDev(S.dev)}">${Icons.back}
            <span>${pid ? `${esc(p ? p.name : pid)} · Chats` : 'Projects'}</span></a></div>`;
        return;
      }
      if (!S.pid) { head.innerHTML = ''; return; }
      const p = projectOf(S.pid);
      head.innerHTML = `<div class="relay-phead">
        <div class="relay-phead__row">
          <a class="relay-phead__back" href="${hDev(S.dev)}" aria-label="All projects">${Icons.back}</a>
          <span class="lamp" id="shell-plamp" aria-hidden="true"></span>
          <h1 class="relay-phead__title">${esc(p ? p.name : S.pid)}</h1>
          <span class="relay-phead__path">${esc(p ? p.path || '' : '')}</span>
        </div>
        <nav class="seg-tabs relay-phead__tabs" aria-label="Project">
          ${TABS.map(([id, label]) => `<a class="seg-tab${id === S.tab ? ' active' : ''}" href="${hProject(S.dev, S.pid, id)}"${id === S.tab ? ' aria-current="page"' : ''}>${label}</a>`).join('')}
        </nav>
      </div>`;
      paintLamps();
    }

    // ── content: one inner organism at a time ─────────────────────────
    function unmountInner() {
      if (S.inner) { const u = S.inner; S.inner = null; u(); }
    }

    function mountInner() {
      unmountInner();
      const box = $('shell-content');
      box.scrollTop = 0;
      box.classList.toggle('relay-shell__content--chat', !!S.sid);
      const ctx = { dev: S.dev, refresh: mountInner };
      if (S.sid) {
        S.inner = RelayChat.mount(box, { ...ctx, sid: S.sid }) || null;
      } else if (S.pid) {
        const organism = (TABS.find(([id]) => id === S.tab) || TABS[0])[2];
        S.inner = organism.mount(box, { ...ctx, pid: S.pid }) || null;
      } else {
        S.inner = mountEmpty(box);
      }
    }

    // #/d/<dev>: no project selected → "Pick a project" + recent sessions.
    function mountEmpty(box) {
      let live = true;
      box.innerHTML = `<div class="relay-shell__empty">
          <h1 class="relay-shell__empty-title">Pick a project</h1>
          <p class="relay-hint">Choose one on the left, or add one with + Project.</p>
        </div>
        <div class="relay-section-label">Recent sessions</div>
        <div class="relay-list relay-stagger" id="shell-recent"><div class="relay-empty">Loading…</div></div>
        <div id="shell-creds"></div>
        <div id="shell-packages"></div>`;
      const offCreds = RelayCredentials.mount($('shell-creds'), { dev: S.dev });
      const offPackages = RelayPackages.mount($('shell-packages'), { dev: S.dev });
      Promise.all([allSessions(true), S.projectsReady]).then(([list]) => {
        if (!live) return;
        const recent = list.slice().sort((a, b) => String(b.lastActiveAt || b.createdAt || '')
          .localeCompare(String(a.lastActiveAt || a.createdAt || ''))).slice(0, RECENT_MAX);
        const name = (pid) => { const p = projectOf(pid); return p ? p.name : pid; };
        $('shell-recent').innerHTML = recent.length ? recent.map((s) => `
          <a class="relay-row relay-row__main${s.state === 'finished' ? ' relay-row--done' : ''}" href="${hChat(S.dev, s.id)}">
            <span class="relay-row__body">
              <span class="relay-row__name">${esc(s.title || 'New session')}</span>
              <span class="relay-row__meta">${esc(name(s.projectId))} · ${esc(timeAgo(s.lastActiveAt || s.createdAt))}${s.preview ? ' · ' + esc(s.preview) : ''}</span>
            </span>
            ${R.stateBadge(s.state)}
          </a>`).join('') : '<div class="relay-empty">No sessions yet.</div>';
      }, (e) => { if (live) $('shell-recent').innerHTML = R.errorHtml(e); });
      return () => { live = false; offCreds(); offPackages(); };
    }

    // ── + Project: name → scaffolded dir, or Folder… → register an existing one ─
    function openForm(open) {
      $('shell-new').hidden = !open;
      $('shell-add').hidden = open;
      S.path = '';
      $('shell-new-name').value = '';
      paintPath();
      if (open) $('shell-new-name').focus();
    }
    function paintPath() {
      $('shell-new-path').innerHTML = S.path
        ? `Folder: <code>${esc(S.path)}</code>`
        : 'New folder in the runner\'s projects root, or pick an existing one.';
    }
    function pickFolder() {
      if (!pickerReady) { FolderPicker.init(); pickerReady = true; }
      FolderPicker.pick({ id: S.dev, name: R.deviceNames.get(S.dev) || 'Runner' }, (path, folder) => {
        if (!S.alive) return;
        S.path = path;
        const name = $('shell-new-name');
        if (!name.value.trim()) name.value = folder;
        paintPath();
      });
    }
    async function createProject(e) {
      e.preventDefault();
      const name = $('shell-new-name').value.trim();
      if (!name && !S.path) { Toast.show('Name the project or pick a folder', 3000); return; }
      const btn = $('shell-new-submit');
      btn.disabled = true;
      try {
        const p = await R.call(EP.RELAY_V1(S.dev, '/projects'), 'POST',
          { ...(name ? { name } : {}), ...(S.path ? { path: S.path } : {}) });
        if (!S.alive) return;
        openForm(false);
        S.projects = (S.projects || []).concat(p);
        S.projectsError = null;
        renderProjects();
        location.hash = hProject(S.dev, p.id);
      } catch (err) {
        Toast.show(err.message, 4000);
      } finally {
        btn.disabled = false;
      }
    }

    // ── routing inside the shell ──────────────────────────────────────
    function apply(p) {
      S.pid = p.pid || null;
      S.sid = p.sid || null;
      S.tab = p.tab || 'chats';
      $('shell').classList.toggle('relay-shell--detail', !!(S.pid || S.sid));
      markSelected();
      renderHead();
      mountInner();
      if (S.sid && !S.sidProject.has(S.sid)) {
        const sid = S.sid;
        allSessions(true).then(() => { if (S.alive && S.sid === sid) renderHead(); }, () => {});
      }
    }

    const offActs = R.onAct($('shell'), {
      runner: runnerMenu,
      add: () => openForm(true),
      'add-cancel': () => openForm(false),
      pick: pickFolder,
    });
    $('shell-new').addEventListener('submit', createProject);
    document.addEventListener('visibilitychange', onVisibility);

    S.projectsReady = loadProjects();   // the empty state's recent list waits for it (project names)
    S.projectsReady.then(pollContainers);
    apply(params);
    loadDevices();
    poll();

    const unmount = () => {
      S.alive = false;
      clearTimeout(S.timer);
      clearTimeout(S.contTimer);
      unmountInner();
      offActs();
      document.removeEventListener('visibilitychange', onVisibility);
      page.classList.remove('relay-page--shell');
    };
    unmount.update = (p) => {
      if (p.dev !== S.dev) return false;
      apply(p);
      return true;
    };
    return unmount;
  }

  return { mount };
})();
