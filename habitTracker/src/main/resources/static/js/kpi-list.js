// KPI List JavaScript
document.addEventListener('DOMContentLoaded', function() {
    initializeModal();
    setTodayAsDefault();
});

function initializeModal() {
    const modal = document.getElementById('addDataModal');
    const closeBtn = document.querySelector('.close');
    const form = document.getElementById('addDataForm');

    // Close modal when clicking X
    closeBtn.addEventListener('click', closeModal);

    // Close modal when clicking outside
    window.addEventListener('click', function(event) {
        if (event.target === modal) {
            closeModal();
        }
    });

    // Handle form submission
    form.addEventListener('submit', handleFormSubmit);
}

function addData(kpiName) {
    const modal = document.getElementById('addDataModal');
    const kpiNameInput = document.getElementById('kpiName');
    
    kpiNameInput.value = kpiName;
    modal.style.display = 'block';
}

function closeModal() {
    const modal = document.getElementById('addDataModal');
    const form = document.getElementById('addDataForm');
    
    modal.style.display = 'none';
    form.reset();
}

function setTodayAsDefault() {
    const dateInput = document.getElementById('dataDate');
    if (dateInput) {
        const today = new Date().toISOString().split('T')[0];
        dateInput.value = today;
    }
}

async function handleFormSubmit(event) {
    event.preventDefault();
    
    const form = event.target;
    const formData = new FormData(form);
    const kpiName = formData.get('kpiName');
    const date = formData.get('date');
    const value = formData.get('value');

    try {
        const response = await fetch(`/kpis/${encodeURIComponent(kpiName)}/data`, {
            method: 'POST',
            headers: {
                'Content-Type': 'application/x-www-form-urlencoded',
            },
            body: new URLSearchParams({
                date: date,
                value: value
            })
        });

        const result = await response.text();
        
        if (response.ok) {
            showMessage(result, 'success');
            closeModal();
        } else {
            showMessage(result, 'error');
        }
    } catch (error) {
        console.error('Error adding KPI data:', error);
        showMessage('Failed to add data. Please try again.', 'error');
    }
}

function viewData(kpiName) {
    // Redirect to dashboard with specific KPI
    window.location.href = `/kpis/dashboard?focus=${encodeURIComponent(kpiName)}`;
}

async function deleteKPI(kpiName) {
    if (!confirm(`Are you sure you want to delete the KPI "${kpiName}"? This will also delete all associated data.`)) {
        return;
    }

    try {
        const response = await fetch(`/kpis/${encodeURIComponent(kpiName)}`, {
            method: 'DELETE'
        });

        const result = await response.text();
        
        if (response.ok) {
            showMessage(result, 'success');
            // Reload page after short delay
            setTimeout(() => {
                window.location.reload();
            }, 1500);
        } else {
            showMessage(result, 'error');
        }
    } catch (error) {
        console.error('Error deleting KPI:', error);
        showMessage('Failed to delete KPI. Please try again.', 'error');
    }
}

function showMessage(message, type) {
    // Remove existing alerts
    const existingAlerts = document.querySelectorAll('.alert');
    existingAlerts.forEach(alert => alert.remove());

    // Create new alert
    const alert = document.createElement('div');
    alert.className = `alert alert-${type === 'success' ? 'success' : 'error'}`;
    alert.innerHTML = `<span>${message}</span>`;

    // Insert at the top of main content
    const mainContent = document.querySelector('.main-content');
    const firstChild = mainContent.firstChild;
    mainContent.insertBefore(alert, firstChild);

    // Auto-remove after 5 seconds
    setTimeout(() => {
        if (alert.parentNode) {
            alert.remove();
        }
    }, 5000);
}
