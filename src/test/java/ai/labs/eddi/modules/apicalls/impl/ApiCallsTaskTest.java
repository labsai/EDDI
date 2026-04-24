/*
 * Copyright (c) 2016-2026 EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.apicalls.impl;

import ai.labs.eddi.configs.apicalls.model.ApiCall;
import ai.labs.eddi.configs.apicalls.model.ApiCallsConfiguration;
import ai.labs.eddi.configs.workflows.model.ExtensionDescriptor;
import ai.labs.eddi.engine.lifecycle.exceptions.LifecycleException;
import ai.labs.eddi.engine.lifecycle.exceptions.WorkflowConfigurationException;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.IConversationMemory.IWritableConversationStep;
import ai.labs.eddi.engine.memory.IData;
import ai.labs.eddi.engine.memory.IMemoryItemConverter;
import ai.labs.eddi.engine.memory.MemoryKeys;
import ai.labs.eddi.engine.runtime.client.configuration.IResourceClientLibrary;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Tests for {@link ApiCallsTask} — the HTTP calls lifecycle task.
 */
@DisplayName("ApiCallsTask")
class ApiCallsTaskTest {

    @Mock
    private IResourceClientLibrary resourceClientLibrary;
    @Mock
    private IMemoryItemConverter memoryItemConverter;
    @Mock
    private IApiCallExecutor httpCallExecutor;
    @Mock
    private IConversationMemory memory;
    @Mock
    private IWritableConversationStep currentStep;

