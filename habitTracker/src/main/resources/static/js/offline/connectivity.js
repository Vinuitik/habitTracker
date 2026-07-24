// Server reachability check. navigator.onLine is only a hint ("device has *a* network"), not
// proof the server is reachable — so this always does a real short-timeout fetch against the
// cheap, unauthenticated /api/ping route rather than trusting the browser flag alone.
const Connectivity = (() => {
  async function isServerReachable(timeoutMs = 2500) {
    if (!navigator.onLine) return false;
    try {
      const controller = new AbortController();
      const timer = setTimeout(() => controller.abort(), timeoutMs);
      const resp = await fetch('/api/ping', { signal: controller.signal, cache: 'no-store' });
      clearTimeout(timer);
      return resp.ok;
    } catch (e) {
      return false;
    }
  }

  return { isServerReachable };
})();
