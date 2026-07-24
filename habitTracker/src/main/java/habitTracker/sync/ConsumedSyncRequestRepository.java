package habitTracker.sync;

import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ConsumedSyncRequestRepository extends MongoRepository<ConsumedSyncRequest, String> {
}
