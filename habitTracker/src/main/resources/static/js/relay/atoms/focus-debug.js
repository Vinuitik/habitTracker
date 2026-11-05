// Focus debug log (off by default). Toggle: tap the chat title (#chat-title) 5 times within 2s — the page reloads
// with the log on/off (remembered in localStorage). /relay?debugfocus=1|0 also works in a browser tab.
// Shows the last events that can drop the keyboard: focus moves, window blur, visibility, viewport resize,
// keys, IME composition, and #chat-input being removed / disabled. For diagnosing the keyboard-language bug.
(function () {
  const KEY = 'relay.debugFocus';
  const q = new URLSearchParams(location.search).get('debugfocus');
  let on = false;
  try { if (q !== null) localStorage.setItem(KEY, q); on = localStorage.getItem(KEY) === '1'; } catch (_) { return; }

  let taps = [];
  document.addEventListener('click', (e) => {
    if (!e.target.closest || !e.target.closest('#chat-title')) return;
    const now = Date.now();
    taps = taps.filter((t) => now - t < 2000).concat(now);
    if (taps.length < 5) return;
    try { localStorage.setItem(KEY, on ? '0' : '1'); } catch (_) { return; }
    location.reload();
  }, true);
  if (!on) return;

  const lines = [];
  let box = null;
  const t0 = performance.now();
  const name = (n) => !n ? '-' : n === window ? 'window' : n === document ? 'document'
    : (n.id ? '#' + n.id : (n.tagName || '?').toLowerCase() + (n.className && typeof n.className === 'string' ? '.' + n.className.split(' ')[0] : ''));
  function log(msg) {
    lines.push(((performance.now() - t0) / 1000).toFixed(2) + 's ' + msg + ' | active=' + name(document.activeElement));
    if (lines.length > 18) lines.shift();
    if (box) box.textContent = lines.join('\n');
  }

  document.addEventListener('focusin', (e) => log('focusin ' + name(e.target)), true);
  document.addEventListener('focusout', (e) => log('focusout ' + name(e.target) + ' → ' + name(e.relatedTarget)), true);
  window.addEventListener('blur', () => log('window blur'));
  window.addEventListener('focus', () => log('window focus'));
  document.addEventListener('visibilitychange', () => log('visibility ' + document.visibilityState));
  window.addEventListener('pagehide', () => log('pagehide'));
  window.addEventListener('resize', () => log('resize ' + innerWidth + 'x' + innerHeight));
  if (window.visualViewport) visualViewport.addEventListener('resize', () => log('vv resize h=' + Math.round(visualViewport.height)));
  document.addEventListener('keydown', (e) => log('keydown key=' + JSON.stringify(e.key) + ' code=' + e.keyCode), true);
  document.addEventListener('compositionstart', () => log('compositionstart'), true);
  document.addEventListener('compositionend', () => log('compositionend'), true);
  document.addEventListener('beforeinput', (e) => log('beforeinput ' + e.inputType), true);
  document.addEventListener('pointerdown', (e) => log('pointerdown ' + name(e.target)), true);

  let watched = null;
  new MutationObserver(() => {
    const el = document.getElementById('chat-input');
    if (el !== watched) { if (watched) log('#chat-input REPLACED/REMOVED'); watched = el; }
    if (el && el.disabled) log('#chat-input DISABLED');
  }).observe(document.documentElement, { subtree: true, childList: true, attributes: true, attributeFilter: ['disabled'] });

  document.addEventListener('DOMContentLoaded', () => {
    box = document.createElement('pre');
    box.className = 'focus-debug';
    box.textContent = 'focus debug on — tap the chat title 5× to turn off';
    document.body.appendChild(box);
  });
  log('debug start');
})();
