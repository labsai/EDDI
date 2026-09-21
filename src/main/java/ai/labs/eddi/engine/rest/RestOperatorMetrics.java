/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.rest;

import ai.labs.eddi.configs.agents.IAgentStore;
import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.configs.hitl.model.ToolApprovalsConfig;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.engine.api.IRestOperatorMetrics;
import ai.labs.eddi.engine.api.OperatorMetricsService;
import ai.labs.eddi.engine.api.model.OperatorCanaryReport;
import ai.labs.eddi.engine.api.model.OperatorGateDryRunRequest;
import ai.labs.eddi.engine.api.model.OperatorGateDryRunResult;
import ai.labs.eddi.engine.api.model.OperatorGateStatusReport;
import ai.labs.eddi.engine.hitl.tools.ToolApprovalGate;
import ai.labs.eddi.engine.hitl.tools.ToolApprovalPatterns;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.InternalServerErrorException;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.core.Response;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * REST implementation of {@link IRestOperatorMetrics}. Validates, then
 * delegates to {@link OperatorMetricsService} — except {@link #gateDryRun},
 * which answers directly from the agent store and the gate.
 */
@ApplicationScoped
public class RestOperatorMetrics implements IRestOperatorMetrics {

    private final OperatorMetricsService operatorMetricsService;
    private final IAgentStore agentStore;
    /** Stateless — the same construction the orchestrator uses. */
    private final ToolApprovalGate toolApprovalGate = new ToolApprovalGate();

    @Inject
    public RestOperatorMetrics(OperatorMetricsService operatorMetricsService, IAgentStore agentStore) {
        this.operatorMetricsService = operatorMetricsService;
        this.agentStore = agentStore;
    }

    @Override
    public Response reportCanaryResult(OperatorCanaryReport report) {
        // Distinguished, not collapsed: telling a caller who sent no body that its
        // "outcome" is wrong sends them looking at a field they never sent.
        if (report == null) {
            throw new BadRequestException("request body is required");
        }
        if (!OperatorMetricsService.isValidOutcome(report.outcome())) {
            throw new BadRequestException("outcome must be one of: pass, fail, unknown");
        }
        operatorMetricsService.recordCanaryResult(report.outcome(), report.durationMs());
        return Response.noContent().build();
    }

    @Override
    public Response reportGateStatus(OperatorGateStatusReport report) {
        if (report == null) {
            throw new BadRequestException("request body is required");
        }
        operatorMetricsService.recordGateStatus(report.verified());
        return Response.noContent().build();
    }

    @Override
    public OperatorGateDryRunResult gateDryRun(OperatorGateDryRunRequest request) {
        if (request == null) {
            throw new BadRequestException("request body is required");
        }
        if (request.agentId() == null || request.agentId().isBlank()) {
            throw new BadRequestException("agentId is required");
        }
        if (request.version() == null || request.version() < 1) {
            throw new BadRequestException("version is required and must be >= 1 — classification runs against the "
                    + "pinned document, never \"latest\"");
        }
        if (request.toolName() == null || request.toolName().isBlank()) {
            throw new BadRequestException("toolName is required");
        }

        AgentConfiguration agentConfig;
        try {
            agentConfig = agentStore.read(request.agentId(), request.version());
        } catch (IResourceStore.ResourceNotFoundException e) {
            throw new NotFoundException("no agent document at id=" + request.agentId() + " version=" + request.version());
        } catch (IResourceStore.ResourceStoreException e) {
            // A store failure must not read as "no policy" — the caller uses this
            // answer to decide whether a write-capable agent is safely gated.
            throw new InternalServerErrorException("could not read the agent document");
        }
        if (agentConfig == null) {
            throw new NotFoundException("no agent document at id=" + request.agentId() + " version=" + request.version());
        }

        ToolApprovalsConfig approvals = agentConfig.getHitlConfig() != null
                ? agentConfig.getHitlConfig().getToolApprovals()
                : null;
        boolean policyPresent = approvals != null
                && approvals.getRequireApproval() != null
                && !approvals.getRequireApproval().isEmpty();
        if (!policyPresent) {
            // classify() would answer the same, but saying it explicitly keeps the
            // policyPresent flag and the gated flag from ever disagreeing.
            return new OperatorGateDryRunResult(false, false, null);
        }

        // Exactly the shapes ToolLoopRunner hands to classify(): the source map the
        // registry builds per tool, and — for http tools — the method:path endpoint
        // recorded at discovery. Discovery lower-cases ONLY the method
        // (HttpCallToolsProvider builds method.toLowerCase() + ":" + path with the
        // path case preserved), so this endpoint must do the same: lower-casing the
        // whole string would make the dry-run classify differently from the runtime
        // gate for any spec with an upper-case path segment. The path is taken
        // verbatim and must be supplied in discovery's normalized form.
        String source = request.source() == null || request.source().isBlank()
                ? "http"
                : request.source().trim().toLowerCase(Locale.ROOT);
        // An unknown source can never match a source-qualified pattern, so it would
        // classify as ungated with full confidence — the same silent fail-open the
        // method-case normalization exists to prevent, one field over. Reject it.
        if (!ToolApprovalPatterns.KNOWN_SOURCES.contains(source)) {
            throw new BadRequestException("unknown source '" + source + "' — known sources: "
                    + String.join(", ", ToolApprovalPatterns.KNOWN_SOURCES));
        }
        Map<String, String> toolSources = Map.of(request.toolName(), source);
        Map<String, String> toolEndpoints = request.endpoint() == null || request.endpoint().isBlank()
                ? Map.of()
                : Map.of(request.toolName(), lowerCaseMethodOnly(request.endpoint().trim()));

        ToolExecutionRequest synthetic = ToolExecutionRequest.builder()
                .id("gate-dry-run")
                .name(request.toolName())
                .arguments("{}")
                .build();
        ToolApprovalGate.GateResult result = toolApprovalGate.classify(
                List.of(synthetic), toolSources, toolEndpoints, approvals, Set.of());

        boolean gated = !result.gated().isEmpty();
        return new OperatorGateDryRunResult(true, gated, gated ? result.gateReasonByCallId().get("gate-dry-run") : null);
    }

    /**
     * {@code "PATCH:/Descriptors/{id}"} → {@code "patch:/Descriptors/{id}"} — the
     * exact normalization discovery applies when it records an endpoint. The path
     * keeps its case; an endpoint without a {@code :} is returned lower-cased
     * whole, since it can only be a bare method.
     */
    private static String lowerCaseMethodOnly(String endpoint) {
        int colon = endpoint.indexOf(':');
        if (colon < 0) {
            return endpoint.toLowerCase(Locale.ROOT);
        }
        return endpoint.substring(0, colon).toLowerCase(Locale.ROOT) + endpoint.substring(colon);
    }
}
