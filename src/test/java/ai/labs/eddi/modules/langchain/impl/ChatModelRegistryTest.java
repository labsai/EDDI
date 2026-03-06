package ai.labs.eddi.modules.langchain.impl;

import ai.labs.eddi.modules.langchain.impl.builder.ILanguageModelBuilder;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import jakarta.inject.Provider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static dev.langchain4j.data.message.AiMessage.aiMessage;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link ChatModelRegistry} — both sync and streaming model caching.
 */
class ChatModelRegistryTest {

    private ChatModelRegistry registry;
    private ChatModel mockSyncModel;
    private StreamingChatModel mockStreamingModel;

    @BeforeEach
    void setUp() {
        mockSyncModel = new ChatModel() {
            @Override
            public ChatResponse chat(List<ChatMessage> messages) {
                return ChatResponse.builder().aiMessage(aiMessage("ok")).build();
            }
        };
        mockStreamingModel = new StreamingChatModel() {
            @Override
            public void chat(ChatRequest chatRequest, StreamingChatResponseHandler handler) {
                // no-op for test
            }
        };

        Map<String, Provider<ILanguageModelBuilder>> builders = new HashMap<>();
        builders.put("openai", () -> new ILanguageModelBuilder() {
            @Override
            public ChatModel build(Map<String, String> parameters) {
                return mockSyncModel;
            }

            @Override
            public StreamingChatModel buildStreaming(Map<String, String> parameters) {
                return mockStreamingModel;
            }
        });
        builders.put("unsupported", () -> new ILanguageModelBuilder() {
            @Override
            public ChatModel build(Map<String, String> parameters) {
                return mockSyncModel;
            }
            // Uses default buildStreaming — throws UnsupportedOperationException
        });

        registry = new ChatModelRegistry(builders);
    }

    @Nested
    @DisplayName("Sync model tests")
    class SyncTests {

        @Test
        @DisplayName("getOrCreate creates model for valid type")
        void getOrCreate_validType_createsModel() throws Exception {
            ChatModel model = registry.getOrCreate("openai", Map.of("apiKey", "test"));
            assertNotNull(model);
            assertSame(mockSyncModel, model);
        }

        @Test
        @DisplayName("getOrCreate caches models by type+params")
        void getOrCreate_sameParams_returnsCached() throws Exception {
            var params = Map.of("apiKey", "test");
            ChatModel first = registry.getOrCreate("openai", params);
            ChatModel second = registry.getOrCreate("openai", params);
            assertSame(first, second);
        }

        @Test
        @DisplayName("getOrCreate throws for unknown type")
        void getOrCreate_unknownType_throwsException() {
            assertThrows(ChatModelRegistry.UnsupportedLangchainTaskException.class,
                    () -> registry.getOrCreate("unknown", Map.of()));
        }

        @Test
        @DisplayName("getOrCreate filters non-model params from cache key")
        void getOrCreate_filtersSystemMessage() throws Exception {
            var params1 = new HashMap<String, String>();
            params1.put("apiKey", "test");
            params1.put("systemMessage", "be helpful");

            var params2 = new HashMap<String, String>();
            params2.put("apiKey", "test");
            params2.put("systemMessage", "be different");

            ChatModel first = registry.getOrCreate("openai", params1);
            ChatModel second = registry.getOrCreate("openai", params2);
            assertSame(first, second);
        }
    }

    @Nested
    @DisplayName("Streaming model tests")
    class StreamingTests {

        @Test
        @DisplayName("getOrCreateStreaming creates streaming model for supported type")
        void getOrCreateStreaming_supportedType_createsModel() throws Exception {
            StreamingChatModel model = registry.getOrCreateStreaming("openai", Map.of("apiKey", "test"));
            assertNotNull(model);
            assertSame(mockStreamingModel, model);
        }

        @Test
        @DisplayName("getOrCreateStreaming caches streaming models")
        void getOrCreateStreaming_sameParams_returnsCached() throws Exception {
            var params = Map.of("apiKey", "test");
            StreamingChatModel first = registry.getOrCreateStreaming("openai", params);
            StreamingChatModel second = registry.getOrCreateStreaming("openai", params);
            assertSame(first, second);
        }

        @Test
        @DisplayName("getOrCreateStreaming returns null for unsupported builder")
        void getOrCreateStreaming_unsupportedBuilder_returnsNull() throws Exception {
            StreamingChatModel model = registry.getOrCreateStreaming("unsupported", Map.of());
            assertNull(model);
        }

        @Test
        @DisplayName("getOrCreateStreaming throws for unknown type")
        void getOrCreateStreaming_unknownType_throwsException() {
            assertThrows(ChatModelRegistry.UnsupportedLangchainTaskException.class,
                    () -> registry.getOrCreateStreaming("unknown", Map.of()));
        }

        @Test
        @DisplayName("Streaming and sync caches are independent")
        void getOrCreate_streamingAndSyncAreIndependent() throws Exception {
            var params = Map.of("apiKey", "test");
            ChatModel syncModel = registry.getOrCreate("openai", params);
            StreamingChatModel streamingModel = registry.getOrCreateStreaming("openai", params);

            assertNotNull(syncModel);
            assertNotNull(streamingModel);
            assertNotSame(syncModel, streamingModel);
        }
    }
}
