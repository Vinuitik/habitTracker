package habitTracker.relay;

import habitTracker.sync.PairingCodeService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.springframework.data.mongodb.core.query.Criteria.where;
import static org.springframework.data.mongodb.core.query.Query.query;

/** Pairing, token lookup and ownership for runner devices (see shared/HUB.md). */
@Service
@RequiredArgsConstructor
public class DeviceService {

    static final int TOKEN_BYTES = 32;
    static final int CODE_TTL_SECONDS = 600;
    static final int MAX_NAME = 64;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final DeviceRepository devices;
    private final MongoTemplate mongo;
    // Own instance, not the sync bean: a vault-sync pairing code must never pair a runner (and vice versa).
    private final PairingCodeService codes = new PairingCodeService();

    public record Paired(String deviceId, String token) {}

    // spring.data.mongodb.auto-index-creation is off, so @Indexed alone creates nothing.
    @PostConstruct
    void ensureIndexes() {
        mongo.indexOps(Device.class).ensureIndex(new Index("tokenHash", Sort.Direction.ASC).unique());
        mongo.indexOps(Device.class).ensureIndex(new Index("userId", Sort.Direction.ASC));
    }

    public String newPairingCode(String userId) {
        return codes.generateCode(userId);
    }

    /** Redeems a pairing code (single use) and creates the device; empty if the code is bad, used or expired. */
    public Optional<Paired> pair(String code, String name) {
        return codes.redeem(code.trim().toUpperCase()).map(userId -> {
            byte[] raw = new byte[TOKEN_BYTES];
            RANDOM.nextBytes(raw);
            String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
            Device d = devices.save(Device.builder()
                    .userId(userId)
                    .name(cleanName(name))
                    .tokenHash(hash(token))
                    .createdAt(Instant.now())
                    .build());
            return new Paired(d.getId(), token);
        });
    }

    public Optional<Device> findByToken(String token) {
        if (token == null || token.isBlank()) return Optional.empty();
        return devices.findByTokenHash(hash(token.trim()));
    }

    /** Empty for unknown ids AND for other users' devices: callers answer 404 either way. */
    public Optional<Device> owned(String userId, String deviceId) {
        if (userId == null || deviceId == null) return Optional.empty();
        return devices.findByIdAndUserId(deviceId, userId);
    }

    public List<Device> list(String userId) {
        return devices.findByUserIdOrderByCreatedAtAsc(userId);
    }

    public void delete(Device d) {
        devices.deleteById(d.getId());
    }

    /** Field update, not save(): never resurrects a device deleted while its socket was still open. */
    public void touch(String deviceId) {
        mongo.updateFirst(query(where("_id").is(deviceId)), Update.update("lastSeenAt", Instant.now()), Device.class);
    }

    public static String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String cleanName(String name) {
        String n = name == null ? "" : name.strip().replaceAll("\\p{Cntrl}", "");
        if (n.isEmpty()) return "device";
        return n.length() > MAX_NAME ? n.substring(0, MAX_NAME) : n;
    }
}
