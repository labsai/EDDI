/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.backup.impl;

import ai.labs.eddi.backup.IZipArchive;
import ai.labs.eddi.configs.agents.IAgentStore;
import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.configs.descriptors.model.DocumentDescriptor;
import ai.labs.eddi.configs.migration.IMigrationManager;
import ai.labs.eddi.configs.migration.TemplateSyntaxMigrator;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.schedule.IScheduleStore;
import ai.labs.eddi.engine.security.spaces.ResourceAccessGuard;
import ai.labs.eddi.engine.security.spaces.SpaceContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.spi.CDI;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.InternalServerErrorException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code eddi.backup.import.failure.count} is the meter an operator alerts on,
 * so every way {@code importAgent} can end badly has to move it — including the
 * one nobody anticipated.
 * <p>
 * The classification matters as much as the count: a request the caller got
 * wrong is a 4xx they can act on, and only a genuine fault of this deployment
 * may become a 500. Both are failures, and a run where the failure meter stayed
 * at zero while imports were breaking is exactly the blind spot the meter was
 * added for.
 */
@DisplayName("RestImportService — what counts as a failed import")
class RestImportServiceFailureMetricsTest {

    private static final String AGENT_ORIGIN_ID = "aabb11112222333344445555";

    private IZipArchive zipArchive;
    private IJsonSerialization jsonSerialization;
    private BackupMetrics metrics;
    private RestImportService importService;

    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @BeforeEach
    void setUp() throws Exception {
        zipArchive = mock(IZipArchive.class);
        jsonSerialization = mock(IJsonSerialization.class);
        metrics = mock(BackupMetrics.class);

        var templateSyntaxMigrator = mock(TemplateSyntaxMigrator.class);
        when(templateSyntaxMigrator.migrate(anyString())).thenAnswer(inv -> inv.getArgument(0));

        importService = new RestImportService(
                zipArchive, jsonSerialization,
                mock(IMigrationManager.class), mock(IDocumentDescriptorStore.class),
                templateSyntaxMigrator, mock(StructuralMatcher.class),
                mock(UpgradeExecutor.class), mock(IScheduleStore.class), metrics,
                mock(ResourceAccessGuard.class), mock(SpaceContext.class));

        when(jsonSerialization.deserialize(anyString(), eq(AgentConfiguration.class)))
                .thenAnswer(inv -> mapper.readValue((String) inv.getArgument(0), AgentConfiguration.class));
        when(jsonSerialization.deserialize(anyString(), eq(DocumentDescriptor.class)))
                .thenAnswer(inv -> mapper.readValue((String) inv.getArgument(0), DocumentDescriptor.class));

        stubUnzip(dir -> {
            Files.writeString(new File(dir, AGENT_ORIGIN_ID + ".agent.json").toPath(), "{\"workflows\":[]}");
            Files.writeString(new File(dir, AGENT_ORIGIN_ID + ".descriptor.json").toPath(), "{\"name\":\"Agent\"}");
        });
    }

    /**
     * The catch-all. Anything the import did not anticipate — an agent store that
     * throws where the contract says it returns — is this deployment's fault, so it
     * is a 500 that names what went wrong and counts on the failure meter.
     * Reporting it as anything else would leave a broken instance looking healthy.
     */
    @Test
    @DisplayName("an unexpected store fault is a 500 that names the cause and counts as a failure")
    void unexpectedStoreFaultIsA500AndCounts() throws Exception {
        var agentStore = mock(IAgentStore.class);
        when(agentStore.create(any())).thenThrow(new IllegalStateException("agent store is down"));

        var thrown = assertThrows(InternalServerErrorException.class, () -> {
            try (var cdi = stubCdi(IAgentStore.class, agentStore)) {
                importService.importAgent(new ByteArrayInputStream(new byte[0]), "create", null, null, null);
            }
        });

        assertTrue(String.valueOf(thrown.getMessage()).contains("agent store is down"),
                "the operator has to be told what actually failed, was: " + thrown.getMessage());
        assertEquals(500, thrown.getResponse().getStatus());
        verify(metrics).importAttempted();
        verify(metrics).importFailed();
    }

