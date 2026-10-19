/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.backup.impl;

import ai.labs.eddi.backup.IZipArchive;
import ai.labs.eddi.configs.agents.IAgentStore;
import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.configs.apicalls.IApiCallsStore;
import ai.labs.eddi.configs.apicalls.IRestApiCallsStore;
import ai.labs.eddi.configs.apicalls.model.ApiCallsConfiguration;
import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.configs.descriptors.model.DocumentDescriptor;
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
import ai.labs.eddi.secrets.sanitize.ConfigSecretRedactor;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.spi.CDI;
import jakarta.ws.rs.BadRequestException;
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

    private IJsonSerialization jsonSerialization;
    private IDocumentDescriptorStore documentDescriptorStore;
    private RestImportService importService;
    private ApiCallsConfiguration httpCalls;
    private CallerTokenAuthorPolicy policy;
    private IApiCallsStore apiCallsStore;
    private IRestApiCallsStore restApiCallsStore;
    private IWorkflowStore workflowStore;
    private IAgentStore agentStore;

    @BeforeEach
    void setUp() throws Exception {
        var zipArchive = mock(IZipArchive.class);
        jsonSerialization = mock(IJsonSerialization.class);
        documentDescriptorStore = mock(IDocumentDescriptorStore.class);
        var migrator = mock(TemplateSyntaxMigrator.class);
        when(migrator.migrate(anyString())).thenAnswer(i -> i.getArgument(0));
        // The httpcalls read passes through the 5.x migration, which here changes
        // nothing.
        var migrationManager = mock(IMigrationManager.class);
        when(migrationManager.migrateApiCalls()).thenReturn(mock(IDocumentMigration.class));
        importService = new RestImportService(zipArchive, jsonSerialization, migrationManager, documentDescriptorStore, migrator,
                mock(StructuralMatcher.class), mock(UpgradeExecutor.class), mock(IScheduleStore.class), mock(BackupMetrics.class),
                mock(ResourceAccessGuard.class), mock(SpaceContext.class), mock(RagSourceIngestionService.class), true, false,
                Optional.empty());

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
        httpCalls = new ApiCallsConfiguration();
        when(jsonSerialization.deserialize(eq("HTTPJSON"), eq(ApiCallsConfiguration.class))).thenReturn(httpCalls);
        when(jsonSerialization.deserialize(eq("HTTPJSON"), eq(Map.class))).thenReturn(Map.of());
        when(jsonSerialization.deserialize(anyString(), eq(WorkflowConfiguration.class))).thenAnswer(i -> new WorkflowConfiguration());

        policy = mock(CallerTokenAuthorPolicy.class);
        importService.useCallerTokenAuthorPolicy(policy);
        apiCallsStore = mock(IApiCallsStore.class);
        restApiCallsStore = mock(IRestApiCallsStore.class);
        workflowStore = mock(IWorkflowStore.class);
        agentStore = mock(IAgentStore.class);
    }

    private void importExpectingForbidden(String strategy) throws Exception {
        importExpecting(ForbiddenException.class, strategy);
    }

    private void importExpecting(Class<? extends Throwable> refusal, String strategy) throws Exception {
        try (MockedStatic<CDI> cdiMock = mockStatic(CDI.class)) {
            CDI cdi = mock(CDI.class);
            cdiMock.when(CDI::current).thenReturn(cdi);
            stubBean(cdi, IApiCallsStore.class, apiCallsStore);
            stubBean(cdi, IRestApiCallsStore.class, restApiCallsStore);
            stubBean(cdi, IWorkflowStore.class, workflowStore);
            stubBean(cdi, IAgentStore.class, agentStore);

            assertThrows(refusal,
                    () -> importService.importAgent(new ByteArrayInputStream(new byte[0]), strategy, null, null, null));
        }
        verify(apiCallsStore, never()).create(any());
        verify(workflowStore, never()).create(any());
        verify(agentStore, never()).create(any());
    }

    @Test
    @DisplayName("an httpcalls config the caller may not author fails the import before it is stored")
    void refusedHttpCallsFailTheImport() throws Exception {
        doThrow(new ForbiddenException("caller token")).when(policy).requireMayAuthor(same(httpCalls), anyString());

        importExpectingForbidden("create");
    }

    @Test
    @DisplayName("a config carrying the read-redaction marker fails the import before it is stored")
    void redactionMarkerFailsTheImport() throws Exception {
        httpCalls.setTargetServerUrl("https://svc:" + ConfigSecretRedactor.IN_STRING_REDACTED + "@api.example.com");

        importExpecting(BadRequestException.class, "create");
    }

    @Test
    @DisplayName("a merge that would replace a ${caller:token} version its caller could not restore is refused before writing")
    void mergeOverAnUnrestorableVersionIsRefusedBeforeTheUpdate() throws Exception {
        String localId = "eeee11112222333344445555";
        var descriptor = new DocumentDescriptor();
        descriptor.setResource(URI.create("eddi://ai.labs.apicalls/apicallstore/apicalls/" + localId + "?version=1"));
        when(documentDescriptorStore.findByOriginId(HTTPCALLS_ID)).thenReturn(List.of(descriptor));
        var stored = new ApiCallsConfiguration();
        when(apiCallsStore.read(localId, 1)).thenReturn(stored);
        doThrow(new ForbiddenException("cannot restore")).when(policy).requireMayRestore(same(stored), anyString());

        importExpectingForbidden("merge");

        verify(restApiCallsStore, never()).updateApiCalls(anyString(), any(), any());
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static <T> void stubBean(CDI cdi, Class<T> beanClass, T bean) {
        Instance<T> instance = mock(Instance.class);
        when(cdi.select(beanClass)).thenReturn(instance);
        when(instance.get()).thenReturn(bean);
    }
}
