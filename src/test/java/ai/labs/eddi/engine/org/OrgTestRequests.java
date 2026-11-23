/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.org;

import java.util.concurrent.Callable;

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

    /**
     * An {@link OrgRequest} for a caller with no org, as a multi-mode resolver
     * answers for a platform operator; it counts how often it was asked.
     */
    public static Refusing refusing() {
        return new Refusing();
    }

    /** See {@link #refusing()}. */
    public static final class Refusing extends OrgRequest {
        private int resolutions;

        private Refusing() {
            super(null);
        }

        @Override
        public <T> T callBound(Callable<T> work) {
            resolutions++;
            throw new OrgResolutionException("test: the caller has no org");
        }

        @Override
        public void runBound(Runnable work) {
            resolutions++;
            throw new OrgResolutionException("test: the caller has no org");
        }

        /** How often an org was resolved. */
        public int resolutions() {
            return resolutions;
        }
    }
}
