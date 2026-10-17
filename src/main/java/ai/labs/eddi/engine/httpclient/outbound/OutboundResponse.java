/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.httpclient.outbound;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The final response of an exchange — after any redirects were followed.
 *
 * @param status
 *            the HTTP status code
 * @param reasonPhrase
 *            the server's reason phrase, when the engine reports one (the JDK
 *            client does not); {@code null} otherwise
 * @param headers
 *            response headers, case-insensitive by name
 * @param finalUri
 *            the URI that produced this response
 * @param redirectChain
 *            the URIs redirected away from, in order; empty when none
 * @param body
 *            the body as the {@link BodyMode} delivered it
 * @param truncated
 *            whether the body was cut short at the cap or the read budget
 *            ({@link BodyMode.OnOverflow#TRUNCATE} only)
 */
public record OutboundResponse<T>(int status, String reasonPhrase, Map<String, List<String>> headers, URI finalUri,
        List<URI> redirectChain, T body, boolean truncated) {

    public OutboundResponse {
        redirectChain = List.copyOf(redirectChain);
    }

    /** The first value of header {@code name}, matched case-insensitively. */
    public Optional<String> firstHeader(String name) {
        List<String> values = headers.get(name);
        return values == null || values.isEmpty() ? Optional.empty() : Optional.ofNullable(values.getFirst());
    }
}
