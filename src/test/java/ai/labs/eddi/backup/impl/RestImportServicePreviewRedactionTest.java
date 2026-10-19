/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.backup.impl;

import ai.labs.eddi.backup.IZipArchive;
import ai.labs.eddi.backup.model.ImportPreview;
import ai.labs.eddi.backup.model.ImportPreview.DiffAction;
import ai.labs.eddi.backup.model.ImportPreview.ResourceDiff;
import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.configs.migration.IMigrationManager;
import ai.labs.eddi.configs.migration.TemplateSyntaxMigrator;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.schedule.IScheduleStore;
import ai.labs.eddi.engine.security.spaces.ResourceAccessGuard;
import ai.labs.eddi.engine.security.spaces.SpaceContext;
import ai.labs.eddi.modules.ingestion.RagSourceIngestionService;
import ai.labs.eddi.secrets.sanitize.ConfigSecretRedactor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The preview endpoints admit {@code eddi-editor}, and
 * {@link StructuralMatcher} reads its targets unredacted so that an upgrade
 * restores the target's own key. What an editor gets back must still be the
 * redacted copy.
 */
class RestImportServicePreviewRedactionTest {

    private static final String TARGET_AGENT_ID = "aaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String PLAINTEXT = "sk-fixturefixturefixturefixture";

    private ResourceAccessGuard resourceAccessGuard;
    private RestImportService importService;
    private ImportPreview preview;

    @BeforeEach
    void setUp() {
        StructuralMatcher structuralMatcher = mock(StructuralMatcher.class);
        resourceAccessGuard = mock(ResourceAccessGuard.class);
        importService = new RestImportService(mock(IZipArchive.class), mock(IJsonSerialization.class), mock(IMigrationManager.class),
                mock(IDocumentDescriptorStore.class), mock(TemplateSyntaxMigrator.class), structuralMatcher, mock(UpgradeExecutor.class),
                mock(IScheduleStore.class), mock(BackupMetrics.class), resourceAccessGuard, mock(SpaceContext.class),
                mock(RagSourceIngestionService.class), true, false, Optional.empty());
        preview = new ImportPreview("src", "Source", TARGET_AGENT_ID, "Target", List.of(new ResourceDiff("s1",
                AbstractBackupService.LLM_EXT, "main", DiffAction.UPDATE, "t1", 1, "structural",
                "{\"tasks\":[{\"parameters\":{\"apiKey\":\"${vault:REDACTED}\"}}]}",
                "{\"tasks\":[{\"parameters\":{\"apiKey\":\"" + PLAINTEXT + "\"}}]}", 0)));
        when(structuralMatcher.buildPreview(any(), eq(TARGET_AGENT_ID), eq(true))).thenReturn(preview);
    }

    @Test
    @DisplayName("an editor's upgrade preview shows the marker, not the target's key")
    void editorPreviewIsRedacted() {
        when(resourceAccessGuard.isAdmin()).thenReturn(false);

        ImportPreview seen = importService.previewImport(new ByteArrayInputStream(new byte[0]), TARGET_AGENT_ID);

        String target = seen.resources().get(0).targetContent();
        assertFalse(target.contains(PLAINTEXT), target);
        assertTrue(target.contains(ConfigSecretRedactor.REDACTED), target);
    }

    @Test
    @DisplayName("an administrator's preview is the matcher's, as stored")
    void adminPreviewIsUnredacted() {
        when(resourceAccessGuard.isAdmin()).thenReturn(true);

        assertSame(preview, importService.previewImport(new ByteArrayInputStream(new byte[0]), TARGET_AGENT_ID));
    }
}
