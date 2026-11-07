/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.rest;

import ai.labs.eddi.engine.api.IRestAgentSetup;
import ai.labs.eddi.engine.setup.AgentSetupService;
import ai.labs.eddi.engine.setup.AgentSetupService.AgentSetupException;
import ai.labs.eddi.engine.setup.AgentSetupService.AgentSetupForbiddenException;
import ai.labs.eddi.engine.setup.ApiAgentSyncService;
import ai.labs.eddi.engine.setup.ApiAgentSyncService.AgentSyncNotFoundException;
import ai.labs.eddi.engine.setup.ApiAgentSyncService.DanglingReferencesException;
import ai.labs.eddi.engine.setup.CreateApiAgentRequest;
import ai.labs.eddi.engine.setup.SetupAgentRequest;
import ai.labs.eddi.engine.setup.SetupResult;
import ai.labs.eddi.engine.setup.SyncApiAgentRequest;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import java.util.Map;
import java.util.UUID;
import org.jboss.logging.Logger;

/**
 * REST implementation for one-command agent setup. Thin adapter that delegates
 * to {@link AgentSetupService} and {@link ApiAgentSyncService}.
 *
 * @author ginccc
 */
@ApplicationScoped
public class RestAgentSetup implements IRestAgentSetup {

    private static final Logger LOGGER = Logger.getLogger(RestAgentSetup.class);

    private final AgentSetupService agentSetupService;
    private final ApiAgentSyncService apiAgentSyncService;

    @Inject
    public RestAgentSetup(AgentSetupService agentSetupService, ApiAgentSyncService apiAgentSyncService) {
        this.agentSetupService = agentSetupService;
        this.apiAgentSyncService = apiAgentSyncService;
    }

    @Override
    public Response setupAgent(SetupAgentRequest request) {
        try {
            SetupResult result = agentSetupService.setupAgent(request);
            return Response.status(Response.Status.CREATED).entity(result).build();
        } catch (AgentSetupForbiddenException e) {
            return Response.status(Response.Status.FORBIDDEN).entity(Map.of("error", e.getMessage())).build();
        } catch (AgentSetupException e) {
            LOGGER.warnf("Agent setup validation failed: %s", e.getMessage());
            return Response.status(Response.Status.BAD_REQUEST).entity(Map.of("error", e.getMessage())).build();
        } catch (Exception e) {
            return opaqueServerError("Agent setup failed", e);
        }
    }

    @Override
    public Response createApiAgent(CreateApiAgentRequest request) {
        try {
            SetupResult result = agentSetupService.createApiAgent(request);
            // A dry run created nothing, so it is not a 201.
            boolean dryRun = request != null && Boolean.TRUE.equals(request.dryRun());
            return Response.status(dryRun ? Response.Status.OK : Response.Status.CREATED).entity(result).build();
        } catch (AgentSetupForbiddenException e) {
            return Response.status(Response.Status.FORBIDDEN).entity(Map.of("error", e.getMessage())).build();
        } catch (AgentSetupException e) {
            LOGGER.warnf("API agent setup validation failed: %s", e.getMessage());
            return Response.status(Response.Status.BAD_REQUEST).entity(Map.of("error", e.getMessage())).build();
        } catch (Exception e) {
            return opaqueServerError("API agent setup failed", e);
        }
    }

    @Override
    public Response syncApiAgent(String agentId, SyncApiAgentRequest request) {
        try {
            return Response.ok(apiAgentSyncService.sync(agentId, request)).build();
        } catch (AgentSyncNotFoundException e) {
            return Response.status(Response.Status.NOT_FOUND).entity(Map.of("error", e.getMessage())).build();
        } catch (DanglingReferencesException e) {
            return Response.status(Response.Status.BAD_REQUEST).entity(Map.of("error", e.getMessage(), "references", e.references())).build();
        } catch (AgentSetupException e) {
            LOGGER.warnf("API agent sync refused: %s", e.getMessage());
            return Response.status(Response.Status.BAD_REQUEST).entity(Map.of("error", e.getMessage())).build();
        } catch (Exception e) {
            return opaqueServerError("API agent sync failed", e);
        }
    }

    /**
     * The answer to a failure none of the arms above maps to a 4xx. Its message is
     * not written for the caller: a database timeout names the replica it waited
     * for, a REST proxy failure the store it could not reach. So the full exception
     * goes to the log under a correlation id and the caller gets only the context
     * and the id, as from {@code ResourceStoreExceptionMapper} and
     * {@code RestAgentManagement}. The body keeps its {@code error} key, which the
     * Manager reads.
     */
    private static Response opaqueServerError(String context, Exception e) {
        String correlationId = UUID.randomUUID().toString();
        LOGGER.errorf(e, "%s [correlationId=%s]", context, correlationId);
        return Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity(Map.of("error", context + " (correlationId: " + correlationId + ")"))
                .build();
    }
}
