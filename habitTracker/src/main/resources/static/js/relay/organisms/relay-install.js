// #/install: install-a-runner guide with Linux / Windows tabs, pair/re-pair card, troubleshooting.
// RelayInstall.mount(el, ctx) → unmount (contract: js/relay/router.js). Selected OS tab survives navigation.
window.RelayInstall = (() => {
  const R = Relay;
  const { OS, INSTALL_CMD, PAIR_CMD, LINUX_AS_USER, ownHub, cmdBlock, codeBox } = R;
  let guideOs = OS;     // tab selected in #/install

  const GUIDE = {
    linux: {
      label: 'Linux',
      needs: 'Linux with systemd (amd64, arm64 or arm). The installer also installs the Claude CLI via npm.',
      open: 'Open a terminal on that machine.',
      service: 'a systemd service, relay-runner@&lt;your user&gt;',
      repair: LINUX_AS_USER + PAIR_CMD.linux,
      repairHint: 'Replace &lt;your user&gt; with the account the runner serves (the one you installed it for). '
        + 'Logged in as that user already? Drop the <code>sudo -u …</code> part.',
      offline: `<p class="relay-hint">Is the service running, and what does it log?</p>
        ${cmdBlock('systemctl status relay-runner@<your user>')}
        ${cmdBlock('journalctl -u relay-runner@<your user> -f')}`,
      wrongUser: 'Pairing saves the token in the home of whoever ran the command. Run it as root or as another '
        + 'account and the service (running as you) never sees it. Revoke the stray device, then pair again with '
        + '<code>sudo -u &lt;your user&gt;</code> as shown above.',
    },
    windows: {
      label: 'Windows',
      needs: 'Windows 10 or 11, x64.',
      open: 'Open PowerShell as administrator: Start → type <strong>PowerShell</strong> → right-click → <strong>Run as administrator</strong>.',
      service: 'a background task, “Relay keeper” in Task Scheduler',
      repair: PAIR_CMD.windows,
      repairHint: 'Run it in PowerShell under the same Windows account you installed with.',
      offline: `<p class="relay-hint">Open <strong>Task Scheduler</strong> → Task Scheduler Library → <strong>Relay keeper</strong>.
        Its status should be Running; if not, right-click → Run, and check the Last Run Result.</p>`,
      wrongUser: 'Pairing saves the token in the profile of the account that ran it (%LOCALAPPDATA%). Run it from '
        + 'another account and the runner never sees it. Revoke the stray device and pair again from your own account.',
    },
  };

  function render(el) {
    const g = GUIDE[guideOs];
    const tab = (os) => `<button class="btn btn--sm ${os === guideOs ? 'btn--primary' : 'btn--ghost'}"
      data-act="os" data-os="${os}" aria-pressed="${os === guideOs}">${GUIDE[os].label}</button>`;
    const hubNote = ownHub ? '' : `<p class="relay-hint">This HabitTracker isn't the runner's built-in hub, so the installer's
      QR points elsewhere: press Ctrl-C at the QR and run the pair command under “Already installed” instead.</p>`;
    el.innerHTML = R.header('Install a runner', ['#/', 'Devices']) + `
      <div id="install-guide">
        <p class="relay-hint relay-intro">A runner is a small background service on your computer. Once it's paired,
          Relay can start and drive agent sessions there from this page or your phone. No Tailscale and no open
          ports: the machine only needs outbound HTTPS.</p>
        <div class="relay-tabs" role="group" aria-label="Operating system">${tab('linux')}${tab('windows')}</div>
        <p class="relay-hint">Needs: ${g.needs}</p>

        <ol class="relay-steps">
          <li class="relay-step">
            <span class="relay-step__title">Open a shell</span>
            <span class="relay-hint">${g.open}</span>
          </li>
          <li class="relay-step">
            <span class="relay-step__title">Run the installer</span>
            ${cmdBlock(INSTALL_CMD[guideOs])}
            <span class="relay-hint">It installs the runner as ${g.service}, so it starts with the computer.</span>
          </li>
          <li class="relay-step">
            <span class="relay-step__title">Scan the QR with your phone</span>
            <span class="relay-hint">When it's done the terminal prints a QR code. Scan it with your phone camera:
              HabitTracker opens “Connect &lt;machine&gt;?”. Tap Confirm. (Ctrl-C skips this; pair later below.)</span>
            ${hubNote}
          </li>
          <li class="relay-step">
            <span class="relay-step__title">Done</span>
            <span class="relay-hint">Within seconds the machine shows up online under Devices.</span>
            <a class="btn btn--primary relay-step__action" href="#/">Go to devices</a>
          </li>
        </ol>

        <div class="relay-card">
          <div class="relay-card__label">Already installed · pair or re-pair</div>
          ${cmdBlock(g.repair + (ownHub ? '' : ` --hub ${location.origin}`))}
          <p class="relay-hint">${g.repairHint} It prints a fresh QR (valid 10 minutes).</p>
          <div class="relay-card__label">Desktop alternative · pairing code</div>
          <p class="relay-hint">No phone at hand? Get a code and add <code>--code &lt;CODE&gt;</code> to the same command.</p>
          ${codeBox(guideOs)}
        </div>

        <h2 class="relay-section">Troubleshooting</h2>
        <details class="relay-faq">
          <summary>The QR expired, or I pressed Ctrl-C</summary>
          <p class="relay-hint">A QR lives 10 minutes and works once. Run the pair command above again for a new one.</p>
        </details>
        <details class="relay-faq">
          <summary>Paired, but it shows offline</summary>
          ${g.offline}
        </details>
        <details class="relay-faq">
          <summary>I ran pair as the wrong user</summary>
          <p class="relay-hint">${g.wrongUser}</p>
        </details>
        <details class="relay-faq">
          <summary>It was online, now it's offline</summary>
          <p class="relay-hint">Usually the computer is asleep: a sleeping machine can't answer. It reconnects by itself
            within about a minute of waking. For an always-reachable runner, turn off sleep while plugged in.</p>
        </details>
      </div>`;
  }

  function mount(el) {
    render(el);
    return R.onAct(el, {
      os: (b) => { guideOs = b.dataset.os; render(el); },
      code: R.getCode,
      copy: R.copyCommand,
    });
  }

  return { mount };
})();
