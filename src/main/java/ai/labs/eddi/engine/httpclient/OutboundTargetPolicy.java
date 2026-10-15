/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.httpclient;

import ai.labs.eddi.engine.security.SelfUrlResolver;
import ai.labs.eddi.modules.llm.tools.UrlValidationUtils;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.Optional;

/**
 * The one answer to "may this deployment open a connection to that address?"
 * for every outbound target that comes from <em>configuration</em> rather than
 * from the model: LLM provider base URLs, embedding endpoints, vector-store
 * hosts, OpenAPI spec locations and the setup wizard's {@code llmBaseUrl}.
 * <p>
 * It applies {@code eddi.security.ssrf-protection.enabled} the way
 * {@code ApiCallExecutor} applies it to httpcalls:
 * <ul>
 * <li>The cloud instance-metadata service is refused <b>always</b>
 * ({@link UrlValidationUtils#rejectCloudMetadataTarget}) — it hands out the
 * instance's cloud credentials and nobody configures it as an API.</li>
 * <li>With the flag <b>on</b>, the target is run through the full
 * {@link UrlValidationUtils#validateUrl} policy — http(s) only, and no
 * loopback, private, link-local or otherwise internal address — with one escape
 * hatch: a URL on this deployment's own origin ({@link SelfUrlResolver}, i.e.
 * {@code eddi.self.base-url} or the derived loopback address) is allowed,
 * because it is set by the operator and an agent that talks to EDDI's own
 * {@code /v1} API is a supported arrangement.</li>
 * <li>With the flag <b>off</b> (the shipped default), private and loopback
 * targets stay reachable: a local Ollama, an in-cluster pgvector, an internal
 * OpenAPI spec.</li>
 * </ul>
 * Before this class existed the flag governed httpcalls, MCP and A2A targets
 * only, and every other configured endpoint — a model {@code baseUrl}, a
 * pgvector {@code host}, a spec URL — was checked against the metadata service
 * alone, protection on or off. An editor who could save an LLM config could
 * therefore point it at any internal host whatever the operator had switched
 * on.
 * <p>
 * Beans that are also constructed directly in tests field-inject this and fall
 * back to {@link #permissive()} when the field is null — see
 * {@link #orPermissive(OutboundTargetPolicy)}.
 *
 * @since 6.6.0
 */
@ApplicationScoped
public class OutboundTargetPolicy {

    /** The switch, shared with the httpcalls, MCP and A2A paths. */
    public static final String CONFIG_KEY = "eddi.security.ssrf-protection.enabled";

    private static final OutboundTargetPolicy PERMISSIVE = new OutboundTargetPolicy(false, null, InetAddress::getAllByName);

    private final boolean ssrfProtectionEnabled;
    private final SelfUrlResolver selfUrlResolver;
    private final UrlValidationUtils.HostResolver hostResolver;

    @Inject
    public OutboundTargetPolicy(@ConfigProperty(name = CONFIG_KEY, defaultValue = "false") boolean ssrfProtectionEnabled,
            SelfUrlResolver selfUrlResolver) {
        this(ssrfProtectionEnabled, selfUrlResolver, InetAddress::getAllByName);
    }

    /**
     * For tests: an injectable resolver so a private or public answer can be
     * simulated without DNS, and a {@code null} self resolver for "no self origin".
     */
    public OutboundTargetPolicy(boolean ssrfProtectionEnabled, SelfUrlResolver selfUrlResolver, UrlValidationUtils.HostResolver hostResolver) {
        this.ssrfProtectionEnabled = ssrfProtectionEnabled;
        this.selfUrlResolver = selfUrlResolver;
        this.hostResolver = hostResolver;
    }

    /**
     * The policy with protection <b>off</b>: metadata check only. What a bean
     * constructed outside CDI (tests) gets, and the behaviour of every covered path
     * before this class existed.
     */
    public static OutboundTargetPolicy permissive() {
        return PERMISSIVE;
    }

