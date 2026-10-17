package habitTracker.KPI;

import java.util.List;
import java.util.Map;

/** API view of a KPIProxyCapability. sourceCode is included so the user can audit what will run. */
public record CapabilityDTO(
        String id,
        String kpiId,
        int version,
        CapabilityStatus status,
        String description,
        String proposal,
        CapabilityPlatform platform,
        Map<String, List<String>> declaredCapabilities,
        String sourceCode,
        int attempts,
        int maxAttempts,
        String failureReason,
        String lastResult) {

    static CapabilityDTO from(KPIProxyCapability c) {
        return new CapabilityDTO(c.getId(), c.getKpiId(), c.getVersion() == null ? 1 : c.getVersion(),
                c.getStatus(), c.getDescription(), c.getProposal(), c.getPlatform(), c.getDeclaredCapabilities(),
                c.getSourceCode(), c.getAttempts() == null ? 0 : c.getAttempts(),
                CapabilityAgentService.MAX_ATTEMPTS, c.getFailureReason(), c.getLastResult());
    }
}
