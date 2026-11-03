/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.updatecheck;

import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/** REST implementation of {@link IRestUpdateCheck}. */
@ApplicationScoped
public class RestUpdateCheck implements IRestUpdateCheck {

    private final UpdateCheckService service;
    private final SecurityIdentity identity;

    @Inject
    public RestUpdateCheck(UpdateCheckService service, SecurityIdentity identity) {
        this.service = service;
        this.identity = identity;
    }

    @Override
    public UpdateCheckService.Status read() {
        return service.status();
    }

    @Override
    public UpdateCheckService.Status update(Update update) {
        String user = identity == null || identity.isAnonymous() ? null : identity.getPrincipal().getName();
        return service.setUiEnabled(update != null && update.enabled(), user);
    }
}
