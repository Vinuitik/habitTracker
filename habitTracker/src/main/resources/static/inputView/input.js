// Global counters for efficient habit positioning
let totalHabits = 0;
let checkedHabits = 0;

// Fetch and display streaks on page load
document.addEventListener('DOMContentLoaded', () => {

    const habitCheckboxes = document.querySelectorAll('.habit-checkbox[data-habit-id]');
    const habitIds = Array.from(habitCheckboxes).map(cb => cb.getAttribute('data-habit-id'));
    
    // Initialize counters
    totalHabits = habitCheckboxes.length;
    checkedHabits = Array.from(habitCheckboxes).filter(cb => cb.checked).length;
    
    // Organize habits on page load - unchecked at top, checked at bottom
    organizeHabitsOnLoad();
    
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
    const isDefaultMade = checkbox.getAttribute('data-default-made') === 'true';

    // Check if this is an unchecked negative habit (shame case)
    if (!completed && isDefaultMade) {
        // Find the habit item and apply shame animation
        const habitItem = checkbox.closest('.habit-item');
        if (habitItem) {
            removeHabitWithShameAnimation(habitItem, habitId, completed);
            return; // Don't proceed with normal flow
        }
    }

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
        
        // Move habit to appropriate position in the list
        moveHabitToPosition(checkbox, completed);
        
        console.log('Success:', data);
    })
    .catch(error => {
        console.error('Error:', error);
        alert('Failed to update habit status. Please try again.');
    });
}

function moveHabitToPosition(checkbox, completed) {
    // Find the habit item (li element) that contains this checkbox
    const habitItem = checkbox.closest('.habit-item');
    if (!habitItem) return;
    
    const habitList = document.querySelector('.habit-list');
    if (!habitList) return;
    
    if (completed) {
        // Move to bottom: append to the end of the list
        habitList.appendChild(habitItem);
        
        // Update counters
        checkedHabits++;
        
        // Add a subtle animation class for visual feedback
        habitItem.classList.add('habit-moving-down');
        setTimeout(() => {
            habitItem.classList.remove('habit-moving-down');
        }, 500);
    } else {
        // Move to top: calculate position using O(1) approach
        // Position = totalHabits - checkedHabits (after decrementing)
        checkedHabits--;
        const targetPosition = totalHabits - checkedHabits - 1; // -1 because we're counting from 0
        
        const allHabitItems = Array.from(habitList.querySelectorAll('.habit-item'));
        
        if (targetPosition <= 0) {
            // Insert at the beginning
            habitList.insertBefore(habitItem, habitList.firstChild);
        } else if (targetPosition >= allHabitItems.length - 1) {
            // This shouldn't happen with unchecking, but safety check
            habitList.appendChild(habitItem);
        } else {
            // Insert at the calculated position
            const referenceNode = allHabitItems[targetPosition];
            habitList.insertBefore(habitItem, referenceNode);
        }
        
        // Add a subtle animation class for visual feedback
        habitItem.classList.add('habit-moving-up');
        setTimeout(() => {
            habitItem.classList.remove('habit-moving-up');
        }, 500);
    }
}

function organizeHabitsOnLoad() {
    const habitList = document.querySelector('.habit-list');
    if (!habitList) return;
    
    const allHabitItems = Array.from(habitList.querySelectorAll('.habit-item'));
    const uncheckedHabits = [];
    const checkedHabits = [];
    
    // Separate habits into checked and unchecked arrays
    allHabitItems.forEach(habitItem => {
        const checkbox = habitItem.querySelector('.habit-checkbox');
        if (checkbox && checkbox.checked) {
            checkedHabits.push(habitItem);
        } else {
            uncheckedHabits.push(habitItem);
        }
    });
    
    // Clear the list and reorder: unchecked first, then checked
    habitList.innerHTML = '';
    
    // Add unchecked habits first (they stay at the top)
    uncheckedHabits.forEach(habitItem => {
        habitList.appendChild(habitItem);
    });
    
    // Add checked habits at the bottom
    checkedHabits.forEach(habitItem => {
        habitList.appendChild(habitItem);
    });
}

/**
 * Handles the shameful removal of negative habits that have been failed
 * @param {HTMLElement} habitItem - The habit item to remove
 * @param {string} habitId - The ID of the habit
 * @param {boolean} completed - The completion status (should be false for shame removal)
 */
function removeHabitWithShameAnimation(habitItem, habitId, completed) {
    // Disable the checkbox to prevent further interaction
    const checkbox = habitItem.querySelector('.habit-checkbox');
    if (checkbox) {
        checkbox.disabled = true;
    }

    // Apply shame styling first
    habitItem.classList.add('habit-shame-removal');

    // Update the database first
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
            // If update fails, restore the checkbox and remove shame styling
            if (checkbox) {
                checkbox.disabled = false;
                checkbox.checked = true; // Restore checked state
            }
            habitItem.classList.remove('habit-shame-removal');
            throw new Error('Failed to update habit status');
        }
        return response.text();
    })
    .then(data => {
        console.log('Negative habit marked as failed:', data);
        
        // After shake animation, start slide out
        setTimeout(() => {
            habitItem.classList.add('habit-slide-out');
            
            // Remove from DOM after slide out animation completes
            setTimeout(() => {
                if (habitItem.parentNode) {
                    habitItem.parentNode.removeChild(habitItem);
                    // Update counters
                    totalHabits--;
                    console.log('Negative habit removed from view due to failure');
                }
            }, 1200); // Match the slide-out animation duration
            
        }, 400); // Wait for shake animation to complete
    })
    .catch(error => {
        console.error('Error updating negative habit:', error);
        alert('Failed to update habit status. Please try again.');
    });
}