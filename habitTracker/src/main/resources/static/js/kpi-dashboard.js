// KPI Dashboard JavaScript
document.addEventListener('DOMContentLoaded', function() {
    initializeDashboard();
    initializeModal();
});

// Use guarded globals so reloading the script doesn't throw when variables are already declared
var currentPeriod = (typeof currentPeriod !== 'undefined') ? currentPeriod : 'weekly';
var charts = (typeof charts !== 'undefined') ? charts : {};

function initializeDashboard() {
    loadAllCharts();
    setupPeriodButtons();
    
    // Check for focus parameter in URL
    const urlParams = new URLSearchParams(window.location.search);
    const focusKPI = urlParams.get('focus');
    if (focusKPI) {
        scrollToKPI(focusKPI);
    }
}

function setupPeriodButtons() {
    document.getElementById('weeklyBtn').addEventListener('click', () => setPeriod('weekly'));
    document.getElementById('monthlyBtn').addEventListener('click', () => setPeriod('monthly'));
}

function setPeriod(period) {
    currentPeriod = period;
    
    // Update button states
    document.querySelectorAll('.period-btn').forEach(btn => {
        btn.classList.remove('active');
    });
    document.getElementById(period + 'Btn').classList.add('active');
    
    // Reload all charts
    loadAllCharts();
}

async function loadAllCharts() {
    const kpiCards = document.querySelectorAll('.kpi-chart-card');
    
    for (const card of kpiCards) {
        const kpiName = card.getAttribute('data-kpi-name');
        await loadKPIChart(kpiName);
    }
}

async function loadKPIChart(kpiName) {
    try {
        const response = await fetch(`/kpis/${encodeURIComponent(kpiName)}/data?period=${currentPeriod}`);
        const data = await response.json();

        console.log('=== LOADING CHART ===');
        console.log(data);
        
        if (response.ok) {
            renderChart(kpiName, data);
            updateKPIStats(kpiName, data);
        } else {
            showChartError(kpiName, 'Failed to load data');
        }
    } catch (error) {
        console.error(`Error loading chart for ${kpiName}:`, error);
        showChartError(kpiName, 'Network error');
    }
}

function renderChart(kpiName, data) {
    const canvas = document.getElementById(`chart-${kpiName}`);
    if (!canvas) return;
    
    // Destroy existing chart if it exists
    if (charts[kpiName]) {
        charts[kpiName].destroy();
    }
    
    const ctx = canvas.getContext('2d');
    
    // DEBUG: Log KPI name and data structure
    console.log('=== RENDERING CHART ===');
    console.log('KPI Name:', kpiName);
    console.log('Total data points:', data.length);
    console.log('Raw data:', data);
    
    // Prepare data
    const labels = data.map(d => formatDate(d.date));
    const values = data.map(d => d.value);
    const emaValues = data.map(d => d.exponentialMovingAverage);
    
    // DEBUG: Log extracted values
    console.log('Dates:', labels);
    console.log('Values:', values);
    console.log('EMA Values:', emaValues);
    
    // DEBUG: Log KPI properties (from first data point if available)
    if (data.length > 0 && data[0]) {
        console.log('KPI Properties from data[0]:', {
            name: data[0].name,
            higherIsBetter: data[0].higherIsBetter,
            description: data[0].description,
            active: data[0].active
        });
    }
    
    // Determine colors based on trend
    const borderColors = data.map(d => getTrendColor(d, data[0]));
    
    // Shift colors so that each segment uses the destination point's color
    // This means the line from point[i] to point[i+1] will have the color of point[i+1]
    const shiftedBorderColors = borderColors.length > 0 
        ? [...borderColors.slice(1), borderColors[borderColors.length - 1]]
        : borderColors;
    
    const backgroundColors = shiftedBorderColors.map(color => color + '20'); // Add transparency
    
    // DEBUG: Log color assignments
    console.log('Border colors (original):', borderColors);
    console.log('Border colors (shifted for segments):', shiftedBorderColors);
    console.log('===========================');
    
    const config = {
        type: 'line',
        data: {
            labels: labels,
            datasets: [
                {
                    label: 'Actual Value',
                    data: values,
                    borderColor: shiftedBorderColors,
                    backgroundColor: backgroundColors,
                    borderWidth: 3,
                    fill: true,
                    tension: 0.2,
                    pointRadius: 4,
                    pointHoverRadius: 6,
                    segment: {
                        borderColor: function(ctx) {
                            // Use the color of the destination point for each segment
                            return shiftedBorderColors[ctx.p1DataIndex] || shiftedBorderColors[0];
                        }
                    }
                },
                {
                    label: 'Trend (EMA)',
                    data: emaValues,
                    borderColor: '#6c757d',
                    backgroundColor: 'transparent',
                    borderWidth: 2,
                    borderDash: [5, 5],
                    fill: false,
                    tension: 0.2,
                    pointRadius: 2,
                    pointHoverRadius: 4
                }
            ]
        },
        options: {
            responsive: true,
            maintainAspectRatio: false,
            scales: {
                x: {
                    display: true,
                    title: {
                        display: true,
                        text: 'Date'
                    }
                },
                y: {
                    display: true,
                    title: {
                        display: true,
                        text: 'Value'
                    }
                }
            },
            plugins: {
                legend: {
                    display: true,
                    position: 'top',
                    labels: {
                        usePointStyle: true,
                        padding: 15
                    }
                },
                tooltip: {
                    mode: 'index',
                    intersect: false,
                    callbacks: {
                        label: function(context) {
                            const dataPoint = data[context.dataIndex];
                            if (context.datasetIndex === 0) {
                                return `Value: ${context.parsed.y.toFixed(2)} (${getTrendText(dataPoint)})`;
                            } else {
                                return `Trend: ${context.parsed.y.toFixed(2)}`;
                            }
                        }
                    }
                }
            },
            interaction: {
                mode: 'nearest',
                axis: 'x',
                intersect: false
            }
        }
    };
    
    charts[kpiName] = new Chart(ctx, config);
}

