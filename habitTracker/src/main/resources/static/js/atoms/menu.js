// Popover menu atom. Menu.open(anchorEl, [{label, danger, onClick, confirm?}]). One menu at a time;
// closes on outside click, Escape or resize. An item with `confirm: 'text'` swaps the menu for an
// inline "text [label] [Cancel]" confirm instead of firing immediately.
window.Menu = (() => {
  let el = null;

  function close() {
    if (!el) return;
    el.remove();
    el = null;
    document.removeEventListener('click', onDoc, true);
    document.removeEventListener('keydown', onKey, true);
    window.removeEventListener('resize', close);
  }
  function onDoc(e) { if (el && !el.contains(e.target)) close(); }
  function onKey(e) { if (e.key === 'Escape') close(); }

  function btn(label, danger, onClick) {
    const b = document.createElement('button');
    b.type = 'button';
    b.className = 'menu__item' + (danger ? ' menu__item--danger' : '');
    b.setAttribute('role', 'menuitem');
    b.textContent = label;
    b.addEventListener('click', (e) => { e.stopPropagation(); onClick(); });
    return b;
  }

  function fill(items) {
    el.replaceChildren();
    items.forEach((it) => {
      if (it.confirm) { el.appendChild(btn(it.label, it.danger, () => fillConfirm(items, it))); return; }
      el.appendChild(btn(it.label, it.danger, () => { close(); it.onClick(); }));
    });
  }

  function fillConfirm(items, it) {
    el.replaceChildren();
    const t = document.createElement('div');
    t.className = 'menu__text';
    t.textContent = it.confirm;
    el.appendChild(t);
    el.appendChild(btn(it.label, it.danger, () => { close(); it.onClick(); }));
    el.appendChild(btn('Cancel', false, () => fill(items)));
  }

  function open(anchor, items) {
    close();
    el = document.createElement('div');
    el.className = 'menu';
    el.setAttribute('role', 'menu');
    fill(items);
    document.body.appendChild(el);
    const r = anchor.getBoundingClientRect();
    const w = el.offsetWidth;
    el.style.top = `${Math.min(r.bottom + 4, window.innerHeight - el.offsetHeight - 8)}px`;
    el.style.left = `${Math.max(8, Math.min(r.right - w, window.innerWidth - w - 8))}px`;
    // Defer so the click that opened the menu does not immediately close it.
    setTimeout(() => {
      if (!el) return;
      document.addEventListener('click', onDoc, true);
      document.addEventListener('keydown', onKey, true);
      window.addEventListener('resize', close);
    }, 0);
  }

  return { open, close };
})();
