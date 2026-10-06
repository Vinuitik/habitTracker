// Central write dispatch for the two upsert-safe, offline-capable actions: marking a habit
// done/undone for a date, and setting a KPI value for a date. Both are pure upserts server-side
// (see StructureService.updateHabitCompletion / KPIService.addKPIData) so replaying the same
// intent more than once — from any of the three paths below — is always safe.
//
//   submitHabitComplete: local-first — enqueue, return, flush() syncs in the background.
//   submit(intent) (still used by KPI values):
//     server reachable → send directly, done (no intermediary)
//     else online + Drive bridge valid → encrypt + push to the user's own Drive mailbox, done
//     else → enqueue locally only, no network attempted
//   flush(): drains anything still purely local, same branch, called on reconnect/focus/interval
const Outbox = (() => {
  function csrf() {
    const meta = document.querySelector('meta[name="_csrf"]');
    if (meta) return meta.getAttribute('content');
    const match = document.cookie.split('; ').find((r) => r.startsWith('XSRF-TOKEN='));
    return match ? decodeURIComponent(match.split('=')[1]) : '';
  }

  const SEND_TIMEOUT_MS = 5000;

  async function sendDirect(intent) {
    const ctrl = new AbortController();
    const timer = setTimeout(() => ctrl.abort(), SEND_TIMEOUT_MS);
    try {
      let resp;
      if (intent.kind === 'habit-complete') {
        const { habitId, completed, date } = intent.payload;
        const form = new URLSearchParams({ completed: String(completed) });
        if (date) form.append('date', date);
        resp = await fetch(`/habits/update/${habitId}`, {
          method: 'POST',
          headers: { 'Content-Type': 'application/x-www-form-urlencoded', 'X-XSRF-TOKEN': csrf() },
          body: form,
          signal: ctrl.signal,
        });
      } else if (intent.kind === 'kpi-value') {
        const { kpiName, date, value } = intent.payload;
        resp = await fetch(`/api/kpis/${encodeURIComponent(kpiName)}/data`, {
          method: 'POST',
          headers: { 'Content-Type': 'application/x-www-form-urlencoded', 'X-XSRF-TOKEN': csrf() },
          body: new URLSearchParams({ date, value: String(value) }),
          signal: ctrl.signal,
        });
      } else {
        return false;
      }
      return resp.ok;
    } catch (e) {
      return false;
    } finally {
      clearTimeout(timer);
    }
  }

  async function submit(intent) {
    if (await Connectivity.isServerReachable()) {
      if (await sendDirect(intent)) return { via: 'server' };
    }

    await DriveClient.refreshBridge();
    if (await DriveClient.isAvailable()) {
      try {
        await DriveClient.pushBatch([intent]);
        return { via: 'drive' };
      } catch (e) {
        // fall through to the local queue
      }
    }

    await OfflineDB.enqueue(intent);
    return { via: 'queued' };
  }

  // Queued items resolve silently otherwise — a flush that just returns leaves the user with no
  // sign the pending write ever left the device, which reads as broken even when it isn't.
  function notifySynced(count) {
    if (count > 0 && typeof window !== 'undefined' && window.Toast) {
      window.Toast.show(count === 1 ? 'Synced 1 pending update' : `Synced ${count} pending updates`);
    }
  }

  // Single-flight: taps during a running flush set `again` so they're picked up right after.
  let flushing = false;
  let again = false;

  async function flush() {
    if (flushing) { again = true; return; }
    flushing = true;
    try {
      do { again = false; await flushOnce(); } while (again);
    } finally {
      flushing = false;
    }
  }

  async function flushOnce() {
    const all = await OfflineDB.all();
    const cutoff = Date.now() - Store.DRIVE_OVERLAY_TTL_MS;
    for (const i of all) {
      if (i.driveSentAt && i.driveSentAt < cutoff) await OfflineDB.remove(i.requestId);
    }
    let remaining = all.filter((i) => !i.driveSentAt);

    // Try the server directly, no ping first — the POST itself is the reachability test.
    let sent = 0;
    while (remaining.length > 0 && await sendDirect(remaining[0])) {
      await OfflineDB.remove(remaining[0].requestId);
      remaining = remaining.slice(1);
      sent++;
    }
    notifySynced(sent);

    if (remaining.length > 0) {
      await DriveClient.refreshBridge();
      if (await DriveClient.isAvailable()) {
        try {
          await DriveClient.pushBatch(remaining); // one batch file for everything still queued
          for (const intent of remaining) {
            await OfflineDB.enqueue({ ...intent, driveSentAt: Date.now() }); // keep as overlay
          }
        } catch (e) {
          // leave queued, retried on the next flush
        }
      }
      Store.notify();
      return;
    }
    await Store.refresh();
  }

  // Local-first: the intent is persisted to IndexedDB (which Store overlays onto the UI) and the
  // call returns immediately; the network work happens in flush(), never on the tap's path.
  async function submitHabitComplete(habitId, completed, date) {
    await OfflineDB.enqueue({
      requestId: crypto.randomUUID(),
      kind: 'habit-complete',
      ts: Date.now(),
      payload: { habitId: Number(habitId), completed: !!completed, date: date || null },
    });
    flush();
    return { via: 'queued' };
  }

  function submitKpiValue(kpiName, date, value) {
    return submit({
      requestId: crypto.randomUUID(),
      kind: 'kpi-value',
      ts: Date.now(),
      payload: { kpiName, date, value: Number(value) },
    });
  }

  // Bootstrap: opportunistically refresh the Drive bridge and drain the queue whenever the
  // app has a real chance of succeeding — reconnect, tab refocus, and a background interval.
  if (typeof window !== 'undefined') {
    window.addEventListener('load', () => { DriveClient.refreshBridge(); flush(); });
    window.addEventListener('online', () => { DriveClient.refreshBridge(); flush(); });
    document.addEventListener('visibilitychange', () => {
      if (document.visibilityState === 'visible') flush();
    });
    setInterval(flush, 5 * 60 * 1000);
  }

  return { submitHabitComplete, submitKpiValue, flush, pendingCount: () => OfflineDB.count() };
})();
