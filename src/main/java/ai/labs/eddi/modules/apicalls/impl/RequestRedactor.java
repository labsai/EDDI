/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.apicalls.impl;

import ai.labs.eddi.engine.security.CallerIdentityResolver;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Removes credential material from a resolved request's headers.
 * <p>
 * One definition, two consumers: the debug record written to conversation
 * memory and the approval preview shown to a human. They must not drift — a
 * header redacted in one and not the other is a credential leak through
 * whichever path was forgotten.
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
                || name.contains("x-api-key") || name.contains("token") || name.contains("secret") || name.contains("credential");
    }

    /**
     * Redact one header value.
     * <p>
     * Name matching only catches conventional names, so an unresolved vault
     * reference and a resolved caller token are additionally matched by value —
     * otherwise placing either in an arbitrarily named header would defeat the
     * redaction entirely.
     */
    public String redactHeaderValue(String headerName, Object headerValue) {
        if (isSensitiveHeaderName(headerName)) {
            return REDACTED;
        }
        if (headerValue instanceof String value) {
            if (value.contains("${vault:") || value.contains("${eddivault:")) {
                return REDACTED;
            }
            return callerIdentityResolver.redactCallerToken(value, REDACTED);
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
     * Redact the {@code headers} entry of a request map in place, as produced by
     * {@link ai.labs.eddi.engine.httpclient.IRequest#toMap()}.
     */
    @SuppressWarnings("unchecked")
    public void redactRequestMap(Map<String, Object> requestMap) {
        if (requestMap == null) {
            return;
        }
        if (requestMap.get("headers") instanceof Map<?, ?> headers) {
            requestMap.put("headers", redactHeaders((Map<String, ?>) headers));
        }
    }
}
