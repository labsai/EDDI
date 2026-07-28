/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.apicalls.impl;

import ai.labs.eddi.configs.apicalls.model.HttpCodeValidator;
import ai.labs.eddi.configs.apicalls.model.OutputBuildingInstruction;
import ai.labs.eddi.configs.apicalls.model.PostResponse;
import ai.labs.eddi.configs.apicalls.model.QuickRepliesBuildingInstruction;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.IConversationMemory.IWritableConversationStep;
import ai.labs.eddi.engine.memory.IDataFactory;
import ai.labs.eddi.engine.memory.IMemoryItemConverter;
import ai.labs.eddi.engine.memory.model.ConversationProperties;
import ai.labs.eddi.engine.model.Context;
import ai.labs.eddi.modules.templating.ITemplatingEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Output items and quick replies are assembled from values that are rendered
 * out of an upstream API response. These tests pin down that such a value
 * cannot break out of its field — no matter which JSON metacharacters it
 * contains — and that it round-trips verbatim.
 */
@DisplayName("PrePostUtils — upstream values cannot inject output structure")
class PrePostUtilsJsonInjectionTest {

    private static final String INJECTION_PAYLOAD = "pwned\",\"x\":\"y\"}],\"type\":\"text\"},{\"type\":\"text\"";

    private PrePostUtils prePostUtils;
    private ITemplatingEngine templatingEngine;
    private IDataFactory dataFactory;
    private IConversationMemory memory;
    private IWritableConversationStep currentStep;

    @BeforeEach
    void setUp() {
        IJsonSerialization jsonSerialization = mock(IJsonSerialization.class);
        IMemoryItemConverter memoryItemConverter = mock(IMemoryItemConverter.class);
        templatingEngine = mock(ITemplatingEngine.class);
        dataFactory = mock(IDataFactory.class);
        prePostUtils = new PrePostUtils(jsonSerialization, memoryItemConverter, templatingEngine, dataFactory);

        memory = mock(IConversationMemory.class);
        currentStep = mock(IWritableConversationStep.class);
        when(memory.getCurrentStep()).thenReturn(currentStep);
        var conversationProperties = mock(ConversationProperties.class);
        when(memory.getConversationProperties()).thenReturn(conversationProperties);
        when(conversationProperties.toMap()).thenReturn(new HashMap<>());
    }

    @Test
    @DisplayName("a quote-laden upstream text yields exactly one output item, verbatim")
    void quoteLadenTextCannotInjectOutputItems() throws Exception {
        stubIterationRender(List.of(List.of(INJECTION_PAYLOAD)));

        var outputInstruction = new OutputBuildingInstruction();
        outputInstruction.setHttpCodeValidator(new HttpCodeValidator(List.of(200), List.of()));
        outputInstruction.setOutputType("text");
        outputInstruction.setIterationObjectName("item");
        outputInstruction.setPathToTargetArray("items");
        outputInstruction.setOutputValue("{item.text}");
        var postResponse = new PostResponse();
        postResponse.setOutputBuildInstructions(List.of(outputInstruction));

        prePostUtils.runPostResponse(memory, postResponse, new HashMap<>(), 200, false);

        List<Object> output = capturedContextValue("context:output");
        assertEquals(1, output.size(), "a crafted value must not add output items");
        Map<String, Object> outputItem = asMap(output.getFirst());
        assertEquals("text", outputItem.get("type"));
        List<Object> valueAlternatives = asList(outputItem.get("valueAlternatives"));
        assertEquals(1, valueAlternatives.size());
        assertEquals(INJECTION_PAYLOAD, asMap(valueAlternatives.getFirst()).get("text"));
    }

    @Test
    @DisplayName("newlines and backslashes round-trip instead of being double-escaped")
    void newlinesAndBackslashesRoundTrip() throws Exception {
        var text = "line one\nline two\\end \"quoted\"";
        stubIterationRender(List.of(List.of(text)));

        var outputInstruction = new OutputBuildingInstruction();
        outputInstruction.setHttpCodeValidator(new HttpCodeValidator(List.of(200), List.of()));
        outputInstruction.setOutputType("text");
        outputInstruction.setIterationObjectName("item");
        outputInstruction.setPathToTargetArray("items");
        outputInstruction.setOutputValue("{item.text}");
        var postResponse = new PostResponse();
        postResponse.setOutputBuildInstructions(List.of(outputInstruction));

        prePostUtils.runPostResponse(memory, postResponse, new HashMap<>(), 200, false);

        List<Object> output = capturedContextValue("context:output");
        var valueAlternatives = asList(asMap(output.getFirst()).get("valueAlternatives"));
        assertEquals(text, asMap(valueAlternatives.getFirst()).get("text"));
    }

