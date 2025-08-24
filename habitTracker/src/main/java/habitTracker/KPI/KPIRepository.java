package habitTracker.KPI;

import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface KPIRepository extends MongoRepository<KPI, String> {
    Optional<KPI> findByName(String name);
    List<KPI> findByNameIn(List<String> names);
    List<KPI> findByActive(Boolean active);
    boolean existsByName(String name);
}
