document.addEventListener('DOMContentLoaded', function() {
    // Handle dropdown toggles
    document.querySelectorAll('.dropdown-btn').forEach(button => {
        button.addEventListener('click', (e) => {
            e.stopPropagation();
            const currentDropdown = button.closest('.dropdown');
            
            // Close all other dropdowns
            document.querySelectorAll('.dropdown').forEach(dropdown => {
                if (dropdown !== currentDropdown) {
                    dropdown.classList.remove('active');
                }
            });
            
            // Toggle current dropdown
            currentDropdown.classList.toggle('active');
        });
    });

    // Close dropdown when clicking outside
    document.addEventListener('click', () => {
        document.querySelectorAll('.dropdown').forEach(dropdown => {
            dropdown.classList.remove('active');
        });
    });

    // Handle delete button clicks
    document.querySelectorAll('.delete-btn').forEach(btn => {
        btn.addEventListener('click', (e) => {
            e.preventDefault();
            e.stopPropagation();
            const habitId = btn.getAttribute('data-habit-id'); // Use habit ID instead of name
            const habitName = btn.getAttribute('data-habit'); // For confirmation message
            if (confirm(`Are you sure you want to delete "${habitName}"?`)) {
                fetch(`/habits/delete/${habitId}`, { // Send ID in the DELETE request
                    method: 'DELETE',
                })
                .then(response => {
                    if (!response.ok) {
                        throw new Error('Network response was not ok');
                    }
                    // Remove the habit item from the DOM
                    btn.closest('.habit-item').remove();
                })
                .catch(error => {
                    console.error('Error:', error);
                    alert('Failed to delete habit');
                });
            }
        });
    });
});