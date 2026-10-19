/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.setup;

import ai.labs.eddi.engine.api.IRestAgentAdministration;
import ai.labs.eddi.engine.runtime.client.factory.IRestInterfaceFactory;
import ai.labs.eddi.secrets.ISecretProvider;
import ai.labs.eddi.secrets.crypto.EnvelopeCrypto;
import ai.labs.eddi.secrets.crypto.VaultChecksum;
import ai.labs.eddi.secrets.model.SecretMetadata;
import ai.labs.eddi.secrets.model.SecretReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Security review 2026-10-17, H1: a key the setup wizard vaulted had no
 * {@code allowedHosts}, so any editor could copy the LLM configuration, point
 * {@code baseUrl} at their own host and receive the key. Every secret the
 * wizard creates now carries the destination it was meant for, and an
 * unrestricted one is created only when the request says {@code ["*"]} — or,
 * outside {@code grant-enforcement=enforce}, when nothing could be derived.
 */
@DisplayName("AgentSetupService — allowedHosts of wizard-vaulted secrets")
class AgentSetupAllowedHostsTest {

    private static final String KEY = "test-provider-key-not-a-real-secret";

    private ISecretProvider secretProvider;
    private AgentSetupService service;
    private Map<String, Object> createdResources;

    @BeforeEach
    void setUp() throws Exception {
        secretProvider = mock(ISecretProvider.class);
        service = new AgentSetupService(mock(IRestInterfaceFactory.class), mock(IRestAgentAdministration.class), secretProvider,
                "http://localhost:11434");
        service.vaultKeyReuse = AgentSetupService.VAULT_KEY_REUSE_CHECKSUM;
        createdResources = new LinkedHashMap<>();
        when(secretProvider.isAvailable()).thenReturn(true);
        when(secretProvider.listKeys(anyString())).thenReturn(List.of());
        when(secretProvider.matchesChecksum(anyString(), any(), anyString())).thenAnswer(
                inv -> VaultChecksum.matches(null, inv.getArgument(0), inv.getArgument(1), inv.getArgument(2)));
    }

