package habitTracker.aiusage;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.time.Instant;
import java.util.List;

public interface AiUsageRepository extends MongoRepository<AiUsage, String> {

    boolean existsByUserIdAndJobId(String userId, String jobId);

    List<AiUsage> findByUserIdAndAtAfterOrderByAtDesc(String userId, Instant after);
}
