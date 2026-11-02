package habitTracker.KPI;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.function.Supplier;

/**
 * Per-thread progress sink for one capability task (CapabilityTaskService). CapabilityAgentService reports its
 * stages and RunnerCapabilityGenerator forwards the runner job's events + token usage here. When no sink is
 * bound (blocking API, nightly repair, tests) every call is a no-op, so the blocking path is unchanged.
 */
final class CapabilityProgress {

    interface Sink {
        /** A new stage started; the previous one is done. */
        void step(String text);

        /** A runner job event (kind step|text), already reduced to plain text. */
        void runnerEvent(String kind, String text);

        /** The runner job's raw usage object (token counts), when it returned one. */
        void usage(JsonNode usage);
    }

    private static final ThreadLocal<Sink> CURRENT = new ThreadLocal<>();

    private CapabilityProgress() {}

    static <T> T with(Sink sink, Supplier<T> body) {
        CURRENT.set(sink);
        try {
            return body.get();
        } finally {
            CURRENT.remove();
        }
    }

    static void step(String text) {
        Sink s = CURRENT.get();
        if (s != null) s.step(text);
    }

    static void runnerEvent(String kind, String text) {
        Sink s = CURRENT.get();
        if (s != null) s.runnerEvent(kind, text);
    }

    static void usage(JsonNode usage) {
        Sink s = CURRENT.get();
        if (s != null && usage != null && !usage.isNull()) s.usage(usage);
    }
}
