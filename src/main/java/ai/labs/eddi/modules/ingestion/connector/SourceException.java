/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.ingestion.connector;

import java.util.Objects;

/**
 * A classified failure thrown by a {@link SourceConnector}.
 *
 * <p>
 * Checked on purpose: a connector author has to decide what each provider
 * failure means for the knowledge base, rather than letting an unclassified
 * exception decide it for them.
 */
public class SourceException extends Exception {

    private final transient SourceError error;

    public SourceException(SourceError error) {
        this(error, null);
    }

    public SourceException(SourceError error, Throwable cause) {
        super(Objects.requireNonNull(error, "error").reason(), cause);
        this.error = error;
    }

    public SourceError error() {
        return error;
    }
}
