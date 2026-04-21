package ai.labs.eddi.engine.internal;

import ai.labs.eddi.configs.properties.model.Property;
import ai.labs.eddi.engine.api.IRestAgentEngine;
import ai.labs.eddi.engine.memory.model.ConversationProperties;
import ai.labs.eddi.engine.memory.model.ConversationState;
import ai.labs.eddi.engine.memory.model.SimpleConversationMemorySnapshot;
import ai.labs.eddi.engine.model.Context;
import ai.labs.eddi.engine.model.InputData;
import ai.labs.eddi.engine.triggermanagement.IRestAgentTriggerStore;
import ai.labs.eddi.engine.triggermanagement.IUserConversationStore;
import ai.labs.eddi.engine.triggermanagement.model.UserConversation;
import ai.labs.eddi.datastore.IResourceStore;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.ws.rs.container.AsyncResponse;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link RestAgentManagement}.
 */
class RestAgentManagementTest {

    private IRestAgentEngine restAgentEngine;
    private IUserConversationStore userConversationStore;
    private IRestAgentTriggerStore agentTriggerStore;
    private SecurityIdentity identity;
    private RestAgentManagement restAgentManagement;
    private AsyncResponse asyncResponse;

    @BeforeEach
    void setUp() throws Exception {
        restAgentEngine = mock(IRestAgentEngine.class);
        userConversationStore = mock(IUserConversationStore.class);
        agentTriggerStore = mock(IRestAgentTriggerStore.class);
        identity = mock(SecurityIdentity.class);
        asyncResponse = mock(AsyncResponse.class);

        // Auth disabled for tests
        restAgentManagement = new RestAgentManagement(restAgentEngine, userConversationStore,
                agentTriggerStore, false);

        // Inject the security identity
        Field identityField = RestAgentManagement.class.getDeclaredField("identity");
        identityField.setAccessible(true);
        identityField.set(restAgentManagement, identity);
    }

    @Nested
    @DisplayName("endCurrentConversation")
    class EndCurrentConversation {

        @Test
        @DisplayName("should return 200 and end conversation when found")
        void endsConversation() throws Exception {
            var userConv = new UserConversation("intent-1", "user-1",
                    ai.labs.eddi.engine.model.Deployment.Environment.production, "agent-1", "conv-1");
            when(userConversationStore.readUserConversation("intent-1", "user-1"))
                    .thenReturn(userConv);

            Response response = restAgentManagement.endCurrentConversation("intent-1", "user-1");

            assertEquals(200, response.getStatus());
            verify(restAgentEngine).endConversation("conv-1");
        }

        @Test
        @DisplayName("should return 200 even when no conversation exists")
        void noConversation() throws Exception {
            when(userConversationStore.readUserConversation("intent-1", "user-1"))
                    .thenReturn(null);

            Response response = restAgentManagement.endCurrentConversation("intent-1", "user-1");

            assertEquals(200, response.getStatus());
            verify(restAgentEngine, never()).endConversation(anyString());
        }

        @Test
        @DisplayName("should propagate ResourceStoreException")
        void storeError() throws Exception {
            when(userConversationStore.readUserConversation("intent-1", "user-1"))
                    .thenThrow(new IResourceStore.ResourceStoreException("DB error"));

            assertThrows(IResourceStore.ResourceStoreException.class,
                    () -> restAgentManagement.endCurrentConversation("intent-1", "user-1"));
        }
    }

    @Nested
    @DisplayName("isUndoAvailable")
    class IsUndoAvailable {

        @Test
        @DisplayName("should return true when undo available")
        void undoAvailable() throws Exception {
            var userConv = createUserConversation();
            when(userConversationStore.readUserConversation("intent-1", "user-1"))
                    .thenReturn(userConv);
            when(restAgentEngine.isUndoAvailable("conv-1")).thenReturn(true);

            assertTrue(restAgentManagement.isUndoAvailable("intent-1", "user-1"));
        }

        @Test
        @DisplayName("should return false when no conversation")
        void noConversation() throws Exception {
            when(userConversationStore.readUserConversation("intent-1", "user-1"))
                    .thenReturn(null);

            assertFalse(restAgentManagement.isUndoAvailable("intent-1", "user-1"));
        }
    }

