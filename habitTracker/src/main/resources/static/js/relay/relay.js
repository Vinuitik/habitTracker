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

  // Markdown for agent replies (port of the Relay Android MarkdownText.kt): fenced code, headings, bullet /
  // numbered lists (indent → md-li--iN), quotes, rules, pipe tables; inline `code`, **bold**, *italic*,
  // ~~strike~~, [links](https://…). Escape first, always. `_` is never emphasis (snake_case stays intact).
  // Unclosed fences render as code to the end, so a half-streamed reply still looks right. Styles: molecules/markdown.css.
  const FENCE = /^ {0,3}(```|~~~)/;
  const RULE = /^ {0,3}([-*_])( *\1){2,} *$/;
  const TABLE_SEP = /^ *\|? *:?-+:? *(\| *:?-+:? *)*\|? *$/;
  const HEADING = /^(#{1,6})\s+(.*?)\s*#*\s*$/;
  const BULLET = /^(\s*)[-*+]\s+(.*)$/;
  const NUMBERED = /^(\s*)(\d+)[.)]\s+(.*)$/;
  const inline = (s) => {
    const keep = [];   // code spans + links, swapped back in last so emphasis can't reach into them
    const stash = (html) => `\u0000${keep.push(html) - 1}\u0000`;
    return esc(s)
      .replace(/`([^`\n]+)`/g, (_, c) => stash(`<code>${c}</code>`))
      .replace(/\[([^\]\n]+)\]\((https?:\/\/[^\s)]+)\)/g, (_, t, u) => stash(`<a class="md-a" href="${u}" target="_blank" rel="noopener noreferrer">${t}</a>`))
      .replace(/\*\*(?=\S)(.+?)\*\*/g, '<strong>$1</strong>')
      .replace(/~~(?=\S)(.+?)~~/g, '<del>$1</del>')
      .replace(/(^|[^*\w])\*(?=[^\s*])([^*\n]*?[^\s*])\*(?!\*)/g, '$1<em>$2</em>')
      .replace(/\n/g, '<br>')
      .replace(/\u0000(\d+)\u0000/g, (_, i) => keep[i]);
  };
  const cells = (line) => line.trim().replace(/^\|/, '').replace(/(?<!\\)\|$/, '').split(/(?<!\\)\|/).map((c) => c.trim().replace(/\\\|/g, '|'));
  function md(text) {
    const lines = String(text ?? '').replace(/\r\n?/g, '\n').split('\n');
    const out = [];
    let para = [];
    let list = null;    // {tag, items: [{indent, text}], start}
    let quote = [];
    const flush = () => {
      if (para.length) out.push(`<p>${inline(para.join('\n'))}</p>`);
      if (quote.length) out.push(`<blockquote class="md-quote">${inline(quote.join('\n'))}</blockquote>`);
      if (list) {
        const start = list.tag === 'ol' && list.start !== 1 ? ` start="${list.start}"` : '';
        out.push(`<${list.tag} class="md-list"${start}>${list.items.map((it) =>
          `<li class="md-li md-li--i${it.indent}">${inline(it.text)}</li>`).join('')}</${list.tag}>`);
      }
      para = []; quote = []; list = null;
    };
    const item = (tag, indent, txt, n) => {
      if (para.length || quote.length || (list && list.tag !== tag && indent === 0)) flush();
      if (!list) list = { tag, items: [], start: n };
      list.items.push({ indent: Math.min(3, Math.floor(indent / 2)), text: txt });
    };
    for (let i = 0; i < lines.length; i++) {
      const line = lines[i];
      const t = line.trim();
      let m;
      if (FENCE.test(line)) {
        flush();
        const code = [];
        for (i++; i < lines.length && !FENCE.test(lines[i]); i++) code.push(lines[i]);
        out.push(`<pre class="relay-pre"><code>${esc(code.join('\n'))}</code></pre>`);
      } else if (!t) {
        flush();
      } else if (t.includes('|') && i + 1 < lines.length && lines[i + 1].includes('|') && TABLE_SEP.test(lines[i + 1])) {
        flush();
        const head = cells(line);
        const rows = [];
        for (i += 2; i < lines.length && lines[i].trim() && lines[i].includes('|'); i++) rows.push(cells(lines[i]));
        i--;
        const row = (r, tag) => `<tr>${head.map((_, c) => `<${tag}>${inline(r[c] || '')}</${tag}>`).join('')}</tr>`;
        out.push(`<div class="md-table"><table><thead>${row(head, 'th')}</thead><tbody>${rows.map((r) => row(r, 'td')).join('')}</tbody></table></div>`);
      } else if (RULE.test(line)) {
        flush();
        out.push('<hr class="md-hr">');
      } else if ((m = HEADING.exec(t))) {
        flush();
        out.push(`<div class="md-h md-h${m[1].length}" role="heading" aria-level="${m[1].length}">${inline(m[2])}</div>`);
      } else if ((m = BULLET.exec(line))) {
        item('ul', m[1].length, m[2]);
      } else if ((m = NUMBERED.exec(line))) {
        item('ol', m[1].length, m[3], Number(m[2]));
      } else if (t.startsWith('>')) {
        if (para.length || list) flush();
        quote.push(t.replace(/^>\s?/, ''));
      } else if (list && /^\s/.test(line)) {
        list.items[list.items.length - 1].text += '\n' + t;   // wrapped continuation of a list item
      } else {
        if (list || quote.length) flush();
        para.push(line);
      }
    }
    flush();
    return out.join('');
  }

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
