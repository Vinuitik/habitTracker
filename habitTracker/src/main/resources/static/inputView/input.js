// Fetch and display streaks on page load
document.addEventListener('DOMContentLoaded', () => {

    const habitCheckboxes = document.querySelectorAll('.habit-checkbox[data-habit-id]');
    const habitIds = Array.from(habitCheckboxes).map(cb => cb.getAttribute('data-habit-id'));
    fetch('/habits/streaks', {
        method: 'POST',
        headers: {
            'Content-Type': 'application/json'
        },
        body: JSON.stringify(habitIds)
    }).then(res => res.json())
        .then(streaks => {
            console.log('Fetched streaks:', streaks);
            // streaks should be an array of { habitId, streak }
            streaks.forEach(({ key, value }) => {
                const habitId = String(key);
                const streak = value;
                const streakSpan = document.querySelector(
                    `.habit-streak[data-habit-id="${habitId}"]`
                );
                const checkbox = document.querySelector(
                    `.habit-checkbox[data-habit-id="${habitId}"]`
                );
                if (streakSpan && checkbox) {
                    streakSpan.textContent =
                        (checkbox.checked ? streak + 1 : streak) + ' day streak';
                    streakSpan.dataset.baseStreak = streak;
                }
                    });
        });
});

function updateHabitStatus(checkbox) {
    const habitId = checkbox.getAttribute('data-habit-id');
    const completed = checkbox.checked;

    // Create FormData or URLSearchParams object
    const formData = new URLSearchParams();
    formData.append('completed', completed);

    fetch(`/habits/update/${habitId}`, {
        method: 'POST',
        headers: {
            'Content-Type': 'application/x-www-form-urlencoded',
        },
        body: formData
    })
    .then(response => {
        if (!response.ok) {
            checkbox.checked = !completed;
            throw new Error('Failed to update habit status');
        }
        return response.text();
    })
    .then(data => {
        // Update streak in DOM
        const streakSpan = document.querySelector(
            `.habit-streak[data-habit-id="${habitId}"]`
        );
        if (streakSpan) {
            const baseStreak = parseInt(streakSpan.dataset.baseStreak || "0", 10);
            streakSpan.textContent = 
                (completed ? baseStreak + 1 : baseStreak) + ' day streak';
        }
        console.log('Success:', data);
    })
    .catch(error => {
        console.error('Error:', error);
        alert('Failed to update habit status. Please try again.');
    });
}