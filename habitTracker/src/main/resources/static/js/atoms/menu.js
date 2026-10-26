// Popover menu atom. Menu.open(anchorEl, [{label, danger, onClick}]). One menu at a time;
// closes on outside click, Escape, scroll or resize.
window.Menu = (() => {
  let el = null;

  function close() {
    if (!el) return;
    el.remove();
    el = null;
    document.removeEventListener('click', close, true);
    document.removeEventListener('keydown', onKey, true);
    window.removeEventListener('resize', close);
  }
  function onKey(e) { if (e.key === 'Escape') close(); }

  function open(anchor, items) {
    close();
    el = document.createElement('div');
    el.className = 'menu';
    el.setAttribute('role', 'menu');
    items.forEach((it) => {
      const b = document.createElement('button');
      b.type = 'button';
      b.className = 'menu__item' + (it.danger ? ' menu__item--danger' : '');
      b.setAttribute('role', 'menuitem');
      b.textContent = it.label;
      b.addEventListener('click', (e) => { e.stopPropagation(); close(); it.onClick(); });
      el.appendChild(b);
    });
    document.body.appendChild(el);
    const r = anchor.getBoundingClientRect();
    const w = el.offsetWidth;
    el.style.top = `${Math.min(r.bottom + 4, window.innerHeight - el.offsetHeight - 8)}px`;
    el.style.left = `${Math.max(8, Math.min(r.right - w, window.innerWidth - w - 8))}px`;
    // Defer so the click that opened the menu does not immediately close it.
    setTimeout(() => {
      document.addEventListener('click', close, true);
      document.addEventListener('keydown', onKey, true);
      window.addEventListener('resize', close);
    }, 0);
  }

  return { open, close };
})();
