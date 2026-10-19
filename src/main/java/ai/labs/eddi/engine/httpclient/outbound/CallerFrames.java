/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.httpclient.outbound;

/**
 * Carries the calling thread's stack trace into an exception created on an HTTP
 * client's own threads, as a suppressed exception.
 * <p>
 * A failure raised on a selector or executor thread has only that thread's
 * frames, so a log line would not show who made the call. Re-creating the
 * exception, as the JDK's synchronous {@code send} does, would lose its
 * specific type, which callers catch by.
 */
public final class CallerFrames extends Exception {

    public CallerFrames() {
        super("rethrown to the caller of an outbound HTTP request");
    }

    /** Attaches the current thread's frames to {@code failure} and returns it. */
    public static <T extends Throwable> T attachTo(T failure) {
        failure.addSuppressed(new CallerFrames());
        return failure;
    }
}
