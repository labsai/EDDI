package ai.labs.eddi.engine.internal;

import ai.labs.eddi.datastore.IResourceStore.ResourceStoreException;
import ai.labs.eddi.engine.api.IConversationService;
import ai.labs.eddi.engine.api.IConversationService.*;
import ai.labs.eddi.engine.memory.model.SimpleConversationMemorySnapshot;
import ai.labs.eddi.engine.memory.model.ConversationState;
import ai.labs.eddi.engine.model.InputData;
import jakarta.ws.rs.InternalServerErrorException;
import jakarta.ws.rs.container.AsyncResponse;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;

import java.net.URI;

import static ai.labs.eddi.engine.model.Deployment.Environment.production;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.mockito.MockitoAnnotations.openMocks;

class RestAgentEngineTest {

    @Mock
    private IConversationService conversationService;

    private RestAgentEngine RestAgentEngine;

    @BeforeEach
    void setUp() {
        openMocks(this);
        RestAgentEngine = new RestAgentEngine(conversationService, 60);
    }

    // ==================== startConversation Tests ====================

    @Nested
    @DisplayName("startConversation")
    class StartConversationTests {

        @Test
        @DisplayName("should return 201 with Location header on success")
        void startConversation_success() throws Exception {
            var convUri = URI.create("eddi://ai.labs.conversation/conv123?version=1");
            var result = new IConversationService.ConversationResult("conv123", convUri);
            when(conversationService.startConversation(any(), eq("agent1"), eq("user1"), anyMap())).thenReturn(result);

            Response response = RestAgentEngine.startConversation(production, "agent1", "user1");

            assertEquals(201, response.getStatus());
            assertNotNull(response.getLocation());
        }

        @Test
        @DisplayName("should return 404 when Agent is not ready")
        void startConversation_agentNotReady() throws Exception {
            when(conversationService.startConversation(any(), eq("agent1"), eq("user1"), anyMap()))
                    .thenThrow(new AgentNotReadyException("Agent not deployed"));

            Response response = RestAgentEngine.startConversation(production, "agent1", "user1");

            assertEquals(404, response.getStatus());
        }

        @Test
        @DisplayName("should throw InternalServerError on ResourceStoreException")
        void startConversation_storeError() throws Exception {
            when(conversationService.startConversation(any(), eq("agent1"), eq("user1"), anyMap())).thenThrow(new ResourceStoreException("DB error"));

            assertThrows(InternalServerErrorException.class, () -> RestAgentEngine.startConversation(production, "agent1", "user1"));
        }
    }

    // ==================== endConversation Tests ====================

    @Nested
    @DisplayName("endConversation")
    class EndConversationTests {

        @Test
        @DisplayName("should return 200 on success")
        void endConversation_success() {
            Response response = RestAgentEngine.endConversation("conv1");

            assertEquals(200, response.getStatus());
            verify(conversationService).endConversation("conv1");
        }
    }

    // ==================== readConversation Tests ====================

    @Nested
    @DisplayName("readConversation")
    class ReadConversationTests {

        @Test
        @DisplayName("should delegate to conversationService and return snapshot")
        void readConversation_success() throws Exception {
            var snapshot = new SimpleConversationMemorySnapshot();
            when(conversationService.readConversation(eq(production), eq("agent1"), eq("conv1"), eq(true), eq(false), isNull())).thenReturn(snapshot);

            SimpleConversationMemorySnapshot result = RestAgentEngine.readConversation(production, "agent1", "conv1", true, false, null);

            assertNotNull(result);
            assertSame(snapshot, result);
        }
    }

    // ==================== say Tests ====================

    @Nested
    @DisplayName("say/sayWithinContext")
    class SayTests {

