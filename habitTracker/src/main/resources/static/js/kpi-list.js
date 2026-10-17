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
const AUTOMATE = { kpiName: null, needsRepair: false, busy: false, current: null, reloadOnClose: false };

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

async function openAutomateModal(kpiName, needsRepair) {
    AUTOMATE.kpiName = kpiName;
    AUTOMATE.needsRepair = !!needsRepair;
    AUTOMATE.reloadOnClose = false;
    document.getElementById('automateModal').classList.add('open');
    renderAutomate(null);
    try {
        const res = await fetch(`/api/kpis/${encodeURIComponent(kpiName)}/capability`, { credentials: 'include' });
        renderAutomate(res.status === 200 ? await res.json() : null);
    } catch (e) {
        renderAutomate(null, 'Could not load automation status.');
    }
}

function closeAutomateModal() {
    document.getElementById('automateModal').classList.remove('open');
    if (AUTOMATE.reloadOnClose) window.location.reload();
}

async function automateCall(path, body) {
    if (AUTOMATE.busy) return;
    setAutomateBusy(true);
    try {
        const res = await fetch(`/api/kpis/${encodeURIComponent(AUTOMATE.kpiName)}/capability${path}`, {
            method: 'POST',
            credentials: 'include',
            headers: { 'Content-Type': 'application/json', 'X-XSRF-TOKEN': getCsrfToken() },
            body: body ? JSON.stringify(body) : undefined
        });
        const data = await res.json().catch(() => ({}));
        if (!res.ok) {
            renderAutomate(AUTOMATE.current, data.error || `Request failed (${res.status})`);
        } else {
            if (data.status === 'ACTIVE') AUTOMATE.reloadOnClose = true;
            renderAutomate(data);
        }
    } catch (e) {
        renderAutomate(AUTOMATE.current, 'Request failed. Please try again.');
    } finally {
        setAutomateBusy(false);
    }
}

function setAutomateBusy(busy) {
    AUTOMATE.busy = busy;
    document.getElementById('automateBusy').hidden = !busy;
    document.querySelectorAll('#automateBody button').forEach(b => { b.disabled = busy; });
}

function renderAutomate(cap, error) {
    AUTOMATE.current = cap;
    const body = document.getElementById('automateBody');
    body.replaceChildren();
    const errEl = document.getElementById('automateError');
    errEl.textContent = error || '';
    errEl.hidden = !error;

    const status = cap ? cap.status : null;
    const fresh = !cap || status === 'REJECTED' || status === 'DISABLED';

    if (fresh) {
        const group = automateEl('div', 'form-group');
        const label = automateEl('label', 'form-group__label', 'What should be captured automatically?');
        label.htmlFor = 'automateDescription';
        const ta = automateEl('textarea', 'input');
        ta.id = 'automateDescription';
        ta.rows = 4;
        ta.maxLength = 2000;
        ta.placeholder = 'e.g. Count the lines of code in my project folder each day';
        group.append(label, ta,
            automateEl('span', 'form-group__hint',
                'The agent proposes a plan first. Nothing runs until you approve it. Scripts run in a sandbox with no network access.'));
        const actions = automateEl('div', 'modal-box__actions');
        actions.append(automateButton('Propose', 'btn--primary', () => {
            const description = ta.value.trim();
            if (!description) { renderAutomate(cap, 'Describe what to capture first.'); return; }
            automateCall('/propose', { description });
        }));
        body.append(group, actions);
        return;
    }

    body.append(automateEl('div', 'automate__meta', `Version ${cap.version} · ${status.replace('_', ' ')}`));

    if (status === 'PROPOSED') {
        body.append(automateEl('div', 'form-group__label', 'Proposal'),
            automateEl('div', 'automate__text', cap.proposal),
            automateEl('div', 'form-group__label', 'Access requested'),
            automateEl('div', 'automate__text', 'Platform: SERVER · network: none · secrets: none'));
        const actions = automateEl('div', 'modal-box__actions');
        actions.append(
            automateButton('Approve', 'btn--primary', () => automateCall(`/${cap.id}/approve`)),
            automateButton('Reject', 'btn--ghost', () => automateCall(`/${cap.id}/reject`)));
        body.append(actions);
    } else if (status === 'TEST_FAILED') {
        body.append(automateEl('div', 'form-group__label', 'Test gate failed'),
            automateEl('pre', 'automate__code', cap.failureReason || 'Unknown failure'),
            automateEl('div', 'form-group__hint', `Attempts used: ${cap.attempts} of ${cap.maxAttempts}`));
        const actions = automateEl('div', 'modal-box__actions');
        if (cap.attempts < cap.maxAttempts) {
            actions.append(automateButton('Regenerate', 'btn--primary', () => automateCall(`/${cap.id}/regenerate`)));
        }
        actions.append(automateButton('Start over', 'btn--ghost', () => automateCall(`/${cap.id}/reject`)));
        body.append(actions);
    } else if (status === 'ACTIVE') {
        body.append(automateEl('div', 'automate__ok', 'Active. A nightly job fills this KPI from the script.'),
            automateEl('div', 'form-group__hint', cap.lastResult || ''));
        if (cap.sourceCode) {
            const d = automateEl('details', 'automate__source');
            d.append(automateEl('summary', null, 'View script'), automateEl('pre', 'automate__code', cap.sourceCode));
            body.append(d);
        }
        if (AUTOMATE.needsRepair) {
            const actions = automateEl('div', 'modal-box__actions');
            actions.append(automateButton('Fix it', 'btn--primary', () => automateCall('/repair')));
            body.append(automateEl('div', 'form-group__hint', 'This KPI stopped producing valid values.'), actions);
        }
    } else {
        // PENDING_TEST / NEEDS_REPAIR: in progress, or awaiting repair
        body.append(automateEl('div', 'automate__text', 'Working on it. Reopen this dialog in a minute.'));
        if (AUTOMATE.needsRepair) {
            const actions = automateEl('div', 'modal-box__actions');
            actions.append(automateButton('Fix it', 'btn--primary', () => automateCall('/repair')));
            body.append(actions);
        }
    }
}
