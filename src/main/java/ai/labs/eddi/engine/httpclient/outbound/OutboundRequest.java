/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.httpclient.outbound;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * One outbound request, before {@link OutboundHttp} applies its policies.
 *
 * @param profile
 *            the traffic class: pool, timeouts, TLS, default policies
 * @param method
 *            the HTTP method, upper case
 * @param uri
 *            the target
 * @param headers
 *            request headers, case-insensitive by name
 * @param callerDerivedHeaders
 *            names (lower case) of headers whose values came from the signed-in
 *            caller — a {@code ${caller:token}} may sit in any header, so these
 *            are stripped on a cross-origin redirect along with the fixed
 *            {@link OutboundHttp#SENSITIVE_HEADERS}
 * @param body
 *            the body; {@link RequestBody#EMPTY} for none
 * @param timeoutOverride
 *            per-hop response timeout; {@code null} for the default
 * @param redirectOverride
 *            {@code null} for the profile's default
 * @param ssrfOverride
 *            {@code null} for the profile's default
 * @param hopValidator
 *            replaces the SSRF policy's check of redirect hops (not of the
 *            initial URL), for a caller whose own gate is stricter than, or
 *            different from, the SSRF rules; {@code null} for the policy's
 *            check
 */
public record OutboundRequest(Profile profile, String method, URI uri, Map<String, List<String>> headers,
        Set<String> callerDerivedHeaders, RequestBody body, Duration timeoutOverride, RedirectPolicy redirectOverride,
        SsrfPolicy ssrfOverride, TargetValidator hopValidator) {

    public OutboundRequest {
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(uri, "uri");
        method = method.toUpperCase(Locale.ROOT);
        headers = caseInsensitiveCopy(headers);
        callerDerivedHeaders = lowerCaseCopy(callerDerivedHeaders);
        body = body == null ? RequestBody.EMPTY : body;
        if (timeoutOverride != null && (timeoutOverride.isNegative() || timeoutOverride.isZero())) {
            throw new IllegalArgumentException("timeout must be positive, was " + timeoutOverride);
        }
    }

    public static Builder builder(Profile profile, URI uri) {
        return new Builder(profile, uri);
    }

    public static Builder get(Profile profile, URI uri) {
        return new Builder(profile, uri).method("GET");
    }

    /** A new unmodifiable, case-insensitive map with copied value lists. */
    static Map<String, List<String>> caseInsensitiveCopy(Map<String, List<String>> source) {
        TreeMap<String, List<String>> copy = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        if (source != null) {
            source.forEach((name, values) -> {
                if (name != null && values != null) {
                    copy.computeIfAbsent(name, k -> new ArrayList<>()).addAll(values);
                }
            });
        }
        copy.replaceAll((name, values) -> List.copyOf(values));
        return Collections.unmodifiableMap(copy);
    }

    private static Set<String> lowerCaseCopy(Set<String> names) {
        TreeSet<String> copy = new TreeSet<>();
        if (names != null) {
            for (String name : names) {
                copy.add(name.toLowerCase(Locale.ROOT));
            }
        }
        return Collections.unmodifiableSet(copy);
    }

    public static final class Builder {
        private final Profile profile;
        private final URI uri;
        private String method = "GET";
        private final TreeMap<String, List<String>> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        private final Set<String> callerDerived = new TreeSet<>();
        private RequestBody body = RequestBody.EMPTY;
        private Duration timeout;
        private RedirectPolicy redirects;
        private SsrfPolicy ssrf;
        private TargetValidator hopValidator;

        private Builder(Profile profile, URI uri) {
            this.profile = profile;
            this.uri = uri;
        }

        public Builder method(String method) {
            this.method = method;
            return this;
        }

        public Builder method(String method, RequestBody body) {
            this.method = method;
            this.body = body;
            return this;
        }

        /** Adds a value; a repeated name keeps every value. */
        public Builder header(String name, String value) {
            headers.computeIfAbsent(name, k -> new ArrayList<>()).add(value);
            return this;
        }

        /**
         * Adds a header whose value came from the signed-in caller — never sent to
         * another origin on a redirect.
         */
        public Builder callerDerivedHeader(String name, String value) {
            callerDerived.add(name);
            return header(name, value);
        }

        public Builder timeout(Duration timeout) {
            this.timeout = timeout;
            return this;
        }

        public Builder redirects(RedirectPolicy redirects) {
            this.redirects = redirects;
            return this;
        }

        public Builder ssrf(SsrfPolicy ssrf) {
            this.ssrf = ssrf;
            return this;
        }

        public Builder hopValidator(TargetValidator hopValidator) {
            this.hopValidator = hopValidator;
            return this;
        }

        public OutboundRequest build() {
            return new OutboundRequest(profile, method, uri, headers, callerDerived, body, timeout, redirects, ssrf,
                    hopValidator);
        }
    }
}
