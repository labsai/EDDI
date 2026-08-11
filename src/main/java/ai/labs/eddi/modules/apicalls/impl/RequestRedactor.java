/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.apicalls.impl;

import ai.labs.eddi.engine.httpclient.IRequest;
import ai.labs.eddi.engine.security.CallerIdentityResolver;
import ai.labs.eddi.secrets.sanitize.SecretRedactionFilter;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Removes credential material from a resolved request — headers, query
 * parameters and body alike.
 * <p>
 * One definition, two consumers: the debug record written to conversation
 * memory and the approval preview shown to a human. They must not drift — a
 * part redacted in one and not the other is a credential leak through whichever
 * path was forgotten. Each of the three has been that leak at some point, which
 * is why they are all defined here rather than at the call sites.
 */
@ApplicationScoped
public class RequestRedactor {

    /** What a redacted value is replaced with. */
    public static final String REDACTED = "<REDACTED>";

    private final CallerIdentityResolver callerIdentityResolver;

    @Inject
    public RequestRedactor(CallerIdentityResolver callerIdentityResolver) {
        this.callerIdentityResolver = callerIdentityResolver;
    }

    /**
     * Whether a header carries credential material, judged by its name.
     * <p>
     * {@code Locale.ROOT}, not the default locale: under a Turkish locale
     * "Authorization" lowercases to "authorızation" (dotless i), every test below
     * misses, and the header is persisted unredacted.
     */
    public static boolean isSensitiveHeaderName(String headerName) {
        if (headerName == null) {
            return false;
        }
        String name = headerName.toLowerCase(Locale.ROOT);
        return name.contains("authorization") || name.contains("api-key") || name.contains("api_key") || name.contains("apikey")
                || name.contains("x-api-key") || name.contains("token") || name.contains("secret") || name.contains("credential")
                || name.contains("password");
    }

    /**
     * Redact one header value.
     * <p>
     * Name matching only catches conventional names, so an unresolved vault
     * reference and a resolved caller token are additionally matched by value —
     * otherwise placing either in an arbitrarily named header would defeat the
     * redaction entirely.
     * <p>
     * The value-shape scan is the last step, and it is the one that closes the
     * asymmetry this class kept for a while: a query parameter and a body both ran
     * through {@link SecretRedactionFilter}, and a header did not. So
     * {@code X-Client-Auth: Bearer eyJhbGciOi…} — a name matching none of the
     * conventional patterns, a value that is not a vault reference and not the
     * current caller's token — was stored and shown to an approver in full, while
     * the identical string one field away in the body was caught. The shape is
     * recognisable and the filter already existed; only the wiring was missing.
     * <p>
     * Note this makes header redaction slightly more aggressive, and headers are
     * deliberately fingerprinted in their REDACTED form (see
     * {@link ResolvedRequest}). Two different secret-shaped values under the same
     * header therefore hash alike — but that is the pre-existing, documented
     * trade-off for headers, not a new one: a caller token legitimately differs
     * between requester and approver, so header values were already excluded from
     * change detection. Gate time and resume time run this same code, so they
     * continue to agree.
     */
    public String redactHeaderValue(String headerName, Object headerValue) {
        if (isSensitiveHeaderName(headerName)) {
            return REDACTED;
        }
        if (headerValue instanceof String value) {
            if (value.contains("${vault:") || value.contains("${eddivault:")) {
                return REDACTED;
            }
            return redactBody(callerIdentityResolver.redactCallerToken(value, REDACTED));
        }
        return headerValue == null ? null : headerValue.toString();
    }

    /** Redact every header in a name-to-value map. */
    public Map<String, String> redactHeaders(Map<String, ?> headers) {
        var redacted = new HashMap<String, String>();
        if (headers == null) {
            return redacted;
        }
        for (var entry : headers.entrySet()) {
            redacted.put(entry.getKey(), redactHeaderValue(entry.getKey(), entry.getValue()));
        }
        return redacted;
    }

    /**
     * Redact secret-shaped values out of a request body.
     * <p>
     * A body has no fixed key vocabulary to check by name the way headers do — it
     * is caller-defined JSON, or another format entirely — so this scans by VALUE
     * SHAPE via {@link SecretRedactionFilter} instead: an OpenAI/Anthropic style
     * key, a bearer token, or a vault reference is redacted wherever it appears,
     * independent of which field it sits under. A hand-rolled secret in a
     * generically named field with none of those shapes is not caught — the same
     * limitation this filter already accepts for LLM tool-call arguments
     * ({@code PendingToolCallBatch.PendingToolCall#argumentsRedacted}); reusing it
     * here keeps the two consistent rather than inventing a second, differently
     * effective scheme for the same class of data.
     * <p>
     * Static, unlike the header methods, because it needs no injected state — and
     * so that {@link ResolvedRequest#of} can reach it without an executor. That
     * matters for the class invariant above: this stays the <em>one</em> definition
     * of "redacted body" across both consumers.
     */
    public static String redactBody(String body) {
        return SecretRedactionFilter.redact(body);
    }

