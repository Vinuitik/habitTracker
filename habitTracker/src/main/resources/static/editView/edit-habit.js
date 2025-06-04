document.addEventListener('DOMContentLoaded', function() {
    const frequencySelect = document.getElementById('frequency');
    const customContainer = document.getElementById('custom-frequency-container');
    const customInput = document.getElementById('customFrequency');
    
    // Show/hide custom frequency input based on selection
    frequencySelect.addEventListener('change', function() {
        if (this.value === 'CUSTOM') {
            customContainer.style.display = 'block';
            frequencySelect.removeAttribute('name');
            customInput.setAttribute('name', 'frequency');
        } else {
            customContainer.style.display = 'none';
            frequencySelect.setAttribute('name', 'frequency');
            customInput.removeAttribute('name');
        }
    });

    // Form submission handling
    document.querySelector('.habit-form').addEventListener('submit', function(e) {
        e.preventDefault();
        
        // Ensure correct frequency field is being sent
        if (frequencySelect.value === 'CUSTOM') {
            if (!customInput.value.trim()) {
                alert('Please enter a custom frequency');
                return;
            }
            frequencySelect.removeAttribute('name');
            customInput.setAttribute('name', 'frequency');
        } else {
            customInput.removeAttribute('name');
            frequencySelect.setAttribute('name', 'frequency');
        }

        const formData = new FormData(this);
        
        fetch(this.action, {
            method: 'POST',
            body: formData
        })
        .then(response => {
            if (!response.ok) {
                throw new Error('Network response was not ok');
            }
            window.location.href = '/habits/list';
        })
        .catch(error => {
            console.error('Error:', error);
            alert('Failed to update habit. Please try again.');
        });
    });

    // Initialize custom frequency container if CUSTOM is selected
    if (frequencySelect.value === 'CUSTOM') {
        customContainer.style.display = 'block';
        frequencySelect.removeAttribute('name');
        customInput.setAttribute('name', 'frequency');
    }
});