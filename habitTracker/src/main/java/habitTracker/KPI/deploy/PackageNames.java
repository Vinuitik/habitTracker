package habitTracker.KPI.deploy;

/** Plain-language program names for the UI; unknown ids are shown as they are. */
final class PackageNames {
    private PackageNames() {}

    static String friendly(String manager, String id) {
        if (id != null && id.toLowerCase(java.util.Locale.ROOT).contains("activitywatch")) return "ActivityWatch";
        return id;
    }
}
