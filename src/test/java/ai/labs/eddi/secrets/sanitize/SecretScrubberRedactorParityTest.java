/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.secrets.sanitize;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The export must never hand out what an editor's {@code GET} hides. The read
 * redactor ({@link ConfigSecretRedactor}) hides a value by its field name
 * alone; the export scrubber judged several of those names by entropy and
 * shape, so a low-entropy literal under {@code nonAzureApiKey} was redacted on
 * GET and exported in clear.
 */
@DisplayName("SecretScrubber — at least as strict by name as ConfigSecretRedactor")
class SecretScrubberRedactorParityTest {

    /** Low entropy and no known shape: only a name rule can catch it. */
    private static final String LITERAL = "my-litellm-master-key";

    private final ObjectMapper mapper = new ObjectMapper();
    private final SecretScrubber scrubber = new SecretScrubber(mapper);

    private boolean exportedInClear(String parent, String field) throws Exception {
        ObjectNode document = mapper.createObjectNode();
        ObjectNode container = document.putObject(parent);
        container.put(field, LITERAL);
        String scrubbed = mapper.readTree(scrubber.scrubJson(mapper.writeValueAsString(document))).at("/" + parent + "/" + field).asText();
        return LITERAL.equals(scrubbed);
    }

    @Test
    @DisplayName("the reported case: parameters.nonAzureApiKey is not exported in clear")
    void nonAzureApiKey() throws Exception {
        assertTrue(ConfigSecretRedactor.isCredentialName("nonAzureApiKey"));
        assertFalse(exportedInClear("parameters", "nonAzureApiKey"));
    }

    @Test
    @DisplayName("every whole credential name the read redactor hides is hidden by the export")
    void wholeNames() throws Exception {
        List<String> leaked = new ArrayList<>();
        for (String name : ConfigSecretRedactor.CREDENTIAL_NAMES) {
            if (exportedInClear("parameters", name)) {
                leaked.add(name);
            }
        }
        assertTrue(leaked.isEmpty(), "exported in clear: " + leaked);
    }

    @Test
    @DisplayName("every credential name ending the read redactor hides is hidden by the export")
    void suffixes() throws Exception {
        List<String> leaked = new ArrayList<>();
        for (String suffix : ConfigSecretRedactor.CREDENTIAL_SUFFIXES) {
            String name = "vendor" + Character.toUpperCase(suffix.charAt(0)) + suffix.substring(1);
            if (exportedInClear("parameters", name)) {
                leaked.add(name);
            }
        }
        assertTrue(leaked.isEmpty(), "exported in clear: " + leaked);
    }

    @Test
    @DisplayName("every credential query-parameter name is hidden inside a queryParams map")
    void queryParameterNames() throws Exception {
        List<String> leaked = new ArrayList<>();
        for (String name : ConfigSecretRedactor.CREDENTIAL_QUERY_NAMES) {
            if (exportedInClear("queryParams", name)) {
                leaked.add(name);
            }
        }
        for (String name : List.of("subscriptionKey", "accessKey", "appKey", "licenseKey", "clientKey", "signingKey", "sig")) {
            if (exportedInClear("queryParams", name)) {
                leaked.add(name);
            }
        }
        assertTrue(leaked.isEmpty(), "exported in clear: " + leaked);
    }

    @Test
    @DisplayName("a numeric credential in an embedded query or header map is hidden by both the read and the export")
    void numericEntriesOfEmbeddedMaps() throws Exception {
        String config = """
                {"parameters":{"customQueryParams":"{\\"sig\\":12345678,\\"page\\":2}",
                  "customHeaders":"{\\"X-Api-Key\\":87654321,\\"X-Retries\\":3}"}}""";

        JsonNode exported = mapper.readTree(scrubber.scrubJson(config)).at("/parameters");
        JsonNode read = mapper.readTree(ConfigSecretRedactor.redactJson(config, Set.of())).at("/parameters");

        for (JsonNode parameters : List.of(exported, read)) {
            String query = parameters.get("customQueryParams").asText();
            String headers = parameters.get("customHeaders").asText();
            assertFalse(query.contains("12345678"), query);
            assertFalse(headers.contains("87654321"), headers);
            assertTrue(query.contains("\"page\":2"), query);
            assertTrue(headers.contains("\"X-Retries\":3"), headers);
        }
    }

