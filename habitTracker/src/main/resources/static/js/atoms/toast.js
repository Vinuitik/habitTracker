// Minimal single-toast atom. Used for the one "it worked" signal the app currently needs:
// letting the user know a locally-queued write finally reached the server.
window.Toast = (() => {
  let el = null;
  let hideTimer = null;

  function ensureEl() {
    if (el) return el;
    el = document.createElement('div');
    el.className = 'toast';
    el.innerHTML = '<span class="toast__dot"></span><span class="toast__text"></span>';
    document.body.appendChild(el);
    return el;
  }

  function show(message, durationMs = 3000) {
    const node = ensureEl();
    node.querySelector('.toast__text').textContent = message;
    clearTimeout(hideTimer);
    requestAnimationFrame(() => node.classList.add('toast--visible'));
    hideTimer = setTimeout(() => node.classList.remove('toast--visible'), durationMs);
  }

  return { show };
})();
