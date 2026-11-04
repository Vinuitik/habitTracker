package habitTracker.KPI;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Plausibility guard for automatically produced readings (capability platforms, SHADOW and ACTIVE alike).
 * A hit means "do not store silently": the reading goes to the confirm-inbox instead, and the circuit breaker
 * is NOT tripped. Pure function, no I/O.
 */
public final class ReadingPlausibility {

    public static final int MIN_HISTORY = 5;
    public static final int HISTORY_WINDOW = 14;
    public static final double MEDIAN_FACTOR = 5.0;

    /** Units that can never be negative. Unknown or missing units are allowed to be negative. */
    private static final Set<String> NON_NEGATIVE_UNITS = Set.of(
            "min", "mins", "minute", "minutes", "h", "hr", "hrs", "hour", "hours", "s", "sec", "secs", "second", "seconds",
            "ms", "day", "days", "count", "counts", "item", "items", "line", "lines", "commit", "commits", "step", "steps",
            "page", "pages", "visit", "visits", "event", "events", "card", "cards", "task", "tasks", "kb", "mb", "gb",
            "km", "m", "mi", "kg", "lb", "kcal", "cal", "%");

    private ReadingPlausibility() {}

    /** Reason in plain language when the value looks wrong, empty when it is fine. history = recent trusted values. */
    public static Optional<String> check(double value, String unit, List<Double> history) {
        if (!Double.isFinite(value)) {
            return Optional.of("The script returned something that is not a valid number");
        }
        if (value < 0 && unit != null && NON_NEGATIVE_UNITS.contains(unit.strip().toLowerCase())) {
            return Optional.of("A negative value (" + CapabilityOutput.formatValue(value, unit) + ") is not possible for " + unit);
        }
        if (history != null && history.size() >= MIN_HISTORY) {
            double median = median(history);
            if (median > 0 && value > MEDIAN_FACTOR * median) {
                return Optional.of(CapabilityOutput.formatValue(value, unit) + " is more than " + (int) MEDIAN_FACTOR
                        + " times the usual " + CapabilityOutput.formatValue(Math.round(median * 100) / 100.0, unit)
                        + " (median of the last " + history.size() + " readings)");
            }
        }
        return Optional.empty();
    }

    static double median(List<Double> values) {
        List<Double> s = new ArrayList<>(values);
        s.sort(Double::compare);
        int n = s.size();
        return n % 2 == 1 ? s.get(n / 2) : (s.get(n / 2 - 1) + s.get(n / 2)) / 2.0;
    }
}
