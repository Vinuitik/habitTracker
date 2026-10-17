package habitTracker.KPI;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

public interface KPIProxyCapabilityRepository extends MongoRepository<KPIProxyCapability, String> {
    Optional<KPIProxyCapability> findFirstByKpiIdAndUserIdOrderByVersionDesc(String kpiId, String userId);

    List<KPIProxyCapability> findByKpiIdAndUserId(String kpiId, String userId);

    // Ownership-scoped lookup: every agent endpoint resolves capabilities through this, never findById.
    Optional<KPIProxyCapability> findByIdAndUserIdAndKpiId(String id, String userId, String kpiId);
}
