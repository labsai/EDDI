/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.connections.settings;

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

/**
 * The deployment-level settings of the connections feature, changeable without
 * a restart.
 * <p>
 * <b>Admin-only, and only here.</b> The same role that writes connections and
 * the vault, because the power is the same: whoever can write a vault reference
 * into an httpcall header can already send that secret to any host, so a
 * properties-only allowlist bought a restart and no protection against an
 * administrator. What it DOES still have to be kept from is everything that is
 * not an administrator acting deliberately — so this surface is deliberately
 * absent from the MCP tools (an LLM must never widen where client secrets may
 * go), from agent export and import, and from Agent Sync. It is deployment
 * topology, not agent configuration.
 * <p>
 * An operator who does separate the two roles pins a value with its property; a
 * write that would change a pinned value answers 409 naming the property.
 */
@Path("/connectionstore/settings")
@Tag(name = "Configuration / Connections", description = "How to authenticate to an external system")
@RolesAllowed("eddi-admin")
public interface IRestConnectionSettings {

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(operationId = "readConnectionSettings", summary = "Read the effective connection settings",
               description = "Each value carries its source (PINNED, STORED or DEFAULT) and the property that pins it.")
    @APIResponse(responseCode = "200", description = "The effective settings.")
    ConnectionSettingsView readSettings();

    @PUT
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(operationId = "updateConnectionSettings", summary = "Replace the stored connection settings",
               description = "A null field is unset and falls back to its default. Takes effect on this instance immediately and on "
                       + "every other instance within five seconds.")
    @APIResponse(responseCode = "200", description = "Stored; the effective settings are returned.")
    @APIResponse(responseCode = "400", description = "A value is malformed; the message names the field.")
    @APIResponse(responseCode = "409", description = "A field is pinned by a property and the request would change it.")
    ConnectionSettingsView updateSettings(ConnectionSettings settings);
}