function updateKPIStats(kpiName, data) {
    if (data.length === 0) return;
    
    const latest = data[data.length - 1];
    const latestElement = document.getElementById(`latest-${kpiName}`);
    const emaElement = document.getElementById(`ema-${kpiName}`);
    const trendElement = document.getElementById(`trend-${kpiName}`);
    
    if (latestElement) {
        latestElement.textContent = latest.value ? latest.value.toFixed(2) : '-';
    }
    
    if (emaElement) {
        emaElement.textContent = latest.exponentialMovingAverage ? 
            latest.exponentialMovingAverage.toFixed(2) : '-';
    }
    
    if (trendElement) {
        const trendText = getTrendText(latest);
        trendElement.textContent = trendText;
        // Pass the KPI info (from data[0]) to getTrendClass
        trendElement.className = `trend-badge ${getTrendClass(latest, data[0])}`;
    }
}

function getTrendColor(dataPoint, kpi) {
    // DEBUG: Log color decision for this data point
    console.log('--- getTrendColor ---');
    console.log('Data point:', {
        date: dataPoint.date,
        value: dataPoint.value,
        exponentialMovingAverage: dataPoint.exponentialMovingAverage,
        colorIntensity: dataPoint.colorIntensity
    });
    console.log('KPI:', {
        name: kpi.name,
        higherIsBetter: kpi.higherIsBetter
    });
    
    if (!dataPoint.value || !dataPoint.exponentialMovingAverage) {
        console.log('-> Returning gray (no data)');
        return '#6c757d'; // Gray for no data
    }
    
    const diff = dataPoint.value - dataPoint.exponentialMovingAverage;
    const isPositiveTrend = diff > 0;
    
    console.log('Difference (value - EMA):', diff.toFixed(2));
    console.log('Is positive trend:', isPositiveTrend);
    console.log('KPI higherIsBetter:', kpi.higherIsBetter);
    
    // Determine if positive trend is good or bad based on KPI direction
    const isGoodTrend = (kpi.higherIsBetter && isPositiveTrend) || (!kpi.higherIsBetter && !isPositiveTrend);
    
    console.log('Is good trend:', isGoodTrend);
    
    // Color intensity based on change magnitude
    const intensity = getColorIntensity(dataPoint.colorIntensity);
    
    console.log('Color intensity:', intensity);
    
    let color;
    if (isGoodTrend) {
        // Green shades for good trends
        switch (intensity) {
            case 'high': color = '#28a745'; break;
            case 'medium': color = '#40c057'; break;
            case 'low': color = '#69db7c'; break;
            default: color = '#6c757d';
        }
    } else {
        // Red shades for bad trends
        switch (intensity) {
            case 'high': color = '#dc3545'; break;
            case 'medium': color = '#e74c3c'; break;
            case 'low': color = '#f8d7da'; break;
            default: color = '#6c757d';
        }
    }
    
    console.log('-> Returning color:', color);
    return color;
}

