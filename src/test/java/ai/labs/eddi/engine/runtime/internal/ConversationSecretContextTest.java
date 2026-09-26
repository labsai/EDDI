/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.runtime.internal;

import ai.labs.eddi.configs.properties.IUserMemoryStore;
import ai.labs.eddi.configs.properties.model.Property;
import ai.labs.eddi.configs.properties.model.Property.Scope;
import ai.labs.eddi.configs.properties.model.UserMemoryEntry;
import ai.labs.eddi.engine.audit.model.AuditEntry;
import ai.labs.eddi.engine.lifecycle.IConversation;
import ai.labs.eddi.engine.lifecycle.ILifecycleManager;
import ai.labs.eddi.engine.lifecycle.exceptions.ConversationPauseException;
import ai.labs.eddi.engine.lifecycle.exceptions.ConversationPauseException.PauseOrigin;
import ai.labs.eddi.engine.lifecycle.exceptions.LifecycleException;
import ai.labs.eddi.engine.memory.ConversationMemory;
import ai.labs.eddi.engine.memory.ConversationMemoryUtilities;
import ai.labs.eddi.engine.memory.IData;
import ai.labs.eddi.engine.memory.IPropertiesHandler;
import ai.labs.eddi.engine.memory.MemoryKeys;
import ai.labs.eddi.engine.memory.model.ConversationState;
import ai.labs.eddi.engine.memory.model.Data;
import ai.labs.eddi.engine.memory.model.PendingToolCallBatch;
import ai.labs.eddi.engine.memory.model.PendingToolCallBatch.PendingToolCall;
import ai.labs.eddi.engine.model.Context;
import ai.labs.eddi.engine.runtime.IExecutableWorkflow;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * A context value the client marks {@code "secret": true} is usable while its
 * turn runs and gone from everything that outlives the turn: the stored step,
 * the returned output, the properties, the user memory store and the audit
 * ledger.
 */
@DisplayName("Conversation: secret context values")
class ConversationSecretContextTest {

    /** Low-entropy on purpose: a realistic-looking token trips Secret Scanning. */
    private static final String TOKEN = "tok-aaaa-bbbb-cccc-1111";

    private static final String PLACEHOLDER = MemoryKeys.SECRET_CONTEXT_PLACEHOLDER;

    private static final ObjectMapper MAPPER = new ObjectMapper().registerModule(new JavaTimeModule())
            .setDefaultPropertyInclusion(JsonInclude.Include.NON_NULL);

    /** A memory value of a type the scrubber cannot walk. */
    record OpaqueHolder(String header) {
    }

    private ConversationMemory memory;
    private IPropertiesHandler propertiesHandler;
    private IConversation.IConversationOutputRenderer outputRenderer;
    private IExecutableWorkflow workflow;
    private ILifecycleManager lifecycleManager;

    @BeforeEach
    void setUp() {
        memory = new ConversationMemory("conv1", "agent1", 1, "user1");
        memory.setConversationState(ConversationState.READY);
        propertiesHandler = mock(IPropertiesHandler.class);
        outputRenderer = mock(IConversation.IConversationOutputRenderer.class);
        workflow = mock(IExecutableWorkflow.class);
        lifecycleManager = mock(ILifecycleManager.class);
        lenient().when(workflow.getLifecycleManager()).thenReturn(lifecycleManager);
        lenient().when(workflow.getWorkflowId()).thenReturn("wf1");
    }

    private Conversation conversation() {
        return new Conversation(List.of(workflow), memory, propertiesHandler, outputRenderer);
    }

    private static Map<String, Context> contexts(String token, Boolean secret) {
        var tokenContext = new Context(Context.ContextType.string, token);
        tokenContext.setSecret(secret);
        Map<String, Context> contexts = new LinkedHashMap<>();
        contexts.put("userToken", tokenContext);
        contexts.put("userInfo", new Context(Context.ContextType.object, Map.of("firstName", "Ada")));
        return contexts;
    }

