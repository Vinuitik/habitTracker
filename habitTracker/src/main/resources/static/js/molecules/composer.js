// Inline "add" composer: a trigger button that expands into textarea + submit + close.
// Enter submits and keeps the composer open for the next entry (Shift+Enter = newline);
// Esc or the close button collapses it. Composer.html({...}) returns markup, Composer.bind(el, fn)
// wires the element it produced. fn(text) is called with trimmed non-empty text.
window.Composer = (() => {
  const { esc } = Dom;

  function html({ trigger, placeholder, submit }) {
    return `
      <div class="composer">
        <button type="button" class="composer__trigger" data-composer="open">${Icons.plus}${esc(trigger)}</button>
        <div class="composer__form" hidden>
          <textarea class="input composer__input" rows="2" maxlength="512" placeholder="${esc(placeholder)}" aria-label="${esc(placeholder)}"></textarea>
          <div class="composer__actions">
            <button type="button" class="btn btn--primary btn--sm" data-composer="submit">${esc(submit)}</button>
            <button type="button" class="btn btn--ghost btn--sm composer__close" data-composer="close" aria-label="Close">${Icons.close}</button>
          </div>
        </div>
      </div>`;
  }

  function bind(el, onSubmit) {
    const trigger = el.querySelector('.composer__trigger');
    const form = el.querySelector('.composer__form');
    const input = el.querySelector('.composer__input');
    const open = () => { trigger.hidden = true; form.hidden = false; input.focus(); };
    const close = () => { form.hidden = true; trigger.hidden = false; input.value = ''; };
    const submit = () => {
      const text = input.value.trim();
      if (!text) { input.focus(); return; }
      input.value = '';
      input.focus();
      onSubmit(text);
    };
    el.addEventListener('click', (e) => {
      const b = e.target.closest('[data-composer]');
      if (!b) return;
      ({ open, close, submit })[b.dataset.composer]();
    });
    input.addEventListener('keydown', (e) => {
      if (e.key === 'Enter' && !e.shiftKey && !e.isComposing) { e.preventDefault(); submit(); }
      else if (e.key === 'Escape') { e.stopPropagation(); close(); }
    });
    return { open, close };
  }

  return { html, bind };
})();
