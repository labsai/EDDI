/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.hitl;

import ai.labs.eddi.configs.groups.model.GroupConversation;
import ai.labs.eddi.configs.groups.model.GroupConversation.TranscriptEntry;
import ai.labs.eddi.configs.groups.model.GroupConversation.TranscriptEntryType;
import ai.labs.eddi.configs.groups.model.GroupConversation.HitlPauseType;
import ai.labs.eddi.configs.groups.model.SharedTaskList;
import ai.labs.eddi.datastore.serialization.SerializationCustomizer;
import ai.labs.eddi.engine.lifecycle.model.HitlDecision;
import ai.labs.eddi.engine.memory.ConversationMemory;
import ai.labs.eddi.engine.memory.ConversationMemoryUtilities;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot;
import ai.labs.eddi.engine.memory.model.ConversationState;
import ai.labs.eddi.engine.memory.model.Data;
import ai.labs.eddi.engine.memory.model.PendingToolCallBatch;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import de.undercouch.bson4jackson.BsonFactory;
import de.undercouch.bson4jackson.BsonParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static ai.labs.eddi.engine.memory.MemoryKeys.ACTIONS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("HitlPlanHash — the canonical plan hash of a pending approval")
class HitlPlanHashTest {

    static PendingToolCallBatch.PendingToolCall call(String callId, String toolName, String args) {
        var call = new PendingToolCallBatch.PendingToolCall();
        call.setCallId(callId);
        call.setToolName(toolName);
        call.setSource("http");
        call.setArgumentsRaw(args);
        call.setArgumentsRedacted(args);
        return call;
    }

    static PendingToolCallBatch batch(PendingToolCallBatch.PendingToolCall... calls) {
        var batch = new PendingToolCallBatch();
        batch.setPauseEpoch("epoch-1");
        batch.setPlanNonce("nonce-1");
        batch.setCalls(new ArrayList<>(List.of(calls)));
        return batch;
    }

    static String hash(PendingToolCallBatch batch) {
        return HitlPlanHash.forToolBatch("conv-1", "agent-1", 3, batch);
    }

    @Nested
    @DisplayName("canonical JSON")
    class Canonical {

        @Test
        @DisplayName("object keys are sorted at every depth, arrays keep their order, whitespace is dropped")
        void keyOrderAndNesting() throws Exception {
            assertEquals("{\"a\":{\"x\":[3,1,{\"m\":true,\"n\":null}],\"y\":\"s\"},\"b\":1}",
                    HitlPlanHash.canonicalJson("{ \"b\" : 1, \"a\" : { \"y\":\"s\", \"x\" : [3, 1, {\"n\":null,\"m\":true}] } }"));
            assertNotEquals(HitlPlanHash.canonicalJson("[1,2]"), HitlPlanHash.canonicalJson("[2,1]"));
        }

        @ParameterizedTest(name = "{0} -> {1}")
        @CsvSource({"1, 1", "1.0, 1", "1.00, 1", "1e0, 1", "10e-1, 1", "0.10, 0.1", "-0, 0", "-0.0, 0", "100, 100", "1e2, 100",
                "1E+2, 100", "-12.500, -12.5", "123456789012345678901234567890, 123456789012345678901234567890", "1e500, 1E+500",
                "10e499, 1E+500"})
        @DisplayName("numbers are canonicalized by value")
        void numbersByValue(String input, String expected) throws Exception {
            assertEquals(expected, HitlPlanHash.canonicalJson(input));
            assertEquals(expected, HitlPlanHash.canonicalNumber(new BigDecimal(input)));
        }

        @Test
        @DisplayName("an escaped and a literal character are the same string; composed and decomposed forms are not")
        void unicode() throws Exception {
            assertEquals(HitlPlanHash.canonicalJson("{\"name\":\"caf\\u00e9\"}"), HitlPlanHash.canonicalJson("{\"name\":\"café\"}"));
            assertEquals(HitlPlanHash.canonicalJson("\"\\ud83d\\ude00\""), HitlPlanHash.canonicalJson("\"😀\""));
            assertNotEquals(HitlPlanHash.canonicalJson("\"café\""), HitlPlanHash.canonicalJson("\"café\""));
            // Non-ASCII keys sort by UTF-16 code unit, like RFC 8785.
            assertEquals("{\"Z\":1,\"a\":2,\"é\":3}", HitlPlanHash.canonicalJson("{\"é\":3,\"a\":2,\"Z\":1}"));
        }

