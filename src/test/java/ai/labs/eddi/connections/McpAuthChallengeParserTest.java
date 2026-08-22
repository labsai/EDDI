/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.connections;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every string this parser reads was written by the very server EDDI has just
 * failed to authenticate against, and the URL it hands back is one EDDI will
 * then go and fetch. So the cases worth pinning down are not only the happy
 * ones: a metadata document hosted anywhere other than the MCP server itself,
 * and metadata describing a resource that is not the server being accessed,
 * both have to come back empty rather than "probably fine".
 */
class McpAuthChallengeParserTest {

    private static final String SERVER = "https://mcp.example.com/mcp/v1";

    @ParameterizedTest
    @ValueSource(ints = {401, 403})
    @DisplayName("a 401 or 403 is an authentication challenge, not an outage")
    void recognisesAuthChallenges(int statusCode) {
        assertTrue(McpAuthChallengeParser.isAuthChallenge(statusCode));
    }

    @ParameterizedTest
    @ValueSource(ints = {200, 204, 400, 404, 429, 500, 502})
    @DisplayName("any other status is left to the circuit breaker to interpret")
    void ignoresOtherStatuses(int statusCode) {
        assertFalse(McpAuthChallengeParser.isAuthChallenge(statusCode));
    }

    @Test
    @DisplayName("a server that sent no challenge header offers nothing to fetch")
    void refusesMissingHeader() {
        assertTrue(McpAuthChallengeParser.resourceMetadataUrl(null, SERVER).isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "\t", "\n   "})
    @DisplayName("a blank challenge header offers nothing to fetch")
    void refusesBlankHeader(String wwwAuthenticate) {
        assertTrue(McpAuthChallengeParser.resourceMetadataUrl(wwwAuthenticate, SERVER).isEmpty());
    }

    @Test
    @DisplayName("nothing is fetched when the server that issued the challenge is unknown")
    void refusesMissingServerUrl() {
        String header = "Bearer resource_metadata=\"https://mcp.example.com/meta\"";
        assertTrue(McpAuthChallengeParser.resourceMetadataUrl(header, null).isEmpty());
    }

    @Test
    @DisplayName("a challenge carrying no resource_metadata parameter offers nothing to fetch")
    void refusesChallengeWithoutParameter() {
        assertTrue(McpAuthChallengeParser.resourceMetadataUrl("Bearer", SERVER).isEmpty());
        String header = "Bearer realm=\"mcp\", error=\"invalid_token\", error_description=\"expired\"";
        assertTrue(McpAuthChallengeParser.resourceMetadataUrl(header, SERVER).isEmpty());
    }

    @Test
    @DisplayName("a quoted resource_metadata url is read")
    void readsQuotedValue() {
        String header = "Bearer resource_metadata=\"https://mcp.example.com/.well-known/oauth-protected-resource\"";
        assertEquals("https://mcp.example.com/.well-known/oauth-protected-resource",
                McpAuthChallengeParser.resourceMetadataUrl(header, SERVER).orElseThrow());
    }

    @Test
    @DisplayName("an unquoted resource_metadata url is read and ends at the next comma")
    void readsUnquotedValue() {
        String alone = "Bearer resource_metadata=https://mcp.example.com/meta";
        assertEquals("https://mcp.example.com/meta", McpAuthChallengeParser.resourceMetadataUrl(alone, SERVER).orElseThrow());

        String followed = "Bearer resource_metadata=https://mcp.example.com/meta, realm=\"mcp\"";
        assertEquals("https://mcp.example.com/meta", McpAuthChallengeParser.resourceMetadataUrl(followed, SERVER).orElseThrow());
    }

    @Test
    @DisplayName("whitespace around the equals sign does not hide the parameter")
    void toleratesWhitespace() {
        String header = "Bearer   resource_metadata   =   \"https://mcp.example.com/meta\"   ";
        assertEquals("https://mcp.example.com/meta", McpAuthChallengeParser.resourceMetadataUrl(header, SERVER).orElseThrow());
    }

    @ParameterizedTest
    @ValueSource(strings = {"resource_metadata", "Resource_Metadata", "RESOURCE_METADATA"})
    @DisplayName("the parameter name is matched however the server capitalised it")
    void matchesParameterNameCaseInsensitively(String parameterName) {
        String header = "Bearer " + parameterName + "=\"https://mcp.example.com/meta\"";
        assertEquals("https://mcp.example.com/meta", McpAuthChallengeParser.resourceMetadataUrl(header, SERVER).orElseThrow());
    }

