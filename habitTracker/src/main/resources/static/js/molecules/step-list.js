// AI work log: compact rows that tick off (● running + seconds counter, ✓ done, · note, ✕ error).
// Shared by the planner (organisms/planner.js) and the KPI Automate dialog (kpi-list.js).
// Events: [{seq, kind: 'step'|'text'|'error', text, t}] where t = client ms when first seen.
// Styles: styles/molecules/step-list.css. Needs atoms/dom.js (Dom.esc).
window.StepList = (() => {
  const esc = (s) => Dom.esc(s);
  const elapsed = (since) => `${Math.max(0, Math.round((Date.now() - since) / 1000))}s`;

  function row(kind, mark, text, since) {
    const time = since ? `<span class="step-row__time" data-since="${since}">${elapsed(since)}</span>` : '';
    return `<div class="step-row step-row--${kind}"><span class="step-row__mark" aria-hidden="true">${mark}</span><span class="step-row__text">${esc(text)}</span>${time}</div>`;
  }

  const wrap = (rows) => `<div class="step-list" aria-label="AI working steps">${rows.join('')}</div>`;

  // live = still running: the last 'step' event is the running one; waitingText shows before any step arrives,
  // counting from waitingSince (ms; default now).
  function html(events, live, waitingText, waitingSince) {
    const lastStep = events.map((e) => e.kind).lastIndexOf('step');
    const rows = events.map((ev, i) => {
      if (ev.kind === 'error') return row('error', '✕', ev.text);
      if (ev.kind === 'text') return row('text', '·', ev.text);
      const running = live && i === lastStep;
      return row(running ? 'running' : 'done', running ? '●' : '✓', ev.text, running ? ev.t : null);
    });
    if (live && lastStep < 0 && waitingText) rows.push(row('running', '●', waitingText, waitingSince || Date.now()));
    return wrap(rows);
  }

  const error = (text) => wrap([row('error', '✕', text)]);

  // Refresh every running row's seconds counter under root. Call once a second while live.
  function tick(root) {
    root.querySelectorAll('[data-since]').forEach((n) => { n.textContent = elapsed(+n.dataset.since); });
  }

  // 950 → "950", 12400 → "12.4k", 3100000 → "3.1M". Shared with pages/ai-usage.js.
  function tokens(n) {
    const v = Math.max(0, Number(n) || 0);
    if (v < 1000) return String(Math.round(v));
    if (v < 999500) return `${+(v / 1e3).toFixed(v < 1e5 ? 1 : 0)}k`;
    return `${+(v / 1e6).toFixed(1)}M`;
  }

  // Runner job usage {inputTokens, outputTokens, totalTokens?} → "Used 12.4k tokens" line ('' when absent).
  function usage(u) {
    if (!u) return '';
    const total = u.totalTokens || (Number(u.inputTokens) || 0) + (Number(u.outputTokens) || 0);
    return total ? `<div class="step-list__usage">Used ${tokens(total)} tokens</div>` : '';
  }

  return { html, error, tick, tokens, usage };
})();
