/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.mcpcalls.impl;

import ai.labs.eddi.configs.apicalls.model.PostResponse;
import ai.labs.eddi.configs.mcpcalls.model.McpCall;
import ai.labs.eddi.configs.mcpcalls.model.McpCallsConfiguration;
import ai.labs.eddi.configs.shared.RetryConfiguration;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.lifecycle.exceptions.LifecycleException;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.IConversationMemory.IWritableConversationStep;
import ai.labs.eddi.engine.memory.IData;
import ai.labs.eddi.engine.memory.IMemoryItemConverter;
import ai.labs.eddi.engine.memory.model.Data;
import ai.labs.eddi.engine.runtime.client.configuration.IResourceClientLibrary;
import ai.labs.eddi.modules.apicalls.impl.PrePostUtils;
import ai.labs.eddi.modules.llm.impl.McpToolProviderManager;
import ai.labs.eddi.modules.llm.tools.ToolExecutionService;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.service.tool.ToolExecutor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.MockitoAnnotations.openMocks;

/**
 * Routing rule-triggered MCP calls through {@code ToolExecutionService} must
 * not cost them their failure signal.
 * <p>
 * {@code ToolExecutionService.executeToolWrapped} catches every exception and
 * <em>returns</em> {@code "Error executing tool: …"}. These tests stub it the
 * way the real service behaves (rather than as a rethrowing pass-through, which
 * is what the other {@code McpCallsTask} tests do) and assert that a failing
 * MCP tool still retries, still writes the {@code <name>Error} memory entry,
 * still runs {@code postResponse} with HTTP 500, still honours
 * {@code continueOnError} — and is never stored as a successful response.
 * <p>
 * The {@code configure()} tests cover the other half: a config already stored
 * in MongoDB that violates the newer transport/URL rules must still load,
 * because a throw there kills every other step of the agent too.
 */
@DisplayName("McpCallsTask — failure signal through the metering wrapper")
class McpCallsTaskFailurePathTest {

    private static final String RESPONSE_OBJECT = "transferResponse";
    private static final String ERROR_OBJECT = "transferResponseError";

    @Mock
    private IResourceClientLibrary resourceClientLibrary;
    @Mock
    private IMemoryItemConverter memoryItemConverter;
    @Mock
    private IJsonSerialization jsonSerialization;
    @Mock
    private McpToolProviderManager mcpToolProviderManager;
    @Mock
    private PrePostUtils prePostUtils;
    @Mock
    private ToolExecutionService toolExecutionService;
    @Mock
    private IConversationMemory memory;
    @Mock
    private IWritableConversationStep currentStep;
    @Mock
    private ToolExecutor executor;

    private McpCallsTask task;
    private AutoCloseable mocks;

    @BeforeEach
    void setUp() {
        mocks = openMocks(this);
        task = new McpCallsTask(resourceClientLibrary, memoryItemConverter, jsonSerialization,
                mcpToolProviderManager, prePostUtils, toolExecutionService);
        task.defaultRateLimit = 100;

        IData<List<String>> actions = new Data<>("actions", List.of("do_transfer"));
        lenient().when(memory.getCurrentStep()).thenReturn(currentStep);
        lenient().when(currentStep.<List<String>>getLatestData("actions")).thenReturn(actions);
        lenient().when(memory.getConversationId()).thenReturn("conv-1");
        lenient().when(memoryItemConverter.convert(memory)).thenReturn(new HashMap<>());

        var specs = List.of(ToolSpecification.builder().name("wire_transfer").build());
        var toolsResult = new McpToolProviderManager.McpToolsResult(specs, Map.of("wire_transfer", executor));
        lenient().when(mcpToolProviderManager.discoverTools(anyList())).thenReturn(toolsResult);
    }

    @AfterEach
    void tearDown() throws Exception {
        mocks.close();
    }

    // ─── helpers ─────────────────────────────────────────────────────────

    private McpCallsConfiguration configWithOneCall(Boolean continueOnError, int maxAttempts) {
        var retry = new RetryConfiguration();
        retry.setMaxAttempts(maxAttempts);
        retry.setBackoffDelayMs(1L);
        retry.setMaxBackoffDelayMs(1L);

        var call = new McpCall();
        call.setName("transfer");
        call.setToolName("wire_transfer");
        call.setActions(List.of("do_transfer"));
        call.setSaveResponse(true);
        call.setResponseObjectName(RESPONSE_OBJECT);
        call.setPostResponse(new PostResponse());
        call.setContinueOnError(continueOnError);
        call.setRetry(retry);

        var config = new McpCallsConfiguration();
        config.setMcpServerUrl("http://mcp.example.com/mcp");
        config.setMcpCalls(List.of(call));
        return config;
    }

