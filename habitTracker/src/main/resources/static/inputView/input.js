function updateHabitStatus(checkbox) {
    const habitId = checkbox.getAttribute('data-habit-id');
    const completed = checkbox.checked;

    // Create FormData or URLSearchParams object
    const formData = new URLSearchParams();
    formData.append('completed', completed);

    fetch(`/habits/update/${habitId}`, {
        method: 'POST',
        headers: {
            'Content-Type': 'application/x-www-form-urlencoded', // Change Content-Type
        },
        body: formData // Send as form data
    })
    .then(response => {
        if (!response.ok) {
            checkbox.checked = !completed;
            throw new Error('Failed to update habit status');
        }
        return response.text(); // Changed from json() since your endpoint returns a String
    })
    .then(data => console.log('Success:', data))
    .catch(error => {
        console.error('Error:', error);
        alert('Failed to update habit status. Please try again.');
    });
}