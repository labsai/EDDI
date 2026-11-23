/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.apicalls.impl;

import ai.labs.eddi.configs.apicalls.model.ApiCall;
import ai.labs.eddi.configs.apicalls.model.ApiCallsConfiguration;
import ai.labs.eddi.configs.apicalls.model.Request;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.IConversationMemory.IWritableConversationStep;
import ai.labs.eddi.engine.memory.IData;
import ai.labs.eddi.engine.memory.IMemoryItemConverter;
import ai.labs.eddi.engine.memory.MemoryKeys;
import ai.labs.eddi.engine.memory.model.Data;
import ai.labs.eddi.engine.runtime.client.configuration.IResourceClientLibrary;
import ai.labs.eddi.engine.security.EmailApprovalGate;
import ai.labs.eddi.engine.security.TurnOrigin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The email approval gate for rule-driven httpcalls (email plan v3 §8.1): a
 * mail turn whose target has an approval policy cannot pause one, so a call
 * {@code readOnlyTools} does not name is refused; untainted turns are
 * unaffected.
 */
class ApiCallsTaskEmailGateTest {

    private IApiCallExecutor executor;
    private IConversationMemory memory;
    private IWritableConversationStep step;
    private ApiCallsTask task;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        executor = mock(IApiCallExecutor.class);
        IMemoryItemConverter converter = mock(IMemoryItemConverter.class);
        memory = mock(IConversationMemory.class);
        step = mock(IWritableConversationStep.class);
        when(memory.getCurrentStep()).thenReturn(step);
        IData<List<String>> actions = mock(IData.class);
        when(actions.getResult()).thenReturn(List.of("order"));
        when(step.getLatestData(MemoryKeys.ACTIONS)).thenReturn(actions);
        when(converter.convert(memory)).thenReturn(new HashMap<>());
        when(executor.execute(any(), any(), any(), any())).thenReturn(Map.of());
        task = new ApiCallsTask(mock(IResourceClientLibrary.class), converter, executor);
    }

    private void mailTurn(String... readOnlyTools) {
        when(memory.getOrigin()).thenReturn(TurnOrigin.EMAIL);
        IData<Object> gate = new Data<>("context:" + EmailApprovalGate.CONTEXT_KEY,
                EmailApprovalGate.contextValue("actions", List.of(readOnlyTools), false, null, null));
        when(step.getData("context:" + EmailApprovalGate.CONTEXT_KEY)).thenReturn(gate);
    }

    private static ApiCall call(String name, String method, String path) {
        var call = new ApiCall();
        call.setName(name);
        call.setActions(List.of("order"));
        var request = new Request();
        request.setMethod(method);
        request.setPath(path);
        call.setRequest(request);
        return call;
    }

    private static ApiCallsConfiguration config(ApiCall... calls) {
        var config = new ApiCallsConfiguration();
        config.setHttpCalls(List.of(calls));
        config.setTargetServerUrl("https://shop.example.com");
        return config;
    }

    @Test
    @DisplayName("a tainted rule-driven httpcall is refused and never sent")
    void taintedCallRefused() throws Exception {
        mailTurn();
        var create = call("createOrder", "POST", "orders");

        task.execute(memory, config(create));

        verify(executor, never()).execute(any(), any(), any(), any());
        verify(step).storeData(argThat(data -> ApiCallsTask.EMAIL_GATE_REFUSED_KEY.equals(data.getKey())));
    }

    @Test
    @DisplayName("a call readOnlyTools names runs; the others in the step are refused")
    void readOnlyCallRuns() throws Exception {
        mailTurn("http.get:*");
        var lookup = call("lookupOrder", "GET", "orders/7");
        var create = call("createOrder", "POST", "orders");

        task.execute(memory, config(lookup, create));

        verify(executor).execute(eq(lookup), eq(memory), any(), any());
        verify(executor, never()).execute(eq(create), any(), any(), any());
    }

    @Test
    @DisplayName("an untainted turn's rule-driven httpcalls run as before")
    void untaintedRuns() throws Exception {
        var create = call("createOrder", "POST", "orders");

        task.execute(memory, config(create));

        verify(executor).execute(eq(create), eq(memory), any(), any());
    }
}
