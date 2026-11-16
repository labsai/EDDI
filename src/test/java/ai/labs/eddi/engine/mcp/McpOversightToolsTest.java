/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.mcp;

import ai.labs.eddi.datastore.serialization.JsonSerialization;
import ai.labs.eddi.engine.hitl.oversight.OversightErrors;
import ai.labs.eddi.engine.hitl.oversight.analytics.ApprovalSummary;
import ai.labs.eddi.engine.hitl.oversight.rest.IRestApprovalAnalytics;
import ai.labs.eddi.engine.hitl.oversight.rest.IRestAuditSamples;
import ai.labs.eddi.engine.hitl.oversight.rest.IRestAutonomy;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.security.ForbiddenException;
import io.quarkus.security.identity.SecurityIdentity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The oversight read tools: thin calls into the in-process REST beans, the
 * resource's coded refusals kept as {@code errorCode}, and the REST tier
 * (admin, editor, approver) at the door.
 */
@DisplayName("McpOversightTools")
class McpOversightToolsTest {

    private static final String AGENT = "6aba70011a25f8be7920e0c1";

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private IRestApprovalAnalytics analytics;
    private IRestAutonomy autonomy;
    private IRestAuditSamples samples;

    @BeforeEach
    void setUp() {
        analytics = mock(IRestApprovalAnalytics.class);
        autonomy = mock(IRestAutonomy.class);
        samples = mock(IRestAuditSamples.class);
    }

    private McpOversightTools tools(Set<String> roles) {
        return new McpOversightTools(analytics, autonomy, samples, new JsonSerialization(mapper), identity(roles), true);
    }

    private static SecurityIdentity identity(Set<String> roles) {
        SecurityIdentity id = mock(SecurityIdentity.class);
        when(id.isAnonymous()).thenReturn(false);
        when(id.hasRole(anyString())).thenAnswer(inv -> roles.contains(inv.getArgument(0, String.class)));
        when(id.getRoles()).thenReturn(roles);
        return id;
    }

    @Test
    @DisplayName("summarize_approvals passes every filter and the grouping through to the analytics resource")
    void summarizePassesTheFilter() throws Exception {
        when(analytics.summarize(any(), eq("category"))).thenReturn(new ApprovalSummary.Result("category", false, List.of()));

        JsonNode result = mapper.readTree(tools(Set.of("eddi-approver")).summarizeApprovals(AGENT, null, "tool:mcp:add_issue_comment",
                "2026-10-01", null, "regular", "TOOL_CALL", "SLACK", 4, "category"));

        ArgumentCaptor<IRestApprovalAnalytics.Query> query = ArgumentCaptor.forClass(IRestApprovalAnalytics.Query.class);
        verify(analytics).summarize(query.capture(), eq("category"));
        assertEquals(AGENT, query.getValue().agentId);
        assertEquals("tool:mcp:add_issue_comment", query.getValue().category);
        assertEquals("2026-10-01", query.getValue().from);
        assertEquals("regular", query.getValue().surface);
        assertEquals("TOOL_CALL", query.getValue().pauseType);
        assertEquals("SLACK", query.getValue().channel);
        assertEquals(4, query.getValue().agentVersion);
        assertEquals("category", result.get("groupBy").asText());
    }

    @Test
    @DisplayName("a refusal of the resource keeps its code — the caller branches on it as a REST client does")
    void codedRefusalKeepsItsCode() throws Exception {
        when(analytics.summarize(any(), any()))
                .thenThrow(OversightErrors.badRequest("WINDOW_TOO_LONG", "The window may be at most 365 days."));

        JsonNode result = mapper.readTree(tools(Set.of("eddi-editor")).summarizeApprovals(AGENT, null, null, "2024-01-01", null, null, null,
                null, null, null));

        assertEquals("WINDOW_TOO_LONG", result.get("errorCode").asText());
        assertEquals("The window may be at most 365 days.", result.get("error").asText());
    }

    @Test
    @DisplayName("get_autonomy_state needs agentId and category, and reads that one category")
    void getAutonomyState() throws Exception {
        assertEquals("BAD_REQUEST", mapper.readTree(tools(Set.of("eddi-admin")).getAutonomyState(AGENT, " ")).get("errorCode").asText());
        verifyNoInteractions(autonomy);

        tools(Set.of("eddi-admin")).getAutonomyState(AGENT, " tool:mcp:add_issue_comment ");
        verify(autonomy).readCategory(AGENT, "tool:mcp:add_issue_comment");
    }

    @Test
    @DisplayName("list and count delegate with the optional agent scope")
    void listAndCountDelegate() throws Exception {
        when(samples.pendingCount(AGENT)).thenReturn(new IRestAuditSamples.PendingCount(3));
        when(autonomy.listStates(null)).thenReturn(new IRestAutonomy.CategoryList(List.of()));
        var tools = tools(Set.of("eddi-admin"));

        assertEquals(3, mapper.readTree(tools.countPendingAuditSamples(AGENT)).get("pending").asLong());
        assertEquals(0, mapper.readTree(tools.listAutonomyRecommendations(null)).get("categories").size());
    }

    @Test
    @DisplayName("eddi-viewer and eddi-user are refused at the door — REST admits admin, editor and approver only")
    void viewerAndUserAreRefused() {
        for (String role : List.of("eddi-viewer", "eddi-user")) {
            var tools = tools(Set.of(role));
            assertThrows(ForbiddenException.class, () -> tools.summarizeApprovals(null, null, null, null, null, null, null, null, null, null));
            assertThrows(ForbiddenException.class, () -> tools.countPendingAuditSamples(null));
        }
        verifyNoInteractions(analytics, samples);
    }
}
