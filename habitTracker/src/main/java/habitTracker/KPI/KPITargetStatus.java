package habitTracker.KPI;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * Pure progress math for a TARGET KPI. gapPct = |current-target| / |baseline-target| (0 once reached),
 * progress = 1-gapPct clamped to [0,1], expected = elapsed/(deadline-setAt) days clamped to [0,1].
 * State precedence: NO_DATA → REACHED (overshoot counts) → MISSED (past deadline) → AHEAD/BEHIND/ON_TRACK.
 */
public record KPITargetStatus(String direction, Double gapPct, Double progress, Double expected,
                              State state, Long daysLeft, Double current) {

    public static final double PACE_BAND = 0.10;
    private static final double EPS = 1e-9;

    public enum State { REACHED, MISSED, AHEAD, ON_TRACK, BEHIND, NO_DATA }

    public static KPITargetStatus of(KPITarget t, Double current, LocalDate today) {
        if (t == null || t.getValue() == null) return null;
        Long daysLeft = t.getDeadline() != null && today != null
                ? ChronoUnit.DAYS.between(today, t.getDeadline()) : null;
        Double expected = expected(t, today);
        Double baseline = t.getBaseline();
        double target = t.getValue();
        String direction = baseline == null ? null
                : target > baseline ? "up" : target < baseline ? "down" : null;

        if (current == null || baseline == null) {
            return new KPITargetStatus(direction, null, null, expected, State.NO_DATA, daysLeft, current);
        }

        boolean reached = "up".equals(direction) ? current >= target
                : "down".equals(direction) ? current <= target
                : true; // baseline == target: nothing to travel
        if (reached) {
            return new KPITargetStatus(direction, 0.0, 1.0, expected, State.REACHED, daysLeft, current);
        }

        double gapPct = Math.abs(current - target) / Math.abs(baseline - target);
        double progress = clamp(1 - gapPct);
        State state;
        if (today != null && t.getDeadline() != null && today.isAfter(t.getDeadline())) {
            state = State.MISSED;
        } else if (expected == null) {
            state = State.ON_TRACK;
        } else if (progress >= expected + PACE_BAND - EPS) {
            state = State.AHEAD;
        } else if (progress < expected - PACE_BAND - EPS) {
            state = State.BEHIND;
        } else {
            state = State.ON_TRACK;
        }
        return new KPITargetStatus(direction, gapPct, progress, expected, state, daysLeft, current);
    }

    private static Double expected(KPITarget t, LocalDate today) {
        if (t.getSetAt() == null || t.getDeadline() == null || today == null) return null;
        long total = ChronoUnit.DAYS.between(t.getSetAt(), t.getDeadline());
        if (total <= 0) return 1.0;
        return clamp((double) ChronoUnit.DAYS.between(t.getSetAt(), today) / total);
    }

    private static double clamp(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }
}
