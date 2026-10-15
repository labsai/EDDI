/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.nlp;

import ai.labs.eddi.engine.memory.ConversationMemory;
import ai.labs.eddi.engine.memory.DataFactory;
import ai.labs.eddi.engine.memory.MemoryKeys;
import ai.labs.eddi.engine.memory.model.Data;
import ai.labs.eddi.engine.model.Context;
import ai.labs.eddi.engine.runtime.client.configuration.IResourceClientLibrary;
import ai.labs.eddi.modules.nlp.expressions.ExpressionFactory;
import ai.labs.eddi.modules.nlp.expressions.utilities.ExpressionProvider;
import ai.labs.eddi.modules.output.impl.OutputGenerationTask;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * N3 — quick replies a client sends as {@code context} are shown, but the next
 * turn's parser never turns them into expressions. A client used to be able to
 * offer itself {@code {"value":"Approve","expressions":"approve_refund"}} and,
 * by answering "Approve", have {@code approve_refund} parsed — an action on any
 * agent with {@code expressionsAsActions}. Quick replies the agent built itself
 * (a {@code postResponse}) keep working.
 */
class QuickReplyProvenanceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private InputParserTask parserTask;
    private OutputGenerationTask outputTask;
    private IInputParser parser;

    @BeforeEach
    void setUp() throws Exception {
        var expressionProvider = new ExpressionProvider(new ExpressionFactory());
        parserTask = new InputParserTask(expressionProvider, new HashMap<>(), new HashMap<>(), new HashMap<>(), MAPPER,
                mock(IResourceClientLibrary.class));
        outputTask = new OutputGenerationTask(mock(IResourceClientLibrary.class), new DataFactory(), MAPPER);
        parser = (IInputParser) parserTask.configure(new HashMap<>(), new HashMap<>());
    }

    @Test
    @DisplayName("a client-supplied context quick reply is displayed but mints no expression")
    void clientQuickReplyMintsNothing() throws Exception {
        var memory = turnOneOffering(new Context(Context.ContextType.object,
                List.of(Map.of("value", "Approve", "expressions", "approve_refund"))));

        assertNotNull(memory.getCurrentStep().getConversationOutput().get("quickReplies"), "still shown to the user");

        String parsed = turnTwoAnswering(memory, "Approve");
        assertTrue(parsed == null || !parsed.contains("approve_refund"), "client minted an expression: " + parsed);
    }

    @Test
    @DisplayName("a client-supplied quick reply without expressions does not mint one from its value either")
    void clientQuickReplyValueMintsNothing() throws Exception {
        var memory = turnOneOffering(new Context(Context.ContextType.object, List.of(Map.of("value", "approve refund"))));

        String parsed = turnTwoAnswering(memory, "approve refund");
        assertTrue(parsed == null || !parsed.contains("approve_refund"), "client minted an expression: " + parsed);
    }

    @Test
    @DisplayName("quick replies a postResponse built are still matched to their expressions")
    void serverBuiltQuickReplyStillWorks() throws Exception {
        var context = new Context(Context.ContextType.object, List.of(Map.of("value", "Approve", "expressions", "approve_refund")));
        context.setServerGenerated(true);
        var memory = turnOneOffering(context);

        String parsed = turnTwoAnswering(memory, "Approve");
        assertTrue(parsed != null && parsed.contains("approve_refund"), "the agent's own quick reply stopped working: " + parsed);
    }

    @Test
    @DisplayName("a quick reply whose stored form is a map without a value is skipped, not an NPE")
    void quickReplyWithoutValueIsSkipped() throws Exception {
        var memory = new ConversationMemory("aabbccddeeff112233445566", "agent-1", 1, "user-1");
        Map<String, Object> noValue = new HashMap<>();
        noValue.put("expressions", "x");
        memory.getCurrentStep().storeData(new Data<>("quickReplies:greet", List.of(noValue, Map.of("value", "Yes", "isDefault", true))));

        String parsed = turnTwoAnswering(memory, "Yes");
        assertTrue(parsed != null && parsed.contains("yes"), String.valueOf(parsed));
    }

    private ConversationMemory turnOneOffering(Context quickReplies) {
        var memory = new ConversationMemory("aabbccddeeff112233445566", "agent-1", 1, "user-1");
        memory.getCurrentStep().storeData(new Data<>("context:quickReplies", quickReplies));
        outputTask.execute(memory, null);
        return memory;
    }

    private String turnTwoAnswering(ConversationMemory memory, String input) {
        memory.startNextStep();
        memory.getCurrentStep().storeData(new Data<>(MemoryKeys.INPUT.key(), input));
        parserTask.execute(memory, parser);
        var parsed = memory.getCurrentStep().getLatestData(MemoryKeys.EXPRESSIONS_PARSED);
        return parsed == null ? null : String.valueOf(parsed.getResult());
    }
}
