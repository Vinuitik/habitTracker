function getCsrfToken() {
    const meta = document.querySelector('meta[name="_csrf"]');
    if (meta) return meta.getAttribute('content');
    const match = document.cookie.split('; ').find(r => r.startsWith('XSRF-TOKEN='));
    return match ? decodeURIComponent(match.split('=')[1]) : '';
}

document.addEventListener('DOMContentLoaded', function() {
    const frequencySelect = document.getElementById('frequency');
    const customContainer = document.getElementById('custom-frequency-container');
    const customInput = document.getElementById('customFrequency');

    frequencySelect.addEventListener('change', function() {
        if (this.value === 'CUSTOM') {
            customContainer.style.display = 'block';
        } else {
            customContainer.style.display = 'none';
        }
    });

    if (frequencySelect.value === 'CUSTOM') {
        customContainer.style.display = 'block';
    }

    document.querySelector('.habit-form').addEventListener('submit', function(e) {
        e.preventDefault();

        const freq = frequencySelect.value === 'CUSTOM'
            ? parseInt(customInput.value.trim(), 10)
            : parseInt(frequencySelect.value, 10);

        if (frequencySelect.value === 'CUSTOM' && !customInput.value.trim()) {
            alert('Please enter a custom frequency');
            return;
        }

        const habitId = parseInt(this.getAttribute('data-habit-id'), 10);
        const body = {
            id: habitId,
            name: document.getElementById('name').value,
            frequency: freq,
            startDate: document.getElementById('startDate').value || null,
            endDate: document.getElementById('endDate').value || null,
            active: document.getElementById('isActive').checked,
            defaultMade: document.getElementById('defaultMade').checked
        };

        fetch(`/habits/edit/${habitId}`, {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json',
                'X-XSRF-TOKEN': getCsrfToken()
            },
            body: JSON.stringify(body)
        })
        .then(response => {
            if (!response.ok) throw new Error('Failed to update habit');
            window.location.href = '/habits/list';
        })
        .catch(error => {
            console.error('Error:', error);
            alert('Failed to update habit. Please try again.');
        });
    });
});
