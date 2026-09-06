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
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.SecretKeyFactory;

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
     * Marker identifying the v2 canonical form. No longer produced — see
     * {@link #V3_PREFIX} — but still selected by {@link #verifyHmac} for rows
     * written while v2 was current.
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

    /**
     * Marker for the <strong>v3</strong> canonical form. No longer produced — see
     * {@link #V4_PREFIX} — but still selected by {@link #verify} for rows written
     * while v3 was current. v3 differs from v2 in exactly two places: the user
     * identifier is signed through {@link #identityToken} rather than verbatim, and
     * the per-conversation {@code sequence} joins the signed payload.
     *
     * @see #buildCanonicalStringV3
     */
    static final String V3_PREFIX = "v3:";

    /**
     * Marker for the <strong>v4</strong> canonical form — the one
     * {@link #computeHmac} writes today. v4 differs from v3 in exactly one place:
     * the timestamp is signed as {@code epoch-millis}, not as the raw
     * {@link Instant}.
     * <p>
     * That single field made tamper-evidence non-functional on both supported
     * backends. v3 signed {@code Instant.toString()} at whatever precision the JVM
     * clock produced — nanoseconds on a Linux container — but no backend stores
     * that: PostgreSQL's {@code TIMESTAMPTZ} keeps microseconds and MongoDB's
     * {@code Date} keeps milliseconds. The entry read back therefore never carried
     * the value that was signed, so the recomputed digest never matched. A live
     * ledger reported {@code valid=0 invalid=78} on entries written seconds
     * earlier: an operator running verify could not tell a forged row from a
     * healthy one, because the control emitted no signal at all.
     * <p>
     * Milliseconds is the coarsest floor of the two backends, so a v4 signature
     * round-trips through either without loss. Signing the epoch value rather than
     * the text also removes {@code Instant.toString()}'s trailing-zero variance
     * ({@code …:00Z} vs {@code …:00.000Z}) from the digest.
     *
     * @see #buildCanonicalStringV4
     */
    static final String V4_PREFIX = "v4:";

    /**
     * The storage floor every supported backend can represent: PostgreSQL keeps
     * microseconds, MongoDB milliseconds, so a signed timestamp must be truncated
     * to the coarser of the two before it is signed.
     */
    static final ChronoUnit SIGNED_TIMESTAMP_PRECISION = ChronoUnit.MILLIS;

    /**
     * Prefix of a GDPR-pseudonymised user identifier. Shared with the erasure
     * cascade so the two cannot drift — the whole point of {@link #identityToken}
     * is that it recognises the exact string erasure writes.
     */
    public static final String GDPR_PSEUDONYM_PREFIX = "gdpr-erased:";

    private AuditHmac() {
        // Utility class
    }

    /**
     * The pseudonym GDPR erasure substitutes for {@code userId}:
     * {@value #GDPR_PSEUDONYM_PREFIX} followed by the hex SHA-256 of the original
     * identifier.
     *
     * @param userId
     *            the original user identifier
     * @return the deterministic pseudonym for that user
     */
    public static String pseudonymFor(String userId) {
        // sha256Hex(null) throws an NPE deep inside the digest, which surfaces as an
        // opaque 500 far from the caller. A null userId here means a REST path or
        // query parameter was not what it was assumed to be, and saying so beats
        // making someone read a stack trace to find out.
        if (userId == null) {
            throw new IllegalArgumentException("userId must not be null when deriving a GDPR pseudonym");
        }
        return GDPR_PSEUDONYM_PREFIX + sha256Hex(userId);
    }

    /**
     * The value v3 signs in place of the raw {@code userId}: the identifier's
     * <em>pseudonym</em>, whether or not erasure has already run.
     * <p>
     * This is what makes GDPR pseudonymisation signature-preserving. Erasure
     * rewrites {@code userId} from {@code alice} to
     * {@code gdpr-erased:<sha256(alice)>}; both map to the same token, so the HMAC
     * computed before erasure still verifies after it. Under v1/v2 — which signed
     * {@code userId} verbatim — a routine erasure left every affected row
     * cryptographically indistinguishable from a tampered one.
     * <p>
     * Identity is still bound: swapping {@code alice} for {@code bob} yields a
     * different token and breaks the HMAC. The one substitution that verifies is
     * replacing an identifier with its own pseudonym, which is precisely the
     * mutation {@link ai.labs.eddi.engine.audit.IAuditStore#pseudonymizeByUserId}
     * is permitted to make.
     */
    static String identityToken(String userId) {
        if (userId == null || userId.isEmpty()) {
            return "";
        }
        return userId.startsWith(GDPR_PSEUDONYM_PREFIX) ? userId : pseudonymFor(userId);
    }

    private static String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
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
            SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            PBEKeySpec spec = new PBEKeySpec(masterKey.toCharArray(),
                    PBKDF2_SALT.getBytes(StandardCharsets.UTF_8), PBKDF2_ITERATIONS, 256);
            return factory.generateSecret(spec).getEncoded();
        } catch (Exception e) {
            throw new RuntimeException("Failed to derive HMAC key", e);
        }
    }

    /**
     * The entry as it should be <em>stored</em>: its timestamp floored to the
     * precision {@link #SIGNED_TIMESTAMP_PRECISION} signs.
     * <p>
     * Signing a millisecond value is not enough on its own. PostgreSQL's
     * {@code timestamp(6)} <em>rounds</em> to the nearest microsecond rather than
     * truncating, so an instant whose nanoseconds fall in the last half-microsecond
     * of a millisecond rounds its microsecond count up across the millisecond
     * boundary and reads back one millisecond later than it was signed — about one
     * row in two thousand, reported as tampered for no reason. Storing what was
     * signed leaves the database nothing to round, and makes the two backends agree
     * on the ledger's timestamp resolution instead of differing by a factor of a
     * thousand.
     *
     * @param entry
     *            the entry about to be signed and stored
     * @return the same entry with a storage-safe timestamp, or unchanged when it
     *         carries none
     */
    public static AuditEntry withStorablePrecision(AuditEntry entry) {
        if (entry == null || entry.timestamp() == null) {
            return entry;
        }
        return entry.withTimestamp(entry.timestamp().truncatedTo(SIGNED_TIMESTAMP_PRECISION));
    }

    /**
     * Compute HMAC-SHA256 over all audit entry fields (excluding the hmac field
     * itself), using the v4 canonical form.
     * <p>
     * Pass the entry through {@link #withStorablePrecision} first: v4 signs the
     * timestamp at millisecond precision, and the entry that is stored has to carry
     * the same value, or the database's own rounding can move it.
     *
     * @param entry
     *            the audit entry (hmac field is ignored)
     * @param hmacKey
     *            the 32-byte HMAC key
     * @return version-tagged, hex-encoded HMAC string ({@code v4:<64 hex chars>})
     */
    public static String computeHmac(AuditEntry entry, byte[] hmacKey) {
        return V4_PREFIX + hmacSha256(buildCanonicalStringV4(entry), hmacKey);
    }

    /**
     * Verify that an audit entry's HMAC matches the expected value.
     * <p>
     * The canonical form is selected from the stored value's version tag: a
     * {@link #V3_PREFIX} row is held to v3 only, a {@link #V2_PREFIX} row to v2
     * only, and an untagged row (written before either existed) to v1. A form is
     * never retried against another — falling back would hand the weaker form's
     * collisions back to an attacker.
     * <p>
     * The digests are compared as raw bytes through
     * {@link MessageDigest#isEqual(byte[], byte[])} rather than with
     * {@link String#equals(Object)}, which returns on the first differing character
     * and so leaks, through timing, how much of a forged HMAC is correct — enough
     * to reconstruct one digit at a time against a compliance ledger. The version
     * prefix is <em>not</em> secret (it only selects the canonicalizer), so
     * inspecting it with {@code startsWith} is fine; it is the digest comparison
     * that has to be data-independent.
     * <p>
     * A stored value that is not valid hex — truncated, mangled, or never a digest
     * at all — cannot match anything and is rejected rather than throwing. Hex
     * parsing accepts either case; every digest this class writes is lowercase.
     *
     * @param entry
     *            the audit entry with its hmac field populated
     * @param hmacKey
     *            the 32-byte HMAC key
     * @return true if the HMAC is valid, false if tampered
     */
    /**
     * The canonical-form version a stored HMAC names: {@code "v4"}, {@code "v3"},
     * {@code "v2"}, {@code "v1"} for a bare pre-tag digest, or {@code null} for an
     * unsigned value.
     * <p>
     * Exists for triage, not verification. A verify sweep reports both a freshly
     * tampered v4 row and an unrecoverable legacy row as INVALID, and those demand
     * opposite reactions: a v4 failure means something touched a row this release
     * wrote and is an alarm, while a pre-v4 failure is usually a row whose payload
     * held live Java objects when it was signed — a form nothing can reconstruct,
     * so no recovery search will ever clear it. Without the version on the problem
     * entry, an operator sweeping an old ledger cannot tell the expected residue
     * from the emergency.
     */
    public static String versionOf(String storedHmac) {
        if (storedHmac == null || storedHmac.isBlank()) {
            return null;
        }
        if (storedHmac.startsWith(V4_PREFIX)) {
            return "v4";
        }
        if (storedHmac.startsWith(V3_PREFIX)) {
            return "v3";
        }
        if (storedHmac.startsWith(V2_PREFIX)) {
            return "v2";
        }
        return "v1";
    }

    public static boolean verifyHmac(AuditEntry entry, byte[] hmacKey) {
        return verify(entry, hmacKey, false) != VerificationOutcome.MISMATCH;
    }

    /**
     * The outcome of re-checking one entry, distinguishing a plain match from one
     * that needed the v3 timestamp-completion search.
     */
    public enum VerificationOutcome {
        /** The stored digest matched a straight recomputation. */
        MATCH,

        /**
         * A v3 row that matched only once the timestamp digits its backend could not
         * store were reconstructed. Proof of integrity, not a weaker result — see
         * {@link #verify}.
         */
        MATCH_RECOVERED,

        /** No recomputation matched. The entry was altered, or signed elsewhere. */
        MISMATCH
    }

    /**
     * Verify an entry, optionally recovering the timestamp precision v3 rows lost
     * on the way into storage.
     * <p>
     * v3 signed the timestamp at the JVM clock's precision while the backends store
     * less of it, so a v3 row's stored fields are not the fields that were signed
     * and it can never verify as written. The <em>signature itself</em> still
     * carries the missing digits, though: trying each candidate sub-precision
     * completion of the stored timestamp and recomputing costs one HMAC per
     * candidate, and a match identifies the original value. The search covers both
     * directions (storage floors <em>or</em> rounds to nearest, depending on the
     * backend) and is capped to the precision the row demonstrably lost — a few
     * thousand candidates and a few milliseconds of work per row; see
     * {@link #recoverV3Timestamp}.
     * <p>
     * This is not a weakening. Producing a completion that matches without holding
     * the key is exactly as hard as forging the digest outright; the search only
     * re-derives a value the writer knew and storage discarded. It is also why
     * legacy rows must never be re-signed in place: resealing without verifying
     * would launder any tampering that already happened, whereas recovering proves
     * the row is the one that was written.
     * <p>
     * About one v3 row in a thousand verifies with no search at all — its clock
     * happened to land on a whole unit. That is expected, not a special case.
     *
     * @param entry
     *            the audit entry with its hmac field populated
     * @param hmacKey
     *            the 32-byte HMAC key
     * @param recoverLegacyTimestamps
     *            run the completion search for v3 rows that do not match as stored
     * @return whether, and how, the entry verified
     */
    public static VerificationOutcome verify(AuditEntry entry, byte[] hmacKey, boolean recoverLegacyTimestamps) {
        return verify(entry, hmacKey, recoverLegacyTimestamps ? new AuditRecoveryBudget(1) : AuditRecoveryBudget.none());
    }

    /**
     * Verify an entry, spending recovery searches from a budget the whole sweep
     * shares.
     * <p>
     * The per-row search is bounded; without this the per-<em>sweep</em> work is
     * not. See {@link AuditRecoveryBudget}.
     *
     * @param entry
     *            the audit entry with its hmac field populated
     * @param hmacKey
     *            the 32-byte HMAC key
     * @param recoveryBudget
     *            consumed once per legacy row that fails the direct check
     * @return whether, and how, the entry verified
     */
    public static VerificationOutcome verify(AuditEntry entry, byte[] hmacKey, AuditRecoveryBudget recoveryBudget) {
        String stored = entry.hmac();
        if (stored == null)
            return VerificationOutcome.MISMATCH;

        String expectedDigest;
        String storedDigest;
        if (stored.startsWith(V4_PREFIX)) {
            expectedDigest = hmacSha256(buildCanonicalStringV4(entry), hmacKey);
            storedDigest = stored.substring(V4_PREFIX.length());
        } else if (stored.startsWith(V3_PREFIX)) {
            byte[] storedV3 = decodeHexOrNull(stored.substring(V3_PREFIX.length()));
            if (storedV3 == null)
                return VerificationOutcome.MISMATCH;
            if (MessageDigest.isEqual(decodeHexOrNull(hmacSha256(buildCanonicalStringV3(entry), hmacKey)), storedV3)) {
                return VerificationOutcome.MATCH;
            }
            // tryConsume() is called only once the direct check has already failed, so a
            // healthy legacy ledger spends none of the budget.
            return recoveryBudget != null && recoveryBudget.tryConsume() && recoverV3Timestamp(entry, hmacKey, storedV3)
                    ? VerificationOutcome.MATCH_RECOVERED
                    : VerificationOutcome.MISMATCH;
        } else if (stored.startsWith(V2_PREFIX)) {
            expectedDigest = hmacSha256(buildCanonicalStringV2(entry), hmacKey);
            storedDigest = stored.substring(V2_PREFIX.length());
        } else {
            // Legacy: a bare hex digest over the v1 canonical string.
            expectedDigest = hmacSha256(buildCanonicalString(entry), hmacKey);
            storedDigest = stored;
        }

        byte[] storedBytes = decodeHexOrNull(storedDigest);
        if (storedBytes == null)
            return VerificationOutcome.MISMATCH;

        return MessageDigest.isEqual(decodeHexOrNull(expectedDigest), storedBytes)
                ? VerificationOutcome.MATCH
                : VerificationOutcome.MISMATCH;
    }

    /**
     * Completions tried per tier and direction: a whole unit would be the next unit
     * up.
     */
    private static final int RECOVERY_CANDIDATES_PER_STEP = 999;

    /**
     * Searches for the sub-precision timestamp digits a v3 row lost in storage.
     * <p>
     * <b>The search runs in both directions</b>, because storage does not only
     * floor. Java's {@code truncatedTo}/{@code toEpochMilli} floor, but
     * PostgreSQL's {@code timestamp(6)} — and the JDBC driver's nanos-to-micros
     * conversion — round to <em>nearest</em>, so a value whose lost digits were in
     * the upper half is stored <em>above</em> the signed one. A forward-only search
     * missed every such row: roughly half of all PostgreSQL legacy rows.
     * <p>
     * <b>The search is capped to exactly the precision the row lost</b>, read off
     * the stored value itself. A row with sub-millisecond digits present came
     * through a microsecond-precision store, so only sub-microsecond digits are
     * unknowable and only ±999ns is searched. A millisecond-aligned row may be a
     * millisecond-floored (MongoDB) one, so the ±999µs tier applies as well. The
     * cap matters because the searched window is, unavoidably, also the window
     * within which a <em>moved</em> stored timestamp is indistinguishable from a
     * truncated one: recovery proves the signed instant exactly, and proves the
     * stored value lies within the destroyed precision of it — never more. Widening
     * the window beyond what storage destroyed would turn a recovery aid into
     * timestamp tamper-tolerance, so it is derived, not configured.
     * <p>
     * A millisecond-floored row written by a nanosecond-resolution clock would need
     * the full million and is deliberately not searched: that costs about a second
     * per row on a bulk sweep, and such a row simply reports as it did before.
     * Recovery is a courtesy to existing ledgers, not a load-bearing path — every
     * row written from now on is v4 and verifies directly.
     *
     * @return true when the digits that reproduce the stored digest were found
     */
    private static boolean recoverV3Timestamp(AuditEntry entry, byte[] hmacKey, byte[] storedDigest) {
        Instant storedTimestamp = entry.timestamp();
        if (storedTimestamp == null) {
            // Nothing was truncated, so there is nothing to complete.
            return false;
        }
        // Sub-millisecond digits present ⇒ a µs-precision store wrote this row and
        // only the nanosecond tier was lost. Only a ms-aligned row can be ms-floored.
        boolean millisecondAligned = storedTimestamp.getNano() % 1_000_000 == 0;
        int[] nanoSteps = millisecondAligned ? new int[]{1, 1_000} : new int[]{1};

        for (int step : nanoSteps) {
            for (int k = 1; k <= RECOVERY_CANDIDATES_PER_STEP; k++) {
                long offsetNanos = (long) k * step;
                if (signedAt(entry, storedTimestamp.plusNanos(offsetNanos), hmacKey, storedDigest)
                        || signedAt(entry, storedTimestamp.minusNanos(offsetNanos), hmacKey, storedDigest)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * True when the v3 digest over the entry at {@code candidate} matches the
     * stored one.
     */
    private static boolean signedAt(AuditEntry entry, Instant candidate, byte[] hmacKey, byte[] storedDigest) {
        String recomputed = hmacSha256(buildCanonicalStringV3(entry.withTimestamp(candidate)), hmacKey);
        return MessageDigest.isEqual(decodeHexOrNull(recomputed), storedDigest);
    }

    /**
     * Decodes a hex digest to its raw bytes, or {@code null} when the text is not
     * valid hex (odd length, non-hex characters). Only the stored side can realise
     * that case; the recomputed side is always {@link HexFormat#formatHex(byte[])}
     * output.
     */
    private static byte[] decodeHexOrNull(String hex) {
        try {
            return HexFormat.of().parseHex(hex);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * Build the <strong>v4</strong> canonical string — the form new entries are
     * signed with.
     * <p>
     * Byte-for-byte v3 apart from the version tag and {@code ts}, which is the
     * millisecond epoch value of the timestamp rather than its
     * {@link Instant#toString()}. See {@link #V4_PREFIX} for why that one field
     * broke verification everywhere.
     * <p>
     * <strong>Frozen once written.</strong> Same rule as v1/v2/v3: changing a byte
     * here makes every v4 row read as tampered. Add a v5 instead.
     */
    static String buildCanonicalStringV4(AuditEntry entry) {
        StringBuilder sb = new StringBuilder(512);
        sb.append("v4");
        sb.append("|id=").append(escape(entry.id()));
        sb.append("|cid=").append(escape(entry.conversationId()));
        sb.append("|bid=").append(escape(entry.agentId()));
        sb.append("|bv=").append(entry.agentVersion());
        sb.append("|uid=").append(escape(identityToken(entry.userId())));
        sb.append("|env=").append(escape(entry.environment()));
        sb.append("|si=").append(entry.stepIndex());
        sb.append("|tid=").append(escape(entry.taskId()));
        sb.append("|tt=").append(escape(entry.taskType()));
        sb.append("|ti=").append(entry.taskIndex());
        sb.append("|seq=").append(entry.sequence());
        sb.append("|dur=").append(entry.durationMs());
        sb.append("|in=").append(canonicalValueV2(entry.input()));
        sb.append("|out=").append(canonicalValueV2(entry.output()));
        sb.append("|llm=").append(canonicalValueV2(entry.llmDetail()));
        sb.append("|tools=").append(canonicalValueV2(entry.toolCalls()));
        sb.append("|actions=").append(canonicalValueV2(entry.actions()));
        sb.append("|cost=").append(entry.cost());
        sb.append("|ts=").append(signedTimestamp(entry.timestamp()));
        return sb.toString();
    }

    /**
     * The timestamp as v4 signs it: epoch milliseconds, or the empty string when
     * the entry carries no timestamp at all.
     */
    private static String signedTimestamp(Instant timestamp) {
        return timestamp == null ? "" : Long.toString(timestamp.truncatedTo(SIGNED_TIMESTAMP_PRECISION).toEpochMilli());
    }

    /**
     * Build the <strong>v3</strong> canonical string — the form written before
     * {@link #buildCanonicalStringV4}, still selected by {@link #verify} for rows
     * signed while it was current.
     * <p>
     * Same escaping and type-tagging as {@link #buildCanonicalStringV2}, with two
     * changes:
     * <ul>
     * <li>{@code uid} is the {@link #identityToken}, not the raw identifier, so a
     * GDPR pseudonymisation no longer invalidates the signature it had.</li>
     * <li>{@code seq} — the entry's position in its conversation — is signed, so an
     * entry cannot be renumbered and a deletion leaves a gap that verification can
     * see. A per-entry HMAC on its own only detects in-place edits.</li>
     * </ul>
     * <p>
     * <strong>Frozen once written.</strong> Same rule as v1/v2: changing a byte
     * here makes every v3 row read as tampered — which is why the timestamp defect
     * became v4 rather than an edit to this method.
     */
    static String buildCanonicalStringV3(AuditEntry entry) {
        StringBuilder sb = new StringBuilder(512);
        sb.append("v3");
        sb.append("|id=").append(escape(entry.id()));
        sb.append("|cid=").append(escape(entry.conversationId()));
        sb.append("|bid=").append(escape(entry.agentId()));
        sb.append("|bv=").append(entry.agentVersion());
        sb.append("|uid=").append(escape(identityToken(entry.userId())));
        sb.append("|env=").append(escape(entry.environment()));
        sb.append("|si=").append(entry.stepIndex());
        sb.append("|tid=").append(escape(entry.taskId()));
        sb.append("|tt=").append(escape(entry.taskType()));
        sb.append("|ti=").append(entry.taskIndex());
        sb.append("|seq=").append(entry.sequence());
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
     * entries are signed with {@link #buildCanonicalStringV4}.
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
