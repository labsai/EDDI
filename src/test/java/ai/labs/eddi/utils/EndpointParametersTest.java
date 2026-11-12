/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.utils;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EndpointParametersTest {

    @ParameterizedTest
    @ValueSource(strings = {"baseUrl", "baseURL", "base_url", "serverUrl", "apiUrl", "proxyUrl", "url", "targetServerUrl", "uri", "endpoint",
            "ENDPOINT", "apiEndpoint", "apiBase", "api_base", "host", "hostname"})
    void namesAnEndpoint(String key) {
        assertTrue(EndpointParameters.isEndpointKey(key), key);
    }

    @ParameterizedTest
    @ValueSource(strings = {"apiKey", "modelName", "apiVersion", "path", "region", "serverName", "allowUrlContext", "urls", "hosts", ""})
    void namesNoEndpoint(String key) {
        assertFalse(EndpointParameters.isEndpointKey(key), key);
    }

    @Test
    void nullIsNoEndpoint() {
        assertFalse(EndpointParameters.isEndpointKey(null));
    }
}
