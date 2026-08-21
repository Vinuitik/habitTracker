// Registers the service worker + requests persistent storage. Include on every page that
// already loads env.js. Secure-context only: the self-signed dev cert blocks SW registration
// in Chrome, so first install must happen over the real Cloudflare-tunnel domain.
//
// Update flow: a new sw.js (bump VERSION there) installs and then WAITS — it does not take over
// on its own (see sw.js). registration.update() below forces the browser to check for that new
// worker on every load/focus instead of waiting on its own throttled (up to 24h) background
// check. Once a worker is sitting in `registration.waiting`, we only ever notify (banner / topbar
// dot) — the actual swap happens exclusively through applyUpdate(), which the user triggers by
// clicking Reload/Update. That's deliberate: auto-activating used to yank any open tab onto a
// fresh deploy the moment it was detected, which broke if the new build's hashed assets hadn't
// finished propagating behind the tunnel yet (a Cloudflare 530 mid-deploy leaves the tab dead).
function showUpdateBanner() {
  if (document.getElementById('sw-update-banner')) return;

  const link = document.createElement('link');
  link.rel = 'stylesheet';
  link.href = '/styles/molecules/update-banner.css';
  document.head.appendChild(link);

  const banner = document.createElement('div');
  banner.id = 'sw-update-banner';
  banner.className = 'update-banner';
  banner.innerHTML = `
    <span class="update-banner__text">A new version is ready.</span>
    <button class="btn btn--primary" type="button">Reload</button>
  `;
  banner.querySelector('button').addEventListener('click', () => window.applyUpdate());
  document.body.appendChild(banner);
  requestAnimationFrame(() => banner.classList.add('update-banner--visible'));
}

let swRegistration = null;

// The one path that hands control to the new worker: tell it to skipWaiting, then reload once it
// actually becomes the controller. Only ever called from a user click (banner Reload / topbar
// Update button) — never automatically.
window.applyUpdate = function applyUpdate() {
  const waiting = swRegistration && swRegistration.waiting;
  if (!waiting) {
    window.location.reload();
    return;
  }
  navigator.serviceWorker.addEventListener('controllerchange', () => {
    window.location.reload();
  }, { once: true });
  waiting.postMessage('SKIP_WAITING');
};

if ('serviceWorker' in navigator && window.isSecureContext) {
  navigator.serviceWorker.register('/sw.js').then((registration) => {
    swRegistration = registration;
    if (navigator.storage && navigator.storage.persist) {
      navigator.storage.persist();
    }

    registration.update().catch(() => {});
    document.addEventListener('visibilitychange', () => {
      if (document.visibilityState === 'visible') registration.update().catch(() => {});
    });

    // Gated on "there's already a controller" so a first install is never mistaken for an update.
    const notifyIfWaiting = () => {
      if (registration.waiting && navigator.serviceWorker.controller) {
        showUpdateBanner();
        if (window.TopbarUpdate) TopbarUpdate.markAvailable();
      }
    };

    // Catches a worker that finished installing before this listener was attached.
    notifyIfWaiting();

    registration.addEventListener('updatefound', () => {
      const installing = registration.installing;
      if (!installing) return;
      installing.addEventListener('statechange', () => {
        if (installing.state === 'installed') notifyIfWaiting();
      });
    });
  }).catch((err) => console.warn('[sw] registration failed:', err));
}
