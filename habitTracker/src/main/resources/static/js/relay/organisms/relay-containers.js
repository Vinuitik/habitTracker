// #/d/<dev>/p/<pid>/containers: docker compose control for one project (shared/API.md "ContainersStatus").
// RelayContainers.mount(el, {dev, pid}) → unmount (contract: js/relay/router.js). Needs atoms/menu.js, molecules/step-list.js.
// Start / stop run in the background on the runner: poll fast while `operation` is set, slowly otherwise.
// Logs: not in the runner API [NOT IMPLEMENTED].
window.RelayContainers = (() => {
  const { esc } = Dom;
  const R = Relay;
  const EP = ENV.ENDPOINTS;
  const POLL_BUSY_MS = ENV.TIMING.CONTAINERS_POLL_BUSY_MS;
  const POLL_IDLE_MS = ENV.TIMING.CONTAINERS_POLL_IDLE_MS;
  const OP_LABEL = { starting: 'Starting', switching: 'Switching', stopping: 'Stopping' };

  function mount(el, { dev, pid }) {
    const base = (tail = '') => EP.RELAY_V1(dev, `/projects/${R.enc(pid)}/containers${tail}`);
    let dead = false;
    let st = null;          // last ContainersStatus
    let err = '';           // last call error (hub / network), separate from st.lastError
    let opSince = 0;        // client ms when the current operation was first seen
    let pollTimer = 0;
    let tickTimer = 0;
    let sig = '';

    const running = () => (st ? st.containers.filter((c) => c.state === 'running') : []);
    // Containers carry the file they came from: truer than activeFile if someone ran compose by hand.
    const runningFile = () => {
      const r = running();
      return (r[0] && r[0].composeFile) || (r.length && st.activeFile) || '';
    };

    const lamp = (up) => `<span class="lamp${up ? ' lamp--on' : ''}" role="img" aria-label="${up ? 'running' : 'stopped'}"></span>`;

    function fileCard(file, upFile, busy) {
      const isUp = file === upFile;
      const cls = upFile ? 'btn--ghost' : 'btn--primary';
      const label = isUp ? 'Restart' : upFile ? 'Switch here' : 'Start';
      const count = st.containers.filter((c) => c.composeFile === file).length;
      return `<div class="relay-row cont-file">
        ${lamp(isUp)}
        <span class="relay-row__body">
          <span class="relay-row__name">${esc(file)}</span>
          <span class="relay-row__meta">${isUp ? 'running' : 'stopped'}${count ? ` · ${count} container${count > 1 ? 's' : ''}` : ''}${file === st.activeFile ? ' · active' : ''}</span>
        </span>
        <button class="btn ${cls}" data-act="start" data-file="${esc(file)}"${busy ? ' disabled' : ''}>${label}</button>
      </div>`;
    }

    function serviceRow(c) {
      const up = c.state === 'running';
      return `<div class="relay-row cont-service">
        ${lamp(up)}
        <span class="relay-row__body">
          <span class="relay-row__name">${esc(c.service)}</span>
          <span class="relay-row__meta">${esc(c.composeFile)}</span>
        </span>
        <span class="badge ${up ? 'badge--success' : 'badge--muted'}">${esc(c.state)}</span>
      </div>`;
    }

    function render() {
      const head = R.header('Containers', [`#/d/${R.enc(dev)}`, 'Sessions'],
        '<button class="btn btn--ghost" data-act="refresh">Refresh</button>');
      if (!st) {
        el.innerHTML = head + (err ? R.errorHtml({ message: err }) : '<div class="relay-empty">Loading…</div>');
        return;
      }
      const busy = !!st.operation;
      const up = running();
      const upFile = runningFile();
      const title = busy ? `${OP_LABEL[st.operation] || st.operation}…`
        : up.length ? `Up · ${up.length} of ${st.containers.length} running` : 'Everything is down';

      let body = `<div class="relay-card cont-status">
        <div class="cont-status__line">${lamp(up.length > 0 && !busy)}<span class="cont-status__title">${esc(title)}</span>
          ${busy ? '<span class="cont-status__time" id="cont-time"></span>' : ''}</div>
        ${upFile && !busy ? `<div class="relay-card__label">from ${esc(upFile)}</div>` : ''}
        ${busy ? StepList.html([{ seq: 0, kind: 'step', text: `${title} ${st.activeFile || ''}`.trim(), t: opSince }], true) : ''}
        ${st.dockerError ? `<div class="relay-empty--error cont-status__msg">Docker unavailable: ${esc(st.dockerError)}</div>` : ''}
        ${!busy && st.lastError ? `<div class="relay-empty--error cont-status__msg">Last operation failed: ${esc(st.lastError)}</div>` : ''}
        ${err ? `<div class="relay-empty--error cont-status__msg">${esc(err)}</div>` : ''}
      </div>`;

      if (!st.composeFiles.length) {
        body += `<div class="relay-empty">${st.hasDockerfile
          ? "Add a docker-compose.yml next to this project's Dockerfile to run it from here."
          : "Add a docker-compose.yml to this project's top-level folder to run it from here."}</div>`;
      } else {
        body += `<h2 class="relay-section">${st.composeFiles.length > 1 ? 'Compose files' : 'Compose file'}</h2>
          <div class="relay-list">${st.composeFiles.map((f) => fileCard(f, upFile, busy)).join('')}</div>`;
        if (st.containers.length) {
          body += `<h2 class="relay-section">Services</h2><div class="relay-list">${st.containers.map(serviceRow).join('')}</div>`;
        }
        body += `<div class="cont-danger"><button class="btn btn--ghost cont-danger__btn" data-act="stop"${busy || !st.containers.length ? ' disabled' : ''}>Take everything down</button></div>`;
      }
      body += '<p class="relay-hint">Logs: not available yet (the runner API has no logs endpoint).</p>';
      el.innerHTML = head + body;
      tick();
    }

    function tick() {
      const t = el.querySelector('#cont-time');
      if (t && opSince) {
        const s = Math.max(0, Math.round((Date.now() - opSince) / 1000));
        t.textContent = `${Math.floor(s / 60)}:${String(s % 60).padStart(2, '0')}`;
      }
      StepList.tick(el);
    }

    function apply(s) {
      st = s;
      err = '';
      if (!s.operation) opSince = 0; else if (!opSince) opSince = Date.now();
      const next = JSON.stringify([s, err]);
      if (next !== sig) { sig = next; render(); }
    }

    function schedule() {
      clearTimeout(pollTimer);
      if (dead) return;
      pollTimer = setTimeout(refresh, st && st.operation ? POLL_BUSY_MS : POLL_IDLE_MS);
    }

    async function refresh() {
      clearTimeout(pollTimer);
      try {
        const s = await R.call(base());
        if (dead) return;
        apply(s);
      } catch (e) {
        if (dead) return;
        err = e.message;
        sig = '';
        render();
      }
      schedule();
    }

    async function act(tail, body, verb) {
      try {
        const s = await R.call(base(tail), 'POST', body);
        if (dead) return;
        apply(s);
      } catch (e) {
        if (dead) return;
        Toast.show(e.status === 409 ? 'Another operation is already running' : `${verb} failed: ${e.message}`, 4000);
      }
      refresh();
    }

    const offClick = R.onAct(el, {
      refresh,
      start: (b) => act('/start', { file: b.dataset.file }, 'Start'),
      stop: (b) => Menu.open(b, [{
        label: 'Take down', danger: true,
        confirm: "Stops and removes every container this project's compose files started.",
        onClick: () => act('/stop', undefined, 'Stop'),
      }]),
    });

    render();
    refresh();
    tickTimer = setInterval(tick, 1000);
    return () => {
      dead = true;
      clearTimeout(pollTimer);
      clearInterval(tickTimer);
      offClick();
      Menu.close();
    };
  }

  return { mount };
})();
