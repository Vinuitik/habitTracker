package habitTracker.relay;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class UsageLimitTest {

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void claudeCliWordingMatches() {
        assertThat(UsageLimit.matches("Claude AI usage limit reached|1731279600")).isTrue();
        assertThat(UsageLimit.matches("You've hit your limit · resets 3pm (Europe/Kyiv)")).isTrue();
        assertThat(UsageLimit.matches("{\"type\":\"rate_limit_error\"}")).isTrue();
    }

    @Test
    void genericRateLimitAndNullDoNotMatch() {
        assertThat(UsageLimit.matches("GitHub API rate limit, backing off")).isFalse();
        assertThat(UsageLimit.matches(null)).isFalse();
    }

    @Test
    void resetTimeIsQuoted() {
        assertThat(UsageLimit.message("You've hit your limit · resets 3pm")).hasValue(
                "Your Claude usage is exhausted (resets 3pm). Try again then.");
    }

    @Test
    void failedSnapshotWithNullErrorFindsLimitInEventTail() throws Exception {
        var snap = json.readTree("{\"state\":\"failed\",\"error\":null,\"events\":["
                + "{\"kind\":\"text\",\"text\":\"working\"},{\"kind\":\"text\",\"text\":\"Claude AI usage limit reached\"}]}");
        assertThat(UsageLimit.message(snap)).hasValue(UsageLimit.MESSAGE);
        assertThat(UsageLimit.message(json.readTree("{\"state\":\"failed\",\"error\":null}"))).isEmpty();
    }
}
