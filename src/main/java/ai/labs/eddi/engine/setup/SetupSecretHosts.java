/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.setup;

import ai.labs.eddi.modules.llm.impl.builder.OpenAiCompatibleProvider;
import ai.labs.eddi.modules.llm.impl.builder.OpenAiCompatibleProviders;
import ai.labs.eddi.secrets.VaultHostAllowlist;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The {@code allowedHosts} a secret the setup wizard vaults is stored with.
 * <p>
 * A wizard-vaulted key used to be stored with no destination list, which
 * {@link VaultHostAllowlist} reads as "may go anywhere". Every editor can read
 * the LLM configuration that holds the {@code ${vault:…}} reference, so any of
 * them could copy it, point {@code baseUrl} at a host of their own and receive
 * the key on the first turn (security review 2026-10-17, H1). The wizard knows
 * where the key is meant to go — the base URL the admin typed, or the endpoint
 * the provider's builder uses when there is none — so that is the list it
 * stores.
 * <p>
 * The list matters only when a configuration names an endpoint: a configuration
 * without one uses the provider's built-in endpoint and is not checked (see
 * {@code ChatModelRegistry.ENDPOINT_KEYS}). The default hosts below are
 * therefore what an editor may legitimately <em>spell out</em>, not a
 * precondition for the default endpoint to work.
 */
final class SetupSecretHosts {

    /**
     * The endpoints of the providers whose builder supplies its own when the
     * configuration names none. The named OpenAI-compatible providers come from
     * their catalog instead ({@link #forLlm}). Providers missing here
     * (azure-openai, ollama, bedrock, oracle-genai) either always carry an endpoint
     * the admin supplied or take no API key at all.
     */
    private static final Map<String, List<String>> PROVIDER_DEFAULT_HOSTS = Map.of(
            "openai", List.of("api.openai.com"),
            "anthropic", List.of("api.anthropic.com"),
            "gemini", List.of("generativelanguage.googleapis.com"),
            "mistral", List.of("api.mistral.ai"),
            "huggingface", List.of("router.huggingface.co"),
            // Jlama's authToken is a Hugging Face token, used to download the model.
            "jlama", List.of("huggingface.co"));

    private SetupSecretHosts() {
    }

    /**
     * The hosts an LLM key for {@code provider} may be sent to.
     *
     * @param baseUrl
     *            the endpoint the admin supplied, or {@code null}
     * @return the host list, or {@code null} when neither the request nor the
     *         provider names an endpoint this class knows
     */
    static List<String> forLlm(String provider, String baseUrl) {
        if (baseUrl != null && !baseUrl.isBlank()) {
            return forUrls(List.of(baseUrl));
        }
        if (provider == null) {
            return null;
        }
        String normalized = provider.trim().toLowerCase(Locale.ROOT);
        List<String> known = PROVIDER_DEFAULT_HOSTS.get(normalized);
        if (known != null) {
            return known;
        }
        return OpenAiCompatibleProviders.find(normalized).map(SetupSecretHosts::catalogHosts).orElse(null);
    }

    private static List<String> catalogHosts(OpenAiCompatibleProvider provider) {
        var urls = new ArrayList<String>();
        urls.add(provider.defaultBaseUrl());
        if (provider.regions() != null) {
            provider.regions().forEach(region -> urls.add(region.baseUrl()));
        }
        return forUrls(urls);
    }

    /**
     * The {@code host} (or {@code host:port}, when the URL spells a port) of every
     * URL, de-duplicated, in a form {@link VaultHostAllowlist#validatedHosts}
     * accepts.
     *
     * @return the list, or {@code null} when no URL yields a host
     */
    static List<String> forUrls(Collection<String> urls) {
        var hosts = new LinkedHashSet<String>();
        for (String url : urls) {
            if (url == null || url.isBlank()) {
                continue;
            }
            try {
                URI uri = new URI(url.trim());
                String host = uri.getHost();
                if (host == null || host.isBlank()) {
                    continue;
                }
                String lowered = host.toLowerCase(Locale.ROOT);
                hosts.add(uri.getPort() >= 0 ? lowered + ":" + uri.getPort() : lowered);
            } catch (URISyntaxException e) {
                // Not a URL the vault could match a destination against either.
            }
        }
        if (hosts.isEmpty()) {
            return null;
        }
        try {
            return VaultHostAllowlist.validatedHosts(new ArrayList<>(hosts));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * Whether a request's list is exactly {@code ["*"]} — how a setup request
     * stores a secret with no destination restriction, said out loud like
     * {@code allowedAgents: ["*"]}.
     */
    static boolean isExplicitlyUnrestricted(List<String> requested) {
        return VaultHostAllowlist.isExplicitlyUnrestricted(requested);
    }
}