        @Test
        @DisplayName("a repeated key and a non-JSON text are refused")
        void refusals() {
            assertThrows(JsonProcessingException.class, () -> HitlPlanHash.canonicalJson("{\"a\":1,\"a\":2}"));
            assertThrows(JsonProcessingException.class, () -> HitlPlanHash.canonicalJson("not json"));
            assertThrows(JsonProcessingException.class, () -> HitlPlanHash.canonicalJson(""));
            assertThrows(JsonProcessingException.class, () -> HitlPlanHash.canonicalJson("{\"amount\":1} {\"amount\":9999}"));
            assertThrows(JsonProcessingException.class, () -> HitlPlanHash.canonicalJson("{\"amount\":1} trailing"));
        }
    }

    @Nested
    @DisplayName("tool-call plans")
    class ToolCalls {

        @Test
        @DisplayName("the format is v1: and 64 hex digits, and the hash is stable")
        void format() {
            String h = hash(batch(call("c1", "sendEmail", "{\"to\":\"a@b.c\"}")));
            assertTrue(h.matches("v1:[0-9a-f]{64}"), h);
            assertEquals(h, hash(batch(call("c1", "sendEmail", "{\"to\":\"a@b.c\"}"))));
        }

        @Test
        @DisplayName("a re-serialization of the same arguments hashes alike")
        void reencodingIsTheSamePlan() {
            String a = hash(batch(call("c1", "t", "{\"amount\":1,\"to\":{\"iban\":\"X\",\"name\":\"caf\\u00e9\"},\"tags\":[\"a\",\"b\"]}")));
            String b = hash(batch(call("c1", "t", "{ \"tags\":[\"a\",\"b\"], \"to\":{\"name\":\"café\",\"iban\":\"X\"}, \"amount\":1.0 }")));
            assertEquals(a, b);
        }

        @Test
        @DisplayName("every part of the plan changes the hash")
        void everyPartCounts() {
            var base = batch(call("c1", "transfer", "{\"amount\":250}"), call("c2", "notify", "{}"));
            String h = hash(base);

            assertNotEquals(h, hash(batch(call("c1", "transfer", "{\"amount\":251}"), call("c2", "notify", "{}"))), "argument value");
            assertNotEquals(h, hash(batch(call("c1", "transfer", "{\"amount\":250,\"x\":1}"), call("c2", "notify", "{}"))), "extra argument");
            assertNotEquals(h, hash(batch(call("c1", "transfer2", "{\"amount\":250}"), call("c2", "notify", "{}"))), "tool name");
            assertNotEquals(h, hash(batch(call("c2", "notify", "{}"), call("c1", "transfer", "{\"amount\":250}"))), "call order");
            assertNotEquals(h, hash(batch(call("cX", "transfer", "{\"amount\":250}"), call("c2", "notify", "{}"))), "call id");
            assertNotEquals(h, hash(batch(call("c1", "transfer", "{\"amount\":250}"))), "a call fewer");

            assertNotEquals(h, HitlPlanHash.forToolBatch("conv-2", "agent-1", 3, base), "conversation");
            assertNotEquals(h, HitlPlanHash.forToolBatch("conv-1", "agent-2", 3, base), "agent");
            assertNotEquals(h, HitlPlanHash.forToolBatch("conv-1", "agent-1", 4, base), "agent version");
            assertNotEquals(h, HitlPlanHash.forToolBatch("conv-1", "agent-1", null, base), "no agent version");

            var other = batch(call("c1", "transfer", "{\"amount\":250}"), call("c2", "notify", "{}"));
            other.setPlanNonce("nonce-2");
            assertNotEquals(h, hash(other), "nonce");
            other.setPlanNonce("nonce-1");
            other.setPauseEpoch("epoch-2");
            assertNotEquals(h, hash(other), "pause epoch");
            other.setPauseEpoch("epoch-1");
            other.getCalls().get(0).setRequestFingerprint("fp");
            assertNotEquals(h, hash(other), "resolved request");
            other.getCalls().get(0).setRequestFingerprint(null);
            other.getCalls().get(0).setSource("mcp");
            assertNotEquals(h, hash(other), "source");
            other.getCalls().get(0).setSource("http");
            other.getCalls().get(0).setArgsTruncated(true);
            assertNotEquals(h, hash(other), "truncation");
            other.getCalls().get(0).setArgsTruncated(false);
            assertEquals(h, hash(other), "back to the original plan");
        }

