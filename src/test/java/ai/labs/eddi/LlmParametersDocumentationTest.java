/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code docs/llm-parameters.md} is generated from
 * {@code model-parameters.json} by {@code scripts/gen-llm-parameters-doc.py}.
 * This fails when the page has fallen behind the catalog, so a parameter added
 * to a provider cannot ship undocumented.
 */
class LlmParametersDocumentationTest {

    private static final Path DOC = Path.of("docs", "llm-parameters.md");
    private static final String REGENERATE = " — run `python scripts/gen-llm-parameters-doc.py` and commit docs/llm-parameters.md";

    @Test
    void everyProviderAndParameterOfTheCatalogIsDocumented() throws IOException {
        assertTrue(Files.isRegularFile(DOC), "expected the working directory to be the project root; " + DOC.toAbsolutePath() + " not found");
        String doc = Files.readString(DOC, StandardCharsets.UTF_8);

        JsonNode providers;
        try (InputStream in = getClass().getResourceAsStream("/llm/model-parameters.json")) {
            providers = new ObjectMapper().readTree(in).get("providers");
        }

        List<String> missing = new ArrayList<>();
        providers.fieldNames().forEachRemaining(id -> {
            JsonNode provider = providers.get(id);
            if (!doc.contains("## " + provider.get("displayName").asText() + " (`" + id + "`)")) {
                missing.add("provider " + id);
            }
            for (JsonNode param : provider.get("params")) {
                String row = "| `" + param.get("key").asText() + "`";
                if (!doc.contains(row)) {
                    missing.add(id + "." + param.get("key").asText());
                }
            }
        });

        assertTrue(missing.isEmpty(), "docs/llm-parameters.md is missing " + missing + REGENERATE);
    }

    @Test
    void theDocumentationIsLinkedFromTheTableOfContents() throws IOException {
        String summary = Files.readString(Path.of("docs", "SUMMARY.md"), StandardCharsets.UTF_8);
        assertTrue(summary.contains("(llm-parameters.md)"), "docs/SUMMARY.md must link llm-parameters.md");
    }
}
