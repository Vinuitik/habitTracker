package habitTracker.KPI;

/**
 * How a KPI's value gets filled in automatically, if at all. NONE means purely manual entry
 * (the default, and the only behavior that existed before M1). MANUAL_PROMPT means the system
 * should ask the user rather than fetch a value itself — it is deliberately excluded from the
 * nightly proxy-fill step (habitTracker.updater.KPIProxyFillService) for that reason.
 */
public enum ProxyType {
    NONE,
    TRELLO_CARD_COUNT,
    MANUAL_PROMPT
}
