function getCsrfToken() {
    const meta = document.querySelector('meta[name="_csrf"]');
    if (meta) return meta.getAttribute('content');
    const match = document.cookie.split('; ').find(r => r.startsWith('XSRF-TOKEN='));
    return match ? decodeURIComponent(match.split('=')[1]) : '';
}

// KPI Dashboard JavaScript
document.addEventListener('DOMContentLoaded', function() {
    initializeDashboard();
    initializeModal();
});

var currentPeriod     = (typeof currentPeriod     !== 'undefined') ? currentPeriod     : 'weekly';
var customRangeStart  = (typeof customRangeStart  !== 'undefined') ? customRangeStart  : null;
var customRangeEnd    = (typeof customRangeEnd    !== 'undefined') ? customRangeEnd    : null;
var charts            = (typeof charts            !== 'undefined') ? charts            : {};
var kpiMeta           = (typeof kpiMeta           !== 'undefined') ? kpiMeta           : {}; // name → {higherIsBetter, goalType, target, direction}

var TARGET_LINE_COLOR = '#6c86f5'; // --color-accent (tokens.css); Chart.js needs a literal colour

// TARGET KPIs: targetStatus.direction ("up" = higher better) wins over higherIsBetter when known.
function resolveHigherIsBetter(kpiName, data) {
    const meta = kpiMeta[kpiName];
    if (meta) {
        if (meta.goalType === 'TARGET' && meta.direction) return meta.direction === 'up';
        if (meta.higherIsBetter != null) return meta.higherIsBetter;
    }
    return data[0] && data[0].higherIsBetter != null ? data[0].higherIsBetter : true;
}

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
    ['weekly', 'monthly', 'alltime', 'custom'].forEach(p =>
        document.getElementById(p + 'Btn').addEventListener('click', () => setPeriod(p))
    );
}

function setPeriod(period) {
    currentPeriod = period;
    if (period !== 'custom') loadAllCharts();
}

function setCustomRange(start, end) {
    customRangeStart = start;
    customRangeEnd   = end;
    loadAllCharts();
}

async function loadAllCharts() {
    const kpiCards = document.querySelectorAll('.kpi-chart-card');
    await Promise.all([...kpiCards].map(card => loadKPIChart(card.getAttribute('data-kpi-name'))));
}

async function loadKPIChart(kpiName) {
    try {
        let url = `/api/kpis/${encodeURIComponent(kpiName)}/data?period=${currentPeriod}`;
        if (currentPeriod === 'custom' && customRangeStart && customRangeEnd) {
            url += `&startDate=${customRangeStart}&endDate=${customRangeEnd}`;
        }
        const response = await fetch(url);
        const data = await response.json();

        if (response.ok) {
            const card0 = document.querySelector(`.kpi-chart-card[data-kpi-name="${CSS.escape(kpiName)}"]`);
            const changes = await loadDefinitionChanges(card0 && card0.getAttribute('data-kpi-id'));
            renderChart(kpiName, data, changes);
            updateKPIStats(kpiName, data);
            const card = document.querySelector(`.kpi-chart-card[data-kpi-name="${CSS.escape(kpiName)}"]`);
            if (card && window.KpiDevices) KpiDevices.update(card, card.getAttribute('data-kpi-id'), data);
        } else {
            showChartError(kpiName, 'Failed to load data');
        }
    } catch (error) {
        console.error(`Error loading chart for ${kpiName}:`, error);
        showChartError(kpiName, 'Network error');
    }
}

// Days this KPI's definition changed (redefine with a different unit or source): [{date}]. Best effort.
async function loadDefinitionChanges(kpiId) {
    if (!kpiId) return [];
    try {
        const res = await fetch(ENV.ENDPOINTS.KPI_DEFINITION_CHANGES(kpiId), { credentials: 'include' });
        return res.ok ? await res.json() : [];
    } catch (e) {
        return [];
    }
}

// Chart.js plugin: a dashed vertical line + "Definition changed" at the first point on/after each change date.
function definitionMarkers(data, changes) {
    const cfg = ENV.KPI_REDEFINE;
    const idxs = (changes || []).map(c => data.findIndex(d => String(d.date).slice(0, 10) >= c.date)).filter(i => i >= 0);
    return {
        id: 'definitionMarkers',
        afterDatasetsDraw(chart) {
            const { ctx, chartArea, scales } = chart;
            ctx.save();
            ctx.strokeStyle = cfg.MARKER_COLOR;
            ctx.fillStyle = cfg.MARKER_COLOR;
            ctx.setLineDash([4, 4]);
            ctx.lineWidth = 1;
            ctx.font = '12px sans-serif';
            idxs.forEach(i => {
                const x = scales.x.getPixelForValue(i);
                ctx.beginPath();
                ctx.moveTo(x, chartArea.top);
                ctx.lineTo(x, chartArea.bottom);
                ctx.stroke();
                ctx.setLineDash([]);
                ctx.textAlign = x > chartArea.right - 130 ? 'right' : 'left';
                ctx.fillText(cfg.CHANGED_LABEL, x + (ctx.textAlign === 'left' ? 4 : -4), chartArea.top + 12);
                ctx.setLineDash([4, 4]);
            });
            ctx.restore();
        }
    };
}

