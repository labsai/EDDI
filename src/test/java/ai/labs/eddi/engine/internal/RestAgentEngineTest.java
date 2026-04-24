/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal;

import ai.labs.eddi.datastore.IResourceStore.ResourceNotFoundException;
import ai.labs.eddi.datastore.IResourceStore.ResourceStoreException;
import ai.labs.eddi.engine.api.IConversationService;
import ai.labs.eddi.engine.api.IConversationService.*;
import ai.labs.eddi.engine.gdpr.ProcessingRestrictedException;
import ai.labs.eddi.engine.memory.model.ConversationState;
import ai.labs.eddi.engine.memory.model.SimpleConversationMemorySnapshot;
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

        @Test
        @DisplayName("should resume with NOT_FOUND for agent not ready")
        void agentNotReady() throws Exception {
            var asyncResponse = mock(AsyncResponse.class);
            var inputData = new InputData("Hello", Map.of());

            doThrow(new AgentNotReadyException("not deployed"))
                    .when(conversationService).say(anyString(), any(), any(), any(), any(), anyBoolean(), any());

            restAgentEngine.sayWithinContext("conv-1", false, false,
                    List.of(), inputData, asyncResponse);

            verify(asyncResponse).resume(any(jakarta.ws.rs.NotFoundException.class));
        }

        @Test
        @DisplayName("should resume with FORBIDDEN for GDPR restriction")
        void gdprRestricted() throws Exception {
            var asyncResponse = mock(AsyncResponse.class);
            var inputData = new InputData("Hello", Map.of());

            doThrow(new ProcessingRestrictedException("restricted"))
                    .when(conversationService).say(anyString(), any(), any(), any(), any(), anyBoolean(), any());

            restAgentEngine.sayWithinContext("conv-1", false, false,
                    List.of(), inputData, asyncResponse);

            var captor = ArgumentCaptor.forClass(Response.class);
            verify(asyncResponse).resume(captor.capture());
            assertEquals(403, captor.getValue().getStatus());
        }

        @Test
        @DisplayName("should resume with NotFoundException for resource not found")
        void resourceNotFound() throws Exception {
            var asyncResponse = mock(AsyncResponse.class);
            var inputData = new InputData("Hello", Map.of());

            doThrow(new ResourceNotFoundException("not found"))
                    .when(conversationService).say(anyString(), any(), any(), any(), any(), anyBoolean(), any());

            restAgentEngine.sayWithinContext("conv-1", false, false,
                    List.of(), inputData, asyncResponse);

            verify(asyncResponse).resume(any(jakarta.ws.rs.NotFoundException.class));
        }

        @Test
        @DisplayName("should throw ISE for generic exception")
        void genericException() throws Exception {
            var asyncResponse = mock(AsyncResponse.class);
            var inputData = new InputData("Hello", Map.of());

            doThrow(new RuntimeException("unexpected"))
                    .when(conversationService).say(anyString(), any(), any(), any(), any(), anyBoolean(), any());

            assertThrows(InternalServerErrorException.class,
                    () -> restAgentEngine.sayWithinContext("conv-1", false, false,
                            List.of(), inputData, asyncResponse));
        }
    }

    @Nested
    @DisplayName("say (plain string)")
    class SayPlainString {

        @Test
        @DisplayName("should delegate to sayWithinContext with InputData")
        void delegatesToSayWithinContext() throws Exception {
            var asyncResponse = mock(AsyncResponse.class);

            restAgentEngine.say("conv-1", false, false, List.of(), "Hello world", asyncResponse);

            verify(asyncResponse).setTimeout(30, java.util.concurrent.TimeUnit.SECONDS);
            verify(conversationService).say(eq("conv-1"), eq(false), eq(false),
                    eq(List.of()), any(InputData.class), eq(false), any());
        }
    }

    @Nested
    @DisplayName("rerunLastConversationStep")
    class RerunLastStep {

        @Test
        @DisplayName("should pass rerunOnly=true and language context")
        void delegatesWithRerunFlag() throws Exception {
            var asyncResponse = mock(AsyncResponse.class);

            restAgentEngine.rerunLastConversationStep("conv-1", "en", false, false,
                    List.of(), asyncResponse);

            verify(asyncResponse).setTimeout(30, java.util.concurrent.TimeUnit.SECONDS);
            var captor = ArgumentCaptor.forClass(InputData.class);
            verify(conversationService).say(eq("conv-1"), eq(false), eq(false),
                    eq(List.of()), captor.capture(), eq(true), any());
            InputData capturedInput = captor.getValue();
            assertEquals("", capturedInput.getInput());
            assertTrue(capturedInput.getContext().containsKey("lang"));
        }
    }

    @Nested
    @DisplayName("readConversationLog")
    class ReadConversationLog {

        @Test
        @DisplayName("should return 200 with content on success")
        void success() throws Exception {
            var logResult = new IConversationService.ConversationLogResult("log content", "text/plain");
            when(conversationService.readConversationLog("conv-1", "text", null))
                    .thenReturn(logResult);

            Response response = restAgentEngine.readConversationLog("conv-1", "text", null);

            assertEquals(200, response.getStatus());
            assertEquals("log content", response.getEntity());
        }

        @Test
        @DisplayName("should throw ISE for store exceptions")
        void storeException() throws Exception {
            when(conversationService.readConversationLog(anyString(), any(), any()))
                    .thenThrow(new ResourceStoreException("DB error"));

            assertThrows(InternalServerErrorException.class,
                    () -> restAgentEngine.readConversationLog("conv-1", "text", null));
        }

        @Test
        @DisplayName("should propagate ResourceNotFoundException")
        void notFound() throws Exception {
            when(conversationService.readConversationLog(anyString(), any(), any()))
                    .thenThrow(new ResourceNotFoundException("Not found"));

            assertThrows(ResourceNotFoundException.class,
                    () -> restAgentEngine.readConversationLog("conv-1", "text", null));
        }
    }

    @Nested
    @DisplayName("isUndoAvailable / isRedoAvailable happy paths")
    class UndoRedoHappyPaths {

        @Test
        @DisplayName("isUndoAvailable returns true when available")
        void undoAvailable() throws Exception {
            when(conversationService.isUndoAvailable("conv-1")).thenReturn(true);

            assertTrue(restAgentEngine.isUndoAvailable("conv-1"));
        }

        @Test
        @DisplayName("isUndoAvailable returns false when not available")
        void undoNotAvailable() throws Exception {
            when(conversationService.isUndoAvailable("conv-1")).thenReturn(false);

            assertFalse(restAgentEngine.isUndoAvailable("conv-1"));
        }

        @Test
        @DisplayName("isRedoAvailable returns true when available")
        void redoAvailable() throws Exception {
            when(conversationService.isRedoAvailable("conv-1")).thenReturn(true);

            assertTrue(restAgentEngine.isRedoAvailable("conv-1"));
        }

        @Test
        @DisplayName("isRedoAvailable returns false when not available")
        void redoNotAvailable() throws Exception {
            when(conversationService.isRedoAvailable("conv-1")).thenReturn(false);

            assertFalse(restAgentEngine.isRedoAvailable("conv-1"));
        }

        @Test
        @DisplayName("isUndoAvailable propagates ResourceNotFoundException")
        void undoNotFoundPropagated() throws Exception {
            when(conversationService.isUndoAvailable("conv-1"))
                    .thenThrow(new ResourceNotFoundException("not found"));

            assertThrows(ResourceNotFoundException.class,
                    () -> restAgentEngine.isUndoAvailable("conv-1"));
        }

        @Test
        @DisplayName("isRedoAvailable propagates ResourceNotFoundException")
        void redoNotFoundPropagated() throws Exception {
            when(conversationService.isRedoAvailable("conv-1"))
                    .thenThrow(new ResourceNotFoundException("not found"));

            assertThrows(ResourceNotFoundException.class,
                    () -> restAgentEngine.isRedoAvailable("conv-1"));
        }

        @Test
        @DisplayName("undo propagates ResourceNotFoundException")
        void undoResourceNotFound() throws Exception {
            when(conversationService.undo("conv-1"))
                    .thenThrow(new ResourceNotFoundException("not found"));

            assertThrows(ResourceNotFoundException.class,
                    () -> restAgentEngine.undo("conv-1"));
        }

        @Test
        @DisplayName("redo propagates ResourceNotFoundException")
        void redoResourceNotFound() throws Exception {
            when(conversationService.redo("conv-1"))
                    .thenThrow(new ResourceNotFoundException("not found"));

            assertThrows(ResourceNotFoundException.class,
                    () -> restAgentEngine.redo("conv-1"));
        }

        @Test
        @DisplayName("undo throws ISE for store error")
        void undoStoreError() throws Exception {
            when(conversationService.undo("conv-1"))
                    .thenThrow(new ResourceStoreException("DB error"));

            assertThrows(InternalServerErrorException.class,
                    () -> restAgentEngine.undo("conv-1"));
        }

        @Test
        @DisplayName("redo throws ISE for store error")
        void redoStoreError() throws Exception {
            when(conversationService.redo("conv-1"))
                    .thenThrow(new ResourceStoreException("DB error"));

            assertThrows(InternalServerErrorException.class,
                    () -> restAgentEngine.redo("conv-1"));
        }
    }
}
