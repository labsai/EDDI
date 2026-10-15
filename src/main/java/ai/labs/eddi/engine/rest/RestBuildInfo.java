/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.rest;

import ai.labs.eddi.engine.api.IRestBuildInfo;
import ai.labs.eddi.engine.api.model.PlatformBuildInfo;
import ai.labs.eddi.engine.runtime.BuildInfo;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/** REST implementation of {@link IRestBuildInfo}. */
@ApplicationScoped
public class RestBuildInfo implements IRestBuildInfo {

    private final BuildInfo buildInfo;

    @Inject
    public RestBuildInfo(BuildInfo buildInfo) {
        this.buildInfo = buildInfo;
    }

    @Override
    public PlatformBuildInfo getVersion() {
        return buildInfo.get();
    }
}
