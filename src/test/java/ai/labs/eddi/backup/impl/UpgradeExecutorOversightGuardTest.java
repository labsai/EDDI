/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.backup.impl;

import ai.labs.eddi.backup.IResourceSource;
import ai.labs.eddi.backup.model.ImportPreview;
import ai.labs.eddi.backup.model.ImportPreview.DiffAction;
import ai.labs.eddi.backup.model.ImportPreview.ResourceDiff;
import ai.labs.eddi.configs.agents.IRestAgentStore;
import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.configs.hitl.model.OversightConfig;
import ai.labs.eddi.configs.hitl.model.SampledAuditConfig;
import ai.labs.eddi.configs.snippets.IRestPromptSnippetStore;
import ai.labs.eddi.configs.workflows.IRestWorkflowStore;
import ai.labs.eddi.datastore.IResourceStore.IResourceId;
import ai.labs.eddi.datastore.serialization.JsonSerialization;
import ai.labs.eddi.engine.hitl.oversight.AgentCallMarker;
import ai.labs.eddi.engine.hitl.oversight.AgentCallOversightGuard;
import ai.labs.eddi.engine.security.spaces.ResourceAccessGuard;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.vertx.http.runtime.CurrentVertxRequest;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.ext.web.RoutingContext;
import jakarta.ws.rs.ForbiddenException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * An upgrade (sync) carrying {@code X-EDDI-Agent-Call} may not change the
 * target agent's {@code hitlConfig.oversight}; it is refused with 403 before
 * any workflow or extension is written.
 */
@DisplayName("UpgradeExecutor — an agent-originated upgrade cannot change oversight")
class UpgradeExecutorOversightGuardTest {

    private static final String AGENT = "cccc11112222333344445555";

    private final ObjectMapper mapper = new ObjectMapper();
    private IRestAgentStore agentStore;
    private IRestWorkflowStore workflowStore;
    private StructuralMatcher matcher;
    private UpgradeExecutor executor;

    private static AgentConfiguration agentWithSampleRate(double rate) {
        var rule = new SampledAuditConfig.Rule();
        rule.setMatch("*");
        rule.setRate(rate);
        var audit = new SampledAuditConfig();
        audit.setRules(List.of(rule));
        var oversight = new OversightConfig();
        oversight.setSampledAudit(audit);
        var hitl = new AgentConfiguration.HitlConfig();
        hitl.setOversight(oversight);
        var agent = new AgentConfiguration();
        agent.setHitlConfig(hitl);
        return agent;
    }

    private static AgentCallOversightGuard guard(String marker) {
        CurrentVertxRequest current = mock(CurrentVertxRequest.class);
        RoutingContext context = mock(RoutingContext.class);
        HttpServerRequest request = mock(HttpServerRequest.class);
        when(current.getCurrent()).thenReturn(context);
        when(context.request()).thenReturn(request);
        when(request.getHeader(AgentCallMarker.HEADER)).thenReturn(marker);
        return new AgentCallOversightGuard(current);
    }

    private ImportPreview previewAdopting(AgentConfiguration source, DiffAction action) throws Exception {
        var agentDiff = new ResourceDiff("src-agent", "agent", "Agent", action, AGENT, 5, "root", mapper.writeValueAsString(source),
                null, -1);
        return new ImportPreview("src-agent", "Agent", AGENT, "Agent", List.of(agentDiff));
    }

    @BeforeEach
    void setUp() throws Exception {
        agentStore = mock(IRestAgentStore.class);
        workflowStore = mock(IRestWorkflowStore.class);
        matcher = mock(StructuralMatcher.class);
        executor = new UpgradeExecutor(agentStore, workflowStore, mock(IRestPromptSnippetStore.class), new JsonSerialization(mapper), matcher,
                mock(IDocumentDescriptorStore.class), mock(BackupMetrics.class), mock(ResourceAccessGuard.class));
        IResourceId current = mock(IResourceId.class);
        when(current.getId()).thenReturn(AGENT);
        when(current.getVersion()).thenReturn(5);
        when(agentStore.getCurrentResourceId(AGENT)).thenReturn(current);
        when(agentStore.readAgent(AGENT, 5)).thenReturn(agentWithSampleRate(0.5));
    }

    @Test
    @DisplayName("a marked upgrade lowering the sample rate fails with 403 before anything is written")
    void markedUpgradeIsRefusedUpFront() throws Exception {
        executor.useOversightGuard(guard(AgentCallMarker.VALUE));
        var source = mock(IResourceSource.class);
        when(matcher.buildPreview(any(), eq(AGENT), anyBoolean())).thenReturn(previewAdopting(agentWithSampleRate(0.0), DiffAction.UPDATE));

        assertThrows(ForbiddenException.class, () -> executor.executeUpgrade(source, AGENT, null, null));

        verify(source, never()).readWorkflows();
        verify(agentStore, never()).updateAgent(anyString(), anyInt(), any(), anyBoolean());
    }

    @Test
    @DisplayName("a CONFLICT is checked too, since it may be adopted")
    void conflictIsChecked() throws Exception {
        executor.useOversightGuard(guard(AgentCallMarker.VALUE));
        assertThrows(ForbiddenException.class,
                () -> executor.requireNoAgentOriginatedOversightChange(previewAdopting(agentWithSampleRate(1.0), DiffAction.CONFLICT), AGENT,
                        null));
    }

    @Test
    @DisplayName("unchanged oversight, an unmarked request, or a skipped agent pass")
    void passes() throws Exception {
        executor.useOversightGuard(guard(AgentCallMarker.VALUE));
        assertDoesNotThrow(
                () -> executor.requireNoAgentOriginatedOversightChange(previewAdopting(agentWithSampleRate(0.5), DiffAction.UPDATE), AGENT, null));
        assertDoesNotThrow(
                () -> executor.requireNoAgentOriginatedOversightChange(previewAdopting(agentWithSampleRate(0.0), DiffAction.SKIP), AGENT, null));

        executor.useOversightGuard(guard(null));
        assertDoesNotThrow(
                () -> executor.requireNoAgentOriginatedOversightChange(previewAdopting(agentWithSampleRate(0.0), DiffAction.UPDATE), AGENT, null));
    }
}
