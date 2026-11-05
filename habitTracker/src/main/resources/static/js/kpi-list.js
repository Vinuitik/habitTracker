function getCsrfToken() {
    const meta = document.querySelector('meta[name="_csrf"]');
    if (meta) return meta.getAttribute('content');
    const match = document.cookie.split('; ').find(r => r.startsWith('XSRF-TOKEN='));
    return match ? decodeURIComponent(match.split('=')[1]) : '';
}

// KPI List JavaScript
document.addEventListener('DOMContentLoaded', function() {
    initializeModal();
    setTodayAsDefault();
});

function initializeModal() {
    const modal = document.getElementById('addDataModal');
    const closeBtn = document.querySelector('.close');
    const form = document.getElementById('addDataForm');

    // Close modal when clicking X
    if (closeBtn) closeBtn.addEventListener('click', closeModal);

    // Close modal when clicking outside
    window.addEventListener('click', function(event) {
        if (event.target === modal) {
            closeModal();
        }
    });

    // Handle form submission
    if (form) form.addEventListener('submit', handleFormSubmit);
}

function addData(kpiName) {
    const modal = document.getElementById('addDataModal');
    const kpiNameInput = document.getElementById('kpiName');
    
    kpiNameInput.value = kpiName;
    modal.classList.add('open');
}

function closeModal() {
    const modal = document.getElementById('addDataModal');
    const form = document.getElementById('addDataForm');
    
    modal.classList.remove('open');
    form.reset();
}

function setTodayAsDefault() {
    const dateInput = document.getElementById('dataDate');
    if (dateInput) {
        const today = new Date().toISOString().split('T')[0];
        dateInput.value = today;
    }
}

async function handleFormSubmit(event) {
    event.preventDefault();

    const form = event.target;
    const formData = new FormData(form);
    const kpiName = formData.get('kpiName');
    const date = formData.get('date');
    const value = formData.get('value');

    try {
        // Offline-capable: direct if the server's up, else via the user's own Drive, else
        // queued locally — see js/offline/outbox.js. Always "succeeds" from here since the
        // write is captured one way or another; only a thrown exception means a real bug.
        const { via } = await Outbox.submitKpiValue(kpiName, date, value);
        showMessage(via === 'server' ? 'Data added successfully' : 'Saved — will sync when possible', 'success');
        closeModal();
    } catch (error) {
        console.error('Error adding KPI data:', error);
        showMessage('Failed to add data. Please try again.', 'error');
    }
}

function viewData(kpiName) {
    // Redirect to dashboard with specific KPI
    window.location.href = `/kpis/dashboard?focus=${encodeURIComponent(kpiName)}`;
}

async function deleteKPI(kpiName) {
    if (!confirm(`Are you sure you want to delete the KPI "${kpiName}"? This will also delete all associated data.`)) {
        return;
    }

    try {
        const response = await fetch(`/api/kpis/${encodeURIComponent(kpiName)}`, {
            method: 'DELETE',
            headers: { 'X-XSRF-TOKEN': getCsrfToken() }
        });

        const result = await response.text();
        
        if (response.ok) {
            showMessage(result, 'success');
            // Reload page after short delay
            setTimeout(() => {
                window.location.reload();
            }, 1500);
        } else {
            showMessage(result, 'error');
        }
    } catch (error) {
        console.error('Error deleting KPI:', error);
        showMessage('Failed to delete KPI. Please try again.', 'error');
    }
}

// M12: clears a KPI's proxy circuit breaker (proxyStatus NEEDS_REPAIR -> ACTIVE,
// consecutiveProxyFailures -> 0) so the nightly proxy-fill step resumes calling its provider.
async function resetProxyHealth(kpiName) {
    try {
        const response = await fetch(`/api/kpis/${encodeURIComponent(kpiName)}/proxy/reset`, {
            method: 'PUT',
            headers: { 'X-XSRF-TOKEN': getCsrfToken() }
        });

        if (response.ok) {
            showMessage(`Proxy reset for "${kpiName}"`, 'success');
            setTimeout(() => {
                window.location.reload();
            }, 1000);
        } else {
            const result = await response.json().catch(() => ({ error: 'Failed to reset proxy.' }));
            showMessage(result.error || 'Failed to reset proxy.', 'error');
        }
    } catch (error) {
        console.error('Error resetting proxy health:', error);
        showMessage('Failed to reset proxy. Please try again.', 'error');
    }
}

function showMessage(message, type) {
    // Remove existing alerts
    const existingAlerts = document.querySelectorAll('.alert');
    existingAlerts.forEach(alert => alert.remove());

    // Create new alert
    const alert = document.createElement('div');
    alert.className = `alert alert-${type === 'success' ? 'success' : 'error'}`;
    alert.innerHTML = `<span>${message}</span>`;

    // Insert at the top of main content
    const mainContent = document.querySelector('.main-content');
    const firstChild = mainContent.firstChild;
    mainContent.insertBefore(alert, firstChild);

    // Auto-remove after 5 seconds
    setTimeout(() => {
        if (alert.parentNode) {
            alert.remove();
        }
    }, 5000);
}