    @Nested
    @DisplayName("undo")
    class Undo {

        @Test
        @DisplayName("should delegate to engine when conversation exists")
        void delegatesToEngine() throws Exception {
            var userConv = createUserConversation();
            when(userConversationStore.readUserConversation("intent-1", "user-1"))
                    .thenReturn(userConv);
            when(restAgentEngine.undo("conv-1")).thenReturn(Response.ok().build());

            Response response = restAgentManagement.undo("intent-1", "user-1");

            assertEquals(200, response.getStatus());
            verify(restAgentEngine).undo("conv-1");
        }

        @Test
        @DisplayName("should return 404 when no conversation")
        void returns404() throws Exception {
            when(userConversationStore.readUserConversation("intent-1", "user-1"))
                    .thenReturn(null);

            Response response = restAgentManagement.undo("intent-1", "user-1");

            assertEquals(404, response.getStatus());
        }
    }

    @Nested
    @DisplayName("isRedoAvailable")
    class IsRedoAvailable {

        @Test
        @DisplayName("should return false when no conversation")
        void noConversation() throws Exception {
            when(userConversationStore.readUserConversation("intent-1", "user-1"))
                    .thenReturn(null);

            assertFalse(restAgentManagement.isRedoAvailable("intent-1", "user-1"));
        }

        @Test
        @DisplayName("should delegate when conversation exists")
        void delegatesWhenExists() throws Exception {
            var userConv = createUserConversation();
            when(userConversationStore.readUserConversation("intent-1", "user-1"))
                    .thenReturn(userConv);
            when(restAgentEngine.isRedoAvailable("conv-1")).thenReturn(true);

            assertTrue(restAgentManagement.isRedoAvailable("intent-1", "user-1"));
        }
    }

    @Nested
    @DisplayName("redo")
    class Redo {

        @Test
        @DisplayName("should return 404 when no conversation")
        void returns404() throws Exception {
            when(userConversationStore.readUserConversation("intent-1", "user-1"))
                    .thenReturn(null);

            Response response = restAgentManagement.redo("intent-1", "user-1");

            assertEquals(404, response.getStatus());
        }

        @Test
        @DisplayName("should delegate to engine when conversation exists")
        void delegatesToEngine() throws Exception {
            var userConv = createUserConversation();
            when(userConversationStore.readUserConversation("intent-1", "user-1"))
                    .thenReturn(userConv);
            when(restAgentEngine.redo("conv-1")).thenReturn(Response.ok().build());

            Response response = restAgentManagement.redo("intent-1", "user-1");

            assertEquals(200, response.getStatus());
            verify(restAgentEngine).redo("conv-1");
        }
    }

    // --- loadConversationMemory ---

    @Nested
    @DisplayName("loadConversationMemory")
    class LoadConversationMemory {

        @Test
        @DisplayName("should resume with snapshot when no language property exists")
        void resumesWithSnapshotNoLang() throws Exception {
            var userConv = createUserConversation();
            when(userConversationStore.readUserConversation("intent-1", "user-1"))
                    .thenReturn(userConv);
            when(restAgentEngine.getConversationState("conv-1"))
                    .thenReturn(ConversationState.READY);

            var snapshot = new SimpleConversationMemorySnapshot();
            when(restAgentEngine.readConversation(eq("conv-1"), any(), any(), any()))
                    .thenReturn(snapshot);

            restAgentManagement.loadConversationMemory("intent-1", "user-1", "en",
                    false, false, List.of(), asyncResponse);

            verify(asyncResponse).resume(snapshot);
        }

        @Test
        @DisplayName("should rerun when language property differs from requested")
        void rerunsWhenLangDiffers() throws Exception {
            var userConv = createUserConversation();
            when(userConversationStore.readUserConversation("intent-1", "user-1"))
                    .thenReturn(userConv);
            when(restAgentEngine.getConversationState("conv-1"))
                    .thenReturn(ConversationState.READY);

            var snapshot = new SimpleConversationMemorySnapshot();
            var langProp = new Property("lang", "de", Property.Scope.conversation);
            var props = new ConversationProperties(null);
            props.put("lang", langProp);
            snapshot.setConversationProperties(props);
            when(restAgentEngine.readConversation(eq("conv-1"), any(), any(), any()))
                    .thenReturn(snapshot);

            restAgentManagement.loadConversationMemory("intent-1", "user-1", "en",
                    false, false, List.of(), asyncResponse);

            verify(restAgentEngine).rerunLastConversationStep(eq("conv-1"), eq("en"),
                    any(), any(), any(), eq(asyncResponse));
            verify(asyncResponse, never()).resume(any(SimpleConversationMemorySnapshot.class));
        }

