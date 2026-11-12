/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.mcp;

import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.audit.rest.AdminActionRecorder;
import ai.labs.eddi.engine.gdpr.GdprComplianceService;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.util.LinkedHashMap;

import static ai.labs.eddi.engine.mcp.McpToolUtils.errorJson;
import static ai.labs.eddi.engine.mcp.McpToolUtils.requireAnyRole;

/**
 * MCP tools for GDPR compliance operations. Provides AI-orchestrated data
 * erasure and export via the MCP protocol.
 * <p>
 * Every call — refused, invalid, failed or done — is recorded as an
 * administrative action with the caller as actor, through
 * {@link AdminActionRecorder}, exactly as the REST surface's calls are.
 *
 * @author ginccc
 * @since 6.0.0
 */
@ApplicationScoped
@McpErrorResults
public class McpGdprTools {

    private static final Logger LOGGER = Logger.getLogger(McpGdprTools.class);

    /** Reported when every step of the erasure cascade succeeded. */
    static final String STATUS_COMPLETED = "completed";

    /**
     * Reported when at least one step failed. Deliberately not "completed": the
     * erasure must not be filed as fulfilled while {@code failedSteps} is
     * non-empty.
     */
    static final String STATUS_PARTIAL = "partially_completed";

    static final String DELETE_USER_DATA = "delete_user_data";
    static final String EXPORT_USER_DATA = "export_user_data";

    /*
     * The outcome of a call as it is recorded in the administrative-action log: the
     * HTTP status the REST surface (RestGdprAdmin) answers for the same outcome, so
     * the two surfaces read alike there.
     */
    static final int OUTCOME_OK = 200;
    /** The erasure lost a step, or the export bundle is not everything. */
    static final int OUTCOME_INCOMPLETE = 207;
    static final int OUTCOME_INVALID = 400;
    static final int OUTCOME_REFUSED = 403;
    static final int OUTCOME_FAILED = 500;

    private final GdprComplianceService gdprComplianceService;
    private final IJsonSerialization jsonSerialization;
    private final SecurityIdentity identity;
    private final AdminActionRecorder adminActionRecorder;
    private final boolean authEnabled;

    @Inject
    public McpGdprTools(GdprComplianceService gdprComplianceService,
            IJsonSerialization jsonSerialization,
            SecurityIdentity identity,
            AdminActionRecorder adminActionRecorder,
            @ConfigProperty(name = "authorization.enabled",
                            defaultValue = "false") boolean authEnabled) {
        this.gdprComplianceService = gdprComplianceService;
        this.jsonSerialization = jsonSerialization;
        this.identity = identity;
        this.adminActionRecorder = adminActionRecorder;
        this.authEnabled = authEnabled;
    }

    @Tool(name = "delete_user_data", description = "Cascade-delete all user "
            + "data across all stores (GDPR Art. 17 Right to Erasure). "
            + "Deletes memories and conversations. Pseudonymizes audit and log "
            + "entries. IRREVERSIBLE — confirmation='CONFIRM' required. "
            + "Returns status='completed' only when every step succeeded; "
            + "status='partially_completed' with a non-empty 'failedSteps' means "
            + "some of the user's data still exists and the erasure must NOT be "
            + "reported to the data subject as fulfilled. A non-empty 'notices' names data "
            + "a step deliberately did not erase and the remedy; act on it before reporting.")
    public String deleteUserData(
                                 @ToolArg(description = "User ID to erase (required)") String userId,
                                 @ToolArg(description = "Must be 'CONFIRM' to proceed") String confirmation) {
        int outcome = OUTCOME_REFUSED;
        try {
            requireAnyRole(identity, authEnabled, McpRoles.ADMIN_ONLY);
            outcome = OUTCOME_INVALID;
            if (userId == null || userId.isBlank()) {
                return errorJson("userId is required");
            }
            if (!"CONFIRM".equals(confirmation)) {
                return errorJson("You must pass confirmation='CONFIRM' to "
                        + "delete all user data. This action is irreversible.");
            }
            if (GdprComplianceService.isReservedPrincipal(userId)) {
                return errorJson("userId names a reserved system principal, not a user");
            }
            outcome = OUTCOME_FAILED;
            try {
                var result = gdprComplianceService.deleteUserData(userId);
                outcome = result.complete() ? OUTCOME_OK : OUTCOME_INCOMPLETE;
                var map = new LinkedHashMap<String, Object>();
                // "completed" used to be hardcoded here. The cascade deliberately
                // continues past a failing store, so an admin — or an LLM agent acting
                // on this tool's answer — was told an Art. 17 request was fulfilled
                // while the data was still there. Report what actually happened and
                // name the steps that did not run, exactly as the REST surface does
                // with its 207.
                map.put("status", result.complete() ? STATUS_COMPLETED : STATUS_PARTIAL);
                map.put("complete", result.complete());
                map.put("failedSteps", result.failedSteps());
                map.put("userId", result.userId());
                map.put("memoriesDeleted", result.memoriesDeleted());
                map.put("conversationsDeleted", result.conversationsDeleted());
                map.put("conversationMappingsDeleted",
                        result.conversationMappingsDeleted());
                map.put("logsPseudonymized", result.logsPseudonymized());
                map.put("auditEntriesPseudonymized",
                        result.auditEntriesPseudonymized());
                map.put("auditEntriesRedacted", result.auditEntriesRedacted());
                // The six counters the cascade has always computed and written to the
                // ledger but never returned to the caller.
                map.put("attachmentsDeleted", result.attachmentsDeleted());
                map.put("journalEntriesDeleted", result.journalEntriesDeleted());
                map.put("checkpointsDeleted", result.checkpointsDeleted());
                map.put("groupConversationsDeleted", result.groupConversationsDeleted());
                map.put("sharedArtifactsDeleted", result.sharedArtifactsDeleted());
                map.put("schedulesDeleted", result.schedulesDeleted());
                map.put("connectionGrantsDeleted", result.connectionGrantsDeleted());
                map.put("autoVaultedSecretsDeleted", result.autoVaultedSecretsDeleted());
                map.put("additionalDeleted", result.additionalDeleted());
                // Same field as the REST body: data a step deliberately left (an unverified
                // e-mail address) and what the operator does about it.
                map.put("notices", result.notices());
                map.put("completedAt", result.completedAt().toString());
                if (!result.complete()) {
                    LOGGER.warnf("MCP delete_user_data: erasure cascade incomplete — failed steps: %s",
                            result.failedSteps());
                }
                return jsonSerialization.serialize(map);
            } catch (Exception e) {
                // The outcome stays what the cascade did: if only the reply failed, the
                // erasure still ran, and the REST record (whose filter reads the status
                // before the body is written) says so too.
                LOGGER.errorf(e, "MCP delete_user_data failed");
                return errorJson("Failed to delete user data");
            }
        } finally {
            recordCall(DELETE_USER_DATA, "deleteUserData", userId, outcome);
        }
    }

