package habitTracker.relay;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class TotpServiceTest {

    // RFC 6238 appendix B, SHA-1 seed; the RFC prints 8 digits, we keep the last 6.
    private static final byte[] RFC_SECRET = "12345678901234567890".getBytes(StandardCharsets.US_ASCII);
    private final TotpService totp = new TotpService();

    @Test
    void matchesRfc6238Vectors() {
        assertEquals("287082", totp.codeAt(RFC_SECRET, TotpService.stepAt(59)));
        assertEquals("081804", totp.codeAt(RFC_SECRET, TotpService.stepAt(1111111109)));
        assertEquals("050471", totp.codeAt(RFC_SECRET, TotpService.stepAt(1111111111)));
        assertEquals("005924", totp.codeAt(RFC_SECRET, TotpService.stepAt(1234567890)));
        assertEquals("279037", totp.codeAt(RFC_SECRET, TotpService.stepAt(2000000000)));
    }

    @Test
    void acceptsOneStepOfDriftEitherWay() {
        long now = 1_700_000_000L;
        long step = TotpService.stepAt(now);
        assertEquals(step - 1, totp.matchingStep(RFC_SECRET, totp.codeAt(RFC_SECRET, step - 1), now));
        assertEquals(step + 1, totp.matchingStep(RFC_SECRET, totp.codeAt(RFC_SECRET, step + 1), now));
        assertEquals(-1, totp.matchingStep(RFC_SECRET, totp.codeAt(RFC_SECRET, step + 2), now));
        assertEquals(-1, totp.matchingStep(RFC_SECRET, totp.codeAt(RFC_SECRET, step - 2), now));
    }

    @Test
    void rejectsGarbageAndToleratesSpaces() {
        long now = 1_700_000_000L;
        String code = totp.codeAt(RFC_SECRET, TotpService.stepAt(now));
        assertEquals(TotpService.stepAt(now),
                totp.matchingStep(RFC_SECRET, code.substring(0, 3) + " " + code.substring(3), now));
        assertEquals(-1, totp.matchingStep(RFC_SECRET, null, now));
        assertEquals(-1, totp.matchingStep(RFC_SECRET, "", now));
        assertEquals(-1, totp.matchingStep(RFC_SECRET, "12345", now));
        assertEquals(-1, totp.matchingStep(RFC_SECRET, "abcdef", now));
    }

    @Test
    void base32MatchesRfc4648() {
        assertEquals("MZXW6YTBOI", TotpService.base32("foobar".getBytes(StandardCharsets.US_ASCII)));
        assertEquals("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ", TotpService.base32(RFC_SECRET));
    }

    @Test
    void qrIsAnInlineSvg() {
        String svg = totp.qrSvg(totp.otpauthUri("a@b.c", RFC_SECRET));
        assertTrue(svg.startsWith("<svg") && svg.contains("h1v1h-1z"));
    }
}