        @Test
        @DisplayName("only the raw arguments count — the redacted copy shown to approvers does not")
        void redactedArgumentsDoNotCount() {
            var a = batch(call("c1", "t", "{\"key\":\"sk-live-1\"}"));
            var b = batch(call("c1", "t", "{\"key\":\"sk-live-2\"}"));
            a.getCalls().get(0).setArgumentsRedacted("{\"key\":\"[REDACTED]\"}");
            b.getCalls().get(0).setArgumentsRedacted("{\"key\":\"[REDACTED]\"}");
            assertNotEquals(hash(a), hash(b));
        }

        @Test
        @DisplayName("arguments that are not one JSON document are hashed as their exact text")
        void textArguments() {
            String dup = hash(batch(call("c1", "t", "{\"a\":1,\"a\":2}")));
            assertNotEquals(dup, hash(batch(call("c1", "t", "{\"a\":2}"))), "a repeated key is not its last value");
            assertNotEquals(hash(batch(call("c1", "t", "not json"))), hash(batch(call("c1", "t", "not  json"))));
            assertNotEquals(hash(batch(call("c1", "t", "\"x\""))), hash(batch(call("c1", "t", "x"))),
                    "a JSON string and the same raw text do not collide");
            assertNotNull(hash(batch(call("c1", "t", null))));
            // Anything after the first value is part of the plan: two documents are
            // hashed as text, so neither the second value nor its change is lost.
            String single = hash(batch(call("c1", "t", "{\"amount\":1}")));
            String trailing = hash(batch(call("c1", "t", "{\"amount\":1} {\"amount\":9999}")));
            assertNotEquals(single, trailing, "a trailing document must not be dropped from the plan");
            assertNotEquals(trailing, hash(batch(call("c1", "t", "{\"amount\":1} {\"amount\":1}"))));
            assertEquals(single, hash(batch(call("c1", "t", " {\"amount\":1}\n"))), "surrounding whitespace is not a trailing token");
        }

        @Test
        @DisplayName("no batch, no hash")
        void noBatch() {
            assertNull(HitlPlanHash.forToolBatch("c", "a", 1, null));
        }
    }

    @Nested
    @DisplayName("rule (turn-level) plans")
    class Rules {

        @Test
        @DisplayName("PAUSE_CONVERSATION is not part of the plan; the other actions are, in order")
        void actions() {
            String h = HitlPlanHash.forRulePause("conv-1", "agent-1", 1, "1000", "wf", 2, List.of("PAUSE_CONVERSATION", "delete", "notify"));
            assertEquals(h, HitlPlanHash.forRulePause("conv-1", "agent-1", 1, "1000", "wf", 2, List.of("delete", "notify")));
            assertNotEquals(h, HitlPlanHash.forRulePause("conv-1", "agent-1", 1, "1000", "wf", 2, List.of("notify", "delete")));
            assertNotEquals(h, HitlPlanHash.forRulePause("conv-1", "agent-1", 1, "1000", "wf", 2, List.of("delete")));
            assertNotEquals(h, HitlPlanHash.forRulePause("conv-1", "agent-1", 1, "1001", "wf", 2, List.of("delete", "notify")), "pause id");
            assertNotEquals(h, HitlPlanHash.forRulePause("conv-1", "agent-1", 1, "1000", "wf", 3, List.of("delete", "notify")), "task");
            assertNotEquals(h, HitlPlanHash.forRulePause("conv-1", "agent-1", 1, "1000", "wf2", 2, List.of("delete", "notify")), "workflow");
        }

        @Test
        @DisplayName("a rule plan and a tool plan never collide")
        void kindsDiffer() {
            assertNotEquals(HitlPlanHash.forRulePause("conv-1", "agent-1", 3, null, null, -1, List.of()),
                    HitlPlanHash.forToolBatch("conv-1", "agent-1", 3, batch()));
        }
    }

    @Nested
    @DisplayName("commit at pause time")
    class Commit {

