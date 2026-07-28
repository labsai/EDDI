/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.security;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.net.URI;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves {@code ${caller:...}} references in outbound API call headers,
 * letting an agent call an API <em>as the user it is talking to</em> instead of
 * with a static credential.
 * <p>
 * Supported references:
 * <ul>
 * <li>{@code ${caller:token}} — the caller's raw bearer token</li>
 * <li>{@code ${caller:userId}} — the caller's principal name (not a
 * secret)</li>
 * </ul>
 * <p>
 * This exists because a static credential is the wrong shape for the job: an
 * OIDC token expires within the hour, cannot be least-privilege, and collapses
 * every action to one synthetic principal in the audit trail. Forwarding the
 * caller's own token means authorization stays EDDI's normal per-endpoint
 * enforcement, and the audit trail names a real person.
 *
 * <h2>Why this is safe</h2>
 * <ul>
 * <li><b>Same-origin only.</b> {@code ${caller:token}} resolves only when the
 * outbound request targets the exact origin the caller addressed. An agent
 * config naming a third-party host cannot exfiltrate the token.</li>
 * <li><b>Headers only.</b> A token in a query string ends up in access logs,
 * proxies and browser history, so a token reference outside a header is
 * rejected rather than resolved.</li>
 * <li><b>Never stored.</b> The value is injected while building the request;
 * {@code ApiCallExecutor} scrubs authorization headers before the request is
 * written to conversation memory.</li>
 * <li><b>Fails closed.</b> An unsatisfiable reference throws instead of
 * resolving to an empty string, which would silently send {@code "Bearer "} and
 * look like a puzzling 401 further downstream.</li>
 * </ul>
 *
 * @author ginccc
 * @since 6.2.0
 */
@ApplicationScoped
public class CallerIdentityResolver {

    private static final Logger LOGGER = Logger.getLogger(CallerIdentityResolver.class);

    /** Matches {@code ${caller:token}} and {@code ${caller:userId}}. */
    static final Pattern CALLER_PATTERN = Pattern.compile("\\$\\{caller:(token|userId)\\}");

    private static final String REF_TOKEN = "token";

    private final CallerIdentityContext callerIdentityContext;
    private final boolean enabled;

    @Inject
    public CallerIdentityResolver(CallerIdentityContext callerIdentityContext,
            @ConfigProperty(name = "eddi.caller-identity.enabled", defaultValue = "true") boolean enabled) {
        this.callerIdentityContext = callerIdentityContext;
        this.enabled = enabled;
    }

    /** Whether a value contains any {@code ${caller:...}} reference. */
    public static boolean containsReference(String value) {
        return value != null && CALLER_PATTERN.matcher(value).find();
    }

    /**
     * Resolve {@code ${caller:...}} references in a header value.
     *
     * @param value
     *            the raw header value; may be {@code null} or contain no reference,
     *            in which case it is returned unchanged
     * @param target
     *            the URI the request will be sent to, used for the same-origin
     *            check
     * @return the resolved value
     * @throws CallerIdentityException
     *             if a reference cannot be satisfied — no authenticated caller, the
     *             feature is disabled, or the target is a different origin
     */
    public String resolveValue(String value, URI target) {
        if (!containsReference(value)) {
            return value;
        }
        if (!enabled) {
            throw new CallerIdentityException(
                    "This API call references ${caller:...}, but caller-identity forwarding is disabled "
                            + "(eddi.caller-identity.enabled=false).");
        }

        var identity = callerIdentityContext.current();
        if (identity == null) {
            throw new CallerIdentityException("This API call references ${caller:...}, but the conversation turn has no authenticated "
                    + "caller. Caller identity is only available for turns driven by an authenticated request.");
        }

        Matcher matcher = CALLER_PATTERN.matcher(value);
        StringBuilder resolved = new StringBuilder();
        while (matcher.find()) {
            String reference = matcher.group(1);
            String replacement = REF_TOKEN.equals(reference) ? resolveToken(identity, target) : nullToEmpty(identity.userId());
            matcher.appendReplacement(resolved, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(resolved);
        return resolved.toString();
    }

    /**
     * Reject a {@code ${caller:token}} reference anywhere a token must not go.
     * <p>
     * Called for query parameters, which are logged, cached and proxied far more
     * freely than headers. Request bodies are not checked: a caller reference there
     * is never substituted, so it cannot leak a token.
     *
     * @param location
     *            human-readable place the reference was found, for the message
     */
    public void rejectTokenReference(String value, String location) {
        if (value != null && value.contains("${caller:token}")) {
            throw new CallerIdentityException(
                    "${caller:token} may only be used in a request header, but was found in " + location + ". "
                            + "Tokens in URLs leak through access logs and proxies.");
        }
    }

    /**
     * Redact the caller's token wherever it appears in an already-resolved value.
     *
     * The header-name patterns used elsewhere only catch conventional names, so a
     * token placed in an arbitrarily named header would otherwise be persisted to
     * conversation memory. This closes that gap by matching on the token itself.
     *
     * @return the value with any occurrence of the caller's token replaced
     */
    public String redactCallerToken(String value, String redaction) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        var identity = callerIdentityContext.current();
        if (identity == null || !identity.hasToken()) {
            return value;
        }
        return value.contains(identity.token()) ? value.replace(identity.token(), redaction) : value;
    }

    private String resolveToken(CallerIdentity identity, URI target) {
        if (!identity.hasToken()) {
            throw new CallerIdentityException(
                    "This API call references ${caller:token}, but the caller's request carried no bearer token.");
        }
        if (!OriginMatcher.sameOrigin(identity.origin(), target)) {
            // Do not log the target's full URI at INFO — it may embed identifiers.
            LOGGER.warnf("Refusing to forward the caller token to a different origin (caller=%s, target=%s)", identity.origin(),
                    OriginMatcher.normalize(target));
            throw new CallerIdentityException("${caller:token} may only be sent back to the origin the caller came from ("
                    + identity.origin() + "), but this call targets " + OriginMatcher.normalize(target) + ".");
        }
        return identity.token();
    }

    private static String nullToEmpty(String value) {
        return value != null ? value : "";
    }

    /** Raised when a {@code ${caller:...}} reference cannot be safely resolved. */
    public static class CallerIdentityException extends RuntimeException {

        public CallerIdentityException(String message) {
            super(message);
        }
    }
}
