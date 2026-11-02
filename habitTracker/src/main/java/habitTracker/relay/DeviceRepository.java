package habitTracker.relay;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

public interface DeviceRepository extends MongoRepository<Device, String> {
    List<Device> findByUserIdOrderByCreatedAtAsc(String userId);
    Optional<Device> findByTokenHash(String tokenHash);
    Optional<Device> findByIdAndUserId(String id, String userId);
}
