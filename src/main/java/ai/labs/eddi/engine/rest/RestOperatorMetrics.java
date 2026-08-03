/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.rest;

import ai.labs.eddi.engine.api.IRestOperatorMetrics;
import ai.labs.eddi.engine.api.OperatorMetricsService;
import ai.labs.eddi.engine.api.model.OperatorCanaryReport;
import ai.labs.eddi.engine.api.model.OperatorGateStatusReport;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.core.Response;

/**
 * REST implementation of {@link IRestOperatorMetrics}. Validates, then
 * delegates to {@link OperatorMetricsService}.
 */
@ApplicationScoped
public class RestOperatorMetrics implements IRestOperatorMetrics {

    private final OperatorMetricsService operatorMetricsService;

    @Inject
    public RestOperatorMetrics(OperatorMetricsService operatorMetricsService) {
        this.operatorMetricsService = operatorMetricsService;
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
}