    private ApiCallsTask task;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        task = new ApiCallsTask(resourceClientLibrary, memoryItemConverter, httpCallExecutor);
        when(memory.getCurrentStep()).thenReturn(currentStep);
    }

    @Test
    @DisplayName("returns correct ID")
    void getId() {
        assertEquals("ai.labs.httpcalls", task.getId());
    }

    @Test
    @DisplayName("returns correct type")
    void getType() {
        assertEquals("httpCalls", task.getType());
    }

    @Nested
    @DisplayName("execute")
    class Execute {

        @Test
        @DisplayName("returns early when no actions in memory")
        void noActions() throws LifecycleException {
            when(currentStep.getLatestData(MemoryKeys.ACTIONS)).thenReturn(null);

            ApiCallsConfiguration config = new ApiCallsConfiguration();
            task.execute(memory, config);

            verifyNoInteractions(httpCallExecutor);
        }

        @Test
        @DisplayName("executes matching API calls for actions")
        @SuppressWarnings("unchecked")
        void executesMatchingCalls() throws Exception {
            // Setup actions
            IData<List<String>> actionsData = mock(IData.class);
            when(actionsData.getResult()).thenReturn(List.of("greet"));
            when(currentStep.getLatestData(MemoryKeys.ACTIONS)).thenReturn(actionsData);
            when(memoryItemConverter.convert(memory)).thenReturn(new HashMap<>());

            // Setup API call config
            ApiCall greetCall = new ApiCall();
            greetCall.setActions(List.of("greet"));

            ApiCallsConfiguration config = new ApiCallsConfiguration();
            config.setHttpCalls(List.of(greetCall));
            config.setTargetServerUrl("http://localhost:8080");

            when(httpCallExecutor.execute(any(), any(), any(), any())).thenReturn(Map.of("result", "ok"));

            task.execute(memory, config);

            verify(httpCallExecutor).execute(eq(greetCall), eq(memory), any(), eq("http://localhost:8080"));
        }

        @Test
        @DisplayName("skips non-matching API calls")
        @SuppressWarnings("unchecked")
        void skipsNonMatching() throws Exception {
            IData<List<String>> actionsData = mock(IData.class);
            when(actionsData.getResult()).thenReturn(List.of("farewell"));
            when(currentStep.getLatestData(MemoryKeys.ACTIONS)).thenReturn(actionsData);
            when(memoryItemConverter.convert(memory)).thenReturn(new HashMap<>());

            ApiCall greetCall = new ApiCall();
            greetCall.setActions(List.of("greet"));

            ApiCallsConfiguration config = new ApiCallsConfiguration();
            config.setHttpCalls(List.of(greetCall));
            config.setTargetServerUrl("http://localhost:8080");

            task.execute(memory, config);

            verifyNoInteractions(httpCallExecutor);
        }

        @Test
        @DisplayName("wildcard action matches everything")
        @SuppressWarnings("unchecked")
        void wildcardAction() throws Exception {
            IData<List<String>> actionsData = mock(IData.class);
            when(actionsData.getResult()).thenReturn(List.of("anything"));
            when(currentStep.getLatestData(MemoryKeys.ACTIONS)).thenReturn(actionsData);
            when(memoryItemConverter.convert(memory)).thenReturn(new HashMap<>());

            ApiCall wildcardCall = new ApiCall();
            wildcardCall.setActions(List.of("*"));

            ApiCallsConfiguration config = new ApiCallsConfiguration();
            config.setHttpCalls(List.of(wildcardCall));
            config.setTargetServerUrl("http://localhost:8080");

            when(httpCallExecutor.execute(any(), any(), any(), any())).thenReturn(null);

            task.execute(memory, config);

            verify(httpCallExecutor).execute(eq(wildcardCall), any(), any(), any());
        }

        @Test
        @DisplayName("empty actions list — no API calls executed")
        @SuppressWarnings("unchecked")
        void emptyActionsList() throws Exception {
            IData<List<String>> actionsData = mock(IData.class);
            when(actionsData.getResult()).thenReturn(List.of());
            when(currentStep.getLatestData(MemoryKeys.ACTIONS)).thenReturn(actionsData);
            when(memoryItemConverter.convert(memory)).thenReturn(new HashMap<>());

            ApiCall greetCall = new ApiCall();
            greetCall.setActions(List.of("greet"));

            ApiCallsConfiguration config = new ApiCallsConfiguration();
            config.setHttpCalls(List.of(greetCall));
            config.setTargetServerUrl("http://localhost:8080");

            task.execute(memory, config);

            verifyNoInteractions(httpCallExecutor);
        }

        @Test
        @DisplayName("multiple matching API calls execute in order")
        @SuppressWarnings("unchecked")
        void multipleMatchingCallsExecuteInOrder() throws Exception {
            IData<List<String>> actionsData = mock(IData.class);
            when(actionsData.getResult()).thenReturn(List.of("greet"));
            when(currentStep.getLatestData(MemoryKeys.ACTIONS)).thenReturn(actionsData);
            when(memoryItemConverter.convert(memory)).thenReturn(new HashMap<>());

            ApiCall call1 = new ApiCall();
            call1.setActions(List.of("greet"));
            ApiCall call2 = new ApiCall();
            call2.setActions(List.of("greet"));

            ApiCallsConfiguration config = new ApiCallsConfiguration();
            config.setHttpCalls(List.of(call1, call2));
            config.setTargetServerUrl("http://localhost:8080");

            when(httpCallExecutor.execute(any(), any(), any(), any())).thenReturn(Map.of("key", "val"));

            task.execute(memory, config);

            var inOrder = inOrder(httpCallExecutor);
            inOrder.verify(httpCallExecutor).execute(eq(call1), any(), any(), any());
            inOrder.verify(httpCallExecutor).execute(eq(call2), any(), any(), any());
        }

        @Test
        @DisplayName("httpCallResult null — does not merge into templateData")
        @SuppressWarnings("unchecked")
        void nullResultNoMerge() throws Exception {
            IData<List<String>> actionsData = mock(IData.class);
            when(actionsData.getResult()).thenReturn(List.of("fetch"));
            when(currentStep.getLatestData(MemoryKeys.ACTIONS)).thenReturn(actionsData);

            var templateData = new HashMap<String, Object>();
            when(memoryItemConverter.convert(memory)).thenReturn(templateData);

            ApiCall fetchCall = new ApiCall();
            fetchCall.setActions(List.of("fetch"));

            ApiCallsConfiguration config = new ApiCallsConfiguration();
            config.setHttpCalls(List.of(fetchCall));
            config.setTargetServerUrl("http://api.example.com");

            when(httpCallExecutor.execute(any(), any(), any(), any())).thenReturn(null);

            task.execute(memory, config);

            // templateData should not have been modified (null result)
            assertTrue(templateData.isEmpty());
        }

        @Test
        @DisplayName("httpCallResult non-empty — merges into templateData for subsequent calls")
        @SuppressWarnings("unchecked")
        void nonEmptyResultMerges() throws Exception {
            IData<List<String>> actionsData = mock(IData.class);
            when(actionsData.getResult()).thenReturn(List.of("fetch"));
            when(currentStep.getLatestData(MemoryKeys.ACTIONS)).thenReturn(actionsData);

            var templateData = new HashMap<String, Object>();
            when(memoryItemConverter.convert(memory)).thenReturn(templateData);

            ApiCall fetchCall = new ApiCall();
            fetchCall.setActions(List.of("fetch"));

            ApiCallsConfiguration config = new ApiCallsConfiguration();
            config.setHttpCalls(List.of(fetchCall));
            config.setTargetServerUrl("http://api.example.com");

            when(httpCallExecutor.execute(any(), any(), any(), any()))
                    .thenReturn(Map.of("weather", "sunny", "temp", "72"));

            task.execute(memory, config);

            // templateData should now contain merged results
            assertEquals("sunny", templateData.get("weather"));
            assertEquals("72", templateData.get("temp"));
        }
    }

    @Nested
    @DisplayName("configure")
    class Configure {

        @Test
        @DisplayName("throws when no URI provided")
        void noUri() {
            Map<String, Object> config = new HashMap<>();
            assertThrows(WorkflowConfigurationException.class,
                    () -> task.configure(config, Map.of()));
        }

        @Test
        @DisplayName("throws when URI is empty")
        void emptyUri() {
            Map<String, Object> config = new HashMap<>();
            config.put("uri", "");
            assertThrows(WorkflowConfigurationException.class,
                    () -> task.configure(config, Map.of()));
        }

        @Test
        @DisplayName("loads config and strips trailing slash from targetServerUrl")
        void loadsConfigStripsSlash() throws Exception {
            ApiCallsConfiguration apiConfig = new ApiCallsConfiguration();
            apiConfig.setTargetServerUrl("http://example.com/api/");
            apiConfig.setHttpCalls(List.of());

            when(resourceClientLibrary.getResource(any(URI.class), eq(ApiCallsConfiguration.class)))
                    .thenReturn(apiConfig);

            Map<String, Object> config = Map.of("uri", "eddi://config/123");
            Object result = task.configure(config, Map.of());

            assertNotNull(result);
            assertEquals("http://example.com/api", ((ApiCallsConfiguration) result).getTargetServerUrl());
        }

        @Test
        @DisplayName("throws when targetServerUrl is empty")
        void emptyTargetServerUrl() throws Exception {
            ApiCallsConfiguration apiConfig = new ApiCallsConfiguration();
            apiConfig.setTargetServerUrl("");

            when(resourceClientLibrary.getResource(any(URI.class), eq(ApiCallsConfiguration.class)))
                    .thenReturn(apiConfig);

            Map<String, Object> config = Map.of("uri", "eddi://config/123");
            assertThrows(WorkflowConfigurationException.class,
                    () -> task.configure(config, Map.of()));
        }
    }

    @Test
    @DisplayName("extension descriptor has correct ID and display name")
    void extensionDescriptor() {
        ExtensionDescriptor descriptor = task.getExtensionDescriptor();
        assertEquals("ai.labs.httpcalls", descriptor.getType());
        assertEquals("Http Calls", descriptor.getDisplayName());
        assertTrue(descriptor.getConfigs().containsKey("uri"));
    }
}
