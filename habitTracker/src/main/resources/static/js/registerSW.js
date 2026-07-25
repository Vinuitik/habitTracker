// Registers the service worker + requests persistent storage. Include on every page that
// already loads env.js. Secure-context only: the self-signed dev cert blocks SW registration
// in Chrome, so first install must happen over the real Cloudflare-tunnel domain.
//
// Update flow: sw.js's own install/activate handlers already call skipWaiting()+clients.claim()
// (see sw.js), so a new deploy (bump VERSION there) takes over as soon as the browser notices the
// byte diff — no reinstall of the PWA is ever needed. The only thing that was missing client-side
// was surfacing it: registration.update() below forces that check on every load/focus instead of
// waiting on the browser's own (throttled, up to 24h) background check, and the
// 'controllerchange' listener shows a reload prompt once the new worker actually takes control.
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
  banner.querySelector('button').addEventListener('click', () => window.location.reload());
  document.body.appendChild(banner);
  requestAnimationFrame(() => banner.classList.add('update-banner--visible'));
}

if ('serviceWorker' in navigator && window.isSecureContext) {
  navigator.serviceWorker.register('/sw.js').then((registration) => {
    if (navigator.storage && navigator.storage.persist) {
      navigator.storage.persist();
    }

    registration.update().catch(() => {});
    document.addEventListener('visibilitychange', () => {
      if (document.visibilityState === 'visible') registration.update().catch(() => {});
    });

    const hadController = !!navigator.serviceWorker.controller;
    registration.addEventListener('updatefound', () => {
      const installing = registration.installing;
      if (!installing) return;
      installing.addEventListener('statechange', () => {
        if (installing.state === 'activated' && hadController) {
          showUpdateBanner();
        }
      });
    });
  }).catch((err) => console.warn('[sw] registration failed:', err));
}