// ── M8: "Automate" — describe -> proposal -> approve -> test gate (see KPI/FLOWS.md) ─────────────
// LLM steps (propose / approve / regenerate / repair) run as async server jobs: POST ENV.ENDPOINTS.KPI_CAP_JOBS
// → {jobId}, then poll KPI_CAP_JOB every ENV.TIMING.KPI_CAP_POLL_MS → {state, events, nextSeq, result?, error?,
// usage?}. Steps render with the shared StepList molecule. Everything shown is plain text via textContent
// (proposal / code / errors are LLM or script output) — never a JSON dump.
const AUTOMATE = {
    kpiName: null, needsRepair: false, current: null, reloadOnClose: false,
    job: null,          // {id, state, events: [{seq,kind,text,t}], nextSeq, error, usage, startedAt}
    pollTimer: null, tickTimer: null, gen: 0,
    confirming: false,  // footer shows the inline "Discard this automation?" confirm
    notice: null,       // one-shot line at the top of the panel ("Setup discarded...")
    returnFocus: null,  // element focused before the dialog opened
};
// Drafts the reject endpoint accepts (CapabilityAgentService.reject). ACTIVE / SHADOW / NEEDS_REPAIR are never
// discarded from here; PENDING_TEST is in flight.
const AUTOMATE_DRAFT = new Set(['PROPOSED', 'TEST_FAILED', 'AWAITING_CONFIRM', 'AWAITING_SENSOR', 'AWAITING_CONTRACT']);
const E_KPI = ENV.ENDPOINTS;

function automateEl(tag, className, text) {
    const el = document.createElement(tag);
    if (className) el.className = className;
    if (text != null) el.textContent = text;   // textContent only: proposal/code/errors are LLM output
    return el;
}

function automateButton(label, cls, handler) {
    const b = automateEl('button', 'btn ' + cls, label);
    b.type = 'button';
    b.addEventListener('click', handler);
    return b;
}

async function automateFetch(url, method, body) {
    const res = await fetch(url, {
        method: method || 'GET',
        credentials: 'include',
        headers: { 'Content-Type': 'application/json', 'X-XSRF-TOKEN': getCsrfToken() },
        body: body ? JSON.stringify(body) : undefined
    });
    const data = res.status === 204 ? null : await res.json().catch(() => null);
    if (!res.ok) {
        const err = new Error((data && data.error) || `The server answered ${res.status}. Try again in a minute.`);
        err.status = res.status;
        throw err;
    }
    return data;
}

async function openAutomateModal(kpiName, needsRepair) {
    automateStopPolling();
    const gen = ++AUTOMATE.gen;
    const modal = document.getElementById('automateModal');
    if (!modal.classList.contains('open')) AUTOMATE.returnFocus = document.activeElement;
    Object.assign(AUTOMATE, { kpiName, needsRepair: !!needsRepair, reloadOnClose: false, job: null, current: null,
        confirming: false, notice: null });
    document.getElementById('automateTitle').textContent = `Automate · ${kpiName}`;
    modal.classList.add('open');
    renderAutomate(null);
    document.getElementById('automateClose').focus();
    try {
        const [cap, active] = await Promise.all([
            automateFetch(E_KPI.KPI_CAP(kpiName)),
            automateFetch(E_KPI.KPI_CAP_ACTIVE(kpiName)).catch(() => null)
        ]);
        if (gen !== AUTOMATE.gen) return;
        renderAutomate(cap);
        if (active && active.jobId) automateWatch(active.jobId);   // a job started earlier is still running
    } catch (e) {
        if (gen === AUTOMATE.gen) renderAutomate(null, 'Could not load the automation status. Check your connection and reopen.');
    }
}

function closeAutomateModal() {
    document.getElementById('automateModal').classList.remove('open');
    automateStopPolling();   // the server job keeps running; reopening resumes it via KPI_CAP_ACTIVE
    AUTOMATE.gen++;
    AUTOMATE.confirming = false;
    if (AUTOMATE.reloadOnClose) { window.location.reload(); return; }
    const back = AUTOMATE.returnFocus;
    AUTOMATE.returnFocus = null;
    if (back && document.contains(back)) back.focus();
}

// Esc: first backs out of the discard confirm, then closes. (✕ and backdrop click: kpi-list.html.)
document.addEventListener('keydown', (e) => {
    if (e.key !== 'Escape') return;
    const modal = document.getElementById('automateModal');
    if (!modal || !modal.classList.contains('open')) return;
    e.preventDefault();
    if (AUTOMATE.confirming) { AUTOMATE.confirming = false; renderAutomateDiscard(AUTOMATE.current); return; }
    closeAutomateModal();
});

// action: propose | approve | regenerate | repair
async function automateStart(action, extra) {
    if (automateRunning()) return;
    const gen = AUTOMATE.gen;
    AUTOMATE.notice = null;
    AUTOMATE.confirming = false;
    AUTOMATE.job = { id: null, state: 'running', events: [], nextSeq: 0, error: null, usage: null, startedAt: Date.now() };
    renderAutomate(AUTOMATE.current);
    try {
        const data = await automateFetch(E_KPI.KPI_CAP_JOBS(AUTOMATE.kpiName), 'POST', { action, ...extra });
        if (gen !== AUTOMATE.gen) return;
        automateWatch(data.jobId);
    } catch (e) {
        if (gen !== AUTOMATE.gen) return;
        AUTOMATE.job = null;
        if (e.status === 409) {   // already running (another tab): watch that one instead
            const active = await automateFetch(E_KPI.KPI_CAP_ACTIVE(AUTOMATE.kpiName)).catch(() => null);
            if (gen === AUTOMATE.gen && active && active.jobId) { automateWatch(active.jobId); return; }
        }
        renderAutomate(AUTOMATE.current, e.message);
    }
}

