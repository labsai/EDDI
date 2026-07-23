/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
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
 * Derives the signing key from the same vault master key
 * ({@code eddi.vault.master-key} / {@code EDDI_VAULT_MASTER_KEY}) using PBKDF2
 * with a distinct salt, so the audit HMAC key is cryptographically independent
 * from the vault's KEK.
 * <p>
 * The HMAC is computed over a canonical string representation of all audit
 * entry fields (excluding the HMAC itself). If any field is tampered with after
 * storage, the HMAC will no longer verify.
 *
 * @author ginccc
 * @since 6.0.0
 */
public final class AuditHmac {

    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final String PBKDF2_SALT = "eddi-audit-hmac-v1";
    /** OWASP recommendation for PBKDF2-SHA256 (2023): minimum 600,000 iterations */
    private static final int PBKDF2_ITERATIONS = 600_000;

    /**
     * Marker prefixed to every HMAC produced by {@link #computeHmac}, identifying
     * the canonical form it was computed over.
     * <p>
     * The stored value carries the version because the two canonicalizers are not
     * interchangeable: verification must pick the one the entry was signed with. A
     * stored HMAC <em>without</em> this prefix is a bare v1 hex digest written
     * before the delimiter escaping existed and is verified with
     * {@link #buildCanonicalString}. Falling back to v1 for a v2-tagged entry would
     * hand the collision back to an attacker, so the choice is by prefix and never
     * by trying both.
     */
    static final String V2_PREFIX = "v2:";

    private AuditHmac() {
        // Utility class
    }

    /**
     * Derive a 32-byte HMAC key from the vault master key string using PBKDF2. Uses
     * a distinct salt from the vault KEK derivation so the keys are independent.
     *
     * @param masterKey
     *            the vault master key string
     * @return the derived 32-byte HMAC key
     */
    public static byte[] deriveHmacKey(String masterKey) {
        try {
            javax.crypto.SecretKeyFactory factory = javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            javax.crypto.spec.PBEKeySpec spec = new javax.crypto.spec.PBEKeySpec(masterKey.toCharArray(),
                    PBKDF2_SALT.getBytes(StandardCharsets.UTF_8), PBKDF2_ITERATIONS, 256);
            return factory.generateSecret(spec).getEncoded();
        } catch (Exception e) {
            throw new RuntimeException("Failed to derive HMAC key", e);
        }
    }

    /**
     * Compute HMAC-SHA256 over all audit entry fields (excluding the hmac field
     * itself), using the v2 canonical form.
     *
     * @param entry
     *            the audit entry (hmac field is ignored)
     * @param hmacKey
     *            the 32-byte HMAC key
     * @return version-tagged, hex-encoded HMAC string ({@code v2:<64 hex chars>})
     */
    public static String computeHmac(AuditEntry entry, byte[] hmacKey) {
        return V2_PREFIX + hmacSha256(buildCanonicalStringV2(entry), hmacKey);
    }

    /**
     * Verify that an audit entry's HMAC matches the expected value.
     * <p>
     * The canonical form is selected from the stored value's version tag, so
     * entries signed before {@link #V2_PREFIX} existed keep verifying against the
     * v1 canonicalizer and entries signed after it are held to v2 only.
     *
     * @param entry
     *            the audit entry with its hmac field populated
     * @param hmacKey
     *            the 32-byte HMAC key
     * @return true if the HMAC is valid, false if tampered
     */
    public static boolean verifyHmac(AuditEntry entry, byte[] hmacKey) {
        String stored = entry.hmac();
        if (stored == null)
            return false;

        if (stored.startsWith(V2_PREFIX)) {
            return computeHmac(entry, hmacKey).equals(stored);
        }

        // Legacy: a bare hex digest over the v1 canonical string.
        return hmacSha256(buildCanonicalString(entry), hmacKey).equals(stored);
    }

    /**
     * Build the <strong>v2</strong> canonical string: deterministic <em>and</em>
     * injective.
     * <p>
     * The v1 form below joins keys and values with {@code = , { } [ ] |} without
     * escaping them, so the map-to-string mapping is not one-to-one — two
     * structurally different entries can canonicalize to the same bytes and share a
     * single valid HMAC, which lets a tampered entry verify as intact. That matters
     * now that {@code toolCalls} carries tool-trace
     * {@code arguments}/{@code result} strings, which are LLM- and user-controlled.
     * <p>
     * v2 closes it two ways: every key and every scalar is escaped so it can no
     * longer contain an unescaped delimiter, and every value is type-tagged
     * ({@code s:} scalar, {@code m} map, {@code l} list, {@code n} null) so a
     * String can never render like a nested map or list.
     *
     * @see #V2_PREFIX
     */
    static String buildCanonicalStringV2(AuditEntry entry) {
        StringBuilder sb = new StringBuilder(512);
        sb.append("v2");
        sb.append("|id=").append(escape(entry.id()));
        sb.append("|cid=").append(escape(entry.conversationId()));
        sb.append("|bid=").append(escape(entry.agentId()));
        sb.append("|bv=").append(entry.agentVersion());
        sb.append("|uid=").append(escape(entry.userId()));
        sb.append("|env=").append(escape(entry.environment()));
        sb.append("|si=").append(entry.stepIndex());
        sb.append("|tid=").append(escape(entry.taskId()));
        sb.append("|tt=").append(escape(entry.taskType()));
        sb.append("|ti=").append(entry.taskIndex());
        sb.append("|dur=").append(entry.durationMs());
        sb.append("|in=").append(canonicalValueV2(entry.input()));
        sb.append("|out=").append(canonicalValueV2(entry.output()));
        sb.append("|llm=").append(canonicalValueV2(entry.llmDetail()));
        sb.append("|tools=").append(canonicalValueV2(entry.toolCalls()));
        sb.append("|actions=").append(canonicalValueV2(entry.actions()));
        sb.append("|cost=").append(entry.cost());
        sb.append("|ts=").append(escape(Objects.toString(entry.timestamp(), "")));
        return sb.toString();
    }

