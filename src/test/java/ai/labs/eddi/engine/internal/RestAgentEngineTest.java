/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal;

import ai.labs.eddi.engine.security.spaces.ResourceAccessGuard;
import ai.labs.eddi.datastore.IResourceStore.ResourceNotFoundException;
import ai.labs.eddi.datastore.IResourceStore.ResourceStoreException;
import ai.labs.eddi.engine.api.IConversationService;
import ai.labs.eddi.engine.api.IConversationService.ConversationNotFoundException;
import ai.labs.eddi.engine.api.IConversationService.*;
import ai.labs.eddi.engine.api.IGroupConversationService;
import ai.labs.eddi.engine.gdpr.ProcessingRestrictedException;
import ai.labs.eddi.engine.hitl.HitlAccessGuard;
import ai.labs.eddi.engine.hitl.tools.IHitlToolJournalStore;
import ai.labs.eddi.engine.memory.IConversationMemoryStore;
import ai.labs.eddi.engine.memory.descriptor.IConversationDescriptorStore;
import ai.labs.eddi.engine.memory.descriptor.model.ConversationDescriptor;
import ai.labs.eddi.engine.memory.model.ConversationState;
import ai.labs.eddi.engine.memory.model.SimpleConversationMemorySnapshot;
import ai.labs.eddi.engine.model.Deployment;
import ai.labs.eddi.engine.model.InputData;
import ai.labs.eddi.engine.security.ConversationAccessGuard;
import ai.labs.eddi.engine.security.OwnershipValidator;
import ai.labs.eddi.engine.tenancy.QuotaExceededException;
import io.quarkus.security.ForbiddenException;
import io.quarkus.security.identity.SecurityIdentity;
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
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

import jakarta.ws.rs.NotFoundException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link RestAgentEngine}.
 */
class RestAgentEngineTest {

    private IConversationService conversationService;
    private IConversationDescriptorStore descriptorStore;
    private SecurityIdentity identity;
    private OwnershipValidator ownershipValidator;
    private RestAgentEngine restAgentEngine;

