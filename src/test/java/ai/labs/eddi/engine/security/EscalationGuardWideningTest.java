/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.security;

import ai.labs.eddi.engine.hitl.tools.ApprovedCallScope;
import ai.labs.eddi.modules.llm.tools.ToolExecutionService;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;

import java.net.URI;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The escalation guard after the security review: who may use EDDI or its
 * credentials (finding 3) is guarded without exception, and configuration
 * writes (finding 2) are guarded unless a person approved the call.
 */
@DisplayName("EscalationGuard — sharing, secrets, connections, and approved configuration writes")
class EscalationGuardWideningTest {

    private static Optional<String> tainted(Callable<Optional<String>> check) throws Exception {
        return TurnOrigin.callWith(TurnOrigin.EMAIL, check);
    }

    @Nested
    @DisplayName("finding 3 — endpoints that widen who can use EDDI or its credentials")
    class Widening {

        @ParameterizedTest(name = "{0} {1}")
        @CsvSource({
                "POST, /descriptorstore/descriptors/aabbccddee1122334455aa01/shares",
                "POST, /descriptorstore/descriptors/aabbccddee1122334455aa01/shares/requests",
                "DELETE, /descriptorstore/descriptors/aabbccddee1122334455aa01/shares",
                "PUT, /descriptorstore/descriptors/aabbccddee1122334455aa01/shares/visibility",
                "PUT, /descriptorstore/descriptors/aabbccddee1122334455aa01/shares/space",
                "PUT, /descriptorstore/descriptors/aabbccddee1122334455aa01/shares/owner",
                "PUT, /secretstore/secrets/default/openai-key",
                "PUT, /secretstore/secrets/default/openai-key/grant",
                "POST, /secretstore/secrets/default/openai-key/grant/agents/agent-1",
                "DELETE, /secretstore/secrets/default/openai-key",
                "POST, /secretstore/secrets/default/rotate-dek",
                "PUT, /spacestore/secrets/openai-key",
                "POST, /connections/github/authorize",
                "DELETE, /connections/github/grant",
                "PUT, /connectionstore/connections/aabbccddee1122334455aa01",
                "POST, /connectionstore/connections",
                "PUT, /connectionstore/settings",
                "PUT, /workspaces/settings",
                "PUT, /eddi/api/secretstore/secrets/default/key/grant"})
        @DisplayName("a tainted write is refused, approved or not, and the refusal is the 'never' one")
        void refused(String method, String path) throws Exception {
            URI uri = URI.create("https://eddi.example.com" + path);
            assertTrue(EscalationGuard.isGuardedRequest(method, uri));
            assertEquals(Optional.of(EscalationGuard.REFUSAL), tainted(() -> EscalationGuard.refuseRequest(method, uri, true)));
            assertEquals(Optional.of(EscalationGuard.REFUSAL), EscalationGuard.refuseInbound(method, path, true, true));
        }

        @Test
        @DisplayName("reading them is not refused, nor writing them from an untainted turn")
        void readsAndUntaintedPass() throws Exception {
            URI shares = URI.create("https://eddi.example.com/descriptorstore/descriptors/aabbccddee1122334455aa01/shares");
            assertTrue(tainted(() -> EscalationGuard.refuseRequest("GET", shares)).isEmpty());
            assertTrue(EscalationGuard.refuseRequest("POST", shares).isEmpty());
            assertTrue(EscalationGuard.refuseInbound("GET", "/secretstore/secrets/default", true, false).isEmpty());
        }
    }

    @Nested
    @DisplayName("finding 2 — configuration writes need a person's approval")
    class Configuration {

