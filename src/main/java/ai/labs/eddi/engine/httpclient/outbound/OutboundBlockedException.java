/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.httpclient.outbound;

/**
 * A target the SSRF policy refuses — the initial URL or a redirect hop. Nothing
 * was sent to it.
 * <p>
 * An {@link IllegalArgumentException}, with the validator's message unchanged,
 * because that is what {@code UrlValidationUtils} has always thrown and every
 * existing caller catches.
 */
public class OutboundBlockedException extends IllegalArgumentException {

    public OutboundBlockedException(String message, Throwable cause) {
        super(message, cause);
    }
}
