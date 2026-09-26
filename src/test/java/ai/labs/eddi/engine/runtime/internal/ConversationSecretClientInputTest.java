/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.runtime.internal;

import ai.labs.eddi.configs.properties.model.Property;
import ai.labs.eddi.configs.properties.model.Property.Scope;
import ai.labs.eddi.engine.audit.model.AuditEntry;
import ai.labs.eddi.engine.lifecycle.IConversation;
import ai.labs.eddi.engine.lifecycle.ILifecycleManager;
import ai.labs.eddi.engine.memory.ConversationMemory;
import ai.labs.eddi.engine.memory.ConversationMemoryUtilities;
import ai.labs.eddi.engine.memory.IData;
import ai.labs.eddi.engine.memory.IPropertiesHandler;
import ai.labs.eddi.engine.memory.MemoryKeys;
import ai.labs.eddi.engine.memory.model.ConversationState;
import ai.labs.eddi.engine.model.Context;
import ai.labs.eddi.engine.runtime.IExecutableWorkflow;
import ai.labs.eddi.modules.nlp.IInputParser;
import ai.labs.eddi.modules.nlp.InputParserTask;
import ai.labs.eddi.modules.nlp.expressions.Expression;
import ai.labs.eddi.modules.nlp.expressions.Expressions;
import ai.labs.eddi.modules.nlp.expressions.utilities.IExpressionProvider;
import ai.labs.eddi.modules.nlp.extensions.dictionaries.IDictionary;
import ai.labs.eddi.modules.nlp.internal.matches.RawSolution;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A message the client sends with {@code secretInput} is usable while its turn
 * runs — a real {@link InputParserTask} is the first task, as in practically
 * every workflow — and gone from everything that outlives the turn: the stored
 * step, the non-detailed snapshot (whose {@code conversationOutputs} are also
 * what the streaming {@code done} frame carries) and the audit ledger. The
 * displayed {@code input} is the masked copy clients rely on.
 */
@DisplayName("Conversation: client-flagged secret input with a parser in the workflow")
class ConversationSecretClientInputTest {

    /** Low-entropy on purpose: a realistic-looking key trips Secret Scanning. */
    private static final String SECRET = "Tok-Aaaa-Bbbb-Cccc-1111";
    private static final String NORMALIZED = "tok-aaaa-bbbb-cccc-1111";
    private static final String PLACEHOLDER = MemoryKeys.SECRET_INPUT_PLACEHOLDER;

    private static final ObjectMapper MAPPER = new ObjectMapper().registerModule(new JavaTimeModule())
            .setDefaultPropertyInclusion(JsonInclude.Include.NON_NULL);

