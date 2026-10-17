package habitTracker.KPI;

import habitTracker.sync.VaultEncryptionService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Per-user encrypted secret store for sandbox capabilities. All methods take the userId explicitly and
 * every query is scoped by it (cf. the cross-user leak fixed in b8a1057). Encryption reuses
 * VaultEncryptionService (AES-256-GCM); the key is KPI_SECRETS_KEY (base64, 32 bytes) or, if unset,
 * derived from jwt.secret.
 *
 * resolve() is the ONLY place plaintext is produced: it decrypts exactly the names asked for, for exactly
 * one user. requestFor() asks for exactly the capability's declared secrets.
 */
@Service
public class CapabilitySecretService {

    static final int MAX_SECRETS_PER_USER = 50;

    private final CapabilitySecretRepository repository;
    private final VaultEncryptionService encryption;
    private final String key;

    public CapabilitySecretService(CapabilitySecretRepository repository, VaultEncryptionService encryption,
                                   @Value("${kpi.secrets.key:}") String configuredKey,
                                   @Value("${jwt.secret}") String jwtSecret) {
        this.repository = repository;
        this.encryption = encryption;
        this.key = configuredKey == null || configuredKey.isBlank() ? deriveKey(jwtSecret) : configuredKey.trim();
    }

    private static String deriveKey(String jwtSecret) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-256")
                    .digest(("kpi-capability-secrets|" + jwtSecret).getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(h);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Names only, sorted. Never values. */
    public List<String> listNames(String userId) {
        TreeSet<String> names = new TreeSet<>();
        repository.findByUserId(userId).forEach(s -> names.add(s.getName()));
        return new ArrayList<>(names);
    }

    public void set(String userId, String name, String value) {
        if (!CapabilityGrants.isValidSecretName(name)) {
            throw new IllegalArgumentException("Invalid secret name");
        }
        String v = value == null ? null : value.strip();
        CapabilityGrants.requireValidSecretValue(v);
        String id = CapabilitySecret.idFor(userId, name);
        if (!repository.existsById(id) && repository.findByUserId(userId).size() >= MAX_SECRETS_PER_USER) {
            throw new IllegalArgumentException("Too many secrets (max " + MAX_SECRETS_PER_USER + ")");
        }
        String enc = Base64.getEncoder().encodeToString(encryption.encryptString(key, v));
        repository.save(CapabilitySecret.builder().id(id).userId(userId).name(name).valueEnc(enc)
                .updatedAt(LocalDateTime.now()).build());
    }

    /** @return true if something was deleted */
    public boolean delete(String userId, String name) {
        return repository.deleteByUserIdAndName(userId, name) > 0;
    }

    /** Decrypts exactly {@code names} for {@code userId}; throws MissingSecretException listing absent ones. */
    public Map<String, String> resolve(String userId, List<String> names) {
        Map<String, String> out = new LinkedHashMap<>();
        if (names == null || names.isEmpty()) return out;
        Map<String, CapabilitySecret> have = new LinkedHashMap<>();
        for (CapabilitySecret s : repository.findByUserIdAndNameIn(userId, names)) {
            if (userId.equals(s.getUserId())) have.put(s.getName(), s);
        }
        List<String> missing = new ArrayList<>();
        for (String n : names) {
            CapabilitySecret s = have.get(n);
            if (s == null) {
                missing.add(n);
                continue;
            }
            out.put(n, new String(encryption.decrypt(key, Base64.getDecoder().decode(s.getValueEnc())),
                    StandardCharsets.UTF_8));
        }
        if (!missing.isEmpty()) throw new MissingSecretException(missing);
        return out;
    }

    /** Names the capability's owner has not set (no decryption). */
    public List<String> missing(String userId, List<String> names) {
        List<String> have = listNames(userId);
        return names.stream().filter(n -> !have.contains(n)).toList();
    }

    /**
     * Builds the sidecar request for a capability: allowed hosts = its declared network hosts, secrets =
     * ONLY its declared secrets, decrypted for ITS owner. Throws MissingSecretException / CapabilityRejectedException.
     */
    public SandboxClient.Request requestFor(KPIProxyCapability cap, String userId, String source, int timeoutSeconds) {
        Map<String, List<String>> declared = CapabilityGrants.normalize(cap.getDeclaredCapabilities());
        List<String> secretNames = CapabilityGrants.secrets(declared);
        Map<String, String> secrets = resolve(userId, secretNames);
        return new SandboxClient.Request(userId, cap.getId(), cap.getLanguage(), source, timeoutSeconds,
                CapabilityGrants.hosts(declared), secrets);
    }
}
