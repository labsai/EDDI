/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.api;

import ai.labs.eddi.engine.api.model.PlatformBuildInfo;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.jboss.resteasy.reactive.NoCache;

/**
 * The version and git commit of the running EDDI.
 * <p>
 * Exists so the Platform Operator can state which build it is running on
 * without relying on anything written into its own agent document, which goes
 * stale at the first upgrade. Admin-only: a precise build identifier is a
 * fingerprint.
 *
 * @since 6.5.0
 */
@Path("/administration/version")
@Tag(name = "Operations / Version", description = "Which build of EDDI is running")
@RolesAllowed("eddi-admin")
public interface IRestBuildInfo {

    @GET
    @NoCache
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "Get the running EDDI version and git commit",
               description = "Answers from the running process, so it is correct immediately after an upgrade. "
                       + "commit is 'unknown' when the build recorded none.")
    @APIResponse(responseCode = "200", description = "Version, full commit and 12-character short commit.")
    PlatformBuildInfo getVersion();
}
