package habitTracker.KPI;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static habitTracker.KPI.KPITargetStatus.State.*;
import static org.junit.jupiter.api.Assertions.*;

class KPITargetStatusTest {

    private static final LocalDate SET_AT = LocalDate.of(2026, 1, 1);
    private static final LocalDate DEADLINE = SET_AT.plusDays(100);

    private static KPITarget target(Double baseline, double value) {
        return KPITarget.builder().value(value).baseline(baseline).setAt(SET_AT).deadline(DEADLINE).build();
    }

    @Test
    void upTarget_onTrack_computesGapProgressExpected() {
        // baseline 0 → target 100, current 50, day 50 of 100
        KPITargetStatus s = KPITargetStatus.of(target(0.0, 100), 50.0, SET_AT.plusDays(50));
        assertEquals("up", s.direction());
        assertEquals(0.5, s.gapPct(), 1e-9);
        assertEquals(0.5, s.progress(), 1e-9);
        assertEquals(0.5, s.expected(), 1e-9);
        assertEquals(ON_TRACK, s.state());
        assertEquals(50L, s.daysLeft());
    }

    @Test
    void downTarget_onTrack() {
        // weight 90 → 80, current 85, day 50
        KPITargetStatus s = KPITargetStatus.of(target(90.0, 80), 85.0, SET_AT.plusDays(50));
        assertEquals("down", s.direction());
        assertEquals(0.5, s.progress(), 1e-9);
        assertEquals(ON_TRACK, s.state());
    }

    @Test
    void reached_exactly_and_overshoot_up() {
        assertEquals(REACHED, KPITargetStatus.of(target(0.0, 100), 100.0, SET_AT.plusDays(10)).state());
        KPITargetStatus over = KPITargetStatus.of(target(0.0, 100), 130.0, SET_AT.plusDays(10));
        assertEquals(REACHED, over.state());
        assertEquals(1.0, over.progress());
        assertEquals(0.0, over.gapPct());
    }

    @Test
    void reached_overshoot_down() {
        assertEquals(REACHED, KPITargetStatus.of(target(90.0, 80), 78.0, SET_AT.plusDays(10)).state());
    }

    @Test
    void reachedAfterDeadline_isStillReached() {
        assertEquals(REACHED, KPITargetStatus.of(target(0.0, 100), 100.0, DEADLINE.plusDays(5)).state());
    }

    @Test
    void missed_whenPastDeadlineAndNotReached() {
        KPITargetStatus s = KPITargetStatus.of(target(0.0, 100), 99.0, DEADLINE.plusDays(1));
        assertEquals(MISSED, s.state());
        assertEquals(-1L, s.daysLeft());
        assertEquals(1.0, s.expected());
    }

    @Test
    void onDeadlineDay_notYetMissed() {
        // progress 0.95 vs expected 1.0 → within band → ON_TRACK, not MISSED
        assertEquals(ON_TRACK, KPITargetStatus.of(target(0.0, 100), 95.0, DEADLINE).state());
    }

    @Test
    void paceBand_boundaries() {
        LocalDate day50 = SET_AT.plusDays(50); // expected 0.5
        KPITarget t = target(0.0, 100);
        assertEquals(AHEAD, KPITargetStatus.of(t, 60.0, day50).state());      // exactly expected+band
        assertEquals(ON_TRACK, KPITargetStatus.of(t, 59.0, day50).state());
        assertEquals(ON_TRACK, KPITargetStatus.of(t, 40.0, day50).state());   // exactly expected-band
        assertEquals(BEHIND, KPITargetStatus.of(t, 39.0, day50).state());
    }

    @Test
    void movingAwayFromTarget_progressClampedToZero() {
        KPITargetStatus s = KPITargetStatus.of(target(50.0, 100), 20.0, SET_AT.plusDays(50));
        assertEquals(0.0, s.progress());
        assertEquals(1.6, s.gapPct(), 1e-9);
        assertEquals(BEHIND, s.state());
    }

    @Test
    void expected_clampedBeforeSetAt() {
        assertEquals(0.0, KPITargetStatus.of(target(0.0, 100), 0.0, SET_AT.minusDays(3)).expected());
    }

    @Test
    void baselineNull_isNoData() {
        KPITargetStatus s = KPITargetStatus.of(target(null, 100), 40.0, SET_AT.plusDays(10));
        assertEquals(NO_DATA, s.state());
        assertNull(s.direction());
        assertNull(s.progress());
        assertEquals(0.1, s.expected(), 1e-9);
    }

    @Test
    void currentNull_isNoData() {
        assertEquals(NO_DATA, KPITargetStatus.of(target(0.0, 100), null, SET_AT.plusDays(10)).state());
    }

    @Test
    void baselineEqualsTarget_gapZero_reached() {
        KPITargetStatus s = KPITargetStatus.of(target(100.0, 100), 100.0, SET_AT.plusDays(10));
        assertEquals(0.0, s.gapPct());
        assertEquals(REACHED, s.state());
    }
}
