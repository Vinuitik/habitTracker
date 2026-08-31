package habitTracker.sync;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class PairingCodeServiceTest {

    @Test
    void generateCode_isRedeemableForTheOwningUser() {
        PairingCodeService service = new PairingCodeService();
        String code = service.generateCode("user-1");

        Optional<String> userId = service.redeem(code);

        assertTrue(userId.isPresent());
        assertEquals("user-1", userId.get());
    }

    @Test
    void redeem_isSingleUse_secondRedemptionFails() {
        PairingCodeService service = new PairingCodeService();
        String code = service.generateCode("user-1");

        assertTrue(service.redeem(code).isPresent());
        assertTrue(service.redeem(code).isEmpty(), "a code must not be redeemable twice");
    }

    @Test
    void redeem_unknownCode_returnsEmpty() {
        PairingCodeService service = new PairingCodeService();

        assertTrue(service.redeem("NOTREALCODE").isEmpty());
    }

    @Test
    void redeem_expiredCode_returnsEmpty() throws InterruptedException {
        PairingCodeService service = new PairingCodeService(20); // 20ms TTL
        String code = service.generateCode("user-1");

        Thread.sleep(50);

        assertTrue(service.redeem(code).isEmpty(), "an expired code must not be redeemable");
    }

    @Test
    void redeem_expiredCode_isAlsoConsumed_cannotBeRedeemedTwice() throws InterruptedException {
        PairingCodeService service = new PairingCodeService(10); // 10ms TTL

        String code = service.generateCode("user-1");
        Thread.sleep(50); // definitely past expiry, well beyond clock-resolution flakiness

        assertTrue(service.redeem(code).isEmpty());
        assertTrue(service.redeem(code).isEmpty(), "redeem() must remove the entry even when expired");
    }

    @Test
    void generateCode_producesDistinctCodesForDifferentUsers() {
        PairingCodeService service = new PairingCodeService();

        String codeA = service.generateCode("user-a");
        String codeB = service.generateCode("user-b");

        assertNotEquals(codeA, codeB);
        assertEquals("user-a", service.redeem(codeA).orElseThrow());
        assertEquals("user-b", service.redeem(codeB).orElseThrow());
    }
}