        @Test
        @DisplayName("a tool pause gets one nonce, and the same hash on the memory and the batch")
        void toolPause() {
            var memory = new ConversationMemory("conv-1", "agent-1", 3, "user-1");
            memory.setConversationState(ConversationState.AWAITING_HUMAN);
            memory.setHitlPausedAt(Instant.ofEpochMilli(1000));
            memory.setHitlPauseType("TOOL_CALL");
            var batch = batch(call("c1", "t", "{\"a\":1}"));
            batch.setPlanNonce(null);
            memory.setHitlPendingToolCalls(batch);

            String h = HitlPlanHash.commit(memory);
            assertNotNull(h);
            assertNotNull(batch.getPlanNonce());
            assertEquals(h, memory.getHitlPlanHash());
            assertEquals(h, batch.getPlanHash());
            assertEquals(h, HitlPlanHash.forPause(memory));

            String nonce = batch.getPlanNonce();
            assertEquals(h, HitlPlanHash.commit(memory), "committing again keeps the nonce and the hash");
            assertEquals(nonce, batch.getPlanNonce());
        }

        @Test
        @DisplayName("a rule pause hashes its bookmark and actions, and needs no nonce")
        void rulePause() {
            var memory = new ConversationMemory("conv-1", "agent-1", 3, "user-1");
            memory.getCurrentStep().storeData(new Data<>(ACTIONS.key(), List.of("delete_account", "PAUSE_CONVERSATION")));
            memory.setHitlPausedAt(Instant.ofEpochMilli(1234));
            memory.setHitlPausedWorkflowId("wf-1");
            memory.setHitlPausedAbsoluteTaskIndex(2);
            memory.setHitlPauseType("RULE");

            String h = HitlPlanHash.commit(memory);
            assertEquals(HitlPlanHash.forRulePause("conv-1", "agent-1", 3, "1234", "wf-1", 2, List.of("delete_account")), h);
        }

        @Test
        @DisplayName("a pause's id is its millisecond — nanoseconds a store drops do not change the hash")
        void millisecondPauseId() {
            var memory = new ConversationMemory("conv-1", "agent-1", 3, "user-1");
            memory.setHitlPausedAt(Instant.ofEpochSecond(1, 2_000_123));
            memory.setHitlPauseType("RULE");
            String h = HitlPlanHash.forPause(memory);
            memory.setHitlPausedAt(Instant.ofEpochMilli(1002));
            assertEquals(h, HitlPlanHash.forPause(memory));
        }

        @Test
        @DisplayName("no pause, no hash")
        void noPause() {
            var memory = new ConversationMemory("conv-1", "agent-1", 3, "user-1");
            assertNull(HitlPlanHash.commit(memory));
            assertNull(memory.getHitlPlanHash());
        }
    }

    /** A tool pause whose plan cannot be hashed: reading its calls fails. */
    static ConversationMemory unhashableToolPause() {
        return unhashableToolPause("conv-1");
    }

    static ConversationMemory unhashableToolPause(String conversationId) {
        var memory = new ConversationMemory(conversationId, "agent-1", 3, "user-1");
        memory.setConversationState(ConversationState.AWAITING_HUMAN);
        memory.setHitlPausedAt(Instant.ofEpochMilli(1000));
        memory.setHitlPauseType("TOOL_CALL");
        var broken = new PendingToolCallBatch() {
            @Override
            public List<PendingToolCall> getCalls() {
                throw new IllegalStateException("secret-argument-value");
            }
        };
        broken.setPauseEpoch("epoch-1");
        memory.setHitlPendingToolCalls(broken);
        return memory;
    }

    static HitlDecision humanDecision(String planHash) {
        var decision = new HitlDecision();
        decision.setVerdict(HitlDecision.HitlVerdict.APPROVED);
        decision.setDecidedBy("reviewer-1");
        decision.setPlanHash(planHash);
        return decision;
    }

    @Nested
    @DisplayName("a pause whose plan hash could not be computed")
    class Unavailable {

        @Test
        @DisplayName("commit stores the UNAVAILABLE marker on the memory and the batch, not null")
        void commitMarksUnavailable() {
            var memory = unhashableToolPause();

            String committed = HitlPlanHash.commit(memory);

            assertEquals(HitlPlanHash.UNAVAILABLE, committed);
            assertEquals(HitlPlanHash.UNAVAILABLE, memory.getHitlPlanHash(), "distinct from a legacy pause's null");
            assertEquals(HitlPlanHash.UNAVAILABLE, memory.getHitlPendingToolCalls().getPlanHash());
        }

