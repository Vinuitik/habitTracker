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
  
  // Handle form submission to process custom frequency
  document.querySelector('.habit-form').addEventListener('submit', function(e) {
    if (document.getElementById('frequency').value === 'CUSTOM') {
      e.preventDefault();
      const customValue = document.getElementById('customFrequency').value.trim();
      
      if (!customValue) {
        alert('Please enter a custom frequency');
        return;
      }
      
      // Set the custom value to the frequency field
      document.getElementById('frequency').value = customValue;
      
      // Now submit the form
      this.submit();
    }
  });
});