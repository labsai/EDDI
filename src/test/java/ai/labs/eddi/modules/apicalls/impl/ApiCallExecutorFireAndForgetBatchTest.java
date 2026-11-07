/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.apicalls.impl;

import ai.labs.eddi.configs.apicalls.model.ApiCall;
import ai.labs.eddi.configs.apicalls.model.BatchRequestBuildingInstruction;
import ai.labs.eddi.configs.apicalls.model.HttpPreRequest;
import ai.labs.eddi.configs.apicalls.model.Request;
import ai.labs.eddi.configs.variables.GlobalVariableResolver;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.httpclient.ICompleteListener;
import ai.labs.eddi.engine.httpclient.IHttpClient;
import ai.labs.eddi.engine.httpclient.IRequest;
import ai.labs.eddi.engine.httpclient.IResponse;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.IConversationMemory.IWritableConversationStep;
import ai.labs.eddi.engine.runtime.IRuntime;
import ai.labs.eddi.engine.security.CallerIdentityContext;
import ai.labs.eddi.engine.security.CallerIdentityResolver;
import ai.labs.eddi.secrets.SecretResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A fire-and-forget batch is one background task. A request of it that threw —
 * a connection reset, a timeout — used to end that task, so every later item of
 * the batch was silently never sent, while the turn had long reported success.
 * Each item is now sent on its own: a failure is logged and the rest of the
 * batch still goes out, in both the sequential and the concurrent mode.
 */
@DisplayName("ApiCallExecutor — one failed request of a fire-and-forget batch does not drop the rest")
class ApiCallExecutorFireAndForgetBatchTest {

    private IRuntime runtime;
    private ApiCallExecutor executor;
    private IConversationMemory memory;
    private IRequest first;
    private IRequest second;
    private IRequest third;

    @BeforeEach
    void setUp() throws Exception {
        IHttpClient httpClient = mock(IHttpClient.class);
        runtime = mock(IRuntime.class);
        PrePostUtils prePostUtils = mock(PrePostUtils.class);
        SecretResolver secretResolver = mock(SecretResolver.class);
        GlobalVariableResolver globalVariableResolver = mock(GlobalVariableResolver.class);
        CallerIdentityResolver callerIdentityResolver = mock(CallerIdentityResolver.class);
        when(secretResolver.resolveValue(anyString())).thenAnswer(inv -> inv.getArgument(0));
        when(globalVariableResolver.resolveValue(anyString())).thenAnswer(inv -> inv.getArgument(0));
        when(callerIdentityResolver.resolveValue(anyString(), any())).thenAnswer(inv -> inv.getArgument(0));

        executor = new ApiCallExecutor(httpClient, mock(IJsonSerialization.class), runtime, prePostUtils, globalVariableResolver,
                secretResolver, callerIdentityResolver, new CallerIdentityContext(null, null), new RequestRedactor(callerIdentityResolver),
                null, false, 30_000L, 2_000_000);

        memory = mock(IConversationMemory.class);
        when(memory.getCurrentStep()).thenReturn(mock(IWritableConversationStep.class));

        first = request();
        second = request();
        third = request();
        when(httpClient.newRequest(any(URI.class), any())).thenReturn(first, second, third);

        when(prePostUtils.executePreRequestPropertyInstructions(any(), any(), any())).thenAnswer(inv -> inv.getArgument(1));
        when(prePostUtils.templateValues(anyString(), any())).thenAnswer(inv -> inv.getArgument(0));
        when(prePostUtils.buildIterationValues(any(), any(), any(), any())).thenReturn(List.of("a", "b", "c"));
    }

    @Test
    @DisplayName("sequential: the request after a thrown one is still sent")
    void sequentialBatchContinuesAfterAFailedRequest() throws Exception {
        doThrow(new IRequest.HttpRequestException("connection reset", new RuntimeException())).when(second).send();

        Callable<Object> batch = dispatchedBatch(true);

        assertDoesNotThrow(batch::call, "a failed item must not end the batch task");
        verify(first, times(1)).send();
        verify(second, times(1)).send();
        verify(third, times(1)).send();
    }

    @Test
    @DisplayName("concurrent: the request after one whose dispatch threw is still sent")
    void concurrentBatchContinuesAfterAFailedRequest() throws Exception {
        doThrow(new IllegalStateException("client closed")).when(second).send(any(ICompleteListener.class));

        Callable<Object> batch = dispatchedBatch(false);

        assertDoesNotThrow(batch::call, "a failed item must not end the batch task");
        verify(first, times(1)).send(any(ICompleteListener.class));
        verify(third, times(1)).send(any(ICompleteListener.class));
    }

    @Test
    @DisplayName("an interrupted batch stops sending and leaves the interrupt flag set")
    void interruptedBatchStops() throws Exception {
        Callable<Object> batch = dispatchedBatch(true);

        try {
            Thread.currentThread().interrupt();
            assertDoesNotThrow(batch::call);
            assertTrue(Thread.currentThread().isInterrupted(), "the interrupt is left for whoever owns the thread");
        } finally {
            Thread.interrupted();
        }
        verify(first, never()).send();
        verify(second, never()).send();
        verify(third, never()).send();
    }

    private Callable<Object> dispatchedBatch(boolean sequentially) throws Exception {
        executor.execute(batchCall(sequentially), memory, new HashMap<>(Map.of("items", List.of("a", "b", "c"))), "http://api.example.com");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Callable<Object>> dispatched = ArgumentCaptor.forClass(Callable.class);
        verify(runtime).submitCallable(dispatched.capture(), isNull());
        return dispatched.getValue();
    }

    private static IRequest request() throws Exception {
        IRequest request = mock(IRequest.class);
        IResponse response = mock(IResponse.class);
        when(request.send()).thenReturn(response);
        when(request.setHttpHeader(any(), any())).thenReturn(request);
        when(request.setQueryParam(any(), any())).thenReturn(request);
        when(request.setBodyEntity(any(), any(), any())).thenReturn(request);
        when(request.toMap()).thenReturn(new HashMap<>());
        when(response.getHttpCode()).thenReturn(200);
        when(response.getHttpHeader()).thenReturn(new HashMap<>());
        return request;
    }

    private static ApiCall batchCall(boolean sequentially) {
        ApiCall call = new ApiCall();
        call.setName("notify");
        call.setFireAndForget(true);
        call.setSaveResponse(false);
        Request request = new Request();
        request.setPath("/api/items");
        request.setMethod("POST");
        call.setRequest(request);
        var batch = new BatchRequestBuildingInstruction();
        batch.setIterationObjectName("item");
        batch.setPathToTargetArray("items");
        batch.setExecuteCallsSequentially(sequentially);
        var preRequest = new HttpPreRequest();
        preRequest.setBatchRequests(batch);
        call.setPreRequest(preRequest);
        return call;
    }
}
