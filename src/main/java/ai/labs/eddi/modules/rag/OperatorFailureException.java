/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.rag;

/**
 * A failure whose message EDDI wrote itself, for the operator who has to act on
 * it — "this version runs exactly one partition per source", a connector's own
 * "the grant was revoked". {@link RagFailures#clientMessage} passes it through
 * as it is, where any other exception becomes a fixed sentence: a message that
 * came from a driver or a provider is not the caller's to read.
 */
public class OperatorFailureException extends IllegalStateException {

    public OperatorFailureException(String message) {
        super(message);
    }

    public OperatorFailureException(String message, Throwable cause) {
        super(message, cause);
    }
}
