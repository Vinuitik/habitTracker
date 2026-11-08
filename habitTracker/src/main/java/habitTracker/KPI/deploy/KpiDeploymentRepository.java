package habitTracker.KPI.deploy;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

public interface KpiDeploymentRepository extends MongoRepository<KpiDeployment, String> {
    Optional<KpiDeployment> findByKpiIdAndDeviceIdAndCapabilityVersion(String kpiId, String deviceId, Integer capabilityVersion);

    List<KpiDeployment> findByKpiIdAndCapabilityVersion(String kpiId, Integer capabilityVersion);

    List<KpiDeployment> findByKpiId(String kpiId);

    List<KpiDeployment> findByDeviceId(String deviceId);
}
