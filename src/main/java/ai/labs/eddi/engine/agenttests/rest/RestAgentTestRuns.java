/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.agenttests.rest;

import ai.labs.eddi.engine.agenttests.AgentTestService;
import ai.labs.eddi.engine.agenttests.IRestAgentTestRuns;
import ai.labs.eddi.engine.agenttests.model.AgentTestRun;
import ai.labs.eddi.engine.agenttests.model.StartRunRequest;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.core.Response;

import java.net.URI;
import java.util.List;

/**
 * REST implementation of {@link IRestAgentTestRuns}; everything is in
 * {@link AgentTestService}.
 *
 * @since 6.5.0
 */
@ApplicationScoped
public class RestAgentTestRuns implements IRestAgentTestRuns {

    private final AgentTestService service;

    @Inject
    public RestAgentTestRuns(AgentTestService service) {
        this.service = service;
    }

    @Override
    public Response startRun(StartRunRequest request) {
        if (request == null) {
            throw new BadRequestException("A body with suiteId and agentId is required.");
        }
        AgentTestRun run = service.startRun(request.suiteId(), request.suiteVersion(), request.agentId(), request.agentVersion(),
                Boolean.TRUE.equals(request.keepConversations()));
        return Response.accepted(run).location(URI.create("/agenttests/runs/" + run.id())).build();
    }

    @Override
    public Response cancelRun(String runId) {
        return Response.accepted(service.cancelRun(runId)).build();
    }

    @Override
    public AgentTestRun readRun(String runId) {
        return service.readRun(runId);
    }

    @Override
    public List<AgentTestRun> listRuns(String agentId, Integer index, Integer limit) {
        return service.listRuns(agentId, index != null ? index : 0, limit != null ? limit : 20);
    }
}