    @Test
    @DisplayName("the parameter is found wherever it sits among the others")
    void findsParameterInAnyPosition() {
        String first = "Bearer resource_metadata=\"https://mcp.example.com/meta\", realm=\"mcp\", scope=\"read\"";
        assertEquals("https://mcp.example.com/meta", McpAuthChallengeParser.resourceMetadataUrl(first, SERVER).orElseThrow());

        String last = "Bearer realm=\"mcp\", error=\"invalid_token\", error_description=\"expired\", scope=\"read\", "
                + "resource_metadata=\"https://mcp.example.com/meta\"";
        assertEquals("https://mcp.example.com/meta", McpAuthChallengeParser.resourceMetadataUrl(last, SERVER).orElseThrow());
    }

    @Test
    @DisplayName("escaped quotes in an earlier parameter do not derail the scan")
    void toleratesEscapedQuotesInOtherParameters() {
        String header = "Bearer realm=\"say \\\"hello\\\"\", resource_metadata=\"https://mcp.example.com/meta\"";
        assertEquals("https://mcp.example.com/meta", McpAuthChallengeParser.resourceMetadataUrl(header, SERVER).orElseThrow());
    }

    @Test
    @DisplayName("an escaped quote inside the url itself leaves nothing fetchable")
    void refusesEscapedQuoteInsideValue() {
        // The scan stops at the backslash-quote rather than unescaping it, so what is
        // left is a URL ending in a stray backslash — not a legal URI, and therefore
        // not something to go and fetch on a hostile server's say-so.
        String header = "Bearer resource_metadata=\"https://mcp.example.com/pa\\\"th\"";
        assertTrue(McpAuthChallengeParser.resourceMetadataUrl(header, SERVER).isEmpty());
    }

    @Test
    @DisplayName("the first of two resource_metadata parameters wins")
    void takesFirstOfDuplicateParameters() {
        String header = "Bearer resource_metadata=\"https://mcp.example.com/first\", "
                + "resource_metadata=\"https://mcp.example.com/second\"";
        assertEquals("https://mcp.example.com/first", McpAuthChallengeParser.resourceMetadataUrl(header, SERVER).orElseThrow());
    }

    @Test
    @DisplayName("a parameter carried by a later challenge in the same header is still read")
    void readsParameterFromSecondChallenge() {
        String header = "Bearer realm=\"mcp\", DPoP resource_metadata=\"https://mcp.example.com/meta\"";
        assertEquals("https://mcp.example.com/meta", McpAuthChallengeParser.resourceMetadataUrl(header, SERVER).orElseThrow());
    }

    @Test
    @DisplayName("the authentication scheme itself is not inspected")
    void ignoresAuthenticationScheme() {
        // Origin, not scheme name, is what makes the URL safe to fetch, so a Basic
        // challenge carrying the parameter is read on the same terms as a Bearer one.
        String header = "Basic realm=\"mcp\", resource_metadata=\"https://mcp.example.com/meta\"";
        assertEquals("https://mcp.example.com/meta", McpAuthChallengeParser.resourceMetadataUrl(header, SERVER).orElseThrow());
    }

    @ParameterizedTest
    @ValueSource(strings = {"Bearer resource_metadata=\"\"", "Bearer resource_metadata="})
    @DisplayName("a resource_metadata parameter with no value offers nothing to fetch")
    void refusesEmptyValue(String wwwAuthenticate) {
        assertTrue(McpAuthChallengeParser.resourceMetadataUrl(wwwAuthenticate, SERVER).isEmpty());
    }

    @Test
    @DisplayName("a metadata url on another host is refused")
    void refusesForeignHost() {
        String header = "Bearer resource_metadata=\"https://evil.example.com/.well-known/oauth-protected-resource\"";
        assertTrue(McpAuthChallengeParser.resourceMetadataUrl(header, SERVER).isEmpty(),
                "a server may not redirect discovery to a host of its choosing");
    }

    @Test
    @DisplayName("a scheme downgrade is a different origin")
    void refusesSchemeDowngrade() {
        String header = "Bearer resource_metadata=\"http://mcp.example.com/meta\"";
        assertTrue(McpAuthChallengeParser.resourceMetadataUrl(header, SERVER).isEmpty());
    }

    @Test
    @DisplayName("a different port is a different origin")
    void refusesDifferentPort() {
        String header = "Bearer resource_metadata=\"https://mcp.example.com:8443/meta\"";
        assertTrue(McpAuthChallengeParser.resourceMetadataUrl(header, SERVER).isEmpty());
    }

    @Test
    @DisplayName("a scheme's default port written out explicitly is still the same origin")
    void foldsDefaultPorts() {
        String https = "Bearer resource_metadata=\"https://mcp.example.com:443/meta\"";
        assertEquals("https://mcp.example.com:443/meta", McpAuthChallengeParser.resourceMetadataUrl(https, SERVER).orElseThrow());

        String http = "Bearer resource_metadata=\"http://mcp.example.com:80/meta\"";
        assertEquals("http://mcp.example.com:80/meta",
                McpAuthChallengeParser.resourceMetadataUrl(http, "http://mcp.example.com/mcp").orElseThrow());
    }