    private String vaultApiKey(String vaultKeyName, AgentSetupService.HostRule rule) throws Exception {
        Method method = AgentSetupService.class.getDeclaredMethod("vaultApiKey", String.class, String.class, String.class, Map.class,
                AgentSetupService.HostRule.class);
        method.setAccessible(true);
        try {
            return (String) method.invoke(service, KEY, "My Agent", vaultKeyName, createdResources, rule);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof Exception cause) {
                throw cause;
            }
            throw e;
        }
    }

    private static AgentSetupService.HostRule derived(String provider, String baseUrl) {
        return new AgentSetupService.HostRule(null, SetupSecretHosts.forLlm(provider, baseUrl), "apiKey");
    }

    @SuppressWarnings("unchecked")
    private List<String> storedHosts() throws Exception {
        ArgumentCaptor<List<String>> hosts = ArgumentCaptor.forClass(List.class);
        verify(secretProvider).store(any(), eq(KEY), anyString(), eq(List.of("*")), hosts.capture());
        return hosts.getValue();
    }

    @Nested
    @DisplayName("the derived list")
    class Derived {

        @Test
        @DisplayName("a key for a provider's built-in endpoint is restricted to that endpoint's host")
        void providerDefaultHost() throws Exception {
            vaultApiKey(null, derived("openai", null));

            assertEquals(List.of("api.openai.com"), storedHosts());
        }

        @Test
        @DisplayName("a key for an admin-supplied base URL is restricted to that host and port")
        void baseUrlHost() throws Exception {
            vaultApiKey(null, derived("openai", "https://LLM.internal.example:8443/v1"));

            assertEquals(List.of("llm.internal.example:8443"), storedHosts());
        }

        @Test
        @DisplayName("a named OpenAI-compatible provider is restricted to its catalog endpoints")
        void catalogProvider() {
            List<String> hosts = SetupSecretHosts.forLlm("deepseek", null);

            assertTrue(hosts != null && !hosts.isEmpty(), String.valueOf(hosts));
            hosts.forEach(host -> assertTrue(host.contains("deepseek"), host));
        }

        @Test
        @DisplayName("a named key the wizard creates carries the list in the create-if-absent insert itself")
        void namedKeyCreate() throws Exception {
            when(secretProvider.getMetadata(any())).thenThrow(new ISecretProvider.SecretNotFoundException("absent"));
            when(secretProvider.storeIfAbsent(any(), anyString(), anyString(), any(), any())).thenReturn(true);

            vaultApiKey("openai-prod", derived("anthropic", null));

            verify(secretProvider).storeIfAbsent(any(), eq(KEY), anyString(), eq(List.of("*")), eq(List.of("api.anthropic.com")));
        }
    }

    @Nested
    @DisplayName("an unrestricted secret")
    class Unrestricted {

        @Test
        @DisplayName("under enforce, nothing derivable and nothing requested is refused before anything is written")
        void enforceRefuses() throws Exception {
            service.grantEnforcement = "enforce";

            var e = assertThrows(AgentSetupService.AgentSetupException.class, () -> vaultApiKey(null, derived("azure-openai", null)));

            assertTrue(e.getMessage().contains("allowedHosts [\"*\"]"), e.getMessage());
            verify(secretProvider, never()).store(any(), anyString(), anyString(), any(), any());
            verify(secretProvider, never()).store(any(), anyString(), anyString(), any());
        }

        @Test
        @DisplayName("under enforce, an explicit [\"*\"] stores the secret with no host list")
        void explicitWildcard() throws Exception {
            service.grantEnforcement = "enforce";

            vaultApiKey(null, new AgentSetupService.HostRule(List.of("*"), null, "apiKey"));

            assertNull(storedHosts());
        }

        @Test
        @DisplayName("under warn, nothing derivable still stores the secret, open")
        void warnStoresOpen() throws Exception {
            service.grantEnforcement = "warn";

            vaultApiKey(null, derived("azure-openai", null));

            assertNull(storedHosts());
        }
    }

    @Nested
    @DisplayName("a requested list")
    class Requested {

        @Test
        @DisplayName("is validated and used instead of the derived one")
        void requestedWins() throws Exception {
            vaultApiKey(null, new AgentSetupService.HostRule(List.of("Proxy.Example:443"), List.of("api.openai.com"), "apiKey"));

            assertEquals(List.of("proxy.example:443"), storedHosts());
        }

        @Test
        @DisplayName("an entry that is not a host, an empty list, or a wildcard mixed with hosts is refused")
        void invalidRefused() {
            for (List<String> bad : List.of(List.of("https://api.openai.com/v1"), List.<String>of(), List.of("*", "api.openai.com"))) {
                assertThrows(AgentSetupService.AgentSetupException.class,
                        () -> vaultApiKey(null, new AgentSetupService.HostRule(bad, null, "apiKey")), String.valueOf(bad));
            }
        }
    }

    @Nested
    @DisplayName("checksum reuse")
    class Reuse {

        private SecretMetadata entry(String keyName, List<String> hosts) {
            return new SecretMetadata(SecretReference.DEFAULT_TENANT, keyName, Instant.EPOCH, null, null, EnvelopeCrypto.sha256Hex(KEY), "test",
                    List.of("*"), hosts);
        }

        @Test
        @DisplayName("a legacy unrestricted entry holding the same key is not reused for a restricted one")
        void legacyOpenEntryNotReused() throws Exception {
            when(secretProvider.listKeys(anyString())).thenReturn(List.of(entry("setup.old.apiKey", null)));

            String reference = vaultApiKey(null, derived("openai", null));

            assertTrue(!reference.contains("setup.old.apiKey"), reference);
            assertEquals(List.of("api.openai.com"), storedHosts());
        }

        @Test
        @DisplayName("an entry restricted to the same hosts is reused")
        void sameHostsReused() throws Exception {
            when(secretProvider.listKeys(anyString())).thenReturn(List.of(entry("setup.same.apiKey", List.of("api.openai.com"))));

            String reference = vaultApiKey(null, derived("openai", null));

            assertEquals("${vault:setup.same.apiKey}", reference);
            verify(secretProvider, never()).store(any(), anyString(), anyString(), any(), any());
        }
    }
}
