/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.configs.agents.IAgentStore;
import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.configs.apicalls.model.ApiCall;
import ai.labs.eddi.configs.apicalls.model.ApiCallsConfiguration;
import ai.labs.eddi.configs.apicalls.model.Request;
import ai.labs.eddi.configs.workflows.IWorkflowStore;
import ai.labs.eddi.configs.workflows.model.WorkflowConfiguration;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.datastore.serialization.JsonSerialization;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.IMemoryItemConverter;
import ai.labs.eddi.engine.runtime.client.configuration.IResourceClientLibrary;
import ai.labs.eddi.modules.apicalls.impl.IApiCallExecutor;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.service.tool.ToolExecutor;
import io.netty.channel.ConnectTimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.channels.UnresolvedAddressException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * What a failed httpcall tool tells the model.
 * <p>
 * The defect: a connection failure surfaced as the bare {@code "Connection
 * refused"}, which a model reads as "the service is down" and reports as such.
 * An operator whose tools pointed at an address unreachable from inside the
 * container told its admin the platform's internal services were broken, and
 * the admin spent the afternoon on EDDI's health instead of on the one
 * misconfigured field.
 *
 * @author ginccc
 */
@DisplayName("httpcall tool failure messages")
class HttpCallToolsProviderFailureMessageTest {

    private static final String BASE = "http://localhost:7080";

    private static ApiCall call(String method, String path) {
        var request = new Request();
        request.setMethod(method);
        request.setPath(path);
        var apiCall = new ApiCall();
        apiCall.setName("listAgentDescriptors");
        apiCall.setRequest(request);
        return apiCall;
    }

    private static String describe(Exception e) {
        return HttpCallToolsProvider.describeToolFailure(e, BASE, call("GET", "/agentstore/agents/descriptors"));
    }

    @Nested
    @DisplayName("a connection failure")
    class ConnectFailure {

        /**
         * The three things the message has to carry: the address that was tried, the
         * fact that this is a reachability problem rather than a fault in the target,
         * and the field to suspect.
         */
        @Test
        @DisplayName("names the URL it tried, and the base URL as the thing to suspect")
        void namesTheUrlAndTheBaseUrl() {
            String message = describe(new RuntimeException(new ConnectException("Connection refused")));
            assertTrue(message.contains("http://localhost:7080/agentstore/agents/descriptors"),
                    "the attempted URL must be in the message: " + message);
            assertTrue(message.contains("GET"), message);
            assertTrue(message.contains("base URL"), "the message must point at the configured base URL: " + message);
            assertTrue(message.contains("NOT a fault in the service behind it"),
                    "the message must contradict the 'the service is down' reading: " + message);
        }

        /**
         * The address must be the one the tool is CONFIGURED with, not the one the
         * browser used — saying "EDDI is unreachable" would repeat the original
         * misdiagnosis one level up.
         */
        @Test
        @DisplayName("says the address must be reachable by the server, not by a browser")
        void distinguishesServerFromBrowser() {
            String message = describe(new RuntimeException(new ConnectException("Connection refused")));
            assertTrue(message.contains("EDDI server itself can reach"), message);
            assertTrue(message.contains("not the address a browser uses"), message);
        }

        @Test
        @DisplayName("is found through a wrapped cause chain")
        void unwrapsCauses() {
            var deep = new IOException("request failed", new ExecutionException(new ConnectException("Connection refused")));
            assertTrue(describe(deep).contains("The connection was refused"), describe(deep));
        }

        @Test
        @DisplayName("distinguishes DNS, timeout, no-route and refusal")
        void distinguishesKinds() {
            assertTrue(describe(new RuntimeException(new UnknownHostException("eddi.internal")))
                    .contains("The host name could not be resolved"));
            assertTrue(describe(new RuntimeException(new UnresolvedAddressException()))
                    .contains("The host name could not be resolved"));
            assertTrue(describe(new RuntimeException(new NoRouteToHostException("no route")))
                    .contains("There is no route to the host"));
            assertTrue(describe(new RuntimeException(new ConnectException("Connection refused")))
                    .contains("The connection was refused"));
        }

        /**
         * Some clients flatten a connect failure into text with no typed cause left.
         * The message fallback is last precisely because it is the weaker signal, but
         * without it the common case of a wrapped-and-stringified refusal reads as an
         * ordinary error again.
         */
        @Test
        @DisplayName("falls back to the message text when the cause chain was flattened")
        void fallsBackToMessageText() {
            assertTrue(describe(new RuntimeException("Failed to connect: Connection refused: no further information"))
                    .contains("The connection was refused"));
        }

        @Test
        @DisplayName("a relative configured path is joined with a single slash")
        void joinsPathsCleanly() {
            String message = HttpCallToolsProvider.describeToolFailure(new ConnectException("Connection refused"), "http://eddi:7070",
                    call("POST", "agentstore/agents"));
            assertTrue(message.contains("http://eddi:7070/agentstore/agents"), message);
            assertFalse(message.contains("7070agentstore"), message);
        }

        @Test
        @DisplayName("an absolute configured path is reported as-is, not concatenated")
        void keepsAbsolutePaths() {
            String message = HttpCallToolsProvider.describeToolFailure(new ConnectException("Connection refused"), BASE,
                    call("GET", "https://other.example/thing"));
            assertTrue(message.contains("https://other.example/thing"), message);
            assertFalse(message.contains(BASE + "https://"), message);
        }

        @Test
        @DisplayName("a missing base URL is described rather than printed as null")
        void handlesAMissingBaseUrl() {
            String message = HttpCallToolsProvider.describeToolFailure(new ConnectException("Connection refused"), null,
                    call("GET", "/agentstore/agents"));
            assertTrue(message.contains("<not configured>"), message);
            assertFalse(message.contains("null/agentstore"), message);
        }
    }

    @Nested
    @DisplayName("everything else")
    class OtherFailures {

        /**
         * Only connect-class failures get the base-URL advice. A 400 from the API is a
         * real answer, and telling the model to suspect the base URL there would send
         * the next admin down the wrong path in the other direction.
         */
        @Test
        @DisplayName("an ordinary failure keeps its own message and gains no base-URL advice")
        void leavesOtherFailuresAlone() {
            String message = describe(new IllegalArgumentException("targetServerUrl cannot be null or empty"));
            assertEquals("targetServerUrl cannot be null or empty", message);
        }

        @Test
        @DisplayName("a null message does not produce the string 'null'")
        void handlesAMissingMessage() {
            assertEquals("Unknown error", describe(new IllegalStateException()));
        }

        /**
         * This string reaches the chat surface. A base URL that embeds credentials must
         * not travel with it.
         */
        /**
         * A PLAIN password — not secret-shaped. SecretRedactionFilter only recognises
         * shapes (sk-..., Bearer ..., key=value), so this is what actually proves the
         * userinfo is removed structurally. The sk-ant case below passed even when
         * userinfo went through verbatim.
         */
        @Test
        @DisplayName("userinfo in the base URL is dropped even when the password is not secret-shaped")
        void stripsPlainUserInfo() {
            String message = HttpCallToolsProvider.describeToolFailure(new ConnectException("Connection refused"),
                    "https://admin:hunter2@eddi.example", call("GET", "/agentstore/agents"));
            assertFalse(message.contains("hunter2"), message);
            assertFalse(message.contains("admin:"), message);
            assertTrue(message.contains("https://eddi.example/agentstore/agents"), message);
        }

        /**
         * An un-encoded {@code @} inside the password must not leave its tail behind.
         */
        @Test
        @DisplayName("userinfo with an @ inside the password is dropped whole")
        void stripsUserInfoContainingAt() {
            String message = HttpCallToolsProvider.describeToolFailure(new ConnectException("Connection refused"),
                    "https://admin:p@ssw0rd@eddi.example", call("GET", "/agentstore/agents"));
            assertFalse(message.contains("ssw0rd"), message);
            assertTrue(message.contains("https://eddi.example/agentstore/agents"), message);
        }

        @Test
        @DisplayName("userinfo is dropped from a non-connect failure's message too")
        void stripsUserInfoFromOtherMessages() {
            String message = describe(new IllegalArgumentException("bad target http://svc:hunter2@api.example.com/x"));
            assertFalse(message.contains("hunter2"), message);
            assertTrue(message.contains("http://api.example.com/x"), message);
        }

        /**
         * With SSRF protection on, loopback is refused before any connection. That is a
         * configuration problem with a specific remedy, not a network failure.
         */
        @Test
        @DisplayName("an SSRF refusal names the protection and the remedy")
        void explainsAnSsrfRefusal() {
            String message = describe(new IllegalArgumentException("Access to internal/local addresses is not allowed: 127.0.0.1"));
            assertTrue(message.contains("SSRF protection"), message);
            assertTrue(message.contains("eddi.self.base-url"), message);
            assertTrue(message.contains("http://localhost:7080/agentstore/agents/descriptors"), message);
            assertFalse(message.contains("network failure"), message);
        }

        /**
         * A READ timeout means the service accepted the connection and was slow — the
         * one case the "not a fault in the service" wording must never cover.
         */
        @Test
        @DisplayName("a read timeout is not dressed up as a connect failure")
        void readTimeoutIsNotAConnectFailure() {
            String message = describe(new RuntimeException("Read timed out", new SocketTimeoutException("Read timed out")));
            assertFalse(message.contains("NOT a fault in the service"), message);
        }

        /**
         * Netty's connect timeout extends ConnectException; it must read as a timeout,
         * not as a refusal.
         */
        @Test
        @DisplayName("Netty's connect timeout is a timeout, not a refusal")
        void nettyConnectTimeoutIsATimeout() {
            String message = describe(new RuntimeException(new ConnectTimeoutException("connection timed out: eddi/10.0.0.4:7070")));
            assertTrue(message.contains("The connection timed out"), message);
        }

        @Test
        @DisplayName("credentials embedded in the base URL are redacted, not echoed")
        void redactsCredentialsInTheBaseUrl() {
            String message = HttpCallToolsProvider.describeToolFailure(new ConnectException("Connection refused"),
                    "https://admin:sk-test-ZmFrZUtleUZvclRlc3RzT25see12345678@eddi.example", call("GET", "/agentstore/agents"));
            assertFalse(message.contains("sk-test-ZmFrZUtleUZvclRlc3RzT25see12345678"),
                    "a secret-shaped value in the base URL must not reach the model: " + message);
        }
    }

    /**
     * Through the REAL executor lambda that {@code discover} builds, not the helper
     * in isolation.
     * <p>
     * This exists because the helper-level tests above do <em>not</em> pin the
     * wiring: a mutation that reverts the catch clause to
     * {@code errorResult(e.getMessage())} leaves {@code describeToolFailure} intact
     * and every one of those tests still green. The assertion that carries the
     * weight is therefore this one — what a failing tool call actually hands back
     * to the model.
     */
    @Nested
    @DisplayName("through the real executor")
    class Wiring {

        private static final String AGENT_ID = "agent-1";
        private static final int AGENT_VERSION = 1;
        private static final String WORKFLOW_URI = "eddi://ai.labs.workflow/workflowstore/workflows/wf-1?version=1";
        private static final String HTTPCALLS_URI = "eddi://ai.labs.httpcalls/apicallstore/apicalls/api-1?version=1";
        private static final String TOOL_NAME = "listAgentDescriptors";

        private IConversationMemory memory;
        private IAgentStore agentStore;
        private IWorkflowStore workflowStore;
        private IResourceClientLibrary resourceClientLibrary;
        private IApiCallExecutor apiCallExecutor;
        private IMemoryItemConverter memoryItemConverter;
        private IJsonSerialization jsonSerialization;

        @BeforeEach
        void setUp() {
            // discover() goes through WorkflowTraversal's process-wide cache, keyed on
            // (agent, version, step type) — a leaked entry answers another test's config.
            WorkflowTraversal.clearCache();
            memory = mock(IConversationMemory.class);
            when(memory.getAgentId()).thenReturn(AGENT_ID);
            when(memory.getAgentVersion()).thenReturn(AGENT_VERSION);
            agentStore = mock(IAgentStore.class);
            workflowStore = mock(IWorkflowStore.class);
            resourceClientLibrary = mock(IResourceClientLibrary.class);
            apiCallExecutor = mock(IApiCallExecutor.class);
            memoryItemConverter = mock(IMemoryItemConverter.class);
            when(memoryItemConverter.convert(any())).thenReturn(new HashMap<>());
            jsonSerialization = new JsonSerialization(new ObjectMapper());
        }

        @AfterEach
        void tearDown() {
            WorkflowTraversal.clearCache();
        }

        private ToolExecutor executorWithTargetServer(String targetServerUrl) throws Exception {
            var agentConfig = new AgentConfiguration();
            agentConfig.setWorkflows(List.of(URI.create(WORKFLOW_URI)));
            when(agentStore.read(AGENT_ID, AGENT_VERSION)).thenReturn(agentConfig);

            var step = new WorkflowConfiguration.WorkflowStep();
            step.setType(URI.create("eddi://ai.labs.httpcalls"));
            step.setConfig(Map.of("uri", HTTPCALLS_URI));
            var workflowConfig = new WorkflowConfiguration();
            workflowConfig.setWorkflowSteps(List.of(step));
            when(workflowStore.read("wf-1", 1)).thenReturn(workflowConfig);

            var httpCallsConfig = new ApiCallsConfiguration();
            httpCallsConfig.setTargetServerUrl(targetServerUrl);
            httpCallsConfig.setHttpCalls(List.of(call("GET", "/agentstore/agents/descriptors")));
            when(resourceClientLibrary.getResource(URI.create(HTTPCALLS_URI), ApiCallsConfiguration.class))
                    .thenReturn(httpCallsConfig);

            var provider = new HttpCallToolsProvider(agentStore, workflowStore, resourceClientLibrary,
                    apiCallExecutor, jsonSerialization, memoryItemConverter);
            ToolExecutor executor = provider.discover(memory).executors().get(TOOL_NAME);
            assertNotNull(executor, "discovery did not produce the tool under test");
            return executor;
        }

        private static ToolExecutionRequest invocation() {
            return ToolExecutionRequest.builder().id("call-1").name(TOOL_NAME).arguments("{}").build();
        }

        /**
         * The exact staging failure: the tools were provisioned with the browser's
         * tunnel address and nothing inside the container answers there.
         */
        @Test
        @DisplayName("a refused connection comes back naming the address and clearing the platform")
        void namesTheAddressThroughTheExecutor() throws Exception {
            when(apiCallExecutor.execute(any(), any(), any(), anyString()))
                    .thenThrow(new RuntimeException("request failed", new ConnectException("Connection refused")));

            String result = executorWithTargetServer("http://localhost:7080").execute(invocation(), "memory-1");

            assertTrue(result.contains("http://localhost:7080/agentstore/agents/descriptors"),
                    "the tool result must name the address that was tried: " + result);
            assertTrue(result.contains("NOT a fault in the service behind it"),
                    "the tool result must contradict the 'the platform is down' reading: " + result);
            assertTrue(result.contains("base URL"), result);
            // Still the one JSON-object shape a tool result takes.
            assertTrue(result.startsWith("{\"error\": \""), result);
        }

        /** Any other failure keeps its own message, through the same path. */
        @Test
        @DisplayName("a non-transport failure is not dressed up as a base-URL problem")
        void leavesOtherFailuresAloneThroughTheExecutor() throws Exception {
            when(apiCallExecutor.execute(any(), any(), any(), anyString()))
                    .thenThrow(new IllegalStateException("the API answered 400 Bad Request"));

            String result = executorWithTargetServer("http://localhost:7080").execute(invocation(), "memory-1");

            assertTrue(result.contains("the API answered 400 Bad Request"), result);
            assertFalse(result.contains("base URL"), "a 400 must not point the model at the base URL: " + result);
        }
    }
}
