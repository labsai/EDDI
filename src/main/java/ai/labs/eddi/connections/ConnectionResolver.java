/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.connections;

import ai.labs.eddi.configs.connections.model.AuthType;
import ai.labs.eddi.configs.connections.model.Binding;
import ai.labs.eddi.configs.connections.model.ConnectionConfiguration;
import ai.labs.eddi.configs.connections.model.StaticAuth;
import ai.labs.eddi.configs.variables.GlobalVariableResolver;
import ai.labs.eddi.connections.model.ConnectionReference;
import ai.labs.eddi.engine.security.CallerIdentity;
import ai.labs.eddi.engine.security.CallerIdentityContext;
import ai.labs.eddi.secrets.SecretResolver;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Turns a {@code ${connection:name}} reference into a credential for
 * <em>this</em> request.
 * <p>
 * The one implementation of credential resolution that all five outbound paths
 * share, replacing five {@code apiKey}-shaped fields each with its own
 * {@code globalVariableResolver → secretResolver} snippet.
 *
 * <h3>Why this is not part of {@code SecretResolver}</h3>
 * {@code SecretResolver} deliberately has no agent and no user identity, and
 * {@code ChatModelRegistry} caches built models keyed on <em>unresolved</em>
 * parameters. Both are load-bearing properties of the deploy-time vault-grant
 * design: enforcement can happen at deploy because a reference means the same
 * thing every time it is resolved. A per-user, per-request, network-calling
 * resolver inside that class would break both. So the vault stays a static
 * secret store, and connections live above it and <em>use</em> it for their
 * client secrets.
 *
 * <h3>Fail closed, always</h3> Every failure path throws
 * {@link ConnectionException}. There is no path that returns "no credential"
 * and no path that substitutes the service grant when a user's is missing.
 * Sending the wrong authority is how one user reads another's data;
 * {@code CallerIdentityResolver} made the same call for
 * {@code ${caller:token}}.
 */
@ApplicationScoped
public class ConnectionResolver {

    private static final Logger LOGGER = Logger.getLogger(ConnectionResolver.class);

    /** Principal under which a {@link Binding#SERVICE} grant is stored. */
    public static final String SERVICE_PRINCIPAL = "__service__";

    /**
     * A reference that survived resolution — meaning the key or variable behind it
     * does not exist.
     */
    private static final Pattern UNRESOLVED_REFERENCE = Pattern.compile("\\$\\{(vault|eddivault|vars):[^}]*}");

    private final ConnectionRegistry connectionRegistry;
    private final SecretResolver secretResolver;
    private final GlobalVariableResolver globalVariableResolver;
    private final CallerIdentityContext callerIdentityContext;
    private final MeterRegistry meterRegistry;
    private final boolean authorizationEnabled;

    /**
     * Supplies OAuth access tokens. Injected as an interface so Phase 2 (STATIC and
     * BASIC only) has no OAuth machinery to carry and Phase 4 adds it without
     * touching this class's structure.
     */
    private final AccessTokenSupplier accessTokenSupplier;

    @Inject
    public ConnectionResolver(ConnectionRegistry connectionRegistry, SecretResolver secretResolver,
            GlobalVariableResolver globalVariableResolver, CallerIdentityContext callerIdentityContext, MeterRegistry meterRegistry,
            AccessTokenSupplier accessTokenSupplier,
            @ConfigProperty(name = "authorization.enabled", defaultValue = "false") boolean authorizationEnabled) {
        this.connectionRegistry = connectionRegistry;
        this.secretResolver = secretResolver;
        this.globalVariableResolver = globalVariableResolver;
        this.callerIdentityContext = callerIdentityContext;
        this.meterRegistry = meterRegistry;
        this.accessTokenSupplier = accessTokenSupplier;
        this.authorizationEnabled = authorizationEnabled;
    }

    /** Whether a value carries a connection reference. */
    public static boolean containsReference(String value) {
        return ConnectionReference.contains(value);
    }

    /**
     * Resolves the credential a reference names, for a call to {@code targetUrl}.
     *
     * @param reference
     *            a value containing {@code ${connection:name}}
     * @param targetUrl
     *            where the call is going; checked against the connection's
     *            {@code baseUrlAllowlist}
     * @param principalOverride
     *            the conversation's user id where request scope is unavailable (the
     *            pipeline runs on a pool thread); {@code null} to read the bound
     *            caller. It is only ever consulted for a principal that a VERIFIED
     *            identity produced — see
     *            {@link #resolvePrincipal(ConnectionConfiguration, String)}.
     * @throws ConnectionException
     *             on every failure; never returns null
     */
    public ResolvedCredential resolve(String reference, URI targetUrl, String principalOverride) {
        ConnectionReference parsed = ConnectionReference.parse(reference);
        ConnectionConfiguration connection;
        try {
            connection = connectionRegistry.require(parsed);
        } catch (ConnectionException e) {
            // Counted here rather than in the block below, because the lookup happens
            // before there is a connection to read authType and binding from — and
            // leaving it uncounted meant a deleted or misspelled connection failed
            // every turn while every dashboard stayed flat.
            countLookupFailure(e);
            throw e;
        }
        Timer.Sample sample = meterRegistry == null ? null : Timer.start(meterRegistry);
        try {
            requireTargetAllowed(connection, targetUrl);
            ResolvedCredential credential = resolveCredential(connection, principalOverride);
            record(connection, "success", sample);
            return credential;
        } catch (ConnectionException e) {
            record(connection, e.getReason().name().toLowerCase(Locale.ROOT), sample);
            throw e;
        }
    }

