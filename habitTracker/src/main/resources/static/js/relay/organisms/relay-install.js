// #/install: one onboarding flow. Windows: download Relay-Setup.exe → run → confirm in browser (installer opens
// /relay/claim/<id>). Linux: one-line command. Both end in a live "waiting for your computer" panel that polls
// /relay/api/devices. Technical bits live in one collapsed <details>. RelayInstall.mount(el, ctx) → unmount
// (contract: js/relay/router.js). The chosen OS survives navigation.
window.RelayInstall = (() => {
  const R = Relay;
  const { esc } = Dom;
  const { OS, INSTALL_CMD, PAIR_CMD, PAIR_CMD_FULL, LINUX_AS_USER, ownHub, hubFlag, cmdBlock, codeBox } = R;
  const EP = ENV.ENDPOINTS;
  let guideOs = OS;     // survives navigation

  const labelled = (label, cmd) => `<div class="relay-card__label">${label}</div>${cmdBlock(cmd)}`;

  function advanced() {
    const win = guideOs === 'windows';
    const pair = win
      ? `<p class="relay-hint">Already installed, or the installer's browser step didn't open? In a <strong>new</strong> terminal (cmd or PowerShell):</p>
         ${cmdBlock(PAIR_CMD.windows + hubFlag)}
         <p class="relay-hint">If “not recognized”, use the full path for your shell:</p>
         ${labelled('Command Prompt (cmd)', PAIR_CMD_FULL.cmd + hubFlag)}
         ${labelled('PowerShell', PAIR_CMD_FULL.powershell + hubFlag)}`
      : `<p class="relay-hint">Already installed? Pair or re-pair as the account the runner serves
           (drop <code>sudo -u …</code> if you are that user already). It prints a QR, valid 10 minutes:</p>
         ${cmdBlock(LINUX_AS_USER + PAIR_CMD.linux + hubFlag)}`;
    const other = win
      ? `<details class="relay-faq"><summary>Install from PowerShell instead</summary>
           <p class="relay-hint">Same result as the setup wizard, no download. Run in PowerShell as administrator:</p>
           ${cmdBlock(INSTALL_CMD.windows)}</details>`
      : '';
    const offline = win
      ? `<p class="relay-hint">Open <strong>Task Scheduler</strong> → Task Scheduler Library → <strong>Relay keeper</strong>.
           Its status should be Running; if not, right-click → Run, and check the Last Run Result.</p>`
      : `<p class="relay-hint">Is the service running, and what does it log?</p>
         ${cmdBlock('systemctl status relay-runner@<your user>')}
         ${cmdBlock('journalctl -u relay-runner@<your user> -f')}`;
    const wrongUser = win
      ? 'Pairing saves the token in the profile of the account that ran it (%LOCALAPPDATA%). Run it from another account and the runner never sees it. Revoke the stray device and pair again from your own account.'
      : 'Pairing saves the token in the home of whoever ran the command. Run it as root or another account and the service (running as you) never sees it. Revoke the stray device, then pair again with <code>sudo -u &lt;your user&gt;</code>.';
    const reconnect = win
      ? `<details class="relay-faq"><summary>Re-connect without a terminal</summary>
           <p class="relay-hint">Start menu → <strong>Connect Relay to HabitTracker</strong>. It opens this site to confirm the machine again.</p></details>`
      : '';
    const hubNote = ownHub ? '' : `<p class="relay-hint">This HabitTracker isn't the runner's built-in hub, so the installer's
      QR points elsewhere: press Ctrl-C at the QR and run the pair command above instead.</p>`;
    return `<details class="relay-more">
      <summary>Advanced &amp; troubleshooting</summary>
      <div class="relay-more__body">
        ${pair}${hubNote}
        <div class="relay-card__label">No phone or browser step? Pairing code</div>
        <p class="relay-hint">Get a code and add <code>--code &lt;CODE&gt;</code> to the pair command.</p>
        ${codeBox(guideOs)}
        <h2 class="relay-section">Troubleshooting</h2>
        ${other}${reconnect}
        <details class="relay-faq"><summary>The QR or confirm link expired</summary>
          <p class="relay-hint">A pairing link lives 10 minutes and works once. Run the pair command again for a new one.</p></details>
        <details class="relay-faq"><summary>Paired, but it shows offline</summary>${offline}</details>
        <details class="relay-faq"><summary>I paired as the wrong user</summary><p class="relay-hint">${wrongUser}</p></details>
        <details class="relay-faq"><summary>It was online, now it's offline</summary>
          <p class="relay-hint">Usually the computer is asleep: a sleeping machine can't answer. It reconnects by itself
            within about a minute of waking. For an always-reachable runner, turn off sleep while plugged in.</p></details>
      </div></details>`;
  }

  const switchLink = () => `<button class="relay-link relay-switch" data-act="os" data-os="${guideOs === 'windows' ? 'linux' : 'windows'}">
    Using ${guideOs === 'windows' ? 'Linux' : 'Windows'}?</button>`;

  function windowsView() {
    return `
      <section class="relay-hero relay-in">
        <h2 class="relay-hero__title">Let Relay work on your computer</h2>
        <p class="relay-hero__lede">A small background app that lets this site start and drive agent sessions on your PC. Nothing to open on your router.</p>
        <a class="btn btn--primary relay-hero__cta" data-act="download" href="${esc(ENV.RELAY.WINDOWS_SETUP_URL)}">Download Relay for Windows</a>
        <span class="relay-hint">Windows 10/11 · x64 · free &amp; open source</span>
      </section>
      <ol class="relay-steps relay-in">
        <li class="relay-step"><span class="relay-step__title">Download</span>
          <span class="relay-hint">Save <code>Relay-Setup.exe</code> from the button above.</span></li>
        <li class="relay-step"><span class="relay-step__title">Run it</span>
          <span class="relay-hint">Open the file and click through the setup wizard.</span>
          <div class="relay-callout">Windows may say “Windows protected your PC” because Relay is unsigned open-source software.
            Click <strong>More info</strong> → <strong>Run anyway</strong>.
            <a class="relay-link" href="${esc(ENV.RELAY.SOURCE_URL)}" target="_blank" rel="noopener">See the source code</a></div></li>
        <li class="relay-step"><span class="relay-step__title">Confirm in your browser</span>
          <span class="relay-hint">When the wizard finishes it opens this site and asks “Connect &lt;your computer&gt;?”. Click Confirm.</span></li>
      </ol>`;
  }

  function linuxView() {
    return `
      <section class="relay-hero relay-in">
        <h2 class="relay-hero__title">Let Relay work on your computer</h2>
        <p class="relay-hero__lede">One command installs a small background service (systemd; amd64, arm64 or arm). Paste it into a terminal.</p>
        <div class="relay-cmd relay-cmd--big"><code>${esc(INSTALL_CMD.linux)}</code>
          <button class="btn btn--primary" data-act="copy-start">Copy</button></div>
        <span class="relay-hint">Not sure how to open a terminal? Press <kbd>Ctrl</kbd>+<kbd>Alt</kbd>+<kbd>T</kbd>, or search “Terminal” in your app menu.
          When it finishes it prints a QR code and a link: open the link on any device logged in here and click Confirm.</span>
      </section>`;
  }

  function mount(el) {
    let mounted = true;
    // Waiting-panel state lives in the closure: survives re-render when switching OS.
    const w = { status: 'idle', baseline: null, device: null, timer: null, since: 0, busy: false };

    const panel = () => {
      if (w.status === 'idle') {
        return `<div class="relay-wait relay-wait--idle"><button class="relay-link" data-act="wait">I already ran it</button></div>`;
      }
      if (w.status === 'done') {
        return `<div class="relay-wait relay-wait--done" role="status">
          <span class="relay-wait__title">✓ ${esc(w.device.name || 'Your computer')} is connected</span>
          <a class="btn btn--primary" href="#/">Open devices</a></div>`;
      }
      if (w.status === 'timeout') {
        return `<div class="relay-wait" role="status"><span class="relay-wait__title">Taking longer than expected</span>
          <span class="relay-hint">Check “Advanced &amp; troubleshooting” below
            (expired link, wrong user, machine offline).</span>
          <button class="btn btn--ghost btn--sm" data-act="wait">Keep waiting</button></div>`;
      }
      return `<div class="relay-wait" role="status"><span class="relay-wait__title"><span class="relay-dot relay-dot--pulse"></span>Waiting for your computer…</span>
        <span class="relay-hint">This page updates by itself once you confirm the connection.</span></div>`;
    };
    const paintPanel = () => { const box = el.querySelector('#wait-box'); if (box) box.innerHTML = panel(); };

    function stop() { clearInterval(w.timer); w.timer = null; }

    // New device id, or a known one that went offline → online, relative to the snapshot.
    const arrived = (list) => list.find((d) => !w.baseline.has(d.id) || (d.online && w.baseline.get(d.id) === false));

    async function snapshot() {
      try {
        const list = await R.call(EP.RELAY_DEVICES);
        w.baseline = new Map(list.map((d) => [d.id, !!d.online]));
        return list;
      } catch (_) { return null; }
    }

    async function tick() {
      if (w.busy || !mounted) return;
      if (Date.now() - w.since > ENV.TIMING.RELAY_INSTALL_WAIT_MS) { stop(); w.status = 'timeout'; paintPanel(); return; }
      w.busy = true;
      try {
        const list = await R.call(EP.RELAY_DEVICES);
        const hit = mounted && w.baseline && arrived(list);
        if (hit) { stop(); w.status = 'done'; w.device = hit; list.forEach((d) => R.deviceNames.set(d.id, d.name)); paintPanel(); }
      } catch (_) { /* transient: next tick retries */ } finally { w.busy = false; }
    }

    async function startWaiting() {
      if (w.status === 'waiting' || w.status === 'done') return;
      w.status = 'waiting'; w.since = Date.now(); paintPanel();
      if (!w.baseline) await snapshot();
      if (!mounted || w.status !== 'waiting') return;
      if (!w.baseline) w.baseline = new Map();   // snapshot failed: treat everything as new only after it succeeds
      stop();
      w.timer = setInterval(tick, ENV.TIMING.RELAY_INSTALL_POLL_MS);
    }

    function render() {
      el.innerHTML = R.header('Connect a computer', ['#/', 'Devices']) + `
        <div id="install-guide">
          ${guideOs === 'windows' ? windowsView() : linuxView()}
          <div id="wait-box" class="relay-in">${panel()}</div>
          <div class="relay-in relay-switch-row">${switchLink()}</div>
          <div class="relay-in">${advanced()}</div>
        </div>`;
    }

    render();
    snapshot();   // baseline taken up front, so a quick install can't slip past it
    const off = R.onAct(el, {
      os: (b) => { guideOs = b.dataset.os; render(); },
      download: startWaiting,          // the anchor's default action (the download) still runs
      wait: startWaiting,
      'copy-start': (b) => { R.copyCommand(b); startWaiting(); },
      code: R.getCode,
      copy: R.copyCommand,
    });
    return () => { mounted = false; stop(); off(); };
  }

  return { mount };
})();
