package habitTracker.KPI;

/**
 * Lifecycle of a stored KPIProxyCapability. Only ACTIVE capabilities are executed (M7).
 *
 * M8 flow: PROPOSED (awaiting user approval) -> PENDING_TEST (code generated / being dry-run)
 * -> ACTIVE (test gate passed) or TEST_FAILED (reason stored, user may regenerate). REJECTED is a
 * proposal the user declined or that a newer proposal superseded. NEEDS_REPAIR marks the old
 * version while a repair version is being built; DISABLED is a superseded ex-ACTIVE version.
 */
public enum CapabilityStatus {
    PROPOSED,
    PENDING_TEST,
    TEST_FAILED,
    ACTIVE,
    REJECTED,
    DISABLED,
    NEEDS_REPAIR
}
