// Registers the service worker + requests persistent storage. Include on every page that
// already loads env.js. Secure-context only: the self-signed dev cert blocks SW registration
// in Chrome, so first install must happen over the real Cloudflare-tunnel domain.
if ('serviceWorker' in navigator && window.isSecureContext) {
  navigator.serviceWorker.register('/sw.js').then((registration) => {
    if (navigator.storage && navigator.storage.persist) {
      navigator.storage.persist();
    }

    const hadController = !!navigator.serviceWorker.controller;
    registration.addEventListener('updatefound', () => {
      const installing = registration.installing;
      if (!installing) return;
      installing.addEventListener('statechange', () => {
        if (installing.state === 'activated' && hadController) {
          console.log('[sw] update installed — reload to pick it up');
        }
      });
    });
  }).catch((err) => console.warn('[sw] registration failed:', err));
}
