package habitTracker.KPI;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;

import java.time.LocalDateTime;
import java.util.Map;

@Document(collection = "kpis")
@CompoundIndex(def = "{'name': 1, 'userId': 1}", unique = true)
@AllArgsConstructor
@NoArgsConstructor
@Data
@Builder
public class KPI {
    @Id
    private String id;
    
    private String name; // unique per user (compound index with userId)
    
    private String description;
    
    private Boolean higherIsBetter; // true if higher values are better, false if lower is better
    
    private LocalDateTime createdAt;
    
    private LocalDateTime updatedAt;
    
    private Boolean active;

    @Indexed
    private String userId;

    // Opt-in: if true, a missed day is auto-filled with defaultValue by the daily cron
    // (KPIDefaultFillService) instead of being left blank. Off by default — not every KPI
    // wants a synthetic zero/whatever on days you forgot to log.
    private Boolean autoFillEnabled;

    private Double defaultValue;

    // Opt-in: how this KPI's value is filled in automatically, if at all. NONE (default) means
    // purely manual entry — zero behavior change for every KPI that existed before M1. The field
    // initializer plus @Builder.Default cover both "built without specifying it" and "read from a
    // pre-M1 Mongo doc that predates this field entirely" (Spring Data's converter leaves fields
    // absent from the source document at whatever the no-arg constructor set them to).
    @Builder.Default
    private ProxyType proxyType = ProxyType.NONE;

    // Provider-specific settings, e.g. {"boardId": "...", "listId": "..."} for TRELLO_CARD_COUNT.
    // Null/empty until a proxyType is configured; each ProxyProvider interprets its own keys.
    private Map<String, String> proxyConfig;

    // Fraction of resolved proxy values that should be flagged for manual confirmation rather
    // than trusted outright — not yet wired into any logic in M1 (schema only; the confirmation
    // flow itself is a later milestone).
    @Builder.Default
    private Double confirmSampleRate = 0.2;

    // M12 maintenance loop: health of this KPI's automatic proxy. ACTIVE (default) means the
    // nightly proxy-fill step keeps calling the provider as normal. NEEDS_REPAIR means
    // ProxyHealthService's circuit breaker tripped (consecutiveProxyFailures reached the
    // configured threshold, or a fetched value was flagged anomalous) and the proxy-fill step
    // skips this KPI until a human resets it (KPIService.resetProxyHealth). Same
    // pre-M12-document backward-compat pattern as proxyType: field initializer + @Builder.Default
    // so a doc that predates this field reads back as ACTIVE, not null.
    @Builder.Default
    private ProxyStatus proxyStatus = ProxyStatus.ACTIVE;

    // Consecutive Optional.empty()/exception results from this KPI's ProxyProvider.fetchValue,
    // tracked by ProxyHealthService. Reset to 0 on any successful (non-anomalous) fetch. Not
    // meaningful once proxyStatus is NEEDS_REPAIR from an anomaly trip, since that path doesn't
    // touch this counter.
    @Builder.Default
    private Integer consecutiveProxyFailures = 0;
}
