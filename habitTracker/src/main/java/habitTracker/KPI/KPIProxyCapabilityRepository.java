package habitTracker.KPI;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Optional;

public interface KPIProxyCapabilityRepository extends MongoRepository<KPIProxyCapability, String> {
    Optional<KPIProxyCapability> findFirstByKpiIdAndUserIdOrderByVersionDesc(String kpiId, String userId);
}
