// /relay/claim/<id> (path, not hash: it's the link inside the runner's pairing QR): confirm or deny.
// RelayClaim.mount(el, {id}) → unmount (contract: js/relay/router.js).
window.RelayClaim = (() => {
  const { $, esc } = Dom;
  const R = Relay;
  const EP = ENV.ENDPOINTS;
  const claimGone = '<div class="relay-empty relay-empty--error">This pairing link expired or was already used. Start the setup again (or run the pair command) for a new link.</div>';

  async function load(id, alive) {
    try {
      const { name } = await R.call(EP.RELAY_CLAIM(id));
      if (!alive()) return;
      $('claim-box').innerHTML = `
        <div class="relay-card relay-claim relay-in">
          <div class="relay-card__label">New computer</div>
          <h2 class="relay-claim__title">Connect ${esc(name)}?</h2>
          <p class="relay-hint">This lets Relay start and drive agent sessions on that computer from your account.
            Only confirm if you just installed or paired it yourself.</p>
          <div class="relay-claim__actions">
            <button class="btn btn--ghost" data-act="deny" data-id="${esc(id)}" data-name="${esc(name)}">Not mine</button>
            <button class="btn btn--primary" data-act="approve" data-id="${esc(id)}" data-name="${esc(name)}">Confirm</button>
          </div>
        </div>`;
    } catch (e) {
      if (alive()) $('claim-box').innerHTML = e.status === 404 ? claimGone : R.errorHtml(e);
    }
  }

  async function claimAction(id, approve, name) {
    const box = $('claim-box');
    box.querySelectorAll('button').forEach((b) => { b.disabled = true; });
    try {
      await R.call(EP.RELAY_CLAIM_ACT(id, approve), 'POST');
      box.innerHTML = approve
        ? `<div class="relay-card relay-claim relay-claim--done relay-in" role="status">
             <h2 class="relay-claim__title">✓ ${esc(name)} connected</h2>
             <p class="relay-hint">You can close this tab. The computer comes online in a few seconds.</p>
             <div class="relay-claim__actions"><a class="btn btn--primary" href="/relay#/">Open devices</a></div></div>`
        : '<div class="relay-empty">Declined. Nothing was connected.</div>';
    } catch (e) {
      box.innerHTML = e.status === 404 ? claimGone : R.errorHtml(e);
    }
  }

  function mount(el, ctx) {
    let mounted = true;
    el.innerHTML = R.header('Connect a computer', ['/relay#/', 'Devices']) + '<div id="claim-box"><div class="relay-empty">Loading…</div></div>';
    const off = R.onAct(el, {
      approve: (b) => claimAction(b.dataset.id, true, b.dataset.name),
      deny: (b) => claimAction(b.dataset.id, false, b.dataset.name),
    });
    load(ctx.id, () => mounted);
    return () => { mounted = false; off(); };
  }

  return { mount };
})();
