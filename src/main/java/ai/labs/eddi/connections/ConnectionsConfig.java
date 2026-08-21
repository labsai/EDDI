/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.connections;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.net.URI;
import java.util.Optional;

/**
 * Deployment-level settings for the connections feature.
 * <p>
 * {@code eddi.connections.enabled} defaults to <b>false</b>, matching the
 * {@code openai-compat} precedent: a surface that stores refresh tokens is one
 * an operator turns on deliberately.
 */
@ApplicationScoped
public class ConnectionsConfig {

    /** Path the provider redirects back to. */
    public static final String CALLBACK_PATH = "/connections/callback";

    private final boolean enabled;
    private final String publicBaseUrl;

    @Inject
    public ConnectionsConfig(@ConfigProperty(name = "eddi.connections.enabled", defaultValue = "false") boolean enabled,
            @ConfigProperty(name = "eddi.connections.public-base-url") Optional<String> publicBaseUrl) {
        this.enabled = enabled;
        this.publicBaseUrl = publicBaseUrl.map(String::trim).orElse("");
    }

    /** Test seam. */
    ConnectionsConfig(boolean enabled, String publicBaseUrl) {
        this.enabled = enabled;
        this.publicBaseUrl = publicBaseUrl == null ? "" : publicBaseUrl.trim();
    }

    public boolean isEnabled() {
        return enabled;
    }

    public String getPublicBaseUrl() {
        return publicBaseUrl;
    }

    /**
     * The {@code redirect_uri} registered at the provider.
     * <p>
     * Built from configuration rather than from the inbound request. A
     * request-derived value can be steered with a {@code Host} or
     * {@code X-Forwarded-Host} header, and this string is both sent to the provider
     * and replayed at the token exchange — the provider matches it exactly, so a
     * forged one turns into a failed exchange at best and a redirect to an
     * attacker's host at worst.
     */
    public String redirectUri() {
        return trimTrailingSlash(publicBaseUrl) + CALLBACK_PATH;
    }

    /**
     * Whether a {@code returnTo} is a page of this deployment.
     * <p>
     * The user reaches this redirect immediately after authenticating at a
     * provider, which is the moment they are least likely to look at the address
     * bar — so an unvalidated value here is a particularly effective open redirect.
     * Same-origin only, and relative paths are accepted because that is what the
     * Manager actually sends.
     */
    public boolean isAllowedReturnTo(String returnTo) {
        if (returnTo == null || returnTo.isBlank()) {
            return false;
        }
        String candidate = returnTo.trim();
        // A protocol-relative URL ("//evil.example.com") has no scheme and is NOT a
        // relative path — browsers resolve it against the current scheme and go to
        // another host. Checked before the startsWith("/") shortcut below, which
        // would otherwise accept it.
        if (candidate.startsWith("//") || candidate.contains("\\")) {
            return false;
        }
        if (candidate.startsWith("/")) {
            return true;
        }
        if (publicBaseUrl.isEmpty()) {
            return false;
        }
        try {
            URI target = new URI(candidate);
            URI base = new URI(publicBaseUrl);
            if (target.getScheme() == null || target.getHost() == null) {
                return false;
            }
            return target.getScheme().equalsIgnoreCase(base.getScheme()) && target.getHost().equalsIgnoreCase(base.getHost())
                    && target.getPort() == base.getPort();
        } catch (Exception e) {
            return false;
        }
    }

    /** Where to send the browser when nothing valid was requested. */
    public String defaultReturnTo() {
        return trimTrailingSlash(publicBaseUrl) + "/manage/connections";
    }

    private static String trimTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}
