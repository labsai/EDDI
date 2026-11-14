/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.backup.impl;

import ai.labs.eddi.backup.IZipArchive;
import ai.labs.eddi.configs.agents.IAgentStore;
import ai.labs.eddi.configs.agents.IRestAgentStore;
import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.configs.descriptors.model.DocumentDescriptor;
import ai.labs.eddi.configs.hitl.model.OversightConfig;
import ai.labs.eddi.configs.hitl.model.SampledAuditConfig;
import ai.labs.eddi.configs.migration.IMigrationManager;
import ai.labs.eddi.configs.migration.TemplateSyntaxMigrator;
import ai.labs.eddi.configs.workflows.IWorkflowStore;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.hitl.oversight.AgentCallMarker;
import ai.labs.eddi.engine.hitl.oversight.AgentCallOversightGuard;
import ai.labs.eddi.engine.schedule.IScheduleStore;
import ai.labs.eddi.engine.security.spaces.ResourceAccessGuard;
import ai.labs.eddi.engine.security.spaces.SpaceContext;
import ai.labs.eddi.modules.ingestion.RagSourceIngestionService;
import io.quarkus.vertx.http.runtime.CurrentVertxRequest;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.spi.CDI;
import jakarta.ws.rs.ForbiddenException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A merge import carrying {@code X-EDDI-Agent-Call} may not replace an existing
 * agent's {@code hitlConfig.oversight}: the import fails with 403 before
 * anything is written, instead of reaching the agent-store write after the
 * workflows have landed.
 */
@DisplayName("RestImportService — an agent-originated merge cannot change oversight")
class RestImportServiceOversightGuardTest {

    private static final String AGENT_ORIGIN_ID = "aaaa11112222333344445555";
    private static final String LOCAL_AGENT_ID = "cccc11112222333344445555";
    private static final String WORKFLOW_ID = "bbbb11112222333344445551";

    private IJsonSerialization jsonSerialization;
    private IDocumentDescriptorStore documentDescriptorStore;
    private RestImportService importService;
    private IAgentStore agentStore;
    private IRestAgentStore restAgentStore;
    private IWorkflowStore workflowStore;

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
        agent.setWorkflows(List.of(URI.create("eddi://ai.labs.workflow/workflowstore/workflows/" + WORKFLOW_ID + "?version=1")));
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

    @BeforeEach
    void setUp() throws Exception {
        var zipArchive = mock(IZipArchive.class);
        jsonSerialization = mock(IJsonSerialization.class);
        documentDescriptorStore = mock(IDocumentDescriptorStore.class);
        var migrator = mock(TemplateSyntaxMigrator.class);
        when(migrator.migrate(anyString())).thenAnswer(i -> i.getArgument(0));
        importService = new RestImportService(zipArchive, jsonSerialization, mock(IMigrationManager.class), documentDescriptorStore, migrator,
                mock(StructuralMatcher.class), mock(UpgradeExecutor.class), mock(IScheduleStore.class), mock(BackupMetrics.class),
                mock(ResourceAccessGuard.class), mock(SpaceContext.class), mock(RagSourceIngestionService.class), true, false,
                Optional.empty());

        doAnswer(inv -> {
            File dir = inv.getArgument(1);
            new File(new File(dir, WORKFLOW_ID), "1").mkdirs();
            Files.writeString(new File(dir, AGENT_ORIGIN_ID + ".agent.json").toPath(), "AGENTJSON");
            return null;
        }).when(zipArchive).unzip(any(InputStream.class), any(File.class));

        // The archive lowers the sample rate of an agent that exists here.
        when(jsonSerialization.deserialize(eq("AGENTJSON"), eq(AgentConfiguration.class))).thenReturn(agentWithSampleRate(0.0));
        var descriptor = new DocumentDescriptor();
        descriptor.setResource(URI.create("eddi://ai.labs.agent/agentstore/agents/" + LOCAL_AGENT_ID + "?version=1"));
        when(documentDescriptorStore.findByOriginId(AGENT_ORIGIN_ID)).thenReturn(List.of(descriptor));

        agentStore = mock(IAgentStore.class);
        when(agentStore.read(LOCAL_AGENT_ID, 1)).thenReturn(agentWithSampleRate(0.5));
        restAgentStore = mock(IRestAgentStore.class);
        workflowStore = mock(IWorkflowStore.class);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static <T> void stubBean(CDI cdi, Class<T> beanClass, T bean) {
        Instance<T> instance = mock(Instance.class);
        when(cdi.select(beanClass)).thenReturn(instance);
        when(instance.get()).thenReturn(bean);
    }

    private MockedStatic<CDI> cdi() {
        MockedStatic<CDI> cdiMock = mockStatic(CDI.class);
        CDI cdi = mock(CDI.class);
        cdiMock.when(CDI::current).thenReturn(cdi);
        stubBean(cdi, IAgentStore.class, agentStore);
        stubBean(cdi, IRestAgentStore.class, restAgentStore);
        stubBean(cdi, IWorkflowStore.class, workflowStore);
        return cdiMock;
    }

    @Test
    @DisplayName("a marked merge lowering an existing agent's sample rate fails with 403, and nothing is written")
    void markedMergeIsRefusedBeforeAnyWrite() throws Exception {
        importService.useOversightGuard(guard(AgentCallMarker.VALUE));

        try (MockedStatic<CDI> ignored = cdi()) {
            assertThrows(ForbiddenException.class,
                    () -> importService.importAgent(new ByteArrayInputStream(new byte[0]), "merge", null, null, null));
        }
        verify(workflowStore, never()).create(any());
        verify(agentStore, never()).create(any());
        verify(restAgentStore, never()).updateAgent(anyString(), anyInt(), any(), anyBoolean());
    }

    @Test
    @DisplayName("the check: a marked merge keeping oversight as stored passes; an unmarked one is not checked")
    void checkOnlyRefusesAChangeOnAMarkedRequest() {
        try (MockedStatic<CDI> ignored = cdi()) {
            importService.useOversightGuard(guard(AgentCallMarker.VALUE));
            assertDoesNotThrow(() -> importService.requireNoAgentOriginatedOversightChange(agentWithSampleRate(0.5), AGENT_ORIGIN_ID));
            assertThrows(ForbiddenException.class,
                    () -> importService.requireNoAgentOriginatedOversightChange(agentWithSampleRate(0.0), AGENT_ORIGIN_ID));

            importService.useOversightGuard(guard(null));
            assertDoesNotThrow(() -> importService.requireNoAgentOriginatedOversightChange(agentWithSampleRate(0.0), AGENT_ORIGIN_ID));
        }
    }

    @Test
    @DisplayName("an archive agent with no local match is a new agent, not checked")
    void newAgentNotChecked() throws Exception {
        when(documentDescriptorStore.findByOriginId(AGENT_ORIGIN_ID)).thenReturn(List.of());
        importService.useOversightGuard(guard(AgentCallMarker.VALUE));

        try (MockedStatic<CDI> ignored = cdi()) {
            assertDoesNotThrow(() -> importService.requireNoAgentOriginatedOversightChange(agentWithSampleRate(0.0), AGENT_ORIGIN_ID));
        }
    }
}
