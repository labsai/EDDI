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
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.httpclient.IHttpClient;
import ai.labs.eddi.engine.httpclient.IRequest;
import ai.labs.eddi.engine.lifecycle.exceptions.LifecycleException;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.IConversationMemory.IWritableConversationStep;
import ai.labs.eddi.engine.runtime.IRuntime;
import ai.labs.eddi.engine.security.CallerIdentityContext;
import ai.labs.eddi.engine.security.CallerIdentityResolver;
import ai.labs.eddi.secrets.SecretResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A fire-and-forget batch expands into one request per element of its target
 * array — data that usually comes from an upstream response or an LLM. It used
 * to have no upper bound, so one turn could fan out into as many outbound
 * requests as that array had elements (M-Q3). The batch is now capped, and a
 * batch over the cap is refused as a whole rather than truncated.
 */
@DisplayName("ApiCallExecutor — fire-and-forget batch fan-out is capped")
class ApiCallExecutorBatchCapTest {

    private static final String SERVER = "http://api.example.com";

    private IHttpClient httpClient;
    private IRuntime runtime;
    private PrePostUtils prePostUtils;
    private ApiCallExecutor executor;
    private IConversationMemory memory;

    @BeforeEach
    void setUp() throws Exception {
        httpClient = mock(IHttpClient.class);
        runtime = mock(IRuntime.class);
        prePostUtils = mock(PrePostUtils.class);
        SecretResolver secretResolver = mock(SecretResolver.class);
        GlobalVariableResolver globalVariableResolver = mock(GlobalVariableResolver.class);
        CallerIdentityResolver callerIdentityResolver = mock(CallerIdentityResolver.class);
        when(secretResolver.resolveValue(anyString())).thenAnswer(inv -> inv.getArgument(0));
        when(globalVariableResolver.resolveValue(anyString())).thenAnswer(inv -> inv.getArgument(0));
        when(callerIdentityResolver.resolveValue(anyString(), any())).thenAnswer(inv -> inv.getArgument(0));

        executor = new ApiCallExecutor(httpClient, mock(IJsonSerialization.class), runtime, prePostUtils, globalVariableResolver,
                secretResolver, callerIdentityResolver, new CallerIdentityContext(null, null), new RequestRedactor(callerIdentityResolver),
                mock(ConnectionResolver.class), false, 30_000L, 2_000_000);

        memory = mock(IConversationMemory.class);
        when(memory.getCurrentStep()).thenReturn(mock(IWritableConversationStep.class));

        IRequest request = mock(IRequest.class);
        when(httpClient.newRequest(any(URI.class), any())).thenReturn(request);
        when(request.setHttpHeader(any(), any())).thenReturn(request);
        when(request.toMap()).thenReturn(new HashMap<>());

        when(prePostUtils.executePreRequestPropertyInstructions(any(), any(), any())).thenAnswer(inv -> inv.getArgument(1));
        when(prePostUtils.templateValues(anyString(), any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    @DisplayName("a batch over the default cap is refused before any request is built or dispatched")
    void overDefaultCapIsRefusedWhole() throws Exception {
        givenTargetArrayOf(ApiCallExecutor.DEFAULT_MAX_BATCH_SIZE + 1);

        LifecycleException e = assertThrows(LifecycleException.class, () -> executor.execute(batchCall(null), memory, new HashMap<>(), SERVER));

        assertTrue(e.getMessage().contains("maxBatchSize"), "the refusal must name the knob that raises it: " + e.getMessage());
        verify(httpClient, never()).newRequest(any(URI.class), any());
        verify(runtime, never()).submitCallable(any(), any());
    }

    @Test
    @DisplayName("a batch exactly at the default cap is sent")
    void atDefaultCapIsSent() throws Exception {
        givenTargetArrayOf(ApiCallExecutor.DEFAULT_MAX_BATCH_SIZE);

        executor.execute(batchCall(null), memory, new HashMap<>(), SERVER);

        verify(httpClient, times(ApiCallExecutor.DEFAULT_MAX_BATCH_SIZE)).newRequest(any(URI.class), any());
        verify(runtime).submitCallable(any(), isNull());
    }

    @Test
    @DisplayName("a configured maxBatchSize lowers the cap")
    void configuredCapLowersTheLimit() throws Exception {
        givenTargetArrayOf(3);

        assertThrows(LifecycleException.class, () -> executor.execute(batchCall(2), memory, new HashMap<>(), SERVER));
        verify(httpClient, never()).newRequest(any(URI.class), any());
    }

    @Test
    @DisplayName("maxBatchSize: unset or non-positive means the default; everything is capped at the ceiling")
    void resolveMaxBatchSize() {
        assertEquals(100, ApiCallExecutor.resolveMaxBatchSize(null, 100, 1000));
        assertEquals(100, ApiCallExecutor.resolveMaxBatchSize(0, 100, 1000));
        assertEquals(100, ApiCallExecutor.resolveMaxBatchSize(-5, 100, 1000));
        assertEquals(500, ApiCallExecutor.resolveMaxBatchSize(500, 100, 1000));
        assertEquals(1000, ApiCallExecutor.resolveMaxBatchSize(Integer.MAX_VALUE, 100, 1000));
        assertEquals(50, ApiCallExecutor.resolveMaxBatchSize(null, 100, 50), "a default above the ceiling is capped too");
    }

    /**
     * An operator can restore the pre-cap behaviour for a deployment without
     * editing every stored config.
     */
    @Test
    @DisplayName("the operator-configured default applies to calls that set no maxBatchSize")
    void operatorDefaultApplies() throws Exception {
        executor.defaultMaxBatchSize = 500;
        executor.maxBatchSizeCeiling = 5000;
        givenTargetArrayOf(300);

        executor.execute(batchCall(null), memory, new HashMap<>(), SERVER);

        verify(httpClient, times(300)).newRequest(any(URI.class), any());
    }

    @Test
    @DisplayName("the operator-configured ceiling caps a stored maxBatchSize above it")
    void operatorCeilingCapsStoredValue() throws Exception {
        executor.maxBatchSizeCeiling = 10;
        givenTargetArrayOf(11);

        LifecycleException e = assertThrows(LifecycleException.class, () -> executor.execute(batchCall(500), memory, new HashMap<>(), SERVER));

        assertTrue(e.getMessage().contains("eddi.httpcalls.batch.max-size-ceiling"), e.getMessage());
        verify(httpClient, never()).newRequest(any(URI.class), any());
    }

    private void givenTargetArrayOf(int size) throws Exception {
        List<Object> items = Collections.nCopies(size, "item");
        when(prePostUtils.buildIterationValues(any(), any(), any(), any())).thenReturn(items);
    }

    private static BatchRequestBuildingInstruction batchInstruction(Integer maxBatchSize) {
        var batch = new BatchRequestBuildingInstruction();
        batch.setIterationObjectName("item");
        batch.setPathToTargetArray("items");
        batch.setMaxBatchSize(maxBatchSize);
        return batch;
    }

    private static ApiCall batchCall(Integer maxBatchSize) {
        ApiCall call = new ApiCall();
        call.setName("batch-call");
        call.setFireAndForget(true);
        call.setSaveResponse(false);
        Request request = new Request();
        request.setPath("/api/items");
        request.setMethod("POST");
        call.setRequest(request);
        var preRequest = new HttpPreRequest();
        preRequest.setBatchRequests(batchInstruction(maxBatchSize));
        call.setPreRequest(preRequest);
        return call;
    }
}
