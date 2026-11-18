/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.slack;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@code eddi.slack.api-base-url}: every bot token is sent to this host, so the
 * value is checked when the bean is created and a bad one stops the startup
 * instead of surfacing as a failed post later.
 */
@DisplayName("eddi.slack.api-base-url")
class SlackApiBaseUrlTest {

    @Test
    @DisplayName("the default is the real Slack API")
    void defaultIsSlack() {
        assertEquals("https://slack.com/api/", SlackConfig.DEFAULT_API_BASE_URL);
        assertEquals("https://slack.com/api/", SlackConfig.validateApiBaseUrl(SlackConfig.DEFAULT_API_BASE_URL));
        assertEquals("https://slack.com/api/", new SlackConfig(60, 300, 3, 500L).getApiBaseUrl());
    }

    @Test
    @DisplayName("a missing trailing slash is added, so a method name can be appended")
    void trailingSlashIsAdded() {
        assertEquals("https://slack.example.org/api/", SlackConfig.validateApiBaseUrl("https://slack.example.org/api"));
        assertEquals("https://slack.example.org/api/", SlackConfig.validateApiBaseUrl("  https://slack.example.org/api/ "));
    }

    @ParameterizedTest(name = "loopback over plain http is accepted: {0}")
    @ValueSource(strings = {"http://localhost:8089/api/", "http://127.0.0.1:8089/api/", "http://[::1]:8089/api/",
            "http://127.1.2.3/api/", "http://LOCALHOST/api/", "http://[0:0:0:0:0:0:0:1]/api/"})
    void loopbackMayUseHttp(String url) {
        assertEquals(url, SlackConfig.validateApiBaseUrl(url));
    }

    @ParameterizedTest(name = "refused: {0}")
    @ValueSource(strings = {
            // a bot token over cleartext to anywhere but this machine
            "http://slack.com/api/", "http://slack.example.org/api/", "http://10.0.0.5/api/",
            // not a base URL for the Web API
            "ftp://slack.com/api/", "file:///etc/passwd", "slack.com/api/", "https://", "",
            // credentials, query or fragment in the URL would travel with every call or be
            // dropped by it
            "https://user:pass@slack.com/api/", "https://slack.com/api/?x=1", "https://slack.com/api/#frag",
            // looks like loopback and is not: a name, or no valid IPv4 literal (the
            // pattern this replaced took 127.999.999.999 for one)
            "http://localhost.evil.example/api/", "http://127.0.0.1.evil.example/api/", "http://127.999.999.999/api/",
            "http://0177.0.0.1/api/",
            // a port outside the TCP range
            "https://slack.example.org:99999/api/"})
    void everythingElseIsRefused(String url) {
        assertThrows(IllegalArgumentException.class, () -> SlackConfig.validateApiBaseUrl(url));
    }

    @Test
    @DisplayName("loopback is judged by the JDK's parse of a literal, never by resolving a name")
    void loopbackIsALiteral() {
        assertEquals(true, SlackConfig.isLiteralLoopback("127.0.0.1"));
        assertEquals(true, SlackConfig.isLiteralLoopback("[::1]"));
        assertEquals(false, SlackConfig.isLiteralLoopback("127.999.999.999"));
        assertEquals(false, SlackConfig.isLiteralLoopback("10.0.0.1"));
        assertEquals(false, SlackConfig.isLiteralLoopback("localhost.example"));
    }

    @Test
    @DisplayName("a bad value fails when the configuration is built, naming the property and not echoing credentials")
    void constructorRefusesABadValue() {
        var config = new SlackConfig(60, 300, 3, 500L);
        var e = assertThrows(IllegalArgumentException.class,
                () -> config.withApiBaseUrl("http://user:secret@slack.example.org/api/"));
        assertEquals(true, e.getMessage().contains("eddi.slack.api-base-url"));
        assertEquals(false, e.getMessage().contains("secret"));
    }

    @Test
    @DisplayName("the property's own startup check validates it the same way")
    void startupCheckRefusesABadValue() {
        var config = new SlackConfig(60, 300, 3, 500L);
        config.apiBaseUrl = "http://slack.example.org/api/";
        assertThrows(IllegalArgumentException.class, config::checkApiBaseUrl);

        config.apiBaseUrl = "https://proxy.example.org";
        config.checkApiBaseUrl();
        assertEquals("https://proxy.example.org/", config.getApiBaseUrl(), "no path is warned about, not refused");
    }
}
