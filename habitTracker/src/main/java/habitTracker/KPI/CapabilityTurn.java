package habitTracker.KPI;

/** One message of the propose/refine conversation. role = "user" | "agent"; text is untrusted for "agent". */
public record CapabilityTurn(String role, String text) {
    static final String USER = "user";
    static final String AGENT = "agent";
}