function renderChart(kpiName, data, changes) {
    const canvas = document.getElementById(`chart-${kpiName}`);
    if (!canvas) return;
    
    // Destroy existing chart if it exists
    if (charts[kpiName]) {
        charts[kpiName].destroy();
    }
    
    const ctx = canvas.getContext('2d');
    
    // Prepare data
    const labels = data.map(d => formatDate(d.date));
    const values = data.map(d => d.value);
    const emaValues = data.map(d => d.exponentialMovingAverage);
    
    // Determine colors based on trend
    const higherIsBetter = resolveHigherIsBetter(kpiName, data);
    const borderColors = data.map(d => getTrendColor(d, higherIsBetter));
    
    // Shift colors so that each segment uses the destination point's color
    // This means the line from point[i] to point[i+1] will have the color of point[i+1]
    const shiftedBorderColors = borderColors.length > 0 
        ? [...borderColors.slice(1), borderColors[borderColors.length - 1]]
        : borderColors;
    
    const backgroundColors = shiftedBorderColors.map(color => color + '20'); // Add transparency
    
    const config = {
        type: 'line',
        plugins: [definitionMarkers(data, changes)],
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
                    pointStyle: data.map(d => d.autoFilled ? 'rectRot' : 'circle'),
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
            ].concat(targetDataset(kpiName, labels.length))
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
                                const autoFilled = dataPoint.autoFilled ? ' — auto-filled' : '';
                                return `Value: ${context.parsed.y.toFixed(2)} (${getTrendText(dataPoint)})${autoFilled}`;
                            } else if (context.dataset.isTarget) {
                                return `Target: ${context.parsed.y.toFixed(2)}`;
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

// Dashed horizontal line at target.value (TARGET KPIs only): a constant extra dataset, no annotation plugin.
function targetDataset(kpiName, pointCount) {
    const meta = kpiMeta[kpiName];
    if (!meta || meta.goalType !== 'TARGET' || !meta.target || meta.target.value == null || pointCount === 0) return [];
    return [{
        label: 'Target',
        isTarget: true,
        data: new Array(pointCount).fill(meta.target.value),
        borderColor: TARGET_LINE_COLOR,
        backgroundColor: 'transparent',
        borderWidth: 2,
        borderDash: [8, 6],
        fill: false,
        tension: 0,
        pointRadius: 0,
        pointHoverRadius: 0
    }];
}

function updateKPIStats(kpiName, data) {
    if (data.length === 0) {
        const t = document.getElementById(`trend-${kpiName}`);
        if (t) t.textContent = 'No data';   // else the template's "Loading…" stays forever
        return;
    }
    
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
        const hib = resolveHigherIsBetter(kpiName, data);
        trendElement.className = `trend-badge ${getTrendClass(latest, hib)}`;
    }
}

function getTrendColor(dataPoint, higherIsBetter) {
    if (!dataPoint.value || !dataPoint.exponentialMovingAverage) {
        return '#6c757d';
    }

    const diff = dataPoint.value - dataPoint.exponentialMovingAverage;
    const percentChange = Math.abs(diff / dataPoint.exponentialMovingAverage) * 100;
    if (percentChange < 1) return '#6c757d';

    const isPositiveTrend = diff > 0;
    const isGoodTrend = (higherIsBetter && isPositiveTrend) || (!higherIsBetter && !isPositiveTrend);
    const intensity = getColorIntensity(dataPoint.colorIntensity);

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
            case 'medium': color = '#e05c5c'; break;
            case 'low': color = '#c44d4d'; break;
            default: color = '#6c757d';
        }
    }

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

function getTrendClass(dataPoint, higherIsBetter) {
    if (!dataPoint.value || !dataPoint.exponentialMovingAverage) {
        return 'neutral';
    }

    const diff = dataPoint.value - dataPoint.exponentialMovingAverage;
    const percentChange = Math.abs(diff / dataPoint.exponentialMovingAverage) * 100;
    if (percentChange < 1) return 'neutral';

    const isPositiveTrend = diff > 0;
    const isGoodTrend = (higherIsBetter && isPositiveTrend) || (!higherIsBetter && !isPositiveTrend);
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
            card.style.border = '2px solid var(--color-accent)';
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
    document.getElementById('kpiName').value = kpiName;
    document.getElementById('addDataModal').classList.add('open');
}

function closeModal() {
    document.getElementById('addDataModal').classList.remove('open');
    document.getElementById('addDataForm').reset();
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
        const response = await fetch(`/api/kpis/${encodeURIComponent(kpiName)}/data`, {
            method: 'POST',
            headers: {
                'Content-Type': 'application/x-www-form-urlencoded',
                'X-XSRF-TOKEN': getCsrfToken()
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