    /**
     * Stands in for the tasks of a turn: reads the live value, then copies it into
     * every place a template can put it.
     */
    private void pipelineCopiesToken(String token) throws Exception {
        doAnswer(invocation -> {
            var step = memory.getCurrentStep();
            IData<Context> live = step.getLatestData("context:userToken");
            assertNotNull(live, "the context entry must be in the step while the turn runs");
            assertEquals(token, live.getResult().getValue(), "the live value must be usable during the turn");
            @SuppressWarnings("unchecked")
            var echoedContext = (Map<String, Object>) step.getConversationOutput().get("context");
            assertEquals(PLACEHOLDER, echoedContext.get("userToken"), "the returned context must never carry the value, not even mid-turn");

            step.storeData(new Data<>("httpCalls:request", Map.of("headers", Map.of("Authorization", "Bearer " + token))));
            step.storeData(new Data<>("opaque:holder", new OpaqueHolder("Bearer " + token)));
            step.addConversationOutputString("debug", "sent Bearer " + token);
            memory.getConversationProperties().put("lastHeader", new Property("lastHeader", "Bearer " + token, Scope.conversation));
            memory.getConversationProperties().put("remembered",
                    new Property("remembered", Map.<String, Object>of("token", token), Scope.longTerm));
            return null;
        }).when(lifecycleManager).executeLifecycle(any(), any());
    }

    private String storedDocument() throws Exception {
        return MAPPER.writeValueAsString(ConversationMemoryUtilities.convertConversationMemory(memory));
    }

    @Test
    @DisplayName("say: the value is live during the turn and gone from the stored conversation afterwards")
    void sayScrubsEverythingThatOutlivesTheTurn() throws Exception {
        pipelineCopiesToken(TOKEN);

        conversation().say("hello", contexts(TOKEN, true));

        String document = storedDocument();
        assertFalse(document.contains(TOKEN), "stored conversation still carries the secret: " + document);
        assertTrue(document.contains("Ada"), "non-secret context must be kept");

        IData<Context> stored = memory.getCurrentStep().getLatestData("context:userToken");
        assertNotNull(stored);
        assertEquals(PLACEHOLDER, stored.getResult().getValue());
        assertEquals(Boolean.TRUE, stored.getResult().getSecret());
        assertEquals("Bearer " + PLACEHOLDER, memory.getConversationProperties().get("lastHeader").getValueString());
        IData<Object> opaque = memory.getCurrentStep().getLatestData("opaque:holder");
        assertNotNull(opaque);
        assertEquals(Map.of("header", "Bearer " + PLACEHOLDER), opaque.getResult(), "an object is scrubbed through its JSON form, keeping its shape");
    }

    @Test
    @DisplayName("init: the conversation-start turn scrubs the same way")
    void initScrubs() throws Exception {
        pipelineCopiesToken(TOKEN);

        conversation().init(contexts(TOKEN, true));

        assertFalse(storedDocument().contains(TOKEN));
    }

    @Test
    @DisplayName("a turn that fails is scrubbed too")
    void failedTurnIsScrubbed() throws Exception {
        doAnswer(invocation -> {
            memory.getCurrentStep().addConversationOutputString("debug", "sent Bearer " + TOKEN);
            throw new LifecycleException("downstream call failed");
        }).when(lifecycleManager).executeLifecycle(any(), any());

        assertThrows(LifecycleException.class, () -> conversation().say("hello", contexts(TOKEN, true)));

        assertFalse(storedDocument().contains(TOKEN));
    }

    @Test
    @DisplayName("a longTerm property is scrubbed before it is written to the user memory store")
    void longTermWriteIsScrubbed() throws Exception {
        IUserMemoryStore store = mock(IUserMemoryStore.class);
        lenient().when(propertiesHandler.getUserMemoryStore()).thenReturn(store);
        pipelineCopiesToken(TOKEN);

        conversation().say("hello", contexts(TOKEN, true));

        var written = ArgumentCaptor.forClass(UserMemoryEntry.class);
        verify(store, atLeastOnce()).upsert(written.capture());
        assertFalse(written.getAllValues().isEmpty());
        written.getAllValues().forEach(entry -> assertFalse(String.valueOf(entry.value()).contains(TOKEN), "written: " + entry.value()));
    }

