document.addEventListener('DOMContentLoaded', function() {
    const frequencySelect = document.getElementById('frequency');
    const customContainer = document.getElementById('custom-frequency-container');
    const customInput = document.getElementById('customFrequency');
    
    // Show/hide custom frequency input based on selection
    frequencySelect.addEventListener('change', function() {
        if (this.value === 'CUSTOM') {
            customContainer.style.display = 'block';
            customInput.required = true;
        } else {
            customContainer.style.display = 'none';
            customInput.required = false;
        }
    });

    // Form submission handling
    document.querySelector('.habit-form').addEventListener('submit', function(e) {
        e.preventDefault();
        
        const formData = new FormData(this);

        
        fetch(this.action, {
            method: 'POST',
            body: formData
        })
        .then(response => {
            if (!response.ok) {
                throw new Error('Network response was not ok');
            }
            window.location.href = '/habits/list'; // Redirect on success
        })
        .catch(error => {
            console.error('Error:', error);
            alert('Failed to update habit. Please try again.');
        });
    });

    // Initialize custom frequency container if CUSTOM is selected
    if (frequencySelect.value === 'CUSTOM') {
        customContainer.style.display = 'block';
        customInput.required = true;
    }
});