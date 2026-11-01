/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.mcp;

import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.agenttests.AgentTestService;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import static ai.labs.eddi.engine.mcp.McpToolUtils.requireAnyRole;
import static ai.labs.eddi.engine.mcp.McpToolUtils.toolFailure;

/**
 * MCP tools for agent test suites — the counterpart of
 * {@code IRestAgentTestRuns}, at the same tier as deploying an agent
 * ({@link McpRoles#AUTHOR}), because starting a run deploys the version under
 * test to the {@code test} environment.
 *
 * @since 6.5.0
 */
@ApplicationScoped
@McpErrorResults
public class McpAgentTestTools {

    private static final Logger LOGGER = Logger.getLogger(McpAgentTestTools.class);

    private final AgentTestService service;
    private final IJsonSerialization jsonSerialization;
    private final SecurityIdentity identity;
    private final boolean authEnabled;

    @Inject
    public McpAgentTestTools(AgentTestService service, IJsonSerialization jsonSerialization, SecurityIdentity identity,
            @ConfigProperty(name = "authorization.enabled", defaultValue = "false") boolean authEnabled) {
        this.service = service;
        this.jsonSerialization = jsonSerialization;
        this.identity = identity;
        this.authEnabled = authEnabled;
    }

    @Tool(name = "run_agent_tests", description = "Run an agent test suite (golden conversations) against an agent version. Deploys the "
            + "version to the 'test' environment if needed (never production) and runs in the background: returns the run id at once — "
            + "poll get_agent_test_run until status is PASSED, FAILED, ERROR, ABORTED or CANCELLED. Tool calls are mocked by the suite; an unmocked call is "
            + "not executed and fails its case.")
    public String runAgentTests(@ToolArg(description = "Test suite id (required)") String suiteId,
                                @ToolArg(description = "Agent id (required)") String agentId,
                                @ToolArg(description = "Suite version; omit for the current one", required = false) Integer suiteVersion,
                                @ToolArg(description = "Agent version; omit for the current one", required = false) Integer agentVersion,
                                @ToolArg(description = "Keep the test conversations for inspection (default false: they are deleted)",
                                         required = false) Boolean keepConversations) {
        requireAnyRole(identity, authEnabled, McpRoles.AUTHOR);
        try {
            return jsonSerialization.serialize(service.startRun(suiteId, suiteVersion, agentId, agentVersion,
                    Boolean.TRUE.equals(keepConversations)));
        } catch (Exception e) {
            return toolFailure(LOGGER, "run_agent_tests", e);
        }
    }

    @Tool(name = "get_agent_test_run", description = "Read an agent test run: status, and per case and turn which expectations failed "
            + "with the actual value, the tools called, any unmocked tool calls, and the cost.")
    public String getAgentTestRun(@ToolArg(description = "Run id (required)") String runId) {
        requireAnyRole(identity, authEnabled, McpRoles.AUTHOR);
        try {
            return jsonSerialization.serialize(service.readRun(runId));
        } catch (Exception e) {
            return toolFailure(LOGGER, "get_agent_test_run", e);
        }
    }

    @Tool(name = "cancel_agent_test_run", description = "Cancel a RUNNING agent test run: no further turn starts, a turn in progress is "
            + "abandoned, and its test conversations are ended and deleted. Returns the record as it is now; poll get_agent_test_run "
            + "until status is CANCELLED. Only works on the node executing the run.")
    public String cancelAgentTestRun(@ToolArg(description = "Run id (required)") String runId) {
        requireAnyRole(identity, authEnabled, McpRoles.AUTHOR);
        try {
            return jsonSerialization.serialize(service.cancelRun(runId));
        } catch (Exception e) {
            return toolFailure(LOGGER, "cancel_agent_test_run", e);
        }
    }

    @Tool(name = "list_agent_test_runs", description = "List an agent's test runs, newest first.")
    public String listAgentTestRuns(@ToolArg(description = "Agent id (required)") String agentId,
                                    @ToolArg(description = "Page index, default 0", required = false) Integer index,
                                    @ToolArg(description = "Page size, default 20, max 100", required = false) Integer limit) {
        requireAnyRole(identity, authEnabled, McpRoles.AUTHOR);
        try {
            return jsonSerialization.serialize(service.listRuns(agentId, index != null ? index : 0, limit != null ? limit : 20));
        } catch (Exception e) {
            return toolFailure(LOGGER, "list_agent_test_runs", e);
        }
    }
}