    @Tool(name = "export_user_data", description = "Export all data for a user "
            + "(GDPR Art. 15/20 Right of Access / Data Portability). "
            + "Returns memories, conversations, managed conversation mappings, the group "
            + "discussions the user started and their shared artifacts. "
            + "'complete'=false means the bundle is INCOMPLETE and must NOT be handed to the "
            + "data subject as a complete Art. 15 bundle: 'omittedCategories' names the "
            + "personal-data categories this exporter does not reach (always non-empty today), "
            + "'conversationsTruncated'=true means the per-request conversation cap bit, "
            + "with 'totalConversations' saying how many the user has, and "
            + "'failedConversationIds' names conversations that could not be loaded at all; "
            + "'groupConversationsTruncated', 'sharedArtifactsTruncated' and 'failedCategories' "
            + "do the same for the group sections. A non-empty 'notices' names data a "
            + "section deliberately did not export and the remedy.")
    public String exportUserData(
                                 @ToolArg(description = "User ID to export (required)") String userId) {
        int outcome = OUTCOME_REFUSED;
        try {
            requireAnyRole(identity, authEnabled, McpRoles.ADMIN_ONLY);
            outcome = OUTCOME_INVALID;
            if (userId == null || userId.isBlank()) {
                return errorJson("userId is required");
            }
            if (GdprComplianceService.isReservedPrincipal(userId)) {
                return errorJson("userId names a reserved system principal, not a user");
            }
            outcome = OUTCOME_FAILED;
            try {
                var export = gdprComplianceService.exportUserData(userId);
                outcome = export.complete() ? OUTCOME_OK : OUTCOME_INCOMPLETE;
                var map = new LinkedHashMap<String, Object>();
                map.put("userId", export.userId());
                map.put("exportedAt", export.exportedAt().toString());
                map.put("memoriesCount", export.memories().size());
                map.put("conversationsCount", export.conversations().size());
                // The REST surface says the same thing with a 207; an LLM agent calling
                // this tool has only the payload to go on, so the cap — and the four
                // categories the exporter never reaches — have to be in it. Same shape as
                // the REST body, for the reason delete_user_data reports 'complete': one
                // result told two different ways is a client reading null from one of them.
                map.put("complete", export.complete());
                map.put("omittedCategories", export.omittedCategories());
                map.put("totalConversations", export.totalConversations());
                map.put("conversationsTruncated", export.conversationsTruncated());
                // The third reason a bundle can be short, alongside the cap and the
                // omitted categories: conversations the exporter could not load at all.
                // Left out here, an agent reading complete=false with
                // conversationsTruncated=false has nothing to say why.
                map.put("failedConversationIds", export.failedConversationIds());
                map.put("managedConversationsCount",
                        export.managedConversations().size());
                map.put("memories", export.memories());
                map.put("conversations", export.conversations());
                map.put("managedConversations", export.managedConversations());
                // Metadata only — the export entries never carry token material.
                map.put("connectionGrants", export.connectionGrants());
                map.put("additionalData", export.additionalData());
                // Group discussions and their shared artifacts (L9 of the 2026-10-17
                // review), with the same truncation and failure signals as the REST body.
                map.put("groupConversations", export.groupConversations());
                map.put("groupConversationsTruncated", export.groupConversationsTruncated());
                map.put("sharedArtifacts", export.sharedArtifacts());
                map.put("sharedArtifactsTruncated", export.sharedArtifactsTruncated());
                map.put("failedCategories", export.failedCategories());
                map.put("notices", export.notices());
                return jsonSerialization.serialize(map);
            } catch (Exception e) {
                // As in delete_user_data: a reply that cannot be written leaves the
                // outcome of the export itself on record.
                LOGGER.errorf(e, "MCP export_user_data failed");
                return errorJson("Failed to export user data");
            }
        } finally {
            recordCall(EXPORT_USER_DATA, "exportUserData", userId, outcome);
        }
    }

    /**
     * Records the call as an administrative action, with the caller as actor and
     * the subject pseudonymised. These two tools reach the GDPR service directly,
     * not through REST, so {@code AdminActionAuditFilter} never sees them; without
     * this the ledger's compliance event says that a user was erased or exported
     * but not who asked. Refusals are recorded too, as the REST surface records a
     * {@code 403}.
     */
    private void recordCall(String tool, String method, String userId, int outcome) {
        adminActionRecorder.recordToolCall(tool, userId, McpGdprTools.class.getSimpleName() + "#" + method, outcome);
    }
}
