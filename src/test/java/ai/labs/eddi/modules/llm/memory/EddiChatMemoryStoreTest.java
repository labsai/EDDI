package ai.labs.eddi.modules.llm.memory;

import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.engine.memory.IConversationMemoryStore;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot;
import dev.langchain4j.data.message.UserMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class EddiChatMemoryStoreTest {

    private EddiChatMemoryStore store;
    private IConversationMemoryStore memoryStore;

    @BeforeEach
    void setUp() throws Exception {
        store = new EddiChatMemoryStore();
        memoryStore = mock(IConversationMemoryStore.class);
        // Inject mock via reflection (field injection)
        Field field = EddiChatMemoryStore.class.getDeclaredField("conversationMemoryStore");
        field.setAccessible(true);
        field.set(store, memoryStore);
    }

    // ==================== getMessages ====================

    @Test
    void getMessages_newConversation_returnsEmpty() throws Exception {
        when(memoryStore.loadConversationMemorySnapshot("conv-1"))
                .thenThrow(new IResourceStore.ResourceNotFoundException("Not found"));
        var result = store.getMessages("conv-1");
        assertTrue(result.isEmpty());
    }

    @Test
    void getMessages_storeError_returnsEmpty() throws Exception {
        when(memoryStore.loadConversationMemorySnapshot("conv-1"))
                .thenThrow(new IResourceStore.ResourceStoreException("DB error"));
        var result = store.getMessages("conv-1");
        assertTrue(result.isEmpty());
    }

    @Test
    void getMessages_emptySnapshot_returnsEmpty() throws Exception {
        var snapshot = new ConversationMemorySnapshot();
        snapshot.setConversationOutputs(List.of());
        when(memoryStore.loadConversationMemorySnapshot("conv-1")).thenReturn(snapshot);
        var result = store.getMessages("conv-1");
        assertTrue(result.isEmpty());
    }

    // ==================== updateMessages ====================

    @Test
    void updateMessages_isNoOp() {
        // No-op by design — no exceptions, no interactions
        store.updateMessages("conv-1", List.of(UserMessage.from("test")));
        verifyNoInteractions(memoryStore);
    }

    // ==================== deleteMessages ====================

    @Test
    void deleteMessages_success() throws Exception {
        store.deleteMessages("conv-1");
        verify(memoryStore).deleteConversationMemorySnapshot("conv-1");
    }

    @Test
    void deleteMessages_notFound_noException() throws Exception {
        doThrow(new IResourceStore.ResourceNotFoundException("nope"))
                .when(memoryStore).deleteConversationMemorySnapshot("conv-1");
        // Should not throw
        assertDoesNotThrow(() -> store.deleteMessages("conv-1"));
    }

    @Test
    void deleteMessages_storeError_noException() throws Exception {
        doThrow(new IResourceStore.ResourceStoreException("DB error"))
                .when(memoryStore).deleteConversationMemorySnapshot("conv-1");
        assertDoesNotThrow(() -> store.deleteMessages("conv-1"));
    }
}
