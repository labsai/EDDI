/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.mcp;

import ai.labs.eddi.datastore.serialization.IJsonSerialization;
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
import static ai.labs.eddi.engine.mcp.McpToolUtils.requireRole;

/**
 * MCP tools for GDPR compliance operations. Provides AI-orchestrated data
 * erasure and export via the MCP protocol.
 *
 * @author ginccc
 * @since 6.0.0
 */
@ApplicationScoped
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

    private final GdprComplianceService gdprComplianceService;
    private final IJsonSerialization jsonSerialization;
    private final SecurityIdentity identity;
    private final boolean authEnabled;

    @Inject
    public McpGdprTools(GdprComplianceService gdprComplianceService,
            IJsonSerialization jsonSerialization,
            SecurityIdentity identity,
            @ConfigProperty(name = "authorization.enabled",
                            defaultValue = "false") boolean authEnabled) {
        this.gdprComplianceService = gdprComplianceService;
        this.jsonSerialization = jsonSerialization;
        this.identity = identity;
        this.authEnabled = authEnabled;
    }

    @Tool(name = "delete_user_data", description = "Cascade-delete all user "
            + "data across all stores (GDPR Art. 17 Right to Erasure). "
            + "Deletes memories and conversations. Pseudonymizes audit and log "
            + "entries. IRREVERSIBLE — confirmation='CONFIRM' required. "
            + "Returns status='completed' only when every step succeeded; "
            + "status='partially_completed' with a non-empty 'failedSteps' means "
            + "some of the user's data still exists and the erasure must NOT be "
            + "reported to the data subject as fulfilled.")
    public String deleteUserData(
                                 @ToolArg(description = "User ID to erase (required)") String userId,
                                 @ToolArg(description = "Must be 'CONFIRM' to proceed") String confirmation) {
        requireRole(identity, authEnabled, "eddi-admin");
        if (userId == null || userId.isBlank()) {
            return errorJson("userId is required");
        }
        if (!"CONFIRM".equals(confirmation)) {
            return errorJson("You must pass confirmation='CONFIRM' to "
                    + "delete all user data. This action is irreversible.");
        }
        try {
            var result = gdprComplianceService.deleteUserData(userId);
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
            // The six counters the cascade has always computed and written to the
            // ledger but never returned to the caller.
            map.put("attachmentsDeleted", result.attachmentsDeleted());
            map.put("journalEntriesDeleted", result.journalEntriesDeleted());
            map.put("checkpointsDeleted", result.checkpointsDeleted());
            map.put("groupConversationsDeleted", result.groupConversationsDeleted());
            map.put("sharedArtifactsDeleted", result.sharedArtifactsDeleted());
            map.put("schedulesDeleted", result.schedulesDeleted());
            map.put("completedAt", result.completedAt().toString());
            if (!result.complete()) {
                LOGGER.warnf("MCP delete_user_data: erasure cascade incomplete — failed steps: %s",
                        result.failedSteps());
            }
            return jsonSerialization.serialize(map);
        } catch (Exception e) {
            LOGGER.errorf(e, "MCP delete_user_data failed");
            return errorJson("Failed to delete user data");
        }
    }

    @Tool(name = "export_user_data", description = "Export all data for a user "
            + "(GDPR Art. 15/20 Right of Access / Data Portability). "
            + "Returns memories, conversations, and managed conversation mappings.")
    public String exportUserData(
                                 @ToolArg(description = "User ID to export (required)") String userId) {
        requireRole(identity, authEnabled, "eddi-admin");
        if (userId == null || userId.isBlank()) {
            return errorJson("userId is required");
        }
        try {
            var export = gdprComplianceService.exportUserData(userId);
            var map = new LinkedHashMap<String, Object>();
            map.put("userId", export.userId());
            map.put("exportedAt", export.exportedAt().toString());
            map.put("memoriesCount", export.memories().size());
            map.put("conversationsCount", export.conversations().size());
            map.put("managedConversationsCount",
                    export.managedConversations().size());
            map.put("memories", export.memories());
            map.put("conversations", export.conversations());
            map.put("managedConversations", export.managedConversations());
            return jsonSerialization.serialize(map);
        } catch (Exception e) {
            LOGGER.errorf(e, "MCP export_user_data failed");
            return errorJson("Failed to export user data");
        }
    }
}
