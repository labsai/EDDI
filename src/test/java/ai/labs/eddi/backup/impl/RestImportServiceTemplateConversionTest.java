/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.backup.impl;

import ai.labs.eddi.backup.IZipArchive;
import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.configs.migration.IMigrationManager;
import ai.labs.eddi.configs.migration.TemplateSyntaxMigrator;
import ai.labs.eddi.datastore.serialization.JsonSerialization;
import ai.labs.eddi.engine.schedule.IScheduleStore;
import ai.labs.eddi.engine.security.spaces.ResourceAccessGuard;
import ai.labs.eddi.engine.security.spaces.SpaceContext;
import ai.labs.eddi.modules.ingestion.RagSourceIngestionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Method;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

/**
 * The import-side Thymeleaf conversion: a convertible template is converted,
 * and one whose conversion Qute would reject is imported as it was. The real
 * migrator and a real JSON serialization are used; {@code readResource} is
 * private, so it is reached by reflection.
 */
@DisplayName("RestImportService — template conversion on import")
class RestImportServiceTemplateConversionTest {

    private static final String ID = "aaaa11112222333344445555";
    private static final URI URI_OF_RESOURCE = URI.create("eddi://ai.labs.llm/llmstore/llms/" + ID + "?version=1");
    /**
     * A conditional the converter is unsure about, whose generic conversion Qute
     * rejects.
     */
    private static final String UNSURE = "[[${items.size() > 0 && (a ? b : 'x' ? 'many' : 'none'}]]";

    @TempDir
    Path dir;

    private RestImportService service;

    @BeforeEach
    void setUp() {
        service = new RestImportService(mock(IZipArchive.class), new JsonSerialization(new ObjectMapper()), mock(IMigrationManager.class),
                mock(IDocumentDescriptorStore.class), new TemplateSyntaxMigrator(), mock(StructuralMatcher.class), mock(UpgradeExecutor.class),
                mock(IScheduleStore.class), mock(BackupMetrics.class), mock(ResourceAccessGuard.class), mock(SpaceContext.class),
                mock(RagSourceIngestionService.class), true, false, Optional.empty());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> importJson(String json) throws Exception {
        Files.writeString(dir.resolve(ID + ".langchain.json"), json);
        Method read = RestImportService.class.getDeclaredMethod("readResource", URI.class, Path.class, String.class, Class.class, boolean.class);
        read.setAccessible(true);
        return (Map<String, Object>) read.invoke(service, URI_OF_RESOURCE, dir, "langchain", Map.class, false);
    }

    @Test
    @DisplayName("a convertible Thymeleaf string is converted")
    void convertibleStringIsConverted() throws Exception {
        Map<String, Object> result = importJson("{\"prompt\":\"Hello [[${properties.name}]]\",\"other\":\"x\"}");
        assertEquals("Hello {properties.name}", result.get("prompt"));
    }

    @Test
    @DisplayName("a conversion that is not valid Qute imports the original resource")
    void invalidConversionImportsTheOriginal() throws Exception {
        Map<String, Object> result = importJson("{\"prompt\":\"Hello [[${properties.name}]]\",\"bad\":\"" + UNSURE + "\"}");
        assertEquals("Hello [[${properties.name}]]", result.get("prompt"), "nothing of the resource is converted");
        assertEquals(UNSURE, result.get("bad"));
    }
}