    @BeforeEach
    void setUp() throws Exception {
        conversationService = mock(IConversationService.class);
        descriptorStore = mock(IConversationDescriptorStore.class);
        identity = mock(SecurityIdentity.class);
        ownershipValidator = mock(OwnershipValidator.class);
        when(ownershipValidator.validateAndResolveUserId(any(), any())).thenAnswer(inv -> inv.getArgument(1));
        // Default: descriptor not found → ownership check skipped gracefully
        when(descriptorStore.readDescriptor(anyString(), anyInt()))
                .thenThrow(new ResourceNotFoundException("test default"));
        var conversationMemoryStore = mock(IConversationMemoryStore.class);
        var hitlAccessGuard = new HitlAccessGuard(identity, ownershipValidator, descriptorStore, conversationService,
                mock(IGroupConversationService.class));
        var conversationAccessGuard = new ConversationAccessGuard(identity, ownershipValidator, descriptorStore);
        var hitlToolJournalStore = mock(IHitlToolJournalStore.class);
        restAgentEngine = new RestAgentEngine(conversationService, conversationMemoryStore, identity, ownershipValidator,
                conversationAccessGuard, mock(ResourceAccessGuard.class), hitlAccessGuard, hitlToolJournalStore, 30);
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
            // G4: the engine attributes the end to the calling principal (null for the
            // unauthenticated mock identity here) via the 2-arg overload.
            verify(conversationService).endConversation("conv-1", null);
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

            verify(asyncResponse).setTimeout(30, TimeUnit.SECONDS);
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

            verify(asyncResponse).resume(any(NotFoundException.class));
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
        @DisplayName("should resume with 429 quota_exceeded, not 500, when the api-call quota denies")
        void quotaExceeded() throws Exception {
            var asyncResponse = mock(AsyncResponse.class);
            var inputData = new InputData("Hello", Map.of());

            doThrow(new QuotaExceededException("API rate limit (5/min) exceeded for tenant 'default'"))
                    .when(conversationService).say(anyString(), any(), any(), any(), any(), anyBoolean(), any());

            restAgentEngine.sayWithinContext("conv-1", false, false,
                    List.of(), inputData, asyncResponse);

            // say() is resumed via AsyncResponse, so QuotaExceededExceptionMapper never
            // runs — without an explicit catch this fell through to the generic handler
            // and surfaced as a 500.
            var captor = ArgumentCaptor.forClass(Response.class);
            verify(asyncResponse).resume(captor.capture());
            Response resumed = captor.getValue();
            assertEquals(429, resumed.getStatus());
            assertEquals("60", resumed.getHeaderString("Retry-After"));
            assertEquals(Map.of("error", "quota_exceeded", "message", "API rate limit (5/min) exceeded for tenant 'default'"),
                    resumed.getEntity());
        }

        @Test
        @DisplayName("should resume with 404, not 500, when the conversation does not exist")
        void conversationNotFound() throws Exception {
            var asyncResponse = mock(AsyncResponse.class);
            var inputData = new InputData("Hello", Map.of());

            doThrow(new ConversationNotFoundException("No conversation found! (conversationId=conv-gone)"))
                    .when(conversationService).say(anyString(), any(), any(), any(), any(), anyBoolean(), any());

            restAgentEngine.sayWithinContext("conv-gone", false, false,
                    List.of(), inputData, asyncResponse);

            // Same shape as the quota branch above: say() is resumed via
            // AsyncResponse, so ConversationNotFoundExceptionMapper never runs and
            // this fell through to the generic handler as a 500 — while every GET
            // on the same conversation already answered 404.
            var captor = ArgumentCaptor.forClass(Response.class);
            verify(asyncResponse).resume(captor.capture());
            Response resumed = captor.getValue();
            assertEquals(404, resumed.getStatus());
            assertEquals("No conversation found! (conversationId=conv-gone)", resumed.getEntity());
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

            verify(asyncResponse).resume(any(NotFoundException.class));
        }

        @Test
        @DisplayName("should resume with 503 + Retry-After, not 500, when the node is draining")
        void rejectedWhileShuttingDown() throws Exception {
            var asyncResponse = mock(AsyncResponse.class);
            var inputData = new InputData("Hello", Map.of());

            // ConversationService#rejectIfShuttingDown throws this synchronously on the
            // request thread once GracefulShutdownService flips isShuttingDown.
            doThrow(new RejectedExecutionException(
                    "This node is shutting down and no longer accepts new conversation turns — retry against another node"))
                    .when(conversationService).say(anyString(), any(), any(), any(), any(), anyBoolean(), any());

            // Without the explicit catch this fell through to the generic handler, which
            // THROWS InternalServerErrorException instead of resuming — so the assertions
            // below (resume called at all, and with 503) both fail on the old code.
            restAgentEngine.sayWithinContext("conv-1", false, false,
                    List.of(), inputData, asyncResponse);

            var captor = ArgumentCaptor.forClass(Response.class);
            verify(asyncResponse).resume(captor.capture());
            Response resumed = captor.getValue();
            assertEquals(503, resumed.getStatus());
            assertEquals("5", resumed.getHeaderString("Retry-After"));
            assertEquals(Map.of("error", "capacity_exceeded",
                    "message", "This node is shutting down and no longer accepts new conversation turns"
                            + " — retry against another node"),
                    resumed.getEntity());
        }

        @Test
        @DisplayName("should resume with 503 when the coordinator rejects with a null message")
        void rejectedWithoutMessage() throws Exception {
            var asyncResponse = mock(AsyncResponse.class);
            var inputData = new InputData("Hello", Map.of());

            doThrow(new RejectedExecutionException())
                    .when(conversationService).say(anyString(), any(), any(), any(), any(), anyBoolean(), any());

            restAgentEngine.sayWithinContext("conv-1", false, false,
                    List.of(), inputData, asyncResponse);

            var captor = ArgumentCaptor.forClass(Response.class);
            verify(asyncResponse).resume(captor.capture());
            Response resumed = captor.getValue();
            assertEquals(503, resumed.getStatus());
            // mirrors RejectedExecutionExceptionMapper's null-message fallback
            assertEquals(Map.of("error", "capacity_exceeded", "message", "Service temporarily unavailable"),
                    resumed.getEntity());
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
            verify(asyncResponse, never()).resume(any(Response.class));
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

            verify(asyncResponse).setTimeout(30, TimeUnit.SECONDS);
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

            verify(asyncResponse).setTimeout(30, TimeUnit.SECONDS);
            var captor = ArgumentCaptor.forClass(InputData.class);
            verify(conversationService).say(eq("conv-1"), eq(false), eq(false),
                    eq(List.of()), captor.capture(), eq(true), any());
            InputData capturedInput = captor.getValue();
            assertEquals("", capturedInput.getInput());
            assertTrue(capturedInput.getContext().containsKey("lang"));
        }

        /**
         * {@code language} used to be mandatory ({@code checkNotEmpty}) and 400'd every
         * caller who followed the endpoint's own description, which never mentioned it.
         * It is optional now, like on {@code say()} — a rerun without it simply carries
         * no language context.
         */
        @Test
        @DisplayName("a rerun without a language proceeds, with no language context")
        void nullLanguageIsOptional() throws Exception {
            var asyncResponse = mock(AsyncResponse.class);

            restAgentEngine.rerunLastConversationStep("conv-1", null, false, false,
                    List.of(), asyncResponse);

            var captor = ArgumentCaptor.forClass(InputData.class);
            verify(conversationService).say(eq("conv-1"), eq(false), eq(false),
                    eq(List.of()), captor.capture(), eq(true), any());
            assertTrue(captor.getValue().getContext().isEmpty(),
                    "absent language means no context entry, exactly as on say()");
        }

        @Test
        @DisplayName("should resume with 503 while draining (rerun shares sayInternal)")
        void rejectedWhileShuttingDown() throws Exception {
            var asyncResponse = mock(AsyncResponse.class);

            doThrow(new RejectedExecutionException("node draining"))
                    .when(conversationService).say(anyString(), any(), any(), any(), any(), anyBoolean(), any());

            restAgentEngine.rerunLastConversationStep("conv-1", "en", false, false,
                    List.of(), asyncResponse);

            var captor = ArgumentCaptor.forClass(Response.class);
            verify(asyncResponse).resume(captor.capture());
            Response resumed = captor.getValue();
            assertEquals(503, resumed.getStatus());
            assertEquals("5", resumed.getHeaderString("Retry-After"));
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

    @Nested
    @DisplayName("OwnershipValidation")
    class OwnershipValidation {

        @Test
        @DisplayName("should pass resolved userId to startConversation")
        void startConversation_resolvesUserId() throws Exception {
            when(ownershipValidator.validateAndResolveUserId(identity, "user-1"))
                    .thenReturn("admin-resolved");
            var result = new IConversationService.ConversationResult("conv-1", URI.create("/conversations/conv-1"));
            when(conversationService.startConversation(any(), anyString(), eq("admin-resolved"), any()))
                    .thenReturn(result);

            Response response = restAgentEngine.startConversation("agent-1",
                    Deployment.Environment.production, "user-1");

            assertEquals(201, response.getStatus());
            verify(conversationService).startConversation(
                    Deployment.Environment.production, "agent-1", "admin-resolved", Map.of());
        }

        @Test
        @DisplayName("should throw ForbiddenException when caller tries to impersonate")
        void startConversation_rejectsImpersonation() {
            when(ownershipValidator.validateAndResolveUserId(identity, "other-user"))
                    .thenThrow(new ForbiddenException("Access denied"));

            assertThrows(ForbiddenException.class,
                    () -> restAgentEngine.startConversation("agent-1",
                            Deployment.Environment.production, "other-user"));
        }

        @Test
        @DisplayName("should throw ForbiddenException when caller does not own conversation (read)")
        void readConversation_rejectsNonOwner() throws Exception {
            var descriptor = mock(ConversationDescriptor.class);
            when(descriptor.getUserId()).thenReturn("other-user");
            doReturn(descriptor).when(descriptorStore).readDescriptor("conv-1", 0);
            doThrow(new ForbiddenException("Access denied"))
                    .when(ownershipValidator).requireOwnerOrAdmin(identity, "other-user", "conversation");

            assertThrows(ForbiddenException.class,
                    () -> restAgentEngine.readConversation("conv-1", false, false, List.of()));
        }

        @Test
        @DisplayName("should throw ForbiddenException when caller does not own conversation (end)")
        void endConversation_rejectsNonOwner() throws Exception {
            var descriptor = mock(ConversationDescriptor.class);
            when(descriptor.getUserId()).thenReturn("other-user");
            doReturn(descriptor).when(descriptorStore).readDescriptor("conv-1", 0);
            doThrow(new ForbiddenException("Access denied"))
                    .when(ownershipValidator).requireOwnerOrAdmin(identity, "other-user", "conversation");

            assertThrows(ForbiddenException.class,
                    () -> restAgentEngine.endConversation("conv-1"));
        }

        @Test
        @DisplayName("should skip ownership check when descriptor not found")
        void descriptorNotFound_skipsCheck() throws Exception {
            // Default stub already throws ResourceNotFoundException — just verify behavior
            var snapshot = new SimpleConversationMemorySnapshot();
            snapshot.setConversationState(ConversationState.READY);
            when(conversationService.readConversation("conv-1", false, false, List.of()))
                    .thenReturn(snapshot);

            SimpleConversationMemorySnapshot result = restAgentEngine
                    .readConversation("conv-1", false, false, List.of());

            assertEquals(ConversationState.READY, result.getConversationState());
            verify(ownershipValidator, never()).requireOwnerOrAdmin(any(), any(), any());
        }
    }
}
