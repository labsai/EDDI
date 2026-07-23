/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.audit;

import ai.labs.eddi.engine.audit.model.AuditEntry;
import com.mongodb.MongoClientSettings;
import org.bson.BsonDocument;
import org.bson.BsonDocumentReader;
import org.bson.Document;
import org.bson.codecs.DecoderContext;
import org.bson.codecs.DocumentCodec;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link AuditHmac} — HMAC key derivation, signing, and verification.
 */
@DisplayName("AuditHmac")
class AuditHmacTest {

    private static byte[] hmacKey;

    @BeforeAll
    static void deriveKey() {
        hmacKey = AuditHmac.deriveHmacKey("test-master-key-12345");
    }

    private static AuditEntry createTestEntry() {
        return new AuditEntry(
                "entry-1", "conv-1", "agent-1", 1, "user-1", "production",
                0, "ai.labs.parser", "expressions", 0, 42L,
                Map.of("userInput", "hello"),
                Map.of("output", "world"),
                null, null,
                List.of("send_message"), 0.005,
                Instant.parse("2026-01-01T00:00:00Z"), null, null);
    }

    // ==================== Key Derivation ====================

    @Nested
    @DisplayName("deriveHmacKey")
    class DeriveKeyTests {

        @Test
        @DisplayName("produces 32-byte key (256 bits)")
        void keyLength() {
            assertEquals(32, hmacKey.length);
        }

        @Test
        @DisplayName("same master key produces same HMAC key (deterministic)")
        void deterministic() {
            byte[] key2 = AuditHmac.deriveHmacKey("test-master-key-12345");
            assertArrayEquals(hmacKey, key2);
        }

        @Test
        @DisplayName("different master keys produce different HMAC keys")
        void differentKeys() {
            byte[] key2 = AuditHmac.deriveHmacKey("different-master-key");
            assertFalse(java.util.Arrays.equals(hmacKey, key2));
        }
    }

    // ==================== HMAC Computation ====================

    @Nested
    @DisplayName("computeHmac")
    class ComputeHmacTests {

        @Test
        @DisplayName("produces a version-tagged hex string")
        void producesHex() {
            String hmac = AuditHmac.computeHmac(createTestEntry(), hmacKey);
            assertNotNull(hmac);
            assertTrue(hmac.startsWith("v2:"),
                    "the stored value must name its canonical form, or verification cannot pick a canonicalizer");
            // Hex string should be 64 chars (32 bytes) after the version tag
            assertEquals(64, hmac.substring("v2:".length()).length());
            assertTrue(hmac.substring("v2:".length()).matches("[0-9a-f]{64}"));
        }

        @Test
        @DisplayName("same entry produces same HMAC (deterministic)")
        void deterministic() {
            AuditEntry entry = createTestEntry();
            String hmac1 = AuditHmac.computeHmac(entry, hmacKey);
            String hmac2 = AuditHmac.computeHmac(entry, hmacKey);
            assertEquals(hmac1, hmac2);
        }

        @Test
        @DisplayName("different entries produce different HMACs")
        void differentEntries() {
            AuditEntry entry1 = createTestEntry();
            AuditEntry entry2 = new AuditEntry(
                    "entry-2", "conv-2", "agent-1", 1, "user-1", "production",
                    0, "ai.labs.parser", "expressions", 0, 42L,
                    Map.of("userInput", "different"),
                    Map.of("output", "world"),
                    null, null,
                    List.of("send_message"), 0.005,
                    Instant.parse("2026-01-01T00:00:00Z"), null, null);
            assertNotEquals(AuditHmac.computeHmac(entry1, hmacKey),
                    AuditHmac.computeHmac(entry2, hmacKey));
        }
    }

    // ==================== HMAC Verification ====================

    @Nested
    @DisplayName("verifyHmac")
    class VerifyHmacTests {

        @Test
        @DisplayName("verifies valid HMAC")
        void validHmac() {
            AuditEntry entry = createTestEntry();
            String hmac = AuditHmac.computeHmac(entry, hmacKey);
            AuditEntry signed = entry.withHmac(hmac);
            assertTrue(AuditHmac.verifyHmac(signed, hmacKey));
        }

