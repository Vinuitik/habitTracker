// "Connect Trello" panel, shown whenever an endpoint answers 412. Saves credentials, then retries.
window.ConnectPanel = (() => {
  const { esc } = Dom;

  function render(container, err, onConnected) {
    const invalid = err && err.code === 'trello_token_invalid';
    container.innerHTML = `
      <section class="card connect-panel">
        <h2 class="connect-panel__title">Connect Trello</h2>
        <p class="connect-panel__text">${invalid
          ? 'Trello rejected your token. Enter a new one.'
          : 'Boards come from your Trello account. Enter an API key and token once.'}</p>
        <form class="connect-panel__form" novalidate>
          <div class="form-group">
            <label class="form-group__label" for="trello-key">API key</label>
            <input class="input" id="trello-key" autocomplete="off" required>
            <span class="form-group__hint">Create a Power-Up at <a class="link" href="${esc(ENV.EXTERNAL.TRELLO_POWERUP_ADMIN)}" target="_blank" rel="noopener noreferrer">trello.com/power-ups/admin</a> and copy its API key.</span>
          </div>
          <div class="form-group">
            <label class="form-group__label" for="trello-token">Token</label>
            <input class="input" id="trello-token" type="password" autocomplete="off" required>
            <span class="form-group__hint">On the same page, use the "Token" link next to the API key to authorize and copy the token.</span>
          </div>
          <div class="form-group__error" hidden></div>
          <div class="modal-box__actions"><button type="submit" class="btn btn--primary">Connect</button></div>
        </form>
      </section>`;
    const form = container.querySelector('form');
    const errBox = container.querySelector('.form-group__error');
    form.addEventListener('submit', async (e) => {
      e.preventDefault();
      const apiKey = form.querySelector('#trello-key').value.trim();
      const token = form.querySelector('#trello-token').value.trim();
      if (!apiKey || !token) return;
      const btn = form.querySelector('button[type=submit]');
      btn.disabled = true;
      errBox.hidden = true;
      try {
        await Api.request(ENV.ENDPOINTS.TRELLO_CREDENTIALS, 'PUT', { apiKey, token });
        Toast.show('Trello connected');
        onConnected();
      } catch (ex) {
        errBox.textContent = ex.message;
        errBox.hidden = false;
        btn.disabled = false;
      }
    });
  }

  return { render };
})();
