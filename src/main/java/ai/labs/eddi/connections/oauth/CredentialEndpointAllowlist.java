/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.connections.oauth;

import ai.labs.eddi.configs.connections.model.ConnectionConfiguration;
import ai.labs.eddi.connections.ConnectionException;
import ai.labs.eddi.connections.ConnectionsConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.net.URI;
import java.util.Set;

/**
 * The origins a client secret may be sent to.
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
 * It covers the token and authorization endpoints, and only those. RFC 9728
 * resource-metadata discovery — a {@code WWW-Authenticate} challenge naming a
 * metadata document that names an authorization server — is <em>not</em>
 * implemented: {@code McpAuthChallengeParser} can read such a challenge, but
 * nothing fetches the document or selects a server from it. If that ever lands,
 * this allowlist is where the selected server has to be checked.
 * <p>
 * Set through {@code PUT /connectionstore/settings}
 * ({@code credentialEndpointAllowlist}), or pinned with
 * {@code eddi.connections.credential-endpoint-allowlist}; see
 * {@link ConnectionsConfig}. Read per call, so a change applies to the next
 * token exchange without a restart. Empty means <b>no OAuth connection can
 * resolve</b> — fail closed, not open: an empty allowlist is far more likely to
 * be one nobody has configured yet than one somebody meant as "anywhere".
 */
@ApplicationScoped
public class CredentialEndpointAllowlist {

    private static final String SETTING = ConnectionsConfig.describe("credentialEndpointAllowlist", ConnectionsConfig.CREDENTIAL_ENDPOINT_ALLOWLIST);

    /** Null when constructed with fixed origins. */
    private final ConnectionsConfig connectionsConfig;

    /** Null when backed by {@link #connectionsConfig}. */
    private final Set<String> fixedOrigins;

    @Inject
    public CredentialEndpointAllowlist(ConnectionsConfig connectionsConfig) {
        this.connectionsConfig = connectionsConfig;
        this.fixedOrigins = null;
    }

    /** A fixed allowlist. Test seam. */
    public CredentialEndpointAllowlist(Set<String> allowedOrigins) {
        this.connectionsConfig = null;
        this.fixedOrigins = Set.copyOf(allowedOrigins);
    }

    /** Whether anything at all is allowed. */
    public boolean isEmpty() {
        return origins().isEmpty();
    }

    /** The approved origins, for a log line or the settings view. */
    public Set<String> origins() {
        return fixedOrigins != null ? fixedOrigins : connectionsConfig.credentialEndpointOrigins();
    }

    /**
     * Refuses a credential endpoint that has not been approved.
     *
     * @param url
     *            a token or authorization URL
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
        // Read once: the allowlist can change between two reads now.
        Set<String> allowed = origins();
        if (allowed.isEmpty()) {
            throw new ConnectionException(ConnectionException.Reason.INVALID_CONFIGURATION, "The credential endpoint allowlist, " + SETTING
                    + ", is empty, so no OAuth credential endpoint may be contacted. Add " + origin + " to it if that is intended.");
        }
        if (!allowed.contains(origin)) {
            throw new ConnectionException(ConnectionException.Reason.INVALID_CONFIGURATION, what + " points at " + origin
                    + ", which is not in the credential endpoint allowlist, " + SETTING + ". The client secret is sent to this origin, so it "
                    + "must be approved in the deployment's connection settings rather than by the connection document.");
        }
    }
}