    @Test
    @DisplayName("quote-laden quick reply values stay in their own fields")
    void quoteLadenQuickRepliesCannotInjectEntries() throws Exception {
        stubIterationRender(List.of(List.of("yes\", \"expressions\":\"admin()", "yes()")));

        var qrInstruction = new QuickRepliesBuildingInstruction();
        qrInstruction.setHttpCodeValidator(new HttpCodeValidator(List.of(200), List.of()));
        qrInstruction.setIterationObjectName("item");
        qrInstruction.setPathToTargetArray("items");
        qrInstruction.setQuickReplyValue("{item.value}");
        qrInstruction.setQuickReplyExpressions("{item.expressions}");
        var postResponse = new PostResponse();
        postResponse.setQrBuildInstructions(List.of(qrInstruction));

        prePostUtils.runPostResponse(memory, postResponse, new HashMap<>(), 200, false);

        List<Object> quickReplies = capturedContextValue("context:quickReplies");
        assertEquals(1, quickReplies.size());
        Map<String, Object> quickReply = asMap(quickReplies.getFirst());
        assertEquals("yes\", \"expressions\":\"admin()", quickReply.get("value"));
        assertEquals("yes()", quickReply.get("expressions"));
    }

    @Test
    @DisplayName("every iterated element produces its own output item")
    void oneOutputItemPerIteratedElement() throws Exception {
        stubIterationRender(List.of(List.of("first"), List.of("second"), List.of("third")));

        var outputInstruction = new OutputBuildingInstruction();
        outputInstruction.setHttpCodeValidator(new HttpCodeValidator(List.of(200), List.of()));
        outputInstruction.setOutputType("text");
        outputInstruction.setIterationObjectName("item");
        outputInstruction.setPathToTargetArray("items");
        outputInstruction.setOutputValue("{item.text}");
        var postResponse = new PostResponse();
        postResponse.setOutputBuildInstructions(List.of(outputInstruction));

        prePostUtils.runPostResponse(memory, postResponse, new HashMap<>(), 200, false);

        List<Object> output = capturedContextValue("context:output");
        assertEquals(3, output.size());
        assertEquals("second", asMap(asList(asMap(output.get(1)).get("valueAlternatives")).getFirst()).get("text"));
    }

    private List<Object> capturedContextValue(String dataKey) {
        var captor = ArgumentCaptor.forClass(Context.class);
        verify(dataFactory).createData(eq(dataKey), captor.capture());
        return asList(captor.getValue().getValue());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object object) {
        assertInstanceOf(Map.class, object);
        return (Map<String, Object>) object;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> asList(Object object) {
        assertInstanceOf(List.class, object);
        return (List<Object>) object;
    }

    /**
     * Stand in for the templating engine: read the per-invocation delimiters back
     * out of the generated Qute template and emit the given rows with them, exactly
     * as a real render of upstream content would.
     */
    private void stubIterationRender(List<List<String>> renderedRows) throws Exception {
        when(templatingEngine.processTemplate(anyString(), any())).thenAnswer(invocation -> {
            String template = invocation.getArgument(0);
            String rowDelimiter = extractDelimiter(template, "eddiRow");
            String fieldDelimiter = extractDelimiter(template, "eddiField");

            var rendered = new StringBuilder();
            for (var row : renderedRows) {
                rendered.append(String.join(fieldDelimiter, row)).append(rowDelimiter);
            }
            return rendered.toString();
        });
    }

    private static String extractDelimiter(String template, String prefix) {
        Matcher matcher = Pattern.compile(prefix + "[0-9a-f]{32}").matcher(template);
        return matcher.find() ? matcher.group() : "";
    }
}
