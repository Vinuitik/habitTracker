package habitTracker.Project;

import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ProjectRepository extends MongoRepository<Project, String> {
    List<Project> findByUserId(String userId);
    Optional<Project> findByIdAndUserId(String id, String userId);
}
