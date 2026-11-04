package habitTracker.KPI;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/** S1: output contract (plain number stays valid, JSON adds unit + evidence) and the plausibility guard. */
class CapabilityOutputTest {

    @Test
    void plainNumber_stillParses_takingTheLastNonBlankLine() {
        CapabilityOutput o = CapabilityOutput.parse("debug line\n47.5\n\n");
        assertEquals(47.5, o.value());
        assertNull(o.unit());
        assertTrue(o.evidence().isEmpty());
        assertEquals(12.0, SandboxCapabilityProvider.parseLastLine("12"));
    }

    @Test
    void json_valueUnitAndEvidence() {
        CapabilityOutput o = CapabilityOutput.parse("noise\n{\"value\": 47, \"unit\": \"min\", \"evidence\": "
                + "[{\"label\": \"YouTube\", \"value\": \"40 min\"}, {\"label\": \"n\", \"value\": 3}]}");
        assertEquals(47.0, o.value());
        assertEquals("min", o.unit());
        assertEquals(List.of(new CapabilityOutput.Row("YouTube", "40 min"), new CapabilityOutput.Row("n", "3")), o.evidence());
        assertEquals(47.0, SandboxCapabilityProvider.parseLastLine("{\"value\": 47}"));
    }

    @Test
    void json_capsEvidenceAtTwentyRows_andAcceptsNumericStringValue() {
        StringBuilder sb = new StringBuilder("{\"value\": \"3\", \"evidence\": [");
        for (int i = 0; i < 30; i++) sb.append(i == 0 ? "" : ",").append("{\"label\":\"r").append(i).append("\",\"value\":\"v\"}");
        CapabilityOutput o = CapabilityOutput.parse(sb.append("]}").toString());
        assertEquals(3.0, o.value());
        assertEquals(CapabilityOutput.MAX_EVIDENCE, o.evidence().size());
    }

    @Test
    void prettyPrintedJson_isAccepted() {
        CapabilityOutput o = CapabilityOutput.parse("log\n{\n  \"value\": 9,\n  \"unit\": \"count\"\n}\n");
        assertEquals(9.0, o.value());
        assertEquals("count", o.unit());
    }

    @Test
    void garbage_nonFinite_andMissingValue_areNull() {
        assertNull(CapabilityOutput.parse(null));
        assertNull(CapabilityOutput.parse("  \n"));
        assertNull(CapabilityOutput.parse("not a number"));
        assertNull(CapabilityOutput.parse("NaN"));
        assertNull(CapabilityOutput.parse("Infinity"));
        assertNull(CapabilityOutput.parse("{\"unit\": \"min\"}"));
        assertNull(CapabilityOutput.parse("{\"value\": \"lots\"}"));
        assertNull(CapabilityOutput.parse("{\"value\": [1]}"));
        assertNull(CapabilityOutput.parse("{broken"));
        assertNull(SandboxCapabilityProvider.parseLastLine("{\"value\": \"NaN\"}"));
    }

    @Test
    void formatValue_isPlainLanguage() {
        assertEquals("47 min", CapabilityOutput.formatValue(47.0, "min"));
        assertEquals("47.5", CapabilityOutput.formatValue(47.5, null));
    }

    // ── plausibility guard ───────────────────────────────────────────────────

    private static final List<Double> HISTORY = List.of(10.0, 12.0, 11.0, 9.0, 10.0, 13.0, 10.0);

    @Test
    void guard_negativeWhereUnitCannotBeNegative_isFlagged_butUnknownUnitIsNot() {
        assertTrue(ReadingPlausibility.check(-3, "min", HISTORY).isPresent());
        assertTrue(ReadingPlausibility.check(-3, "Minutes", List.of()).isPresent());
        assertTrue(ReadingPlausibility.check(-3, "°C", HISTORY).isEmpty());
        assertTrue(ReadingPlausibility.check(-3, null, HISTORY).isEmpty());
    }

    @Test
    void guard_nonFinite_isAlwaysFlagged() {
        assertTrue(ReadingPlausibility.check(Double.NaN, null, List.of()).isPresent());
        assertTrue(ReadingPlausibility.check(Double.POSITIVE_INFINITY, "min", HISTORY).isPresent());
    }

    @Test
    void guard_moreThanFiveTimesMedian_isFlagged_exactlyFiveTimesIsNot() {
        double median = ReadingPlausibility.median(HISTORY); // 10
        assertEquals(10.0, median);
        Optional<String> hit = ReadingPlausibility.check(50.1, "min", HISTORY);
        assertTrue(hit.isPresent());
        assertTrue(hit.get().contains("5 times"));
        assertTrue(ReadingPlausibility.check(50.0, "min", HISTORY).isEmpty());
        assertTrue(ReadingPlausibility.check(0.0, "min", HISTORY).isEmpty());
    }

    @Test
    void guard_needsAtLeastFiveReadings_andAPositiveMedian() {
        assertTrue(ReadingPlausibility.check(1000, "min", List.of(10.0, 10.0, 10.0, 10.0)).isEmpty());
        assertTrue(ReadingPlausibility.check(1000, "min", List.of(0.0, 0.0, 0.0, 0.0, 0.0)).isEmpty());
    }

    @Test
    void median_ofEvenCount_isTheMeanOfTheMiddleTwo() {
        assertEquals(2.5, ReadingPlausibility.median(List.of(4.0, 1.0, 3.0, 2.0)));
    }
}
