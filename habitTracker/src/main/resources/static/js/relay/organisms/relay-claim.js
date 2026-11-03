// /relay/claim/<id> (path, not hash: it's the link inside the runner's pairing QR): confirm or deny.
// RelayClaim.mount(el, {id}) → unmount (contract: js/relay/router.js).
window.RelayClaim = (() => {
  const { $, esc } = Dom;
  const R = Relay;
  const EP = ENV.ENDPOINTS;
  const claimGone = '<div class="relay-empty relay-empty--error">This pairing link expired or was already used. Run the pair command again for a new QR.</div>';

  async function load(id, alive) {
    try {
      const { name } = await R.call(EP.RELAY_CLAIM(id));
      if (!alive()) return;
      $('claim-box').innerHTML = `
        <div class="relay-card">
          <div class="relay-card__label">Connect this machine to your account?</div>
          <div class="relay-code">${esc(name)}</div>
          <p class="relay-hint">Only confirm if you just ran the pair command on that machine yourself.</p>
          <div class="relay-head__actions">
            <button class="btn btn--ghost" data-act="deny" data-id="${esc(id)}">Not mine</button>
            <button class="btn btn--primary" data-act="approve" data-id="${esc(id)}">Confirm</button>
          </div>
        </div>`;
    } catch (e) {
      if (alive()) $('claim-box').innerHTML = e.status === 404 ? claimGone : R.errorHtml(e);
    }
  }

  async function claimAction(id, approve) {
    const box = $('claim-box');
    box.querySelectorAll('button').forEach((b) => { b.disabled = true; });
    try {
      await R.call(EP.RELAY_CLAIM_ACT(id, approve), 'POST');
      box.innerHTML = approve
        ? `<div class="relay-card"><div class="relay-card__label">Added</div>
             <p class="relay-hint">The machine finishes pairing and comes online in a few seconds.</p>
             <a class="btn btn--primary" href="/relay#/">Go to devices</a></div>`
        : '<div class="relay-empty">Declined. The pair command on that machine stops.</div>';
    } catch (e) {
      box.innerHTML = e.status === 404 ? claimGone : R.errorHtml(e);
    }
  }

  function mount(el, ctx) {
    let mounted = true;
    el.innerHTML = R.header('Add device', ['/relay#/', 'Devices']) + '<div id="claim-box"><div class="relay-empty">Loading…</div></div>';
    const off = R.onAct(el, {
      approve: (b) => claimAction(b.dataset.id, true),
      deny: (b) => claimAction(b.dataset.id, false),
    });
    load(ctx.id, () => mounted);
    return () => { mounted = false; off(); };
  }

  return { mount };
})();
