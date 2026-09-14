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
import ai.labs.eddi.connections.ConnectionResolver;
import ai.labs.eddi.connections.ResolvedCredential;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.httpclient.IHttpClient;
import ai.labs.eddi.engine.httpclient.IRequest;
import ai.labs.eddi.engine.httpclient.IResponse;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.IConversationMemory.IWritableConversationStep;
import ai.labs.eddi.engine.runtime.IRuntime;
import ai.labs.eddi.engine.security.CallerIdentity;
import ai.labs.eddi.engine.security.CallerIdentityContext;
import ai.labs.eddi.engine.security.CallerIdentityResolver;
import ai.labs.eddi.engine.security.ResolutionPrincipal;
import ai.labs.eddi.engine.security.ResolutionPrincipalContext;
import ai.labs.eddi.secrets.SecretResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.net.URI;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A fire-and-forget batch runs on a runtime thread of its own. The executor
 * wraps that work with {@code CallerIdentityContext#propagate}, which used to
 * carry the caller and nothing else — so a {@code PER_USER} connection resolved
 * inside the batch found no {@link ResolutionPrincipal} on the worker and was
 * refused as if the turn were a scheduled run, with advice about scheduled
 * runs. Both bindings have to travel together.
 */
@DisplayName("ApiCallExecutor — a fire-and-forget batch keeps the turn's principal and caller")
class ApiCallExecutorBatchPrincipalPropagationTest {

    private static final String SERVER = "http://api.example.com";
    private static final ResolutionPrincipal OWNER = new ResolutionPrincipal("alice", ResolutionPrincipal.Provenance.VERIFIED);
    private static final CallerIdentity CALLER = new CallerIdentity("alice-token", "alice", "https://eddi.example.com");

    private final CallerIdentityContext callerIdentityContext = new CallerIdentityContext(null, null);
    private final ResolutionPrincipalContext resolutionPrincipalContext = new ResolutionPrincipalContext();

    private IRuntime runtime;
    private ConnectionResolver connectionResolver;
    private ApiCallExecutor executor;
    private IConversationMemory memory;

    private final AtomicReference<ResolutionPrincipal> principalSeenByResolver = new AtomicReference<>();
    private final AtomicReference<CallerIdentity> callerSeenByResolver = new AtomicReference<>();

    @BeforeEach
    void setUp() throws Exception {
        callerIdentityContext.clear();
        resolutionPrincipalContext.clear();

        IHttpClient httpClient = mock(IHttpClient.class);
        runtime = mock(IRuntime.class);
        PrePostUtils prePostUtils = mock(PrePostUtils.class);
        connectionResolver = mock(ConnectionResolver.class);
        SecretResolver secretResolver = mock(SecretResolver.class);
        GlobalVariableResolver globalVariableResolver = mock(GlobalVariableResolver.class);
        CallerIdentityResolver callerIdentityResolver = mock(CallerIdentityResolver.class);
        when(secretResolver.resolveValue(anyString())).thenAnswer(inv -> inv.getArgument(0));
        when(globalVariableResolver.resolveValue(anyString())).thenAnswer(inv -> inv.getArgument(0));
        when(callerIdentityResolver.resolveValue(anyString(), any())).thenAnswer(inv -> inv.getArgument(0));

        // The resolver is where the principal is actually consumed, so what it can see
        // on the worker thread is the whole question.
        doAnswer(inv -> {
            principalSeenByResolver.set(resolutionPrincipalContext.current());
            callerSeenByResolver.set(callerIdentityContext.current());
            return new ResolvedCredential("Authorization", "Bearer live-token");
        }).when(connectionResolver).resolve(anyString(), any(), any());

        executor = new ApiCallExecutor(httpClient, mock(IJsonSerialization.class), runtime, prePostUtils, globalVariableResolver,
                secretResolver, callerIdentityResolver, callerIdentityContext, new RequestRedactor(callerIdentityResolver), connectionResolver,
                false, 30_000L, 2_000_000);

        memory = mock(IConversationMemory.class);
        when(memory.getCurrentStep()).thenReturn(mock(IWritableConversationStep.class));

        IRequest request = mock(IRequest.class);
        IResponse response = mock(IResponse.class);
        when(httpClient.newRequest(any(URI.class), any())).thenReturn(request);
        when(request.send()).thenReturn(response);
        when(request.setHttpHeader(any(), any())).thenReturn(request);
        when(request.toMap()).thenReturn(new HashMap<>());
        when(response.getHttpCode()).thenReturn(200);
        when(response.getHttpHeader()).thenReturn(new HashMap<>());

        when(prePostUtils.executePreRequestPropertyInstructions(any(), any(), any())).thenAnswer(inv -> inv.getArgument(1));
        when(prePostUtils.templateValues(anyString(), any())).thenAnswer(inv -> inv.getArgument(0));
        when(prePostUtils.buildIterationValues(any(), any(), any(), any())).thenReturn(List.of("one"));
    }

    @AfterEach
    void tearDown() {
        callerIdentityContext.clear();
        resolutionPrincipalContext.clear();
    }

    @Test
    @DisplayName("the batch callable carries the dispatching turn's principal AND caller to the runtime thread")
    void batchCallableCarriesBothBindings() throws Exception {
        // The bindings a pipeline turn has when it reaches ApiCallExecutor.
        resolutionPrincipalContext.bind(OWNER);
        callerIdentityContext.bind(CALLER);

        executor.execute(batchCall(), memory, templateData(), SERVER);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Callable<Object>> dispatched = ArgumentCaptor.forClass(Callable.class);
        verify(runtime).submitCallable(dispatched.capture(), isNull());

        // Run it the way the runtime does: on another thread, with nothing bound.
        // Clearing this thread first proves the callable carries the bindings itself
        // rather than borrowing the test thread's.
        callerIdentityContext.clear();
        resolutionPrincipalContext.clear();
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            worker.submit(dispatched.getValue()).get();
            assertNull(worker.submit(resolutionPrincipalContext::current).get(), "the worker must be left with no principal afterwards");
        } finally {
            worker.shutdownNow();
        }

        assertEquals(OWNER, principalSeenByResolver.get(),
                "the connection resolver ran on the batch thread and must see the conversation's principal there, or every "
                        + "PER_USER connection in a fire-and-forget batch is refused with advice about scheduled runs");
        assertEquals(CALLER, callerSeenByResolver.get(), "the caller must still travel too — carrying one binding and not the other is the drift");
    }

    private static ApiCall batchCall() {
        ApiCall call = new ApiCall();
        call.setName("batch-call");
        call.setFireAndForget(true);
        call.setSaveResponse(false);
        Request request = new Request();
        request.setPath("/api/items");
        request.setMethod("POST");
        request.setHeaders(new LinkedHashMap<>(Map.of("Authorization", "${connection:jira}")));
        call.setRequest(request);
        var batch = new BatchRequestBuildingInstruction();
        batch.setIterationObjectName("item");
        batch.setPathToTargetArray("items");
        batch.setExecuteCallsSequentially(true);
        var preRequest = new HttpPreRequest();
        preRequest.setBatchRequests(batch);
        call.setPreRequest(preRequest);
        return call;
    }

    private static Map<String, Object> templateData() {
        var data = new HashMap<String, Object>();
        data.put("userInfo", new HashMap<String, Object>(Map.of("userId", "alice")));
        return data;
    }
}
