// #/d/<dev>/p/<pid>/files: read-only file browser. Breadcrumb + folder/file list, text viewer beside it (stacked on phones).
// RelayFiles.mount(el, {dev, pid, refresh}) → unmount (contract: js/relay/router.js).
// Runner: GET /projects/{pid}/files?path= (FileEntry[]), /files/content?path= (400 bad path, 404, 413 >1MiB, 415 binary).
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

    el.innerHTML = R.header('Files', [`#/d/${R.enc(dev)}`, 'Sessions'],
      '<button class="btn btn--ghost" data-act="refresh">Refresh</button>') + `
      <nav class="relay-files__crumbs" id="files-crumbs" aria-label="Path"></nav>
      <div class="relay-files">
        <div class="relay-files__list relay-list" id="files-list"><div class="relay-empty">Loading…</div></div>
        <section class="relay-files__view" id="files-view" aria-label="File content">
          <div class="relay-empty">Select a file to view it.</div>
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

    async function view(path) {
      open = path;
      const my = ++viewSeq;
      el.querySelectorAll('[data-file]').forEach((b) => b.classList.toggle('relay-files__item--on', b.dataset.file === path));
      const box = $('files-view');
      const head = `<div class="relay-files__path">${esc(path)}</div>`;
      box.innerHTML = head + '<div class="relay-empty">Loading…</div>';
      try {
        const { content } = await R.call(`${base}/content?path=${R.enc(path)}`);
        if (!alive || my !== viewSeq) return;
        box.innerHTML = head + (content ? `<pre class="relay-files__pre">${esc(content)}</pre>` : '<div class="relay-empty">Empty file.</div>');
      } catch (e) {
        if (alive && my === viewSeq) box.innerHTML = head + `<div class="relay-empty">${esc(noView(e))}</div>`;
      }
    }

    const onClick = (ev) => {
      const t = ev.target.closest('[data-dir],[data-file],[data-act="refresh"]');
      if (!t) return;
      if (t.dataset.act === 'refresh') { list(); if (open) view(open); }
      else if (t.dataset.dir !== undefined) { dir = t.dataset.dir; list(); }
      else view(t.dataset.file);
    };
    el.addEventListener('click', onClick);
    list();
    return () => { alive = false; el.removeEventListener('click', onClick); };
  }

  return { mount };
})();
