// Tiny DOM helpers shared by the Projects page scripts: id lookup, HTML escaping, URL
// allow-listing, relative time, modal open/close.
window.Dom = (() => {
  const $ = (id) => document.getElementById(id);
  const esc = (s) => String(s ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
  // Only https URLs may reach an href (blocks javascript: / data: from Trello-provided strings).
  const safeUrl = (u) => (typeof u === 'string' && /^https:\/\//i.test(u) ? u : '');
  const open = (id) => $(id).classList.add('open');
  const close = (id) => $(id).classList.remove('open');
  function timeAgo(iso) {
    const t = Date.parse(iso);
    if (!t) return 'never';
    const s = Math.max(0, Math.round((Date.now() - t) / 1000));
    if (s < 60) return 'just now';
    if (s < 3600) return `${Math.floor(s / 60)}m ago`;
    if (s < 86400) return `${Math.floor(s / 3600)}h ago`;
    return `${Math.floor(s / 86400)}d ago`;
  }
  return { $, esc, safeUrl, open, close, timeAgo };
})();