        @Test
        @DisplayName("rejects tampered entry")
        void tamperedEntry() {
            AuditEntry entry = createTestEntry();
            String hmac = AuditHmac.computeHmac(entry, hmacKey);
            // Tamper with the entry by changing the environment
            AuditEntry tampered = entry.withEnvironment("TAMPERED").withHmac(hmac);
            assertFalse(AuditHmac.verifyHmac(tampered, hmacKey));
        }

        @Test
        @DisplayName("rejects null HMAC")
        void nullHmac() {
            AuditEntry entry = createTestEntry();
            assertFalse(AuditHmac.verifyHmac(entry, hmacKey));
        }

        @Test
        @DisplayName("rejects wrong key")
        void wrongKey() {
            AuditEntry entry = createTestEntry();
            String hmac = AuditHmac.computeHmac(entry, hmacKey);
            AuditEntry signed = entry.withHmac(hmac);

            byte[] wrongKey = AuditHmac.deriveHmacKey("wrong-master-key");
            assertFalse(AuditHmac.verifyHmac(signed, wrongKey));
        }

        /**
         * Both stored forms go through the same byte comparison, so one assertion pass
         * over both proves the constant-time comparison did not cost either version its
         * verification. A v1 row carries a bare hex digest over the v1 canonical
         * string; a v2 row carries {@code v2:} + a digest over the v2 form.
         */
        @Test
        @DisplayName("a v1 row and a v2 row both verify, and either one tampered is rejected")
        void bothVersionsVerifyAndBothRejectTampering() {
            AuditEntry entry = createTestEntry();

            String v1Hmac = legacySignV1(AuditHmac.buildCanonicalString(entry));
            String v2Hmac = AuditHmac.computeHmac(entry, hmacKey);

            assertFalse(v1Hmac.startsWith("v2:"), "precondition: the v1 row carries no version tag");
            assertTrue(v2Hmac.startsWith("v2:"), "precondition: the v2 row names its canonical form");

            assertTrue(AuditHmac.verifyHmac(entry.withHmac(v1Hmac), hmacKey), "a pre-v2 ledger row must still verify");
            assertTrue(AuditHmac.verifyHmac(entry.withHmac(v2Hmac), hmacKey), "a v2 ledger row must verify");

            AuditEntry tampered = entry.withEnvironment("TAMPERED");
            assertFalse(AuditHmac.verifyHmac(tampered.withHmac(v1Hmac), hmacKey), "a tampered v1 row must be rejected");
            assertFalse(AuditHmac.verifyHmac(tampered.withHmac(v2Hmac), hmacKey), "a tampered v2 row must be rejected");
        }

        /**
         * The comparison now decodes both sides from hex, so a stored value that is not
         * a digest at all has to be rejected rather than propagate a parse failure out
         * of a verification sweep over the whole ledger.
         */
        @Test
        @DisplayName("a malformed stored HMAC is rejected without throwing")
        void malformedStoredHmacIsRejected() {
            AuditEntry entry = createTestEntry();

            for (String malformed : List.of(
                    "", // empty
                    "zz", // non-hex characters
                    "abc", // odd length
                    "not-a-digest",
                    "v2:", // version tag with nothing behind it
                    "v2:zzzz",
                    AuditHmac.computeHmac(entry, hmacKey).substring(0, 20))) { // truncated
                assertFalse(assertDoesNotThrow(() -> AuditHmac.verifyHmac(entry.withHmac(malformed), hmacKey),
                        "verification must not throw on stored value '" + malformed + "'"),
                        "a malformed stored HMAC must not verify: '" + malformed + "'");
            }
        }

