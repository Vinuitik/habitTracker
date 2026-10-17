package habitTracker.KPI;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SandboxCapabilityProviderTest {

    @Test
    void parseLastLine_usesLastNonBlankLine() {
        assertEquals(42.0, SandboxCapabilityProvider.parseLastLine("hello\n42\n\n"));
        assertEquals(-1.5, SandboxCapabilityProvider.parseLastLine("  -1.5  "));
    }

    @Test
    void parseLastLine_rejectsGarbageEmptyAndNonFinite() {
        assertNull(SandboxCapabilityProvider.parseLastLine(null));
        assertNull(SandboxCapabilityProvider.parseLastLine(""));
        assertNull(SandboxCapabilityProvider.parseLastLine("1\nnot a number"));
        assertNull(SandboxCapabilityProvider.parseLastLine("NaN"));
        assertNull(SandboxCapabilityProvider.parseLastLine("Infinity"));
    }
}
