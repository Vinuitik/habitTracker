// Relay page shared kit (relay/FLOWS.md "UI"): hub call + status mapping, header / badge / markdown
// helpers, pair + install commands, pairing-code box, copy button. No routing, no view of its own:
// js/relay/router.js picks the view, js/relay/organisms/* render them. Loaded first of the relay scripts.
// Everything goes through /relay/api; runner calls are ENV.ENDPOINTS.RELAY_V1(dev, path) (shared/API.md).
window.Relay = (() => {
  const { $, esc } = Dom;
  const EP = ENV.ENDPOINTS;
  const enc = encodeURIComponent;
  // Hub baked into the runner (Relay repo: runner/internal/uplink.DefaultHub); any other origin needs --hub.
  const DEFAULT_HUB = 'https://habittrackerdima.me';
  // Installer + pair commands per OS (Relay repo: release install.sh / install.ps1). Keep in sync with the scripts.
  const OS = /Windows/i.test(navigator.userAgent) ? 'windows' : 'linux';   // phones (Android = Linux UA) → linux
  const INSTALL_CMD = {
    linux: 'curl -fsSL https://github.com/Vinuitik/relay/releases/latest/download/install.sh | sudo sh',
    windows: 'irm https://github.com/Vinuitik/relay/releases/latest/download/install.ps1 | iex',
  };
  const PAIR_CMD = {
    linux: '/opt/relay/bin/relay-runner pair',
    windows: 'relay-runner pair',   // on PATH after Relay-Setup.exe (new terminal needed)
  };
  // Full-path forms per shell, for when the PATH entry isn't live yet.
  const PAIR_CMD_FULL = {
    cmd: '"%LOCALAPPDATA%\\Relay\\bin\\relay-runner.exe" pair',
    powershell: '& "$env:LOCALAPPDATA\\Relay\\bin\\relay-runner.exe" pair',
  };
  const LINUX_AS_USER = 'sudo -u <your user> ';   // guide shows the explicit form: pair must run as the service's user
  const ownHub = location.origin === DEFAULT_HUB;
  const hubFlag = ownHub ? '' : ` --hub ${location.origin}`;
  const pairCmd = (extra = '', os = OS) => PAIR_CMD[os] + hubFlag + extra;
  const STATES = {
    busy:     ['Working', ''],
    waiting:  ['Needs you', 'badge--warning'],
    idle:     ['Idle', 'badge--success'],
    finished: ['Finished', 'badge--muted'],
    error:    ['Error', 'badge--danger'],
  };
  const deviceNames = new Map();   // device id → name, filled by the devices list, read by the sessions header

  // Per-user localStorage (last route, chat mode/config prefs): key relay.<k>.<userId from /auth/me>.
  // JSON values; null removes. Storage blocked / /auth/me down → get() is null, set() a no-op.
  let uidP = null;
  const userId = () => (uidP = uidP || Api.request(EP.AUTH_ME).then((r) => (r && r.userId) || '', () => ''));
  const store = {
    async get(k) {
      const key = `relay.${k}.${await userId()}`;
      try { return JSON.parse(localStorage.getItem(key)); } catch (_) { return null; }
    },
    async set(k, v) {
      const key = `relay.${k}.${await userId()}`;
      try { if (v == null) localStorage.removeItem(key); else localStorage.setItem(key, JSON.stringify(v)); } catch (_) { /* storage blocked */ }
    },
  };

  // Claude CLI "subscription used up" wording (mirror of relay/UsageLimit.PHRASES); generic "rate limit" excluded on purpose.
  const USAGE_RE = /usage limit|hit your (usage )?limit|limit reached|out of (extra )?usage|credit balance is too low|rate_limit_error|usage[ _-]?exhausted|quota (exceeded|exhausted)/i;

  // Api.raw + hub status mapping. 401 = 2FA expired (or logged out) → verify, then come back here.
  async function call(url, method, body) {
    try {
      return (await Api.raw(url, method, body)).data;
    } catch (e) {
      if (e.status === 401) {
        location.href = ENV.ROUTES.TWOFA_VERIFY + '?next=' + enc(location.pathname + location.hash);
        e.message = 'Two-factor check expired — redirecting…';
      } else if (USAGE_RE.test(e.message || '')) e.message = 'Your Claude usage is exhausted. Try again once it resets.';
      else if (e.status === 503) e.message = 'Device offline (machine asleep?)';
      else if (e.status === 504) e.message = "Device didn't answer";
      else if (!e.status) e.message = 'Network error — HabitTracker unreachable';
      throw e;
    }
  }

  const errorHtml = (e) => `<div class="relay-empty relay-empty--error">${esc(e.message)}</div>`;
  const stateBadge = (s) => {
    const [label, cls] = STATES[s] || [s || '?', 'badge--muted'];
    return `<span class="badge ${cls}">${esc(label)}</span>`;
  };
  const header = (title, back, actions = '') => `
    ${back ? `<a class="relay-back" href="${back[0]}">← ${back[1]}</a>` : ''}
    <div class="relay-head">
      <h1 class="relay-head__title">${title}</h1>
      <div class="relay-head__actions">${actions}</div>
    </div>`;

  // Markdown-ish: ``` fences → <pre>, blank lines → paragraphs, `code`, **bold**. Escape first, always.
  const inline = (s) => esc(s)
    .replace(/`([^`\n]+)`/g, '<code>$1</code>')
    .replace(/\*\*([^*\n]+)\*\*/g, '<strong>$1</strong>')
    .replace(/\n/g, '<br>');
  const md = (text) => String(text ?? '').split(/^ {0,3}```.*$/m).map((part, i) => (i % 2
    ? `<pre class="relay-pre"><code>${esc(part.replace(/^\n|\n$/g, ''))}</code></pre>`
    : part.split(/\n\s*\n/).map((p) => p.trim()).filter(Boolean).map((p) => `<p>${inline(p)}</p>`).join(''))).join('');

  const cmdBlock = (cmd) => `<div class="relay-cmd"><code>${esc(cmd)}</code>
    <button class="btn btn--ghost btn--sm" data-act="copy">Copy</button></div>`;
  const codeBox = (os) => `<div id="code-box" data-os="${os}"><button class="btn btn--ghost btn--sm" data-act="code">Get a code</button></div>`;

  // Fills #code-box (devices "Add device" card or the install guide) with a single-use pairing code.
  async function getCode() {
    const box = $('code-box');
    const os = box.dataset.os || OS;
    box.innerHTML = 'Getting a code…';
    try {
      const { code, expiresInSeconds } = await call(EP.RELAY_PAIRING_CODE, 'POST');
      box.innerHTML = `
        <div class="relay-card__label">Single use · ${Math.round(expiresInSeconds / 60)} min</div>
        <div class="relay-code">${esc(code)}</div>
        <div class="relay-card__label">Run on the machine</div>
        ${cmdBlock((os === 'linux' && box.closest('#install-guide') ? LINUX_AS_USER : '') + pairCmd(` --code ${code}`, os))}
        <p class="relay-hint">The device appears in the list once it connects (Refresh).</p>`;
    } catch (e) {
      box.innerHTML = errorHtml(e);
    }
  }

  // Copies the command next to the button (any .relay-cmd).
  async function copyCommand(btn) {
    const el = btn.closest('.relay-cmd').querySelector('code');
    try {
      await navigator.clipboard.writeText(el.textContent);
      btn.textContent = 'Copied';
    } catch (_) { // no clipboard API (http, old browser) → select it for a manual copy
      const r = document.createRange();
      r.selectNodeContents(el);
      getSelection().removeAllRanges();
      getSelection().addRange(r);
      btn.textContent = 'Selected';
    }
    setTimeout(() => { btn.textContent = 'Copy'; }, 2000);
  }

  // Context-size tag (Session/AgentChat.contextTokens, runner context.go): tokens every next message
  // re-sends, so cost per message grows with it. '' when unknown (older runner, no transcript).
  const ctxLevel = (n) => (n >= ENV.LIMITS.RELAY_CTX_HIGH ? 'high' : n >= ENV.LIMITS.RELAY_CTX_WARN ? 'warn' : 'ok');
  const ctxText = (n) => (n >= 1e6 ? `${(n / 1e6).toFixed(1)}M` : `${Math.max(1, Math.round(n / 1000))}k`);
  const ctxTag = (n) => (n > 0
    ? `<span class="ctx-tag ctx-tag--${ctxLevel(n)}" title="Each message re-sends about ${ctxText(n)} tokens. ${
      ctxLevel(n) === 'high' ? 'That is ~2× a fresh chat: /compact it or start a new chat.' : 'Cost per message grows with this.'}">${ctxText(n)}</span>`
    : '');

  // Click delegation helper for organisms: handlers = {act: (button) => …}; returns the remover.
  function onAct(el, handlers) {
    const listener = (e) => {
      const b = e.target.closest('[data-act]');
      if (b && handlers[b.dataset.act]) handlers[b.dataset.act](b);
    };
    el.addEventListener('click', listener);
    return () => el.removeEventListener('click', listener);
  }

  return {
    enc, OS, INSTALL_CMD, PAIR_CMD, PAIR_CMD_FULL, hubFlag, LINUX_AS_USER, ownHub, pairCmd, deviceNames, store,
    call, errorHtml, stateBadge, ctxTag, ctxLevel, ctxText, header, md, cmdBlock, codeBox, getCode, copyCommand, onAct,
  };
})();
