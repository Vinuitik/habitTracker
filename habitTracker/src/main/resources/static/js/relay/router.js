// Relay page router (relay/FLOWS.md "UI"). Hash routes, plus one path route:
//   #/                            RelayDevices  devices (pair / revoke)
//   #/install                     RelayInstall  install-a-runner guide (Linux / Windows)
//   #/d/<dev>                     RelayShell    rail + Needs-you strip, no project: "Pick a project" + recent sessions
//   #/d/<dev>/p/<pid>[/<tab>]     RelayShell    project tabs: chats (default) · git · containers · files
//   #/d/<dev>/s/<sid>             RelayShell    chat (RelayChat inside the shell, rail kept)
//   /relay/claim/<id>             RelayClaim    confirm a runner's QR pairing (path: it's the link inside the QR)
// RelayShell mounts the tab organisms (RelayChats / RelayGit / RelayContainers / RelayFiles / RelayChat)
// into its own content element with the same contract.
//
// Organism contract (js/relay/organisms/*):
//   window.<Organism> = { mount(el, ctx) → unmount }
//   el   = the element the organism owns (innerHTML) until unmounted (#relay-view, or the shell's content)
//   ctx  = { ...route params (dev, pid, tab, sid, id; decoded, absent when not in the route), refresh }
//          refresh() re-runs the current view (fresh unmount + mount)
//   mount must return synchronously (start async loads inside; drop their results after unmount)
//   unmount() stops everything the organism started: timers / polling, listeners outside el, page classes
//   optional unmount.update(params): the next route resolves to the same organism → re-route in place
//          instead of remounting (the shell keeps its rail); return false to get a normal remount.
// The router calls the previous unmount before every mount, so leaving a chat stops its polling.
(() => {
  const { $ } = Dom;
  const dec = decodeURIComponent;
  const HASH_ROUTES = [
    [/^\/d\/([^/]+)\/p\/([^/]+)(?:\/(chats|git|containers|files))?$/,
      (m) => [RelayShell, { dev: dec(m[1]), pid: dec(m[2]), tab: m[3] || 'chats' }]],
    [/^\/d\/([^/]+)\/s\/([^/]+)$/, (m) => [RelayShell, { dev: dec(m[1]), sid: dec(m[2]) }]],
    [/^\/d\/([^/]+)$/, (m) => [RelayShell, { dev: dec(m[1]) }]],
    [/^\/install$/, () => [RelayInstall, {}]],
  ];
  let current = null;   // { organism, unmount }

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
    const [organism, params] = resolve();
    const u = current && current.unmount;
    if (current && current.organism === organism && u && u.update && u.update(params) !== false) return;
    current = null;
    if (u) u();
    $('relay-page').classList.remove('relay-page--chat');
    window.scrollTo(0, 0);
    current = { organism, unmount: organism.mount($('relay-view'), { ...params, refresh: route }) || null };
  }

  window.addEventListener('hashchange', route);
  initTopbar(ENV.ROUTES.RELAY);
  route();
})();
