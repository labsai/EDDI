/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.attachments.blob;

import java.io.IOException;

/**
 * A {@link BlobBackend#put} stream did not have the length it was allowed or
 * declared to have. Thrown after the partial object was removed.
 */
public class BlobLengthException extends IOException {

    /** Which bound the stream broke. */
    public enum Reason {
        /** Longer than the declared length. */
        EXCEEDS_DECLARED_LENGTH,
        /** Longer than the call's {@code maxBytes}, or declared above it. */
        EXCEEDS_MAX_BYTES,
        /** Ended before the declared length. */
        SHORTER_THAN_DECLARED
    }

    private final Reason reason;

    public BlobLengthException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
