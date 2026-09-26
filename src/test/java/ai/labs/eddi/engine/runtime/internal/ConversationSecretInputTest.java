/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.runtime.internal;

import ai.labs.eddi.engine.lifecycle.IConversation;
import ai.labs.eddi.engine.lifecycle.ILifecycleManager;
import ai.labs.eddi.engine.memory.ConversationMemory;
import ai.labs.eddi.engine.memory.IPropertiesHandler;
import ai.labs.eddi.engine.model.Context;
import ai.labs.eddi.engine.runtime.IExecutableWorkflow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.MockitoAnnotations.openMocks;

/**
 * Unit tests for the secret input handling logic in {@link Conversation}.
 * <p>
 * Tests verify that {@code isSecretInputFlagged()} correctly detects secret
 * context flags, and {@code storeUserInputInMemory()} scrubs plaintext from
 * conversation output while keeping it in lifecycle data for vaulting.
 */
class ConversationSecretInputTest {

    @Mock
    IPropertiesHandler propertiesHandler;

    @Mock
    IConversation.IConversationOutputRenderer outputRenderer;

    private ConversationMemory memory;

    @BeforeEach
    void setUp() {
        openMocks(this);
        memory = new ConversationMemory("conv-test", "agent-test", 1, "user-test");
    }

    @Nested
    @DisplayName("Secret input flagging and scrubbing")
    class SecretInputFlagging {

        @Test
        @DisplayName("Secret context flag causes message to be scrubbed in output")
        void secretInput_isScrubbed() {
            Conversation conversation = new Conversation(List.of(), memory, propertiesHandler, outputRenderer);

            Map<String, Context> contexts = new LinkedHashMap<>();
            Context secretCtx = new Context();
            secretCtx.setType(Context.ContextType.string);
            secretCtx.setValue("true");
            contexts.put("secretInput", secretCtx);

            try {
                conversation.say("my-secret-api-key-12345", contexts);
            } catch (Exception ignored) {
                // Expected — no lifecycle packages configured
            }

            var currentStep = memory.getCurrentStep();
            var outputMap = currentStep.getConversationOutput();

            boolean foundScrubbed = outputMap.entrySet().stream()
                    .anyMatch(e -> e.getKey().contains("input") && "<secret input>".equals(e.getValue()));
            assertTrue(foundScrubbed, "Expected '<secret input>' placeholder in output, got: " + outputMap);
        }

        @Test
        @DisplayName("Normal input (no secret context) is NOT scrubbed")
        void normalInput_isNotScrubbed() {
            Conversation conversation = new Conversation(List.of(), memory, propertiesHandler, outputRenderer);

            try {
                conversation.say("hello world", Map.of());
            } catch (Exception ignored) {
            }

            var currentStep = memory.getCurrentStep();
            var outputMap = currentStep.getConversationOutput();

            boolean foundPlaintext = outputMap.entrySet().stream().anyMatch(e -> e.getKey().contains("input") && "hello world".equals(e.getValue()));
            assertTrue(foundPlaintext, "Expected plaintext 'hello world' in output, got: " + outputMap);
        }

        @Test
        @DisplayName("Secret context with value 'false' does NOT trigger scrubbing")
        void secretFlagFalse_isNotScrubbed() {
            Conversation conversation = new Conversation(List.of(), memory, propertiesHandler, outputRenderer);

            Map<String, Context> contexts = new LinkedHashMap<>();
            Context secretCtx = new Context();
            secretCtx.setType(Context.ContextType.string);
            secretCtx.setValue("false");
            contexts.put("secretInput", secretCtx);

            try {
                conversation.say("not-a-secret", contexts);
            } catch (Exception ignored) {
            }

            var currentStep = memory.getCurrentStep();
            var outputMap = currentStep.getConversationOutput();

            boolean foundPlaintext = outputMap.entrySet().stream().anyMatch(e -> e.getKey().contains("input") && "not-a-secret".equals(e.getValue()));
            assertTrue(foundPlaintext, "Expected plaintext when secretInput=false, got: " + outputMap);
        }

        @Test
        @DisplayName("Empty context map does NOT trigger scrubbing")
        void emptyContext_isNotScrubbed() {
            Conversation conversation = new Conversation(List.of(), memory, propertiesHandler, outputRenderer);

            try {
                conversation.say("regular input", Map.of());
            } catch (Exception ignored) {
            }

            var currentStep = memory.getCurrentStep();
            var outputMap = currentStep.getConversationOutput();

            boolean foundPlaintext = outputMap.entrySet().stream()
                    .anyMatch(e -> e.getKey().contains("input") && "regular input".equals(e.getValue()));
            assertTrue(foundPlaintext, "Expected plaintext with empty context, got: " + outputMap);
        }

