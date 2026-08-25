/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.connections;

import ai.labs.eddi.configs.connections.model.AuthType;
import ai.labs.eddi.configs.connections.model.Binding;
import ai.labs.eddi.configs.connections.model.ConnectionConfiguration;
import ai.labs.eddi.configs.connections.model.StaticAuth;
import ai.labs.eddi.connections.model.ConnectionReference;
import ai.labs.eddi.engine.security.CallerIdentity;
import ai.labs.eddi.engine.security.CallerIdentityContext;
import ai.labs.eddi.engine.security.ResolutionPrincipal;
import ai.labs.eddi.engine.security.ResolutionPrincipalContext;
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
import java.util.Optional;

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

    private final ConnectionRegistry connectionRegistry;
    private final CredentialReferenceResolver credentialReferenceResolver;
    private final CallerIdentityContext callerIdentityContext;
    private final MeterRegistry meterRegistry;
    private final boolean authorizationEnabled;

    /**
     * Supplies OAuth access tokens. Injected as an interface so Phase 2 (STATIC and
     * BASIC only) has no OAuth machinery to carry and Phase 4 adds it without
     * touching this class's structure.
     */
    private final AccessTokenSupplier accessTokenSupplier;

    /**
     * The principal of the conversation turn running on this thread, and how much
     * that identity is worth. The authoritative input to a {@link Binding#PER_USER}
     * decision.
     * <p>
     * Field-injected rather than taken through the constructor because a
     * {@code ConnectionResolver} is also built directly, without a container. A
     * {@code null} context is not a licence to fall back to something else: nothing
     * is bound, so every {@code PER_USER} resolution refuses, which is the only
     * safe reading of "I cannot tell who this is".
     */
    @Inject
    ResolutionPrincipalContext resolutionPrincipalContext;

    @Inject
    public ConnectionResolver(ConnectionRegistry connectionRegistry, CredentialReferenceResolver credentialReferenceResolver,
            CallerIdentityContext callerIdentityContext, MeterRegistry meterRegistry, AccessTokenSupplier accessTokenSupplier,
            @ConfigProperty(name = "authorization.enabled", defaultValue = "false") boolean authorizationEnabled) {
        this.connectionRegistry = connectionRegistry;
        this.credentialReferenceResolver = credentialReferenceResolver;
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
     *            the conversation's user id as the caller knows it, or {@code null}
     *            when the caller has none to offer. A cross-check only: the
     *            authority for a {@link Binding#PER_USER} grant is the
     *            {@link ResolutionPrincipal} bound to this turn, which carries a
     *            provenance this parameter cannot. A non-null value that disagrees
     *            with the bound principal is refused rather than preferred — see
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

    /**
     * Resolves the credential for a <em>discovery</em> request — one whose result
     * is shared, not served to the user who happened to trigger it.
     * <p>
     * The MCP {@code initialize} handshake and {@code tools/list} run once per
     * cached client and their outcome is reused by every conversation that follows.
     * Sending one user's credential there would pin that user's session, and their
     * permissions, onto everybody after them.
     * <p>
     * That is a reason to withhold a {@link Binding#PER_USER} grant. It was
     * mistakenly applied to <em>every</em> binding, and the consequence was that a
     * connection-bound MCP server registered ZERO tools: discovery went out
     * unauthenticated, the server answered 401, and the failure surfaced as an
     * agent that silently had no tools. A {@link Binding#SERVICE} grant is the same
     * credential for everyone by definition, so there is nothing to leak and every
     * reason to send it.
     *
     * @return the credential, or empty when the connection is {@code PER_USER} and
     *         genuinely has nothing a shared session may carry — the caller sends
     *         the request unauthenticated and lets the server decide
     * @throws ConnectionException
     *             if a non-{@code PER_USER} connection cannot be resolved; a
     *             discovery failure must be loud, not another silent empty tool
     *             list
     */
    public Optional<ResolvedCredential> resolveForDiscovery(String reference, URI targetUrl) {
        ConnectionReference parsed = ConnectionReference.parse(reference);
        // Read the binding without resolving. An unknown name falls through to
        // resolve(), which throws NOT_FOUND and counts it — swallowing it here would
        // reintroduce the empty-tool-list-with-no-explanation failure by a new route.
        Binding binding = connectionRegistry.find(parsed).map(ConnectionConfiguration::getBinding).orElse(null);
        // CALLER_SUPPLIED is withheld from discovery for exactly the reason PER_USER
        // is: the handshake's result is cached and replayed for every conversation
        // that follows, so whichever caller happened to trigger it would pin their
        // credential — and their permissions — onto everybody after them.
        if (binding == Binding.PER_USER || binding == Binding.CALLER_SUPPLIED) {
            return Optional.empty();
        }
        return Optional.of(resolve(reference, targetUrl, null));
    }

    private ResolvedCredential resolveCredential(ConnectionConfiguration connection, String principalOverride) {
        // Binding decides before authType does: CALLER_SUPPLIED is always STATIC, but
        // its value comes from the turn rather than from the document, so it must not
        // reach the STATIC branch below — that one resolves a valueTemplate this
        // connection is forbidden to have.
        if (connection.getBinding() == Binding.CALLER_SUPPLIED) {
            return callerSuppliedCredential(connection);
        }
        return switch (connection.getAuthType()) {
            case STATIC -> new ResolvedCredential(connection.getStaticAuth().getHeaderName(),
                    resolveReferences(connection.getStaticAuth().getValueTemplate(), connection));
            case BASIC -> basicCredential(connection);
            case OAUTH2_CLIENT_CREDENTIALS, OAUTH2_AUTHORIZATION_CODE -> oauthCredential(connection, principalOverride);
        };
    }

    /**
     * The credential the caller attached to this request, for a connection whose
     * whole point is that EDDI stores nothing.
     * <p>
     * Fails closed when there is none. The tempting alternative — send the call
     * unauthenticated and let the target answer — produces a 401 from a third party
     * with nothing anywhere naming the cause, which is the same failure mode the
     * missing-grant path was fixed for in {@code oauthCredential}.
     * <p>
     * The message distinguishes the two ways this happens, because the fix differs
     * and neither is visible from the other end: a turn that never carried the
     * credential (the calling system did not attach it) and a HITL resume that did
     * not carry it again (the credential lives for one request, and the resume is a
     * new one — see the plan's §5.5 "HITL resume").
     */
    private ResolvedCredential callerSuppliedCredential(ConnectionConfiguration connection) {
        CallerIdentity caller = callerIdentityContext == null ? null : callerIdentityContext.current();
        String value = caller == null ? null : caller.connectionCredential(connection.getName());
        if (value == null) {
            throw new ConnectionException(ConnectionException.Reason.NO_CALLER_CREDENTIAL,
                    "Connection '" + connection.getName() + "' is CALLER_SUPPLIED, but this request carried no credential for it. "
                            + "The calling system must send a '" + CallerIdentityContext.CONNECTION_CREDENTIAL_HEADER + ": "
                            + connection.getName() + " <value>' header — on every request, including a "
                            + "POST /agents/{conversationId}/resume that releases an approved tool call, because the credential is "
                            + "never stored and does not survive the pause.");
        }
        return new ResolvedCredential(connection.getStaticAuth().getHeaderName(), value);
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
     * The one input is the {@link ResolutionPrincipal} bound to this thread: the
     * owner of the conversation whose turn is executing, together with how that
     * owner's user id was established. Never the thread's {@link CallerIdentity} —
     * on a HITL resume that is the APPROVER, often an administrator by design, and
     * resolving there ran the approved call against the approver's SaaS account.
     * Never the request either, since a cached MCP client's request has no
     * conversation at all.
     * <p>
     * {@link Binding#PER_USER} needs a <em>verified</em> principal, not merely a
     * present one. {@code authorization.enabled=true} is not that proof: the
     * OpenAI-compatible {@code /v1} adapter, in api-key mode with
     * {@code trust-user-headers} (its default), believes a caller-supplied
     * {@code X-OpenWebUI-User-Id} verbatim, so a holder of the shared key can open
     * a conversation as anyone and would otherwise resolve that person's live
     * tokens. A deployment that deliberately delegates authentication to such a
     * proxy says so per connection, and says it about that connection only.
     * <p>
     * The startup guard reports the same conditions at boot but does not refuse —
     * it logs, because refusing cost a cluster its next rolling restart over one
     * config document. This is where the refusal actually happens.
     */
    private String resolvePrincipal(ConnectionConfiguration connection, String principalOverride) {
        if (connection.getBinding() != Binding.PER_USER) {
            return SERVICE_PRINCIPAL;
        }
        ResolutionPrincipal principal = resolutionPrincipalContext == null ? null : resolutionPrincipalContext.current();
        if (principal == null || !principal.hasUserId()) {
            throw new ConnectionException(ConnectionException.Reason.NO_VERIFIED_PRINCIPAL,
                    "Connection '" + connection.getName() + "' is PER_USER and no conversation principal is bound to this turn — a "
                            + "scheduled run, a trigger, or an outbound call made outside the conversation pipeline"
                            + (hasBoundCaller()
                                    ? " (a request caller IS bound here, but the caller of a request is not the owner of the conversation)"
                                    : "")
                            + ". Refusing rather than falling back to the service grant.");
        }
        // Cross-check, not a second source. The override is the conversation's own
        // userId read from template data, so it agrees with the bound principal on
        // every path that has both; a disagreement means the two disagree about
        // whose call this is, and that is never a question to answer by picking one.
        if (principalOverride != null && !principalOverride.isBlank() && !principalOverride.equals(principal.userId())) {
            throw new ConnectionException(ConnectionException.Reason.NO_VERIFIED_PRINCIPAL,
                    "Connection '" + connection.getName() + "' is PER_USER and the turn's bound principal does not match the user the "
                            + "call was built for. Refusing rather than choosing one of two disagreeing identities.");
        }
        if (!principal.isVerified() && !allowsUnverifiedPrincipal(connection)) {
            throw new ConnectionException(ConnectionException.Reason.NO_VERIFIED_PRINCIPAL,
                    "Connection '" + connection.getName() + "' is PER_USER, but nothing authenticated this conversation's user id"
                            + (authorizationEnabled
                                    ? " — it was self-asserted by the surface that opened the conversation, or the conversation predates "
                                            + "provenance being recorded and must be started again."
                                    : ", because authorization.enabled=false and no identity in this deployment is verified.")
                            + " Resolving it would hand that user's tokens to whoever claimed to be them. Enable OIDC, make the connection "
                            + "SERVICE-bound, or — if a trusted proxy authenticates these users — allow an unverified principal on this "
                            + "connection deliberately.");
        }
        return principal.userId();
    }

    /**
     * Whether a request identity is bound here at all.
     * <p>
     * Never an input to the decision — a request caller is not the conversation's
     * owner, which is the whole reason this class stopped reading it. It only tells
     * the refusal apart from the one raised on a scheduled run, where the answer to
     * "why is nobody here" is a completely different piece of advice.
     */
    private boolean hasBoundCaller() {
        CallerIdentity caller = callerIdentityContext == null ? null : callerIdentityContext.current();
        return caller != null;
    }

    /**
     * Whether this connection's owner has accepted that its users are authenticated
     * somewhere other than EDDI.
     * <p>
     * The escape hatch exists because delegating authentication to a front proxy is
     * a legitimate deployment, not a mistake — but it has to be stated per
     * connection, default off, so that turning it on is a decision about one
     * provider's tokens rather than a global posture.
     */
    private static boolean allowsUnverifiedPrincipal(ConnectionConfiguration connection) {
        return connection.isAllowUnverifiedPrincipal();
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
        return credentialReferenceResolver.resolveRequired(template, connection.getName(), "credential");
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
