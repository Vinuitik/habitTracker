// Enable/disable custom frequency input
document.addEventListener('DOMContentLoaded', function() {
    document.querySelectorAll('input[name="freq"]').forEach(radio => {
        radio.addEventListener('change', function() {
            document.getElementById('custom-freq').disabled = this.value !== 'custom';
        });
    });

    // Single select for left column
    document.querySelectorAll('.left-habit-list .habit-item').forEach(item => {
        item.addEventListener('click', function() {
            document.querySelectorAll('.left-habit-list .habit-item').forEach(li => li.classList.remove('active'));
            this.classList.add('active');
        });
    });

    // Multi-select for right column
    document.querySelectorAll('.right-habit-list .habit-item').forEach(item => {
        item.addEventListener('click', function() {
            this.classList.toggle('active');
        });
    });
});

// Utility function to calculate GCD
function gcd(a, b) {
    return b === 0 ? a : gcd(b, a % b);
}

// Utility function to calculate GCD of an array
function gcdArray(arr) {
    if (arr.length === 0) return 0;
    return arr.reduce((a, b) => gcd(a, b));
}

// Get selected frequencies from both columns
function getSelectedFrequencies() {
    const left = document.querySelector('.left-habit-list .habit-item.active');
    const right = Array.from(document.querySelectorAll('.right-habit-list .habit-item.active'));
    // Require at least one selected in both columns
    if (!left || right.length === 0) {
        return [];
    }
    let freqs = [];
    freqs.push(Number(left.getAttribute('data-frequency')));
    right.forEach(item => {
        freqs.push(Number(item.getAttribute('data-frequency')));
    });
    return freqs.filter(f => !isNaN(f));
}

// Update the frequency result box
function updateFrequencyResult() {
    const freqType = document.querySelector('input[name="freq"]:checked').value;
    const resultBox = document.getElementById('result-frequency');
    const freqs = getSelectedFrequencies();

    if (freqType === 'average') {
        if (freqs.length === 0) {
            resultBox.textContent = '-';
        } else {
            const avg = Math.round(freqs.reduce((a, b) => a + b, 0) / freqs.length);
            resultBox.textContent = avg;
        }
    } else if (freqType === 'gcd') {
        if (freqs.length === 0) {
            resultBox.textContent = '-';
        } else {
            resultBox.textContent = gcdArray(freqs);
        }
    } else if (freqType === 'custom') {
        const custom = document.getElementById('custom-freq').value;
        resultBox.textContent = custom ? custom : '-';
    }
}

// Add event listeners for selection and frequency changes
document.addEventListener('DOMContentLoaded', function() {
    // Update frequency result on habit selection
    document.querySelectorAll('.left-habit-list .habit-item').forEach(item => {
        item.addEventListener('click', updateFrequencyResult);
    });
    document.querySelectorAll('.right-habit-list .habit-item').forEach(item => {
        item.addEventListener('click', updateFrequencyResult);
    });

    // Update frequency result on frequency strategy change
    document.querySelectorAll('input[name="freq"]').forEach(radio => {
        radio.addEventListener('change', updateFrequencyResult);
    });

    // Update frequency result on custom input change
    document.getElementById('custom-freq').addEventListener('input', updateFrequencyResult);

    // Initial update
    updateFrequencyResult();
});