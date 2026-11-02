package habitTracker.integrations.trello;

import habitTracker.sync.VaultEncryptionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * One-time (idempotent, runs every startup) encryption of legacy raw trello_credentials rows: every row whose
 * secretsEncrypted flag is not true gets apiKey/token encrypted via TrelloCredential.sealSecrets() and the flag set.
 * The write is conditional on the flag still being unset, so a concurrent normal write that already
 * sealed the row wins and nothing is double-encrypted. Logs counts only.
 */
@Component
public class TrelloCredentialSecretsMigration implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(TrelloCredentialSecretsMigration.class);

    private final MongoTemplate mongo;
    private final VaultEncryptionService vault;

    public TrelloCredentialSecretsMigration(MongoTemplate mongo, VaultEncryptionService vault) {
        this.mongo = mongo;
        this.vault = vault;
    }

    @Override
    public void run(ApplicationArguments args) {
        migrate();
    }

    /** @return number of rows encrypted by this pass */
    public int migrate() {
        List<TrelloCredential> pending = mongo.find(new Query(Criteria.where("secretsEncrypted").ne(true)), TrelloCredential.class);
        int encrypted = 0;
        int failed = 0;
        for (TrelloCredential row : pending) {
            try {
                row.sealSecrets(vault);
                Update update = new Update()
                        .set("apiKey", row.getApiKey())
                        .set("token", row.getToken())
                        .set("secretsEncrypted", true);
                Query unchanged = new Query(Criteria.where("id").is(row.getId()).and("secretsEncrypted").ne(true));
                encrypted += (int) mongo.updateFirst(unchanged, update, TrelloCredential.class).getModifiedCount();
            } catch (RuntimeException e) {
                failed++;
            }
        }
        if (!pending.isEmpty() || failed > 0) {
            log.info("[SecretsMigration] trello_credentials: {} pending, {} encrypted, {} failed", pending.size(), encrypted, failed);
        }
        return encrypted;
    }
}