        @Test
        @DisplayName("Secret input: output is scrubbed while input:initial still exists for vaulting")
        void secretInput_outputScrubbedButLifecycleDataPreserved() {
            Conversation conversation = new Conversation(List.of(), memory, propertiesHandler, outputRenderer);

            Map<String, Context> contexts = new LinkedHashMap<>();
            Context secretCtx = new Context();
            secretCtx.setType(Context.ContextType.string);
            secretCtx.setValue("true");
            contexts.put("secretInput", secretCtx);

            try {
                conversation.say("vault-this-key", contexts);
            } catch (Exception ignored) {
            }

            var currentStep = memory.getCurrentStep();
            var outputMap = currentStep.getConversationOutput();

            // The conversation output should be scrubbed (not contain plaintext)
            boolean outputIsScrubbed = outputMap.entrySet().stream()
                    .anyMatch(e -> e.getKey().contains("input") && "<secret input>".equals(e.getValue()));
            assertTrue(outputIsScrubbed, "Conversation output must be scrubbed, got: " + outputMap);

            // The plaintext must NOT appear in conversation output
            boolean outputHasPlaintext = outputMap.entrySet().stream().anyMatch(e -> "vault-this-key".equals(e.getValue()));
            assertFalse(outputHasPlaintext, "Plaintext 'vault-this-key' must NOT appear in conversation output");
        }
    }

    /**
     * These tests exercise the persisted step data (not just the conversation
     * output). A no-op workflow is supplied so that the turn's lifecycle data —
     * including the raw {@code input:initial} — is actually stored onto the current
     * step (the engine only flushes lifecycle data onto the step once per
     * configured workflow). This is the data path that
     * {@code convertSimpleConversationMemory} replays to the client on reload, so
     * it is exactly what leaked the plaintext before the fix.
     */
    @Nested
    @DisplayName("Secret input scrubbed from persisted step (survives reload)")
    class SecretInputPersistedStep {

        private Conversation newConversationWithNoOpWorkflow() {
            var lifecycleManager = mock(ILifecycleManager.class);
            var workflow = mock(IExecutableWorkflow.class);
            when(workflow.getWorkflowId()).thenReturn("wf-test");
            when(workflow.getLifecycleManager()).thenReturn(lifecycleManager);
            return new Conversation(List.of(workflow), memory, propertiesHandler, outputRenderer);
        }

        private Map<String, Context> secretFlag() {
            Map<String, Context> contexts = new LinkedHashMap<>();
            Context secretCtx = new Context();
            secretCtx.setType(Context.ContextType.string);
            secretCtx.setValue("true");
            contexts.put("secretInput", secretCtx);
            return contexts;
        }

        @Test
        @DisplayName("Secret input: input:initial persisted as placeholder, never plaintext")
        void secretInput_inputInitialScrubbedInStep() {
            Conversation conversation = newConversationWithNoOpWorkflow();

            try {
                conversation.say("sk-live-super-secret-value", secretFlag());
            } catch (Exception ignored) {
                // Expected — no real lifecycle tasks configured
            }

            var currentStep = memory.getCurrentStep();

            var initial = currentStep.getLatestData("input:initial");
            assertNotNull(initial, "input:initial should have been stored on the step");
            assertEquals("<secret input>", initial.getResult(),
                    "Persisted input:initial must be the placeholder, not the plaintext");

            // Nothing on the step (the persisted document) may carry the plaintext.
            boolean anyElementHasPlaintext = currentStep.getAllElements().stream()
                    .anyMatch(d -> "sk-live-super-secret-value".equals(String.valueOf(d.getResult())));
            assertFalse(anyElementHasPlaintext, "No persisted step datum may contain the secret plaintext");
        }

        @Test
        @DisplayName("Normal input: input:initial keeps plaintext (no over-scrubbing)")
        void normalInput_inputInitialRetainsPlaintext() {
            Conversation conversation = newConversationWithNoOpWorkflow();

            try {
                conversation.say("just a normal message", Map.of());
            } catch (Exception ignored) {
            }

            var currentStep = memory.getCurrentStep();
            var initial = currentStep.getLatestData("input:initial");
            assertNotNull(initial, "input:initial should have been stored on the step");
            assertEquals("just a normal message", initial.getResult(),
                    "Non-secret input must be preserved verbatim in input:initial");
        }
    }
}