        @ParameterizedTest(name = "{0} {1}")
        @CsvSource({
                "PUT, /llmstore/llms/aabbccddee1122334455aa01",
                "PUT, /langchainstore/langchains/aabbccddee1122334455aa01",
                "PUT, /apicallstore/apicalls/aabbccddee1122334455aa01",
                "PUT, /httpcallsstore/httpcalls/aabbccddee1122334455aa01",
                "POST, /agentstore/agents",
                "PUT, /botstore/bots/aabbccddee1122334455aa01",
                "PATCH, /workflowstore/workflows/aabbccddee1122334455aa01",
                "PUT, /packagestore/packages/aabbccddee1122334455aa01",
                "PUT, /rulestore/rulesets/aabbccddee1122334455aa01",
                "PUT, /behaviorstore/behaviorsets/aabbccddee1122334455aa01",
                "PUT, /outputstore/outputsets/aabbccddee1122334455aa01",
                "PUT, /propertysetterstore/propertysetters/aabbccddee1122334455aa01",
                "PUT, /mcpcallsstore/mcpcalls/aabbccddee1122334455aa01",
                "POST, /ragstore/rags/aabbccddee1122334455aa01/ingest",
                "PUT, /snippetstore/snippets/aabbccddee1122334455aa01",
                "PUT, /variablestore/variables/greeting",
                "PUT, /spacestore/variables/greeting",
                "PUT, /dictionarystore/dictionaries/aabbccddee1122334455aa01",
                "PUT, /regulardictionarystore/regulardictionaries/aabbccddee1122334455aa01",
                "POST, /AgentTriggerStore/agenttriggers",
                "PUT, /bottriggerstore/bottriggers/support",
                "POST, /groupstore/groups",
                "PUT, /groupstore/groups/aabbccddee1122334455aa01",
                "POST, /groupstore/templates/aabbccddee1122334455aa01",
                "POST, /administration/production/deploy/aabbccddee1122334455aa01",
                "POST, /administration/unrestricted/undeploy/aabbccddee1122334455aa01",
                "POST, /administration/agents/setup",
                "POST, /backup/import"})
        @DisplayName("a tainted write is refused with the approval hint, and allowed when approved")
        void needsApproval(String method, String path) throws Exception {
            URI uri = URI.create("https://eddi.example.com" + path);
            assertFalse(EscalationGuard.isGuardedRequest(method, uri), "not a 'never' path");
            assertEquals(Optional.of(EscalationGuard.CONFIG_REFUSAL), tainted(() -> EscalationGuard.refuseRequest(method, uri, false)));
            assertTrue(tainted(() -> EscalationGuard.refuseRequest(method, uri, true)).isEmpty());
            assertEquals(Optional.of(EscalationGuard.CONFIG_REFUSAL), EscalationGuard.refuseInbound(method, path, true, false));
            assertTrue(EscalationGuard.refuseInbound(method, path, true, true).isEmpty());
            assertTrue(EscalationGuard.refuseInbound(method, path, false, false).isEmpty(), "an untainted request is untouched");
        }

        @ParameterizedTest(name = "{0} {1}")
        @CsvSource({
                "POST, /groupstore/groups/aabbccddee1122334455aa01/workspace/backlog",
                "PUT, /groupstore/groups/aabbccddee1122334455aa01/workspace/backlog/task-1",
                "POST, /groupstore/groups/aabbccddee1122334455aa01/lessons",
                "POST, /agents/aabbccddee1122334455aa01/start",
                "POST, /schedulestore/schedules",
                "GET, /llmstore/llms/aabbccddee1122334455aa01",
                "GET, /administration/production/deploy/aabbccddee1122334455aa01/preflight"})
        @DisplayName("a team's workspace, starting things and reading configuration are not configuration writes")
        void notConfiguration(String method, String path) throws Exception {
            URI uri = URI.create("https://eddi.example.com" + path);
            assertTrue(tainted(() -> EscalationGuard.refuseRequest(method, uri, false)).isEmpty());
        }

