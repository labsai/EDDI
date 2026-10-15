/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.backup.impl;

import ai.labs.eddi.backup.IZipArchive;
import ai.labs.eddi.configs.agents.IAgentStore;
import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.configs.apicalls.IApiCallsStore;
import ai.labs.eddi.configs.apicalls.model.ApiCallsConfiguration;
import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.configs.migration.IDocumentMigration;
import ai.labs.eddi.configs.migration.IMigrationManager;
import ai.labs.eddi.configs.migration.TemplateSyntaxMigrator;
import ai.labs.eddi.configs.workflows.IWorkflowStore;
import ai.labs.eddi.configs.workflows.model.WorkflowConfiguration;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.schedule.IScheduleStore;
import ai.labs.eddi.engine.security.CallerTokenAuthorPolicy;
import ai.labs.eddi.engine.security.spaces.ResourceAccessGuard;
import ai.labs.eddi.engine.security.spaces.SpaceContext;
import ai.labs.eddi.modules.ingestion.RagSourceIngestionService;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.spi.CDI;
import jakarta.ws.rs.ForbiddenException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * An import writes extensions straight to their stores, past the REST layer's
 * {@code ${caller:token}} author check, so the import applies the check itself
 * — and a refusal fails the import with the 403, not a 500.
 */
class RestImportServiceCallerTokenTest {

    private static final String AGENT_ORIGIN_ID = "aaaa11112222333344445555";
    private static final String WORKFLOW_ID = "bbbb11112222333344445551";
    private static final String HTTPCALLS_ID = "dddd11112222333344445555";

    @Test
    @DisplayName("an httpcalls config the caller may not author fails the import before it is stored")
    void refusedHttpCallsFailTheImport() throws Exception {
        var zipArchive = mock(IZipArchive.class);
        var jsonSerialization = mock(IJsonSerialization.class);
        var migrator = mock(TemplateSyntaxMigrator.class);
        when(migrator.migrate(anyString())).thenAnswer(i -> i.getArgument(0));
        // The httpcalls read passes through the 5.x migration, which here changes
        // nothing.
        var migrationManager = mock(IMigrationManager.class);
        when(migrationManager.migrateApiCalls()).thenReturn(mock(IDocumentMigration.class));
        var importService = new RestImportService(zipArchive, jsonSerialization, migrationManager,
                mock(IDocumentDescriptorStore.class), migrator, mock(StructuralMatcher.class), mock(UpgradeExecutor.class),
                mock(IScheduleStore.class), mock(BackupMetrics.class), mock(ResourceAccessGuard.class), mock(SpaceContext.class),
                mock(RagSourceIngestionService.class), true, false, Optional.empty());

        String httpCallsUri = "eddi://ai.labs.apicalls/apicallstore/apicalls/" + HTTPCALLS_ID + "?version=1";
        doAnswer(inv -> {
            File dir = inv.getArgument(1);
            File workflowDir = new File(new File(dir, WORKFLOW_ID), "1");
            workflowDir.mkdirs();
            Files.writeString(new File(dir, AGENT_ORIGIN_ID + ".agent.json").toPath(), "AGENTJSON");
            Files.writeString(new File(workflowDir, WORKFLOW_ID + ".workflow.json").toPath(),
                    "{\"workflowSteps\":[{\"config\":{\"uri\":\"" + httpCallsUri + "\"}}]}");
            Files.writeString(new File(workflowDir, HTTPCALLS_ID + ".httpcalls.json").toPath(), "HTTPJSON");
            return null;
        }).when(zipArchive).unzip(any(InputStream.class), any(File.class));

        var agentConfig = new AgentConfiguration();
        agentConfig.setWorkflows(List.of(URI.create("eddi://ai.labs.workflow/workflowstore/workflows/" + WORKFLOW_ID + "?version=1")));
        when(jsonSerialization.deserialize(eq("AGENTJSON"), eq(AgentConfiguration.class))).thenReturn(agentConfig);
        var httpCalls = new ApiCallsConfiguration();
        when(jsonSerialization.deserialize(eq("HTTPJSON"), eq(ApiCallsConfiguration.class))).thenReturn(httpCalls);
        when(jsonSerialization.deserialize(eq("HTTPJSON"), eq(Map.class))).thenReturn(Map.of());
        when(jsonSerialization.deserialize(anyString(), eq(WorkflowConfiguration.class))).thenAnswer(i -> new WorkflowConfiguration());

        var policy = mock(CallerTokenAuthorPolicy.class);
        doThrow(new ForbiddenException("caller token")).when(policy).requireMayAuthor(same(httpCalls), anyString());
        importService.useCallerTokenAuthorPolicy(policy);

        var apiCallsStore = mock(IApiCallsStore.class);
        var workflowStore = mock(IWorkflowStore.class);
        var agentStore = mock(IAgentStore.class);
        try (MockedStatic<CDI> cdiMock = mockStatic(CDI.class)) {
            CDI cdi = mock(CDI.class);
            cdiMock.when(CDI::current).thenReturn(cdi);
            stubBean(cdi, IApiCallsStore.class, apiCallsStore);
            stubBean(cdi, IWorkflowStore.class, workflowStore);
            stubBean(cdi, IAgentStore.class, agentStore);

            assertThrows(ForbiddenException.class,
                    () -> importService.importAgent(new ByteArrayInputStream(new byte[0]), "create", null, null, null));
        }

        verify(apiCallsStore, never()).create(any());
        verify(workflowStore, never()).create(any());
        verify(agentStore, never()).create(any());
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static <T> void stubBean(CDI cdi, Class<T> beanClass, T bean) {
        Instance<T> instance = mock(Instance.class);
        when(cdi.select(beanClass)).thenReturn(instance);
        when(instance.get()).thenReturn(bean);
    }
}