    @Test
    @DisplayName("the audit ledger receives the turn's entries with the value redacted")
    void auditEntriesAreRedacted() throws Exception {
        List<AuditEntry> ledger = new ArrayList<>();
        memory.setAuditCollector(ledger::add);
        doAnswer(invocation -> {
            memory.getAuditCollector().collect(new AuditEntry("e1", "conv1", "agent1", 1, "user1", null, 1, "ai.labs.llm", "llm", 0, 1L,
                    Map.of("userInput", "hello"), null, Map.of("compiledPrompt", "use Bearer " + TOKEN), null, List.of(), 0.0,
                    Instant.now(), null, null));
            return null;
        }).when(lifecycleManager).executeLifecycle(any(), any());

        conversation().say("hello", contexts(TOKEN, true));

        assertEquals(1, ledger.size());
        assertEquals("use Bearer " + PLACEHOLDER, ledger.getFirst().llmDetail().get("compiledPrompt"));
        assertEquals("hello", ledger.getFirst().input().get("userInput"), "the input was not a secret and stays as recorded");
    }

    @Test
    @DisplayName("a tool-call pause scrubs the persisted pending batch, the arguments a resume would execute included")
    void pendingToolCallBatchIsScrubbed() throws Exception {
        doAnswer(invocation -> {
            var call = new PendingToolCall();
            call.setToolName("downstream");
            call.setArgumentsRaw("{\"authorization\":\"Bearer " + TOKEN + "\"}");
            call.setArgumentsRedacted("{\"authorization\":\"Bearer " + TOKEN + "\"}");
            var batch = new PendingToolCallBatch();
            batch.setCalls(List.of(call));
            batch.setChatTranscriptJson("[{\"system\":\"token " + TOKEN + "\"}]");
            memory.setHitlPendingToolCalls(batch);
            throw new ConversationPauseException("wf1", 1, "gated", PauseOrigin.TOOL_CALL);
        }).when(lifecycleManager).executeLifecycle(any(), any());

        conversation().say("hello", contexts(TOKEN, true));

        assertEquals(ConversationState.AWAITING_HUMAN, memory.getConversationState());
        PendingToolCallBatch persisted = memory.getHitlPendingToolCalls();
        assertNotNull(persisted);
        assertEquals("{\"authorization\":\"Bearer " + PLACEHOLDER + "\"}", persisted.getCalls().getFirst().getArgumentsRaw());
        assertFalse(storedDocument().contains(TOKEN));
    }

    @Test
    @DisplayName("a numeric leaf of a secret object is replaced where a task copied it")
    void numericSecretLeaf() throws Exception {
        doAnswer(invocation -> {
            memory.getCurrentStep().storeData(new Data<>("copy", Map.of("account", 12345678L)));
            return null;
        }).when(lifecycleManager).executeLifecycle(any(), any());
        var secretObject = new Context(Context.ContextType.object, Map.of("accountNumber", 12345678L));
        secretObject.setSecret(true);

        conversation().say("hello", Map.of("account", secretObject));

        IData<Object> copy = memory.getCurrentStep().getLatestData("copy");
        assertNotNull(copy);
        assertEquals(Map.of("account", PLACEHOLDER), copy.getResult());
    }

    @Test
    @DisplayName("control: the same value without the flag is stored as before")
    void unflaggedValueIsKept() throws Exception {
        doAnswer(invocation -> {
            memory.getCurrentStep().addConversationOutputString("debug", "sent Bearer " + TOKEN);
            return null;
        }).when(lifecycleManager).executeLifecycle(any(), any());

        conversation().say("hello", contexts(TOKEN, null));

        assertTrue(storedDocument().contains(TOKEN));
    }

    @Test
    @DisplayName("a value too short to search for is removed from its own entry only")
    void shortValueOnlyRemovedFromItsEntry() throws Exception {
        String shortValue = "ab12";
        doAnswer(invocation -> {
            memory.getCurrentStep().addConversationOutputString("debug", "code " + shortValue);
            return null;
        }).when(lifecycleManager).executeLifecycle(any(), any());

        conversation().say("hello", contexts(shortValue, true));

        IData<Context> stored = memory.getCurrentStep().getLatestData("context:userToken");
        assertNotNull(stored);
        assertEquals(PLACEHOLDER, stored.getResult().getValue());
        assertTrue(storedDocument().contains("code " + shortValue));
    }

