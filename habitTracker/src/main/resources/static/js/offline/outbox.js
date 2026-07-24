// Central write dispatch for the two upsert-safe, offline-capable actions: marking a habit
// done/undone for a date, and setting a KPI value for a date. Both are pure upserts server-side
// (see StructureService.updateHabitCompletion / KPIService.addKPIData) so replaying the same
// intent more than once — from any of the three paths below — is always safe.
//
//   submit(intent):
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

  async function sendDirect(intent) {
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
        });
      } else if (intent.kind === 'kpi-value') {
        const { kpiName, date, value } = intent.payload;
        resp = await fetch(`/api/kpis/${encodeURIComponent(kpiName)}/data`, {
          method: 'POST',
          headers: { 'Content-Type': 'application/x-www-form-urlencoded', 'X-XSRF-TOKEN': csrf() },
          body: new URLSearchParams({ date, value: String(value) }),
        });
      } else {
        return false;
      }
      return resp.ok;
    } catch (e) {
      return false;
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

  async function flush() {
    const queued = await OfflineDB.all();
    if (queued.length === 0) return;

    if (await Connectivity.isServerReachable()) {
      for (const intent of queued) {
        if (await sendDirect(intent)) await OfflineDB.remove(intent.requestId);
      }
      return;
    }

    await DriveClient.refreshBridge();
    if (await DriveClient.isAvailable()) {
      try {
        await DriveClient.pushBatch(queued); // one batch file for everything still queued
        for (const intent of queued) await OfflineDB.remove(intent.requestId);
      } catch (e) {
        // leave queued, retried on the next flush
      }
    }
  }

  function submitHabitComplete(habitId, completed, date) {
    return submit({
      requestId: crypto.randomUUID(),
      kind: 'habit-complete',
      ts: Date.now(),
      payload: { habitId: Number(habitId), completed: !!completed, date: date || null },
    });
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
