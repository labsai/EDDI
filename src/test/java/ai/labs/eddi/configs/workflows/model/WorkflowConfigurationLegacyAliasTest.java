/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.workflows.model;

import ai.labs.eddi.datastore.serialization.SerializationCustomizer;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A v5 workflow document must still load with its steps.
 * <p>
 * This is the one failure mode the project's hard backward-compatibility rule
 * exists to prevent, and the one it is worst at surfacing: the payload key
 * moved from {@code packageExtensions} (5.x) to {@code workflowSteps} (6.x),
 * and with {@code FAIL_ON_UNKNOWN_PROPERTIES=false} — deliberately pinned false
 * in {@link SerializationCustomizer} — an unaliased old key is dropped without
 * a word. The workflow then loads with ZERO steps,
 * {@code WorkflowStoreClientLibrary} builds a valid executable workflow out of
 * an empty list, and the agent <em>deploys successfully</em> and runs no
 * parser, no behaviour rules and no output for the rest of its life. No
 * exception, no warning, no failed deployment: only a customer whose upgraded
 * agent answers nothing.
 * <p>
 * The fixture is the repo's own v5 agent, i.e. the exact wire shape a 5.x
 * export ZIP carries and {@code RestImportService} still accepts.
 */
@DisplayName("WorkflowConfiguration — v5 legacy key")
class WorkflowConfigurationLegacyAliasTest {

    private static final Path V5_WORKFLOW = Path.of("src", "test", "resources", "tests", "useCases",
            "5af59eca9bcb0f31b4b3b938", "1", "5af59eca9bcb0f31b4b3b938.package.json");

    /** Exactly how stored documents are read: the shared, lenient recipe. */
    private static ObjectMapper productionMapper() {
        return SerializationCustomizer.configureObjectMapper(new ObjectMapper(), false);
    }

    @Test
    @DisplayName("a v5 .package.json loads all of its steps, not an empty workflow")
    void v5PackageExtensionsLoadsItsSteps() throws IOException {
        String body = Files.readString(V5_WORKFLOW, StandardCharsets.UTF_8);
        assertTrue(body.contains("\"packageExtensions\""), "fixture no longer carries the v5 key — this test would "
                + "pass while guarding nothing");

        var config = productionMapper().readValue(body, WorkflowConfiguration.class);

        assertEquals(6, config.getWorkflowSteps().size(),
                "a v5 workflow deserialized to an empty step list: the agent deploys and then does nothing at all");
        assertEquals("eddi://ai.labs.parser", config.getWorkflowSteps().getFirst().getType().toString(),
                "step order must survive the alias");
    }

    @Test
    @DisplayName("the intermediate v6 development names stay readable too")
    void intermediateNamesStillLoad() throws IOException {
        var mapper = productionMapper();
        for (String key : new String[]{"workflowExtensions", "pipelineSteps"}) {
            String body = """
                    { "%s": [ { "type": "eddi://ai.labs.parser", "extensions": {}, "config": {} } ] }
                    """.formatted(key);
            var config = mapper.readValue(body, WorkflowConfiguration.class);
            assertEquals(1, config.getWorkflowSteps().size(), "'" + key + "' must remain readable");
        }
    }

    @Test
    @DisplayName("writes stay on the v6 name — the alias is read-only")
    void writesUseTheCanonicalName() throws IOException {
        var mapper = productionMapper();
        String body = Files.readString(V5_WORKFLOW, StandardCharsets.UTF_8);

        String reSerialized = mapper.writeValueAsString(mapper.readValue(body, WorkflowConfiguration.class));

        assertTrue(reSerialized.contains("\"workflowSteps\""), "must be written back under the v6 key");
        assertFalse(reSerialized.contains("packageExtensions"), "the retired key must not travel back into storage");
    }
}
