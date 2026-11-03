/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.updatecheck;

import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.jboss.resteasy.reactive.NoCache;

/**
 * Whether this deployment checks for new EDDI releases. Reading is open to
 * every Manager role (the banner needs it); changing it is {@code eddi-admin}
 * only, and REST has no role hierarchy.
 */
@Path("/administration/update-check")
@Tag(name = "Operations / Update check", description = "Check for new EDDI releases and notify the administrators")
public interface IRestUpdateCheck {

    /** The request body of {@link #update(Update)}. */
    record Update(boolean enabled) {
    }

    @GET
    @NoCache
    @RolesAllowed({"eddi-admin", "eddi-editor", "eddi-user", "eddi-viewer"})
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "Is the update check on?", description = "enabled: checks run now. lockedByConfig: "
            + "eddi.update-check.enabled=false switches the check off for good and the stored switch cannot turn it on. "
            + "uiEnabled: the stored switch alone.")
    @APIResponse(responseCode = "200", description = "{enabled, lockedByConfig, uiEnabled}")
    UpdateCheckService.Status read();

    @PUT
    @RolesAllowed("eddi-admin")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "Turn the update check on or off", description = "Stores the switch for every node and across restarts. "
            + "Has no effect while eddi.update-check.enabled=false (lockedByConfig).")
    @APIResponse(responseCode = "200", description = "The resulting status.")
    UpdateCheckService.Status update(Update update);
}
