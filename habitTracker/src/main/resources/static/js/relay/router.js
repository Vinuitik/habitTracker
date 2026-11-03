// Relay page router (relay/FLOWS.md "UI"). Hash routes, plus one path route:
//   #/                       RelayDevices   devices (pair / revoke)
//   #/install                RelayInstall   install-a-runner guide (Linux / Windows)
//   #/d/<dev>                RelaySessions  sessions on that machine (+ new session)
//   #/d/<dev>/s/<sid>        RelayChat      chat
//   #/d/<dev>/p/<pid>/containers  RelayContainers  docker compose control (temporary route until the shell)
//   /relay/claim/<id>        RelayClaim     confirm a runner's QR pairing (path: it's the link inside the QR)
//
// Organism contract (js/relay/organisms/*), what later tabs (Git / Containers / Files / Subagents) plug into:
//   window.<Organism> = { mount(el, ctx) → unmount }
//   el   = #relay-view, the organism owns its innerHTML until unmounted
//   ctx  = { ...route params (dev, pid, sid, id; decoded, absent when not in the route), refresh }
//          refresh() re-runs the current route (fresh unmount + mount)
//   mount must return synchronously (start async loads inside; drop their results after unmount)
//   unmount() stops everything the organism started: timers / polling, listeners outside el, page classes
// The router calls the previous unmount before every mount, so leaving a chat stops its polling.
(() => {
  const { $ } = Dom;
  const dec = decodeURIComponent;
  const HASH_ROUTES = [
    [/^\/d\/([^/]+)\/p\/([^/]+)\/files$/, (m) => [RelayFiles, { dev: dec(m[1]), pid: dec(m[2]) }]],   // temporary until the M1 shell
    [/^\/d\/([^/]+)\/s\/([^/]+)$/, (m) => [RelayChat, { dev: dec(m[1]), sid: dec(m[2]) }]],
    [/^\/d\/([^/]+)\/p\/([^/]+)\/containers$/, (m) => [RelayContainers, { dev: dec(m[1]), pid: dec(m[2]) }]],
    [/^\/d\/([^/]+)\/p\/([^/]+)\/git$/, (m) => [RelayGit, { dev: dec(m[1]), pid: dec(m[2]) }]],
    [/^\/d\/([^/]+)$/, (m) => [RelaySessions, { dev: dec(m[1]) }]],
    [/^\/install$/, () => [RelayInstall, {}]],
  ];
  let unmount = null;

  function resolve() {
    const claim = location.pathname.match(/^\/relay\/claim\/([^/]+)$/);
    if (claim) return [RelayClaim, { id: dec(claim[1]) }];
    const h = location.hash.replace(/^#/, '');
    for (const [re, build] of HASH_ROUTES) {
      const m = h.match(re);
      if (m) return build(m);
    }
    return [RelayDevices, {}];
  }

  function route() {
    if (unmount) { const u = unmount; unmount = null; u(); }
    $('relay-page').classList.remove('relay-page--chat');
    window.scrollTo(0, 0);
    const [organism, params] = resolve();
    unmount = organism.mount($('relay-view'), { ...params, refresh: route }) || null;
  }

  window.addEventListener('hashchange', route);
  initTopbar(ENV.ROUTES.RELAY);
  route();
})();
