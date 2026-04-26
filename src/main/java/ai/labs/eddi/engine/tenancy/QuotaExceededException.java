/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.tenancy;

/**
 * Thrown when a tenant's quota has been exceeded. Mapped to HTTP 429 (Too Many
 * Requests) by
 * {@link ai.labs.eddi.engine.tenancy.rest.QuotaExceededExceptionMapper}.
 */
public class QuotaExceededException extends RuntimeException {

    public QuotaExceededException(String message) {
        super(message);
    }
}