    private ResolvedCredential resolveCredential(ConnectionConfiguration connection, String principalOverride) {
        return switch (connection.getAuthType()) {
            case STATIC -> new ResolvedCredential(connection.getStaticAuth().getHeaderName(),
                    resolveReferences(connection.getStaticAuth().getValueTemplate(), connection));
            case BASIC -> basicCredential(connection);
            case OAUTH2_CLIENT_CREDENTIALS, OAUTH2_AUTHORIZATION_CODE -> oauthCredential(connection, principalOverride);
        };
    }

    /**
     * Builds the Basic header, doing the base64 here rather than making the author
     * vault a pre-encoded blob.
     * <p>
     * That is the whole point of {@link AuthType#BASIC} existing as a type: a
     * pre-encoded {@code user:pass} cannot be rotated field by field, cannot be
     * read back to check which account it belongs to, and hides a password inside
     * something that does not look like one.
     */
    private ResolvedCredential basicCredential(ConnectionConfiguration connection) {
        StaticAuth staticAuth = connection.getStaticAuth();
        String password = resolveReferences(staticAuth.getPasswordRef(), connection);
        String encoded = Base64.getEncoder()
                .encodeToString((staticAuth.getUsername() + ":" + password).getBytes(StandardCharsets.UTF_8));
        return new ResolvedCredential(staticAuth.getHeaderName(), "Basic " + encoded);
    }

    private ResolvedCredential oauthCredential(ConnectionConfiguration connection, String principalOverride) {
        String principal = resolvePrincipal(connection, principalOverride);
        String accessToken = accessTokenSupplier.accessToken(connection, principal);
        return new ResolvedCredential("Authorization", "Bearer " + accessToken);
    }

    /**
     * Decides whose grant to use, and refuses rather than guessing.
     * <p>
     * {@link Binding#PER_USER} needs a <em>verified</em> principal, not merely a
     * present one. With {@code authorization.enabled=false} — the shipped default —
     * there is no verified identity anywhere in the system, and the
     * OpenAI-compatible adapter documents that it believes
     * {@code X-OpenWebUI-User-Id} verbatim. Without this check, anyone claiming
     * {@code userId=alice} resolves Alice's Google token. The startup guard refuses
     * that configuration outright; this is the second of the two enforcement
     * points, because a guard covers the deployment and this covers the request.
     */
    private String resolvePrincipal(ConnectionConfiguration connection, String principalOverride) {
        if (connection.getBinding() != Binding.PER_USER) {
            return SERVICE_PRINCIPAL;
        }
        if (!authorizationEnabled) {
            throw new ConnectionException(ConnectionException.Reason.NO_VERIFIED_PRINCIPAL,
                    "Connection '" + connection.getName() + "' is PER_USER, but authorization.enabled=false, so no caller identity is "
                            + "verified. Any caller could claim any userId and resolve that user's tokens. Enable OIDC or make the "
                            + "connection SERVICE-bound.");
        }
        CallerIdentity caller = callerIdentityContext.current();
        String principal = caller != null && caller.userId() != null && !caller.userId().isBlank() ? caller.userId() : principalOverride;
        if (principal == null || principal.isBlank()) {
            throw new ConnectionException(ConnectionException.Reason.NO_VERIFIED_PRINCIPAL,
                    "Connection '" + connection.getName() + "' is PER_USER and this turn has no resolvable user — a scheduled run, a "
                            + "trigger or a retry on a callback thread. Refusing rather than falling back to the service grant.");
        }
        return principal;
    }

