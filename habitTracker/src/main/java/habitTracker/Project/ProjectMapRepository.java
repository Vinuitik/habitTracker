package habitTracker.Project;

import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface ProjectMapRepository extends MongoRepository<ProjectMap, String> {
    Optional<ProjectMap> findByProjectId(String projectId);
    void deleteByProjectId(String projectId);
}
