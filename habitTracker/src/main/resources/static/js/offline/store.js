// Local single source of truth for the Today page. The UI renders ONLY from getToday():
//   last server snapshot (IndexedDB meta 'todaySnapshot') + every unsent habit-complete intent
//   from the outbox applied on top. A pull (refresh) replaces the snapshot, never the intents,
//   so a stale server response can't revert a tap.
// Intents pushed via Drive (driveSentAt set) stay as overlay for DRIVE_OVERLAY_TTL_MS — the
// server only drains Drive every 15 min, so a pull in that window would otherwise revert them.
const Store = (() => {
  const SNAPSHOT_KEY = 'todaySnapshot';
  const STREAKS_KEY = 'streaksSnapshot';
  const DRIVE_OVERLAY_TTL_MS = 20 * 60 * 1000;
  const PULL_TIMEOUT_MS = 5000;
  const listeners = new Set();
  let inflight = null;

  function overlayActive(intent) {
    return !intent.driveSentAt || Date.now() - intent.driveSentAt < DRIVE_OVERLAY_TTL_MS;
  }

  async function getToday() {
    const snap = await OfflineDB.getMeta(SNAPSHOT_KEY);
    if (!snap || !snap.date) return null;
    const intents = (await OfflineDB.all())
      .filter((i) => i.kind === 'habit-complete' && overlayActive(i))
      .sort((a, b) => a.ts - b.ts);
    const habits = (snap.habits || []).map((h) => ({ ...h }));
    for (const { payload } of intents) {
      if (payload.date && payload.date !== snap.date) continue;
      const h = habits.find((x) => x.id === payload.habitId);
      if (h) h.completed = payload.completed;
    }
    return { ...snap, habits };
  }

  // One pull at a time; null on any failure (offline, 530, 401) — callers keep what they have.
  function refresh() {
    if (inflight) return inflight;
    inflight = (async () => {
      const ctrl = new AbortController();
      const timer = setTimeout(() => ctrl.abort(), PULL_TIMEOUT_MS);
      try {
        const r = await fetch(ENV.ENDPOINTS.TODAY, { credentials: 'include', cache: 'no-store', signal: ctrl.signal });
        if (!r.ok) return null;
        const data = await r.json();
        if (!data || !data.date) return null;
        await OfflineDB.setMeta(SNAPSHOT_KEY, data);
        listeners.forEach((fn) => fn());
        return data;
      } catch (e) {
        return null;
      } finally {
        clearTimeout(timer);
        inflight = null;
      }
    })();
    return inflight;
  }

  return {
    getToday,
    refresh,
    onChange: (fn) => listeners.add(fn),
    notify: () => listeners.forEach((fn) => fn()),
    getStreaks: () => OfflineDB.getMeta(STREAKS_KEY),
    setStreaks: (s) => OfflineDB.setMeta(STREAKS_KEY, s),
    DRIVE_OVERLAY_TTL_MS,
  };
})();
