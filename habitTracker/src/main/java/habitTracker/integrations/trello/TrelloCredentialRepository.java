package habitTracker.integrations.trello;

import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface TrelloCredentialRepository extends MongoRepository<TrelloCredential, String> {
    Optional<TrelloCredential> findByUserId(String userId);
    void deleteByUserId(String userId);
}