function getColorIntensity(intensity) {
    return intensity || 'low';
}

function getTrendText(dataPoint) {
    if (!dataPoint.value || !dataPoint.exponentialMovingAverage) {
        return 'No trend';
    }
    
    const diff = dataPoint.value - dataPoint.exponentialMovingAverage;
    const percentChange = Math.abs(diff / dataPoint.exponentialMovingAverage) * 100;
    
    if (percentChange < 1) {
        return 'Stable';
    }
    
    const direction = diff > 0 ? 'Up' : 'Down';
    return `${direction} ${percentChange.toFixed(1)}%`;
}

function getTrendClass(dataPoint, kpi) {
    if (!dataPoint.value || !dataPoint.exponentialMovingAverage) {
        return 'neutral';
    }
    
    const diff = dataPoint.value - dataPoint.exponentialMovingAverage;
    const percentChange = Math.abs(diff / dataPoint.exponentialMovingAverage) * 100;
    
    if (percentChange < 1) {
        return 'neutral';
    }
    
    const isPositiveTrend = diff > 0; // Value went up
    
    // Determine if this trend is good or bad based on KPI direction
    // Same logic as getTrendColor
    const isGoodTrend = (kpi.higherIsBetter && isPositiveTrend) || (!kpi.higherIsBetter && !isPositiveTrend);
    
    return isGoodTrend ? 'positive' : 'negative';
}

function formatDate(dateString) {
    const date = new Date(dateString);
    return date.toLocaleDateString('en-US', { 
        month: 'short', 
        day: 'numeric' 
    });
}

function showChartError(kpiName, message) {
    const canvas = document.getElementById(`chart-${kpiName}`);
    if (!canvas) return;
    
    const container = canvas.parentElement;
    container.innerHTML = `
        <div class="chart-loading">
            <p>⚠️ ${message}</p>
            <button class="btn btn-small btn-secondary" onclick="loadKPIChart('${kpiName}')">
                Retry
            </button>
        </div>
    `;
}

function refreshDashboard() {
    loadAllCharts();
}

function scrollToKPI(kpiName) {
    const card = document.querySelector(`[data-kpi-name="${kpiName}"]`);
    if (card) {
        setTimeout(() => {
            card.scrollIntoView({ behavior: 'smooth', block: 'center' });
            card.style.border = '2px solid #2eaadc';
            setTimeout(() => {
                card.style.border = '';
            }, 3000);
        }, 500);
    }
}

// Modal functionality
function initializeModal() {
    const modal = document.getElementById('addDataModal');
    const closeBtn = document.querySelector('.close');
    const form = document.getElementById('addDataForm');

    if (closeBtn) {
        closeBtn.addEventListener('click', closeModal);
    }

    window.addEventListener('click', function(event) {
        if (event.target === modal) {
            closeModal();
        }
    });

    if (form) {
        form.addEventListener('submit', handleFormSubmit);
    }
    
    setTodayAsDefault();
}

function addDataToKPI(kpiName) {
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
    setTodayAsDefault();
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
            closeModal();
            // Reload the specific chart
            await loadKPIChart(kpiName);
            showMessage('Data added successfully!', 'success');
        } else {
            showMessage(result, 'error');
        }
    } catch (error) {
        console.error('Error adding KPI data:', error);
        showMessage('Failed to add data. Please try again.', 'error');
    }
}

function showMessage(message, type) {
    // Simple notification - could be enhanced with a proper notification system
    const notification = document.createElement('div');
    notification.style.cssText = `
        position: fixed;
        top: 20px;
        right: 20px;
        padding: 1rem 1.5rem;
        border-radius: 6px;
        color: white;
        font-weight: 600;
        z-index: 3000;
        animation: slideIn 0.3s ease-out;
        background-color: ${type === 'success' ? '#28a745' : '#dc3545'};
    `;
    notification.textContent = message;
    
    document.body.appendChild(notification);
    
    setTimeout(() => {
        notification.style.animation = 'slideOut 0.3s ease-in';
        setTimeout(() => {
            notification.remove();
        }, 300);
    }, 3000);
}

// Add animation styles
const style = document.createElement('style');
style.textContent = `
    @keyframes slideIn {
        from { transform: translateX(100%); opacity: 0; }
        to { transform: translateX(0); opacity: 1; }
    }
    
    @keyframes slideOut {
        from { transform: translateX(0); opacity: 1; }
        to { transform: translateX(100%); opacity: 0; }
    }
`;
document.head.appendChild(style);