        /**
         * A v2-tagged value must never be re-checked against the v1 canonicalizer —
         * that would hand the v1 collision back to an attacker. Signing the v1 form and
         * storing it under the v2 tag must therefore fail.
         */
        @Test
        @DisplayName("a v1 digest stored under the v2 tag does not verify")
        void v1DigestUnderV2TagDoesNotVerify() {
            AuditEntry entry = createTestEntry();
            String mislabelled = "v2:" + legacySignV1(AuditHmac.buildCanonicalString(entry));

            assertFalse(AuditHmac.verifyHmac(entry.withHmac(mislabelled), hmacKey),
                    "the version tag selects the canonicalizer and is never retried against the other one");
        }

        /** Reproduces exactly what the pre-v2 code wrote into the ledger. */
        private String legacySignV1(String canonical) {
            try {
                Mac mac = Mac.getInstance("HmacSHA256");
                mac.init(new SecretKeySpec(hmacKey, "HmacSHA256"));
                return HexFormat.of().formatHex(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
            } catch (GeneralSecurityException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    // ==================== Canonical String ====================

    @Nested
    @DisplayName("buildCanonicalString")
    class CanonicalStringTests {

        @Test
        @DisplayName("includes all fields")
        void includesAllFields() {
            String canonical = AuditHmac.buildCanonicalString(createTestEntry());
            assertTrue(canonical.contains("id=entry-1"));
            assertTrue(canonical.contains("cid=conv-1"));
            assertTrue(canonical.contains("bid=agent-1"));
            assertTrue(canonical.contains("uid=user-1"));
            assertTrue(canonical.contains("env=production"));
            assertTrue(canonical.contains("tid=ai.labs.parser"));
            assertTrue(canonical.contains("actions=send_message"));
        }

        @Test
        @DisplayName("handles null fields gracefully")
        void nullFields() {
            AuditEntry entry = new AuditEntry(
                    null, null, null, null, null, null,
                    0, null, null, 0, 0L,
                    null, null, null, null, null, 0.0,
                    null, null, null);
            String canonical = AuditHmac.buildCanonicalString(entry);
            assertNotNull(canonical);
            assertTrue(canonical.contains("id="));
            assertTrue(canonical.contains("cid="));
        }

        @Test
        @DisplayName("maps are sorted for deterministic output")
        void sortedMaps() {
            AuditEntry entry1 = new AuditEntry(
                    "id", "c", "b", 1, "u", "e",
                    0, "t", "tt", 0, 0L,
                    Map.of("z", "1", "a", "2"),
                    null, null, null, null, 0.0,
                    Instant.EPOCH, null, null);
            String canonical = AuditHmac.buildCanonicalString(entry1);
            // a should come before z in sorted output
            int posA = canonical.indexOf("a=2");
            int posZ = canonical.indexOf("z=1");
            assertTrue(posA < posZ, "Map keys should be sorted alphabetically");
        }
    }

    // ==================== Nested maps / lists (D1) ====================

    @Nested
    @DisplayName("nested map and list canonicalization")
    class NestedCanonicalizationTests {

        private AuditEntry entryWith(Map<String, Object> llmDetail, Map<String, Object> toolCalls) {
            return new AuditEntry(
                    "entry-1", "conv-1", "agent-1", 1, "user-1", "production",
                    0, "ai.labs.llm", "langchain", 0, 42L,
                    Map.of("userInput", "hello"),
                    Map.of("output", "world"),
                    llmDetail, toolCalls,
                    List.of("send_message"), 0.005,
                    Instant.parse("2026-01-01T00:00:00Z"), null, null);
        }

        private Map<String, Object> tokenUsage() {
            Map<String, Object> tokenUsage = new LinkedHashMap<>();
            tokenUsage.put("inputTokens", 10);
            tokenUsage.put("outputTokens", 20);
            tokenUsage.put("totalTokens", 30);
            return tokenUsage;
        }

        /**
         * Guards the back-compat promise of the recursion change: every audit entry
         * written before nesting existed has flat (or null) maps, and its stored HMAC
         * must keep verifying. Pinned to the literal the pre-recursion implementation
         * produced — if the canonical format drifts by a single byte, every historical
         * ledger entry silently becomes "tampered".
         */
        @Test
        @DisplayName("flat maps canonicalize byte-identically to the pre-recursion format")
        void flatMapCanonicalStringUnchangedByRecursion() {
            assertEquals("id=entry-1|cid=conv-1|bid=agent-1|bv=1|uid=user-1|env=production|si=0"
                    + "|tid=ai.labs.parser|tt=expressions|ti=0|dur=42"
                    + "|in={userInput=hello}|out={output=world}|llm=|tools="
                    + "|actions=send_message|cost=0.005|ts=2026-01-01T00:00:00Z",
                    AuditHmac.buildCanonicalString(createTestEntry()));
        }

        @Test
        @DisplayName("nested map insertion order does not change the HMAC")
        void nestedMapProducesSortedDeterministicString() {
            Map<String, Object> ascending = new LinkedHashMap<>();
            ascending.put("inputTokens", 10);
            ascending.put("outputTokens", 20);

            Map<String, Object> descending = new LinkedHashMap<>();
            descending.put("outputTokens", 20);
            descending.put("inputTokens", 10);

            assertEquals(
                    AuditHmac.computeHmac(entryWith(Map.of("tokenUsage", ascending), null), hmacKey),
                    AuditHmac.computeHmac(entryWith(Map.of("tokenUsage", descending), null), hmacKey));
        }

        @Test
        @DisplayName("a nested LinkedHashMap and a nested org.bson.Document hash identically")
        void bsonDocumentAndLinkedHashMapHashIdentically() {
            Map<String, Object> asMap = new LinkedHashMap<>();
            asMap.put("modelName", "gpt-4o");
            asMap.put("tokenUsage", tokenUsage());

            Map<String, Object> asDocument = new LinkedHashMap<>();
            asDocument.put("modelName", "gpt-4o");
            asDocument.put("tokenUsage", new Document(tokenUsage()));

            assertEquals(
                    AuditHmac.computeHmac(entryWith(asMap, null), hmacKey),
                    AuditHmac.computeHmac(entryWith(asDocument, null), hmacKey));
        }

        @Test
        @DisplayName("toolCalls list of maps hashes identically to a list of Documents")
        void nestedListOfMapsHashesDeterministically() {
            Map<String, Object> call = new LinkedHashMap<>();
            call.put("tool", "calculator");
            call.put("result", "42");

            assertEquals(
                    AuditHmac.computeHmac(entryWith(null, Map.of("calls", List.of(call))), hmacKey),
                    AuditHmac.computeHmac(entryWith(null, Map.of("calls", List.of(new Document(call)))), hmacKey));
        }

        @Test
        @DisplayName("null counts inside a nested map do not throw and canonicalize as empty")
        void nullValueInNestedMapDoesNotThrow() {
            Map<String, Object> partial = new LinkedHashMap<>();
            partial.put("inputTokens", null);
            partial.put("outputTokens", 20);

            String canonical = assertDoesNotThrow(
                    () -> AuditHmac.buildCanonicalString(entryWith(Map.of("tokenUsage", partial), null)));
            assertTrue(canonical.contains("llm={tokenUsage={inputTokens=,outputTokens=20}}"), canonical);
        }

        /**
         * The real defect this recursion closes: entries are signed in memory with
         * nested {@link LinkedHashMap}s but read back from Mongo with nested
         * {@link Document}s ({@code AuditStore.fromDocument} copies only the top
         * level). BSON-encoding and decoding the stored document reproduces exactly
         * that, so the stored HMAC must still verify afterwards.
         */
        @Test
        @DisplayName("HMAC still verifies after a real BSON round-trip of nested llmDetail/toolCalls")
        void nestedMapsSurviveBsonRoundTrip() {
            Map<String, Object> llmDetail = new LinkedHashMap<>();
            llmDetail.put("compiledPrompt", "you are helpful");
            llmDetail.put("tokenUsage", tokenUsage());

            Map<String, Object> call = new LinkedHashMap<>();
            call.put("tool", "calculator");
            call.put("llmTaskId", "task-1");
            Map<String, Object> toolCalls = new LinkedHashMap<>();
            toolCalls.put("calls", List.of(call));

            AuditEntry written = entryWith(llmDetail, toolCalls);
            AuditEntry signed = written.withHmac(AuditHmac.computeHmac(written, hmacKey));

            AuditEntry readBack = new AuditEntry(signed.id(), signed.conversationId(), signed.agentId(), signed.agentVersion(),
                    signed.userId(), signed.environment(), signed.stepIndex(), signed.taskId(), signed.taskType(),
                    signed.taskIndex(), signed.durationMs(), signed.input(), signed.output(),
                    bsonRoundTrip(signed.llmDetail()), bsonRoundTrip(signed.toolCalls()),
                    signed.actions(), signed.cost(), signed.timestamp(), signed.hmac(), signed.agentSignature());

            assertInstanceOf(Document.class, readBack.llmDetail().get("tokenUsage"),
                    "round-trip must reproduce the nested org.bson.Document the store returns");
            assertTrue(AuditHmac.verifyHmac(readBack, hmacKey),
                    "stored HMAC must survive the Mongo document round-trip");
        }

        /**
         * Encodes a map to BSON and decodes it back, mirroring what the Mongo driver
         * does on write/read, then applies the shallow copy
         * {@code AuditStore.fromDocument} performs.
         */
        private Map<String, Object> bsonRoundTrip(Map<String, Object> map) {
            Document encoded = new Document(map);
            BsonDocument bson = encoded.toBsonDocument(BsonDocument.class, MongoClientSettings.getDefaultCodecRegistry());
            Document decoded = new DocumentCodec().decode(new BsonDocumentReader(bson), DecoderContext.builder().build());
            return new LinkedHashMap<>(decoded);
        }
    }

    // ==================== Canonical-form injectivity (D3) ====================

    /**
     * The v1 canonical string joins keys and values with {@code = , { } [ ]} and
     * escapes none of them, so the map-to-string mapping is not injective: two
     * structurally different entries collapse onto the same bytes and therefore
     * share one valid HMAC. An entry tampered into its twin verifies as intact.
     * <p>
     * That is reachable now that {@code toolCalls} carries tool-trace
     * {@code arguments}/{@code result} strings, which the model and the user write.
     */
    @Nested
    @DisplayName("canonical-form injectivity")
    class InjectivityTests {

        private AuditEntry entryWithToolCalls(Map<String, Object> toolCalls) {
            return new AuditEntry(
                    "entry-1", "conv-1", "agent-1", 1, "user-1", "production",
                    0, "ai.labs.llm", "langchain", 0, 42L,
                    Map.of("userInput", "hello"),
                    Map.of("output", "world"),
                    null, toolCalls,
                    List.of("send_message"), 0.005,
                    Instant.parse("2026-01-01T00:00:00Z"), null, null);
        }

        /** Two keys, versus one key whose value re-creates the separator. */
        private final Map<String, Object> twoEntries = Map.of("a", "x", "b", "y");
        private final Map<String, Object> oneCraftedEntry = Map.of("a", "x,b=y");

        @Test
        @DisplayName("the v1 form really does collide (this is what v2 exists to fix)")
        void v1CanonicalStringCollides() {
            assertEquals(
                    AuditHmac.buildCanonicalString(entryWithToolCalls(twoEntries)),
                    AuditHmac.buildCanonicalString(entryWithToolCalls(oneCraftedEntry)),
                    "if this stops holding the v1 form was changed, which invalidates every stored HMAC");
        }

        @Test
        @DisplayName("structurally different maps get different HMACs under v2")
        void differentStructuresDoNotCollide() {
            assertNotEquals(
                    AuditHmac.computeHmac(entryWithToolCalls(twoEntries), hmacKey),
                    AuditHmac.computeHmac(entryWithToolCalls(oneCraftedEntry), hmacKey));
        }

        /**
         * The attack the collision enables: swap the stored {@code toolCalls} for a
         * structurally different map that canonicalizes identically and the untouched
         * HMAC still verifies.
         */
        @Test
        @DisplayName("an entry tampered into its v1 twin no longer verifies")
        void tamperingIntoAColludingTwinIsRejected() {
            AuditEntry original = entryWithToolCalls(twoEntries);
            String hmac = AuditHmac.computeHmac(original, hmacKey);

            AuditEntry tampered = entryWithToolCalls(oneCraftedEntry).withHmac(hmac);

            assertFalse(AuditHmac.verifyHmac(tampered, hmacKey),
                    "a different toolCalls structure must not verify under the original entry's HMAC");
        }

        /**
         * A scalar whose text mimics the rendering of a nested list of maps must not
         * canonicalize like the real thing — that is what the type tags are for.
         */
        @Test
        @DisplayName("a string that looks like a nested structure does not collide with one")
        void scalarMimickingNestedStructureDoesNotCollide() {
            Map<String, Object> real = Map.of("calls", List.of(Map.of("tool", "calculator")));
            Map<String, Object> mimic = Map.of("calls", "[{tool=calculator}]");

            assertEquals(
                    AuditHmac.buildCanonicalString(entryWithToolCalls(real)),
                    AuditHmac.buildCanonicalString(entryWithToolCalls(mimic)),
                    "precondition: the v1 form cannot tell them apart");
            assertNotEquals(
                    AuditHmac.computeHmac(entryWithToolCalls(real), hmacKey),
                    AuditHmac.computeHmac(entryWithToolCalls(mimic), hmacKey));
        }

        @Test
        @DisplayName("an action list is not interchangeable with one comma-joined action")
        void actionListSeparatorIsNotForgeable() {
            AuditEntry twoActions = withActions(List.of("send_message", "log_it"));
            AuditEntry oneAction = withActions(List.of("send_message,log_it"));

            assertEquals(AuditHmac.buildCanonicalString(twoActions), AuditHmac.buildCanonicalString(oneAction),
                    "precondition: String.join collapses both onto 'send_message,log_it'");
            assertNotEquals(
                    AuditHmac.computeHmac(twoActions, hmacKey),
                    AuditHmac.computeHmac(oneAction, hmacKey));
        }

        private AuditEntry withActions(List<String> actions) {
            return new AuditEntry(
                    "entry-1", "conv-1", "agent-1", 1, "user-1", "production",
                    0, "ai.labs.llm", "langchain", 0, 42L,
                    Map.of("userInput", "hello"), Map.of("output", "world"),
                    null, null, actions, 0.005,
                    Instant.parse("2026-01-01T00:00:00Z"), null, null);
        }

        /**
         * The back-compat half of the version tag. Rows written before v2 carry a bare
         * hex digest over the v1 canonical string; they must keep verifying, or every
         * historical ledger entry reads as tampered on the first upgraded start-up.
         */
        @Test
        @DisplayName("a stored v1 entry (bare hex, v1 canonical form) still verifies")
        void legacyV1EntryStillVerifies() {
            AuditEntry entry = createTestEntry();
            String legacyHmac = legacySign(AuditHmac.buildCanonicalString(entry));

            assertFalse(legacyHmac.startsWith("v2:"), "a v1 row carries no version tag");
            assertTrue(AuditHmac.verifyHmac(entry.withHmac(legacyHmac), hmacKey),
                    "pre-v2 ledger rows must keep verifying against the v1 canonicalizer");
        }

        @Test
        @DisplayName("a tampered v1 entry is still rejected")
        void legacyV1EntryTamperedIsRejected() {
            AuditEntry entry = createTestEntry();
            String legacyHmac = legacySign(AuditHmac.buildCanonicalString(entry));

            assertFalse(AuditHmac.verifyHmac(entry.withEnvironment("TAMPERED").withHmac(legacyHmac), hmacKey));
        }

        /** Reproduces exactly what the pre-v2 code wrote into the ledger. */
        private String legacySign(String canonical) {
            try {
                Mac mac = Mac.getInstance("HmacSHA256");
                mac.init(new SecretKeySpec(hmacKey, "HmacSHA256"));
                return HexFormat.of().formatHex(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
            } catch (GeneralSecurityException e) {
                throw new IllegalStateException(e);
            }
        }
    }
}
