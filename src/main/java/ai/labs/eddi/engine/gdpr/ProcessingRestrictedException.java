/*
 * Copyright (c) 2016-2026 EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.gdpr;

/**
 * Thrown when a conversation operation is attempted for a user whose processing
 * has been restricted under GDPR Art. 18 (Right to Restriction of Processing).
 *
 * @author ginccc
 * @since 6.0.0
 */
public class ProcessingRestrictedException extends RuntimeException {

    public ProcessingRestrictedException(String message) {
        super(message);
    }
}
