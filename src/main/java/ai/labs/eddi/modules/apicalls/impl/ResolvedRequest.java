/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.apicalls.impl;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * The HTTP request an {@code ApiCall} resolves to, with every credential
 * already redacted, plus a fingerprint re-checked before execution.
 * <p>
 * This is what a human approves. Approving a <em>tool name</em> is close to
 * meaningless for a generated API client: the name comes from an
 * {@code operationId} and says nothing about which resource is being written or
 * with what body. The approver sees this, and {@link #fingerprint()} is
 * re-derived immediately before execution so what runs is what was approved.
 *
 * <h2>Headers: fingerprinted redacted. Body: fingerprinted raw.</h2>
 *
 * The asymmetry is deliberate, and it is not a compromise on either side.
 * <p>
 * <b>Headers</b> are fingerprinted in their redacted form because
 * {@code ApiCallExecutor} resolves {@code ${caller:token}} into the
 * {@code Authorization} header, and on a resumed turn the caller is whoever
 * <em>approved</em> the pause, who is routinely not the person whose turn
 * raised it. Fingerprinting the live header would mismatch on every cross-user
 * approval — the normal, desirable case — and the guard would fire constantly
 * on correct behaviour until someone disabled it. <em>Whose</em> credentials
 * carry a request is governed by authentication, not approval, and deliberately
 * does not participate.
 * <p>
 * <b>Bodies</b> have no such legitimate variance: {@code ${caller:token}} is
 * rejected outside headers, and a {@code ${vault:...}} reference resolves to
 * the same value at gate time and at execution. So the body is hashed as
 * resolved and only the stored copy is redacted — {@link #of} does that itself
 * so no call site can get the order wrong. Redacting first would collapse two
 * <em>different</em> credentials to one marker and so to one fingerprint,
 * letting a swapped secret pass the pre-execution check unnoticed. The
 * fingerprint is never exposed to any client, so hashing the raw body reveals
 * nothing.
 */
public record ResolvedRequest(
        String method,
        String uri,
        Map<String, String> queryParams,
        Map<String, String> headers,
        String body,
        String fingerprint) {

    /**
     * Build a resolved request: fingerprint the raw body, store a redacted one.
     *
     * @param redactedHeaders
     *            already redacted by the caller, which owns the injected
     *            {@code CallerIdentityResolver} needed to match a live caller token
     *            by value.
     * @param rawBody
     *            the body <em>as resolved</em>. Redacted here rather than by the
     *            caller so that {@link #body()} is always safe to display and the
     *            fingerprint always covers what will actually be sent — see the
     *            class javadoc for why those must be the two different forms.
     * @param fingerprintable
     *            false when this call cannot be resolved ahead of execution without
     *            side effects — see {@link IApiCallExecutor#resolve}. The preview
     *            is still produced; {@link #fingerprint()} is null, and enforcement
     *            is skipped rather than failing a call it cannot honestly pin.
     */
    public static ResolvedRequest of(String method, String uri, Map<String, String> queryParams, Map<String, String> redactedHeaders,
                                     String rawBody, boolean fingerprintable) {

        var sortedQuery = sorted(queryParams);
        var sortedHeaders = sortedByLowercasedName(redactedHeaders);
        String fingerprint = fingerprintable ? fingerprintOf(method, uri, sortedQuery, sortedHeaders, rawBody) : null;
        return new ResolvedRequest(method, uri, sortedQuery, sortedHeaders, RequestRedactor.redactBody(rawBody), fingerprint);
    }

    /** Whether this request was pinned to a fingerprint at gate time. */
    public boolean isPinned() {
        return fingerprint != null;
    }

    /**
     * SHA-256 over a length-prefixed canonical encoding.
     * <p>
     * Length prefixes rather than plain delimiters because a JSON body can contain
     * any separator we might pick: without them, moving a newline from a body into
     * a header value could produce two different requests with one fingerprint.
     * Header names are lowercased and both maps sorted, so ordering and casing —
     * neither of which changes what the request does — cannot change the hash.
     */
    private static String fingerprintOf(String method, String uri, Map<String, String> queryParams, Map<String, String> headers, String body) {

        var canonical = new StringBuilder();
        appendField(canonical, "method", method == null ? "" : method.toUpperCase(Locale.ROOT));
        appendField(canonical, "uri", uri);
        for (var entry : queryParams.entrySet()) {
            appendField(canonical, "query." + entry.getKey(), entry.getValue());
        }
        for (var entry : headers.entrySet()) {
            appendField(canonical, "header." + entry.getKey(), entry.getValue());
        }
        appendField(canonical, "body", body);

        try {
            var digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            var hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandated by the JLS for every conforming JVM.
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private static void appendField(StringBuilder canonical, String name, String value) {
        String safe = value == null ? "" : value;
        canonical.append(name).append(':').append(safe.length()).append(':').append(safe).append('\n');
    }

    private static Map<String, String> sorted(Map<String, String> values) {
        var result = new TreeMap<String, String>();
        if (values != null) {
            values.forEach((key, value) -> result.put(key, value == null ? "" : value));
        }
        return result;
    }

    private static Map<String, String> sortedByLowercasedName(Map<String, String> values) {
        var result = new TreeMap<String, String>();
        if (values != null) {
            values.forEach((key, value) -> result.put(key == null ? "" : key.toLowerCase(Locale.ROOT), value == null ? "" : value));
        }
        return result;
    }
}
