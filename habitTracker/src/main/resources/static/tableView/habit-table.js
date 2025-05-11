function updateHabitStatus(checkbox) {
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
            checkbox.checked = value; // The value is the boolean indicating completion
            checkbox.setAttribute('data-date', entry.date);
            checkbox.setAttribute('data-habit-name', key.match(/key=(.*?),/)[1]); // Extract habit name from Pair
            checkbox.setAttribute('data-habit-id', key.match(/value=(\d+)/)[1]); // Extract habit ID from Pair
            checkbox.onchange = () => updateHabitStatus(checkbox);

            habitCell.appendChild(checkbox);
            row.appendChild(habitCell);
        }

        tbody.appendChild(row);
    });
}