// "Looks right" is instant (AWAITING_CONFIRM -> SHADOW), no job. "Wrong, because..." is a job (action 'wrong').
async function automateConfirm(capId) {
    try {
        const cap = await automateFetch(E_KPI.KPI_CAP_CONFIRM(AUTOMATE.kpiName, capId), 'POST');
        if (cap && (cap.status === 'ACTIVE' || cap.status === 'SHADOW')) AUTOMATE.reloadOnClose = true;
        renderAutomate(cap);
    } catch (e) {
        renderAutomate(AUTOMATE.current, e.message);
    }
}

// "Discard setup" (footer, after the inline confirm): reject the draft, instant, no job. The document stays in
// Mongo as REJECTED; the panel then shows the describe form again, prefilled, so it doubles as "start over".
async function automateReject(capId) {
    AUTOMATE.confirming = false;
    try {
        const cap = await automateFetch(E_KPI.KPI_CAP_REJECT(AUTOMATE.kpiName, capId), 'POST');
        AUTOMATE.job = null;
        AUTOMATE.notice = cap && cap.repair
            ? 'Fix discarded. The version you already use is unchanged.'
            : 'Setup discarded. The KPI stays manual. Describe it again to start over, or close this.';
        renderAutomate(cap);
    } catch (e) {
        renderAutomate(AUTOMATE.current, e.message);
    }
}

// Footer left: "Discard setup" whenever a draft exists → inline confirm (same swap pattern as atoms/menu.js).
function renderAutomateDiscard(cap) {
    const zone = document.getElementById('automateDiscard');
    const foot = document.getElementById('automateFoot');
    zone.replaceChildren();
    const draft = !!cap && AUTOMATE_DRAFT.has(cap.status);
    if (!draft) AUTOMATE.confirming = false;
    foot.classList.toggle('automate-dialog__foot--confirming', AUTOMATE.confirming);
    if (!draft) return;
    if (automateRunning()) {   // no cancel endpoint for a running job: wait for it, or close (it keeps running)
        const b = automateButton('Discard setup', 'btn--ghost automate-dialog__discard-btn', () => {});
        b.disabled = true;
        zone.append(b, automateEl('span', 'automate-dialog__hint', 'Available when this step finishes.'));
        return;
    }
    if (AUTOMATE.confirming) {
        const keep = automateButton('Keep it', 'btn--ghost', () => { AUTOMATE.confirming = false; renderAutomateDiscard(cap); });
        zone.append(
            automateEl('span', 'automate-dialog__confirm-text', cap.repair
                ? 'Discard this fix? The version you already use stays as it is.'
                : 'Discard this automation? The KPI stays manual.'),
            automateButton('Discard', 'btn--danger', () => automateReject(cap.id)),
            keep);
        keep.focus();
        return;
    }
    zone.append(automateButton('Discard setup', 'btn--ghost automate-dialog__discard-btn', () => {
        AUTOMATE.confirming = true;
        renderAutomateDiscard(cap);
    }));
}

// Primary/secondary actions of the current step live in the sticky footer (right side); primary goes last.
function automateFoot(...buttons) {
    document.getElementById('automateActions').append(...buttons);
}

function automateRunning() {
    return !!AUTOMATE.job && AUTOMATE.job.state === 'running';
}

function automateWatch(jobId) {
    if (!AUTOMATE.job || AUTOMATE.job.id !== jobId) {
        AUTOMATE.job = { id: jobId, state: 'running', events: [], nextSeq: 0, error: null, usage: null, startedAt: Date.now() };
    }
    AUTOMATE.job.id = jobId;
    renderAutomate(AUTOMATE.current);
    automatePoll(AUTOMATE.gen);
}

async function automatePoll(gen) {
    const job = AUTOMATE.job;
    if (!job || gen !== AUTOMATE.gen) return;
    try {
        const snap = await automateFetch(E_KPI.KPI_CAP_JOB(AUTOMATE.kpiName, job.id, job.nextSeq));
        if (gen !== AUTOMATE.gen || AUTOMATE.job !== job) return;
        (snap.events || []).forEach(ev => job.events.push({ seq: ev.seq, kind: ev.kind, text: ev.text, t: Date.now() }));
        job.nextSeq = snap.nextSeq != null ? snap.nextSeq : job.nextSeq;
        job.state = snap.state;
        job.usage = snap.usage || job.usage;
        if (snap.state === 'done') {
            const cap = snap.result;
            if (cap && (cap.status === 'ACTIVE' || cap.status === 'SHADOW')) AUTOMATE.reloadOnClose = true;
            renderAutomate(cap);
            return;
        }
        if (snap.state === 'failed') {
            job.error = snap.error || 'Something went wrong. Try again.';
            renderAutomate(AUTOMATE.current);
            return;
        }
        renderAutomate(AUTOMATE.current);
    } catch (e) {
        if (gen !== AUTOMATE.gen || AUTOMATE.job !== job) return;
        if (e.status === 404) {   // expired (server restart): the stored capability status is the truth
            AUTOMATE.job = null;
            openAutomateModal(AUTOMATE.kpiName, AUTOMATE.needsRepair);
            return;
        }
        // transient network error: keep polling
    }
    AUTOMATE.pollTimer = setTimeout(() => automatePoll(gen), ENV.TIMING.KPI_CAP_POLL_MS);
}