        @Test
        @DisplayName("should resume without rerun when language matches")
        void noRerunWhenLangMatches() throws Exception {
            var userConv = createUserConversation();
            when(userConversationStore.readUserConversation("intent-1", "user-1"))
                    .thenReturn(userConv);
            when(restAgentEngine.getConversationState("conv-1"))
                    .thenReturn(ConversationState.READY);

            var snapshot = new SimpleConversationMemorySnapshot();
            var langProp = new Property("lang", "en", Property.Scope.conversation);
            var props = new ConversationProperties(null);
            props.put("lang", langProp);
            snapshot.setConversationProperties(props);
            when(restAgentEngine.readConversation(eq("conv-1"), any(), any(), any()))
                    .thenReturn(snapshot);

            restAgentManagement.loadConversationMemory("intent-1", "user-1", "en",
                    false, false, List.of(), asyncResponse);

            verify(asyncResponse).resume(snapshot);
            verify(restAgentEngine, never()).rerunLastConversationStep(
                    anyString(), anyString(), any(), any(), any(), any());
        }
    }

    // --- sayWithinContext ---

    @Nested
    @DisplayName("sayWithinContext")
    class SayWithinContext {

        @Test
        @DisplayName("should delegate to engine when conversation exists")
        void delegatesToEngine() throws Exception {
            var userConv = createUserConversation();
            when(userConversationStore.readUserConversation("intent-1", "user-1"))
                    .thenReturn(userConv);
            when(restAgentEngine.getConversationState("conv-1"))
                    .thenReturn(ConversationState.READY);

            var inputData = new InputData("Hello", Map.of());

            restAgentManagement.sayWithinContext("intent-1", "user-1", false, false,
                    List.of(), inputData, asyncResponse);

            verify(restAgentEngine).sayWithinContext(eq("conv-1"), any(), any(), any(),
                    eq(inputData), eq(asyncResponse));
        }

        @Test
        @DisplayName("should extract language from context")
        void extractsLanguageFromContext() throws Exception {
            var userConv = createUserConversation();
            when(userConversationStore.readUserConversation("intent-1", "user-1"))
                    .thenReturn(userConv);
            when(restAgentEngine.getConversationState("conv-1"))
                    .thenReturn(ConversationState.READY);

            var langContext = new Context(Context.ContextType.string, "fr");
            var inputData = new InputData("Bonjour", Map.of("lang", langContext));

            restAgentManagement.sayWithinContext("intent-1", "user-1", false, false,
                    List.of(), inputData, asyncResponse);

            verify(restAgentEngine).sayWithinContext(eq("conv-1"), any(), any(), any(),
                    eq(inputData), eq(asyncResponse));
        }
    }

    @Nested
    @DisplayName("UserConversationResult")
    class UserConversationResultTests {

        @Test
        @DisplayName("should support getters and setters")
        void gettersAndSetters() {
            var result = new RestAgentManagement.UserConversationResult();
            assertFalse(result.isNewlyCreatedConversation());
            assertNull(result.getUserConversation());

            var userConv = createUserConversation();
            result.setNewlyCreatedConversation(true);
            result.setUserConversation(userConv);

            assertTrue(result.isNewlyCreatedConversation());
            assertEquals(userConv, result.getUserConversation());
        }

        @Test
        @DisplayName("should support parameterized constructor")
        void parameterizedConstructor() {
            var userConv = createUserConversation();
            var result = new RestAgentManagement.UserConversationResult(true, userConv);

            assertTrue(result.isNewlyCreatedConversation());
            assertEquals("conv-1", result.getUserConversation().getConversationId());
        }
    }

    private UserConversation createUserConversation() {
        return new UserConversation("intent-1", "user-1",
                ai.labs.eddi.engine.model.Deployment.Environment.production, "agent-1", "conv-1");
    }
}
