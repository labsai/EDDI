/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.properties.impl;

import ai.labs.eddi.configs.properties.model.Property.Scope;
import ai.labs.eddi.configs.properties.model.PropertyInstruction;
import ai.labs.eddi.engine.lifecycle.exceptions.LifecycleException;
import ai.labs.eddi.engine.memory.ConversationMemory;
import ai.labs.eddi.engine.memory.DataFactory;
import ai.labs.eddi.engine.memory.IConversationMemory.IWritableConversationStep;
import ai.labs.eddi.engine.memory.IData;
import ai.labs.eddi.engine.memory.IMemoryItemConverter;
import ai.labs.eddi.engine.memory.model.Data;
import ai.labs.eddi.engine.model.Context;
import ai.labs.eddi.engine.runtime.client.configuration.IResourceClientLibrary;
import ai.labs.eddi.modules.nlp.expressions.utilities.IExpressionProvider;
import ai.labs.eddi.modules.properties.IPropertySetter;
import ai.labs.eddi.modules.properties.model.SetOnActions;
import ai.labs.eddi.modules.templating.ITemplatingEngine;
import ai.labs.eddi.secrets.ISecretProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * G2 — a {@code scope: "secret"} write must leave NO copy of the plaintext in
 * the conversation step that gets persisted for the turn.
 * <p>
 * These tests run against a REAL {@link ConversationMemory} / conversation step
 * (not a mocked one) precisely because the two defects were about step data
 * nobody was looking at:
 * <ul>
 * <li>{@code input:normalized} — written by {@code InputParserTask} into the
 * same step and serialized into the stored document just like
 * {@code input:initial}.</li>
 * <li>the byte-equality guard — with any parser normalizer configured the
 * resolved secret is the NORMALIZED input, so it never equals the raw
 * {@code input:initial} and the scrub silently did nothing.</li>
 * </ul>
 */
class PropertySetterTaskSecretScrubTest {

    /** What the user typed. */
    private static final String RAW_INPUT = "sk-live_abc.123";
    /**
     * What a punctuation normalizer makes of it — and therefore what
     * {@code {memory.current.input}} resolves to.
     */
    private static final String NORMALIZED_INPUT = "sk live abc 123";
    private static final String PLACEHOLDER = "<secret input>";

    private ISecretProvider secretProvider;
    private PropertySetterTask task;
    private ConversationMemory memory;

    @BeforeEach
    void setUp() throws Exception {
        var expressionProvider = mock(IExpressionProvider.class);
        var memoryItemConverter = mock(IMemoryItemConverter.class);
        var templatingEngine = mock(ITemplatingEngine.class);
        var resourceClientLibrary = mock(IResourceClientLibrary.class);
        secretProvider = mock(ISecretProvider.class);

        memory = new ConversationMemory("aabbccddeeff112233445566", "agent-1", 1, "user-1");
        when(memoryItemConverter.convert(any())).thenReturn(new HashMap<>());
        when(templatingEngine.processTemplate(anyString(), anyMap()))
                .thenAnswer(invocation -> invocation.getArgument(0));

        task = new PropertySetterTask(expressionProvider, memoryItemConverter, templatingEngine,
                new DataFactory(), resourceClientLibrary, new ObjectMapper(), secretProvider);
    }

    /**
     * Reproduces the real pipeline order: the engine writes the raw input, the
     * parser (always the first workflow step) writes the normalized copy and
     * overwrites the echoed conversation output with it.
     */
    private IWritableConversationStep stepWithParsedInput() {
        IWritableConversationStep step = memory.getCurrentStep();
        step.storeData(new Data<>("input:initial", RAW_INPUT));
        step.storeData(new Data<>("input:normalized", NORMALIZED_INPUT));
        step.addConversationOutputString("input", NORMALIZED_INPUT);
        step.storeData(new Data<>("actions", List.of("store_secret")));
        return step;
    }

    /** A property setter that stores {@code apiKey} with {@code scope: secret}. */
    private IPropertySetter secretPropertySetter(String resolvedValue) {
        var instruction = new PropertyInstruction();
        instruction.setName("apiKey");
        instruction.setValueString(resolvedValue);
        instruction.setScope(Scope.secret);
        instruction.setOverride(true);

        var setOnActions = new SetOnActions();
        setOnActions.setActions(List.of("store_secret"));
        setOnActions.setSetProperties(List.of(instruction));

        var propertySetter = mock(IPropertySetter.class);
        when(propertySetter.getSetOnActionsList()).thenReturn(List.of(setOnActions));
        when(propertySetter.extractProperties(any())).thenReturn(new LinkedList<>());
        return propertySetter;
    }