        @Test
        @DisplayName("strict binding refuses a human decision on it (fail closed), without leaking the failure")
        void strictRefuses() {
            var refused = assertThrows(HitlPlanBinding.PlanMismatch.class,
                    () -> HitlPlanBinding.check("conv-1", humanDecision(null), HitlPlanHash.UNAVAILABLE, () -> true));
            assertTrue(refused.getMessage().contains("could not be bound to a plan hash"), refused.getMessage());
            assertFalse(refused.getMessage().contains("unavailable"), "the marker is not part of the client message");
        }

        @Test
        @DisplayName("non-strict accepts it as PLAN_HASH_UNAVAILABLE — not LEGACY_UNHASHED")
        void nonStrictAccepts() {
            assertEquals(HitlPlanBinding.Outcome.PLAN_HASH_UNAVAILABLE,
                    HitlPlanBinding.check("conv-1", humanDecision(null), HitlPlanHash.UNAVAILABLE, () -> false));
        }

        @Test
        @DisplayName("a presented hash, even the marker itself, is refused; system decisions stay exempt")
        void presentedAndSystem() {
            assertThrows(HitlPlanBinding.PlanMismatch.class,
                    () -> HitlPlanBinding.check("conv-1", humanDecision("v1:" + "0".repeat(64)), HitlPlanHash.UNAVAILABLE, () -> false));
            assertThrows(HitlPlanBinding.PlanMismatch.class,
                    () -> HitlPlanBinding.check("conv-1", humanDecision(HitlPlanHash.UNAVAILABLE), HitlPlanHash.UNAVAILABLE, () -> false));
            var system = humanDecision(null);
            system.setDecidedBy("system:timeout");
            assertEquals(HitlPlanBinding.Outcome.SYSTEM, HitlPlanBinding.check("conv-1", system, HitlPlanHash.UNAVAILABLE, () -> true));
        }

        @Test
        @DisplayName("a legacy pause (no hash at all) is unchanged: accepted even under strict binding")
        void legacyUnchanged() {
            assertEquals(HitlPlanBinding.Outcome.LEGACY_UNHASHED,
                    HitlPlanBinding.check("conv-1", humanDecision(null), null, () -> true));
            assertTrue(HitlPlanBinding.verifies(null, "v1:" + "0".repeat(64)));
        }

        @Test
        @DisplayName("the marker is never served as a hash and never matches")
        void neverServedNorMatched() {
            assertNull(HitlPlanHash.presentable(HitlPlanHash.UNAVAILABLE));
            assertNull(HitlPlanHash.presentable(null));
            assertEquals("v1:abc", HitlPlanHash.presentable("v1:abc"));
            assertFalse(HitlPlanHash.matches(HitlPlanHash.UNAVAILABLE, HitlPlanHash.UNAVAILABLE));
            assertTrue(HitlPlanBinding.verifies(HitlPlanHash.UNAVAILABLE, "v1:" + "0".repeat(64)),
                    "nothing to re-derive against; check() is the gate");
        }

        @Test
        @DisplayName("the marker survives the snapshot conversion and both stores' serializers (MongoDB BSON, PostgreSQL JSON)")
        void survivesPersistence() throws Exception {
            // A stored id is an ObjectId (the BSON codec maps _id to one).
            var memory = unhashableToolPause("65a1b2c3d4e5f60718293a4b");
            HitlPlanHash.commit(memory);
            // The conversion copies the batch's fields, which the broken test batch cannot
            // provide; store the marker on a plain batch exactly as commit left it.
            var batch = batch(call("c1", "t", "{\"a\":1}"));
            batch.setPlanHash(memory.getHitlPendingToolCalls().getPlanHash());
            memory.setHitlPendingToolCalls(batch);
            ConversationMemorySnapshot snapshot = ConversationMemoryUtilities.convertConversationMemory(memory);
            assertEquals(HitlPlanHash.UNAVAILABLE, snapshot.getHitlPlanHash());

            BsonFactory bsonFactory = new BsonFactory();
            bsonFactory.enable(BsonParser.Feature.HONOR_DOCUMENT_LENGTH);
            var bson = new ObjectMapper(bsonFactory);
            bson.registerModule(new JavaTimeModule());
            bson.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
            new SerializationCustomizer(false).customize(bson);
            var json = new ObjectMapper();
            json.registerModule(new JavaTimeModule());
            SerializationCustomizer.configureObjectMapper(json, false);

            for (ObjectMapper mapper : List.of(bson, json)) {
                var loaded = mapper.readValue(mapper.writeValueAsBytes(snapshot), ConversationMemorySnapshot.class);
                assertEquals(HitlPlanHash.UNAVAILABLE, loaded.getHitlPlanHash());
                assertEquals(HitlPlanHash.UNAVAILABLE, loaded.getHitlPendingToolCalls().getPlanHash());
                var resumed = ConversationMemoryUtilities.convertConversationMemorySnapshot(loaded);
                assertEquals(HitlPlanHash.UNAVAILABLE, resumed.getHitlPlanHash(), "the resume sees the marker, not a legacy pause");
                assertEquals(HitlPlanBinding.Outcome.PLAN_HASH_UNAVAILABLE,
                        HitlPlanBinding.check("conv-1", humanDecision(null), resumed.getHitlPlanHash(), () -> false));
            }
        }
    }