    @Test
    @DisplayName("S4: a short secret copied whole into a property, a datum, the longTerm store and the audit trail is replaced there")
    void shortValueReplacedWhereAValueIsTheSecret() throws Exception {
        String pin = "4711";
        IUserMemoryStore store = mock(IUserMemoryStore.class);
        lenient().when(propertiesHandler.getUserMemoryStore()).thenReturn(store);
        List<AuditEntry> ledger = new ArrayList<>();
        memory.setAuditCollector(ledger::add);
        doAnswer(invocation -> {
            memory.getConversationProperties().put("pin", new Property("pin", pin, Scope.conversation));
            memory.getConversationProperties().put("rememberedPin", new Property("rememberedPin", pin, Scope.longTerm));
            memory.getConversationProperties().put("pinNumber", new Property("pinNumber", 4711, Scope.conversation));
            memory.getCurrentStep().storeData(new Data<>("httpCalls:request", Map.of("pin", pin, "note", "order 14711 shipped")));
            memory.getAuditCollector().collect(new AuditEntry("e1", "conv1", "agent1", 1, "user1", null, 1, "ai.labs.httpcalls", "httpcalls", 0,
                    1L, Map.of("userInput", "hello"), Map.of("pin", pin), null, null, List.of(), 0.0, Instant.now(), null, null));
            return null;
        }).when(lifecycleManager).executeLifecycle(any(), any());

        conversation().say("hello", contexts(pin, true));

        assertEquals(PLACEHOLDER, memory.getConversationProperties().get("pin").getValueString());
        assertEquals(PLACEHOLDER, memory.getConversationProperties().get("pinNumber").getValueString());
        IData<Object> request = memory.getCurrentStep().getLatestData("httpCalls:request");
        assertNotNull(request);
        @SuppressWarnings("unchecked")
        var requestMap = (Map<String, Object>) request.getResult();
        assertEquals(PLACEHOLDER, requestMap.get("pin"));
        assertEquals("order 14711 shipped", requestMap.get("note"), "a short value is never replaced INSIDE other text");

        var written = ArgumentCaptor.forClass(UserMemoryEntry.class);
        verify(store, atLeastOnce()).upsert(written.capture());
        written.getAllValues().forEach(entry -> assertFalse(pin.equals(String.valueOf(entry.value())), "written: " + entry.value()));

        assertEquals(1, ledger.size());
        assertEquals(PLACEHOLDER, ledger.getFirst().output().get("pin"));
    }

    @Test
    @DisplayName("S4: true/false and values under four characters are never exact-matched")
    void trivialValuesAreNotExactMatched() throws Exception {
        doAnswer(invocation -> {
            memory.getConversationProperties().put("flag", new Property("flag", "true", Scope.conversation));
            memory.getConversationProperties().put("code", new Property("code", "abc", Scope.conversation));
            return null;
        }).when(lifecycleManager).executeLifecycle(any(), any());
        var secretObject = new Context(Context.ContextType.object, Map.of("enabled", true, "tag", "abc"));
        secretObject.setSecret(true);

        conversation().say("hello", Map.of("settings", secretObject));

        assertEquals("true", memory.getConversationProperties().get("flag").getValueString());
        assertEquals("abc", memory.getConversationProperties().get("code").getValueString());
    }

    @Test
    @DisplayName("JSON: the flag is read from requests and omitted from entries that do not set it")
    void contextJson() throws Exception {
        Context parsed = MAPPER.readValue("{\"type\":\"string\",\"value\":\"x\",\"secret\":true}", Context.class);
        assertInstanceOf(Boolean.class, parsed.getSecret());
        assertTrue(parsed.getSecret());
        assertFalse(MAPPER.writeValueAsString(new Context(Context.ContextType.string, "x")).contains("secret"));
    }
}
