function updateHabitStatus(checkbox) {
    const name = checkbox.getAttribute('data-habit');
    const checked = checkbox.checked;
    
    fetch('/habits/update', {
        method: 'POST',
        headers: {
            'Content-Type': 'application/json',
        },
        body: JSON.stringify({
            date: new Date().toISOString().split('T')[0],
            name: name,
            status: checked
        })
    })
    .then(response => {
        if (!response.ok) {
            checkbox.checked = !checked; // Revert on error
            throw new Error('Network response was not ok');
        }
        return response.json();
    })
    .then(data => console.log('Success:', data))
    .catch(error => {
        console.error('Error:', error);
        alert('Failed to update habit status. Please try again.');
    });
}