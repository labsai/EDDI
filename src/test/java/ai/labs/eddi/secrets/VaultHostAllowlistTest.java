/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.secrets;

import ai.labs.eddi.secrets.model.SecretMetadata;
import ai.labs.eddi.secrets.model.SecretReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link VaultHostAllowlist}: the per-secret destination list, checked where a
 * {@code ${vault:…}} reference is resolved for an outbound request.
 */
class VaultHostAllowlistTest {

    private static final SecretReference OPENAI = new SecretReference("default", "openai-key");
    private static final String OPENAI_REF = "${vault:openai-key}";

    private ISecretProvider provider;
    private VaultHostAllowlist allowlist;

    @BeforeEach
    void setUp() {
        provider = mock(ISecretProvider.class);
        allowlist = new VaultHostAllowlist(provider);
    }

    private void secretAllowedAt(List<String> hosts) throws Exception {
        when(provider.getMetadata(OPENAI)).thenReturn(
                new SecretMetadata("default", "openai-key", Instant.EPOCH, null, null, null, "OpenAI", List.of("*"), hosts));
    }

    @Nested
    @DisplayName("validatedHosts")
    class ValidatedHosts {

        @Test
        @DisplayName("entries are trimmed, lower-cased and de-duplicated; null stays null")
        void normalises() {
            assertEquals(List.of("api.openai.com", "api.example.com:8443"),
                    VaultHostAllowlist.validatedHosts(List.of(" API.OpenAI.com ", "api.example.com:8443", "api.openai.com")));
            assertNull(VaultHostAllowlist.validatedHosts(null));
            assertEquals(List.of(), VaultHostAllowlist.validatedHosts(List.of()));
        }

        @Test
        @DisplayName("a scheme, a path, a wildcard, a blank or a bad port is refused by name")
        void refusesShapesThatAreNotAHost() {
            for (String bad : List.of("https://api.openai.com", "api.openai.com/v1", "*.openai.com", " ", "api.openai.com:0",
                    "api.openai.com:70000", "api.openai.com:99999999999", "api openai.com")) {
                var error = assertThrows(IllegalArgumentException.class, () -> VaultHostAllowlist.validatedHosts(List.of(bad)), bad);
                assertTrue(error.getMessage().contains("allowedHosts"), error.getMessage());
            }
        }

        @Test
        @DisplayName("the list is bounded")
        void bounded() {
            var tooMany = IntStream.range(0, VaultHostAllowlist.MAX_ALLOWED_HOSTS + 1).mapToObj(i -> "h" + i + ".example.com")
                    .toList();
            assertThrows(IllegalArgumentException.class, () -> VaultHostAllowlist.validatedHosts(tooMany));
        }
    }

    @Nested
    @DisplayName("requireAllowed")
    class RequireAllowed {

        @Test
        @DisplayName("a secret without a list may go anywhere — the shape of every secret stored before the list existed")
        void unrestrictedSecretGoesAnywhere() throws Exception {
            secretAllowedAt(null);
            assertDoesNotThrow(() -> allowlist.requireAllowed(List.of(OPENAI_REF), "https://attacker.example/v1", "LLM provider 'openai'"));
            secretAllowedAt(List.of());
            assertDoesNotThrow(() -> allowlist.requireAllowed(List.of(OPENAI_REF), "https://attacker.example/v1", "LLM provider 'openai'"));
        }

        @Test
        @DisplayName("the configured host passes, on the scheme-default port or an explicit one, whatever the case")
        void allowedHostPasses() throws Exception {
            secretAllowedAt(List.of("api.openai.com"));
            assertDoesNotThrow(() -> allowlist.requireAllowed(List.of(OPENAI_REF), "https://API.openai.com/v1/chat", "LLM provider 'openai'"));
            assertDoesNotThrow(() -> allowlist.requireAllowed(List.of(OPENAI_REF), "https://api.openai.com:8443/v1", "LLM provider 'openai'"));
            assertDoesNotThrow(() -> allowlist.requireAllowed(List.of(OPENAI_REF), "api.openai.com", "LLM provider 'openai'"));
        }

        @Test
        @DisplayName("host:port admits only that port — explicit or the scheme default")
        void hostPortIsExact() throws Exception {
            secretAllowedAt(List.of("api.openai.com:443"));
            assertDoesNotThrow(() -> allowlist.requireAllowed(List.of(OPENAI_REF), "https://api.openai.com/v1", "x"));
            assertDoesNotThrow(() -> allowlist.requireAllowed(List.of(OPENAI_REF), "https://api.openai.com:443/v1", "x"));
            assertThrows(VaultHostAllowlist.HostNotAllowedException.class,
                    () -> allowlist.requireAllowed(List.of(OPENAI_REF), "https://api.openai.com:8443/v1", "x"));
            assertThrows(VaultHostAllowlist.HostNotAllowedException.class,
                    () -> allowlist.requireAllowed(List.of(OPENAI_REF), "http://api.openai.com/v1", "x"));
        }