        @Test
        @DisplayName("should delegate to conversationService.say")
        void say_delegatesToService() throws Exception {
            AsyncResponse asyncResponse = mock(AsyncResponse.class);

            RestAgentEngine.say(production, "agent1", "conv1", false, false, null, "Hello", asyncResponse);

            verify(asyncResponse).setTimeout(60, java.util.concurrent.TimeUnit.SECONDS);
            verify(conversationService).say(eq(production), eq("agent1"), eq("conv1"), eq(false), eq(false), isNull(), any(InputData.class),
                    eq(false), any());
        }

        @Test
        @DisplayName("should resume with 410 when conversation has ended")
        void say_conversationEnded() throws Exception {
            AsyncResponse asyncResponse = mock(AsyncResponse.class);

            doThrow(new ConversationEndedException("Conversation has ended!")).when(conversationService).say(any(), any(), any(), any(), any(), any(),
                    any(), anyBoolean(), any());

            RestAgentEngine.say(production, "agent1", "conv1", false, false, null, "Hello", asyncResponse);

            // Capture the argument passed to resume
            ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
            verify(asyncResponse).resume(captor.capture());
            Object resumed = captor.getValue();
            assertInstanceOf(Response.class, resumed);
            assertEquals(410, ((Response) resumed).getStatus());
        }

        @Test
        @DisplayName("should resume with 409 on AgentMismatchException")
        void say_agentMismatch() throws Exception {
            AsyncResponse asyncResponse = mock(AsyncResponse.class);

            doThrow(new AgentMismatchException("wrong agent")).when(conversationService).say(any(), any(), any(), any(), any(), any(), any(),
                    anyBoolean(), any());

            RestAgentEngine.say(production, "agent1", "conv1", false, false, null, "Hello", asyncResponse);

            ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
            verify(asyncResponse).resume(captor.capture());
            Object resumed = captor.getValue();
            assertInstanceOf(Response.class, resumed);
            assertEquals(409, ((Response) resumed).getStatus());
        }
    }

    // ==================== undo/redo Tests ====================

    @Nested
    @DisplayName("undo/redo")
    class UndoRedoTests {

        @Test
        @DisplayName("isUndoAvailable should delegate to service")
        void isUndoAvailable() throws Exception {
            when(conversationService.isUndoAvailable(production, "agent1", "conv1")).thenReturn(true);

            assertTrue(RestAgentEngine.isUndoAvailable(production, "agent1", "conv1"));
        }

        @Test
        @DisplayName("undo should return 200 when successful")
        void undo_success() throws Exception {
            when(conversationService.undo(production, "agent1", "conv1")).thenReturn(true);

            Response response = RestAgentEngine.undo(production, "agent1", "conv1");

            assertEquals(200, response.getStatus());
        }

        @Test
        @DisplayName("undo should return 409 when undo not available")
        void undo_notAvailable() throws Exception {
            when(conversationService.undo(production, "agent1", "conv1")).thenReturn(false);

            Response response = RestAgentEngine.undo(production, "agent1", "conv1");

            assertEquals(409, response.getStatus());
        }

        @Test
        @DisplayName("redo should return 200 when successful")
        void redo_success() throws Exception {
            when(conversationService.redo(production, "agent1", "conv1")).thenReturn(true);

            Response response = RestAgentEngine.redo(production, "agent1", "conv1");

            assertEquals(200, response.getStatus());
        }

        @Test
        @DisplayName("isRedoAvailable should delegate to service")
        void isRedoAvailable() throws Exception {
            when(conversationService.isRedoAvailable(production, "agent1", "conv1")).thenReturn(false);

            assertFalse(RestAgentEngine.isRedoAvailable(production, "agent1", "conv1"));
        }
    }

    // ==================== getConversationState Tests ====================

    @Nested
    @DisplayName("getConversationState")
    class ConversationStateTests {

        @Test
        @DisplayName("should delegate to conversationService")
        void getConversationState() {
            when(conversationService.getConversationState(production, "conv1")).thenReturn(ConversationState.READY);

            assertEquals(ConversationState.READY, RestAgentEngine.getConversationState(production, "conv1"));
        }
    }
}