    /**
     * Stub {@code executeToolWrapped} exactly as the real service behaves: run the
     * supplier, and on any exception swallow it and return the error sentinel.
     */
    @SuppressWarnings("unchecked")
    private void meteringSwallowsExceptions() {
        lenient().when(toolExecutionService.executeToolWrapped(anyString(), any(), any(), any(), any(),
                anyBoolean(), anyBoolean(), anyBoolean(), anyInt()))
                .thenAnswer(invocation -> {
                    Supplier<String> supplier = invocation.getArgument(4);
                    try {
                        return supplier.get();
                    } catch (RuntimeException e) {
                        return "Error executing tool: " + e.getMessage();
                    }
                });
    }

    /** Stub a wrapper that returns without ever dispatching to the executor. */
    private void meteringShortCircuitsWith(String returned) {
        lenient().when(toolExecutionService.executeToolWrapped(anyString(), any(), any(), any(), any(),
                anyBoolean(), anyBoolean(), anyBoolean(), anyInt()))
                .thenReturn(returned);
    }

    private String capturedMemoryEntry(String objectName) {
        ArgumentCaptor<Object> value = ArgumentCaptor.forClass(Object.class);
        verify(prePostUtils).createMemoryEntry(eq(currentStep), value.capture(), eq(objectName), eq("mcpCalls"));
        return String.valueOf(value.getValue());
    }

    // ─── tool failures ───────────────────────────────────────────────────

    @Nested
    @DisplayName("a failing MCP tool")
    class FailingTool {

        @Test
        @DisplayName("non-retryable tool error → error memory entry, postResponse 500, exception propagates")
        void toolErrorIsAFailureNotAResponse() throws Exception {
            meteringSwallowsExceptions();
            when(executor.execute(any(), any())).thenThrow(new RuntimeException("insufficient funds"));
            var config = configWithOneCall(false, 3);

            var thrown = assertThrows(LifecycleException.class, () -> task.execute(memory, config));
            assertTrue(thrown.getMessage().contains("insufficient funds"), thrown.getMessage());

            // the error text lands under <responseObjectName>Error, never as the response
            assertTrue(capturedMemoryEntry(ERROR_OBJECT).contains("insufficient funds"));
            verify(prePostUtils, never()).createMemoryEntry(any(), any(), eq(RESPONSE_OBJECT), any());

            // the error branch of postResponse ran, the success branch did not
            verify(prePostUtils).runPostResponse(eq(memory), any(), anyMap(), eq(500), eq(true));
            verify(prePostUtils, never()).runPostResponse(any(), any(), anyMap(), eq(200), eq(false));

            // non-retryable → exactly one attempt
            verify(executor, times(1)).execute(any(), any());
        }

        @Test
        @DisplayName("retryable transport error → retried up to maxAttempts")
        void transportErrorIsRetried() throws Exception {
            meteringSwallowsExceptions();
            when(executor.execute(any(), any())).thenThrow(new RuntimeException("Connection refused"));
            var config = configWithOneCall(false, 3);

            assertThrows(LifecycleException.class, () -> task.execute(memory, config));

            verify(executor, times(3)).execute(any(), any());
            verify(prePostUtils).createMemoryEntry(eq(currentStep), any(), eq(ERROR_OBJECT), eq("mcpCalls"));
            verify(prePostUtils, never()).createMemoryEntry(any(), any(), eq(RESPONSE_OBJECT), any());
        }

        @Test
        @DisplayName("continueOnError=true → turn continues but the failure is still recorded as an error")
        void continueOnErrorKeepsTheTurnAliveWithoutFakingSuccess() throws Exception {
            meteringSwallowsExceptions();
            when(executor.execute(any(), any())).thenThrow(new RuntimeException("insufficient funds"));
            var config = configWithOneCall(true, 3);

            task.execute(memory, config);

            assertTrue(capturedMemoryEntry(ERROR_OBJECT).contains("insufficient funds"));
            verify(prePostUtils, never()).createMemoryEntry(any(), any(), eq(RESPONSE_OBJECT), any());
            verify(prePostUtils).runPostResponse(eq(memory), any(), anyMap(), eq(500), eq(true));
        }