    /**
     * v2 counterpart of {@link #canonicalValue}: same recursion, but each rendering
     * carries a type tag and every scalar and key is escaped, so distinct
     * structures cannot produce the same string.
     */
    private static String canonicalValueV2(Object value) {
        if (value == null)
            return "n";
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> sorted = new TreeMap<>();
            for (Map.Entry<?, ?> e : map.entrySet()) {
                sorted.put(String.valueOf(e.getKey()), e.getValue());
            }
            return sorted.entrySet().stream().map(e -> escape(e.getKey()) + "=" + canonicalValueV2(e.getValue()))
                    .collect(Collectors.joining(",", "m{", "}"));
        }
        if (value instanceof List<?> list) {
            return list.stream().map(AuditHmac::canonicalValueV2).collect(Collectors.joining(",", "l[", "]"));
        }
        return "s:" + escape(value.toString());
    }

    /**
     * Backslash-escape every character the canonical string uses as a delimiter, so
     * a value can never introduce or terminate a field, a map entry or a list
     * element. The backslash itself is escaped first, which keeps the
     * transformation reversible and therefore collision-free.
     */
    private static String escape(String value) {
        if (value == null)
            return "";

        StringBuilder sb = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\', '=', ',', '{', '}', '[', ']', '|' -> sb.append('\\').append(c);
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }

    /**
     * Build a deterministic canonical string from all audit entry fields (excluding
     * hmac) for HMAC computation.
     * <p>
     * <strong>Frozen — v1.</strong> Every entry written before {@link #V2_PREFIX}
     * existed carries a bare hex HMAC over exactly these bytes, and
     * {@link #verifyHmac} still recomputes it for those rows. Changing this method
     * by a single byte makes every historical ledger row read as tampered. New
     * entries are signed with {@link #buildCanonicalStringV2}.
     */
    static String buildCanonicalString(AuditEntry entry) {
        StringBuilder sb = new StringBuilder(512);
        sb.append("id=").append(nullSafe(entry.id()));
        sb.append("|cid=").append(nullSafe(entry.conversationId()));
        sb.append("|bid=").append(nullSafe(entry.agentId()));
        sb.append("|bv=").append(entry.agentVersion());
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
        sb.append("|actions=").append(entry.actions() != null ? String.join(",", entry.actions()) : "");
        sb.append("|cost=").append(entry.cost());
        sb.append("|ts=").append(Objects.toString(entry.timestamp(), ""));
        return sb.toString();
    }

    /**
     * Produce a deterministic string for a map by sorting keys. Uses TreeMap to
     * ensure consistent ordering regardless of Map implementation.
     * <p>
     * Nested maps and lists are canonicalized <em>recursively</em> rather than via
     * {@code toString()}. This is load-bearing for round-tripped entries: the store
     * deserializes {@code llmDetail}/{@code toolCalls} with a shallow
     * {@code new LinkedHashMap<>(document)}, so a nested value comes back as an
     * {@code org.bson.Document} whose {@code toString()} is prefixed with
     * {@code Document&#123;} — a stored entry would then fail to verify against its
     * own HMAC. Scalars still fall through to {@code toString()}, so flat maps
     * (every entry written before nesting existed) produce a byte-identical
     * canonical string and keep verifying.
     */
    private static String sortedMapString(Map<?, ?> map) {
        if (map == null)
            return "";
        // Sort keys and produce a deterministic representation
        Map<String, Object> sorted = new TreeMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            sorted.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        return sorted.entrySet().stream().map(e -> e.getKey() + "=" + canonicalValue(e.getValue()))
                .collect(Collectors.joining(",", "{", "}"));
    }

    /**
     * Canonicalize a single value: maps are sorted recursively, lists keep their
     * order with each element canonicalized, everything else uses
     * {@code toString()} (null becomes the empty string, matching the historical
     * {@code Objects.toString(value, "")} behaviour).
     */
    private static String canonicalValue(Object value) {
        if (value == null)
            return "";
        if (value instanceof Map<?, ?> nested)
            return sortedMapString(nested);
        if (value instanceof List<?> list)
            return list.stream().map(AuditHmac::canonicalValue).collect(Collectors.joining(",", "[", "]"));
        return value.toString();
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
