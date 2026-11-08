package habitTracker.KPI.deploy;

import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;

import java.util.List;
import java.util.Optional;

public interface KpiDeploymentRepository extends MongoRepository<KpiDeployment, String> {
    Optional<KpiDeployment> findByKpiIdAndDeviceIdAndCapabilityVersion(String kpiId, String deviceId, Integer capabilityVersion);

    List<KpiDeployment> findByKpiIdAndCapabilityVersion(String kpiId, Integer capabilityVersion);

    List<KpiDeployment> findByKpiId(String kpiId);

    List<KpiDeployment> findByDeviceId(String deviceId);

    /** Rows the reconciler still has work on (status != target); FAILED is parked until a retry. */
    @Query("{ $or: [ { target: 'ACTIVE', status: { $nin: ['ACTIVE', 'FAILED'] } }, { target: 'REMOVED', status: { $nin: ['REMOVED', 'FAILED'] } } ] }")
    List<KpiDeployment> findUnconverged();

    @Query("{ deviceId: ?0, $or: [ { target: 'ACTIVE', status: { $nin: ['ACTIVE', 'FAILED'] } }, { target: 'REMOVED', status: { $nin: ['REMOVED', 'FAILED'] } } ] }")
    List<KpiDeployment> findUnconvergedByDeviceId(String deviceId);
}
