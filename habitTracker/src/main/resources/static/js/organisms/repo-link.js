// Board header repo row ([data-repo] slot in Board's header): folder link + repo-map status + map job progress.
// RepoLink.mount(el, projectId) → GET PROJECT → not linked: "Link folder" (FolderPicker) ·
// linked (project.runnerDeviceId + folderName): "Linked to <folder>" + Unlink (DELETE PROJECT_LINK) + map status
// (GET PROJECT_MAP; 404 = not mapped) + "Map repo"/"Remap" → POST PROJECT_MAP {jobId} → poll PROJECT_MAP_JOB
// every ENV.TIMING.MAP_POLL_MS with ?after=<nextSeq>, events appended to the progress panel.
// Jobs live in `jobs` (per project) so a board re-render / navigating away and back keeps the progress.
window.RepoLink = (() => {
  const { esc, timeAgo } = Dom;
  const EP = ENV.ENDPOINTS;
  let M = null;            // mounted: { el, projectId, project, err, map (undefined = loading, null = not mapped), mapErr }
  const jobs = new Map();  // projectId -> { projectId, jobId, after, events, state: running|done|failed, error, fails, timer }

  const linkOf = (p) => (p && p.runnerDeviceId ? { deviceId: p.runnerDeviceId, name: p.folderName || 'folder' } : null);
  const mounted = (m) => M === m && m.el.isConnected;

  async function mount(el, projectId) {
    if (!el) return;
    const m = { el, projectId, project: null, err: '', map: undefined, mapErr: '' };
    M = m;
    el.addEventListener('click', (e) => onClick(m, e));
    el.innerHTML = '';
    try {
      m.project = await Api.request(EP.PROJECT(projectId));
    } catch (e) {
      m.err = e.message;
    }
    if (!mounted(m)) return;
    render(m);
    if (linkOf(m.project)) loadMap(m);
    const job = jobs.get(projectId);
    if (job && job.state === 'running' && !job.timer) schedule(job);
  }

  async function loadMap(m) {
    m.map = undefined;
    m.mapErr = '';
    render(m);
    try {
      m.map = await Api.request(EP.PROJECT_MAP(m.projectId));
    } catch (e) {
      if (e.status === 404) m.map = null;
      else m.mapErr = e.message;
    }
    if (mounted(m)) render(m);
  }

  // ── rendering ─────────────────────────────────────────────────────────
  function mapStatusHtml(m) {
    if (m.mapErr) return `<span class="repo-bar__meta repo-bar__meta--error">${esc(m.mapErr)}</span>`;
    if (m.map === undefined) return '<span class="repo-bar__meta">Checking map…</span>';
    if (!m.map) return '<span class="badge badge--muted">Not mapped</span>';
    const subs = m.map.subsystems || [];
    const sha = String(m.map.commitSha || '').slice(0, 7);
    return `<span class="badge badge--success" title="${esc(subs.map((s) => s.name).join(', '))}">Mapped</span>
      <span class="repo-bar__meta">${sha ? `${esc(sha)} · ` : ''}${esc(timeAgo(m.map.builtAt))} · ${subs.length} subsystem${subs.length === 1 ? '' : 's'}</span>`;
  }

  function render(m) {
    if (!mounted(m)) return;
    if (!m.project) {
      m.el.innerHTML = m.err ? `<span class="repo-bar__meta repo-bar__meta--error">${esc(m.err)}</span>` : '';
      return;
    }
    const link = linkOf(m.project);
    if (!link) {
      m.el.innerHTML = `
        <button type="button" class="btn btn--ghost btn--sm" data-repo-act="link">${Icons.folder}Link folder</button>
        <span class="repo-bar__meta">Connect this board to its repo on one of your computers</span>`;
      return;
    }
    const job = jobs.get(m.projectId);
    const running = !!job && job.state === 'running';
    m.el.innerHTML = `
      <span class="repo-bar__linked" >${Icons.folder}<span>Linked to <strong>${esc(link.name)}</strong></span></span>
      <button type="button" class="btn btn--ghost btn--sm" data-repo-act="unlink">Unlink</button>
      <span class="repo-bar__sep" aria-hidden="true"></span>
      ${mapStatusHtml(m)}
      <button type="button" class="btn btn--ghost btn--sm" data-repo-act="map" ${running || m.map === undefined ? 'disabled' : ''}>${m.map ? 'Remap' : 'Map repo'}</button>
      <span class="repo-bar__note">uses your Claude tokens</span>
      <div class="map-progress" data-progress></div>`;
    renderProgress(m);
  }

  function renderProgress(m) {
    if (!mounted(m)) return;
    const box = m.el.querySelector('[data-progress]');
    if (!box) return;
    const job = jobs.get(m.projectId);
    box.hidden = !job;
    if (!job) { box.innerHTML = ''; return; }
    const title = { running: 'Mapping repo…', done: 'Map built', failed: 'Mapping failed' }[job.state];
    box.innerHTML = `
      <div class="map-progress__head">
        <span class="map-progress__title${job.state === 'failed' ? ' map-progress__title--error' : ''}">${title}</span>
        ${job.state === 'running' ? '' : '<button type="button" class="btn btn--ghost btn--sm" data-repo-act="dismiss">Close</button>'}
      </div>
      <ol class="map-progress__log">
        ${job.events.length ? job.events.map((ev) => `<li class="map-progress__ev map-progress__ev--${esc(ev.kind || 'text')}">${esc(ev.text ?? '')}</li>`).join('')
    : '<li class="map-progress__ev map-progress__ev--text">Starting…</li>'}
      </ol>
      ${job.error ? `<div class="map-progress__error">${esc(job.error)}</div>` : ''}`;
    const log = box.querySelector('.map-progress__log');
    log.scrollTop = log.scrollHeight;
  }

  // ── actions ───────────────────────────────────────────────────────────
  function onClick(m, e) {
    const b = e.target.closest('[data-repo-act]');
    if (!b || !mounted(m)) return;
    const a = b.dataset.repoAct;
    if (a === 'link') {
      FolderPicker.open(m.projectId, (project, name) => {
        if (!mounted(m)) return;
        m.project = project;
        Toast.show(`Linked to ${name}`);
        loadMap(m);
      });
    } else if (a === 'unlink') {
      Menu.open(b, [{ label: 'Unlink folder', danger: true, confirm: 'Unlink this folder from the board?', onClick: () => unlink(m) }]);
    } else if (a === 'map') startMap(m, b);
    else if (a === 'dismiss') { jobs.delete(m.projectId); renderProgress(m); }
  }

  async function unlink(m) {
    try {
      const project = await RelayApi.call(EP.PROJECT_LINK(m.projectId), 'DELETE');
      stopJob(m.projectId);
      if (!mounted(m)) return;
      m.project = project || { ...m.project, link: null };
      m.map = undefined;
      render(m);
      Toast.show('Folder unlinked');
    } catch (e) {
      Toast.show(e.message);
    }
  }

  async function startMap(m, btn) {
    btn.disabled = true;
    let r;
    try {
      r = await RelayApi.call(EP.PROJECT_MAP(m.projectId), 'POST');
    } catch (e) {
      Toast.show(e.message);
      if (mounted(m)) btn.disabled = false;
      return;
    }
    stopJob(m.projectId);
    const job = { projectId: m.projectId, jobId: r.jobId, after: 0, events: [], state: 'running', error: '', fails: 0, timer: null };
    jobs.set(m.projectId, job);
    render(m);
    schedule(job);
  }

  function stopJob(projectId) {
    const j = jobs.get(projectId);
    if (j) clearTimeout(j.timer);
    jobs.delete(projectId);
  }

  function schedule(job) {
    job.timer = setTimeout(() => tick(job), ENV.TIMING.MAP_POLL_MS);
  }

  // One poll: append new events, finish on done/failed. Background tabs skip the request but keep the timer.
  async function tick(job) {
    job.timer = null;
    if (jobs.get(job.projectId) !== job) return;
    if (document.hidden) { schedule(job); return; }
    try {
      const r = await Api.request(EP.PROJECT_MAP_JOB(job.projectId, job.jobId, job.after));
      if (jobs.get(job.projectId) !== job) return;
      job.fails = 0;
      job.events.push(...(r.events || []));
      if (job.events.length > ENV.REPO.MAP_LOG_MAX) job.events.splice(0, job.events.length - ENV.REPO.MAP_LOG_MAX);
      if (r.nextSeq != null) job.after = r.nextSeq;
      const state = r.state || r.status;
      if (state === 'done') job.state = 'done';
      else if (state === 'failed') {
        job.state = 'failed';
        const lastErr = [...job.events].reverse().find((ev) => ev.kind === 'error');
        job.error = r.error || (lastErr ? '' : 'Mapping failed');
      }
    } catch (e) {
      if (jobs.get(job.projectId) !== job) return;
      job.fails += 1;
      if (e.status === 404 || job.fails >= 5) {
        job.state = 'failed';
        job.error = e.status === 404 ? 'Mapping job not found (server restarted?)' : e.message;
      }
    }
    const m = M && M.projectId === job.projectId && mounted(M) ? M : null;
    if (job.state === 'running') { if (m) renderProgress(m); schedule(job); return; }
    if (job.state === 'done') {
      jobs.delete(job.projectId);
      if (m) { Toast.show('Repo mapped'); loadMap(m); }
      return;
    }
    if (m) render(m);   // failed: re-enable the button, show the error panel
  }

  return { mount };
})();
