document.addEventListener('DOMContentLoaded', function() {

    const habitsList = document.querySelector('.habits-list');
    const activeBtn = document.getElementById('activeBtn');
    const inactiveBtn = document.getElementById('inactiveBtn');

    // Cache the original (active) habits HTML
    const activeHabitsHTML = habitsList ? habitsList.cloneNode(true) : '';
    let inactiveHabitsHTML = null;

    // Fetch and build inactive habits HTML
    fetch('/habits/inactive')
        .then(res => res.json())
        .then(data => {
            // Build HTML structure for inactive habits
            const ul = document.createElement('ul');
            ul.className = 'habits-list';
            data.forEach(habit => {
                const li = document.createElement('li');
                li.className = 'habit-item';
                li.innerHTML = `
                    <span class="habit-name">${habit.name}</span>
                    <div class="habit-actions">
                        <div class="dropdown">
                            <button class="dropdown-btn">
                                <i class="fas fa-ellipsis-v"></i>
                            </button>
                            <div class="dropdown-content">
                                <a href="/habits/edit/${habit.id}">Edit</a>
                                <a href="/habits/info/${habit.id}">Info</a>
                                <a href="#" class="delete-btn" data-habit="${habit.name}" data-habit-id="${habit.id}">Delete</a>
                            </div>
                        </div>
                    </div>
                `;
                ul.appendChild(li);
            });
            inactiveHabitsHTML = ul;
        });

    // Helper to swap the habits list
    function swapHabitsList(newList) {
        const currentHabitsList = document.querySelector('.habits-list');
        if (!currentHabitsList) return;
        currentHabitsList.innerHTML = newList.innerHTML;
        attachDropdownHandlers();
        attachDeleteHandlers();
    }

    // Attach dropdown handlers
    function attachDropdownHandlers() {
        document.querySelectorAll('.dropdown-btn').forEach(button => {
            button.addEventListener('click', (e) => {
                e.stopPropagation();
                const currentDropdown = button.closest('.dropdown');
                document.querySelectorAll('.dropdown').forEach(dropdown => {
                    if (dropdown !== currentDropdown) {
                        dropdown.classList.remove('active');
                    }
                });
                currentDropdown.classList.toggle('active');
            });
        });
        document.addEventListener('click', () => {
            document.querySelectorAll('.dropdown').forEach(dropdown => {
                dropdown.classList.remove('active');
            });
        });
    }

    // Attach delete handlers
    function attachDeleteHandlers() {
        document.querySelectorAll('.delete-btn').forEach(btn => {
            btn.addEventListener('click', (e) => {
                e.preventDefault();
                e.stopPropagation();
                const habitId = btn.getAttribute('data-habit-id');
                const habitName = btn.getAttribute('data-habit');
                if (confirm(`Are you sure you want to delete "${habitName}"?`)) {
                    fetch(`/habits/delete/${habitId}`, {
                        method: 'DELETE',
                    })
                    .then(response => {
                        if (!response.ok) throw new Error('Network response was not ok');
                        btn.closest('.habit-item').remove();
                    })
                    .catch(error => {
                        console.error('Error:', error);
                        alert('Failed to delete habit');
                    });
                }
            });
        });
    }

    // Toggle handlers
    activeBtn.addEventListener('click', function() {
        activeBtn.classList.add('active');
        inactiveBtn.classList.remove('active');
        if (activeHabitsHTML) swapHabitsList(activeHabitsHTML);
    });

    inactiveBtn.addEventListener('click', function() {
        inactiveBtn.classList.add('active');
        activeBtn.classList.remove('active');
        if (inactiveHabitsHTML) swapHabitsList(inactiveHabitsHTML);
    });

    // Initial handlers for active habits
    attachDropdownHandlers();
    attachDeleteHandlers();
});
