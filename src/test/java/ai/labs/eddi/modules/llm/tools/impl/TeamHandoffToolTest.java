/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.tools.impl;

import ai.labs.eddi.configs.groups.model.HandoffPolicies.HandoffTarget;
import ai.labs.eddi.configs.groups.model.WorkspaceHandoffs.SourceKind;
import ai.labs.eddi.configs.groups.model.WorkspaceHandoffs.Trigger;
import ai.labs.eddi.engine.runtime.internal.handoff.HandoffRequest;
import ai.labs.eddi.engine.runtime.internal.handoff.HandoffResult;
import ai.labs.eddi.engine.runtime.internal.handoff.HandoffResult.Outcome;
import ai.labs.eddi.engine.runtime.internal.handoff.TeamHandoffService;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.model.chat.request.json.JsonEnumSchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TeamHandoffToolTest {

    TeamHandoffService service;
    List<TeamHandoffTool.Outcome> outcomes;
    HandoffRequest.RunQuota quota;

    @BeforeEach
    void setUp() {
        service = mock(TeamHandoffService.class);
        outcomes = new ArrayList<>();
        quota = mock(HandoffRequest.RunQuota.class);
    }

    TeamHandoffTool tool(int maxPerTurn) {
        var targets = List.of(new HandoffTarget("account-desk", "g-desk", "Warm leads", 7), new HandoffTarget("legal", "g-legal", null, null));
        var ctx = new TeamHandoffTool.Context(SourceKind.GROUP, "g-src", null, "gc-1", "member-1", "user-1", targets, maxPerTurn, 2000, 1,
                List.of("agent:x"),
                quota, ref -> "Plan".equalsIgnoreCase(ref) ? "art-1" : null, outcomes::add);
        return new TeamHandoffTool(service, ctx);
    }

    String call(TeamHandoffTool tool, String json) {
        return tool.executor().execute(ToolExecutionRequest.builder().name(TeamHandoffTool.TOOL_NAME).arguments(json).build(), "m");
    }

    HandoffResult filed(String id) {
        return new HandoffResult(Outcome.QUEUED_FOR_REVIEW, id, null, "Handed off.");
    }

    @Test
    void theTeamArgumentIsAnEnumOfTheDeclaredAliasesOnly() {
        var spec = tool(2).specification();
        assertEquals("handoff_to_team", spec.name());
        var schema = (JsonObjectSchema) spec.parameters();
        var team = (JsonEnumSchema) schema.properties().get("team");
        assertEquals(List.of("account-desk", "legal"), team.enumValues());
        assertEquals(List.of("team", "subject", "description"), schema.required());
        assertTrue(spec.description().contains("account-desk: Warm leads"));
    }

    @Test
    void anUnknownAliasNeverReachesTheService() {
        String message = call(tool(2), "{\"team\":\"finance\",\"subject\":\"s\",\"description\":\"d\"}");
        assertTrue(message.contains("account-desk, legal"), message);
        verify(service, never()).file(any());
    }

    @Test
    void theTargetIsResolvedServerSideAndTheSourceComesFromTheContext() {
        when(service.file(any())).thenReturn(filed("h-1"));
        call(tool(2), "{\"team\":\"account-desk\",\"subject\":\"Research ACME\",\"description\":\"d\",\"reference\":\"plan\"}");
        var captor = ArgumentCaptor.forClass(HandoffRequest.class);
        verify(service).file(captor.capture());
        HandoffRequest request = captor.getValue();
        assertEquals("g-desk", request.targetGroupId());
        assertEquals(SourceKind.GROUP, request.sourceKind());
        assertEquals("g-src", request.sourceId());
        assertEquals("member-1", request.sourceMemberAgentId());
        assertEquals(Trigger.TOOL, request.trigger());
        assertEquals(7, request.priority(), "no priority argument: the target's default");
        assertEquals("art-1", request.artifactId());
        assertEquals(1, request.hop());
        assertEquals(List.of("agent:x"), request.lineage());
        assertSame(quota, request.runQuota());
    }

    @Test
    void aModelCannotSpoofTheSourceThroughArguments() {
        when(service.file(any())).thenReturn(filed("h-1"));
        call(tool(2), "{\"team\":\"legal\",\"subject\":\"s\",\"description\":\"d\",\"sourceId\":\"someone-else\",\"sourceKind\":\"AGENT\","
                + "\"targetGroupId\":\"g-other\",\"hop\":0,\"lineage\":[]}");
        var captor = ArgumentCaptor.forClass(HandoffRequest.class);
        verify(service).file(captor.capture());
        assertEquals("g-legal", captor.getValue().targetGroupId());
        assertEquals("g-src", captor.getValue().sourceId());
        assertEquals(SourceKind.GROUP, captor.getValue().sourceKind());
        assertEquals(1, captor.getValue().hop());
    }

    @Test
    void anExplicitPriorityWinsOverTheDefault() {
        when(service.file(any())).thenReturn(filed("h-1"));
        call(tool(2), "{\"team\":\"account-desk\",\"subject\":\"s\",\"description\":\"d\",\"priority\":2}");
        var captor = ArgumentCaptor.forClass(HandoffRequest.class);
        verify(service).file(captor.capture());
        assertEquals(2, captor.getValue().priority());
    }

    @Test
    void thePerTurnCapCountsOnlyAcceptedHandoffs() {
        var tool = tool(1);
        when(service.file(any())).thenReturn(HandoffResult.of(Outcome.NOT_ACCEPTED, "no"),
                new HandoffResult(Outcome.DUPLICATE, null, "PENDING", "dup"),
                filed("h-1"));
        String args = "{\"team\":\"legal\",\"subject\":\"s\",\"description\":\"d\"}";
        assertEquals("no", call(tool, args), "a refusal does not use the allowance");
        assertEquals("dup", call(tool, args), "a duplicate files nothing, so it does not use the allowance either");
        assertEquals("Handed off.", call(tool, args));
        String over = call(tool, args);
        assertTrue(over.contains("limit"), over);
        verify(service, times(3)).file(any());
    }

    @Test
    void attemptsAreBoundedEvenWhenTheyAllFail() {
        var tool = tool(5);
        when(service.file(any())).thenReturn(HandoffResult.of(Outcome.NOT_ACCEPTED, "no"));
        String args = "{\"team\":\"legal\",\"subject\":\"s\",\"description\":\"d\"}";
        for (int i = 0; i < TeamHandoffTool.MAX_ATTEMPTS_PER_TURN; i++) {
            assertEquals("no", call(tool, args));
        }
        assertTrue(call(tool, args).contains("Too many"));
        verify(service, times(TeamHandoffTool.MAX_ATTEMPTS_PER_TURN)).file(any());
    }

    @Test
    void malformedArgumentsAreAnswerNotAnException() {
        String message = call(tool(2), "{not json");
        assertTrue(message.contains("not valid JSON"));
        verify(service, never()).file(any());
    }

    @Test
    void everyAttemptIsReportedAndAFailingObserverNeverBreaksTheCall() {
        when(service.file(any())).thenReturn(filed("h-1"));
        var targets = List.of(new HandoffTarget("legal", "g-legal", null, null));
        var ctx = new TeamHandoffTool.Context(SourceKind.AGENT, "agent-1", null, "c-1", null, "u", targets, 2, 2000, 0, List.of(), null, null, o -> {
            throw new IllegalStateException("boom");
        });
        String message = new TeamHandoffTool(service, ctx).executor().execute(ToolExecutionRequest.builder().name("handoff_to_team")
                .arguments("{\"team\":\"legal\",\"subject\":\"s\",\"description\":\"d\"}").build(), "m");
        assertEquals("Handed off.", message);
        call(tool(2), "{\"team\":\"legal\",\"subject\":\"Seen\",\"description\":\"d\"}");
        assertEquals(1, outcomes.size());
        assertEquals("Seen", outcomes.get(0).subject());
        assertEquals("g-legal", outcomes.get(0).target().groupId());
    }

    @Test
    void betFieldsRideTheRequestAndAHalfBetFilesNothing() {
        when(service.file(any())).thenReturn(filed("h-1"));
        call(tool(2), "{\"team\":\"legal\",\"subject\":\"s\",\"description\":\"d\",\"hypothesis\":\"h\",\"metric\":\"m\","
                + "\"target\":\">= 3\",\"reviewDate\":\"2026-12-01\"}");
        var captor = ArgumentCaptor.forClass(HandoffRequest.class);
        verify(service).file(captor.capture());
        assertEquals("m", captor.getValue().bet().metric());

        String refused = call(tool(2), "{\"team\":\"legal\",\"subject\":\"s2\",\"description\":\"d\",\"hypothesis\":\"h\"}");
        assertTrue(refused.contains("Nothing was handed off"), refused);
        verify(service, org.mockito.Mockito.times(1)).file(any());
    }

    @Test
    void theSpecOffersTheOptionalBetFieldsButRequiresNone() {
        var schema = (JsonObjectSchema) tool(2).specification().parameters();
        assertTrue(schema.properties().keySet().containsAll(List.of("hypothesis", "metric", "target", "reviewDate")));
        assertEquals(List.of("team", "subject", "description"), schema.required());
    }

}
