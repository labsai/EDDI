/*
 * Copyright (c) 2016-2026 EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.output.keys;

import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import java.util.List;

/**
 * @author ginccc
 */
@Path("/outputstore/actions")
@Tag(name = "Output")
public interface IRestOutputActions {
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    List<String> readOutputActions(@QueryParam("workflowId") String workflowId, @QueryParam("workflowVersion") Integer workflowVersion,
                                   @QueryParam("filter")
                                   @DefaultValue("") String filter,
                                   @QueryParam("limit")
                                   @DefaultValue("20") Integer limit);
}
