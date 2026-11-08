package habitTracker.KPI.deploy;

import java.util.List;

/** How per-device values for one day become the KPI's value: sum | max | avg | latest. Unknown rule = sum. */
public final class DeviceCombiner {
    private DeviceCombiner() {}

    /** @param values ordered oldest to newest by recordedAt (so "latest" is the last element). Null when empty. */
    public static Double combine(String rule, List<Double> values) {
        if (values == null || values.isEmpty()) return null;
        String r = rule == null ? "sum" : rule.toLowerCase();
        return switch (r) {
            case "max" -> values.stream().mapToDouble(Double::doubleValue).max().getAsDouble();
            case "avg" -> values.stream().mapToDouble(Double::doubleValue).average().getAsDouble();
            case "latest" -> values.get(values.size() - 1);
            default -> values.stream().mapToDouble(Double::doubleValue).sum();
        };
    }
}
