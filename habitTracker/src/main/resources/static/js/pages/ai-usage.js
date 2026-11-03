// AI usage page (/projects/usage): GET ENV.ENDPOINTS.AI_USAGE(ENV.AI_USAGE.DAYS) →
// {totals:{input,output}, byKind, byProject, byKpi, recent} → total (big) · by kind · by project · by KPI · recent jobs.
// Bars are the <progress> atom (styles/atoms/progress.css). Numbers via StepList.tokens ("12.4k").
(() => {
  const { esc, timeAgo } = Dom;
  const fmt = StepList.tokens;
  const KIND_LABELS = {
    map: 'Repo map',
    plan: 'Planner',
    kpi_propose: 'KPI proposal',
    kpi_generate: 'KPI script',
    kpi_repair: 'KPI repair',
  };
  const kindLabel = (k) => KIND_LABELS[k] || k;

  // rows: [{label, tokens, meta?}] → list with a bar relative to the largest row.
  function barList(title, rows, emptyText) {
    const max = Math.max(1, ...rows.map((r) => r.tokens));
    const body = rows.length
      ? `<ul class="usage-list">${rows.map((r) => `
          <li class="usage-list__row">
            <div class="usage-list__line">
              <span class="usage-list__label">${esc(r.label)}</span>
              <span class="usage-list__value">${fmt(r.tokens)}${r.meta ? ` <span class="usage-list__meta">${esc(r.meta)}</span>` : ''}</span>
            </div>
            <progress class="progress" max="${max}" value="${r.tokens}"></progress>
          </li>`).join('')}</ul>`
      : `<p class="usage-page__sub">${esc(emptyText)}</p>`;
    return `<section class="card usage-card"><h2 class="usage-card__title">${esc(title)}</h2>${body}</section>`;
  }

  function render(d) {
    const view = document.getElementById('usage-view');
    const total = (d.totals.input || 0) + (d.totals.output || 0);
    if (!total && !(d.recent || []).length) {
      view.innerHTML = '<section class="card usage-empty"><h2 class="usage-card__title">No AI runs yet</h2>'
        + '<p class="usage-page__sub">Map a repo, plan a board or automate a KPI. Token use shows up here.</p></section>';
      return;
    }
    const runs = (d.byKind || []).reduce((n, k) => n + (k.runs || 0), 0);
    view.innerHTML = `
      <section class="card usage-total">
        <span class="usage-total__label">Total, last ${d.days} days</span>
        <span class="usage-total__value">${fmt(total)} <span class="usage-total__unit">tokens</span></span>
        <span class="usage-total__meta">in ${fmt(d.totals.input)} · out ${fmt(d.totals.output)} · ${runs} run${runs === 1 ? '' : 's'}</span>
      </section>
      ${barList('By kind', (d.byKind || []).map((k) => ({ label: kindLabel(k.kind), tokens: k.tokens, meta: `${k.runs}×` })), '')}
      <div class="usage-grid">
        ${barList('By project', (d.byProject || []).map((p) => ({ label: p.name, tokens: p.tokens })), 'No project runs')}
        ${barList('By KPI', (d.byKpi || []).map((k) => ({ label: k.kpiName, tokens: k.tokens })), 'No KPI runs')}
      </div>
      <section class="card usage-card">
        <h2 class="usage-card__title">Recent jobs</h2>
        <ul class="usage-recent">${(d.recent || []).map((r) => `
          <li class="usage-recent__row">
            <span class="usage-recent__time">${esc(timeAgo(r.at))}</span>
            <span class="usage-recent__kind">${esc(kindLabel(r.kind))}</span>
            <span class="usage-recent__label">${esc(r.label || '')}</span>
            <span class="usage-recent__tokens">${fmt(r.tokens)}</span>
          </li>`).join('')}</ul>
      </section>`;
  }

  async function init() {
    if (!(await checkAuth())) return;
    initTopbar(ENV.ROUTES.AI_USAGE);
    document.getElementById('usage-back').setAttribute('href', ENV.ROUTES.PROJECTS);
    try {
      render(await Api.request(ENV.ENDPOINTS.AI_USAGE(ENV.AI_USAGE.DAYS)));
    } catch (e) {
      document.getElementById('usage-view').innerHTML = `<p class="usage-page__sub usage-page__sub--error">${esc(e.message)}</p>`;
    }
  }

  init();
})();
