package ai.labs.eddi.engine.internal;

import ai.labs.eddi.datastore.IResourceStore.ResourceNotFoundException;
import ai.labs.eddi.datastore.IResourceStore.ResourceStoreException;
import ai.labs.eddi.engine.api.IConversationService;
import ai.labs.eddi.engine.api.IConversationService.*;
import ai.labs.eddi.engine.gdpr.ProcessingRestrictedException;
import ai.labs.eddi.engine.memory.model.ConversationState;
import ai.labs.eddi.engine.memory.model.SimpleConversationMemorySnapshot;
import ai.labs.eddi.engine.model.Context;
import ai.labs.eddi.engine.model.Deployment;
import ai.labs.eddi.engine.model.InputData;
import jakarta.ws.rs.InternalServerErrorException;
import jakarta.ws.rs.container.AsyncResponse;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.net.URI;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link RestAgentEngine}.
 */
class RestAgentEngineTest {

    private IConversationService conversationService;
    private RestAgentEngine restAgentEngine;

    @BeforeEach
    void setUp() {
        conversationService = mock(IConversationService.class);
        restAgentEngine = new RestAgentEngine(conversationService, 30);
    }

    @Nested
    @DisplayName("startConversation")
    class StartConversation {

        @Test
        @DisplayName("should return 201 with location on success")
        void success() throws Exception {
            var result = new IConversationService.ConversationResult("conv-1", URI.create("/conversations/conv-1"));
            when(conversationService.startConversation(any(), anyString(), any(), any()))
                    .thenReturn(result);

            Response response = restAgentEngine.startConversation("agent-1",
                    Deployment.Environment.production, "user-1");

            assertEquals(201, response.getStatus());
            assertEquals(URI.create("/conversations/conv-1"), response.getLocation());
            verify(conversationService).startConversation(Deployment.Environment.production, "agent-1", "user-1", Map.of());
        }

        @Test
        @DisplayName("should return 403 for GDPR restriction")
        void gdprRestriction() throws Exception {
            when(conversationService.startConversation(any(), anyString(), any(), any()))
                    .thenThrow(new ProcessingRestrictedException("Processing restricted for user-1"));

            Response response = restAgentEngine.startConversationWithContext("agent-1",
                    Deployment.Environment.production, "user-1", Map.of());

            assertEquals(403, response.getStatus());
        }

        @Test
        @DisplayName("should return 404 for agent not ready")
        void agentNotReady() throws Exception {
            when(conversationService.startConversation(any(), anyString(), any(), any()))
                    .thenThrow(new AgentNotReadyException("Not deployed"));

            Response response = restAgentEngine.startConversation("agent-1",
                    Deployment.Environment.production, "user-1");

            assertEquals(404, response.getStatus());
        }

        @Test
        @DisplayName("should throw ISE for store exceptions")
        void storeException() throws Exception {
            when(conversationService.startConversation(any(), anyString(), any(), any()))
                    .thenThrow(new ResourceStoreException("DB error"));

            assertThrows(InternalServerErrorException.class,
                    () -> restAgentEngine.startConversation("agent-1",
                            Deployment.Environment.production, "user-1"));
        }
    }

    @Nested
    @DisplayName("endConversation")
    class EndConversation {

        @Test
        @DisplayName("should return 200")
        void success() {
            Response response = restAgentEngine.endConversation("conv-1");

            assertEquals(200, response.getStatus());
            verify(conversationService).endConversation("conv-1");
        }
    }

    @Nested
    @DisplayName("readConversation")
    class ReadConversation {

        @Test
        @DisplayName("should return snapshot on success")
        void success() throws Exception {
            var snapshot = new SimpleConversationMemorySnapshot();
            snapshot.setConversationState(ConversationState.READY);
            when(conversationService.readConversation("conv-1", false, false, List.of()))
                    .thenReturn(snapshot);

            SimpleConversationMemorySnapshot result = restAgentEngine
                    .readConversation("conv-1", false, false, List.of());

            assertEquals(ConversationState.READY, result.getConversationState());
        }

        @Test
        @DisplayName("should throw ISE for store exceptions")
        void storeException() throws Exception {
            when(conversationService.readConversation(anyString(), any(), any(), any()))
                    .thenThrow(new ResourceStoreException("DB error"));

            assertThrows(InternalServerErrorException.class,
                    () -> restAgentEngine.readConversation("conv-1", false, false, List.of()));
        }

