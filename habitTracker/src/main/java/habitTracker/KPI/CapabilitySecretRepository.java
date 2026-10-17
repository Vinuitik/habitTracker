package habitTracker.KPI;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/** Every method is scoped by userId; there is deliberately no lookup by name alone. */
public interface CapabilitySecretRepository extends MongoRepository<CapabilitySecret, String> {
    List<CapabilitySecret> findByUserId(String userId);

    Optional<CapabilitySecret> findByUserIdAndName(String userId, String name);

    List<CapabilitySecret> findByUserIdAndNameIn(String userId, Collection<String> names);

    long deleteByUserIdAndName(String userId, String name);
}