    /**
     * The stores throw {@code ResourceStoreException}, which is checked and which
     * {@code createResourceDirect} rethrows unwrapped rather than dressing it up as
     * something else. It must land on the same catch-all: still a 500, still
     * counted.
     */
    @Test
    @DisplayName("a checked ResourceStoreException from the store lands on the same catch-all")
    void checkedStoreExceptionIsA500AndCounts() throws Exception {
        var agentStore = mock(IAgentStore.class);
        doAnswer(inv -> {
            throw new IResourceStore.ResourceStoreException("write concern not met");
        }).when(agentStore).create(any());

        var thrown = assertThrows(InternalServerErrorException.class, () -> {
            try (var cdi = stubCdi(IAgentStore.class, agentStore)) {
                importService.importAgent(new ByteArrayInputStream(new byte[0]), "create", null, null, null);
            }
        });

        assertTrue(String.valueOf(thrown.getMessage()).contains("write concern not met"),
                "the store's own reason must survive, was: " + thrown.getMessage());
        verify(metrics).importFailed();
    }

    /**
     * A store that rejects the configuration itself — an invalid
     * {@code hitlConfig}, say — raises {@code IllegalArgumentException}, which the
     * {@code IllegalArgumentExceptionMapper} turns into a 400. Letting the
     * catch-all have it instead would report the operator's own malformed archive
     * as a fault of this deployment, so it is classified separately — and counted.
     */
    @Test
    @DisplayName("a rejected configuration keeps its own exception and still counts")
    void rejectedConfigurationKeepsIts400AndCounts() throws Exception {
        var agentStore = mock(IAgentStore.class);
        when(agentStore.create(any()))
                .thenThrow(new IllegalArgumentException("hitlConfig.timeoutSeconds must be positive"));

        var thrown = assertThrows(IllegalArgumentException.class, () -> {
            try (var cdi = stubCdi(IAgentStore.class, agentStore)) {
                importService.importAgent(new ByteArrayInputStream(new byte[0]), "create", null, null, null);
            }
        });

        assertEquals("hitlConfig.timeoutSeconds must be positive", thrown.getMessage(),
                "the validation message is the whole value of a 400; wrapping it in a 500 would lose it");
        verify(metrics).importFailed();
    }

    /**
     * A strategy the service does not implement is the caller's mistake, so it
     * stays a 400 — the catch-all must not repackage it as a server fault — and it
     * is still a failed import as far as the meter is concerned.
     */
    @Test
    @DisplayName("an unknown strategy stays a 400 and still counts as a failure")
    void unknownStrategyIsA400AndCounts() throws Exception {
        var thrown = assertThrows(BadRequestException.class,
                () -> importService.importAgent(new ByteArrayInputStream(new byte[0]), "sideways", null, null, null));

        assertTrue(thrown.getMessage().contains("sideways"),
                "the message must name the strategy that was rejected, was: " + thrown.getMessage());
        verify(metrics).importFailed();
        verify(zipArchive, never()).unzip(any(InputStream.class), any(File.class));
    }

    // ==================== Helpers ====================

    private interface ArchiveContent {
        void write(File dir) throws IOException;
    }

    private void stubUnzip(ArchiveContent content) throws Exception {
        doAnswer(inv -> {
            File dir = inv.getArgument(1);
            assertTrue(dir.mkdirs() || dir.isDirectory());
            content.write(dir);
            return null;
        }).when(zipArchive).unzip(any(InputStream.class), any(File.class));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private AutoCloseable stubCdi(Class<?> storeClass, Object store) {
        var cdiMock = mockStatic(CDI.class);
        var cdi = mock(CDI.class);
        cdiMock.when(CDI::current).thenReturn(cdi);
        var instance = (Instance<Object>) mock(Instance.class);
        when(cdi.select((Class<Object>) storeClass)).thenReturn(instance);
        when(instance.get()).thenReturn(store);
        return cdiMock;
    }
}
