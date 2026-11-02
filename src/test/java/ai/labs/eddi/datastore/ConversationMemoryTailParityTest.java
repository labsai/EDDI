/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.datastore;

import ai.labs.eddi.configs.hitl.HitlTimeoutPolicy;
import ai.labs.eddi.configs.properties.model.Property;
import ai.labs.eddi.datastore.mongo.MongoTestBase;
import ai.labs.eddi.datastore.mongo.codec.JacksonProvider;
import ai.labs.eddi.datastore.postgres.PostgresConversationMemoryStore;
import ai.labs.eddi.datastore.postgres.PostgresTestBase;
import ai.labs.eddi.datastore.serialization.SerializationCustomizer;
import ai.labs.eddi.engine.memory.ConversationMemoryStore;
import ai.labs.eddi.engine.memory.ConversationMemoryTail;
import ai.labs.eddi.engine.memory.ConversationMemoryUtilities;
import ai.labs.eddi.engine.memory.IConversationMemoryStore;
import ai.labs.eddi.engine.memory.MemoryKeys;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot.ConversationStepSnapshot;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot.ResultSnapshot;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot.WorkflowRunSnapshot;
import ai.labs.eddi.engine.memory.model.ConversationOutput;
import ai.labs.eddi.engine.memory.model.ConversationState;
import ai.labs.eddi.engine.memory.model.PendingToolCallBatch;
import ai.labs.eddi.engine.memory.model.PendingToolCallBatch.PendingToolCall;
import ai.labs.eddi.engine.memory.model.PendingToolCallBatch.ResolvedRequestPreview;
import ai.labs.eddi.engine.model.Context;
import ai.labs.eddi.engine.model.Deployment;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.mongodb.client.model.Filters;
import de.undercouch.bson4jackson.BsonFactory;
import de.undercouch.bson4jackson.BsonParser;
import org.bson.Document;
import org.bson.codecs.configuration.CodecRegistries;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The history-free reads answer exactly what the full reads answered, on both
 * backends.
 * <p>
 * {@link IConversationMemoryStore#loadConversationMemoryTail} and
 * {@link IConversationMemoryStore#loadConversationMemorySnapshotForApprover}
 * leave most of the stored document on the server. Their whole contract is that
 * no response can tell: for every representative conversation shape, the
 * current-step projection built from the tail must serialize to the same JSON
 * as the one built from the full snapshot, with every combination of
 * {@code returnDetailed} and {@code returningFields}, and the approver
 * projection of the slim approver read must equal the approver projection of
 * the full one.
 */
@DisplayName("Conversation tail reads — same responses as the full read, MongoDB and PostgreSQL")
class ConversationMemoryTailParityTest extends MongoTestBase {

    private static final String AGENT_ID = "6740832a2b0f614abcaee7aa";
    private static final List<List<String>> RETURNING_FIELDS = Arrays.asList(null, List.of(), List.of(""),
            List.of("conversationOutputs"), List.of("conversationSteps"), List.of("conversationProperties"),
            List.of("conversationSteps", "conversationProperties"), List.of("conversationOutputs", "conversationSteps", "conversationProperties"));

    private static ConversationMemoryStore mongoStore;
    private static PostgresConversationMemoryStore postgresStore;
    private static DataSource postgresDataSource;
    private static ObjectMapper json;

    @BeforeAll
    static void initStores() {
        // The production codec (PersistenceModule): a BSON-backed Jackson mapper.
        BsonFactory bsonFactory = new BsonFactory();
        bsonFactory.enable(BsonParser.Feature.HONOR_DOCUMENT_LENGTH);
        var bsonMapper = new ObjectMapper(bsonFactory);
        bsonMapper.registerModule(new JavaTimeModule());
        bsonMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        new SerializationCustomizer(false).customize(bsonMapper);
        mongoStore = new ConversationMemoryStore(getDatabase().withCodecRegistry(
                CodecRegistries.fromRegistries(getDatabase().getCodecRegistry(), CodecRegistries.fromProviders(new JacksonProvider(bsonMapper)))));

        var dataSourceInstance = PostgresTestBase.createDataSourceInstance();
        postgresDataSource = dataSourceInstance.get();
        postgresStore = new PostgresConversationMemoryStore(dataSourceInstance, jsonSerialization);

        // The REST layer's mapper configuration, so "same JSON" means same response
        // body.
        json = objectMapper;
    }

    static Stream<Arguments> conversations() {
        List<Arguments> cases = new ArrayList<>();
        for (String backend : List.of("mongo", "postgres")) {
            cases.add(Arguments.of(backend, "READY, 20 turns with spend and a redo entry",
                    (Supplier<ConversationMemorySnapshot>) () -> ready(20, true)));
            cases.add(Arguments.of(backend, "READY, one turn", (Supplier<ConversationMemorySnapshot>) () -> ready(1, false)));
            cases.add(Arguments.of(backend, "paused on a TOOL_CALL",
                    (Supplier<ConversationMemorySnapshot>) ConversationMemoryTailParityTest::toolCallPause));
            cases.add(Arguments.of(backend, "paused on a RULE", (Supplier<ConversationMemorySnapshot>) ConversationMemoryTailParityTest::rulePause));
            cases.add(Arguments.of(backend, "last turn sent as secret input",
                    (Supplier<ConversationMemorySnapshot>) ConversationMemoryTailParityTest::secretLastTurn));
            cases.add(Arguments.of(backend, "drifted: more steps than outputs",
                    (Supplier<ConversationMemorySnapshot>) ConversationMemoryTailParityTest::drifted));
            cases.add(Arguments.of(backend, "ERROR with a task error and no spend",
                    (Supplier<ConversationMemorySnapshot>) ConversationMemoryTailParityTest::errored));
        }
        return cases.stream();
    }

    @ParameterizedTest(name = "{0}: {1}")
    @MethodSource("conversations")
    @DisplayName("current-step projection from the tail = from the full snapshot")
    void currentStepProjectionMatches(String backend, String shape, Supplier<ConversationMemorySnapshot> conversation) throws Exception {
        var store = store(backend);
        String id = store.storeConversationMemorySnapshot(conversation.get());

        for (boolean detailed : List.of(false, true)) {
            for (List<String> fields : RETURNING_FIELDS) {
                var expected = ConversationMemoryUtilities.convertSimpleConversationMemorySnapshot(store.loadConversationMemorySnapshot(id), detailed,
                        true, fields);
                var actual = ConversationMemoryUtilities.convertSimpleConversationMemorySnapshot(store.loadConversationMemoryTail(id), detailed,
                        fields);
                assertEquals(json.writeValueAsString(expected), json.writeValueAsString(actual),
                        backend + " / " + shape + " / detailed=" + detailed + " / returningFields=" + fields);
            }
        }
    }

    @ParameterizedTest(name = "{0}: {1}")
    @MethodSource("conversations")
    @DisplayName("the tail is the full document minus exactly the documented cuts")
    void tailHoldsTheDocumentedFields(String backend, String shape, Supplier<ConversationMemorySnapshot> conversation) throws Exception {
        var store = store(backend);
        String id = store.storeConversationMemorySnapshot(conversation.get());

        var full = store.loadConversationMemorySnapshot(id);
        var tail = store.loadConversationMemoryTail(id);
        var reference = ConversationMemoryTail.ofFullSnapshot(full);

        assertEquals(reference.stepCount(), tail.stepCount(), "stepCount");
        assertEquals(reference.outputCount(), tail.outputCount(), "outputCount");
        assertEquals(reference.redoCount(), tail.redoCount(), "redoCount");
        assertEquals(reference.conversationCost(), tail.conversationCost(), "conversationCost (bit-identical)");
        assertTrue(tail.snapshot().getRedoCache().isEmpty(), "redo cache is not transferred");
        assertTrue(tail.snapshot().getConversationSteps().size() <= 1, "at most the last step is transferred");

        // Everything else — header, state, HITL bookmark, properties, last step and
        // output — is identical once the cut fields are cut from the full one too.
        cut(full);
        cut(tail.snapshot());
        assertEquals(json.writeValueAsString(full), json.writeValueAsString(tail.snapshot()), backend + " / " + shape);
    }

    @ParameterizedTest(name = "{0}: {1}")
    @MethodSource("conversations")
    @DisplayName("approver projection of the slim approver read = of the full read")
    void approverProjectionMatches(String backend, String shape, Supplier<ConversationMemorySnapshot> conversation) throws Exception {
        var store = store(backend);
        String id = store.storeConversationMemorySnapshot(conversation.get());

        var expected = ConversationMemoryUtilities.sanitizePendingToolCallsForApprover(store.loadConversationMemorySnapshot(id));
        var actual = ConversationMemoryUtilities.sanitizePendingToolCallsForApprover(store.loadConversationMemorySnapshotForApprover(id));
        assertEquals(json.writeValueAsString(expected), json.writeValueAsString(actual), backend + " / " + shape);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    @DisplayName("the slim reads leave the frozen transcript and raw arguments on the server")
    void slimReadsDropResumeOnlyFields(String backend) throws Exception {
        var store = store(backend);
        String id = store.storeConversationMemorySnapshot(toolCallPause());

        var full = store.loadConversationMemorySnapshot(id).getHitlPendingToolCalls();
        assertNotNull(full.getChatTranscriptJson(), "fixture sanity: the full read carries the transcript");

        for (var batch : List.of(store.loadConversationMemoryTail(id).snapshot().getHitlPendingToolCalls(),
                store.loadConversationMemorySnapshotForApprover(id).getHitlPendingToolCalls())) {
            assertNull(batch.getChatTranscriptJson());
            assertNull(batch.getGatingAssistantMessageJson());
            assertNull(batch.getTraceSoFar());
            assertNull(batch.getFingerprint());
            // What the approval card reads is still there.
            assertEquals(full.getCalls().size(), batch.getCalls().size());
            assertEquals(full.getCalls().getFirst().getArgumentsRedacted(), batch.getCalls().getFirst().getArgumentsRedacted());
            assertEquals(full.getCalls().getFirst().isRequestPinned(), batch.getCalls().getFirst().isRequestPinned());
            assertEquals(full.getPauseEpoch(), batch.getPauseEpoch());
        }
        if ("mongo".equals(backend)) {
            assertNull(store.loadConversationMemoryTail(id).snapshot().getHitlPendingToolCalls().getCalls().getFirst().getArgumentsRaw());
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    @DisplayName("a step stored under EDDI 5's 'packages' key is read in full, so its spend still counts")
    void legacyPackagesKeyFallsBackToTheFullRead(String backend) throws Exception {
        var store = store(backend);
        String id = store.storeConversationMemorySnapshot(ready(4, true));
        renameWorkflowsToPackages(backend, id);

        var full = store.loadConversationMemorySnapshot(id);
        assertNotNull(ConversationMemoryTail.ofFullSnapshot(full).conversationCost(), "fixture sanity: the legacy steps carry spend");
        var tail = store.loadConversationMemoryTail(id);
        assertEquals(ConversationMemoryTail.ofFullSnapshot(full).conversationCost(), tail.conversationCost());
        assertEquals(json.writeValueAsString(ConversationMemoryUtilities.convertSimpleConversationMemorySnapshot(full, false, true, null)),
                json.writeValueAsString(ConversationMemoryUtilities.convertSimpleConversationMemorySnapshot(tail, false, null)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    @DisplayName("an unknown or malformed id has no tail, as it has no snapshot")
    void unknownConversation(String backend) throws Exception {
        var store = store(backend);
        String unknown = "mongo".equals(backend) ? new ObjectId().toHexString() : "8f14e45f-ceea-467a-9575-0123456789ab";
        assertNull(store.loadConversationMemoryTail(unknown));
        assertNull(store.loadConversationMemorySnapshotForApprover(unknown));
        assertNull(store.loadConversationMemoryTail("not-an-id"));
        assertNull(store.loadConversationMemorySnapshotForApprover("not-an-id"));
    }

    static Stream<String> backends() {
        return Stream.of("mongo", "postgres");
    }

    private static IConversationMemoryStore store(String backend) {
        return "mongo".equals(backend) ? mongoStore : postgresStore;
    }

    /**
     * The fields a tail may leave out, removed from either side before comparing.
     */
    private static void cut(ConversationMemorySnapshot snapshot) {
        if (!snapshot.getConversationSteps().isEmpty()) {
            snapshot.setConversationSteps(new ArrayList<>(List.of(snapshot.getConversationSteps().getLast())));
        }
        if (!snapshot.getConversationOutputs().isEmpty()) {
            snapshot.setConversationOutputs(new ArrayList<>(List.of(snapshot.getConversationOutputs().getLast())));
        }
        snapshot.getRedoCache().clear();
        var batch = snapshot.getHitlPendingToolCalls();
        if (batch != null) {
            batch.setChatTranscriptJson(null);
            batch.setGatingAssistantMessageJson(null);
            batch.setTraceSoFar(null);
            batch.setFingerprint(null);
            batch.getCalls().forEach(call -> call.setArgumentsRaw(null));
        }
    }

    private static void renameWorkflowsToPackages(String backend, String id) throws Exception {
        if ("mongo".equals(backend)) {
            var collection = getDatabase().getCollection("conversationmemories");
            var stored = collection.find(Filters.eq("_id", new ObjectId(id))).first();
            assertNotNull(stored);
            for (Object step : stored.getList("conversationSteps", Object.class)) {
                var stepDocument = (Document) step;
                stepDocument.put("packages", stepDocument.remove("workflows"));
            }
            collection.replaceOne(Filters.eq("_id", new ObjectId(id)), stored);
            return;
        }
        try (Connection connection = postgresDataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "UPDATE conversation_memories SET data = replace(data::text, '\"workflows\"', '\"packages\"')::jsonb WHERE id = ?::uuid")) {
            statement.setString(1, id);
            assertEquals(1, statement.executeUpdate());
        }
    }

    // ─── Fixtures ───────────────────────────────────────────────

    private static ConversationMemorySnapshot base(ConversationState state) {
        var snapshot = new ConversationMemorySnapshot();
        snapshot.setAgentId(AGENT_ID);
        snapshot.setAgentVersion(3);
        snapshot.setUserId("user-1");
        snapshot.setEnvironment(Deployment.Environment.production);
        snapshot.setConversationState(state);
        snapshot.getConversationProperties().put("agentName", new Property("agentName", "Ada", Property.Scope.conversation));
        snapshot.getConversationProperties().put("turns", new Property("turns", 7, Property.Scope.longTerm));
        return snapshot;
    }

    /** One turn: a step with input, actions, output, spend; and its output. */
    private static void addTurn(ConversationMemorySnapshot snapshot, int turn, Double cost, List<String> actions, boolean withOutput) {
        var timestamp = new Date(1_760_000_000_000L + turn * 1000L);
        var parser = new WorkflowRunSnapshot();
        parser.getLifecycleTasks().add(new ResultSnapshot("input:initial", "message " + turn, null, timestamp, "wf-1", true));
        parser.getLifecycleTasks().add(new ResultSnapshot("context:language", new Context(Context.ContextType.string, "en"), null, timestamp,
                "wf-1", false));
        parser.getLifecycleTasks().add(new ResultSnapshot("actions", actions, null, timestamp, "wf-1", true));
        var llm = new WorkflowRunSnapshot();
        llm.getLifecycleTasks().add(new ResultSnapshot("output:text:llm", "answer " + turn, null, timestamp, "wf-2", true));
        llm.getLifecycleTasks().add(new ResultSnapshot("langchain:trace:llm", "a long trace " + "x".repeat(2000), null, timestamp, "wf-2", false));
        if (cost != null) {
            llm.getLifecycleTasks().add(new ResultSnapshot(MemoryKeys.AUDIT_COST, cost, null, timestamp, "wf-2", false));
        }
        var step = new ConversationStepSnapshot();
        step.getWorkflows().add(parser);
        step.getWorkflows().add(llm);
        snapshot.getConversationSteps().add(step);

        if (withOutput) {
            var output = new ConversationOutput();
            output.put("input", "message " + turn);
            output.put("actions", actions);
            output.put("output", List.of(Map.of("type", "text", "text", "answer " + turn)));
            snapshot.getConversationOutputs().add(output);
        }
    }

    private static ConversationMemorySnapshot ready(int turns, boolean withRedo) {
        var snapshot = base(ConversationState.READY);
        for (int turn = 0; turn < turns; turn++) {
            // Costs whose float sum depends on the order they are added in.
            addTurn(snapshot, turn, turn % 3 == 2 ? null : 0.1 + turn * 0.0000123, List.of(turn == 0 ? "CONVERSATION_START" : "answer"), true);
        }
        if (withRedo) {
            var undone = new ConversationStepSnapshot();
            var run = new WorkflowRunSnapshot();
            run.getLifecycleTasks().add(new ResultSnapshot(MemoryKeys.AUDIT_COST, 0.0042, null, new Date(1_760_000_100_000L), "wf-2", false));
            run.getLifecycleTasks().add(new ResultSnapshot(MemoryKeys.AUDIT_DISCARDED_COST, 3, null, new Date(1_760_000_100_000L), "wf-2", false));
            undone.getWorkflows().add(run);
            var undoneOutput = new ConversationOutput();
            undoneOutput.put("input", "undone");
            undone.setConversationOutput(undoneOutput);
            snapshot.getRedoCache().push(undone);
        }
        return snapshot;
    }

    private static ConversationMemorySnapshot toolCallPause() {
        var snapshot = base(ConversationState.AWAITING_HUMAN);
        for (int turn = 0; turn < 5; turn++) {
            addTurn(snapshot, turn, 0.25, List.of("answer"), true);
        }
        snapshot.setHitlPausedAt(Instant.parse("2026-10-27T09:15:00Z"));
        snapshot.setHitlPauseReason("Approve the tool call");
        snapshot.setHitlTimeoutPolicy(HitlTimeoutPolicy.AUTO_REJECT);
        snapshot.setHitlApprovalTimeout("PT30M");
        snapshot.setHitlPauseType("TOOL_CALL");

        var preview = new ResolvedRequestPreview();
        preview.setMethod("POST");
        preview.setUri("https://api.example.test/orders?key=abc");
        preview.setHeaders(new LinkedHashMap<>(Map.of("Authorization", "Bearer fixturefixturefixturefixture")));
        preview.setQueryParams(new LinkedHashMap<>(Map.of("key", "abc")));
        preview.setBody("{\"amount\":12}");

        var call = new PendingToolCall();
        call.setCallId("call-1");
        call.setToolName("http:create_order");
        call.setSource("http");
        call.setArgumentsRaw("{\"amount\":12,\"apiKey\":\"fixturefixturefixture\"}");
        call.setArgumentsRedacted("{\"amount\":12,\"apiKey\":\"***\"}");
        call.setGateReason("http:*");
        call.setRequestFingerprint("digest-of-the-request");
        call.setRequestPreview(preview);

        var second = new PendingToolCall();
        second.setCallId("call-2");
        second.setToolName("builtin:calculator");
        second.setSource("builtin");
        second.setArgumentsRaw("{\"expression\":\"1+1\"}");
        second.setArgumentsRedacted("{\"expression\":\"1+1\"}");
        second.setGateReason("builtin:*");

        var batch = new PendingToolCallBatch();
        batch.setPauseEpoch("epoch-1");
        batch.setLlmTaskId("llm-task");
        batch.setWorkflowId("wf-2");
        batch.setCalls(new ArrayList<>(List.of(call, second)));
        batch.setExecutedUngatedCallNames(List.of("builtin:datetime"));
        batch.setChatTranscriptJson("[" + "{\"role\":\"user\",\"text\":\"" + "y".repeat(5000) + "\"}" + "]");
        batch.setGatingAssistantMessageJson("{\"role\":\"assistant\"}");
        batch.setTraceSoFar(List.of(Map.of("tool", "builtin:datetime", "result", "2026-10-27")));
        batch.setFingerprint("batch-digest");
        snapshot.setHitlPendingToolCalls(batch);
        return snapshot;
    }

    private static ConversationMemorySnapshot rulePause() {
        var snapshot = base(ConversationState.AWAITING_HUMAN);
        for (int turn = 0; turn < 6; turn++) {
            addTurn(snapshot, turn, 0.01, turn == 5 ? List.of("request_refund", "PAUSE_CONVERSATION") : List.of("answer"), true);
        }
        snapshot.setHitlPausedAt(Instant.parse("2026-10-27T09:20:00Z"));
        snapshot.setHitlPauseReason("Refunds need a human");
        snapshot.setHitlTimeoutPolicy(HitlTimeoutPolicy.WAIT_INDEFINITELY);
        snapshot.setHitlPauseType("RULE");
        return snapshot;
    }

    private static ConversationMemorySnapshot secretLastTurn() {
        var snapshot = ready(3, false);
        var timestamp = new Date(1_760_000_200_000L);
        var run = new WorkflowRunSnapshot();
        run.getLifecycleTasks().add(new ResultSnapshot("input:initial", "hunter2-secret-value", null, timestamp, "wf-1", true));
        run.getLifecycleTasks().add(new ResultSnapshot("context:secretInput", new Context(Context.ContextType.string, "true"), null, timestamp,
                "wf-1", false));
        run.getLifecycleTasks().add(new ResultSnapshot("output:text:echo", "You said hunter2-secret-value", null, timestamp, "wf-1", true));
        var step = new ConversationStepSnapshot();
        step.getWorkflows().add(run);
        snapshot.getConversationSteps().add(step);
        var output = new ConversationOutput();
        output.put("input", "hunter2-secret-value");
        output.put("context", new LinkedHashMap<>(Map.of("secretInput", "true")));
        output.put("output", List.of(Map.of("type", "text", "text", "You said hunter2-secret-value")));
        snapshot.getConversationOutputs().add(output);
        return snapshot;
    }

    private static ConversationMemorySnapshot drifted() {
        var snapshot = base(ConversationState.READY);
        for (int turn = 0; turn < 4; turn++) {
            addTurn(snapshot, turn, 0.5, List.of("answer"), turn < 3);
        }
        return snapshot;
    }

    private static ConversationMemorySnapshot errored() {
        var snapshot = base(ConversationState.ERROR);
        for (int turn = 0; turn < 3; turn++) {
            addTurn(snapshot, turn, null, List.of("answer"), true);
        }
        snapshot.getConversationOutputs().getLast().put("taskErrors", List.of(Map.of("taskId", "ai.labs.llm", "message", "model timed out")));
        return snapshot;
    }
}