    /**
     * Redact a query parameter's value.
     * <p>
     * Judged by name like a header, and for the same reason: {@code ?api_key=…} or
     * {@code ?access_token=…} is a conventional way to pass a credential, and this
     * value is shown to an approver who is routinely not the person whose turn
     * raised the pause. Value-shape matching backs the name check up so a
     * credential under an unconventional name is still caught.
     */
    public static String redactQueryParamValue(String name, String value) {
        if (isSensitiveHeaderName(name)) {
            return REDACTED;
        }
        if (value == null) {
            return "";
        }
        if (value.contains("${vault:") || value.contains("${eddivault:")) {
            return REDACTED;
        }
        // No caller-token check, unlike a header: CallerIdentityResolver rejects
        // ${caller:token} outside headers outright, so a live caller token cannot
        // legitimately reach a query parameter. That is what lets this stay static
        // — and static is what lets ResolvedRequest#of apply it itself, keeping
        // "fingerprint the raw, store the redacted" in one place.
        return redactBody(value);
    }

    /**
     * Shape-scan the part of a URI before any query string, WITHOUT letting the
     * scan eat the authority.
     * <p>
     * {@code SecretRedactionFilter}'s generic rule matches
     * {@code (api_key|token|secret|password|authorization)[=:]<8+ chars>}, and its
     * trailing character class does not exclude {@code /}. Run over a whole URI
     * that scan consumes to the end of the string the moment the host itself ends
     * in one of those words followed by a port — plausible for an in-cluster
     * service name — so {@code https://vault-secret:8200/v1/agents/a1} collapsed to
     * {@code https://vault-secret=<REDACTED>}. That is worse than the leak it
     * guards: the approver loses the method's target entirely, and what is left is
     * not even a URI. Over-redaction hides what is being written to; a human who
     * cannot see the target cannot approve it.
     * <p>
     * So the scheme and authority are held aside and the scan is applied only to
     * the path, where a templated credential can actually land.
     */
    private static String redactUpToQuery(String beforeQuery) {
        int schemeEnd = beforeQuery.indexOf("://");
        if (schemeEnd < 0) {
            // Relative or scheme-less: it is all path.
            return redactBody(beforeQuery);
        }
        int authorityStart = schemeEnd + 3;
        int pathStart = beforeQuery.indexOf('/', authorityStart);
        String authority = pathStart < 0 ? beforeQuery.substring(authorityStart) : beforeQuery.substring(authorityStart, pathStart);
        String path = pathStart < 0 ? "" : beforeQuery.substring(pathStart);

        // The authority is kept verbatim EXCEPT its userinfo: `user:sk-…@host`
        // really does carry a credential, and it is bounded by '@', so scanning
        // it cannot run away into the host and path the way scanning the whole
        // authority did. Everything from '@' onward (host, port) stays legible.
        int at = authority.lastIndexOf('@');
        String safeAuthority = at < 0 ? authority : redactBody(authority.substring(0, at)) + authority.substring(at);

        return beforeQuery.substring(0, authorityStart) + safeAuthority + redactBody(path);
    }

    /**
     * Redact a request URI.
     * <p>
     * The URI was the one field of a resolved request that carried no redaction of
     * any kind, which made it the leak the rest of this class exists to prevent: a
     * credential templated into the path —
     * {@code "/v1/invoices?api_key=${vault:k}"} — is resolved to its live value by
     * {@code ApiCallExecutor#buildRequest} before the URI is ever built, and the
     * same value then appeared REDACTED in {@code queryParams} and PLAINTEXT in
     * {@code uri}, adjacent fields of one JSON object shown to an approver who is
     * routinely not the person whose turn raised the pause.
     * <p>
     * Two passes, because a URI has two places to hide one:
     * <ul>
     * <li>the query string is split and each value run through
     * {@link #redactQueryParamValue} — the SAME function the {@code queryParams}
     * map uses, so the two views of one credential cannot disagree;</li>
     * <li>whatever remains (scheme, userinfo, host, path) goes through
     * {@link #redactBody}'s value-shape scan, which catches
     * {@code https://user:sk-…@host} and a key segment inside a path.</li>
     * </ul>
     * <p>
     * Static and null-tolerant for the same reason as {@link #redactBody}:
     * {@link ResolvedRequest#of} applies it without an executor, keeping
     * "fingerprint the raw, store the redacted" resolved in exactly one place.
     */

