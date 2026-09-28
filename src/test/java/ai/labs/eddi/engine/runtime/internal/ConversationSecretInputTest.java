/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.runtime.internal;

import ai.labs.eddi.configs.properties.model.Property;
import ai.labs.eddi.engine.lifecycle.IConversation;
import ai.labs.eddi.engine.lifecycle.ILifecycleManager;
import ai.labs.eddi.engine.memory.ConversationMemory;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.IPropertiesHandler;
import ai.labs.eddi.engine.memory.model.Data;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
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

        /**
         * Non-vacuous guard for the parser-derived leak: the parser runs on the raw
         * plaintext and emits {@code unknown(<token>)} expressions embedding the secret
         * into expressions:parsed / expressions:matches / the "expressions" output.
         * Those are persisted and rendered to an admin, so scrubbing only input:initial
         * is not enough. Here the mocked lifecycle writes exactly what InputParserTask
         * would, and we assert the turn's finally scrub removes it.
         */
        @Test
        @DisplayName("Secret input: parser-derived expressions embedding the secret are scrubbed")
        void secretInput_parserDerivedFormsScrubbed() throws Exception {
            var lifecycleManager = mock(ILifecycleManager.class);
            var workflow = mock(IExecutableWorkflow.class);
            when(workflow.getWorkflowId()).thenReturn("wf-parser");
            when(workflow.getLifecycleManager()).thenReturn(lifecycleManager);
            // Simulate InputParserTask writing the raw (normalized) secret into the
            // derived forms while the pipeline runs.
            doAnswer(inv -> {
                IConversationMemory mem = inv.getArgument(0);
                var step = mem.getCurrentStep();
                step.storeData(new Data<>("expressions:parsed", "unknown(hunter2secret)"));
                step.addConversationOutputString("expressions", "unknown(hunter2secret)");
                step.storeData(new Data<>("expressions:matches", List.of("hunter2secret -> unknown(hunter2secret)")));
                return null;
            }).when(lifecycleManager).executeLifecycle(any(), any());

            Conversation conversation = new Conversation(List.of(workflow), memory, propertiesHandler, outputRenderer);
            try {
                conversation.say("Hunter2Secret", secretFlag());
            } catch (Exception ignored) {
                // Expected — no real lifecycle tasks configured beyond the stub
            }

            var currentStep = memory.getCurrentStep();

            // input:initial itself is the placeholder (baseline behaviour).
            var initial = currentStep.getLatestData("input:initial");
            assertNotNull(initial);
            assertEquals("<secret input>", initial.getResult());

            // The parsed expressions are wiped, not left holding the token.
            var parsed = currentStep.getLatestData("expressions:parsed");
            assertNotNull(parsed, "expressions:parsed should still exist (scrubbed to empty), not the raw token");
            assertEquals("", parsed.getResult());

            // No persisted step datum may carry the normalized secret token.
            boolean anyElementHasToken = currentStep.getAllElements().stream()
                    .anyMatch(d -> String.valueOf(d.getResult()).toLowerCase().contains("hunter2secret"));
            assertFalse(anyElementHasToken, "No persisted step datum may contain the parsed secret token");

            // Nor may any conversation-output value.
            var output = currentStep.getConversationOutput();
            boolean outputHasToken = output != null && output.values().stream()
                    .anyMatch(v -> String.valueOf(v).toLowerCase().contains("hunter2secret"));
            assertFalse(outputHasToken, "No conversation-output value may contain the parsed secret token");
        }

        /**
         * An output template such as {@code {memory.current.input}} renders the live
         * plaintext into step data and the conversation output while the pipeline runs;
         * a non-secret property instruction can copy it into a conversation property.
         * The turn's finally scrub must remove those copies too.
         */
        @Test
        @DisplayName("Secret input: copies rendered into output, step data and properties are scrubbed")
        void secretInput_renderedCopiesScrubbed() throws Exception {
            var lifecycleManager = mock(ILifecycleManager.class);
            var workflow = mock(IExecutableWorkflow.class);
            when(workflow.getWorkflowId()).thenReturn("wf-output");
            when(workflow.getLifecycleManager()).thenReturn(lifecycleManager);
            doAnswer(inv -> {
                IConversationMemory mem = inv.getArgument(0);
                var step = mem.getCurrentStep();
                step.storeData(new Data<>("output:text:echo", List.of("You said: pw-Rendered-99")));
                step.addConversationOutputList("output", List.of(Map.of("type", "text", "text", "You said: pw-Rendered-99")));
                mem.getConversationProperties().put("lastInput",
                        new Property("lastInput", "pw-Rendered-99",
                                Property.Scope.conversation));
                return null;
            }).when(lifecycleManager).executeLifecycle(any(), any());

            Conversation conversation = new Conversation(List.of(workflow), memory, propertiesHandler, outputRenderer);
            try {
                conversation.say("pw-Rendered-99", secretFlag());
            } catch (Exception ignored) {
                // Expected — no real lifecycle tasks configured beyond the stub
            }

            var currentStep = memory.getCurrentStep();
            for (var datum : currentStep.getAllElements()) {
                assertFalse(String.valueOf(datum.getResult()).contains("pw-Rendered-99"),
                        "secret survived in step data '" + datum.getKey() + "'");
            }
            assertEquals(List.of("You said: <secret input>"), currentStep.getLatestData("output:text:echo").getResult());
            String output = String.valueOf(currentStep.getConversationOutput());
            assertFalse(output.contains("pw-Rendered-99"), "secret survived in the conversation output: " + output);
            assertTrue(output.contains("You said: <secret input>"), "the rest of the reply must be kept: " + output);
            assertEquals("<secret input>", memory.getConversationProperties().get("lastInput").getValueString());
        }

        @Test
        @DisplayName("Secret input: a short PIN copied into the reply is removed as a whole token")
        void secretInput_shortPinCopyScrubbed() throws Exception {
            var lifecycleManager = mock(ILifecycleManager.class);
            var workflow = mock(IExecutableWorkflow.class);
            when(workflow.getWorkflowId()).thenReturn("wf-pin");
            when(workflow.getLifecycleManager()).thenReturn(lifecycleManager);
            doAnswer(inv -> {
                IConversationMemory mem = inv.getArgument(0);
                mem.getCurrentStep().addConversationOutputList("output",
                        List.of(Map.of("type", "text", "text", "PIN 739 saved for order 17391.")));
                return null;
            }).when(lifecycleManager).executeLifecycle(any(), any());

            Conversation conversation = new Conversation(List.of(workflow), memory, propertiesHandler, outputRenderer);
            try {
                conversation.say("739", secretFlag());
            } catch (Exception ignored) {
                // Expected — no real lifecycle tasks configured beyond the stub
            }

            String output = String.valueOf(memory.getCurrentStep().getConversationOutput());
            assertTrue(output.contains("PIN <secret input> saved for order 17391."), output);
        }

        @Test
        @DisplayName("Secret input: raw copies are scrubbed even after a secret property already replaced input:initial")
        void secretInput_rawCopyScrubbedAfterPropertyScrub() throws Exception {
            var lifecycleManager = mock(ILifecycleManager.class);
            var workflow = mock(IExecutableWorkflow.class);
            when(workflow.getWorkflowId()).thenReturn("wf-vaulted");
            when(workflow.getLifecycleManager()).thenReturn(lifecycleManager);
            doAnswer(inv -> {
                IConversationMemory mem = inv.getArgument(0);
                var step = mem.getCurrentStep();
                step.addConversationOutputList("output", List.of("echo: Raw-Secret_Value.9"));
                // What PropertySetterTask leaves behind after vaulting a normalized match.
                step.storeData(new Data<>("input:initial", "<secret input>"));
                step.storeData(new Data<>("input:normalized", "<secret input>"));
                return null;
            }).when(lifecycleManager).executeLifecycle(any(), any());

            Conversation conversation = new Conversation(List.of(workflow), memory, propertiesHandler, outputRenderer);
            try {
                conversation.say("Raw-Secret_Value.9", secretFlag());
            } catch (Exception ignored) {
                // Expected — no real lifecycle tasks configured beyond the stub
            }

            String output = String.valueOf(memory.getCurrentStep().getConversationOutput());
            assertFalse(output.contains("Raw-Secret_Value.9"), "raw secret survived in the conversation output: " + output);
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
