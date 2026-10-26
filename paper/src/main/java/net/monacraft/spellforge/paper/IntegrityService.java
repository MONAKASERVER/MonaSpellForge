package net.monacraft.spellforge.paper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Objects;
import java.util.UUID;

final class IntegrityService {
    private final byte[] secret;

    IntegrityService(String secret) {
        this.secret = Base64.getDecoder().decode(Objects.requireNonNull(secret));
        if (this.secret.length < 32) throw new IllegalArgumentException("HMAC secret must be at least 256 bits");
    }

    String sign(int schemaVersion, UUID instanceId, String fragmentId, int level, UUID ownerId, String issuer, long issuedAt) {
        String canonical = schemaVersion + "|" + instanceId + "|" + fragmentId + "|" + level + "|" + ownerId + "|" + issuer + "|" + issuedAt;
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception error) { throw new IllegalStateException("Could not create item signature", error); }
    }

    boolean verify(String signature, int schemaVersion, UUID instanceId, String fragmentId, int level, UUID ownerId, String issuer, long issuedAt) {
        if (signature == null) return false;
        byte[] expected = sign(schemaVersion, instanceId, fragmentId, level, ownerId, issuer, issuedAt).getBytes(StandardCharsets.US_ASCII);
        return MessageDigest.isEqual(expected, signature.getBytes(StandardCharsets.US_ASCII));
    }

    String signPayload(String domain, Object... fields) {
        StringBuilder canonical = new StringBuilder(Objects.requireNonNull(domain));
        for (Object field : fields) canonical.append('|').append(Objects.toString(field, ""));
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(
                    mac.doFinal(canonical.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (Exception error) { throw new IllegalStateException("Could not create payload signature", error); }
    }

    boolean verifyPayload(String signature, String domain, Object... fields) {
        if (signature == null) return false;
        byte[] expected = signPayload(domain, fields).getBytes(StandardCharsets.US_ASCII);
        return MessageDigest.isEqual(expected, signature.getBytes(StandardCharsets.US_ASCII));
    }
}
