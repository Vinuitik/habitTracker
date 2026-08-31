package habitTracker.KPI;

/**
 * Injectable source of a uniform random double in [0, 1), used by KPIService to decide whether a
 * freshly-written proxy KPIData point gets flagged `pending` for manual confirmation
 * (see KPIService's confirmSampleRate-driven sampling). Deliberately not Math.random() called
 * directly at the call site — going through an interface lets unit tests inject a fixed value and
 * assert both the "hit" (pending=true) and "miss" (commits normally) branches deterministically,
 * with no flaky seeded-random tests.
 */
public interface SampleRandomSource {
    double nextDouble();
}
