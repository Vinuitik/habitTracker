// Pointer-based drag and drop for sortable items across containers (works for mouse + touch;
// HTML5 DnD is deliberately not used). Touch drags start only after a long-press without
// movement, so normal scrolling still works.
//
// Dnd.init(root, {
//   itemSel, zoneSel, containerSel, scrollerSel, ignoreSel,   // selectors
//   handleSel?  (drag starts only from inside this element of the item, e.g. a list header)
//   axis?       ('y' default: items stacked vertically; 'x': items laid out in a row, zone itself is the container)
//   cfg: {holdMs, startPx, holdSlopPx, edgePx, maxPx},        // from ENV
//   onDrop({ item, fromContainer, toContainer, prevEl, nextEl })
// })
// Classes applied: .dnd-ghost (floating clone), .dnd-placeholder (drop slot), .dnd-source (hidden
// original), .dnd-active on <body>, .dnd-scroller (scroller while dragging; CSS disables snap).
window.Dnd = (() => {
  function init(root, o) {
    const c = o.cfg;
    const horiz = o.axis === 'x';
    let pend = null;   // pointerdown seen, drag not started yet
    let drag = null;   // active drag

    root.addEventListener('pointerdown', (e) => {
      if (drag || pend || (e.pointerType === 'mouse' && e.button !== 0)) return;
      const item = e.target.closest(o.itemSel);
      if (!item || !root.contains(item) || e.target.closest(o.ignoreSel)) return;
      if (o.handleSel && !e.target.closest(o.handleSel)) return;
      pend = { item, id: e.pointerId, type: e.pointerType, x0: e.clientX, y0: e.clientY, x: e.clientX, y: e.clientY, timer: null };
      if (pend.type !== 'mouse') pend.timer = setTimeout(() => { if (pend) start(); }, c.holdMs);
      window.addEventListener('pointermove', onMove);
      window.addEventListener('pointerup', onUp);
      window.addEventListener('pointercancel', onCancel);
    });

    // Registered up front (iOS ignores preventDefault from listeners added mid-gesture).
    root.addEventListener('touchmove', (e) => { if (drag && e.cancelable) e.preventDefault(); }, { passive: false });

    function listenersOff() {
      window.removeEventListener('pointermove', onMove);
      window.removeEventListener('pointerup', onUp);
      window.removeEventListener('pointercancel', onCancel);
      document.removeEventListener('keydown', onKey, true);
      document.removeEventListener('contextmenu', blockCtx, true);
    }
    const blockCtx = (e) => e.preventDefault();
    const onKey = (e) => { if (e.key === 'Escape') cancel(); };

    function onMove(e) {
      if (e.pointerId !== (pend || drag).id) return;
      if (pend) {
        pend.x = e.clientX; pend.y = e.clientY;
        const d = Math.hypot(pend.x - pend.x0, pend.y - pend.y0);
        if (pend.type === 'mouse') { if (d >= c.startPx) start(); }
        else if (d > c.holdSlopPx) { clearTimeout(pend.timer); pend = null; listenersOff(); }   // user is scrolling
        return;
      }
      drag.x = e.clientX; drag.y = e.clientY;
    }

    function start() {
      const { item, id, x, y } = pend;
      clearTimeout(pend.timer);
      pend = null;
      const r = item.getBoundingClientRect();
      const ghost = item.cloneNode(true);
      ghost.classList.add('dnd-ghost');
      ghost.removeAttribute('tabindex');
      ghost.style.width = `${r.width}px`;
      const ph = document.createElement('div');
      ph.className = 'dnd-placeholder';
      ph.style.height = `${r.height}px`;
      if (horiz) { ph.style.width = `${r.width}px`; ghost.style.height = `${r.height}px`; }
      item.parentElement.insertBefore(ph, item);
      item.classList.add('dnd-source');
      document.body.appendChild(ghost);
      const scrollers = [...root.querySelectorAll(o.scrollerSel)];
      const scroller = scrollers[0];
      scrollers.forEach((sc) => sc.classList.add('dnd-scroller'));
      document.body.classList.add('dnd-active');
      drag = { item, id, ghost, ph, scroller, scrollers, x, y, dx: x - r.left, dy: y - r.top, raf: 0, container: item.parentElement };
      document.addEventListener('keydown', onKey, true);
      document.addEventListener('contextmenu', blockCtx, true);
      tick();
    }

    function tick() {
      if (!drag) return;
      const d = drag;
      d.ghost.style.transform = `translate(${d.x - d.dx}px, ${d.y - d.dy}px)`;
      const under = document.elementFromPoint(d.x, d.y);   // ghost has pointer-events: none
      const zone = under && under.closest(o.zoneSel);
      const cont = zone && (o.containerSel ? zone.querySelector(o.containerSel) : zone);
      if (cont) d.container = cont;
      if (horiz) d.scroller = d.container;
      placeIn(d.container, horiz ? d.x : d.y);
      autoScroll(d);
      d.raf = requestAnimationFrame(tick);
    }

    function siblings(cont, d) {
      return [...cont.children].filter((n) => n.matches(o.itemSel) && n !== d.item);
    }

    // pt = pointer coordinate along the item axis (y for stacked items, x for a row).
    function placeIn(cont, pt) {
      const d = drag;
      const sibs = siblings(cont, d);
      const before = sibs.find((n) => {
        const r = n.getBoundingClientRect();
        return pt < (horiz ? r.left + r.width / 2 : r.top + r.height / 2);
      });
      if (before) { if (d.ph.nextSibling !== before || d.ph.parentElement !== cont) cont.insertBefore(d.ph, before); }
      else if (horiz && sibs.length) {   // keep the slot before trailing non-item children (e.g. the "add list" column)
        const last = sibs[sibs.length - 1];
        if (d.ph.previousSibling !== last || d.ph.parentElement !== cont) last.after(d.ph);
      }
      else if (d.ph.parentElement !== cont || d.ph.nextSibling) cont.appendChild(d.ph);
    }

    function speed(dist) {   // dist = how far inside the edge band the pointer is (0..edge)
      return Math.ceil((Math.min(dist, c.edgePx) / c.edgePx) * c.maxPx);
    }

    function autoScroll(d) {
      const sr = d.scroller.getBoundingClientRect();
      if (d.x < sr.left + c.edgePx) d.scroller.scrollLeft -= speed(sr.left + c.edgePx - d.x);
      else if (d.x > sr.right - c.edgePx) d.scroller.scrollLeft += speed(d.x - (sr.right - c.edgePx));
      if (horiz) return;
      const cr = d.container.getBoundingClientRect();
      if (d.y < cr.top + c.edgePx) d.container.scrollTop -= speed(cr.top + c.edgePx - d.y);
      else if (d.y > cr.bottom - c.edgePx) d.container.scrollTop += speed(d.y - (cr.bottom - c.edgePx));
    }

    function teardown() {
      if (pend) clearTimeout(pend.timer);
      pend = null;
      listenersOff();
      if (!drag) return null;
      const d = drag;
      drag = null;
      cancelAnimationFrame(d.raf);
      d.ghost.remove();
      d.item.classList.remove('dnd-source');
      d.scrollers.forEach((sc) => sc.classList.remove('dnd-scroller'));
      document.body.classList.remove('dnd-active');
      // swallow the click that follows a drag release
      const eat = (ev) => { ev.stopPropagation(); ev.preventDefault(); };
      document.addEventListener('click', eat, true);
      setTimeout(() => document.removeEventListener('click', eat, true), 0);
      return d;
    }

    function onUp(e) {
      if (e.pointerId !== (pend || drag || {}).id) return;
      const d = teardown();
      if (!d) return;
      const to = d.ph.parentElement;
      const items = [...to.children].filter((n) => n.matches(o.itemSel) && n !== d.item);
      const next = items.find((n) => n.compareDocumentPosition(d.ph) & Node.DOCUMENT_POSITION_FOLLOWING) || null;
      const prev = next ? items[items.indexOf(next) - 1] || null : items[items.length - 1] || null;
      const from = d.item.parentElement;
      d.ph.remove();
      o.onDrop({ item: d.item, fromContainer: from, toContainer: to, prevEl: prev, nextEl: next });
    }

    function onCancel() { cancel(); }
    function cancel() {
      const d = teardown();
      if (d) d.ph.remove();
    }
  }

  return { init };
})();