        @Test
        @DisplayName("another host is refused with the reference, the destination and the list in the message — never a value")
        void otherHostRefused() throws Exception {
            secretAllowedAt(List.of("api.openai.com"));

            var error = assertThrows(VaultHostAllowlist.HostNotAllowedException.class,
                    () -> allowlist.requireAllowed(List.of(OPENAI_REF), "https://attacker.example/v1", "LLM provider 'openai'"));

            assertTrue(error.getMessage().contains(OPENAI_REF), error.getMessage());
            assertTrue(error.getMessage().contains("attacker.example:443"), error.getMessage());
            assertTrue(error.getMessage().contains("api.openai.com"), error.getMessage());
            assertTrue(error.getMessage().contains("/grant"), "the fix must be named: " + error.getMessage());
        }

        @Test
        @DisplayName("no suffix or subdomain matching: example.com admits neither api.example.com nor example.com.attacker.net")
        void noSuffixMatching() throws Exception {
            secretAllowedAt(List.of("example.com"));
            assertThrows(VaultHostAllowlist.HostNotAllowedException.class,
                    () -> allowlist.requireAllowed(List.of(OPENAI_REF), "https://api.example.com/", "x"));
            assertThrows(VaultHostAllowlist.HostNotAllowedException.class,
                    () -> allowlist.requireAllowed(List.of(OPENAI_REF), "https://example.com.attacker.net/", "x"));
        }

        @Test
        @DisplayName("an unparseable destination is refused for a restricted secret")
        void unparseableDestinationRefused() throws Exception {
            secretAllowedAt(List.of("api.openai.com"));
            assertThrows(VaultHostAllowlist.HostNotAllowedException.class, () -> allowlist.requireAllowed(List.of(OPENAI_REF), "http://[bad", "x"));
            assertThrows(VaultHostAllowlist.HostNotAllowedException.class, () -> allowlist.requireAllowed(List.of(OPENAI_REF), "", "x"));
        }

        @Test
        @DisplayName("a provider failure is closed: the secret is not sent because its list is unknown")
        void providerFailureIsClosed() throws Exception {
            when(provider.getMetadata(OPENAI)).thenThrow(new ISecretProvider.SecretProviderException("db down"));
            assertThrows(VaultHostAllowlist.HostNotAllowedException.class,
                    () -> allowlist.requireAllowed(List.of(OPENAI_REF), "https://api.openai.com/v1", "x"));
        }

        @Test
        @DisplayName("a missing secret and a non-reference are left to the resolver")
        void missingSecretAndNonReferencesAreSkipped() throws Exception {
            when(provider.getMetadata(OPENAI)).thenThrow(new ISecretProvider.SecretNotFoundException("missing"));
            assertDoesNotThrow(() -> allowlist.requireAllowed(List.of(OPENAI_REF, "not-a-reference"), "https://anywhere.example/", "x"));
            assertDoesNotThrow(() -> allowlist.requireAllowed((Iterable<String>) null, "https://anywhere.example/", "x"));
        }

        @Test
        @DisplayName("the map form checks every reference found in the values")
        void mapFormFindsReferences() throws Exception {
            secretAllowedAt(List.of("api.openai.com"));
            Map<String, String> params = Map.of("apiKey", OPENAI_REF, "modelName", "gpt-x", "baseUrl", "https://attacker.example/v1");

            assertThrows(VaultHostAllowlist.HostNotAllowedException.class,
                    () -> allowlist.requireAllowed(params, params.get("baseUrl"), "LLM provider 'openai'"));
            assertDoesNotThrow(() -> allowlist.requireAllowed(Map.of("modelName", "gpt-x"), "https://attacker.example/v1", "x"));
            verify(provider, never()).getMetadata(new SecretReference("default", "gpt-x"));
        }
    }

    @Test
    @DisplayName("referencesIn collects every distinct reference, in both spellings")
    void referencesIn() {
        assertEquals(Set.of("${vault:a}", "${vault:t/b}", "${eddivault:c}"),
                VaultHostAllowlist.referencesIn(Arrays.asList("Bearer ${vault:a}", "${vault:t/b} and ${vault:a}", "${eddivault:c}", null, "plain")));
    }

    @Test
    @DisplayName("a reserved key name is never looked up")
    void reservedNamesSkipped() throws Exception {
        assertDoesNotThrow(() -> allowlist.requireAllowed(
                List.of("${vault:" + SecretReference.AGENT_SIGNING_KEY_PREFIX + "agent}"), "https://anywhere.example/", "x"));
        verify(provider, never()).getMetadata(any());
    }
}
