package habitTracker.Project;

import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface BoardSnapshotRepository extends MongoRepository<BoardSnapshot, String> {
    Optional<BoardSnapshot> findByProjectId(String projectId);
    List<BoardSnapshot> findByUserId(String userId);
    void deleteByProjectId(String projectId);
}
