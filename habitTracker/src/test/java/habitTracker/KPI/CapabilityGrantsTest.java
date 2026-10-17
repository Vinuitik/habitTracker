package habitTracker.KPI;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CapabilityGrantsTest {

    private static Map<String, List<String>> net(String... hosts) {
        return Map.of("network", List.of(hosts), "secrets", List.of());
    }

    @Test
    void validHosts_areLowercasedAndDeduped() {
        Map<String, List<String>> n = CapabilityGrants.normalize(net("API.GitHub.com", "api.github.com", "api.trello.com"));
        assertEquals(List.of("api.github.com", "api.trello.com"), n.get("network"));
    }

    @Test
    void nullOrEmptyDeclared_isFine() {
        assertEquals(List.of(), CapabilityGrants.normalize(null).get("network"));
        assertEquals(List.of(), CapabilityGrants.normalize(Map.of()).get("secrets"));
    }

    @Test
    void invalidHosts_areRejected() {
        for (String h : List.of("", " ", "localhost", "javaapp", "sandbox", "egress-proxy", "mongodbHabit", "10.0.0.1",
                "172.17.0.1", "127.0.0.1", "1.2.3.4", "api.example.com:443", "*.example.com", "*", "foo.internal",
                "metadata.google.internal", "printer.local", "host.docker.internal", "a.com/path", "user@a.com",
                "http://a.com", "[::1]", "::1", "single", "a..com", "-a.com", "a-.com", "a b.com", "a.c", "a.123",
                "x.arpa", "x.lan", "x.localhost")) {
            assertThrows(CapabilityRejectedException.class, () -> CapabilityGrants.normalize(net(h)), "host: '" + h + "'");
        }
    }

    @Test
    void tooManyHostsOrSecrets_rejected() {
        String[] many = new String[CapabilityGrants.MAX_HOSTS + 1];
        for (int i = 0; i < many.length; i++) many[i] = "h" + i + ".example.com";
        assertThrows(CapabilityRejectedException.class, () -> CapabilityGrants.normalize(net(many)));
        List<String> secrets = new java.util.ArrayList<>();
        for (int i = 0; i <= CapabilityGrants.MAX_SECRETS; i++) secrets.add("S" + i);
        assertThrows(CapabilityRejectedException.class,
                () -> CapabilityGrants.normalize(Map.of("network", List.of(), "secrets", secrets)));
    }

    @Test
    void secretNames() {
        for (String ok : List.of("API_KEY", "trelloToken", "_x", "A1")) {
            assertTrue(CapabilityGrants.isValidSecretName(ok), ok);
        }
        for (String bad : List.of("", "1A", "A-B", "A B", "A=B", "PATH", "HOME", "HTTPS_PROXY", "http_proxy",
                "NO_PROXY", "PYTHONPATH", "LD_PRELOAD", "SSL_CERT_FILE", "TARGET_DATE", "x".repeat(65))) {
            assertFalse(CapabilityGrants.isValidSecretName(bad), bad);
        }
        assertFalse(CapabilityGrants.isValidSecretName(null));
        assertThrows(CapabilityRejectedException.class,
                () -> CapabilityGrants.normalize(Map.of("network", List.of(), "secrets", List.of("HTTPS_PROXY"))));
    }

    @Test
    void secretValues() {
        CapabilityGrants.requireValidSecretValue("abcd");
        for (String bad : new String[]{null, "abc", "line1\nline2", "a\rbcd", "ab\0cd", "x".repeat(4097)}) {
            assertThrows(IllegalArgumentException.class, () -> CapabilityGrants.requireValidSecretValue(bad));
        }
    }
}