function automateStopPolling() {
    clearTimeout(AUTOMATE.pollTimer);
    AUTOMATE.pollTimer = null;
    clearInterval(AUTOMATE.tickTimer);
    AUTOMATE.tickTimer = null;
}

// Steps of the current/last job + token usage. Error events of a failed job are left out: its single
// sentence shows in #automateError instead.
function renderAutomateJob(rail) {
    const job = AUTOMATE.job;
    if (!job) return;
    const live = job.state === 'running';
    const events = job.state === 'failed' ? job.events.filter(ev => ev.kind !== 'error') : job.events;
    const log = automateEl('section', 'automate-dialog__log');
    log.append(automateEl('h3', 'automate-dialog__log-title', live ? 'Working now' : 'Last run'));
    const box = automateEl('div', 'automate__steps');
    // StepList escapes all text; usage is numbers only
    box.innerHTML = StepList.html(events, live, 'Waiting for your runner', job.startedAt) + StepList.usage(job.usage);
    log.append(box);
    rail.append(log);
    if (live && !AUTOMATE.tickTimer) {
        AUTOMATE.tickTimer = setInterval(() => StepList.tick(document.getElementById('automateRail')), 1000);
    } else if (!live) {
        clearInterval(AUTOMATE.tickTimer);
        AUTOMATE.tickTimer = null;
    }
}

// Confirm gate: "Yesterday's value: 47 min" + the evidence rows, then Looks right / Wrong, because...
function renderAutomateConfirm(body, cap) {
    const when = cap.sampleDate ? `Value for ${cap.sampleDate}` : "Yesterday's value";
    body.append(automateEl('div', 'automate__text', 'The script works. Before it starts filling this KPI, check that its number is right.'));
    body.append(automateEl('div', 'automate__sample', `${when}: ${cap.sampleText}`));
    const rows = cap.sampleEvidence || [];
    if (rows.length) {
        const list = automateEl('div', 'automate__evidence');
        rows.forEach(r => {
            const row = automateEl('div', 'automate__evidence-row');
            row.append(automateEl('span', 'automate__evidence-label', r.label),
                automateEl('span', 'automate__evidence-value', r.value));
            list.append(row);
        });
        body.append(list);
    } else {
        body.append(automateEl('div', 'form-group__hint', 'This script did not list what it counted, so there is nothing more to check it against.'));
    }
    const left = (cap.maxConfirmRounds || 3) - (cap.confirmRounds || 0);
    const group = automateEl('div', 'form-group');
    group.hidden = true;
    const label = automateEl('label', 'form-group__label', 'What is wrong with the number?');
    label.htmlFor = 'automateWrongReason';
    const ta = automateEl('textarea', 'input');
    ta.id = 'automateWrongReason';
    ta.rows = 3;
    ta.maxLength = 500;
    ta.placeholder = 'e.g. I watched about 2 hours, and Shorts were not counted';
    group.append(label, ta, automateEl('span', 'form-group__hint',
        `The AI will rewrite the script using your answer. You have ${left} ${left === 1 ? 'try' : 'tries'} left.`));
    const send = automateButton('Send and rebuild', 'btn--primary', () => {
        const reason = ta.value.trim();
        if (!reason) { renderAutomate(cap, 'Say what is wrong first.'); return; }
        automateStart('wrong', { id: cap.id, description: reason });
    });
    send.hidden = true;
    const wrong = automateButton('Wrong, because…', 'btn--ghost', () => {
        group.hidden = false; send.hidden = false; wrong.hidden = true; ta.focus();
    });
    const right = automateButton('Looks right', 'btn--primary', () => automateConfirm(cap.id));
    wrong.addEventListener('click', () => { right.className = 'btn btn--ghost'; });   // one primary at a time
    automateFoot(wrong, right, send);
    body.append(group);
}

// Plain-language summary of a capability: what / where from / how often / what it needs.
function renderAutomateSummary(body, cap) {
    const declared = cap.declaredCapabilities || {};
    const hosts = declared.network || [];
    const secrets = declared.secrets || [];
    const facts = automateEl('dl', 'automate__facts');
    const fact = (label, value) => facts.append(automateEl('dt', null, label), automateEl('dd', null, value));
    fact('Measures', cap.measures || cap.description || 'See the plan below.');
    if (cap.platform === 'RUNNER') {
        fact('Data comes from', cap.dataSource || 'Your own computer, read by your runner.');
        fact('Updates', 'Once a day on your runner. Values reach this site whenever your runner is online.');
        fact('Needs from you', 'Your runner switched on now and then. No passwords or API keys.');
        body.append(facts);
        if (cap.proposal) {
            const plan = automateEl('details', 'automate__source');
            plan.append(automateEl('summary', null, 'Full plan'), automateEl('div', 'automate__text', cap.proposal));
            body.append(plan);
        }
        return;
    }
    fact('Data comes from', cap.dataSource
        || (hosts.length ? `The internet: ${hosts.join(', ')}` : 'Computed inside the sandbox, without internet access.'));
    fact('Updates', 'Once a day, overnight, recording the previous day’s value.');
    fact('Needs from you', secrets.length
        ? `${secrets.length === 1 ? 'One secret' : secrets.length + ' secrets'} (API key or token): ${secrets.join(', ')}. Set below.`
        : 'Nothing. No passwords or API keys.');
    body.append(facts);
    if (cap.proposal) {
        const plan = automateEl('details', 'automate__source');
        plan.append(automateEl('summary', null, 'Full plan'), automateEl('div', 'automate__text', cap.proposal));
        body.append(plan);
    }
}

