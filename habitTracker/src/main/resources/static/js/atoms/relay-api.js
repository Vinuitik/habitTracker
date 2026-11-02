// Api.request for calls that reach a runner through the Relay hub (/relay/api/** and the project
// link/map endpoints). Hub status mapping, same as relay/relay.js `call()`:
// 401 = 2FA not verified / expired → /2fa/verify?next=<this page>; 503 = runner offline; 504 = no answer.
window.RelayApi = (() => {
  async function call(url, method, body) {
    try {
      return await Api.request(url, method, body);
    } catch (e) {
      if (e.status === 401) {
        location.href = `${ENV.ROUTES.TWOFA_VERIFY}?next=${encodeURIComponent(location.pathname + location.search + location.hash)}`;
        e.message = 'Two-factor check needed — redirecting…';
      } else if (e.status === 503) e.message = 'Runner offline';
      else if (e.status === 504) e.message = "Runner didn't answer";
      else if (!e.status) e.message = 'Network error — HabitTracker unreachable';
      throw e;
    }
  }
  return { call };
})();
