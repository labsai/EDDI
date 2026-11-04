/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.apicalls.impl;

import ai.labs.eddi.engine.memory.MemoryKeys;
import ai.labs.eddi.engine.model.Context;
import ai.labs.eddi.engine.security.OriginMatcher;
import io.micrometer.core.instrument.Metrics;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves {@code ${context:<key>}} references in httpcall headers from the
 * current turn's context entries marked {@code "secret": true}.
 * <p>
 * This is the per-turn credential for an API agent whose user's own bearer
 * token is handed to EDDI with every request — sent as secret context, and
 * re-supplied on a HITL resume — in deployments where EDDI's own OIDC is off,
 * so {@code ${caller:token}} has no caller to forward. The Qute form
 * {@code Bearer {context.userToken}} works too, but it is plain templating: it
 * renders into any field of the request, towards any host the call names, and
 * renders {@code "Bearer "} when the value is missing. The reference has the
 * guarantees of {@code ${caller:token}} instead:
 * <ul>
 * <li><b>Secret entries only.</b> An entry the client did not mark
 * {@code "secret": true} is refused: only secret entries are scrubbed from the
 * stored conversation when the turn ends.</li>
 * <li><b>Headers only.</b> A reference in the path, a query parameter or the
 * body is refused, never resolved: a token in a URL is recorded by every hop,
 * and one in a body is not a credential the API reads.</li>
 * <li><b>Written in the header itself.</b> A reference that conversation data
 * or a variable value produced is refused; only one the configuration wrote is
 * resolved.</li>
 * <li><b>A lone reference</b>, optionally after an authorization scheme
 * ({@code Bearer ${context:userToken}}). A value that mixes the reference with
 * other text is refused, the same acceptance rule {@code setup-api} applies to
 * {@code apiAuth}.</li>
 * <li><b>Only to the host the call's configuration targets.</b> The resolved
 * request must have the origin of the httpcalls configuration's
 * {@code targetServerUrl}; an absolute path naming another host is refused, and
 * so is a {@code targetServerUrl} that is itself a template.</li>
 * <li><b>Fails closed.</b> A missing entry, an empty value, or the placeholder
 * a resumed turn holds when the client did not re-supply the value throws
 * instead of sending {@code "Bearer "}.</li>
 * <li><b>Never stored.</b> The resolved value joins the executor's redaction
 * set, so it is removed by value from the request record, the approval preview,
 * error bodies and log lines; the turn's end scrubs it from everything else as
 * for every secret context entry.</li>
 * </ul>
 * Every outcome is counted as {@code eddi.httpcalls.context.reference} with an
 * {@code outcome} tag.
 */
public final class SecretContextReferences {

    /** The template namespace, as Qute sees it. */
    public static final String NAMESPACE = "context";

    static final String OPENER = "${" + NAMESPACE + ":";

    /** A context key: what a client can name and a template can address. */
    private static final String KEY = "([A-Za-z0-9_.\\-]{1,128})";

    /**
     * The only accepted header shape: one reference, optionally after an
     * authorization scheme. The same shape {@code AgentSetupService} accepts for
     * {@code apiAuth}.
     */
    private static final Pattern HEADER_VALUE = Pattern.compile("(?:[A-Za-z][A-Za-z0-9-]*\\s+)?\\$\\{context:" + KEY + "}");

    /** A well-formed reference anywhere in a value. */
    private static final Pattern REFERENCE = Pattern.compile("\\$\\{context:" + KEY + "}");

    static final String METRIC = "eddi.httpcalls.context.reference";

    private SecretContextReferences() {
    }

    /** Whether {@code value} contains a {@code ${context:…}} opener at all. */
    public static boolean containsReference(String value) {
        return value != null && value.contains(OPENER);
    }

    /**
     * Refuses a {@code ${context:…}} reference outside a header. The path is
     * checked URL-decoded as well, because it is templated URL-encoded.
     */
    static void rejectOutsideHeader(String value, String location) {
        if (value == null) {
            return;
        }
        boolean found = containsReference(value);
        if (!found && value.indexOf('%') >= 0) {
            try {
                found = containsReference(URLDecoder.decode(value, StandardCharsets.UTF_8));
            } catch (IllegalArgumentException malformedEscape) {
                found = false;
            }
        }
        if (found) {
            record("misplaced");
            throw new SecretContextReferenceException("A ${context:…} reference may only be used in a request header, but was found in "
                    + location + ". It is never resolved there: a credential in a URL is recorded by every hop before the API sees it, "
                    + "and one in a body is not a credential the API reads.");
        }
    }

