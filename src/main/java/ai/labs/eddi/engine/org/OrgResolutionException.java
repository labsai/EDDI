/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.org;

import io.quarkus.security.ForbiddenException;

/**
 * The request names no org the resolver accepts. It is a 403, never a fallback
 * to the default org (plan §9.1). The message never names another org.
 */
public class OrgResolutionException extends ForbiddenException {

    public OrgResolutionException(String message) {
        super("[SECURITY] " + message);
    }
}
