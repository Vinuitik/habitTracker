// Minimal single-toast atom. Used for the one "it worked" signal the app currently needs:
// letting the user know a locally-queued write finally reached the server.
window.Toast = (() => {
  let el = null;
  let hideTimer = null;

  function ensureEl() {
    if (el) return el;
    el = document.createElement('div');
    el.className = 'toast';
    el.innerHTML = '<span class="toast__dot"></span><span class="toast__text"></span><button type="button" class="toast__action" hidden></button>';
    document.body.appendChild(el);
    return el;
  }

  // action (optional): { label, onClick } renders an inline button (e.g. Undo).
  function show(message, durationMs = 3000, action) {
    const node = ensureEl();
    node.querySelector('.toast__text').textContent = message;
    const btn = node.querySelector('.toast__action');
    btn.hidden = !action;
    node.classList.toggle('toast--actionable', !!action);
    btn.onclick = action ? () => { node.classList.remove('toast--visible'); action.onClick(); } : null;
    if (action) btn.textContent = action.label;
    clearTimeout(hideTimer);
    requestAnimationFrame(() => node.classList.add('toast--visible'));
    hideTimer = setTimeout(() => node.classList.remove('toast--visible'), durationMs);
  }

  return { show };
})();
