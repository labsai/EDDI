package ai.labs.eddi.engine.audit;

import ai.labs.eddi.engine.audit.model.AuditEntry;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.stream.Collectors;

/**
 * HMAC-SHA256 integrity signing for audit entries.
 * <p>
 * Derives the signing key from the same vault master key ({@code eddi.vault.master-key} /
 * {@code EDDI_VAULT_MASTER_KEY}) using PBKDF2 with a distinct salt, so the audit HMAC key
 * is cryptographically independent from the vault's KEK.
 * <p>
 * The HMAC is computed over a canonical string representation of all
 * audit entry fields (excluding the HMAC itself). If any field is tampered
 * with after storage, the HMAC will no longer verify.
 *
 * @author ginccc
 * @since 6.0.0
 */
public final class AuditHmac {

    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final String PBKDF2_SALT = "eddi-audit-hmac-v1";

    private AuditHmac() {
        // Utility class
    }

    /**
     * Derive a 32-byte HMAC key from the vault master key string using PBKDF2.
     * Uses a distinct salt from the vault KEK derivation so the keys are independent.
     *
     * @param masterKey the vault master key string
     * @return the derived 32-byte HMAC key
     */
    public static byte[] deriveHmacKey(String masterKey) {
        try {
            javax.crypto.SecretKeyFactory factory =
                    javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            javax.crypto.spec.PBEKeySpec spec =
                    new javax.crypto.spec.PBEKeySpec(
                            masterKey.toCharArray(),
                            PBKDF2_SALT.getBytes(StandardCharsets.UTF_8),
                            600_000,
                            256);
            return factory.generateSecret(spec).getEncoded();
        } catch (Exception e) {
            throw new RuntimeException("Failed to derive HMAC key", e);
        }
    }

    /**
     * Compute HMAC-SHA256 over all audit entry fields (excluding the hmac field itself).
     *
     * @param entry   the audit entry (hmac field is ignored)
     * @param hmacKey the 32-byte HMAC key
     * @return hex-encoded HMAC string
     */
    public static String computeHmac(AuditEntry entry, byte[] hmacKey) {
        String canonical = buildCanonicalString(entry);
        return hmacSha256(canonical, hmacKey);
    }

    /**
     * Verify that an audit entry's HMAC matches the expected value.
     *
     * @param entry   the audit entry with its hmac field populated
     * @param hmacKey the 32-byte HMAC key
     * @return true if the HMAC is valid, false if tampered
     */
    public static boolean verifyHmac(AuditEntry entry, byte[] hmacKey) {
        if (entry.hmac() == null) return false;
        String expected = computeHmac(entry, hmacKey);
        return expected.equals(entry.hmac());
    }

    /**
     * Build a deterministic canonical string from all audit entry fields
     * (excluding hmac) for HMAC computation.
     */
    static String buildCanonicalString(AuditEntry entry) {
        StringBuilder sb = new StringBuilder(512);
        sb.append("id=").append(nullSafe(entry.id()));
        sb.append("|cid=").append(nullSafe(entry.conversationId()));
        sb.append("|bid=").append(nullSafe(entry.botId()));
        sb.append("|bv=").append(entry.botVersion());
        sb.append("|uid=").append(nullSafe(entry.userId()));
        sb.append("|env=").append(nullSafe(entry.environment()));
        sb.append("|si=").append(entry.stepIndex());
        sb.append("|tid=").append(nullSafe(entry.taskId()));
        sb.append("|tt=").append(nullSafe(entry.taskType()));
        sb.append("|ti=").append(entry.taskIndex());
        sb.append("|dur=").append(entry.durationMs());
        sb.append("|in=").append(sortedMapString(entry.input()));
        sb.append("|out=").append(sortedMapString(entry.output()));
        sb.append("|llm=").append(sortedMapString(entry.llmDetail()));
        sb.append("|tools=").append(sortedMapString(entry.toolCalls()));
        sb.append("|actions=").append(Objects.toString(entry.actions(), ""));
        sb.append("|cost=").append(entry.cost());
        sb.append("|ts=").append(Objects.toString(entry.timestamp(), ""));
        return sb.toString();
    }

    /**
     * Produce a deterministic string for a map by sorting keys.
     * Uses TreeMap to ensure consistent ordering regardless of Map implementation.
     */
    private static String sortedMapString(Map<String, Object> map) {
        if (map == null) return "";
        // Sort keys and produce a deterministic representation
        return new TreeMap<>(map).entrySet().stream()
                .map(e -> e.getKey() + "=" + Objects.toString(e.getValue(), ""))
                .collect(Collectors.joining(",", "{", "}"));
    }

    private static String hmacSha256(String data, byte[] key) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(key, HMAC_ALGORITHM));
            byte[] hash = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new RuntimeException("HMAC-SHA256 computation failed", e);
        }
    }

    private static String nullSafe(String value) {
        return value != null ? value : "";
    }
}