// Declared hosts + secret inputs. Secret inputs are write-only: the server never returns a value,
// only which names are set. All text via textContent (names/hosts are LLM output).
function renderAutomateAccess(body, cap) {
    if (cap.platform === 'RUNNER') {
        const SOURCE_TEXT = { activitywatch: 'ActivityWatch (which apps, windows and sites were in use)', file: 'files of an app on your computer' };
        const sources = ((cap.declaredCapabilities || {}).sources || []).map(s => SOURCE_TEXT[s] || s);
        const box = automateEl('div', 'automate__access');
        box.append(automateEl('div', 'form-group__hint',
            `Runs on your runner in a sandbox with no internet. It may read only: ${sources.join(', ') || 'nothing'}. You approve exactly what it reads before it touches real data.`));
        body.append(box);
        return;
    }
    const declared = cap.declaredCapabilities || {};
    const hosts = declared.network || [];
    const secrets = declared.secrets || [];
    const box = automateEl('div', 'automate__access');
    box.append(automateEl('div', 'form-group__hint', hosts.length
        ? 'Internet access: the script may reach ONLY these sites. Approving grants exactly this.'
        : 'Internet access: none.'));
    if (hosts.length) {
        const chips = automateEl('div', 'automate__chips');
        hosts.forEach(h => chips.append(automateEl('span', 'automate__chip', h)));
        box.append(chips);
    }
    if (secrets.length) {
        box.append(automateEl('div', 'form-group__hint',
            'Secrets are given to this script only, as environment variables with these names.'));
    }
    secrets.forEach(name => {
        const row = automateEl('div', 'automate__secret');
        const status = automateEl('span', 'automate__secret-state', 'checking...');
        const input = automateEl('input', 'input input--sm');
        input.type = 'password';
        input.autocomplete = 'new-password';
        input.placeholder = 'Paste value (write-only)';
        input.setAttribute('aria-label', `Value for ${name}`);
        const save = automateButton('Save', 'btn--ghost btn--sm', async () => {
            const value = input.value;
            if (!value) return;
            const ok = await automateSecretCall(name, 'PUT', { value }, status);
            if (ok) input.value = '';
        });
        const remove = automateButton('Remove', 'btn--ghost btn--sm', () => automateSecretCall(name, 'DELETE', null, status));
        row.append(automateEl('span', 'automate__secret-name', name), status, input, save, remove);
        box.append(row);
        automateSecretNames().then(names => setSecretState(status, names.includes(name)))
            .catch(() => { status.textContent = 'unknown'; });
    });
    body.append(box);
}

function renderAutomateCode(body, cap) {
    if (!cap.sourceCode) return;
    const d = automateEl('details', 'automate__source');
    d.append(automateEl('summary', null, cap.platform === 'RUNNER' ? 'View the collector (Python)' : 'View the script (Python)'),
        automateEl('pre', 'automate__code automate__code--source', cap.sourceCode));
    body.append(d);
}

function setSecretState(el, isSet) {
    el.textContent = isSet ? 'set' : 'not set';
    el.className = 'automate__secret-state ' + (isSet ? 'automate__secret-state--set' : 'automate__secret-state--unset');
}

async function automateSecretNames() {
    const data = await automateFetch(E_KPI.KPI_SECRETS);
    return (data && data.names) || [];
}

async function automateSecretCall(name, method, body, statusEl) {
    const errEl = document.getElementById('automateError');
    try {
        await automateFetch(E_KPI.KPI_SECRET(name), method, body);
        errEl.hidden = true;
        setSecretState(statusEl, method === 'PUT');
        return true;
    } catch (e) {
        errEl.textContent = e.status ? `Could not save the secret: ${e.message}` : 'Could not save the secret. Check your connection.';
        errEl.hidden = false;
        statusEl.textContent = 'unknown';
        return false;
    }
}

