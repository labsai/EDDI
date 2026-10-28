/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs;

import ai.labs.eddi.configs.channels.rest.RestChannelIntegrationStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A response header a cross-origin browser client must read is invisible to it
 * unless {@code quarkus.http.cors.exposed-headers} names it:
 * {@code headers.get(...)} returns null and nothing says why.
 */
@DisplayName("CORS exposed headers")
class CorsExposedHeadersConfigTest {

    private static final String EXPOSED_HEADERS = "quarkus.http.cors.exposed-headers";

    /**
     * Read from the source tree: {@code src/test/resources/application.properties}
     * shadows the main file on the test classpath.
     */
    private static List<String> exposedHeaders() throws Exception {
        var path = Path.of(System.getProperty("basedir", ".")).resolve("src/main/resources/application.properties");
        assertTrue(Files.isRegularFile(path), "Expected the application config at " + path);
        var properties = new Properties();
        try (Reader in = Files.newBufferedReader(path)) {
            properties.load(in);
        }
        String value = properties.getProperty(EXPOSED_HEADERS);
        assertNotNull(value, EXPOSED_HEADERS + " is not set");
        return Arrays.stream(value.split(",")).map(String::trim).map(String::toLowerCase).toList();
    }

    @Test
    @DisplayName("every header a browser client of EDDI reads is exposed")
    void exposesWhatClientsRead() throws Exception {
        List<String> exposed = exposedHeaders();
        for (String header : List.of("Location", "Mcp-Session-Id", "Retry-After", RestChannelIntegrationStore.WARNINGS_HEADER)) {
            assertTrue(exposed.contains(header.toLowerCase()), header + " is missing from " + EXPOSED_HEADERS + ": " + exposed);
        }
    }
}
