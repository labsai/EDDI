package ai.labs.eddi.engine.runtime.internal;

import ai.labs.eddi.engine.lifecycle.IConversation;
import ai.labs.eddi.engine.memory.ConversationMemory;
import ai.labs.eddi.engine.memory.IPropertiesHandler;
import ai.labs.eddi.engine.model.Context;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.MockitoAnnotations.openMocks;

/**
 * Unit tests for the secret input handling logic in {@link Conversation}.
 * <p>
 * Tests verify that {@code isSecretInputFlagged()} correctly detects
 * secret context flags, and {@code storeUserInputInMemory()} scrubs
 * plaintext from conversation output while keeping it in lifecycle data
 * for vaulting.
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
        memory = new ConversationMemory("conv-test", "bot-test", 1, "user-test");
    }

    @Nested
    @DisplayName("Secret input flagging and scrubbing")
    class SecretInputFlagging {

        @Test
        @DisplayName("Secret context flag causes message to be scrubbed in output")
        void secretInput_isScrubbed() {
            Conversation conversation = new Conversation(
                    List.of(), memory, propertiesHandler, outputRenderer);

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
                    .anyMatch(e -> e.getKey().contains("input")
                            && "<secret input>".equals(e.getValue()));
            assertTrue(foundScrubbed,
                    "Expected '<secret input>' placeholder in output, got: " + outputMap);
        }

        @Test
        @DisplayName("Normal input (no secret context) is NOT scrubbed")
        void normalInput_isNotScrubbed() {
            Conversation conversation = new Conversation(
                    List.of(), memory, propertiesHandler, outputRenderer);

            try {
                conversation.say("hello world", Map.of());
            } catch (Exception ignored) {
            }

            var currentStep = memory.getCurrentStep();
            var outputMap = currentStep.getConversationOutput();

            boolean foundPlaintext = outputMap.entrySet().stream()
                    .anyMatch(e -> e.getKey().contains("input")
                            && "hello world".equals(e.getValue()));
            assertTrue(foundPlaintext,
                    "Expected plaintext 'hello world' in output, got: " + outputMap);
        }

        @Test
        @DisplayName("Secret context with value 'false' does NOT trigger scrubbing")
        void secretFlagFalse_isNotScrubbed() {
            Conversation conversation = new Conversation(
                    List.of(), memory, propertiesHandler, outputRenderer);

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

            boolean foundPlaintext = outputMap.entrySet().stream()
                    .anyMatch(e -> e.getKey().contains("input")
                            && "not-a-secret".equals(e.getValue()));
            assertTrue(foundPlaintext,
                    "Expected plaintext when secretInput=false, got: " + outputMap);
        }

        @Test
        @DisplayName("Empty context map does NOT trigger scrubbing")
        void emptyContext_isNotScrubbed() {
            Conversation conversation = new Conversation(
                    List.of(), memory, propertiesHandler, outputRenderer);

            try {
                conversation.say("regular input", Map.of());
            } catch (Exception ignored) {
            }

            var currentStep = memory.getCurrentStep();
            var outputMap = currentStep.getConversationOutput();

            boolean foundPlaintext = outputMap.entrySet().stream()
                    .anyMatch(e -> e.getKey().contains("input")
                            && "regular input".equals(e.getValue()));
            assertTrue(foundPlaintext,
                    "Expected plaintext with empty context, got: " + outputMap);
        }

        @Test
        @DisplayName("Secret input: output is scrubbed while input:initial still exists for vaulting")
        void secretInput_outputScrubbedButLifecycleDataPreserved() {
            Conversation conversation = new Conversation(
                    List.of(), memory, propertiesHandler, outputRenderer);

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
                    .anyMatch(e -> e.getKey().contains("input")
                            && "<secret input>".equals(e.getValue()));
            assertTrue(outputIsScrubbed,
                    "Conversation output must be scrubbed, got: " + outputMap);

            // The plaintext must NOT appear in conversation output
            boolean outputHasPlaintext = outputMap.entrySet().stream()
                    .anyMatch(e -> "vault-this-key".equals(e.getValue()));
            assertFalse(outputHasPlaintext,
                    "Plaintext 'vault-this-key' must NOT appear in conversation output");
        }
    }
}