    /**
     * The generalisation of {@code ${caller:token}}'s same-origin rule: a
     * connection names the origins its credential may reach, so a config edit
     * cannot redirect a Google token to an attacker's host.
     */
    private void requireTargetAllowed(ConnectionConfiguration connection, URI targetUrl) {
        if (targetUrl == null) {
            throw new ConnectionException(ConnectionException.Reason.TARGET_NOT_ALLOWED,
                    "Connection '" + connection.getName() + "' cannot be resolved without a target URL to check against its allowlist.");
        }
        String origin = originOf(targetUrl, connection);
        List<String> allowlist = connection.getBaseUrlAllowlist();
        if (allowlist == null || allowlist.isEmpty()) {
            throw new ConnectionException(ConnectionException.Reason.INVALID_CONFIGURATION,
                    "Connection '" + connection.getName() + "' has an empty baseUrlAllowlist, so it may not be sent anywhere.");
        }
        for (String allowed : allowlist) {
            // Canonicalised on both sides rather than string-compared: a stored
            // "https://API.Example.com/" and a target "https://api.example.com" are the
            // same origin, and a comparison that says otherwise looks like a working
            // allowlist that blocks everything.
            if (canonicalise(allowed, connection).equals(origin)) {
                return;
            }
        }
        throw new ConnectionException(ConnectionException.Reason.TARGET_NOT_ALLOWED, "Connection '" + connection.getName() + "' may not be sent to "
                + origin + ". Add that origin to its baseUrlAllowlist if it is intended.");
    }

    private static String originOf(URI targetUrl, ConnectionConfiguration connection) {
        if (targetUrl.getScheme() == null || targetUrl.getHost() == null) {
            throw new ConnectionException(ConnectionException.Reason.TARGET_NOT_ALLOWED,
                    "Connection '" + connection.getName() + "' was given a target that is not an absolute URL: " + targetUrl);
        }
        return ConnectionConfiguration.canonicalOrigin(targetUrl);
    }

    private static String canonicalise(String allowedOrigin, ConnectionConfiguration connection) {
        try {
            return ConnectionConfiguration.requireCanonicalOrigin(allowedOrigin, "baseUrlAllowlist");
        } catch (IllegalArgumentException e) {
            // Stored documents predate, or bypass, the write-time validator (import,
            // a direct database write). A malformed entry must not silently match
            // nothing OR silently match everything — it is a configuration error and
            // says so.
            throw new ConnectionException(ConnectionException.Reason.INVALID_CONFIGURATION,
                    "Connection '" + connection.getName() + "' has a malformed baseUrlAllowlist entry: " + e.getMessage(), e);
        }
    }

    /**
     * Resolves {@code ${vars:…}} then {@code ${vault:…}} inside a template.
     * <p>
     * Order matters and matches the rest of the codebase: a global variable may
     * expand to a vault reference, and the reverse is never intended.
     */
    private String resolveReferences(String template, ConnectionConfiguration connection) {
        String resolved = globalVariableResolver.resolveValue(template);
        resolved = secretResolver.resolveValue(resolved);
        if (resolved == null || resolved.isBlank()) {
            throw new ConnectionException(ConnectionException.Reason.INVALID_CONFIGURATION,
                    "Connection '" + connection.getName() + "' resolved to an empty credential. Check that the referenced vault key exists.");
        }
        // Every reference form this method resolves is checked, not just the vault
        // one. A ${vars:} that did not resolve fails identically — the literal text is
        // sent as the credential and the provider answers 401 with nothing naming the
        // missing variable — so checking only ${vault:} left half the guard missing.
        if (UNRESOLVED_REFERENCE.matcher(resolved).find()) {
            throw new ConnectionException(ConnectionException.Reason.INVALID_CONFIGURATION,
                    "Connection '" + connection.getName() + "' has a reference that did not resolve. The vault key or global variable is "
                            + "missing, or the vault is inactive.");
        }
        return resolved;
    }

    /**
     * Counts a failure that happened before the connection could be read, so its
     * {@code authType} and {@code binding} are genuinely unknown rather than
     * omitted.
     */
    private void countLookupFailure(ConnectionException failure) {
        if (meterRegistry == null) {
            return;
        }
        meterRegistry.counter("connection.resolve.count", "authType", "unknown", "binding", "unknown", "outcome",
                failure.getReason().name().toLowerCase(Locale.ROOT)).increment();
    }

    private void record(ConnectionConfiguration connection, String outcome, Timer.Sample sample) {
        if (meterRegistry == null) {
            return;
        }
        // Tags are bounded categoricals only. A connection name is author-supplied
        // and unbounded, so tagging with it would let a config author mint Micrometer
        // series without limit and publish provider names to anyone who can read
        // /q/metrics.
        String authType = connection.getAuthType() == null ? "unknown" : connection.getAuthType().name();
        String binding = connection.getBinding() == null ? "unknown" : connection.getBinding().name();
        meterRegistry.counter("connection.resolve.count", "authType", authType, "binding", binding, "outcome", outcome).increment();
        if (sample != null) {
            sample.stop(meterRegistry.timer("connection.resolve.time", "authType", authType, "binding", binding));
        }
        if ("not_connected".equals(outcome)) {
            meterRegistry.counter("connection.grant.missing.count", "binding", binding).increment();
        }
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debugf("Connection '%s' resolve outcome: %s", connection.getName(), outcome);
        }
    }
}
