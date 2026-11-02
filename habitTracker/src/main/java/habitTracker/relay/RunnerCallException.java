package habitTracker.relay;

/** The runner answered, but with a non-2xx status. body = its raw response (usually {"error": ...}). */
public class RunnerCallException extends RuntimeException {

    private final int status;
    private final String body;

    public RunnerCallException(int status, String body) {
        super("runner returned " + status);
        this.status = status;
        this.body = body;
    }

    public int getStatus() { return status; }
    public String getBody() { return body; }
}
