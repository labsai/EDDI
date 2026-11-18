/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.ingestion;

import ai.labs.eddi.configs.connections.model.Binding;
import ai.labs.eddi.connections.ConnectionException;
import ai.labs.eddi.connections.ConnectionResolver;
import ai.labs.eddi.connections.ResolvedCredential;
import ai.labs.eddi.modules.ingestion.connector.SourceCredentials;
import ai.labs.eddi.modules.ingestion.connector.SourceError;
import ai.labs.eddi.modules.ingestion.connector.SourceException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.net.URI;
import java.util.regex.Pattern;

/**
 * The connections framework, as an ingestion run may use it: {@code SERVICE}
 * connections only, resolved with no principal.
 *
 * <p>
 * The binding is read before anything is resolved. A {@code PER_USER}
 * connection would be refused by the resolver too — no principal is bound to an
 * ingestion run — but with a message about conversation turns that tells an
 * operator nothing, and only on the first request of every run. Here it is
 * refused by name, with the fix. A {@code CALLER_SUPPLIED} connection is
 * refused for the same reason: there is no caller to supply it.
 *
 * <p>
 * Every request is resolved for its own URL, so the connection's
 * {@code baseUrlAllowlist} is checked on each one — a source cannot send a
 * Google token anywhere its connection does not name.
 */
@ApplicationScoped
public class ServiceConnectionCredentials implements SourceCredentials {

    /** The connections framework's name rule; anything else never names one. */
    private static final Pattern CONNECTION_NAME = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$");

    private final ConnectionResolver resolver;

    @Inject
    public ServiceConnectionCredentials(ConnectionResolver resolver) {
        this.resolver = resolver;
    }

    @Override
    public ResolvedCredential resolve(String connectionName, URI targetUrl) throws SourceException {
        if (connectionName == null || !CONNECTION_NAME.matcher(connectionName).matches()) {
            throw new SourceException(new SourceError.Auth(
                    "'" + connectionName + "' is not a connection name, so no credential can be resolved"));
        }
        String reference = "${connection:" + connectionName + "}";
        Binding binding;
        try {
            binding = resolver.bindingOf(reference).orElse(null);
        } catch (ConnectionException e) {
            throw new SourceException(classify(connectionName, e), e);
        }
        if (binding == null) {
            throw new SourceException(new SourceError.Auth("No connection named '" + connectionName
                    + "' exists. Create it (for Google Drive: an OAUTH2_JWT_BEARER connection holding the service "
                    + "account's key), then run the source again."));
        }
        if (binding != Binding.SERVICE) {
            throw new SourceException(new SourceError.Auth("Connection '" + connectionName + "' is bound " + binding
                    + ", but an ingestion run has no user and no caller whose credential it could use. A knowledge "
                    + "base source needs a SERVICE-bound connection, such as OAUTH2_JWT_BEARER with a service "
                    + "account; sources that read a person's own account are not supported yet."));
        }
        try {
            return resolver.resolve(reference, targetUrl, null);
        } catch (ConnectionException e) {
            throw new SourceException(classify(connectionName, e), e);
        }
    }

    /**
     * A token endpoint that is down may be up on the next attempt; everything else
     * the resolver refuses — a target off the allowlist, a revoked key, a document
     * it cannot honour — stays refused until someone changes the connection.
     * Neither says anything about a document, so neither is definitive.
     */
    private static SourceError classify(String connectionName, ConnectionException e) {
        String reason = "Connection '" + connectionName + "' could not be used (" + e.getReason() + "): "
                + e.getMessage();
        return e.getReason() == ConnectionException.Reason.TOKEN_ENDPOINT_UNAVAILABLE
                ? new SourceError.Transient(reason)
                : new SourceError.Auth(reason);
    }
}
