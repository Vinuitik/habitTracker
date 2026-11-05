// #/d/<dev>/p/<pid>/files: file browser + editor. Breadcrumb + folder/file list, text viewer beside it (stacked on phones).
// RelayFiles.mount(el, {dev, pid, refresh}) → unmount (contract: js/relay/router.js).
// Runner: GET /projects/{pid}/files?path= (FileEntry[]), /files/content?path= ({content, hash}; 400 bad path, 404, 413 >1MiB, 415 binary).
// Edit: Edit → textarea → Save = PUT /files/content {path, content, baseHash}; 409 = changed since opened (Claude / VS Code
// wrote it) → Reload. Existing text files only, no create/delete. Runner without `hash` (older build) → no Edit button.
// The hub proxy caps bodies at 4 MiB, so an oversized response may arrive as a non-413 error: shown the same way.
window.RelayFiles = (() => {
  const { $, esc } = Dom;
  const R = Relay;
  const EP = ENV.ENDPOINTS;

  const join = (dir, name) => (dir ? dir + '/' + name : name);
  const sizeText = (n) => (n < 1024 ? n + ' B' : n < 1048576 ? (n / 1024).toFixed(1) + ' KB' : (n / 1048576).toFixed(1) + ' MB');
  const noView = (e) => (e.status === 413 ? 'Too large to preview (over 1 MiB).'
    : e.status === 415 ? 'Binary file, no preview.'
      : e.status === 404 ? 'File no longer exists.' : e.message);

  function mount(el, ctx) {
    const { dev, pid } = ctx;
    const base = EP.RELAY_V1(dev, `/projects/${R.enc(pid)}/files`);
    let alive = true;
    let dir = '';       // project-relative folder being listed
    let open = '';      // project-relative file in the viewer
    let listSeq = 0;    // drop stale list / content responses
    let viewSeq = 0;
    let file = null;    // {path, content, hash} of the open file
    let editing = false;

    el.innerHTML = R.header('Files', [`#/d/${R.enc(dev)}`, 'Sessions'],
      '<button class="btn btn--ghost" data-act="refresh">Refresh</button>') + `
      <nav class="relay-files__crumbs" id="files-crumbs" aria-label="Path"></nav>
      <div class="relay-files">
        <div class="relay-files__list relay-list" id="files-list"><div class="relay-empty">Loading…</div></div>
        <section class="relay-files__view" id="files-view" aria-label="File content">
          <div class="relay-empty">Select a file to view or edit it.</div>
        </section>
      </div>`;

    function crumbs() {
      let acc = '';
      $('files-crumbs').innerHTML = '<button class="relay-files__crumb" data-dir="">project</button>'
        + (dir ? dir.split('/') : []).map((p) => {
          acc = join(acc, p);
          return `<span class="relay-files__sep">/</span><button class="relay-files__crumb" data-dir="${esc(acc)}">${esc(p)}</button>`;
        }).join('');
    }

    async function list() {
      const my = ++listSeq;
      crumbs();
      $('files-list').innerHTML = '<div class="relay-empty">Loading…</div>';
      try {
        const items = await R.call(base + (dir ? `?path=${R.enc(dir)}` : ''));
        if (!alive || my !== listSeq) return;
        items.sort((a, b) => (b.isDir - a.isDir) || a.name.localeCompare(b.name));
        $('files-list').innerHTML = items.length ? items.map((f) => {
          const p = join(dir, f.name);
          return f.isDir
            ? `<button class="relay-row relay-files__item" data-dir="${esc(p)}"><span class="relay-files__name">${esc(f.name)}/</span></button>`
            : `<button class="relay-row relay-files__item${p === open ? ' relay-files__item--on' : ''}" data-file="${esc(p)}">
                <span class="relay-files__name">${esc(f.name)}</span><span class="relay-hint">${sizeText(f.size)}</span></button>`;
        }).join('') : '<div class="relay-empty">Empty folder.</div>';
      } catch (e) {
        if (alive && my === listSeq) $('files-list').innerHTML = R.errorHtml(e);
      }
    }

    const viewHead = (path, actions = '') => `<div class="relay-files__path"><span class="relay-files__pathtext">${esc(path)}</span>${actions}</div>`;

    function paintFile() {
      const box = $('files-view');
      if (editing) {
        box.innerHTML = viewHead(file.path, `<span class="relay-files__acts">
            <button class="btn btn--ghost btn--sm" data-act="edit-cancel">Cancel</button>
            <button class="btn btn--primary btn--sm" data-act="save" id="files-save">Save</button></span>`)
          + '<textarea class="relay-files__edit" id="files-edit" spellcheck="false" autocapitalize="off" autocomplete="off" aria-label="File content"></textarea>';
        const ta = $('files-edit');
        ta.value = file.content;
        ta.focus();
        return;
      }
      box.innerHTML = viewHead(file.path, file.hash
        ? '<span class="relay-files__acts"><button class="btn btn--ghost btn--sm" data-act="edit">Edit</button></span>' : '')
        + (file.content ? `<pre class="relay-files__pre">${esc(file.content)}</pre>` : '<div class="relay-empty">Empty file.</div>')
        + (file.hash ? '' : '<div class="relay-hint relay-files__note">Update the runner to edit files from here.</div>');
    }

    async function view(path) {
      if (editing && path !== open && !confirmLeave()) return;
      open = path;
      editing = false;
      file = null;
      const my = ++viewSeq;
      el.querySelectorAll('[data-file]').forEach((b) => b.classList.toggle('relay-files__item--on', b.dataset.file === path));
      const box = $('files-view');
      box.innerHTML = viewHead(path) + '<div class="relay-empty">Loading…</div>';
      try {
        const { content, hash } = await R.call(`${base}/content?path=${R.enc(path)}`);
        if (!alive || my !== viewSeq) return;
        file = { path, content: content || '', hash: hash || '' };
        paintFile();
      } catch (e) {
        if (alive && my === viewSeq) box.innerHTML = viewHead(path) + `<div class="relay-empty">${esc(noView(e))}</div>`;
      }
    }

    // Unsaved edits: leaving needs a second tap on the same target (no dialogs in the PWA).
    let leaveArmed = 0;
    function confirmLeave() {
      const dirty = $('files-edit') && $('files-edit').value !== file.content;
      if (!dirty || leaveArmed > Date.now()) { leaveArmed = 0; return true; }
      leaveArmed = Date.now() + 4000;
      Toast.show('Unsaved changes: tap again to discard them', 4000);
      return false;
    }

    async function save() {
      const btn = $('files-save');
      const content = $('files-edit').value;
      btn.disabled = true;
      btn.textContent = 'Saving…';
      try {
        const r = await R.call(`${base}/content`, 'PUT', { path: file.path, content, baseHash: file.hash });
        if (!alive || !file || file.path !== r.path) return;
        file = { path: r.path, content: r.content, hash: r.hash };
        editing = false;
        paintFile();
        Toast.show('Saved', 2500);
        list();   // sizes changed
      } catch (e) {
        if (!alive) return;
        btn.disabled = false;
        btn.textContent = 'Save';
        Toast.show(e.status === 409 ? 'Changed on the machine since you opened it. Copy your edit, then Reload.'
          : e.status === 405 ? 'Update the runner to edit files' : `Save failed: ${e.message}`, 6000);
      }
    }

    const onClick = (ev) => {
      const t = ev.target.closest('[data-dir],[data-file],[data-act]');
      if (!t) return;
      const act = t.dataset.act;
      if (act === 'edit') { editing = true; paintFile(); }
      else if (act === 'edit-cancel') { if (confirmLeave()) { editing = false; paintFile(); } }
      else if (act === 'save') save();
      else if (act === 'refresh') { list(); if (open && (!editing || confirmLeave())) { editing = false; view(open); } }
      else if (act) return;
      else if (t.dataset.dir !== undefined) { dir = t.dataset.dir; list(); }
      else view(t.dataset.file);
    };
    el.addEventListener('click', onClick);
    list();
    return () => { alive = false; el.removeEventListener('click', onClick); };
  }

  return { mount };
})();