// failureReason (server text) → {headline, action, detail?}. detail = raw script/runner output, shown collapsed.
function describeFailure(cap) {
    const reason = (cap.failureReason || '').trim();
    const left = cap.maxAttempts - cap.attempts;
    const retry = left > 0
        ? 'Press Regenerate and the AI will fix it using this error.'
        : 'No attempts left. Start over with a different description.';
    const gen = 'Code generation failed: ';
    const gate = 'Test gate failed: ';
    if (reason.startsWith(gen)) {
        const why = reason.slice(gen.length);
        return { headline: why.endsWith('.') ? why : why + '.', action: 'Then press Regenerate.' };
    }
    const runnerPrefixes = [
        ['Runner sandbox unavailable: ', why => ({ headline: why, action: 'Nothing was run. Fix the runner, then press Regenerate.' })],
        ['Static check failed: ', why => ({ headline: 'The collector broke a safety rule (for example it used a module that is not allowed, or read a forbidden path).', action: retry, detail: why })],
        ['The runner rejected the collector: ', why => ({ headline: 'Your runner refused the collector as written.', action: retry, detail: why })],
        ['Fixture test failed: ', why => ({ headline: 'The collector gave wrong numbers on its own sample days.', action: retry, detail: why })],
    ];
    for (const [prefix, f] of runnerPrefixes) {
        if (reason.startsWith(prefix)) return f(reason.slice(prefix.length));
    }
    if (reason.startsWith(gate)) {
        const why = reason.slice(gate.length);
        if (/secret .* not set/i.test(why)) {
            const names = (why.match(/secret (.*?) not set/i) || [])[1] || 'a secret';
            return { headline: `The script needs ${names}, which is not set yet.`, action: 'Set it below, then press Regenerate.' };
        }
        if (why.startsWith('TIMEOUT')) return { headline: 'The script took longer than the time limit.', action: retry, detail: why };
        if (why.startsWith('SIDECAR_ERROR')) {
            return { headline: 'The sandbox that tests scripts is not reachable right now.', action: 'Try Regenerate again in a few minutes.', detail: why };
        }
        if (why.startsWith('script exited 0')) return { headline: 'The script ran but did not print a number on its last line.', action: retry, detail: why };
        return { headline: 'The script crashed when tested.', action: retry, detail: why };
    }
    return { headline: reason || 'It stopped producing valid values.', action: retry };
}

function renderAutomateFailure(body, cap) {
    const f = describeFailure(cap);
    body.append(automateEl('div', 'automate__fail', f.headline), automateEl('div', 'form-group__hint',
        `${f.action} Attempts used: ${cap.attempts} of ${cap.maxAttempts}.`));
    if (f.detail) {
        const d = automateEl('details', 'automate__source');
        d.append(automateEl('summary', null, 'What the script printed'), automateEl('pre', 'automate__code', f.detail));
        body.append(d);
    }
}

// S3 RUNNER: per-case result of the collector's own sample days (fixtures). Plain rows, no JSON.
function renderAutomateTests(body, cap) {
    const cases = cap.collectorTests || [];
    if (!cases.length) return;
    const list = automateEl('div', 'automate__evidence');
    cases.forEach(c => {
        const row = automateEl('div', 'automate__evidence-row');
        const got = c.got == null ? 'no number' : String(c.got);
        const want = c.expected == null ? '' : ` (expected ${c.expected})`;
        row.append(automateEl('span', 'automate__evidence-label', `${c.passed ? '✓' : '✕'} Sample day “${c.name}”`),
            automateEl('span', 'automate__evidence-value', c.error ? `${got}${want}: ${c.error}` : `${got}${want}`));
        list.append(row);
    });
    body.append(automateEl('div', 'form-group__hint', 'Sample days the collector was tested on:'), list);
}

// S3 RUNNER contract: what the collector may read, in plain language. Approve → runner approval + dry run on yesterday.
function renderAutomateContract(body, cap) {
    body.append(automateEl('div', 'automate__text', cap.repair
        ? 'The fix needs to read different data than the version you approved, so it needs your OK again.'
        : 'The collector passed its tests on sample days. Before it reads your real data, approve what it may read.'));
    body.append(automateEl('div', 'automate__sample', cap.contractText || 'Reads only what the plan declared · no internet'));
    body.append(automateEl('div', 'form-group__hint',
        'Your runner enforces this: the collector sees only these inputs, with no internet. A later fix that reads anything else will ask you again.'));
    if (cap.failureReason) body.append(automateEl('div', 'automate__fail', describeFailure(cap).headline));
    renderAutomateTests(body, cap);
    renderAutomateSummary(body, cap);
    renderAutomateCode(body, cap);
    automateFoot(automateButton('Approve', 'btn--primary', () => automateStart('contract', { id: cap.id })));
}

// S3 RUNNER: ActivityWatch missing / stopped on the runner. "Install it" checks first, installs if needed, then continues.
function renderAutomateSensor(body, cap) {
    body.append(automateEl('div', 'automate__fail', cap.failureReason || 'ActivityWatch isn’t running on your runner.'));
    const installed = /installed but not started|could not read|install it from/i.test(cap.failureReason || '');
    body.append(automateEl('div', 'form-group__hint', installed
        ? 'Start ActivityWatch on that computer, then press Check again.'
        : 'ActivityWatch records which apps and sites you use, only on that computer. Install it lets your runner set it up; this can take a few minutes.'));
    renderAutomateSummary(body, cap);
    automateFoot(automateButton(installed ? 'Check again' : 'Install it', 'btn--primary', () => automateStart('sensor', { id: cap.id })));
}

