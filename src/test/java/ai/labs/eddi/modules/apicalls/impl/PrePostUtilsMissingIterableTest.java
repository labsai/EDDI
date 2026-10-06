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
import ai.labs.eddi.modules.properties.impl.SecretPropertyVault;
import ai.labs.eddi.modules.templating.impl.TemplatingEngine;
import io.quarkus.qute.Engine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * An LLM reply without a {@code quickReplies} array must yield no quick
 * replies, not fail the turn with {@code Iteration error - ... not found}. Runs
 * the real Qute engine, configured like application.properties (strict
 * rendering off).
 */
@DisplayName("PrePostUtils — a missing iterable renders as an empty loop")
class PrePostUtilsMissingIterableTest {

    private IDataFactory dataFactory;
    private IConversationMemory memory;
    private PrePostUtils prePostUtils;

    @BeforeEach
    void setUp() {
        dataFactory = mock(IDataFactory.class);
        var engine = new TemplatingEngine(Engine.builder().addDefaults().strictRendering(false).build());
        prePostUtils = new PrePostUtils(mock(IJsonSerialization.class), mock(IMemoryItemConverter.class), engine, dataFactory,
                mock(SecretPropertyVault.class));

        memory = mock(IConversationMemory.class);
        var currentStep = mock(IWritableConversationStep.class);
        when(memory.getCurrentStep()).thenReturn(currentStep);
        var conversationProperties = mock(ConversationProperties.class);
        when(memory.getConversationProperties()).thenReturn(conversationProperties);
        when(conversationProperties.toMap()).thenReturn(new HashMap<>());
    }

    @Test
    @DisplayName("missing quick-reply array: no quick replies and no exception")
    void missingQuickReplyArray() {
        var data = new HashMap<String, Object>();
        data.put("properties", Map.of("aiOutputObject", Map.of("text", "hi")));

        assertDoesNotThrow(() -> prePostUtils.runPostResponse(memory, quickReplyPostResponse("properties.aiOutputObject.quickReplies"), data,
                200, false));

        assertTrue(captured("context:quickReplies").isEmpty());
    }

    @Test
    @DisplayName("present quick-reply array: quick replies are built as before")
    void presentQuickReplyArray() throws Exception {
        var data = new HashMap<String, Object>();
        data.put("properties", Map.of("aiOutputObject", Map.of("quickReplies", List.of(Map.of("v", "yes"), Map.of("v", "no")))));

        prePostUtils.runPostResponse(memory, quickReplyPostResponse("properties.aiOutputObject.quickReplies"), data, 200, false);

        assertEquals(2, captured("context:quickReplies").size());
    }

    @Test
    @DisplayName("an author-written .orEmpty is not doubled")
    void alreadyOrEmpty() {
        var data = new HashMap<String, Object>();
        assertDoesNotThrow(() -> prePostUtils.runPostResponse(memory, quickReplyPostResponse("properties.nothing.orEmpty"), data, 200, false));
        assertTrue(captured("context:quickReplies").isEmpty());
    }

    @Test
    @DisplayName("missing output array: no output items and no exception")
    void missingOutputArray() {
        var instruction = new OutputBuildingInstruction();
        instruction.setHttpCodeValidator(new HttpCodeValidator(List.of(200), List.of()));
        instruction.setOutputType("text");
        instruction.setIterationObjectName("item");
        instruction.setPathToTargetArray("items");
        instruction.setOutputValue("{item.text}");
        var postResponse = new PostResponse();
        postResponse.setOutputBuildInstructions(List.of(instruction));

        assertDoesNotThrow(() -> prePostUtils.runPostResponse(memory, postResponse, new HashMap<>(), 200, false));

        assertTrue(captured("context:output").isEmpty());
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {"items|items.orEmpty", "  items  |items.orEmpty", "a.b.c|a.b.c.orEmpty", "items.orEmpty|items.orEmpty",
            "items.orEmpty()|items.orEmpty()", "a ?: b|a ?: b", "a ?? b|a ?? b", "a or b|a or b", "1..5|1..5"})
    @DisplayName("orEmpty is appended unless the author already handled a missing value")
    void orEmptyNormalisation(String input, String expected) {
        assertEquals(expected, PrePostUtils.orEmpty(input));
    }

    @Test
    @DisplayName("orEmpty leaves null alone")
    void orEmptyNull() {
        assertNull(PrePostUtils.orEmpty(null));
    }

    private List<Object> captured(String key) {
        var captor = ArgumentCaptor.forClass(Context.class);
        verify(dataFactory).createData(eq(key), captor.capture());
        assertInstanceOf(List.class, captor.getValue().getValue());
        @SuppressWarnings("unchecked")
        List<Object> value = (List<Object>) captor.getValue().getValue();
        return value;
    }

    private static PostResponse quickReplyPostResponse(String path) {
        var instruction = new QuickRepliesBuildingInstruction();
        instruction.setHttpCodeValidator(new HttpCodeValidator(List.of(200), List.of()));
        instruction.setIterationObjectName("qr");
        instruction.setPathToTargetArray(path);
        instruction.setQuickReplyValue("{qr.v}");
        instruction.setQuickReplyExpressions("");
        var postResponse = new PostResponse();
        postResponse.setQrBuildInstructions(List.of(instruction));
        return postResponse;
    }
}
