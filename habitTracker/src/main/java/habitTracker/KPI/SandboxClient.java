package habitTracker.KPI;

import java.util.List;
import java.util.Map;

/** Calls the sandbox sidecar (see /sandbox). Mocked in tests. Implementations must not throw. */
public interface SandboxClient {

    enum Outcome { OK, NONZERO_EXIT, TIMEOUT, SIDECAR_ERROR }

    /**
     * allowedHosts: exact hostnames the run may reach via the egress proxy (empty = no network).
     * secrets: NAME -> plaintext, ONLY the capability's declared ones; becomes env vars in the runner.
     */
    record Request(String userId, String capabilityId, String language, String sourceCode, int timeoutSeconds,
                   List<String> allowedHosts, Map<String, String> secrets) {

        public Request(String userId, String capabilityId, String language, String sourceCode, int timeoutSeconds) {
            this(userId, capabilityId, language, sourceCode, timeoutSeconds, List.of(), Map.of());
        }

        /** Never print secret values (logs, Mockito failure messages). */
        @Override
        public String toString() {
            return "Request[userId=" + userId + ", capabilityId=" + capabilityId + ", allowedHosts=" + allowedHosts
                    + ", secretNames=" + (secrets == null ? List.of() : secrets.keySet()) + "]";
        }
    }

    record Result(Outcome outcome, Integer exitCode, String stdout, String stderr) {
        public boolean ok() { return outcome == Outcome.OK; }
    }

    Result run(Request request);
}