    /**
     * Resolves the reference in one header value.
     *
     * @param headerName
     *            the header, for messages
     * @param value
     *            the header value after templating and variable/vault resolution
     * @param configured
     *            the header value as the configuration wrote it
     * @param target
     *            the fully resolved request target
     * @param configuredTarget
     *            the httpcalls configuration's {@code targetServerUrl}, its global
     *            variables resolved
     * @param secretContext
     *            the current turn's context entry for a key, or {@code null}
     * @return the resolved value; {@code value} unchanged when it carries no
     *         reference
     * @throws SecretContextReferenceException
     *             when the reference cannot be resolved safely
     */
    static Resolution resolveHeader(String headerName, String value, String configured, URI target, String configuredTarget,
                                    Function<String, Context> secretContext) {
        if (!containsReference(value)) {
            return new Resolution(value, null);
        }
        String where = "Header '" + headerName + "'";
        Matcher shape = HEADER_VALUE.matcher(value.trim());
        if (!shape.matches()) {
            record("mixed");
            throw new SecretContextReferenceException(where + " mixes a ${context:…} reference with other text. Write the reference alone, "
                    + "optionally after an authorization scheme such as 'Bearer '.");
        }
        Matcher reference = REFERENCE.matcher(value);
        if (!reference.find()) {
            record("mixed");
            throw new SecretContextReferenceException(where + " carries a malformed ${context:…} reference.");
        }
        String key = reference.group(1);
        if (configured == null || !configured.contains(reference.group())) {
            // Same rule as ${caller:token}: what is released is decided by what the
            // configuration wrote, not by a value that rendered into it — user input,
            // an API response, or a ${vars:…} that can be changed after saving.
            record("not_configured");
            throw new SecretContextReferenceException(where + " receives " + reference.group() + " from conversation data or a variable "
                    + "value. Write the reference in the header itself.");
        }
        requireConfiguredOrigin(where, target, configuredTarget);
        if (secretContext == null) {
            record("missing");
            throw missing(where, key);
        }
        Context entry = secretContext.apply(key);
        if (entry == null) {
            record("missing");
            throw missing(where, key);
        }
        if (!Boolean.TRUE.equals(entry.getSecret())) {
            record("not_secret");
            throw new SecretContextReferenceException(where + " references ${context:" + key + "}, but that context entry is not marked "
                    + "\"secret\": true. Only secret entries are resolved, because only they are scrubbed from the stored conversation.");
        }
        if (!(entry.getValue() instanceof String plaintext) || plaintext.isBlank()) {
            record("missing");
            throw missing(where, key);
        }
        if (MemoryKeys.SECRET_CONTEXT_PLACEHOLDER.equals(plaintext)) {
            record("expired");
            throw new SecretContextReferenceException(where + " references ${context:" + key + "}, which this turn no longer has: it "
                    + "resumed after a human approval, or a later turn referenced it. Send the value again in the context of the request "
                    + "that makes this call.");
        }
        record("resolved");
        String resolved = value.substring(0, reference.start()) + plaintext + value.substring(reference.end());
        return new Resolution(resolved, plaintext);
    }

    private static void requireConfiguredOrigin(String where, URI target, String configuredTarget) {
        String allowedOrigin = null;
        if (configuredTarget != null && !configuredTarget.contains("{") && !configuredTarget.contains("$")) {
            try {
                allowedOrigin = OriginMatcher.normalize(URI.create(configuredTarget.trim()));
            } catch (IllegalArgumentException unparseable) {
                allowedOrigin = null;
            }
        }
        if (allowedOrigin == null) {
            record("cross_origin");
            throw new SecretContextReferenceException(where + " references ${context:…}, but the httpcalls configuration's targetServerUrl "
                    + "is not a plain URL, so the host the value may go to cannot be known. Set a literal targetServerUrl.");
        }
        if (!OriginMatcher.sameOrigin(allowedOrigin, target)) {
            record("cross_origin");
            throw new SecretContextReferenceException(where + " references ${context:…}, which is only sent to the configuration's "
                    + "targetServerUrl (" + allowedOrigin + "), but this call targets " + OriginMatcher.normalize(target) + ".");
        }
    }

    private static SecretContextReferenceException missing(String where, String key) {
        return new SecretContextReferenceException(where + " references ${context:" + key + "}, but this turn's context has no value "
                + "for it. Send it with the request, marked \"secret\": true. Refusing to send the header without it.");
    }

    private static void record(String outcome) {
        Metrics.globalRegistry.counter(METRIC, "outcome", outcome).increment();
    }

    /**
     * A resolved header value and the plaintext substituted into it, or
     * {@code null} when there was no reference.
     */
    record Resolution(String value, String plaintext) {
    }

    /**
     * A {@code ${context:…}} reference that cannot be resolved safely. An
     * {@link IllegalArgumentException}, like every other build-time refusal of the
     * executor, so the pipeline reports it as a configuration error.
     */
    public static class SecretContextReferenceException extends IllegalArgumentException {
        public SecretContextReferenceException(String message) {
            super(message);
        }
    }
}