    /**
     * Redact one query value from a URI, judging it in its DECODED form.
     * <p>
     * The scan has to see what the value actually is. {@code HttpClientWrapper}
     * decodes into {@code queryParamsMap}, but {@code toMap()} hands back the raw
     * {@code uri.toString()} — so the same credential arrives here still encoded,
     * and percent-encoding defeats the shape rules outright: a bearer token becomes
     * {@code Bearer%20aaaa…}, which the {@code Bearer\s+…} rule no longer matches,
     * and a {@code ${vault:…}} reference survives as {@code $%7Bvault%3A…}. The
     * result was a credential redacted in {@code queryParams} and plaintext one
     * field away in {@code uri} — the exact pair of adjacent contradictory fields
     * this method was added to stop.
     * <p>
     * The ORIGINAL value is emitted when nothing matched, so the preview keeps
     * showing what is genuinely on the wire; only a value the scan actually hit is
     * replaced. A malformed escape falls back to scanning the raw form rather than
     * skipping the check.
     */
    private static String redactQueryValueDecoded(String name, String rawValue) {
        String decoded = rawValue;
        try {
            decoded = java.net.URLDecoder.decode(rawValue, java.nio.charset.StandardCharsets.UTF_8);
        } catch (IllegalArgumentException malformedEscape) {
            // Keep the raw form — an unparseable escape is no reason to skip the scan.
        }
        String redacted = redactQueryParamValue(name, decoded);
        return redacted.equals(decoded) ? rawValue : redacted;
    }

    public static String redactUri(String uri) {
        if (uri == null) {
            return null;
        }
        int queryStart = uri.indexOf('?');
        if (queryStart < 0) {
            return redactUpToQuery(uri);
        }
        String beforeQuery = redactUpToQuery(uri.substring(0, queryStart));
        String query = uri.substring(queryStart + 1);
        // Preserve the fragment: it is not a query parameter and splitting on '&'
        // would otherwise fold it into the last value.
        String fragment = "";
        int fragmentStart = query.indexOf('#');
        if (fragmentStart >= 0) {
            fragment = redactBody(query.substring(fragmentStart));
            query = query.substring(0, fragmentStart);
        }
        var redactedQuery = new StringBuilder();
        for (String pair : query.split("&", -1)) {
            if (!redactedQuery.isEmpty()) {
                redactedQuery.append('&');
            }
            int eq = pair.indexOf('=');
            if (eq < 0) {
                // A valueless flag carries no credential to redact, but could still
                // BE one (?sk-live-…), so it is shape-scanned like anything else.
                redactedQuery.append(redactBody(pair));
                continue;
            }
            String name = pair.substring(0, eq);
            redactedQuery.append(name).append('=').append(redactQueryValueDecoded(name, pair.substring(eq + 1)));
        }
        return beforeQuery + "?" + redactedQuery + fragment;
    }

    /**
     * Redact the {@link IRequest#KEY_URI}, {@link IRequest#KEY_HEADERS},
     * {@link IRequest#KEY_QUERY_PARAMS} and {@link IRequest#KEY_BODY} entries of a
     * request map, as produced by {@link IRequest#toMap()}.
     * <p>
     * Each entry is REPLACED with a redacted copy rather than rewritten in place.
     * That distinction is load-bearing for the query parameters:
     * {@code HttpClientWrapper.RequestWrapper#toMap} hands back its live
     * {@code queryParamsMap} rather than a copy, so mutating the nested map would
     * corrupt the request that is about to be sent — while swapping the entry in
     * this (freshly built) outer map cannot.
     */
    @SuppressWarnings("unchecked")
    public void redactRequestMap(Map<String, Object> requestMap) {
        if (requestMap == null) {
            return;
        }
        // The KEY_* constants, not string literals: this map's shape is
        // IRequest#toMap's contract, and a redactor that spells the keys itself is
        // one rename away from silently redacting nothing.
        if (requestMap.get(IRequest.KEY_URI) instanceof String uri) {
            requestMap.put(IRequest.KEY_URI, redactUri(uri));
        }
        if (requestMap.get(IRequest.KEY_HEADERS) instanceof Map<?, ?> headers) {
            requestMap.put(IRequest.KEY_HEADERS, redactHeaders((Map<String, ?>) headers));
        }
        if (requestMap.get(IRequest.KEY_QUERY_PARAMS) instanceof Map<?, ?> queryParams) {
            requestMap.put(IRequest.KEY_QUERY_PARAMS, redactQueryParams((Map<String, ?>) queryParams));
        }
        if (requestMap.get(IRequest.KEY_BODY) instanceof String body) {
            requestMap.put(IRequest.KEY_BODY, redactBody(body));
        }
    }

    /**
     * Redact a query-parameter map, preserving its multi-valued shape.
     * <p>
     * Values arrive as {@code List<String>} from the default implementation but a
     * bare value is tolerated, for the same reason
     * {@code ApiCallExecutor#normalizeQueryParams} tolerates both.
     */
    public static Map<String, Object> redactQueryParams(Map<String, ?> queryParams) {
        var redacted = new HashMap<String, Object>();
        if (queryParams == null) {
            return redacted;
        }
        queryParams.forEach((name, value) -> {
            if (value instanceof List<?> values) {
                redacted.put(name, values.stream().map(v -> redactQueryParamValue(name, v == null ? null : v.toString())).toList());
            } else {
                redacted.put(name, redactQueryParamValue(name, value == null ? null : value.toString()));
            }
        });
        return redacted;
    }
}
