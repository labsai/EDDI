/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.agenttests;

import ai.labs.eddi.configs.IRestVersionInfo;
import ai.labs.eddi.configs.agenttests.model.AgentTestSuite;
import ai.labs.eddi.configs.descriptors.model.DocumentDescriptor;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.util.List;

/**
 * REST API for {@link AgentTestSuite} documents — versioned configuration, like
 * every other store. Running a suite is {@code IRestAgentTestRuns}.
 *
 * @since 6.5.0
 */
@Path("/testsuitestore/testsuites")
@Tag(name = "Configuration / Agent Test Suites", description = "Golden-conversation regression suites for agents")
@RolesAllowed({"eddi-admin", "eddi-editor"})
public interface IRestAgentTestSuiteStore extends IRestVersionInfo {
    String resourceBaseType = "eddi://ai.labs.testsuite";
    String resourceURI = resourceBaseType + "/testsuitestore/testsuites/";
    String versionQueryParam = "?version=";

    @GET
    @Path("descriptors")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(operationId = "readTestSuiteDescriptors", description = "Read list of agent test suite descriptors.")
    List<DocumentDescriptor> readTestSuiteDescriptors(@QueryParam("filter")
    @DefaultValue("") String filter,
                                                      @QueryParam("index")
                                                      @DefaultValue("0") Integer index,
                                                      @QueryParam("limit")
                                                      @DefaultValue("20") Integer limit);

    @GET
    @Path("/{id}")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(operationId = "readTestSuite", description = "Read an agent test suite.")
    AgentTestSuite readTestSuite(@PathParam("id") String id,
                                 @Parameter(name = "version", required = true, example = "1")
                                 @QueryParam("version") Integer version);

    @PUT
    @Path("/{id}")
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(operationId = "updateTestSuite", description = "Update an agent test suite (creates a new version).")
    Response updateTestSuite(@PathParam("id") String id,
                             @Parameter(name = "version", required = true, example = "1")
                             @QueryParam("version") Integer version,
                             AgentTestSuite suite);

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(operationId = "createTestSuite", description = "Create an agent test suite.")
    Response createTestSuite(AgentTestSuite suite);

    @DELETE
    @Path("/{id}")
    @Operation(operationId = "deleteTestSuite", description = "Delete an agent test suite.")
    Response deleteTestSuite(@PathParam("id") String id,
                             @Parameter(name = "version", required = true, example = "1")
                             @QueryParam("version") Integer version,
                             @QueryParam("permanent")
                             @DefaultValue("false") Boolean permanent);
}
