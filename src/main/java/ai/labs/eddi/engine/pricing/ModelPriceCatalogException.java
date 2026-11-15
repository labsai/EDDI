/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.pricing;

import java.util.List;

/**
 * A refused catalog write: {@link #INVALID} with the violations, or
 * {@link #CONFLICT} with the revision now stored.
 */
public class ModelPriceCatalogException extends RuntimeException {

    public static final int INVALID = 400;
    public static final int CONFLICT = 409;

    private final int status;
    private final List<String> violations;
    private final Long currentRevision;

    public ModelPriceCatalogException(int status, String message, List<String> violations, Long currentRevision) {
        super(message);
        this.status = status;
        this.violations = violations == null ? List.of() : List.copyOf(violations);
        this.currentRevision = currentRevision;
    }

    public int status() {
        return status;
    }

    public List<String> violations() {
        return violations;
    }

    public Long currentRevision() {
        return currentRevision;
    }
}
