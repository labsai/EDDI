/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.org;

import jakarta.enterprise.context.ApplicationScoped;

/**
 * {@code eddi.org.resolver=single}: every request belongs to
 * {@link OrgId#DEFAULT}. It reads nothing from the request, so the
 * {@code X-EDDI-Org} header has no effect in single mode.
 */
@ApplicationScoped
public class SingleOrgResolver implements OrgResolver {

    public static final String NAME = "single";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public OrgId resolve(OrgRequestFacts facts) {
        return OrgId.DEFAULT;
    }
}