// S3 RUNNER: fetch the runner's stored readings now instead of waiting for the hourly pull.
function renderAutomatePull(body, cap) {
    if (cap.platform !== 'RUNNER') return;
    const PULL_TEXT = {
        PULLED: (n) => n ? `Fetched ${n} new value${n === 1 ? '' : 's'} from your runner.` : 'Your runner had no new values yet.',
        SKIPPED_OFFLINE: () => 'Your runner is offline. Values wait there until it is back.',
        NOT_RUNNABLE: () => 'This collector is not running yet.',
        FAILED: () => 'The collector failed on your runner. It will be repaired automatically.',
    };
    const note = automateEl('div', 'form-group__hint', '');
    automateFoot(automateButton('Fetch values now', 'btn--ghost', async () => {
        note.textContent = 'Fetching...';
        try {
            const r = await automateFetch(E_KPI.KPI_CAP_PULL(AUTOMATE.kpiName), 'POST');
            note.textContent = (PULL_TEXT[r && r.outcome] || (() => 'Done.'))(r ? r.stored : 0);
            AUTOMATE.reloadOnClose = true;
        } catch (e) {
            note.textContent = e.message;
        }
    }));
    body.append(note);
}

// Left rail: the setup as a sequence (where am I, what is left) + the live step log of the current/last job.
// RUNNER collectors have one extra stage (approve what it reads).
const AUTOMATE_STAGES = [
    ['describe', 'Describe it'], ['plan', 'Review the plan'], ['build', 'Build and test'],
    ['access', 'Approve what it reads'], ['check', 'Check the number'], ['trial', 'Trial run'], ['active', 'Fills itself'],
];
// Stage a stored status sits at, and the stage a running job works on from that status.
const AUTOMATE_STAGE_AT = {
    PROPOSED: 'plan', PENDING_TEST: 'build', TEST_FAILED: 'build', AWAITING_SENSOR: 'build',
    AWAITING_CONTRACT: 'access', AWAITING_CONFIRM: 'check', SHADOW: 'trial', ACTIVE: 'active', NEEDS_REPAIR: 'active',
};
const AUTOMATE_STAGE_WORKING = {
    PROPOSED: 'build', TEST_FAILED: 'build', AWAITING_SENSOR: 'build', AWAITING_CONTRACT: 'access',
    AWAITING_CONFIRM: 'build', SHADOW: 'build', ACTIVE: 'build', NEEDS_REPAIR: 'build', PENDING_TEST: 'build',
};
const AUTOMATE_STAGE_BLOCKED = {
    TEST_FAILED: 'Test did not pass', AWAITING_SENSOR: 'Needs ActivityWatch', NEEDS_REPAIR: 'Needs a fix',
};

function renderAutomateRail(cap) {
    const rail = document.getElementById('automateRail');
    rail.replaceChildren();
    const status = cap ? cap.status : null;
    const fresh = !cap || status === 'REJECTED' || status === 'DISABLED';
    const running = automateRunning();
    const stages = AUTOMATE_STAGES.filter(([key]) => key !== 'access' || (cap && cap.platform === 'RUNNER'));
    let currentKey = fresh ? (running ? 'plan' : 'describe') : (running ? AUTOMATE_STAGE_WORKING[status] : AUTOMATE_STAGE_AT[status]);
    if (!currentKey) currentKey = 'describe';
    const cur = Math.max(0, stages.findIndex(([key]) => key === currentKey));
    const allDone = !running && status === 'ACTIVE';
    const blocked = !running && AUTOMATE_STAGE_BLOCKED[status];

    const list = automateEl('ol', 'stage-rail');
    stages.forEach(([key, label], i) => {
        let state = i < cur || allDone ? 'done' : i === cur ? 'current' : 'todo';
        let note = null;
        if (state === 'current' && running) { state = 'current stage-rail__item--working'; note = 'Working on it now'; }
        else if (state === 'current' && blocked) { state = 'blocked'; note = blocked; }
        else if (state === 'current' && key === 'trial' && cap.shadowUntil) note = `Until ${cap.shadowUntil}`;
        const item = automateEl('li', `stage-rail__item stage-rail__item--${state}`);
        if (i === cur) item.setAttribute('aria-current', 'step');
        const mark = automateEl('span', 'stage-rail__mark', state === 'done' ? '✓' : state === 'blocked' ? '!' : '');
        mark.setAttribute('aria-hidden', 'true');
        const text = automateEl('span', 'stage-rail__label', label);
        if (note) text.append(automateEl('span', 'stage-rail__note', note));
        item.append(mark, text);
        list.append(item);
    });

    // One-line form of the same thing, shown instead of the list below 900px
    const compact = automateEl('p', 'stage-rail__compact', `Step ${cur + 1} of ${stages.length}: `);
    compact.append(automateEl('strong', null, stages[cur][1]));
    if (allDone) compact.textContent = 'All steps done: this KPI fills itself.';
    else if (running) compact.append(' (working on it now)');
    else if (blocked) compact.append(` (${blocked.toLowerCase()})`);

    rail.append(list, compact);
    renderAutomateJob(rail);
}

const AUTOMATE_STATUS_TEXT = {
    PROPOSED: 'Waiting for your approval', TEST_FAILED: 'The test did not pass', ACTIVE: 'Active',
    PENDING_TEST: 'In progress', NEEDS_REPAIR: 'Needs a fix',
    AWAITING_CONFIRM: 'Check the number', SHADOW: 'Trial run',
    AWAITING_SENSOR: 'Needs ActivityWatch', AWAITING_CONTRACT: 'Approve what it reads'
};