    @Nested
    @DisplayName("group plans")
    class Groups {

        private GroupConversation pausedGroup() {
            var gc = new GroupConversation();
            gc.setId("gc-1");
            gc.setGroupId("group-1");
            gc.setPausedAt(Instant.ofEpochMilli(5000));
            gc.setPausedAtPhaseIndex(1);
            gc.setPausedPhaseName("Execution");
            gc.setHitlPauseType(HitlPauseType.TASK);
            gc.getTranscript().add(entry("a", "opening", 0));
            gc.getTranscript().add(entry("b", "the work", 1));
            var tasks = new SharedTaskList();
            gc.setTaskList(tasks);
            return gc;
        }

        private TranscriptEntry entry(String speaker, String content, int phase) {
            return new TranscriptEntry(speaker, speaker, content, phase, "p" + phase, TranscriptEntryType.OPINION, Instant.EPOCH, null, null);
        }

        @Test
        @DisplayName("covers the paused phase's contributions, not earlier phases'")
        void transcript() {
            var gc = pausedGroup();
            String h = HitlPlanHash.forGroup(gc);
            assertNotNull(h);
            assertEquals(h, HitlPlanHash.forGroup(pausedGroup()));

            var earlierChanged = pausedGroup();
            earlierChanged.getTranscript().set(0, entry("a", "rewritten opening", 0));
            assertEquals(h, HitlPlanHash.forGroup(earlierChanged));

            var workChanged = pausedGroup();
            workChanged.getTranscript().set(1, entry("b", "different work", 1));
            assertNotEquals(h, HitlPlanHash.forGroup(workChanged));

            var answered = pausedGroup();
            answered.setSynthesizedAnswer("final");
            assertNotEquals(h, HitlPlanHash.forGroup(answered));

            var otherPause = pausedGroup();
            otherPause.setPausedAt(Instant.ofEpochMilli(5001));
            assertNotEquals(h, HitlPlanHash.forGroup(otherPause));
        }

        @Test
        @DisplayName("not paused, no hash")
        void notPaused() {
            var gc = pausedGroup();
            gc.setPausedAt(null);
            assertNull(HitlPlanHash.forGroup(gc));
            assertNull(HitlPlanHash.forGroup(null));
        }
    }

    @Test
    @DisplayName("matches compares exactly (trimmed), and nothing matches null or blank")
    void matches() {
        assertTrue(HitlPlanHash.matches("v1:ab", "v1:ab"));
        assertTrue(HitlPlanHash.matches("v1:ab", " v1:ab "));
        assertFalse(HitlPlanHash.matches("v1:ab", "v1:AB"));
        assertFalse(HitlPlanHash.matches("v1:ab", "v1:abc"));
        assertFalse(HitlPlanHash.matches(null, null));
        assertFalse(HitlPlanHash.matches("v1:ab", null));
        assertFalse(HitlPlanHash.matches("", ""));
    }

    @Test
    @DisplayName("nonces are random and URL-safe")
    void nonces() {
        String a = HitlPlanHash.newNonce();
        assertNotEquals(a, HitlPlanHash.newNonce());
        assertTrue(a.matches("[A-Za-z0-9_-]{22}"), a);
    }
}
