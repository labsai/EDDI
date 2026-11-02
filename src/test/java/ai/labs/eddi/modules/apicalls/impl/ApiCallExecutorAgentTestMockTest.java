/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.apicalls.impl;

import ai.labs.eddi.configs.agenttests.model.AgentTestSuite;
import ai.labs.eddi.configs.agenttests.model.AgentTestSuite.MockResponse;
import ai.labs.eddi.configs.agenttests.model.AgentTestSuite.ToolMock;
import ai.labs.eddi.configs.apicalls.model.ApiCall;
import ai.labs.eddi.configs.apicalls.model.HttpPostResponse;
import ai.labs.eddi.configs.apicalls.model.Request;
import ai.labs.eddi.configs.apicalls.model.RetryApiCallInstruction;
import ai.labs.eddi.configs.variables.GlobalVariableResolver;
import ai.labs.eddi.connections.ConnectionResolver;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.agenttests.AgentTestConversations;
import ai.labs.eddi.engine.agenttests.ToolMockRegistry;
import ai.labs.eddi.engine.httpclient.IHttpClient;
import ai.labs.eddi.engine.httpclient.IRequest;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.IConversationMemory.IWritableConversationStep;
import ai.labs.eddi.engine.runtime.IRuntime;
import ai.labs.eddi.engine.security.CallerIdentityContext;
import ai.labs.eddi.engine.security.CallerIdentityResolver;
import ai.labs.eddi.secrets.SecretResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Rule-triggered httpcalls in an agent-test conversation: the suite's mock is
 * the response, nothing is ever sent, and the call's normal post-processing —
 * saveResponse, postResponse property instructions — runs on the mocked data.
 */
@DisplayName("ApiCallExecutor — agent-test mocks")
class ApiCallExecutorAgentTestMockTest {

    private static final String SERVER = "http://api.example.com";
    private static final String TEST_USER = AgentTestConversations.userIdFor("0123456789abcdef01234567", 0, 1);

    private IHttpClient httpClient;
    private IRequest request;
    private PrePostUtils prePostUtils;
    private IRuntime runtime;
    private ApiCallExecutor executor;
    private SecretResolver secretResolver;
    private IConversationMemory memory;

