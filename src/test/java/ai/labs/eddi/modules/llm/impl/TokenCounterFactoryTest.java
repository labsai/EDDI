package ai.labs.eddi.modules.llm.impl;

import dev.langchain4j.data.message.*;
import dev.langchain4j.model.TokenCountEstimator;
import dev.langchain4j.model.openai.OpenAiTokenCountEstimator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TokenCounterFactoryTest {

    private TokenCounterFactory factory;

    @BeforeEach
    void setUp() {
        factory = new TokenCounterFactory();
    }

    @Nested
    @DisplayName("getEstimator")
    class GetEstimatorTests {

        @Test
        @DisplayName("openai model type → returns OpenAiTokenCountEstimator")
        void openai_returnsOpenAiEstimator() {
            TokenCountEstimator estimator = factory.getEstimator("openai", "gpt-4o");
            assertInstanceOf(OpenAiTokenCountEstimator.class, estimator);
        }

        @Test
        @DisplayName("azure-openai model type → returns OpenAiTokenCountEstimator")
        void azureOpenai_returnsOpenAiEstimator() {
            TokenCountEstimator estimator = factory.getEstimator("azure-openai", "gpt-4o");
            assertInstanceOf(OpenAiTokenCountEstimator.class, estimator);
        }

        @Test
        @DisplayName("unknown model type → returns approximate estimator")
        void unknownType_returnsApproximate() {
            TokenCountEstimator estimator = factory.getEstimator("anthropic", null);
            assertInstanceOf(TokenCounterFactory.ApproximateTokenCountEstimator.class, estimator);
        }

        @Test
        @DisplayName("null model type → returns approximate estimator")
        void nullType_returnsApproximate() {
            TokenCountEstimator estimator = factory.getEstimator(null, null);
            assertInstanceOf(TokenCounterFactory.ApproximateTokenCountEstimator.class, estimator);
        }

        @Test
        @DisplayName("openai with null model name → uses gpt-4o default")
        void openai_nullModelName_usesDefault() {
            TokenCountEstimator estimator = factory.getEstimator("openai", null);
            assertInstanceOf(OpenAiTokenCountEstimator.class, estimator);
            // Verify it works with the default model
            assertTrue(estimator.estimateTokenCountInText("Hello world") > 0);
        }
    }

    @Nested
    @DisplayName("ApproximateTokenCountEstimator")
    class ApproximateEstimatorTests {

        @Test
        @DisplayName("estimates tokens as chars / 4")
        void estimatesTokensApproximately() {
            var estimator = new TokenCounterFactory.ApproximateTokenCountEstimator();
            // "Hello world!" = 12 chars → 3 tokens
            assertEquals(3, estimator.estimateTokenCountInText("Hello world!"));
        }

        @Test
        @DisplayName("returns at least 1 for non-empty text")
        void returnsMinimumOne() {
            var estimator = new TokenCounterFactory.ApproximateTokenCountEstimator();
            // "Hi" = 2 chars → 0 by division, but minimum 1
            assertEquals(1, estimator.estimateTokenCountInText("Hi"));
        }

        @Test
        @DisplayName("returns 0 for null or empty text")
        void returnsZeroForEmpty() {
            var estimator = new TokenCounterFactory.ApproximateTokenCountEstimator();
            assertEquals(0, estimator.estimateTokenCountInText(null));
            assertEquals(0, estimator.estimateTokenCountInText(""));
        }

        @Test
        @DisplayName("estimateTokenCountInMessage works for different message types")
        void estimateTokenCountInMessage_variousTypes() {
            var estimator = new TokenCounterFactory.ApproximateTokenCountEstimator();

            // SystemMessage
            int systemTokens = estimator.estimateTokenCountInMessage(new SystemMessage("System prompt here"));
            assertTrue(systemTokens > 0);

            // AiMessage
            int aiTokens = estimator.estimateTokenCountInMessage(AiMessage.from("AI response text"));
            assertTrue(aiTokens > 0);

            // UserMessage
            int userTokens = estimator.estimateTokenCountInMessage(UserMessage.from("User input text"));
            assertTrue(userTokens > 0);
        }

        @Test
        @DisplayName("estimateTokenCountInMessages sums all messages")
        void estimateTokenCountInMessages_sumsAll() {
            var estimator = new TokenCounterFactory.ApproximateTokenCountEstimator();

            List<ChatMessage> messages = List.of(new SystemMessage("System prompt"), UserMessage.from("Hello"), AiMessage.from("World"));

            int total = estimator.estimateTokenCountInMessages(messages);
            int expectedMin = ("System prompt".length() + "Hello".length() + "World".length()) / 4;
            assertTrue(total >= expectedMin, "Total should be at least the sum of individual estimates");
        }
    }

    @Nested
    @DisplayName("extractText")
    class ExtractTextTests {

        @Test
        @DisplayName("extracts text from SystemMessage")
        void extractsFromSystem() {
            assertEquals("Hello", TokenCounterFactory.extractText(new SystemMessage("Hello")));
        }

        @Test
        @DisplayName("extracts text from AiMessage")
        void extractsFromAi() {
            assertEquals("Response", TokenCounterFactory.extractText(AiMessage.from("Response")));
        }

        @Test
        @DisplayName("extracts text from UserMessage with single text")
        void extractsFromUserSingleText() {
            assertEquals("User input", TokenCounterFactory.extractText(UserMessage.from("User input")));
        }

        @Test
        @DisplayName("handles AiMessage with null text")
        void handlesNullAiText() {
            // AiMessage with tool execution request may have null text
            assertEquals("", TokenCounterFactory.extractText(AiMessage.from("")));
        }
    }
}
