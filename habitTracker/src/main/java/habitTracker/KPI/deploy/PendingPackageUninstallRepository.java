package habitTracker.KPI.deploy;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

public interface PendingPackageUninstallRepository extends MongoRepository<PendingPackageUninstall, String> {
    Optional<PendingPackageUninstall> findByUserIdAndDeviceIdAndManagerAndPackageId(String userId, String deviceId, String manager, String packageId);

    List<PendingPackageUninstall> findByDeviceId(String deviceId);
}