        @Test
        @DisplayName("should propagate ResourceNotFoundException")
        void notFound() throws Exception {
            when(conversationService.readConversation(anyString(), any(), any(), any()))
                    .thenThrow(new ResourceNotFoundException("Not found"));

            assertThrows(ResourceNotFoundException.class,
                    () -> restAgentEngine.readConversation("conv-1", false, false, List.of()));
        }
    }

    @Nested
    @DisplayName("getConversationState")
    class GetConversationState {

        @Test
        @DisplayName("should delegate to service")
        void delegatesToService() {
            when(conversationService.getConversationState("conv-1"))
                    .thenReturn(ConversationState.READY);

            assertEquals(ConversationState.READY,
                    restAgentEngine.getConversationState("conv-1"));
        }
    }

    @Nested
    @DisplayName("undo/redo")
    class UndoRedo {

        @Test
        @DisplayName("undo should return 200 when performed")
        void undoSuccess() throws Exception {
            when(conversationService.undo("conv-1")).thenReturn(true);

            Response response = restAgentEngine.undo("conv-1");

            assertEquals(200, response.getStatus());
        }

        @Test
        @DisplayName("undo should return 409 when not performed")
        void undoConflict() throws Exception {
            when(conversationService.undo("conv-1")).thenReturn(false);

            Response response = restAgentEngine.undo("conv-1");

            assertEquals(409, response.getStatus());
        }

        @Test
        @DisplayName("redo should return 200 when performed")
        void redoSuccess() throws Exception {
            when(conversationService.redo("conv-1")).thenReturn(true);

            Response response = restAgentEngine.redo("conv-1");

            assertEquals(200, response.getStatus());
        }

        @Test
        @DisplayName("redo should return 409 when not performed")
        void redoConflict() throws Exception {
            when(conversationService.redo("conv-1")).thenReturn(false);

            Response response = restAgentEngine.redo("conv-1");

            assertEquals(409, response.getStatus());
        }

        @Test
        @DisplayName("isUndoAvailable should throw ISE for store errors")
        void isUndoStoreError() throws Exception {
            when(conversationService.isUndoAvailable("conv-1"))
                    .thenThrow(new ResourceStoreException("DB error"));

            assertThrows(InternalServerErrorException.class,
                    () -> restAgentEngine.isUndoAvailable("conv-1"));
        }

        @Test
        @DisplayName("isRedoAvailable should throw ISE for store errors")
        void isRedoStoreError() throws Exception {
            when(conversationService.isRedoAvailable("conv-1"))
                    .thenThrow(new ResourceStoreException("DB error"));

            assertThrows(InternalServerErrorException.class,
                    () -> restAgentEngine.isRedoAvailable("conv-1"));
        }
    }

    @Nested
    @DisplayName("sayWithinContext")
    class SayWithinContext {

        @Test
        @DisplayName("should set timeout and delegate to service")
        void delegatesToService() throws Exception {
            var asyncResponse = mock(AsyncResponse.class);
            var inputData = new InputData("Hello", Map.of());

            restAgentEngine.sayWithinContext("conv-1", false, false,
                    List.of(), inputData, asyncResponse);

            verify(asyncResponse).setTimeout(30, java.util.concurrent.TimeUnit.SECONDS);
            verify(conversationService).say(eq("conv-1"), eq(false), eq(false),
                    eq(List.of()), eq(inputData), eq(false), any());
        }

        @Test
        @DisplayName("should resume with CONFLICT for agent mismatch")
        void agentMismatch() throws Exception {
            var asyncResponse = mock(AsyncResponse.class);
            var inputData = new InputData("Hello", Map.of());

            doThrow(new AgentMismatchException("mismatch"))
                    .when(conversationService).say(anyString(), any(), any(), any(), any(), anyBoolean(), any());

            restAgentEngine.sayWithinContext("conv-1", false, false,
                    List.of(), inputData, asyncResponse);

            var captor = ArgumentCaptor.forClass(Response.class);
            verify(asyncResponse).resume(captor.capture());
            assertEquals(409, captor.getValue().getStatus());
        }

        @Test
        @DisplayName("should resume with GONE for ended conversation")
        void conversationEnded() throws Exception {
            var asyncResponse = mock(AsyncResponse.class);
            var inputData = new InputData("Hello", Map.of());

            doThrow(new ConversationEndedException("ended"))
                    .when(conversationService).say(anyString(), any(), any(), any(), any(), anyBoolean(), any());

            restAgentEngine.sayWithinContext("conv-1", false, false,
                    List.of(), inputData, asyncResponse);

            var captor = ArgumentCaptor.forClass(Response.class);
            verify(asyncResponse).resume(captor.capture());
            assertEquals(410, captor.getValue().getStatus());
        }
    }
}
