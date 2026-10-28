package habitTracker.relay;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Map;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * RFC 6238 TOTP (HMAC-SHA1, 6 digits, 30s steps) — what Google Authenticator / Aegis / 1Password
 * expect by default. Pure functions; replay protection and lockout live in TwoFactorService.
 */
@Component
public class TotpService {

    static final int STEP_SECONDS = 30;
    static final int DIGITS = 6;
    // Accept the previous and next step too, for clock drift between phone and server.
    static final int WINDOW = 1;
    static final int SECRET_BYTES = 20;

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

    public byte[] newSecret() {
        byte[] s = new byte[SECRET_BYTES];
        RANDOM.nextBytes(s);
        return s;
    }

    public static long stepAt(long epochSeconds) {
        return Math.floorDiv(epochSeconds, STEP_SECONDS);
    }

    public String codeAt(byte[] secret, long step) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(secret, "HmacSHA1"));
            byte[] h = mac.doFinal(ByteBuffer.allocate(8).putLong(step).array());
            int off = h[h.length - 1] & 0x0f;
            int bin = ((h[off] & 0x7f) << 24) | ((h[off + 1] & 0xff) << 16)
                    | ((h[off + 2] & 0xff) << 8) | (h[off + 3] & 0xff);
            return String.format("%06d", bin % 1_000_000);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** @return the step {@code code} matches within ±WINDOW of {@code epochSeconds}, or -1. */
    public long matchingStep(byte[] secret, String code, long epochSeconds) {
        if (code == null) return -1;
        String c = code.replaceAll("\\s", "");
        if (!c.matches("\\d{" + DIGITS + "}")) return -1;
        byte[] given = c.getBytes(US_ASCII);
        long now = stepAt(epochSeconds);
        long found = -1;
        // No early exit: every candidate is compared, in constant time.
        for (long s = now - WINDOW; s <= now + WINDOW; s++) {
            if (MessageDigest.isEqual(given, codeAt(secret, s).getBytes(US_ASCII))) found = s;
        }
        return found;
    }

    /** RFC 4648 base32 without padding — the form authenticator apps accept for manual entry. */
    public static String base32(byte[] data) {
        StringBuilder out = new StringBuilder();
        int buffer = 0, bits = 0;
        for (byte b : data) {
            buffer = (buffer << 8) | (b & 0xff);
            bits += 8;
            while (bits >= 5) {
                out.append(BASE32.charAt((buffer >> (bits - 5)) & 31));
                bits -= 5;
            }
        }
        if (bits > 0) out.append(BASE32.charAt((buffer << (5 - bits)) & 31));
        return out.toString();
    }

    public String otpauthUri(String email, byte[] secret) {
        String label = URLEncoder.encode("HabitTracker:" + email, UTF_8).replace("+", "%20");
        return "otpauth://totp/" + label + "?secret=" + base32(secret) + "&issuer=HabitTracker";
    }

    /** Inline SVG of a QR code, one 1x1 square per dark module (no image library, no JS). */
    public String qrSvg(String text) {
        try {
            BitMatrix m = new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0,
                    Map.of(EncodeHintType.MARGIN, 2));
            StringBuilder d = new StringBuilder();
            for (int y = 0; y < m.getHeight(); y++) {
                for (int x = 0; x < m.getWidth(); x++) {
                    if (m.get(x, y)) d.append('M').append(x).append(',').append(y).append("h1v1h-1z");
                }
            }
            return "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 " + m.getWidth() + " " + m.getHeight()
                    + "\" shape-rendering=\"crispEdges\"><rect width=\"100%\" height=\"100%\" fill=\"#fff\"/>"
                    + "<path fill=\"#000\" d=\"" + d + "\"/></svg>";
        } catch (WriterException e) {
            throw new IllegalStateException(e);
        }
    }
}
