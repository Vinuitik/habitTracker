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
            // Remove active from all left items
            document.querySelectorAll('.left-habit-list .habit-item').forEach(li => li.classList.remove('active'));
            // Toggle active on clicked item
            const isActive = this.classList.contains('active');
            if (!isActive) {
                this.classList.add('active');
                // Also activate on right if exists
                const rightItem = document.querySelector(`.right-habit-list .habit-item[data-id="${this.getAttribute('data-id')}"]`);
                if (rightItem) rightItem.classList.add('active');
            } else {
                this.classList.remove('active');
                // Also deactivate on right if exists
                const rightItem = document.querySelector(`.right-habit-list .habit-item[data-id="${this.getAttribute('data-id')}"]`);
                if (rightItem) rightItem.classList.remove('active');
            }
        });
    });

    // Multi-select for right column
    document.querySelectorAll('.right-habit-list .habit-item').forEach(item => {
        item.addEventListener('click', function() {
            this.classList.toggle('active');
            // If this habit is now unselected and is also selected on the left, unselect it on the left
            if (!this.classList.contains('active')) {
                const leftItem = document.querySelector(`.left-habit-list .habit-item[data-id="${this.getAttribute('data-id')}"]`);
                if (leftItem && leftItem.classList.contains('active')) {
                    leftItem.classList.remove('active');
                }
            }
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

// Helper to get selected habit IDs and their streaks
function getSelectedHabitsInfo() {
    const left = document.querySelector('.left-habit-list .habit-item.active');
    const right = Array.from(document.querySelectorAll('.right-habit-list .habit-item.active'));
    if (!left || right.length === 0) return null;
    const mainId = Number(left.getAttribute('data-id'));
    const mainStreak = Number(left.getAttribute('data-streak'));
    const subIds = right.map(item => Number(item.getAttribute('data-id')));
    const subStreaks = right.map(item => Number(item.getAttribute('data-streak')));
    return {
        mainId,
        subIds,
        allStreaks: [mainStreak, ...subStreaks]
    };
}

// Call this function to add a rule
function saveRule() {
    const habitsInfo = getSelectedHabitsInfo();
    if (!habitsInfo) {
        alert("Please select one main habit and at least one sub habit.");
        return;
    }
    // Get frequency from result box or your logic
    const frequency = Number(document.getElementById('result-frequency').textContent);
    // Get the largest streak
    const streak = Math.max(...habitsInfo.allStreaks);

    const payload = {
        mainId: habitsInfo.mainId,
        subIds: habitsInfo.subIds,
        frequency,
        streak
    };

    console.log('Saving rule with payload:', JSON.stringify(payload));

    fetch('/habits/addRule', {
        method: 'POST',
        headers: {
            'Content-Type': 'application/json'
        },
        body: JSON.stringify(payload)
    })
    .then(res => res.ok ? res.text() : Promise.reject(res.statusText))
    .then(() => {
         window.location.href = '/habits/list';
    })
    .catch(err => alert('Error: ' + err));
}

// Attach saveRule to the Save Rule button
document.addEventListener('DOMContentLoaded', function() {
    const saveBtn = document.querySelector('.save-rule-btn');
    if (saveBtn) {
        saveBtn.addEventListener('click', saveRule);
    }
});