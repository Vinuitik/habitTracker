package habitTracker.KPI;

/** Calls the sandbox sidecar (see /sandbox). Mocked in tests. Implementations must not throw. */
public interface SandboxClient {

    enum Outcome { OK, NONZERO_EXIT, TIMEOUT, SIDECAR_ERROR }

    record Request(String userId, String capabilityId, String language, String sourceCode, int timeoutSeconds) {}

    record Result(Outcome outcome, Integer exitCode, String stdout, String stderr) {
        public boolean ok() { return outcome == Outcome.OK; }
    }

    Result run(Request request);
}
