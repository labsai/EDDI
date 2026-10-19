/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.httpclient.outbound;

import java.net.http.HttpTimeoutException;

/**
 * The exchange ran past its wall-clock budget — the whole-exchange deadline or
 * a body's read budget — and was abandoned, its connection closed.
 * <p>
 * An {@link HttpTimeoutException}, so callers that already catch the JDK's
 * timeout type, and {@code LlmFailureClassifier}, see the same thing.
 */
public class OutboundTimeoutException extends HttpTimeoutException {

    public OutboundTimeoutException(String message) {
        super(message);
    }
}