function renderAutomateStatus(cap, fresh) {
    const el = document.getElementById('automateStatus');
    if (automateRunning()) el.textContent = 'Your runner is working on it. You can close this; it keeps going.';
    else if (fresh) el.textContent = 'Describe what to capture. Nothing runs until you approve a plan.';
    else el.textContent = `${AUTOMATE_STATUS_TEXT[cap.status] || cap.status} (version ${cap.version})`;
}

// Fix it: the AI repairs a live script that stopped producing valid values.
function automateFixIt(body, hint) {
    if (!AUTOMATE.needsRepair) return;
    if (hint) body.append(automateEl('div', 'form-group__hint', hint));
    automateFoot(automateButton('Fix it', 'btn--primary', () => automateStart('repair')));
}

function renderAutomate(cap, error) {
    AUTOMATE.current = cap;
    const body = document.getElementById('automateBody');
    body.replaceChildren();
    document.getElementById('automateActions').replaceChildren();
    const job = AUTOMATE.job;
    const errText = error || (job && job.state === 'failed' ? job.error : '');
    const errEl = document.getElementById('automateError');
    errEl.textContent = errText || '';
    errEl.hidden = !errText;

    const status = cap ? cap.status : null;
    const fresh = !cap || status === 'REJECTED' || status === 'DISABLED';
    renderAutomateStatus(cap, fresh);
    renderAutomateRail(cap);
    renderAutomateDiscard(cap);
    if (AUTOMATE.notice) body.append(automateEl('div', 'automate-dialog__notice', AUTOMATE.notice));

    if (automateRunning()) {
        body.append(automateEl('div', 'automate__text',
            'Progress shows step by step as your runner finishes each one. You can close this dialog; reopen it to check on it.'));
        if (cap && !fresh) renderAutomateSummary(body, cap);
        return;
    }

    if (fresh) {
        const group = automateEl('div', 'form-group');
        const label = automateEl('label', 'form-group__label', 'What should be captured automatically?');
        label.htmlFor = 'automateDescription';
        const ta = automateEl('textarea', 'input');
        ta.id = 'automateDescription';
        ta.rows = 5;
        ta.maxLength = 2000;
        ta.placeholder = 'e.g. Count the lines of code in my project folder each day';
        if (cap && cap.description && status === 'REJECTED') ta.value = cap.description;
        group.append(label, ta,
            automateEl('span', 'form-group__hint',
                'The AI proposes a plan first. Nothing runs until you approve it. Scripts run in a sandbox, with internet access only to sites you approve.'));
        automateFoot(automateButton('Propose a plan', 'btn--primary', () => {
            const description = ta.value.trim();
            if (!description) { renderAutomate(cap, 'Describe what to capture first.'); return; }
            automateStart('propose', { description });
        }));
        body.append(group);
        return;
    }

    if (status === 'AWAITING_CONTRACT') {
        renderAutomateContract(body, cap);
    } else if (status === 'AWAITING_SENSOR') {
        renderAutomateSensor(body, cap);
    } else if (status === 'PROPOSED') {
        renderAutomateSummary(body, cap);
        renderAutomateAccess(body, cap);
        automateFoot(automateButton('Approve and build', 'btn--primary', () => automateStart('approve', { id: cap.id })));
    } else if (status === 'TEST_FAILED') {
        renderAutomateFailure(body, cap);
        renderAutomateTests(body, cap);
        renderAutomateSummary(body, cap);
        renderAutomateAccess(body, cap);
        renderAutomateCode(body, cap);
        if (cap.attempts < cap.maxAttempts) {
            automateFoot(automateButton('Regenerate', 'btn--primary', () => automateStart('regenerate', { id: cap.id })));
        }
    } else if (status === 'AWAITING_CONFIRM') {
        renderAutomateConfirm(body, cap);
    } else if (status === 'SHADOW') {
        body.append(automateEl('div', 'automate__ok',
            `On trial until ${cap.shadowUntil || 'the trial ends'}. Each night's value is saved but marked unconfirmed, and shows up in your confirm list so you can check it.`));
        body.append(automateEl('div', 'form-group__hint', 'After the trial it becomes active on its own.'));
        renderAutomateSummary(body, cap);
        if (cap.lastResult) body.append(automateEl('div', 'form-group__hint', `Last run: ${cap.lastResult}`));
        renderAutomatePull(body, cap);
        renderAutomateCode(body, cap);
        automateFixIt(body, 'This KPI stopped producing valid values. Fix it lets the AI repair the script.');
    } else if (status === 'ACTIVE') {
        body.append(automateEl('div', 'automate__ok', 'Active. This KPI now fills itself every night.'));
        renderAutomateSummary(body, cap);
        if (cap.lastResult) body.append(automateEl('div', 'form-group__hint', `Last run: ${cap.lastResult}`));
        renderAutomatePull(body, cap);
        renderAutomateCode(body, cap);
        automateFixIt(body, 'This KPI stopped producing valid values. Fix it lets the AI repair the script.');
    } else {
        // PENDING_TEST / NEEDS_REPAIR with no job running here
        body.append(automateEl('div', 'automate__text', status === 'NEEDS_REPAIR'
            ? 'This script stopped producing valid values.'
            : 'Work on this script is in progress or was interrupted. Reopen this dialog in a minute.'));
        renderAutomateSummary(body, cap);
        automateFixIt(body, null);
    }
}