    @Test
    @DisplayName("a *Key literal in a key-named model-parameter map is hidden by both; an LLM task's promptCacheKey by neither")
    void keyNamedParameterMaps() throws Exception {
        String config = """
                {"userMemoryConfig":{"dream":{"parameters":{"accessKey":"plain-access","subscriptionKey":"plainsub"}}},
                 "lessonConfig":{"consolidation":{"parameters":{"accessKey":"plain-access"}}},
                 "tasks":[{"parameters":{"promptCacheKey":"tenant-a"}}]}""";

        for (String json : List.of(scrubber.scrubJson(config), ConfigSecretRedactor.redactJson(config, Set.of()))) {
            JsonNode tree = mapper.readTree(json);
            assertFalse(json.contains("plain-access") || json.contains("plainsub"), json);
            assertEquals("tenant-a", tree.at("/tasks/0/parameters/promptCacheKey").asText(), json);
        }
    }

    /**
     * Reads {@code /parameters/<field>} of {@code json} after export and after the
     * read redaction.
     */
    private List<String> exportedAndRead(String field, String value) throws Exception {
        ObjectNode document = mapper.createObjectNode();
        document.putObject("parameters").put(field, value);
        String json = mapper.writeValueAsString(document);
        return List.of(mapper.readTree(scrubber.scrubJson(json)).at("/parameters/" + field).asText(),
                mapper.readTree(ConfigSecretRedactor.redactJson(json, Set.of())).at("/parameters/" + field).asText());
    }

    @Test
    @DisplayName("a reference under a newly recognised credential name stays legible on read and on export")
    void referencesStayLegible() throws Exception {
        for (String field : List.of("nonAzureApiKey", "connectionString", "secretAccessKey", "vendorApiKey")) {
            for (String reference : List.of("${vars.proxyKey}", "${vars:proxyKey}", "{properties.proxyKey}", "Bearer ${vars.proxyKey}")) {
                assertEquals(List.of(reference, reference), exportedAndRead(field, reference), field + " = " + reference);
            }
        }
    }

    @Test
    @DisplayName("a literal beside a reference is masked on read and on export alike")
    void mixedLiteralAndReferenceIsMasked() throws Exception {
        for (String mixed : List.of("my-litellm-master-key ${vars.proxyKey}", "prefix-{properties.token}")) {
            List<String> both = exportedAndRead("nonAzureApiKey", mixed);
            assertEquals(SecretScrubber.REDACTED, both.get(0), mixed);
            assertEquals(ConfigSecretRedactor.REDACTED, both.get(1), mixed);
        }
    }

    @Test
    @DisplayName("a query-map credential parameter holding a template stays legible on both paths")
    void queryParameterReference() throws Exception {
        ObjectNode document = mapper.createObjectNode();
        document.putObject("queryParams").put("sig", "{properties.sig}");
        String json = mapper.writeValueAsString(document);

        assertEquals("{properties.sig}", mapper.readTree(scrubber.scrubJson(json)).at("/queryParams/sig").asText());
        assertEquals("{properties.sig}", mapper.readTree(ConfigSecretRedactor.redactJson(json, Set.of())).at("/queryParams/sig").asText());
    }

    @Test
    @DisplayName("a query-parameter name is not a credential outside a query map")
    void queryNamesStayLocal() throws Exception {
        // "key" names an identifier everywhere else (a map key, a lesson key); the
        // redactor treats it as a credential only inside queryParams, and so does the
        // export, so the round trip stays lossless.
        ObjectNode document = mapper.createObjectNode();
        document.putObject("settings").put("key", "customer");
        String scrubbed = mapper.readTree(scrubber.scrubJson(mapper.writeValueAsString(document))).at("/settings/key").asText();
        assertEquals("customer", scrubbed);
    }
}
