package ai.labs.eddi.modules.llm.memory;

import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.engine.memory.IConversationMemoryStore;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot;
import dev.langchain4j.data.message.ChatMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Extended tests for {@link EddiChatMemoryStore} covering getMessages,
 * updateMessages, and deleteMessages with various success and error paths.
 */
@DisplayName("EddiChatMemoryStore Extended Tests")
class EddiChatMemoryStoreExtendedTest {

    private EddiChatMemoryStore store;
    private IConversationMemoryStore conversationMemoryStore;

    @BeforeEach
    void setUp() {
        store = new EddiChatMemoryStore();
        conversationMemoryStore = mock(IConversationMemoryStore.class);
        store.conversationMemoryStore = conversationMemoryStore;
    }

    @Nested
    @DisplayName("getMessages")
    class GetMessages {

        @Test
        @DisplayName("should return empty list for new conversation (not found)")
        void returnsEmptyForNotFound() throws Exception {
            when(conversationMemoryStore.loadConversationMemorySnapshot("conv-1"))
                    .thenThrow(new IResourceStore.ResourceNotFoundException("not found"));

            List<ChatMessage> messages = store.getMessages("conv-1");

            assertNotNull(messages);
            assertTrue(messages.isEmpty());
        }

        @Test
        @DisplayName("should return empty list on ResourceStoreException")
        void returnsEmptyOnStoreException() throws Exception {
            when(conversationMemoryStore.loadConversationMemorySnapshot("conv-1"))
                    .thenThrow(new IResourceStore.ResourceStoreException("db error"));

            List<ChatMessage> messages = store.getMessages("conv-1");

            assertNotNull(messages);
            assertTrue(messages.isEmpty());
        }

        @Test
        @DisplayName("should return messages from existing conversation")
        void returnsMessagesFromSnapshot() throws Exception {
            ConversationMemorySnapshot snapshot = new ConversationMemorySnapshot();
            snapshot.setConversationSteps(new ArrayList<>());

            when(conversationMemoryStore.loadConversationMemorySnapshot("conv-1"))
                    .thenReturn(snapshot);

            List<ChatMessage> messages = store.getMessages("conv-1");

            assertNotNull(messages);
            assertTrue(messages.isEmpty());
        }
    }

    @Nested
    @DisplayName("updateMessages")
    class UpdateMessages {

        @Test
        @DisplayName("should be a no-op (EDDI lifecycle manages persistence)")
        void isNoOp() {
            assertDoesNotThrow(() -> store.updateMessages("conv-1", List.of()));
            verifyNoInteractions(conversationMemoryStore);
        }
    }

    @Nested
    @DisplayName("deleteMessages")
    class DeleteMessages {

        @Test
        @DisplayName("should delete conversation successfully")
        void deletesSuccessfully() throws Exception {
            doNothing().when(conversationMemoryStore).deleteConversationMemorySnapshot("conv-1");

            assertDoesNotThrow(() -> store.deleteMessages("conv-1"));
            verify(conversationMemoryStore).deleteConversationMemorySnapshot("conv-1");
        }

        @Test
        @DisplayName("should handle non-existent conversation gracefully")
        void handlesNotFound() throws Exception {
            doThrow(new IResourceStore.ResourceNotFoundException("not found"))
                    .when(conversationMemoryStore).deleteConversationMemorySnapshot("conv-1");

            assertDoesNotThrow(() -> store.deleteMessages("conv-1"));
        }

        @Test
        @DisplayName("should handle store exception gracefully")
        void handlesStoreException() throws Exception {
            doThrow(new IResourceStore.ResourceStoreException("db error"))
                    .when(conversationMemoryStore).deleteConversationMemorySnapshot("conv-1");

            assertDoesNotThrow(() -> store.deleteMessages("conv-1"));
        }
    }
}
