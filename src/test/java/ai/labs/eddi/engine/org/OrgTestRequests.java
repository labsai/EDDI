/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.org;

/**
 * Test support for classes that bind the request org through
 * {@link OrgRequest}.
 */
public final class OrgTestRequests {

    private OrgTestRequests() {
    }

    /**
     * An {@link OrgRequest} as single mode builds it: every request is
     * {@link OrgId#DEFAULT}. Resolution reads nothing in single mode, so no CDI
     * instances are needed.
     */
    public static OrgRequest singleMode() {
        return new OrgRequest(new OrgResolution(null, null, null, SingleOrgResolver.NAME));
    }
}