        @Test
        @DisplayName("configuration tools need approval, also behind a namespace; the guarded tools stay refused when approved")
        void tools() throws Exception {
            for (String tool : List.of("update_resource", "apply_agent_changes", "create_agent_trigger", "deploy_agent", "undeploy_agent",
                    "update_group", "eddi_update_resource", "createSubAgent", "retainAgent")) {
                assertEquals(Optional.of(EscalationGuard.CONFIG_REFUSAL), tainted(() -> EscalationGuard.refuseTool(tool, false)), tool);
                assertTrue(tainted(() -> EscalationGuard.refuseTool(tool, true)).isEmpty(), tool);
            }
            assertEquals(Optional.of(EscalationGuard.REFUSAL), tainted(() -> EscalationGuard.refuseTool("update_email_senders", true)));
            assertTrue(EscalationGuard.refuseTool("update_resource", false).isEmpty(), "untainted");
            assertFalse(EscalationGuard.GUARDED_TOOLS.contains("update_resource"),
                    "kept out of the never-list EmailAgentToolsInspector refuses at save time");
        }

        @Test
        @DisplayName("ToolExecutionService runs a configuration tool only as the approved execution of a call of the same conversation")
        void toolExecutionServiceNeedsApproval() throws Exception {
            ToolExecutionService tools = TurnOriginTestSupport.toolExecutionService();
            var executed = new AtomicBoolean();

            String refused = TurnOrigin.callWith(TurnOrigin.EMAIL,
                    () -> tools.executeToolWrapped("update_resource", "{}", null, "conv-1", () -> {
                        executed.set(true);
                        return "updated";
                    }, false, false, false, 60));
            assertFalse(executed.get());
            assertTrue(refused.contains("unless a person approves"), refused);

            String otherConversation = TurnOrigin.callWith(TurnOrigin.EMAIL,
                    () -> ApprovedCallScope.run(null, "conv-other",
                            () -> tools.executeToolWrapped("update_resource", "{}", null, "conv-1", () -> {
                                executed.set(true);
                                return "updated";
                            }, false, false, false, 60)));
            assertFalse(executed.get(), "an approval of another conversation's call does not count");
            assertTrue(otherConversation.contains("unless a person approves"), otherConversation);

            String approved = TurnOrigin.callWith(TurnOrigin.EMAIL,
                    () -> ApprovedCallScope.run(null, "conv-1",
                            () -> tools.executeToolWrapped("update_resource", "{}", null, "conv-1", () -> "updated", false, false, false, 60)));
            assertEquals("updated", approved);
        }
    }

    @Nested
    @DisplayName("TaintedRequestFilter as a JAX-RS filter")
    class Filter {

        private ContainerRequestContext request(String method, String path, String origin, String approved) {
            ContainerRequestContext request = mock(ContainerRequestContext.class);
            var headers = new MultivaluedHashMap<String, String>();
            if (origin != null) {
                headers.add(TurnOrigin.HEADER, origin);
            }
            if (approved != null) {
                headers.add(TurnOrigin.APPROVED_HEADER, approved);
            }
            UriInfo uriInfo = mock(UriInfo.class);
            when(uriInfo.getPath()).thenReturn(path);
            when(request.getMethod()).thenReturn(method);
            when(request.getUriInfo()).thenReturn(uriInfo);
            when(request.getHeaders()).thenReturn(headers);
            return request;
        }

        @Test
        @DisplayName("a tainted share write and an unapproved tainted configuration write answer 403; an approved one passes")
        void filters() {
            var filter = new TaintedRequestFilter();

            ContainerRequestContext share = request("POST", "/descriptorstore/descriptors/x/shares", "email", "true");
            filter.filter(share);
            ArgumentCaptor<Response> answer = ArgumentCaptor.forClass(Response.class);
            verify(share).abortWith(answer.capture());
            assertEquals(403, answer.getValue().getStatus());

            ContainerRequestContext config = request("PUT", "/llmstore/llms/x", "email", null);
            filter.filter(config);
            verify(config).abortWith(any());

            ContainerRequestContext approved = request("PUT", "/llmstore/llms/x", "email", "true");
            filter.filter(approved);
            verify(approved, never()).abortWith(any());

            ContainerRequestContext untainted = request("PUT", "/llmstore/llms/x", null, null);
            filter.filter(untainted);
            verify(untainted, never()).abortWith(any());
        }
    }
}
