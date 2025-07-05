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

function updateTable(data) {
    console.log("Data received from server:", data);
    const tbody = document.querySelector('.habit-table tbody');
    tbody.innerHTML = ''; // Clear existing rows

    data.forEach(entry => {
        const row = document.createElement('tr');

        // Add date cell
        const dateCell = document.createElement('td');
        dateCell.className = 'date-cell';
        dateCell.textContent = entry.date;
        row.appendChild(dateCell);

        // Add habit cells
        for (const [key, value] of Object.entries(entry.habits)) {
            const habitCell = document.createElement('td');
            habitCell.className = 'habit-cell';

            const checkbox = document.createElement('input');
            checkbox.type = 'checkbox';
            checkbox.checked = value;
            checkbox.setAttribute('data-date', entry.date);

            // Improved regex: allow negative numbers for value
            const match = key.match(/Pair\(key=(.*), value=(-?\d+)\)/);
            if (match) {
                checkbox.setAttribute('data-habit-name', match[1]);
                checkbox.setAttribute('data-habit-id', match[2]);
                
                // Check habit status and apply styling
                const habitKey = key;
                const habitStatus = entry.habitStatuses && entry.habitStatuses[habitKey];
                
                if (habitStatus === 'INACTIVE') {
                    checkbox.classList.add('inactive-habit');
                    checkbox.disabled = true;
                    checkbox.setAttribute('data-habit-status', 'INACTIVE');
                } else {
                    checkbox.setAttribute('data-habit-status', habitStatus || 'ACTIVE_INCOMPLETE');
                }
            } else {
                // fallback if format is unexpected
                checkbox.setAttribute('data-habit-name', key);
                checkbox.setAttribute('data-habit-id', '');
                console.warn('Unexpected key format:', key);
            }

            checkbox.onchange = () => updateHabitStatus(checkbox);

            habitCell.appendChild(checkbox);
            row.appendChild(habitCell);
        }

        tbody.appendChild(row);
    });
}