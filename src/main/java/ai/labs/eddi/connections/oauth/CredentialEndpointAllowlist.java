/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.connections.oauth;

import ai.labs.eddi.configs.connections.model.ConnectionConfiguration;
import ai.labs.eddi.connections.ConnectionException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.net.URI;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The origins an <em>operator</em> is willing to send a client secret to.
 * <p>
 * Separate from a connection's {@code baseUrlAllowlist}, and deliberately not
 * settable per connection. {@code baseUrlAllowlist} says where the ACCESS TOKEN
 * may go; this says where the CLIENT SECRET may go, and the client secret is
 * the more valuable of the two — an access token expires, a client secret mints
 * new ones. A connection document cannot be allowed to vouch for its own token
 * endpoint: an author who can edit one could otherwise point {@code tokenUrl}
 * at a host they control and receive the vault-resolved secret on the first
 * refresh.
 * <p>
 * It is also what bounds MCP OAuth discovery: {@code WWW-Authenticate} names a
 * metadata document, and that document names an authorization server. Discovery
 * may <em>select</em> among pre-approved servers; it may never
 * <em>introduce</em> one.
 * <p>
 * Configured as {@code eddi.connections.credential-endpoint-allowlist}, a
 * comma-separated list of bare origins. Empty means <b>no OAuth connection can
 * resolve</b> — fail closed, not open: an empty allowlist is far more likely to
 * be an operator who has not configured it yet than one who meant "anywhere".
 */
@ApplicationScoped
public class CredentialEndpointAllowlist {

    private final Set<String> allowedOrigins;

    @Inject
    public CredentialEndpointAllowlist(
            @ConfigProperty(name = "eddi.connections.credential-endpoint-allowlist") Optional<String> configuredOrigins) {
        this.allowedOrigins = parse(configuredOrigins.orElse(""));
    }

    /** Test seam. */
    CredentialEndpointAllowlist(Set<String> allowedOrigins) {
        this.allowedOrigins = Set.copyOf(allowedOrigins);
    }

    private static Set<String> parse(String configured) {
        if (configured == null || configured.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(configured.split(",")).map(String::trim).filter(entry -> !entry.isEmpty())
                .map(entry -> ConnectionConfiguration.requireCanonicalOrigin(entry, "eddi.connections.credential-endpoint-allowlist"))
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /** Whether anything at all is allowed. */
    public boolean isEmpty() {
        return allowedOrigins.isEmpty();
    }

    /** The configured origins, for a startup log line. */
    public Set<String> origins() {
        return allowedOrigins;
    }

    /**
     * Refuses a credential endpoint the operator has not approved.
     *
     * @param url
     *            a token, authorization or discovery URL
     * @param what
     *            names the field, so the error says which one to fix
     */
    public void require(String url, String what) {
        if (url == null || url.isBlank()) {
            return;
        }
        URI parsed;
        try {
            parsed = new URI(url.trim());
        } catch (Exception e) {
            throw new ConnectionException(ConnectionException.Reason.INVALID_CONFIGURATION, what + " is not a valid URL: " + url, e);
        }
        if (parsed.getScheme() == null || parsed.getHost() == null) {
            throw new ConnectionException(ConnectionException.Reason.INVALID_CONFIGURATION, what + " must be an absolute URL: " + url);
        }
        String origin = ConnectionConfiguration.canonicalOrigin(parsed);
        if (allowedOrigins.isEmpty()) {
            throw new ConnectionException(ConnectionException.Reason.INVALID_CONFIGURATION,
                    "eddi.connections.credential-endpoint-allowlist is empty, so no OAuth credential endpoint may be contacted. Add "
                            + origin + " to it if that is intended.");
        }
        if (!allowedOrigins.contains(origin)) {
            throw new ConnectionException(ConnectionException.Reason.INVALID_CONFIGURATION, what + " points at " + origin
                    + ", which is not in eddi.connections.credential-endpoint-allowlist. The client secret is sent to this origin, so it "
                    + "must be approved by an operator rather than by the connection document.");
        }
    }
}
