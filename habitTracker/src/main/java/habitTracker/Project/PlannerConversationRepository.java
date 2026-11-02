package habitTracker.Project;

import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface PlannerConversationRepository extends MongoRepository<PlannerConversation, String> {
    Optional<PlannerConversation> findByIdAndProjectIdAndUserId(String id, String projectId, String userId);
    void deleteByProjectId(String projectId);
}
