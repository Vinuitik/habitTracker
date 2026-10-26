// Inline SVG icon strings (stroke = currentColor). Styled by styles/atoms/icon.css.
window.Icons = (() => {
  const svg = (d) => `<svg class="icon" viewBox="0 0 24 24" aria-hidden="true">${d}</svg>`;
  return {
    back:    svg('<path d="M19 12H5M12 19l-7-7 7-7"/>'),
    clock:   svg('<circle cx="12" cy="12" r="9"/><path d="M12 7v5l3 2"/>'),
    check:   svg('<path d="M5 12.5l4.5 4.5L19 7"/>'),
    list:    svg('<rect x="4" y="4" width="16" height="16" rx="2"/><path d="M8 12l3 3 5-6"/>'),
    desc:    svg('<path d="M4 6h16M4 12h16M4 18h10"/>'),
    comment: svg('<path d="M21 12a8 8 0 0 1-11.5 7.2L4 20l1-4.5A8 8 0 1 1 21 12z"/>'),
    kebab:   svg('<circle cx="12" cy="5" r="1.4"/><circle cx="12" cy="12" r="1.4"/><circle cx="12" cy="19" r="1.4"/>'),
    close:   svg('<path d="M6 6l12 12M18 6L6 18"/>'),
    plus:    svg('<path d="M12 5v14M5 12h14"/>'),
  };
})();
