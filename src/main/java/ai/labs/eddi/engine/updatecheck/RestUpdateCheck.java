/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.updatecheck;

import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;

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
        String user = identity == null || identity.isAnonymous() || identity.getPrincipal() == null ? null : identity.getPrincipal().getName();
        if (update == null || update.enabled() == null) {
            // A missing body or field must not read as "off" for every node.
            throw new BadRequestException("A body with a boolean 'enabled' is required");
        }
        return service.setUiEnabled(update.enabled(), user);
    }
}