    @Test
    @DisplayName("scheme and host compare case-insensitively and the url is handed back as written")
    void comparesOriginCaseInsensitively() {
        String header = "Bearer resource_metadata=\"HTTPS://MCP.EXAMPLE.COM/meta\"";
        assertEquals("HTTPS://MCP.EXAMPLE.COM/meta", McpAuthChallengeParser.resourceMetadataUrl(header, SERVER).orElseThrow());
    }

    @Test
    @DisplayName("a relative metadata url has no origin to compare and is refused")
    void refusesRelativeUrl() {
        String header = "Bearer resource_metadata=\"/.well-known/oauth-protected-resource\"";
        assertTrue(McpAuthChallengeParser.resourceMetadataUrl(header, SERVER).isEmpty());
    }

    @Test
    @DisplayName("a metadata url with no host at all is refused")
    void refusesHostlessUrl() {
        // file:// parses cleanly and would otherwise be handed to the fetcher; the
        // origin check is what stops it before SafeHttpClient ever sees it.
        String header = "Bearer resource_metadata=\"file:///etc/passwd\"";
        assertTrue(McpAuthChallengeParser.resourceMetadataUrl(header, SERVER).isEmpty());
    }

    @Test
    @DisplayName("an unparseable metadata url is refused rather than thrown at the caller")
    void refusesUnparseableMetadataUrl() {
        String header = "Bearer resource_metadata=\"https://mcp.example.com/%zz\"";
        assertTrue(McpAuthChallengeParser.resourceMetadataUrl(header, SERVER).isEmpty());
    }

    @Test
    @DisplayName("an unparseable server url is refused rather than thrown at the caller")
    void refusesUnparseableServerUrl() {
        String header = "Bearer resource_metadata=\"https://mcp.example.com/meta\"";
        assertTrue(McpAuthChallengeParser.resourceMetadataUrl(header, "https://mcp.example.com/%zz").isEmpty());
    }

    @Test
    @DisplayName("metadata advertising the base url of the server being accessed describes it")
    void acceptsMetadataDescribingTheServer() {
        assertTrue(McpAuthChallengeParser.describesResource("https://mcp.example.com", SERVER),
                "a server advertises its base url while the client addressed a path beneath it");
        assertTrue(McpAuthChallengeParser.describesResource(SERVER, SERVER));
        assertTrue(McpAuthChallengeParser.describesResource("HTTPS://MCP.EXAMPLE.COM:443/", SERVER));
    }

    @Test
    @DisplayName("metadata advertising somebody else's resource does not describe the server")
    void refusesMetadataDescribingAnotherResource() {
        assertFalse(McpAuthChallengeParser.describesResource("https://other.example.com", SERVER));
        assertFalse(McpAuthChallengeParser.describesResource("http://mcp.example.com", SERVER), "a scheme downgrade is a different origin");
        assertFalse(McpAuthChallengeParser.describesResource("https://mcp.example.com:8443", SERVER));
    }

    @Test
    @DisplayName("metadata that advertises no resource at all describes nothing")
    void refusesMissingAdvertisedResource() {
        assertFalse(McpAuthChallengeParser.describesResource(null, SERVER));
    }

    @Test
    @DisplayName("no resource can be confirmed when the server being accessed is unknown")
    void refusesMissingServerUrlWhenMatchingResource() {
        assertFalse(McpAuthChallengeParser.describesResource("https://mcp.example.com", null));
    }

    @Test
    @DisplayName("an advertised resource with no host is refused")
    void refusesHostlessAdvertisedResource() {
        assertFalse(McpAuthChallengeParser.describesResource("urn:ietf:oauth-protected-resource", SERVER));
    }

    @Test
    @DisplayName("a server url missing its scheme has no origin to compare")
    void refusesSchemelessServerUrl() {
        assertFalse(McpAuthChallengeParser.describesResource("https://mcp.example.com", "mcp.example.com/mcp/v1"));
    }

    @Test
    @DisplayName("a server url whose authority was mistyped away has no host to compare")
    void refusesServerUrlWithoutAuthority() {
        // One slash short of an authority: "https:/host/path" parses without error and
        // yields a path, no host — a config typo that must not be read as a match.
        assertFalse(McpAuthChallengeParser.describesResource("https://mcp.example.com", "https:/mcp.example.com/mcp/v1"));
    }

    @Test
    @DisplayName("an unparseable advertised resource is refused rather than thrown at the caller")
    void refusesUnparseableAdvertisedResource() {
        assertFalse(McpAuthChallengeParser.describesResource("https://mcp.example.com/%zz", SERVER));
    }
}
