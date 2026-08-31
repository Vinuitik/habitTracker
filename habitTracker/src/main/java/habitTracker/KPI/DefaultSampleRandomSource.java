package habitTracker.KPI;

import org.springframework.stereotype.Component;

/**
 * Production SampleRandomSource — the one and only place Math.random() is called for the
 * confirm-sampling decision. Tests inject a mock/fake SampleRandomSource instead of this bean.
 */
@Component
public class DefaultSampleRandomSource implements SampleRandomSource {
    @Override
    public double nextDouble() {
        return Math.random();
    }
}