    private void assertNoPlaintextAnywhere(IWritableConversationStep step) {
        for (IData<?> data : step.getAllElements()) {
            assertFalse(String.valueOf(data.getResult()).contains(RAW_INPUT),
                    "raw secret survived in step data '" + data.getKey() + "'");
            assertFalse(String.valueOf(data.getResult()).contains(NORMALIZED_INPUT),
                    "normalized secret survived in step data '" + data.getKey() + "'");
        }
        String output = String.valueOf(step.getConversationOutput());
        assertFalse(output.contains(RAW_INPUT), "raw secret survived in the conversation output: " + output);
        assertFalse(output.contains(NORMALIZED_INPUT), "normalized secret survived in the conversation output: " + output);
    }

    @Test
    @DisplayName("the parser's input:normalized copy is scrubbed too, not only input:initial")
    void normalizedInputCopyIsScrubbed() throws Exception {
        IWritableConversationStep step = stepWithParsedInput();

        task.execute(memory, secretPropertySetter(NORMALIZED_INPUT));

        assertEquals(PLACEHOLDER, step.<String>getData("input:normalized").getResult(),
                "InputParserTask's copy of the plaintext is persisted with the step and must be scrubbed");
        assertEquals(PLACEHOLDER, step.<String>getData("input:initial").getResult());
        assertNoPlaintextAnywhere(step);
    }

    @Test
    @DisplayName("a normalized secret still scrubs the RAW input — the scrub is not gated on byte equality")
    void normalizationDoesNotDefeatTheScrub() throws Exception {
        IWritableConversationStep step = stepWithParsedInput();

        // The resolved property value is the NORMALIZED input; it is NOT equal to the
        // raw input:initial value, which is exactly what silently disabled the scrub.
        assertFalse(NORMALIZED_INPUT.equals(RAW_INPUT));

        task.execute(memory, secretPropertySetter(NORMALIZED_INPUT));

        assertEquals(PLACEHOLDER, step.<String>getData("input:initial").getResult(),
                "the raw input must be scrubbed even though it differs from the resolved secret");
        assertEquals(PLACEHOLDER, step.getConversationOutput().get("input"));
        assertNoPlaintextAnywhere(step);
    }

    @Test
    @DisplayName("the vault-failure abort path leaves no plaintext behind either")
    void vaultFailureAbortAlsoScrubsEveryCopy() throws Exception {
        doThrow(new ISecretProvider.SecretProviderException("Vault unavailable"))
                .when(secretProvider).store(any(), anyString(), anyString(), anyList());

        IWritableConversationStep step = stepWithParsedInput();

        assertThrows(LifecycleException.class, () -> task.execute(memory, secretPropertySetter(NORMALIZED_INPUT)));

        assertEquals(PLACEHOLDER, step.<String>getData("input:initial").getResult());
        assertEquals(PLACEHOLDER, step.<String>getData("input:normalized").getResult());
        assertNoPlaintextAnywhere(step);
    }

    @Test
    @DisplayName("a secret supplied through a context variable is scrubbed from the persisted context datum")
    void contextSuppliedSecretIsScrubbed() throws Exception {
        IWritableConversationStep step = memory.getCurrentStep();
        step.storeData(new Data<>("actions", List.of("store_secret")));
        step.storeData(new Data<>("context:apiKey", new Context(Context.ContextType.string, RAW_INPUT)));

        task.execute(memory, secretPropertySetter(RAW_INPUT));

        Context stored = step.<Context>getData("context:apiKey").getResult();
        assertEquals(PLACEHOLDER, stored.getValue(),
                "the context datum is serialized into the conversation document and must not keep the plaintext");
    }

    @Test
    @DisplayName("an unrelated short input is NOT scrubbed away by a secret coming from elsewhere")
    void unrelatedInputIsPreserved() throws Exception {
        IWritableConversationStep step = memory.getCurrentStep();
        step.storeData(new Data<>("input:initial", "ok"));
        step.addConversationOutputString("input", "ok");
        step.storeData(new Data<>("actions", List.of("store_secret")));

        task.execute(memory, secretPropertySetter("static-config-literal-key"));

        assertEquals("ok", step.<String>getData("input:initial").getResult(),
                "a two-character input must not be wiped because it happens to appear inside the secret");
    }
}
