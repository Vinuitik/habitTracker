/* Renders the shared topbar and wires up CSRF for the logout form.
   Call initTopbar(activeRoute) after DOM is ready.
   activeRoute: one of the href strings, e.g. '/habits/add' */
function initTopbar(activeRoute) {
  const csrf = (() => {
    const m = document.cookie.split('; ').find(r => r.startsWith('XSRF-TOKEN='));
    return m ? decodeURIComponent(m.split('=')[1]) : '';
  })();

  const csrfInput = document.getElementById('logout-csrf');
  if (csrfInput) csrfInput.value = csrf;

  // Mark active link
  document.querySelectorAll('.topbar__link').forEach(a => {
    a.classList.toggle('active', a.getAttribute('href') === activeRoute);
  });
}
