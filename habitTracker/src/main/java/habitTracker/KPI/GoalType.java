package habitTracker.KPI;

// How a KPI is judged. HIGHER/LOWER = open-ended direction (legacy higherIsBetter);
// TARGET = reach KPI.target.value by KPI.target.deadline (see KPITargetStatus).
public enum GoalType {
    TARGET, HIGHER, LOWER;

    // Pre-goalType documents: derive from higherIsBetter (null treated as false, matching
    // the long-standing Boolean.TRUE.equals(...) reads elsewhere).
    public static GoalType fromHigherIsBetter(Boolean higherIsBetter) {
        return Boolean.TRUE.equals(higherIsBetter) ? HIGHER : LOWER;
    }
}
