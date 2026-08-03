/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.api;

import ai.labs.eddi.engine.api.model.OperatorCanaryReport;
import ai.labs.eddi.engine.api.model.OperatorGateStatusReport;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * Lets the Manager report the outcome of a client-driven operator check onto
 * this deployment's {@code /q/metrics}.
 * <p>
 * The write canary and the gate-installed check both run entirely in the
 * Manager: one drives a synthetic conversation and inspects its pause, the
 * other re-reads every version of the operator agent document. Neither has a
 * server-side equivalent — this deployment has no first-class notion of "the
 * operator", just an agent like any other — so what this endpoint provides is
 * purely visibility: an on-call engineer watching Grafana should not have to
 * have a Manager tab open to see whether the write gate is currently sound.
 * <p>
 * <b>This is not a verification endpoint.</b> A report is trusted at face
 * value, which is exactly why it sits behind {@code eddi-admin} — the same tier
 * that can provision the operator in the first place. Anyone who could
 * misreport through this endpoint could just as easily reconfigure the operator
 * directly.
 *
 * @since 6.2.0
 */
@Path("/administration/operator")
@Tag(name = "Operations / Operator Metrics", description = "Client-reported operator canary and gate-verification outcomes")
@RolesAllowed("eddi-admin")
public interface IRestOperatorMetrics {

    @POST
    @Path("/canary-result")
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(summary = "Report a write canary outcome",
               description = "Records the result of a client-run write canary (a synthetic conversation that provokes and then rejects a real "
                       + "gated write) as eddi.operator.canary{outcome} and eddi.operator.canary.duration.")
    @APIResponse(responseCode = "204", description = "Recorded.")
    @APIResponse(responseCode = "400", description = "outcome was missing or not one of pass/fail/unknown.")
    Response reportCanaryResult(OperatorCanaryReport report);

    @POST
    @Path("/gate-status")
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(summary = "Report a gate-verification outcome",
               description = "Sets the eddi.operator.gate.verified gauge to 1 when every provisioned version of the operator agent read back "
                       + "with a sound approval gate, 0 otherwise. This is the meter worth alerting on.")
    @APIResponse(responseCode = "204", description = "Recorded.")
    Response reportGateStatus(OperatorGateStatusReport report);
}