    private ConversationMemory memory;
    private IPropertiesHandler propertiesHandler;
    private IConversation.IConversationOutputRenderer outputRenderer;
    private IExecutableWorkflow workflow;
    private ILifecycleManager lifecycleManager;
    private InputParserTask parserTask;
    private final List<AuditEntry> ledger = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        memory = new ConversationMemory("conv1", "agent1", 1, "user1");
        memory.setConversationState(ConversationState.READY);
        memory.setAuditCollector(ledger::add);
        propertiesHandler = mock(IPropertiesHandler.class);
        outputRenderer = mock(IConversation.IConversationOutputRenderer.class);
        workflow = mock(IExecutableWorkflow.class);
        lifecycleManager = mock(ILifecycleManager.class);
        lenient().when(workflow.getLifecycleManager()).thenReturn(lifecycleManager);
        lenient().when(workflow.getWorkflowId()).thenReturn("wf1");
        parserTask = new InputParserTask(mock(IExpressionProvider.class), new HashMap<>(), new HashMap<>(), new HashMap<>(), new ObjectMapper());
    }

    /**
     * A parser that lower-cases (a normalizer) and reports the input as unknown.
     */
    private static IInputParser normalizingParser() throws Exception {
        IInputParser parser = mock(IInputParser.class);
        when(parser.getConfig()).thenReturn(new IInputParser.Config(false, true, true));
        when(parser.normalize(anyString(), any())).thenAnswer(invocation -> invocation.<String>getArgument(0).toLowerCase());
        IDictionary.IFoundWord foundWord = mock(IDictionary.IFoundWord.class);
        when(foundWord.getExpressions()).thenReturn(new Expressions(new Expression("unknown", new Expression(NORMALIZED))));
        RawSolution rawSolution = mock(RawSolution.class);
        when(rawSolution.getDictionaryEntries()).thenReturn(List.of(foundWord));
        when(parser.parse(anyString(), any(), anyList())).thenReturn(List.of(rawSolution));
        return parser;
    }

    /**
     * The turn: the real parser runs first; later tasks echo the input into an
     * output, optionally capture it into a conversation property (the wizard
     * pattern) and record an audit entry.
     */
    private void pipeline(List<String> seenDuringTurn, boolean captureIntoProperty) throws Exception {
        IInputParser parser = normalizingParser();
        doAnswer(invocation -> {
            parserTask.execute(memory, parser);
            var step = memory.getCurrentStep();
            IData<String> initial = step.getLatestData(MemoryKeys.INPUT_INITIAL.key());
            seenDuringTurn.add(initial.getResult());
            seenDuringTurn.add(String.valueOf(step.getConversationOutput().get("input")));

            step.addConversationOutputList("output", List.of(Map.of("type", "text", "text", "Stored key " + SECRET)));
            if (captureIntoProperty) {
                memory.getConversationProperties().put("apiKey", new Property("apiKey", initial.getResult(), Scope.conversation));
            }
            memory.getAuditCollector()
                    .collect(new AuditEntry("e1", "conv1", "agent1", 1, "user1", null, 1, "ai.labs.parser", "expressions", 0, 1L,
                            Map.of("userInput", SECRET), null, Map.of("compiledPrompt", "user said " + NORMALIZED), null, List.of(), 0.0,
                            Instant.now(), null, null));
            return null;
        }).when(lifecycleManager).executeLifecycle(any(), any());
    }

    private static Map<String, Context> secretFlag() {
        Map<String, Context> contexts = new LinkedHashMap<>();
        contexts.put("secretInput", new Context(Context.ContextType.string, "true"));
        return contexts;
    }

    @Test
    @DisplayName("the parser sees the plaintext, but nothing that outlives the turn carries it")
    void noPlaintextOutlivesTheTurn() throws Exception {
        List<String> seen = new ArrayList<>();
        pipeline(seen, false);

        conversation().say(SECRET, secretFlag());

        assertEquals(SECRET, seen.get(0), "tasks must see the raw input while the turn runs");
        assertEquals(NORMALIZED, seen.get(1), "the parser overwrites the displayed input mid-turn — the reason for the re-assert");

        var step = memory.getCurrentStep();
        assertEquals(PLACEHOLDER, step.getLatestData(MemoryKeys.INPUT_INITIAL.key()).getResult());
        assertEquals(PLACEHOLDER, step.getLatestData(MemoryKeys.INPUT_NORMALIZED.key()).getResult());
        assertEquals(PLACEHOLDER, step.getConversationOutput().get("input"), "the display input is the masked copy");
        assertEquals("", step.getLatestData(MemoryKeys.EXPRESSIONS_PARSED.key()).getResult());
        assertFalse(step.getConversationOutput().containsKey("expressions"));

        var stored = ConversationMemoryUtilities.convertConversationMemory(memory);
        var simple = ConversationMemoryUtilities.convertSimpleConversationMemory(stored, false, false);
        // conversationOutputs of the non-detailed snapshot are exactly what the
        // streaming done frame serialises.
        String doneOutputs = MAPPER.writeValueAsString(simple.getConversationOutputs());
        String simpleSteps = MAPPER.writeValueAsString(simple.getConversationSteps());
        assertEquals(PLACEHOLDER, simple.getConversationOutputs().getLast().get("input"));
        for (String form : List.of(SECRET, NORMALIZED)) {
            assertFalse(doneOutputs.contains(form), "done frame / snapshot outputs leak: " + doneOutputs);
            assertFalse(simpleSteps.contains(form), "snapshot steps leak: " + simpleSteps);
        }
        assertTrue(doneOutputs.contains("Stored key " + PLACEHOLDER), "an echo in an output is scrubbed: " + doneOutputs);

        String storedSteps = MAPPER.writeValueAsString(stored.getConversationSteps()) + MAPPER.writeValueAsString(stored.getConversationOutputs());
        for (String form : List.of(SECRET, NORMALIZED)) {
            assertFalse(storedSteps.contains(form), "the stored step leaks: " + storedSteps);
        }

        assertEquals(1, ledger.size());
        assertEquals(PLACEHOLDER, ledger.getFirst().input().get("userInput"));
        assertFalse(ledger.getFirst().llmDetail().get("compiledPrompt").toString().contains(NORMALIZED));
    }

    @Test
    @DisplayName("a property the designer captured the input into is left alone — that is the designer's choice")
    void capturedPropertyIsKept() throws Exception {
        pipeline(new ArrayList<>(), true);

        conversation().say(SECRET, secretFlag());

        assertEquals(SECRET, memory.getConversationProperties().get("apiKey").getValueString());
    }

    @Test
    @DisplayName("an unflagged turn keeps its input")
    void unflaggedTurnIsUntouched() throws Exception {
        pipeline(new ArrayList<>(), false);

        conversation().say(SECRET, Map.of());

        var step = memory.getCurrentStep();
        assertEquals(SECRET, step.getLatestData(MemoryKeys.INPUT_INITIAL.key()).getResult());
        assertEquals(NORMALIZED, step.getConversationOutput().get("input"));
        assertEquals(SECRET, ledger.getFirst().input().get("userInput"));
    }

    private Conversation conversation() {
        return new Conversation(List.of(workflow), memory, propertiesHandler, outputRenderer);
    }
}
