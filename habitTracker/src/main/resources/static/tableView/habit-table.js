function updateHabitStatus(checkbox) {
    const date = checkbox.getAttribute('data-date');
    const habit = checkbox.getAttribute('data-habit');
    const checked = checkbox.checked;

    fetch('/habits/update', {
        method: 'POST',
        headers: {
            'Content-Type': 'application/json',
        },
        body: JSON.stringify({
            date: date,
            name: habit,
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
    .catch(error => {
        console.error('Error:', error);
        alert('Failed to update habit status');
    });
}