/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.httpclient.outbound;

/**
 * Decides whether a URL may be contacted, by throwing
 * {@link IllegalArgumentException} when it may not.
 */
@FunctionalInterface
public interface TargetValidator {

    void validate(String url);
}
