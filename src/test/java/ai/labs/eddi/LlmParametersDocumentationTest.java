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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
    void everyProviderParameterDescriptionAndRuleOfTheCatalogIsDocumentedInItsOwnSection() throws IOException {
        assertTrue(Files.isRegularFile(DOC), "expected the working directory to be the project root; " + DOC.toAbsolutePath() + " not found");
        String doc = Files.readString(DOC, StandardCharsets.UTF_8);

        JsonNode providers;
        try (InputStream in = getClass().getResourceAsStream("/llm/model-parameters.json")) {
            providers = new ObjectMapper().readTree(in).get("providers");
        }

        List<String> problems = new ArrayList<>();
        providers.fieldNames().forEachRemaining(id -> {
            JsonNode provider = providers.get(id);
            String heading = "## " + provider.get("displayName").asText() + " (`" + id + "`)";
            int from = doc.indexOf(heading);
            if (from < 0) {
                problems.add("provider " + id + " has no section");
                return;
            }
            int next = doc.indexOf("\n## ", from + heading.length());
            String section = next < 0 ? doc.substring(from) : doc.substring(from, next);

            // a compat provider renders its parent's parameters too (without the parent's
            // rules)
            Map<String, JsonNode> params = new LinkedHashMap<>();
            Map<String, Boolean> inheritedOnly = new LinkedHashMap<>();
            if (provider.hasNonNull("inherits")) {
                for (JsonNode inherited : providers.get(provider.get("inherits").asText()).get("params")) {
                    params.put(inherited.get("key").asText(), inherited);
                    inheritedOnly.put(inherited.get("key").asText(), true);
                }
            }
            for (JsonNode own : provider.get("params")) {
                params.put(own.get("key").asText(), own);
                inheritedOnly.put(own.get("key").asText(), false);
            }

            params.forEach((key, param) -> {
                String where = id + "." + key;
                if (!section.contains("| `" + key + "`")) {
                    problems.add(where + " has no row in its section");
                    return;
                }
                String description = param.get("description").asText().replace("|", "\\|");
                if (!section.contains(description)) {
                    problems.add(where + " description is stale");
                }
                if (!inheritedOnly.get(key) && param.has("rules")) {
                    for (JsonNode rule : param.get("rules")) {
                        if (!section.contains(rule.get("note").asText())) {
                            problems.add(where + " rule note is stale: " + rule.get("note").asText());
                        }
                    }
                }
            });
        });

        assertTrue(problems.isEmpty(), "docs/llm-parameters.md is out of date: " + problems + REGENERATE);
    }

    @Test
    void theDocumentationIsLinkedFromTheTableOfContents() throws IOException {
        String summary = Files.readString(Path.of("docs", "SUMMARY.md"), StandardCharsets.UTF_8);
        assertTrue(summary.contains("(llm-parameters.md)"), "docs/SUMMARY.md must link llm-parameters.md");
    }
}
