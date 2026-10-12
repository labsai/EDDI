/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code http.server.requests} tags each request with its URI, and Micrometer
 * denies every new URI once {@code max-uri-tags} is reached — those requests
 * get no metrics at all. Static files are tagged by their literal path, so the
 * ~800 content-hashed Manager chunks under {@code /assets} filled the old cap
 * of 200 within one Manager session, and the REST calls made afterwards went
 * unmeasured.
 * <p>
 * The patterns are evaluated exactly as Quarkus does — split at the first
 * {@code =}, trimmed, whole-path {@link java.util.regex.Matcher#matches()} —
 * and read from the source tree for the same reason as {@link CspPolicyTest}.
 */
@DisplayName("HTTP server metrics: bounded URI tags")
class HttpMetricsUriTagsConfigTest {

    /** Room for the UI shells, the folded static directories and new endpoints. */
    private static final int HEADROOM = 100;

    private static Path basedir() {
        return Path.of(System.getProperty("basedir", "."));
    }

    private static Properties applicationProperties() throws Exception {
        var path = basedir().resolve("src/main/resources/application.properties");
        assertTrue(Files.isRegularFile(path), "Expected the application config at " + path);
        var properties = new Properties();
        try (Reader in = Files.newBufferedReader(path)) {
            properties.load(in);
        }
        return properties;
    }

    private static Map<Pattern, String> matchPatterns() throws Exception {
        var raw = applicationProperties().getProperty("quarkus.micrometer.binder.http-server.match-patterns");
        assertTrue(raw != null && !raw.isBlank(), "match-patterns must be set");
        Map<Pattern, String> patterns = new LinkedHashMap<>();
        for (var entry : raw.split(",")) {
            int eq = entry.indexOf('=');
            assertTrue(eq > 0, "every entry must be pattern=replacement: " + entry);
            patterns.put(Pattern.compile(entry.substring(0, eq).trim()), entry.substring(eq + 1).trim());
        }
        return patterns;
    }

    private static String tagFor(Map<Pattern, String> patterns, String path) {
        for (var entry : patterns.entrySet()) {
            if (entry.getKey().matcher(path).matches()) {
                return entry.getValue();
            }
        }
        return null;
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"/assets/index-C_2WVivV.js", "/assets/chunk-OB3PAWPO-r3btFb1u.js", "/assets/index-D1x2y3z4.css",
            "/fonts/inter-latin-400-normal.woff2", "/scripts/js/chat-ui.DRb9IR7o.js", "/scripts/css/chat-ui.D_l0VG5z.css",
            "/scripts/js/landing-redirect.js", "/img/favicon.ico"})
    @DisplayName("each static file folds into its directory's single tag")
    void staticFilesFoldIntoOneTagPerDirectory(String path) throws Exception {
        var directory = path.substring(0, path.indexOf('/', 1));
        assertEquals(directory + "/{file}", tagFor(matchPatterns(), path));
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"/", "/manage", "/chat", "/agentstore/agents/descriptors", "/agents/pending-approvals", "/assets",
            "/assetstore/x", "/v1/models"})
    @DisplayName("other paths are left to their REST template")
    void otherPathsAreUntouched(String path) throws Exception {
        assertNull(tagFor(matchPatterns(), path));
    }

    @Test
    @DisplayName("the URI-tag cap exceeds every API path in the OpenAPI snapshot, with headroom")
    void capCoversTheApi() throws Exception {
        var snapshot = basedir().resolve("ui/manager/src/test/mocks/openapi-operations.json");
        assertTrue(Files.isRegularFile(snapshot), "Expected the OpenAPI operations snapshot at " + snapshot);
        Set<String> paths = new HashSet<>();
        for (var operation : new ObjectMapper().readTree(snapshot.toFile()).get("operations")) {
            paths.add(operation.asText().split(" ", 2)[1]);
        }
        assertTrue(paths.size() > 200, "the snapshot looks truncated: " + paths.size() + " paths");

        int cap = Integer.parseInt(applicationProperties().getProperty("quarkus.micrometer.binder.http-server.max-uri-tags"));
        assertTrue(cap >= paths.size() + HEADROOM, "max-uri-tags=" + cap + " leaves less than " + HEADROOM + " tags of headroom over the "
                + paths.size() + " API paths; raise it, or URIs first seen after the cap get no request metrics");
    }
}
