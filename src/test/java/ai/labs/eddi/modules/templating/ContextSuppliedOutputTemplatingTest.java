/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.templating;

import ai.labs.eddi.engine.memory.ConversationMemory;
import ai.labs.eddi.engine.memory.DataFactory;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.IData;
import ai.labs.eddi.engine.memory.IMemoryItemConverter;
import ai.labs.eddi.engine.memory.model.Data;
import ai.labs.eddi.engine.model.Context;
import ai.labs.eddi.modules.output.impl.OutputGeneration;
import ai.labs.eddi.modules.output.impl.OutputGenerationTask;
import ai.labs.eddi.modules.output.model.OutputEntry;
import ai.labs.eddi.modules.output.model.OutputValue;
import ai.labs.eddi.modules.output.model.QuickReply;
import ai.labs.eddi.modules.output.model.types.TextOutputItem;
import ai.labs.eddi.modules.templating.impl.TemplatingEngine;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.qute.Engine;
import io.quarkus.qute.ReflectionValueResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Output and quick replies that arrive as <em>data</em> — a {@code context}
 * value on a {@code /say} call, or what an httpcall's {@code postResponse}
 * built from an upstream response — must reach the user exactly as sent. Only
 * output authored in an output set is a template.
 * <p>
 * Runs the real output task, the real templating task and a real Qute engine
 * (with the reflection resolver Quarkus registers), so the assertion is on what
 * the user would actually receive. Before the fix a chat client could send
 * {@code {vars.apiKey}} as context output and get the deployment's global
 * variable back — or {@code {#for i in 2000000000}} and pin a worker.
 */
@DisplayName("context-supplied output is delivered verbatim, never templated")
class ContextSuppliedOutputTemplatingTest {

    private static final String SECRET = "s3cret-global-value";
    private static final String PROBE = "{vars.apiKey}";

    private OutputGenerationTask outputGenerationTask;
    private OutputTemplateTask outputTemplateTask;
    private IConversationMemory memory;

    @BeforeEach
    void setUp() {
        var objectMapper = new ObjectMapper();
        var dataFactory = new DataFactory();
        var engine = Engine.builder().addDefaults().addValueResolver(new ReflectionValueResolver()).strictRendering(false).build();
        var templatingEngine = new TemplatingEngine(engine);

        IMemoryItemConverter converter = mock(IMemoryItemConverter.class);
        when(converter.convert(any())).thenAnswer(inv -> {
            Map<String, Object> data = new HashMap<>();
            data.put("vars", Map.of("apiKey", SECRET));
            return data;
        });

        outputGenerationTask = new OutputGenerationTask(null, dataFactory, objectMapper);
        outputTemplateTask = new OutputTemplateTask(templatingEngine, converter, dataFactory, objectMapper);
        memory = new ConversationMemory("conversation-1", "agent-1", 1, "user-1");
    }

    @Test
    @DisplayName("context output text reaches the user literally")
    void contextOutputIsNotTemplated() throws Exception {
        storeContext("context:output", List.of(Map.of("valueAlternatives", List.of(Map.of("type", "text", "text", PROBE)))));

        runOutputAndTemplating(null);

        IData<Object> output = memory.getCurrentStep().getLatestData("output:text:context");
        assertNotNull(output, "precondition: the context output was stored");
        assertTrue(output.isVerbatim());
        assertEquals(PROBE, ((TextOutputItem) output.getResult()).getText());
        assertNoSecretInConversationOutput();
    }

    @Test
    @DisplayName("context quick replies reach the user literally")
    void contextQuickRepliesAreNotTemplated() throws Exception {
        storeContext("context:quickReplies", List.of(Map.of("value", PROBE, "expressions", PROBE)));

        runOutputAndTemplating(null);

        IData<List<QuickReply>> quickReplies = memory.getCurrentStep().getLatestData("quickReplies:context");
        assertNotNull(quickReplies, "precondition: the context quick replies were stored");
        assertEquals(PROBE, quickReplies.getResult().getFirst().getValue());
        assertEquals(PROBE, quickReplies.getResult().getFirst().getExpressions());
        assertNoSecretInConversationOutput();
    }

    @Test
    @DisplayName("a quick-reply item inside context output is not templated either")
    void quickReplyItemInsideContextOutputIsNotTemplated() throws Exception {
        storeContext("context:output",
                List.of(Map.of("valueAlternatives", List.of(Map.of("type", "quickReply", "value", PROBE, "expressions", "x")))));

        runOutputAndTemplating(null);

        IData<List<QuickReply>> quickReplies = memory.getCurrentStep().getLatestData("quickReplies:context");
        assertNotNull(quickReplies);
        assertEquals(PROBE, quickReplies.getResult().getFirst().getValue());
    }

    @Test
    @DisplayName("a Qute loop sent as context output is not evaluated")
    void loopIsNotEvaluated() throws Exception {
        String loop = "{#for i in 2000000000}x{/for}";
        storeContext("context:output", List.of(Map.of("valueAlternatives", List.of(Map.of("type", "text", "text", loop)))));

        runOutputAndTemplating(null);

        IData<Object> output = memory.getCurrentStep().getLatestData("output:text:context");
        assertEquals(loop, ((TextOutputItem) output.getResult()).getText());
    }

    @Test
    @DisplayName("control: output authored in the output set is still templated")
    void configuredOutputIsStillTemplated() throws Exception {
        memory.getCurrentStep().storeData(new Data<List<String>>("actions", List.of("greet")));
        var outputGeneration = new OutputGeneration(null);
        outputGeneration.addOutputEntry(new OutputEntry("greet", 0, List.of(new OutputValue(List.of(new TextOutputItem("Key: " + PROBE)))),
                List.of(new QuickReply("QR " + PROBE, "qr", false))));
        // A context output in the same turn must not switch templating off for authored
        // output.
        storeContext("context:output", List.of(Map.of("valueAlternatives", List.of(Map.of("type", "text", "text", PROBE)))));

        runOutputAndTemplating(outputGeneration);

        IData<Object> authored = memory.getCurrentStep().getLatestData("output:text:greet");
        assertFalse(authored.isVerbatim());
        assertEquals("Key: " + SECRET, ((TextOutputItem) authored.getResult()).getText());
        IData<List<QuickReply>> authoredQuickReplies = memory.getCurrentStep().getLatestData("quickReplies:greet");
        assertEquals("QR " + SECRET, authoredQuickReplies.getResult().getFirst().getValue());

        IData<Object> fromContext = memory.getCurrentStep().getLatestData("output:text:context");
        assertEquals(PROBE, ((TextOutputItem) fromContext.getResult()).getText());
    }

    private void storeContext(String key, Object value) {
        memory.getCurrentStep().storeData(new Data<>(key, new Context(Context.ContextType.object, value)));
    }

    private void runOutputAndTemplating(OutputGeneration outputGeneration) throws Exception {
        outputGenerationTask.execute(memory, outputGeneration);
        outputTemplateTask.execute(memory, null);
    }

    private void assertNoSecretInConversationOutput() {
        String rendered = String.valueOf(memory.getCurrentStep().getConversationOutput());
        assertFalse(rendered.contains(SECRET), "the global variable leaked into the conversation output: " + rendered);
    }
}
