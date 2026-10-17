/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.httpclient.outbound;

import java.io.IOException;

/**
 * A response body over the caller's cap, read with
 * {@link BodyMode.OnOverflow#FAIL}. The read stopped at the cap — the rest was
 * never downloaded — and the connection was closed.
 */
public class BodyTooLargeException extends IOException {

    private final long maxBytes;

    public BodyTooLargeException(long maxBytes) {
        super("Response body exceeds the limit of " + maxBytes + " bytes");
        this.maxBytes = maxBytes;
    }

    public long maxBytes() {
        return maxBytes;
    }
}
