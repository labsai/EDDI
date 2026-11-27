/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.ingestion.connector;

import ai.labs.eddi.connections.ResolvedCredential;

import java.net.URI;

/**
 * How a connector obtains the credential for one request to its provider.
 *
 * <p>
 * An ingestion run has no conversation and no caller, so the only credential it
 * may spend is a {@code SERVICE}-bound connection: one grant for everybody,
 * resolved without a principal. A {@code PER_USER} connection needs a verified
 * conversation principal, which a scheduled run never has, and a
 * {@code CALLER_SUPPLIED} one needs a request that carried it. Both are refused
 * here — before anything is resolved — rather than left to fail deeper down
 * with a message about conversations.
 *
 * <p>
 * The context never holds a token: a connector asks per request, for the URL it
 * is about to call, so the connection's {@code baseUrlAllowlist} is checked
 * against every request and the grant cache decides when the token is renewed.
 */
@FunctionalInterface
public interface SourceCredentials {

    /**
     * Refuses every request: for a run assembled without the connections framework,
     * where no source that needs a credential can work.
     */
    SourceCredentials NONE = (connectionName, targetUrl) -> {
        throw new SourceException(new SourceError.Auth(
                "This ingestion run has no access to connections, so connection '" + connectionName
                        + "' cannot be used"));
    };

    /**
     * The header to send with one request.
     *
     * @param connectionName
     *            the connection's name, as the source configuration gives it
     * @param targetUrl
     *            the exact URL the request goes to; checked against the
     *            connection's {@code baseUrlAllowlist}
     * @throws SourceException
     *             {@link SourceError.Auth} when the connection is missing, not
     *             {@code SERVICE}-bound, refuses the target or has no usable grant;
     *             {@link SourceError.Transient} when its token endpoint failed and
     *             a retry may succeed
     */
    ResolvedCredential resolve(String connectionName, URI targetUrl) throws SourceException;
}
