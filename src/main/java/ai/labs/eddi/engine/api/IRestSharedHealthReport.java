/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.api;

import jakarta.annotation.security.PermitAll;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * The public side of an owner report link (H4b): the health of one agent, as
 * the link allows, for someone without a Manager account.
 * <p>
 * <b>Unauthenticated by design</b> — the link's token is the credential. This
 * path (and only this one: {@code /health/reports/*}, {@code GET}/{@code HEAD})
 * is on the {@code health-report} permit rule of
 * {@code application.properties}; every other health endpoint, the owner-side
 * link management under {@code /agents/{agentId}/health/reports} included,
 * stays authenticated. Read-only, rate-limited, never cached, no CORS of its
 * own; an unknown, expired or revoked token is a plain 404.
 */
@Path("/health/reports")
@Tag(name = "Agent Health", description = "Reliability, efficiency, adoption and quality of an agent over time, per version")
// deny-unannotated-endpoints denies anything unannotated; the token is checked
// by the resource itself.
@PermitAll
public interface IRestSharedHealthReport {

    @GET
    @Path("/{token}")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "Read a shared health report", description = "Unauthenticated: the token of an owner report link is the "
            + "credential. The agent's health over the link's period, limited to its sections, with the deployment's brand; no "
            + "drill-down, no conversation ids, no user data. Rate-limited per link and per node.")
    @APIResponse(responseCode = "200", description = "The report.")
    @APIResponse(responseCode = "404", description = "No live link has this token (unknown, expired or revoked alike).")
    @APIResponse(responseCode = "429", description = "Too many requests; Retry-After says when to try again.")
    Response readSharedReport(@PathParam("token") String token, @HeaderParam("Host") String host);
}
