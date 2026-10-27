// fetch wrapper: JSON + CSRF header. Errors carry .status and .code (server's {error}).
// 412 means Trello is not connected / token invalid (see ConnectPanel).
window.Api = (() => {
  const csrf = () => {
    const m = document.cookie.split('; ').find((r) => r.startsWith('XSRF-TOKEN='));
    return m ? decodeURIComponent(m.split('=')[1]) : '';
  };

  async function raw(url, method = 'GET', body) {
    const res = await fetch(url, {
      method,
      credentials: 'include',
      headers: { 'Content-Type': 'application/json', 'X-XSRF-TOKEN': csrf() },
      body: body === undefined ? undefined : JSON.stringify(body),
    });
    const data = res.status === 204 ? null : await res.json().catch(() => null);
    if (!res.ok) {
      const err = new Error((data && data.error) || `Request failed (${res.status})`);
      err.status = res.status;
      err.code = data && data.error;
      throw err;
    }
    return { data, res };
  }

  const request = async (url, method, body) => (await raw(url, method, body)).data;
  const isTrelloAuth = (e) => !!e && e.status === 412;

  return { request, raw, isTrelloAuth };
})();
