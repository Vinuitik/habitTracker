package habitTracker.sync;

import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface UserSyncSettingsRepository extends MongoRepository<UserSyncSettings, String> {
    Optional<UserSyncSettings> findByUserId(String userId);
    List<UserSyncSettings> findByDriveRefreshTokenNotNull();
    void deleteByUserId(String userId);
}
