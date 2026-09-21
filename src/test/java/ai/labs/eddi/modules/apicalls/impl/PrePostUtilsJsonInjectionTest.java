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
import ai.labs.eddi.modules.templating.impl.TemplatingEngine;
import io.quarkus.qute.Engine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
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

    /** Mirrors the template data keys used by {@link PrePostUtils}. */
    private static final String KEY_FIELD_DELIMITER = "eddiFieldDelimiter";
    private static final String KEY_ROW_DELIMITER = "eddiRowDelimiter";

    /** A nonce baked into the template text — exactly what must not happen. */
    private static final Pattern NONCE_IN_TEMPLATE = Pattern.compile("eddi(Field|Row)[0-9a-f]{32}");

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

        prePostUtils.runPostResponse(memory, outputPostResponse(), new HashMap<>(), 200, false);

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

        prePostUtils.runPostResponse(memory, outputPostResponse(), new HashMap<>(), 200, false);

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

        prePostUtils.runPostResponse(memory, outputPostResponse(), new HashMap<>(), 200, false);

        List<Object> output = capturedContextValue("context:output");
        assertEquals(3, output.size());
        assertEquals("second", asMap(asList(asMap(output.get(1)).get("valueAlternatives")).getFirst()).get("text"));
    }

    // ==================== delimiter nonce — the actual security property
    // ====================

    @Test
    @DisplayName("the delimiters are freshly randomised on every invocation")
    void delimitersAreUnguessableAndDifferPerInvocation() throws Exception {
        stubIterationRender(List.of(List.of("only")));

        prePostUtils.buildIterationValues("item", "items", null, new HashMap<>());
        prePostUtils.buildIterationValues("item", "items", null, new HashMap<>());

        List<Map<String, Object>> renders = capturedRenderData();
        assertEquals(2, renders.size());

        for (var render : renders) {
            for (var key : List.of(KEY_FIELD_DELIMITER, KEY_ROW_DELIMITER)) {
                String delimiter = String.valueOf(render.get(key));
                assertTrue(delimiter.matches("eddi(Field|Row)[0-9a-f]{32}"),
                        "a delimiter must carry a 128-bit random nonce that upstream content cannot guess, but was: " + delimiter);
            }
        }

        assertNotEquals(renders.get(0).get(KEY_ROW_DELIMITER), renders.get(1).get(KEY_ROW_DELIMITER),
                "a constant row delimiter would be forgeable by upstream content");
        assertNotEquals(renders.get(0).get(KEY_FIELD_DELIMITER), renders.get(1).get(KEY_FIELD_DELIMITER),
                "a constant field delimiter would be forgeable by upstream content");
        assertNotEquals(renders.get(0).get(KEY_FIELD_DELIMITER), renders.get(0).get(KEY_ROW_DELIMITER),
                "field and row delimiter must not collide");
    }

    @Test
    @DisplayName("the generated template text carries no nonce, so it stays a stable cache key")
    void templateTextIsIdenticalAcrossInvocations() throws Exception {
        stubIterationRender(List.of(List.of("only")));

        prePostUtils.buildIterationValues("item", "items", "item.active", new HashMap<>());
        prePostUtils.buildIterationValues("item", "items", "item.active", new HashMap<>());

        List<String> templates = capturedTemplates();
        assertEquals(2, templates.size());
        assertEquals(templates.get(0), templates.get(1),
                "the same instruction must produce the same template string — otherwise the compiled-template cache misses every time");
        assertTrue(templates.getFirst().contains("{" + KEY_ROW_DELIMITER + "}"),
                "the delimiter must be referenced as template data, not inlined: " + templates.getFirst());
        assertFalse(NONCE_IN_TEMPLATE.matcher(templates.getFirst()).find(),
                "no nonce may leak into the template text: " + templates.getFirst());
    }

    // ==================== real Qute engine ====================

    @Test
    @DisplayName("a real Qute render round-trips unsafe upstream values and compiles the template once")
    void realQuteRenderRoundTripsUnsafeValuesAndReusesTheCompiledTemplate() throws Exception {
        Engine realEngine = Engine.builder().addDefaults().strictRendering(false).build();
        Engine countingEngine = mock(Engine.class);
        when(countingEngine.parse(anyString())).thenAnswer(invocation -> realEngine.parse(invocation.getArgument(0, String.class)));

        var realPrePostUtils = new PrePostUtils(mock(IJsonSerialization.class), mock(IMemoryItemConverter.class),
                new TemplatingEngine(countingEngine), dataFactory);

        var texts = List.of("say \"hi\"", "line one\nline two", "back\\slash", "eddiRow0000");
        List<Map<String, String>> items = texts.stream().map(text -> Map.of("text", text)).toList();

        for (int run = 0; run < 3; run++) {
            var templateData = new HashMap<String, Object>();
            templateData.put("items", items);

            realPrePostUtils.runPostResponse(memory, outputPostResponse(), templateData, 200, false);
        }

        var captor = ArgumentCaptor.forClass(Context.class);
        verify(dataFactory, times(3)).createData(eq("context:output"), captor.capture());
        List<Object> output = asList(captor.getValue().getValue());

        assertEquals(texts.size(), output.size(), "one output item per iterated element");
        for (int i = 0; i < texts.size(); i++) {
            var valueAlternatives = asList(asMap(output.get(i)).get("valueAlternatives"));
            assertEquals(texts.get(i), asMap(valueAlternatives.getFirst()).get("text"), "value must round-trip verbatim through a real Qute render");
        }

        verify(countingEngine, times(1)).parse(anyString());
    }

    private static PostResponse outputPostResponse() {
        var outputInstruction = new OutputBuildingInstruction();
        outputInstruction.setHttpCodeValidator(new HttpCodeValidator(List.of(200), List.of()));
        outputInstruction.setOutputType("text");
        outputInstruction.setIterationObjectName("item");
        outputInstruction.setPathToTargetArray("items");
        outputInstruction.setOutputValue("{item.text}");
        var postResponse = new PostResponse();
        postResponse.setOutputBuildInstructions(List.of(outputInstruction));
        return postResponse;
    }

    private List<Object> capturedContextValue(String dataKey) {
        var captor = ArgumentCaptor.forClass(Context.class);
        verify(dataFactory).createData(eq(dataKey), captor.capture());
        return asList(captor.getValue().getValue());
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private List<Map<String, Object>> capturedRenderData() throws Exception {
        ArgumentCaptor<Map> captor = ArgumentCaptor.forClass(Map.class);
        verify(templatingEngine, times(2)).processTemplate(anyString(), captor.capture());
        List<Map<String, Object>> renders = new ArrayList<>();
        captor.getAllValues().forEach(render -> renders.add((Map<String, Object>) render));
        return renders;
    }

    private List<String> capturedTemplates() throws Exception {
        var captor = ArgumentCaptor.forClass(String.class);
        verify(templatingEngine, times(2)).processTemplate(captor.capture(), any());
        return captor.getAllValues();
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
     * out of the template DATA — that is where they live, because the template text
     * has to stay nonce-free to remain a stable compiled-template cache key — and
     * emit the given rows with them, exactly as a real render of upstream content
     * would.
     */
    private void stubIterationRender(List<List<String>> renderedRows) throws Exception {
        when(templatingEngine.processTemplate(anyString(), any())).thenAnswer(invocation -> {
            Map<String, Object> renderData = invocation.getArgument(1);
            String rowDelimiter = String.valueOf(renderData.get(KEY_ROW_DELIMITER));
            String fieldDelimiter = String.valueOf(renderData.get(KEY_FIELD_DELIMITER));

            var rendered = new StringBuilder();
            for (var row : renderedRows) {
                rendered.append(String.join(fieldDelimiter, row)).append(rowDelimiter);
            }
            return rendered.toString();
        });
    }
}