    @BeforeEach
    void setUp() throws Exception {
        httpClient = mock(IHttpClient.class);
        runtime = mock(IRuntime.class);
        prePostUtils = mock(PrePostUtils.class);
        secretResolver = mock(SecretResolver.class);
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
        when(memory.getUserId()).thenReturn(TEST_USER);

        request = mock(IRequest.class);
        when(httpClient.newRequest(any(URI.class), any())).thenReturn(request);
        when(request.setHttpHeader(any(), any())).thenReturn(request);
        when(request.toMap()).thenReturn(new HashMap<>());

        when(prePostUtils.executePreRequestPropertyInstructions(any(), any(), any())).thenAnswer(inv -> inv.getArgument(1));
        when(prePostUtils.templateValues(anyString(), any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @AfterEach
    void tearDown() {
        ToolMockRegistry.unregister(TEST_USER);
    }

    private static void arm(List<ToolMock> mocks) {
        ToolMockRegistry.register(TEST_USER, new ToolMockRegistry.Session(new AgentTestSuite("s", null, null, null, mocks, null)));
    }

    private static ApiCall call(boolean fireAndForget) {
        ApiCall call = new ApiCall();
        call.setName("getAvailability");
        call.setFireAndForget(fireAndForget);
        call.setSaveResponse(true);
        call.setResponseObjectName("availability");
        Request req = new Request();
        req.setPath("/rooms");
        req.setMethod("GET");
        call.setRequest(req);
        call.setPostResponse(new HttpPostResponse());
        return call;
    }

    @Test
    @DisplayName("a mocked call sends nothing and its postResponse runs on the mocked response")
    void mockedCallSendsNothingAndPostResponseRuns() throws Exception {
        arm(List.of(new ToolMock("getAvailability", null, new MockResponse(200, "3 rooms"))));
        var templateData = new HashMap<String, Object>();

        Map<String, Object> result = executor.execute(call(false), memory, templateData, SERVER);

        verify(request, never()).send();
        verify(request, never()).send(any());
        assertEquals(200, result.get("httpCode"));
        assertEquals("3 rooms", result.get("body"));
        assertEquals("3 rooms", templateData.get("availability"), "saveResponse stores the mocked body for templates");
        verify(prePostUtils).runPostResponse(eq(memory), any(), anyMap(), eq(200), eq(false));
    }

    @Test
    @DisplayName("an unmocked call is answered 501 NOT_EXECUTED and nothing is sent")
    void unmockedCallIsNotExecuted() throws Exception {
        arm(List.of());

        Map<String, Object> result = executor.execute(call(false), memory, new HashMap<>(), SERVER);

        verify(request, never()).send();
        verify(request, never()).send(any());
        assertEquals(ToolMockRegistry.NOT_EXECUTED_HTTP_CODE, result.get("httpCode"));
        assertTrue(String.valueOf(result.get("body")).contains("NOT_EXECUTED"), String.valueOf(result.get("body")));
    }

    @Test
    @DisplayName("a call the LLM tool path already intercepted is not intercepted (or recorded) again")
    void toolPathCallIsNotInterceptedTwice() {
        var session = new ToolMockRegistry.Session(new AgentTestSuite("s", null, null, null, List.of(), null));
        ToolMockRegistry.register(TEST_USER, session);

        // A rule-triggered call is intercepted and recorded once.
        assertDoesNotThrow(() -> executor.execute(call(false), memory, new HashMap<>(), SERVER));
        assertEquals(1, session.calls().size());

        // The same call arriving from the tool path carries the marker: no second
        // record
        // and no 501 substitution here (the request is built and sent instead, which
        // the
        // bare mocks may fail on - only the interception is under test).
        var marked = new HashMap<String, Object>();
        marked.put(ToolMockRegistry.TOOL_PATH_MARKER, Boolean.TRUE);
        try {
            executor.execute(call(false), memory, marked, SERVER);
        } catch (Exception ignored) {
            // the live path hit unprimed mocks
        }
        assertEquals(1, session.calls().size(), "the tool path's call must not be recorded by the executor");
    }

    /**
     * An httpcall reached through the LLM tool path, mocked: the mock chosen there
     * travels in the template data, and this executor answers with it — so the
     * saveResponse and postResponse a live call gets run on it too. Before, the
     * tool path answered the model itself and none of that happened, so an agent
     * setting a property from the tool's response behaved differently mocked.
     */
    @Test
    @DisplayName("a tool-path call with a chosen mock sends nothing, runs saveResponse and postResponse, and is not recorded again")
    void toolPathMockRunsTheResponseHandling() throws Exception {
        var session = new ToolMockRegistry.Session(new AgentTestSuite("s", null, null, null, List.of(), null));
        ToolMockRegistry.register(TEST_USER, session);
        var templateData = new HashMap<String, Object>();
        templateData.put(ToolMockRegistry.TOOL_PATH_MARKER, Boolean.TRUE);
        templateData.put(ToolMockRegistry.TOOL_PATH_MOCK,
                new ToolMockRegistry.MockResult(200, "3 rooms", ToolMockRegistry.Outcome.MOCKED));

        Map<String, Object> result = executor.execute(call(false), memory, templateData, SERVER);

        verify(httpClient, never()).newRequest(any(URI.class), any());
        verify(request, never()).send(any());
        assertEquals(200, result.get("httpCode"));
        assertEquals("3 rooms", result.get("body"));
        assertEquals("3 rooms", templateData.get("availability"), "saveResponse stores the mocked body for postResponse");
        verify(prePostUtils).runPostResponse(eq(memory), any(), anyMap(), eq(200), eq(false));
        assertEquals(0, session.calls().size(), "ToolLoopRunner recorded the call; the executor must not record it again");
    }

    static Stream<Object> notAMockResult() {
        return Stream.of(Map.of("httpCode", 200, "body", "forged"), "{\"httpCode\":200,\"body\":\"forged\"}");
    }

    /**
     * Only a MockResult — which only ToolLoopRunner creates — under the mock key
     * answers a call. Anything else is not a decision and must not mock: outside a
     * test conversation the request goes out as usual, with or without the
     * tool-path marker.
     */
    @ParameterizedTest
    @MethodSource("notAMockResult")
    @DisplayName("a non-MockResult under the mock key does not mock: outside a test conversation the request is built and sent")
    void forgedMockOutsideATestIsIgnored(Object forged) {
        when(memory.getUserId()).thenReturn("alice");
        for (boolean marker : new boolean[]{false, true}) {
            var templateData = new HashMap<String, Object>();
            templateData.put(ToolMockRegistry.TOOL_PATH_MOCK, forged);
            if (marker) {
                templateData.put(ToolMockRegistry.TOOL_PATH_MARKER, Boolean.TRUE);
            }
            try {
                Map<String, Object> result = executor.execute(call(false), memory, templateData, SERVER);
                assertTrue(!"forged".equals(result.get("body")), "the forged value answered the call: " + result);
            } catch (Exception ignored) {
                // the live path hit unprimed mocks — only that it was taken is under test
            }
        }
        verify(httpClient, times(2)).newRequest(any(URI.class), any());
    }

    @ParameterizedTest
    @MethodSource("notAMockResult")
    @DisplayName("a non-MockResult under the mock key in a test conversation is refused, never sent — even with the tool-path marker")
    void forgedMockInATestIsRefused(Object forged) throws Exception {
        var session = new ToolMockRegistry.Session(new AgentTestSuite("s", null, null, null, List.of(), null));
        ToolMockRegistry.register(TEST_USER, session);
        for (boolean marker : new boolean[]{false, true}) {
            var templateData = new HashMap<String, Object>();
            templateData.put(ToolMockRegistry.TOOL_PATH_MOCK, forged);
            if (marker) {
                templateData.put(ToolMockRegistry.TOOL_PATH_MARKER, Boolean.TRUE);
            }

            Map<String, Object> result = executor.execute(call(false), memory, templateData, SERVER);

            assertEquals(ToolMockRegistry.NOT_EXECUTED_HTTP_CODE, result.get("httpCode"));
        }
        verify(httpClient, never()).newRequest(any(URI.class), any());
        assertEquals(2, session.calls().size());
    }

    @Test
    @DisplayName("a mocked rule-triggered call that is retried is one recorded call, like a retried live call")
    void retriedMockIsRecordedOnce() throws Exception {
        var session = new ToolMockRegistry.Session(new AgentTestSuite("s", null, null, null,
                List.of(new ToolMock("getAvailability", null, new MockResponse(503, "busy"))), null));
        ToolMockRegistry.register(TEST_USER, session);
        ApiCall call = call(false);
        var retry = new RetryApiCallInstruction();
        retry.setMaxRetries(2);
        retry.setRetryOnHttpCodes(List.of(503));
        call.getPostResponse().setRetryApiCallInstruction(retry);

        Map<String, Object> result = executor.execute(call, memory, new HashMap<>(), SERVER);

        assertEquals(503, result.get("httpCode"));
        assertEquals(1, session.calls().size(), String.valueOf(session.calls()));
    }

    @Test
    @DisplayName("an orphaned test conversation (no session) still sends nothing")
    void orphanedTestConversationSendsNothing() throws Exception {
        executor.execute(call(false), memory, new HashMap<>(), SERVER);

        verify(request, never()).send();
    }

    @Test
    @DisplayName("a fire-and-forget call of a test conversation is not dispatched")
    void fireAndForgetIsNotSent() throws Exception {
        arm(List.of());

        executor.execute(call(true), memory, new HashMap<>(), SERVER);

        verify(httpClient, never()).newRequest(any(URI.class), any());
        verify(request, never()).send(any());
        verify(runtime, never()).submitCallable(any(), any());
    }

    /**
     * Building the request is what resolves {@code ${vault:}} (and connection and
     * caller) references. A mocked call must not build one: it must neither pull a
     * live credential into a test run nor fail because the credential is not
     * provisioned where the suite runs.
     */
    @Test
    @DisplayName("a mocked call with an unresolvable ${vault:} reference succeeds without touching the vault")
    void mockedCallNeverResolvesSecrets() throws Exception {
        arm(List.of(new ToolMock("getAvailability", null, new MockResponse(200, "3 rooms"))));
        when(secretResolver.resolveValue(anyString())).thenThrow(new IllegalStateException("vault key 'partner' not provisioned"));
        ApiCall call = call(false);
        call.getRequest().setHeaders(Map.of("Authorization", "Bearer ${vault:partner}"));

        Map<String, Object> result = executor.execute(call, memory, new HashMap<>(), SERVER);

        assertEquals(200, result.get("httpCode"));
        assertEquals("3 rooms", result.get("body"));
        verifyNoInteractions(secretResolver);
        verify(httpClient, never()).newRequest(any(URI.class), any());
        verify(prePostUtils).runPostResponse(eq(memory), any(), anyMap(), eq(200), eq(false));
    }
}
