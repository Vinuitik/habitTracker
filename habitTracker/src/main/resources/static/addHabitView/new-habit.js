function getCsrfToken() {
    const meta = document.querySelector('meta[name="_csrf"]');
    if (meta) return meta.getAttribute('content');
    const match = document.cookie.split('; ').find(r => r.startsWith('XSRF-TOKEN='));
    return match ? decodeURIComponent(match.split('=')[1]) : '';
}

// Patch all forms on the page to include the CSRF header via fetch, or inject a hidden input
document.addEventListener('DOMContentLoaded', function() {
  // Set today's date as default for start date
  const today = new Date().toISOString().split('T')[0];
  document.getElementById('startDate').value = today;
  
  // Show/hide custom frequency field based on selection
  document.getElementById('frequency').addEventListener('change', function() {
    const customContainer = document.getElementById('custom-frequency-container');
    if (this.value === 'CUSTOM') {
      customContainer.style.display = 'flex';
    } else {
      customContainer.style.display = 'none';
    }
  });
  
  // Inject CSRF token as hidden input into the form before submit
  const form = document.querySelector('.habit-form');
  const csrfToken = getCsrfToken();
  if (csrfToken && form) {
    const csrfInput = document.createElement('input');
    csrfInput.type = 'hidden';
    csrfInput.name = '_csrf';
    csrfInput.value = csrfToken;
    form.appendChild(csrfInput);
  }

  // Handle form submission to process custom frequency
  form.addEventListener('submit', function(e) {
    if (document.getElementById('frequency').value === 'CUSTOM') {
      e.preventDefault();
      const customValue = document.getElementById('customFrequency').value.trim();

      if (!customValue) {
        alert('Please enter a custom frequency');
        return;
      }

      document.getElementById('frequency').value = customValue;
      this.submit();
    }
  });
});