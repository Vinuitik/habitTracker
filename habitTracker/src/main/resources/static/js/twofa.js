// 2FA setup page (twofa-setup.html): "Copy setup key". Everything else on the page works without JS,
// so the button stays hidden unless this runs.
(() => {
  document.querySelectorAll('[data-copy]').forEach((btn) => {
    btn.hidden = false;
    const label = btn.textContent;
    btn.addEventListener('click', async () => {
      try {
        await navigator.clipboard.writeText(btn.dataset.copy);   // secure context only (https / localhost)
        btn.textContent = 'Copied';
      } catch (_) {
        const key = btn.parentElement.querySelector('.auth-secret');
        const r = document.createRange();
        r.selectNodeContents(key);
        getSelection().removeAllRanges();
        getSelection().addRange(r);
        btn.textContent = 'Selected — copy it';
      }
      setTimeout(() => { btn.textContent = label; }, 2000);
    });
  });
})();
