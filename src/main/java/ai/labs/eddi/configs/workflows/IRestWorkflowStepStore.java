/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.workflows;

import ai.labs.eddi.configs.workflows.model.ExtensionDescriptor;
import jakarta.annotation.security.RolesAllowed;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import java.util.List;

/**
 * @author ginccc
 */
@Path("/extensionstore/extensions")
@Tag(name = "Configuration / Workflows", description = "Workflow pipeline configuration and available steps")
@RolesAllowed({"eddi-admin", "eddi-editor"})
public interface IRestWorkflowStepStore {
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    List<ExtensionDescriptor> getWorkflowSteps(@QueryParam("filter")
    @DefaultValue("") String filter);
}
