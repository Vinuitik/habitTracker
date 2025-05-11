document.addEventListener('DOMContentLoaded', function () {
    // Prevent the default form submission
    const form = document.querySelector('form');
    
    form.addEventListener('submit', function(event) {
        event.preventDefault(); // Prevent the regular form submission
        
        // Get the habit ID from the input field
        const habitId = document.getElementById('habit-name').getAttribute('data-id');
        
        // Build the data object from form fields
        const habitData = {
            id: habitId,
            name: document.getElementById('habit-name').value,
            description: document.getElementById('habit-description').value,
            twoMinuteRule: document.getElementById('two-minute-rule').value,
            status: document.getElementById('habit-status').value,
            streak: document.getElementById('habit-streak').value
        };

        // Send data as JSON via fetch API
        fetch('/habits/info/save', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json'
            },
            body: JSON.stringify(habitData)
        })
        .then(response => {
            if (response.ok) {
                // Show success message
                const successMessage = document.createElement('div');
                successMessage.className = 'success-message';
                successMessage.textContent = 'Habit saved successfully!';
                form.prepend(successMessage);
                
                // Redirect after a short delay
                setTimeout(() => {
                    window.location.href = '/habits/list';
                }, 1000);
            } else {
                return response.text().then(text => {
                    throw new Error(text || 'Failed to save habit');
                });
            }
        })
        .catch(error => {
            console.error('Error:', error);
            alert(error.message || 'An error occurred. Please try again.');
        });
    });
});