package habitTracker.KPI;

/**
 * Lifecycle of a stored KPIProxyCapability. Only ACTIVE capabilities are executed (M7).
 *
 * M8 flow: PROPOSED (awaiting user approval) -> PENDING_TEST (code generated / being dry-run)
 * -> ACTIVE (test gate passed) or TEST_FAILED (reason stored, user may regenerate). REJECTED is a
 * proposal the user declined or that a newer proposal superseded. NEEDS_REPAIR marks the old
 * version while a repair version is being built; DISABLED is a superseded ex-ACTIVE version.
 *
 * S1 (kpi-sensors.md "Test pipeline" 5-6), platform-agnostic: a first build that passes its test gate does not go
 * ACTIVE directly. It stops at AWAITING_CONFIRM (the user sees yesterday's value + evidence and answers), then
 * SHADOW (kpi.shadow.days of readings stored as unconfirmed), then ACTIVE. Repairs of an already live capability
 * skip the confirm step. SHADOW capabilities are executed like ACTIVE ones.
 */
public enum CapabilityStatus {
    PROPOSED,
    PENDING_TEST,
    TEST_FAILED,
    ACTIVE,
    REJECTED,
    DISABLED,
    NEEDS_REPAIR,
    AWAITING_CONFIRM,
    SHADOW
}
