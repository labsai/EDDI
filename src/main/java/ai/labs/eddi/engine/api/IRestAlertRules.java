/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.api;

import ai.labs.eddi.engine.alerts.model.AlertRule;
import ai.labs.eddi.engine.alerts.model.DeliveryTarget.DeliveryType;
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
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.util.List;

/**
 * Alert rules: which agents, which source and condition, where to deliver.
 *
 * <h3>Who may do what</h3> Administrators write any rule. Editors write rules
 * for exactly one agent they hold {@code EDIT} on, with Slack targets and the
 * inbox only — a rule over every agent or several is an administrator's
 * ({@code ADMIN_SCOPE_REQUIRED}), and so is a webhook target
 * ({@code WEBHOOK_ADMIN_ONLY}: an arbitrary URL, signed with a vault secret).
 * Access is checked against the stored rule on update and delete as well. An
 * editor lists the rules of an agent they name; a webhook target is shown to
 * them by host only. No role hierarchy in REST: the roles are enumerated.
 *
 * <h3>Webhook URLs and {@code targetId}</h3> A read never returns a webhook
 * URL: it answers scheme and host plus {@code /***}. Every delivery target
 * carries a server-assigned {@code targetId}; an update or test send that
 * returns a masked or empty URL keeps the stored URL of the target with the
 * same {@code targetId}, and a masked or empty URL without a known id is
 * refused.
 */
@Path("/alerts/rules")
@Tag(name = "Alerts", description = "Alert rules and the alert inbox")
@RolesAllowed({"eddi-admin", "eddi-editor"})
public interface IRestAlertRules {

    record RulePage(long total, int index, int limit, List<AlertRule> rules) {
    }

    /** What a test send did, per target. */
    record TestResult(List<TestDelivery> deliveries) {
    }

    record TestDelivery(DeliveryType type, String label, boolean delivered, String error) {
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "List alert rules", description = "Newest first. agentId (the rules that list that agent) is required unless the "
            + "caller is an administrator.")
    @APIResponse(responseCode = "200", description = "One page of rules.")
    @APIResponse(responseCode = "400", description = "agentId missing for a non-administrator.")
    @APIResponse(responseCode = "403", description = "The caller does not hold EDIT on the agent.")
    RulePage listRules(@QueryParam("agentId") String agentId,
                       @QueryParam("index")
                       @DefaultValue("0") Integer index,
                       @QueryParam("limit")
                       @DefaultValue("20") Integer limit);

    @GET
    @Path("/{ruleId}")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "Read an alert rule")
    @APIResponse(responseCode = "200", description = "The rule.")
    @APIResponse(responseCode = "403", description = "The caller may not see this rule.")
    @APIResponse(responseCode = "404", description = "No such rule.")
    AlertRule readRule(@PathParam("ruleId") String ruleId);

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "Create an alert rule", description = "Validated on save; the error names every field that is wrong.")
    @APIResponse(responseCode = "201", description = "The created rule.")
    @APIResponse(responseCode = "400", description = "The rule is not valid (INVALID_RULE).")
    @APIResponse(responseCode = "403", description = "The scope or a target is an administrator's, or the caller lacks EDIT on the agent.")
    Response createRule(AlertRule rule);

    @PUT
    @Path("/{ruleId}")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "Replace an alert rule", description = "A webhook target sent with its masked URL (or none) keeps the stored URL "
            + "of the target with the same targetId; without a known targetId it is refused. New targets get a targetId from the server.")
    @APIResponse(responseCode = "200", description = "The updated rule.")
    @APIResponse(responseCode = "400", description = "The rule is not valid (INVALID_RULE).")
    @APIResponse(responseCode = "403", description = "The caller may not change this rule, or the new one.")
    @APIResponse(responseCode = "404", description = "No such rule.")
    AlertRule updateRule(@PathParam("ruleId") String ruleId, AlertRule rule);

    @DELETE
    @Path("/{ruleId}")
    @Operation(summary = "Delete an alert rule", description = "Its events stay in the inbox.")
    @APIResponse(responseCode = "204", description = "Deleted.")
    @APIResponse(responseCode = "403", description = "The caller may not change this rule.")
    @APIResponse(responseCode = "404", description = "No such rule.")
    Response deleteRule(@PathParam("ruleId") String ruleId);

    @POST
    @Path("/test")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "Send a test alert", description = "Validates the rule in the body (saved or not) and sends a clearly marked test "
            + "alert to each of its targets now. Nothing is stored.")
    @APIResponse(responseCode = "200", description = "What each target did.")
    @APIResponse(responseCode = "400", description = "The rule is not valid (INVALID_RULE).")
    @APIResponse(responseCode = "403", description = "The scope or a target is an administrator's, or the caller lacks EDIT on the agent.")
    TestResult testRule(AlertRule rule);
}
