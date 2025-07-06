function updateHabitStatus(checkbox) {
    // Don't allow updates for inactive habits
    const habitStatus = checkbox.getAttribute('data-habit-status');
    if (habitStatus === 'INACTIVE') {
        checkbox.checked = false; // Force uncheck
        return;
    }

    const habitId = checkbox.getAttribute('data-habit-id');
    const date = checkbox.getAttribute('data-date');
    const completed = checkbox.checked;

    // Create FormData or URLSearchParams object
    const formData = new URLSearchParams();
    formData.append('completed', completed);
    formData.append('date', date);

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

function fetchUpdatedTable() {
    const startDate = document.getElementById('start-date').value;
    const endDate = document.getElementById('end-date').value;

    if (!startDate || !endDate) {
        alert('Please select both start and end dates.');
        return;
    }

    fetch(`/habits/tableAsync?startDate=${startDate}&endDate=${endDate}`, {
        method: 'GET',
        headers: {
            'Accept': 'application/json'
        }
    })
    .then(response => {
        if (!response.ok) {
            throw new Error('Failed to fetch updated table data');
        }
        return response.json();
    })
    .then(data => {
        updateTable(data);
    })
    .catch(error => {
        console.error('Error:', error);
        alert('Failed to update the table. Please try again.');
    });
}

// Store the original habit order from table headers
function getHabitOrderFromHeaders() {
    const headers = document.querySelectorAll('.habit-table thead th');
    const habitOrder = [];
    
    // Skip the first header (Date column)
    for (let i = 1; i < headers.length; i++) {
        habitOrder.push(headers[i].textContent.trim());
    }
    
    return habitOrder;
}

// Helper function to find habit key by name in the habits object
function findHabitKeyByName(habits, habitName) {
    for (const key of Object.keys(habits)) {
        const match = key.match(/Pair\(key=(.*), value=(-?\d+)\)/);
        if (match && match[1] === habitName) {
            return key;
        }
    }
    return null;
}

function updateTable(data) {
    console.log("Data received from server:", data);
    const tbody = document.querySelector('.habit-table tbody');
    tbody.innerHTML = ''; // Clear existing rows
    
    // Get the habit order from table headers to maintain consistency
    const habitOrder = getHabitOrderFromHeaders();

    data.forEach(entry => {
        const row = document.createElement('tr');

        // Add date cell
        const dateCell = document.createElement('td');
        dateCell.className = 'date-cell';
        dateCell.textContent = entry.date;
        row.appendChild(dateCell);

        // Add habit cells in the same order as headers
        habitOrder.forEach(habitName => {
            const habitCell = document.createElement('td');
            habitCell.className = 'habit-cell';

            const checkbox = document.createElement('input');
            checkbox.type = 'checkbox';
            checkbox.setAttribute('data-date', entry.date);

            // Find the habit key that matches this habit name
            const habitKey = findHabitKeyByName(entry.habits, habitName);
            
            if (habitKey) {
                const value = entry.habits[habitKey];
                checkbox.checked = value;

                // Extract habit ID from the key
                const match = habitKey.match(/Pair\(key=(.*), value=(-?\d+)\)/);
                if (match) {
                    checkbox.setAttribute('data-habit-name', match[1]);
                    checkbox.setAttribute('data-habit-id', match[2]);
                    
                    // Check habit status and apply styling
                    const habitStatus = entry.habitStatuses && entry.habitStatuses[habitKey];
                    
                    if (habitStatus === 'INACTIVE') {
                        checkbox.classList.add('inactive-habit');
                        checkbox.disabled = true;
                        checkbox.setAttribute('data-habit-status', 'INACTIVE');
                    } else {
                        checkbox.setAttribute('data-habit-status', habitStatus || 'ACTIVE_INCOMPLETE');
                    }
                }
            } else {
                // Habit not found in data - might be a new habit or data issue
                checkbox.checked = false;
                checkbox.setAttribute('data-habit-name', habitName);
                checkbox.setAttribute('data-habit-id', '');
                checkbox.setAttribute('data-habit-status', 'ACTIVE_INCOMPLETE');
                console.warn('Habit not found in data:', habitName);
            }

            checkbox.onchange = () => updateHabitStatus(checkbox);

            habitCell.appendChild(checkbox);
            row.appendChild(habitCell);
        });

        tbody.appendChild(row);
    });
}