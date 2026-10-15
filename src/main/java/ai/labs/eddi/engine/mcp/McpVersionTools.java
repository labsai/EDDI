/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.mcp;

import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.runtime.BuildInfo;
import io.quarkiverse.mcp.server.Tool;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.io.IOException;

import static ai.labs.eddi.engine.mcp.McpToolUtils.errorJson;
import static ai.labs.eddi.engine.mcp.McpToolUtils.requireRole;

/**
 * MCP tool answering which build of EDDI is running. Same source as
 * {@code GET /administration/version}: {@link BuildInfo}.
 *
 * @since 6.5.0
 */
@McpErrorResults
@ApplicationScoped
public class McpVersionTools {

    private final BuildInfo buildInfo;
    private final IJsonSerialization jsonSerialization;
    private final SecurityIdentity identity;
    private final boolean authEnabled;

    @Inject
    public McpVersionTools(BuildInfo buildInfo, IJsonSerialization jsonSerialization, SecurityIdentity identity,
            @ConfigProperty(name = "authorization.enabled", defaultValue = "false") boolean authEnabled) {
        this.buildInfo = buildInfo;
        this.jsonSerialization = jsonSerialization;
        this.identity = identity;
        this.authEnabled = authEnabled;
    }

    @Tool(name = "get_eddi_version", description = "Get the version and git commit of the running EDDI. "
            + "Read live from the process, so it is correct right after an upgrade. commit is 'unknown' if the build recorded none.")
    public String getEddiVersion() {
        requireRole(identity, authEnabled, "eddi-admin");
        try {
            return jsonSerialization.serialize(buildInfo.get());
        } catch (IOException e) {
            return errorJson("Failed to serialize build info", e);
        }
    }
}
