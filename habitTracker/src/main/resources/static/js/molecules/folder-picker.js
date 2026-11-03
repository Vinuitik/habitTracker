// Folder picker modal (#folder-modal in projects.html): pick a runner, browse its folders, link one.
// FolderPicker.open(projectId, onLinked) → step 1 runners (GET RELAY_DEVICES, offline ones disabled)
// → step 2 browse (GET RELAY_BROWSE, opens at '~') → "Select this folder" → POST PROJECT_LINK
// {deviceId, path} → onLinked(updatedProject). Keyboard: ↑/↓ move, Enter/→ open, ←/Backspace up, Esc close.
window.FolderPicker = (() => {
  const { $, esc, timeAgo } = Dom;
  const EP = ENV.ENDPOINTS;
  let st = null;   // { projectId, onLinked, dev, res, seq } — null when closed
  const body = () => $('folder-picker');
  const live = (s, my) => st === s && s.seq === my;

  const note = (html, error) => `<div class="folder-picker__empty${error ? ' folder-picker__empty--error' : ''}">${html}</div>`;

  // ── paths (runner may be Linux or Windows) ────────────────────────────
  const isWin = (p) => /^[A-Za-z]:/.test(p);
  function join(base, name) {
    if (!base) return name;                         // roots view: the entry name is the root itself
    const sep = isWin(base) ? '\\' : '/';
    return base.endsWith(sep) ? base + name : base + sep + name;
  }
  function crumbs(path) {
    const out = [{ label: 'Computer', path: '' }];
    if (!path) return out;
    const win = isWin(path);
    if (!win) out.push({ label: '/', path: '/' });
    let acc = win ? '' : '/';
    path.split(/[\\/]+/).filter(Boolean).forEach((part, i) => {
      acc = win ? (i === 0 ? `${part}\\` : join(acc, part)) : join(acc, part);
      out.push({ label: part, path: acc });
    });
    return out;
  }
  const folderName = (path) => path.split(/[\\/]+/).filter(Boolean).pop() || path;

  // ── open / close ──────────────────────────────────────────────────────
  function open(projectId, onLinked) {
    st = { projectId, onLinked, dev: null, res: null, seq: 0 };
    Dom.open('folder-modal');
    showDevices();
  }
  function close() {
    st = null;
    Dom.close('folder-modal');
  }

  // ── step 1: runners ───────────────────────────────────────────────────
  async function showDevices() {
    const s = st;
    const my = ++s.seq;
    s.dev = null;
    $('folder-modal-title').textContent = 'Pick a runner';
    body().innerHTML = note('Loading runners…');
    let list;
    try {
      list = (await RelayApi.call(EP.RELAY_DEVICES)) || [];
    } catch (e) {
      if (live(s, my)) body().innerHTML = note(esc(e.message), true) + actions('', 'Retry', 'retry-devices');
      return;
    }
    if (!live(s, my)) return;
    if (!list.length) {
      body().innerHTML = note(`No runners yet. A runner is a small service on your computer that lets
        HabitTracker read your project folders.
        <a class="btn btn--primary folder-picker__cta" href="${ENV.ROUTES.RELAY_INSTALL}">Install a runner</a>`);
      return;
    }
    list.sort((a, b) => Number(b.online) - Number(a.online));
    body().innerHTML = `
      <p class="folder-picker__hint">Which computer has the repo?</p>
      <div class="folder-picker__list" data-list>
        ${list.map((d) => `
          <button type="button" class="folder-row" data-dev="${esc(d.id)}" ${d.online ? '' : 'disabled'}>
            <span class="folder-row__dot${d.online ? ' folder-row__dot--on' : ''}"></span>
            <span class="folder-row__body">
              <span class="folder-row__name">${esc(d.name || 'Unnamed runner')}</span>
              <span class="folder-row__meta">${d.online ? 'online' : `offline · last seen ${esc(timeAgo(d.lastSeenAt))}`}</span>
            </span>
            ${d.online ? Icons.chevron : ''}
          </button>`).join('')}
      </div>`;
    s.devices = list;
    focusFirst();
  }

  // ── step 2: browse ────────────────────────────────────────────────────
  async function browse(path) {
    const s = st;
    const my = ++s.seq;
    $('folder-modal-title').textContent = 'Pick a folder';
    const list = body().querySelector('[data-list]');
    if (s.res && list) list.setAttribute('aria-busy', 'true');   // keep the old listing while loading
    else body().innerHTML = note('Opening folders…');
    let res;
    try {
      res = await RelayApi.call(EP.RELAY_BROWSE(s.dev.id, path));
    } catch (e) {
      if (!live(s, my)) return;
      body().innerHTML = barHtml(s) + note(esc(e.message), true) + actions('', 'Retry', 'retry-browse');
      s.retryPath = path;
      return;
    }
    if (!live(s, my)) return;
    s.res = res;
    renderBrowse();
  }

  const barHtml = (s) => `
    <div class="folder-picker__bar">
      <button type="button" class="btn btn--ghost btn--sm" data-act="runners">${Icons.back}${esc(s.dev.name || 'Runners')}</button>
    </div>`;

  const actions = (path, label, act) => `
    <div class="modal-box__actions">
      <button type="button" class="btn btn--primary" data-act="${act}" ${act === 'select' && !path ? 'disabled' : ''}>${label}</button>
      <button type="button" class="btn btn--ghost" data-act="cancel">Cancel</button>
    </div>`;

  function renderBrowse() {
    const s = st;
    const r = s.res;
    const path = r.path || '';
    const cs = crumbs(path);
    const up = r.parent !== undefined && r.parent !== null;
    const entries = (r.entries || []).slice().sort((a, b) => a.name.localeCompare(b.name));
    body().innerHTML = `
      ${barHtml(s)}
      <nav class="folder-crumbs" aria-label="Folder path">
        ${cs.map((c, i) => (i === cs.length - 1
    ? `<span class="folder-crumbs__item folder-crumbs__item--current" aria-current="location">${esc(c.label)}</span>`
    : `<button type="button" class="folder-crumbs__item" data-path="${esc(c.path)}">${esc(c.label)}</button>`)).join('<span class="folder-crumbs__sep" aria-hidden="true">›</span>')}
      </nav>
      <div class="folder-picker__list" data-list>
        ${up ? `<button type="button" class="folder-row folder-row--up" data-path="${esc(r.parent)}">${Icons.up}<span class="folder-row__name">..</span></button>` : ''}
        ${entries.map((e) => `
          <button type="button" class="folder-row" data-path="${esc(join(path, e.name))}">
            ${Icons.folder}<span class="folder-row__name">${esc(e.name)}</span>${Icons.chevron}
          </button>`).join('')}
        ${entries.length ? '' : note('No subfolders')}
      </div>
      <div class="folder-picker__path">${path ? `Selected: <code>${esc(path)}</code>` : 'Open a drive or folder to select it.'}</div>
      <div class="form-group__error" data-err></div>
      ${actions(path, 'Select this folder', 'select')}`;
    focusFirst();
  }

  async function select(btn) {
    const s = st;
    const path = s.res && s.res.path;
    if (!path) return;
    btn.disabled = true;
    btn.textContent = 'Linking…';
    try {
      const project = await RelayApi.call(EP.PROJECT_LINK(s.projectId), 'POST', { deviceId: s.dev.id, path });
      if (st !== s) return;
      close();
      s.onLinked(project, folderName(path));
    } catch (e) {
      if (st !== s) return;
      body().querySelector('[data-err]').textContent = e.message;
      btn.disabled = false;
      btn.textContent = 'Select this folder';
    }
  }

  // ── events ────────────────────────────────────────────────────────────
  const rows = () => [...body().querySelectorAll('.folder-row:not(:disabled)')];
  function focusFirst() {
    const r = rows();
    (r.find((b) => !b.classList.contains('folder-row--up')) || r[0] || body().querySelector('[data-act]'))?.focus();
  }

  function goUp() {
    const r = st && st.dev && st.res;
    if (r && r.parent !== undefined && r.parent !== null) browse(r.parent);
  }

  function onClick(e) {
    if (!st) return;
    const b = e.target.closest('button');
    if (!b || b.disabled) return;
    if (b.dataset.dev) {
      st.dev = (st.devices || []).find((d) => d.id === b.dataset.dev);
      st.res = null;
      if (st.dev) browse(ENV.REPO.PICKER_START);
    } else if (b.dataset.path !== undefined) browse(b.dataset.path);
    else if (b.dataset.act === 'runners' || b.dataset.act === 'retry-devices') showDevices();
    else if (b.dataset.act === 'retry-browse') browse(st.retryPath);
    else if (b.dataset.act === 'select') select(b);
    else if (b.dataset.act === 'cancel') close();
  }

  function onKey(e) {
    if (!st) return;
    if (e.key === 'Escape') { e.stopPropagation(); close(); return; }
    const r = rows();
    const i = r.indexOf(document.activeElement);
    if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
      if (!r.length) return;
      e.preventDefault();
      const n = i < 0 ? 0 : (i + (e.key === 'ArrowDown' ? 1 : r.length - 1)) % r.length;
      r[n].focus();
    } else if (e.key === 'ArrowRight' && i >= 0) {
      e.preventDefault();
      r[i].click();
    } else if ((e.key === 'ArrowLeft' || e.key === 'Backspace') && i >= 0) {
      e.preventDefault();
      goUp();
    }
  }

  function init() {
    body().addEventListener('click', onClick);
    $('folder-modal').addEventListener('keydown', onKey);
    $('folder-modal').querySelector('.modal-box__close').addEventListener('click', close);
  }

  return { init, open, close, folderName };
})();