        @Test
        @DisplayName("rate-limit rejection → failure path, not a stored response")
        void rateLimitRejectionIsAFailure() throws Exception {
            meteringShortCircuitsWith("Error: Rate limit exceeded for tool: wire_transfer");
            var config = configWithOneCall(false, 1);

            assertThrows(LifecycleException.class, () -> task.execute(memory, config));

            verify(executor, never()).execute(any(), any());
            String recorded = capturedMemoryEntry(ERROR_OBJECT);
            assertFalse(recorded.startsWith("Error: Rate limit exceeded"),
                    "the sentinel must be reported as a failure, not echoed as a response: " + recorded);
            verify(prePostUtils, never()).createMemoryEntry(any(), any(), eq(RESPONSE_OBJECT), any());
            verify(prePostUtils).runPostResponse(eq(memory), any(), anyMap(), eq(500), eq(true));
        }
    }

    // ─── successful calls keep working ───────────────────────────────────

    @Nested
    @DisplayName("a successful MCP tool")
    class SuccessfulTool {

        @Test
        @DisplayName("result is stored and the metering pipeline is still applied")
        void successStillMetered() throws Exception {
            meteringSwallowsExceptions();
            when(executor.execute(any(), any())).thenReturn("{\"status\":\"sent\"}");
            when(jsonSerialization.deserialize(anyString(), eq(Object.class))).thenReturn(Map.of("status", "sent"));
            var config = configWithOneCall(false, 3);

            task.execute(memory, config);

            verify(prePostUtils).createMemoryEntry(eq(currentStep), eq(Map.of("status", "sent")), eq(RESPONSE_OBJECT), eq("mcpCalls"));
            verify(prePostUtils).runPostResponse(eq(memory), any(), anyMap(), eq(200), eq(false));
            verify(prePostUtils, never()).createMemoryEntry(any(), any(), eq(ERROR_OBJECT), any());
            // rate limiting on, caching off, cost tracking on — with the configured limit
            verify(toolExecutionService).executeToolWrapped(eq("wire_transfer"), anyString(), isNull(), eq("conv-1"),
                    any(), eq(true), eq(false), eq(true), eq(100));
        }

        @Test
        @DisplayName("a wrapper short-circuit that is not an error sentinel (cache hit) is returned as the result")
        void nonErrorShortCircuitIsAResult() throws Exception {
            meteringShortCircuitsWith("{\"status\":\"cached\"}");
            when(jsonSerialization.deserialize(anyString(), eq(Object.class))).thenReturn(Map.of("status", "cached"));
            var config = configWithOneCall(false, 3);

            task.execute(memory, config);

            verify(executor, never()).execute(any(), any());
            verify(prePostUtils).createMemoryEntry(eq(currentStep), eq(Map.of("status", "cached")), eq(RESPONSE_OBJECT), eq("mcpCalls"));
            verify(prePostUtils, never()).createMemoryEntry(any(), any(), eq(ERROR_OBJECT), any());
        }
    }

    // ─── stored-config backward compatibility ────────────────────────────

    @Nested
    @DisplayName("configure() on an already-stored config")
    class ConfigureBackwardCompatibility {

        private Object configureWith(McpCallsConfiguration stored) throws Exception {
            when(resourceClientLibrary.getResource(any(), eq(McpCallsConfiguration.class))).thenReturn(stored);
            return task.configure(Map.of("uri", "eddi://ai.labs.mcpcalls/mcpcallsstore/mcpcalls/abc?version=1"), Map.of());
        }

        @Test
        @DisplayName("a legacy transport ('sse') still loads the workflow instead of bricking the agent")
        void legacyTransportStillLoads() throws Exception {
            var stored = new McpCallsConfiguration();
            stored.setMcpServerUrl("http://mcp.example.com/mcp");
            stored.setTransport("sse");

            assertSame(stored, configureWith(stored));
        }

        @Test
        @DisplayName("a blank server URL still loads the workflow (the MCP step alone degrades)")
        void blankServerUrlStillLoads() throws Exception {
            var stored = new McpCallsConfiguration();
            stored.setMcpServerUrl("   ");

            assertSame(stored, configureWith(stored));
        }

        @Test
        @DisplayName("a non-http server URL still loads the workflow")
        void nonHttpServerUrlStillLoads() throws Exception {
            var stored = new McpCallsConfiguration();
            stored.setMcpServerUrl("file:///etc/passwd");

            assertSame(stored, configureWith(stored));
        }

        @Test
        @DisplayName("a valid config is returned unchanged")
        void validConfigIsReturned() throws Exception {
            var stored = new McpCallsConfiguration();
            stored.setMcpServerUrl("https://mcp.example.com/mcp");
            stored.setTransport("streamable-http");

            assertSame(stored, configureWith(stored));
            assertEquals("streamable-http", stored.getTransport());
        }
    }
}