    /** {@code policy}, or {@link #permissive()} when it was never injected. */
    public static OutboundTargetPolicy orPermissive(OutboundTargetPolicy policy) {
        return policy != null ? policy : PERMISSIVE;
    }

    /** Whether {@value #CONFIG_KEY} is on. */
    public boolean isEnabled() {
        return ssrfProtectionEnabled;
    }

    /**
     * Refuses {@code url} when it targets the metadata service (always), or, with
     * protection on, when it is not an http(s) URL on a public address — unless it
     * is on this deployment's own origin.
     *
     * @throws IllegalArgumentException
     *             naming the reason; the message says which rule refused it
     */
    public void validateUrl(String url) {
        UrlValidationUtils.rejectCloudMetadataTarget(url, hostResolver);
        if (!ssrfProtectionEnabled || isSelf(url)) {
            return;
        }
        UrlValidationUtils.validateUrl(url, hostResolver);
    }

    /**
     * {@link #validateUrl(String)} for a bare {@code host} or {@code host:port} — a
     * pgvector, Qdrant or MongoDB address that is opened as a raw TCP connection
     * rather than fetched as a URL. Wrapped in an {@code http://} URL for the
     * metadata check; with protection on the host must resolve, and to no private
     * address. There is no self escape hatch here: EDDI's own listener is an HTTP
     * endpoint, never a database.
     *
     * @throws IllegalArgumentException
     *             when the host is unusable, is the metadata service, or (flag on)
     *             is or resolves to a private address
     */
    public void validateHost(String host) {
        String bare = bareHost(host);
        if (bare == null) {
            return;
        }
        UrlValidationUtils.rejectCloudMetadataTarget("http://" + bracketed(bare) + "/", hostResolver);
        if (!ssrfProtectionEnabled) {
            return;
        }
        if (isPrivateHost(bare)) {
            throw new IllegalArgumentException("Host resolves to a private/internal address, which " + CONFIG_KEY + "=true refuses: " + bare);
        }
    }

    /**
     * Whether {@code host} is, or resolves to, an address
     * {@link UrlValidationUtils#isPrivateAddress} refuses. An unresolvable host
     * answers {@code true}: with protection on, "cannot tell" is a refusal, as it
     * is for a URL.
     */
    public boolean isPrivateHost(String host) {
        String bare = bareHost(host);
        if (bare == null) {
            return true;
        }
        if (UrlValidationUtils.isBlockedHostname(bare.toLowerCase(Locale.ROOT))) {
            return true;
        }
        try {
            for (InetAddress address : hostResolver.resolveAll(bare)) {
                if (UrlValidationUtils.isPrivateAddress(address)) {
                    return true;
                }
            }
            return false;
        } catch (UnknownHostException e) {
            return true;
        }
    }

    private boolean isSelf(String url) {
        if (selfUrlResolver == null) {
            return false;
        }
        try {
            return selfUrlResolver.isSelf(URI.create(url.trim()));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * {@code host}, trimmed, without a {@code :port} suffix and without IPv6
     * brackets; {@code null} when blank. A bare IPv6 literal ({@code ::1}) is told
     * from {@code host:port} by the number of colons.
     */
    static String bareHost(String host) {
        if (host == null || host.isBlank()) {
            return null;
        }
        String trimmed = host.trim();
        if (trimmed.startsWith("[")) {
            int close = trimmed.indexOf(']');
            return close > 1 ? trimmed.substring(1, close) : null;
        }
        int firstColon = trimmed.indexOf(':');
        if (firstColon >= 0 && firstColon == trimmed.lastIndexOf(':')) {
            return trimmed.substring(0, firstColon);
        }
        return trimmed;
    }

    private static String bracketed(String bare) {
        return bare.indexOf(':') >= 0 ? "[" + bare + "]" : bare;
    }

    /** For tests and diagnostics: the self origin this policy exempts, if any. */
    Optional<String> selfOrigin() {
        return selfUrlResolver == null ? Optional.empty() : Optional.ofNullable(selfUrlResolver.origin());
    }
}
