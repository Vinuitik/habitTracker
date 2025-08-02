// KPI Create Form JavaScript
document.addEventListener('DOMContentLoaded', function() {
    initializeFormValidation();
    setupFormInteractions();
});

function initializeFormValidation() {
    const form = document.querySelector('.kpi-form');
    const nameInput = document.getElementById('name');
    
    if (form) {
        form.addEventListener('submit', validateForm);
    }
    
    if (nameInput) {
        nameInput.addEventListener('input', validateKPIName);
        nameInput.addEventListener('blur', validateKPIName);
    }
}

function setupFormInteractions() {
    // Add visual feedback for radio buttons
    const radioInputs = document.querySelectorAll('input[type="radio"]');
    radioInputs.forEach(radio => {
        radio.addEventListener('change', function() {
            // Remove active class from all radio options
            const allOptions = document.querySelectorAll('.radio-option');
            allOptions.forEach(option => option.classList.remove('selected'));
            
            // Add active class to selected option
            if (this.checked) {
                this.closest('.radio-option').classList.add('selected');
            }
        });
    });

    // Add interaction feedback for checkboxes
    const checkboxInputs = document.querySelectorAll('input[type="checkbox"]');
    checkboxInputs.forEach(checkbox => {
        checkbox.addEventListener('change', function() {
            const option = this.closest('.checkbox-option');
            if (this.checked) {
                option.classList.add('selected');
            } else {
                option.classList.remove('selected');
            }
        });
    });
}

function validateForm(event) {
    const name = document.getElementById('name').value.trim();
    const description = document.getElementById('description').value.trim();
    const higherIsBetter = document.querySelector('input[name="higherIsBetter"]:checked');
    
    let isValid = true;
    let errorMessage = '';

    // Validate KPI name
    if (!name) {
        isValid = false;
        errorMessage = 'KPI name is required.';
    } else if (name.length < 2) {
        isValid = false;
        errorMessage = 'KPI name must be at least 2 characters long.';
    } else if (name.length > 50) {
        isValid = false;
        errorMessage = 'KPI name must be less than 50 characters.';
    } else if (!/^[a-zA-Z0-9\s\-_]+$/.test(name)) {
        isValid = false;
        errorMessage = 'KPI name can only contain letters, numbers, spaces, hyphens, and underscores.';
    }

    // Validate direction preference
    if (!higherIsBetter) {
        isValid = false;
        errorMessage = 'Please select whether higher or lower values are better.';
    }

    if (!isValid) {
        event.preventDefault();
        showError(errorMessage);
        return false;
    }

    // Show loading state
    const submitButton = event.target.querySelector('button[type="submit"]');
    if (submitButton) {
        submitButton.disabled = true;
        submitButton.textContent = 'Creating KPI...';
    }

    return true;
}

function validateKPIName() {
    const nameInput = document.getElementById('name');
    const name = nameInput.value.trim();
    
    // Remove existing validation messages
    const existingError = nameInput.parentNode.querySelector('.validation-error');
    if (existingError) {
        existingError.remove();
    }
    
    nameInput.classList.remove('error');

    if (name && name.length > 0) {
        if (name.length < 2) {
            showFieldError(nameInput, 'Name must be at least 2 characters long.');
        } else if (name.length > 50) {
            showFieldError(nameInput, 'Name must be less than 50 characters.');
        } else if (!/^[a-zA-Z0-9\s\-_]+$/.test(name)) {
            showFieldError(nameInput, 'Name can only contain letters, numbers, spaces, hyphens, and underscores.');
        }
    }
}

function showFieldError(input, message) {
    input.classList.add('error');
    
    const errorElement = document.createElement('span');
    errorElement.className = 'validation-error';
    errorElement.textContent = message;
    errorElement.style.color = '#dc3545';
    errorElement.style.fontSize = '0.85rem';
    errorElement.style.marginTop = '0.25rem';
    errorElement.style.display = 'block';
    
    input.parentNode.appendChild(errorElement);
}

function showError(message) {
    // Remove existing alerts
    const existingAlerts = document.querySelectorAll('.alert');
    existingAlerts.forEach(alert => alert.remove());

    // Create new alert
    const alert = document.createElement('div');
    alert.className = 'alert alert-error';
    alert.innerHTML = `<span>${message}</span>`;

    // Insert at the top of main content
    const mainContent = document.querySelector('.main-content');
    const pageHeader = mainContent.querySelector('.page-header');
    if (pageHeader) {
        pageHeader.parentNode.insertBefore(alert, pageHeader.nextSibling);
    } else {
        mainContent.insertBefore(alert, mainContent.firstChild);
    }

    // Scroll to top to show error
    window.scrollTo({ top: 0, behavior: 'smooth' });

    // Auto-remove after 5 seconds
    setTimeout(() => {
        if (alert.parentNode) {
            alert.remove();
        }
    }, 5000);
}

// Add CSS for validation error state
const style = document.createElement('style');
style.textContent = `
    .form-input.error,
    .form-textarea.error {
        border-color: #dc3545;
        box-shadow: 0 0 0 3px rgba(220, 53, 69, 0.1);
    }
    
    .radio-option.selected label {
        border-color: #2eaadc;
        background-color: #f8fbfd;
    }
    
    .checkbox-option.selected {
        background-color: #f8fbfd;
        border-color: #2eaadc;
    }
    
    .validation-error {
        animation: fadeIn 0.3s ease-in-out;
    }
    
    @keyframes fadeIn {
        from { opacity: 0; transform: translateY(-5px); }
        to { opacity: 1; transform: translateY(0); }
    }
`;
document.head.appendChild(style);
