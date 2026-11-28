/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.org;

import io.quarkus.security.ForbiddenException;

/**
 * The request names no org the resolver accepts. It is a 403, never a fallback
 * to the default org (plan §9.1).
 * <p>
 * {@link #getMessage()} is the fixed {@link #CLIENT_MESSAGE}: REST and MCP
 * error paths return it to the caller, so it may never carry a claim value, a
 * header or an org id. The reason, which a resolver may phrase from request
 * data, is kept in {@link #detail()} for server-side logging only.
 */
public class OrgResolutionException extends ForbiddenException {

    public static final String CLIENT_MESSAGE = "Organization could not be resolved for this request";

    private final String detail;

    public OrgResolutionException(String detail) {
        super(CLIENT_MESSAGE);
        this.detail = detail;
    }

    /** Why resolution failed, for server logs only. */
    public String detail() {
        return detail;
    }
}
