/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.setup;

import ai.labs.eddi.configs.agents.IRestAgentStore;
import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.configs.apicalls.IRestApiCallsStore;
import ai.labs.eddi.configs.apicalls.model.ApiCall;
import ai.labs.eddi.configs.apicalls.model.ApiCallsConfiguration;
import ai.labs.eddi.configs.hitl.HitlConfigValidation;
import ai.labs.eddi.configs.hitl.model.ToolApprovalsConfig;
import ai.labs.eddi.configs.llm.IRestLlmStore;
import ai.labs.eddi.configs.workflows.IRestWorkflowStore;
import ai.labs.eddi.configs.workflows.model.WorkflowConfiguration;
import ai.labs.eddi.engine.hitl.tools.ToolApprovalPatterns;
import ai.labs.eddi.engine.mcp.McpApiToolBuilder;
import ai.labs.eddi.engine.model.Deployment;
import ai.labs.eddi.engine.runtime.client.factory.IRestInterfaceFactory;
import ai.labs.eddi.engine.runtime.client.factory.RestInterfaceFactory;
import ai.labs.eddi.engine.setup.AgentSetupService.AgentSetupException;
import ai.labs.eddi.modules.llm.model.LlmConfiguration;
import ai.labs.eddi.modules.templating.TemplateEscaping;
import ai.labs.eddi.utils.LogSanitizer;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Re-sync of an agent created by {@code setup-api} from a newer OpenAPI spec:
 * {@code POST /administration/agents/{agentId}/setup-api/sync}.
 * <p>
 * Only the httpcalls configurations setup stamped with
 * {@link ApiCallsConfiguration.OpenApiOrigin} are regenerated. Everything else
 * stays as the integrator left it: the LLM task (prompt, model, parameters,
 * {@code toolQuotas}, {@code responseSchema}, guardrails — only its
 * {@code tools} list and the generated API summary at the end of the system
 * prompt are rewritten), hand-written httpcalls, MCP calls and every agent
 * field. The new agent version is saved as <b>compatible</b>, so running
 * conversations move to it on their next turn once it is deployed.
 * <p>
 * Two refusals, both before anything is written:
 * <ul>
 * <li>a removed tool that something still names — a {@code toolQuotas} rule, a
 * {@code toolApprovals} exemption (agent or task level), a tool-result
 * guardrail exemption — is reported by reference and the sync fails with
 * 400;</li>
 * <li>an agent without any marked configuration (hand-written, or created
 * before the marker existed) is refused with how to add the marker.</li>
 * </ul>
 * The gate is never weakened: the agent's stored {@code hitlConfig} is kept,
 * and the request's {@code requireApproval} patterns are only ever added to it,
 * so method-based rules ({@code http.post:*}) gate new write endpoints the
 * moment they appear.
 * <p>
 * Deliberately not an MCP tool and not in the Platform Operator's allow-list:
 * one call rewrites an agent's toolset.
 */
@ApplicationScoped
public class ApiAgentSyncService {

    private static final Logger LOGGER = Logger.getLogger(ApiAgentSyncService.class);

    static final String HTTPCALLS_STEP_TYPE = "eddi://ai.labs.httpcalls";
    static final String LLM_STEP_TYPE = "eddi://ai.labs.llm";
    private static final String SUMMARY_START = "Available API endpoints (";

    private final IRestInterfaceFactory restInterfaceFactory;
    private final AgentSetupService setupService;
    private final ObjectMapper comparison = new ObjectMapper();

    @Inject
    public ApiAgentSyncService(IRestInterfaceFactory restInterfaceFactory, AgentSetupService setupService) {
        this.restInterfaceFactory = restInterfaceFactory;
        this.setupService = setupService;
    }

    /** The agent does not exist. */
    public static class AgentSyncNotFoundException extends AgentSetupException {
        public AgentSyncNotFoundException(String message) {
            super(message);
        }
    }

    /** Removed tools are still referenced; nothing was written. */
    public static class DanglingReferencesException extends AgentSetupException {
        private final List<String> references;

        public DanglingReferencesException(List<String> references) {
            super("The new spec removes tools that the agent still references, so nothing was changed. Remove or update each reference, "
                    + "then sync again: " + String.join("; ", references));
            this.references = List.copyOf(references);
        }

        public List<String> references() {
            return references;
        }
    }

    /** One generated httpcalls step of the agent's workflow. */
    private record GeneratedStep(String location, String id, int version, ApiCallsConfiguration config) {
    }

    public SyncResult sync(String agentId, SyncApiAgentRequest request) throws AgentSetupException {
        if (request == null || request.openApiSpec() == null || request.openApiSpec().isBlank()) {
            throw new AgentSetupException("openApiSpec is required");
        }
        if (agentId == null || agentId.isBlank()) {
            throw new AgentSetupException("agentId is required");
        }
        try {
            HitlConfigValidation.validate(request.hitlConfig());
        } catch (IllegalArgumentException e) {
            throw new AgentSetupException("Invalid hitlConfig: " + e.getMessage(), e);
        }
        boolean dryRun = Boolean.TRUE.equals(request.dryRun());
        boolean deploy = request.deploy() == null || request.deploy();
        Deployment.Environment environment;
        try {
            environment = Deployment.Environment.parseStrict(request.environment());
        } catch (IllegalArgumentException e) {
            throw new AgentSetupException(e.getMessage(), e);
        }

        // ── Read: the agent, its workflow and the generated configurations ────────
        IRestAgentStore agentStore = rest(IRestAgentStore.class);
        Integer current = agentStore.getCurrentVersion(agentId);
        if (current == null || current < 1) {
            throw new AgentSyncNotFoundException("Agent not found: " + agentId);
        }
        AgentConfiguration agent = agentStore.readAgent(agentId, current);
        if (agent == null || agent.getWorkflows() == null) {
            throw new AgentSyncNotFoundException("Agent not found: " + agentId + " version " + current);
        }

        IRestWorkflowStore workflowStore = rest(IRestWorkflowStore.class);
        IRestApiCallsStore apiCallsStore = rest(IRestApiCallsStore.class);
        URI workflowUri = null;
        WorkflowConfiguration workflow = null;
        Map<String, GeneratedStep> generatedByGroup = new LinkedHashMap<>();
        for (URI candidate : agent.getWorkflows()) {
            WorkflowConfiguration read = workflowStore.readWorkflow(idOf(candidate.toString()), versionOf(candidate.toString()));
            if (read == null || read.getWorkflowSteps() == null) {
                continue;
            }
            Map<String, GeneratedStep> found = new LinkedHashMap<>();
            for (var step : read.getWorkflowSteps()) {
                String location = stepUri(step, HTTPCALLS_STEP_TYPE);
                if (location == null) {
                    continue;
                }
                ApiCallsConfiguration config = apiCallsStore.readApiCalls(idOf(location), versionOf(location));
                if (config != null && config.getOpenApiOrigin() != null && config.getOpenApiOrigin().getGroup() != null) {
                    found.put(config.getOpenApiOrigin().getGroup(),
                            new GeneratedStep(location, idOf(location), versionOf(location), config));
                }
            }
            if (found.isEmpty()) {
                continue;
            }
            if (workflow != null) {
                throw new AgentSetupException("Agent " + agentId + " has generated httpcalls in more than one workflow; sync handles one. "
                        + "Nothing was changed.");
            }
            workflowUri = candidate;
            workflow = read;
            generatedByGroup.putAll(found);
        }
        if (workflow == null) {
            AgentSetupService.recordSetupApi("sync", "unmarked");
            throw new AgentSetupException("Agent " + agentId + " has no httpcalls configuration generated by setup-api, so there is nothing "
                    + "sync may regenerate. Agents created before sync existed carry no marker: add \"openApiOrigin\": {\"group\": \"<tag>\", "
                    + "\"authHeader\": \"Authorization\"} to each generated httpcalls configuration (and save it), or create the agent again "
                    + "with setup-api. Nothing was changed.");
        }

        // ── Regenerate from the new spec ──────────────────────────────────────────
        String authHeader = firstNonBlank(request.apiAuthHeader(), recordedAuthHeader(generatedByGroup.values()));
        String apiAuth = request.apiAuth() != null ? request.apiAuth() : recordedApiAuth(generatedByGroup.values());
        McpApiToolBuilder.ApiBuildResult built;
        try {
            built = McpApiToolBuilder.parseAndBuild(request.openApiSpec(), request.endpoints(), request.apiBaseUrl(), apiAuth, authHeader,
                    setupService.specFetcher());
        } catch (IllegalArgumentException e) {
            throw new AgentSetupException("OpenAPI parsing failed: " + e.getMessage(), e);
        }
        Map<String, Object> vaulted = new LinkedHashMap<>();
        String effectiveApiAuth;
        if (request.apiAuth() == null) {
            effectiveApiAuth = apiAuth;
        } else if (dryRun) {
            effectiveApiAuth = AgentSetupService.previewApiAuth(apiAuth);
        } else {
            var targets = built.configsByGroup().values().stream().map(ApiCallsConfiguration::getTargetServerUrl).toList();
            effectiveApiAuth = setupService.vaultApiAuth(apiAuth, "agent-" + agentId, vaulted, new AgentSetupService.HostRule(
                    AgentSetupService.apiAuthRequestedHosts(request.allowedHosts()), SetupSecretHosts.forUrls(targets), "apiAuth"));
        }
        if (!Objects.equals(effectiveApiAuth, apiAuth)) {
            built = McpApiToolBuilder.parseAndBuild(request.openApiSpec(), request.endpoints(), request.apiBaseUrl(), effectiveApiAuth, authHeader,
                    setupService.specFetcher());
        }
        AgentSetupService.markGenerated(built, effectiveApiAuth, authHeader);

        // ── Diff ──────────────────────────────────────────────────────────────────
        Map<String, ApiCall> oldCalls = callsByName(generatedByGroup.values().stream().map(GeneratedStep::config).toList());
        Map<String, ApiCall> newCalls = callsByName(built.configsByGroup().values());
        List<String> added = new ArrayList<>();
        List<String> changed = new ArrayList<>();
        List<String> removed = new ArrayList<>();
        for (var entry : newCalls.entrySet()) {
            ApiCall old = oldCalls.get(entry.getKey());
            if (old == null) {
                added.add(entry.getKey());
            } else if (!sameJson(old, entry.getValue())) {
                changed.add(entry.getKey());
            }
        }
        for (String name : oldCalls.keySet()) {
            if (!newCalls.containsKey(name)) {
                removed.add(name);
            }
        }

        // ── Read the LLM steps; refuse dangling references before any write ──────
        IRestLlmStore llmStore = rest(IRestLlmStore.class);
        Map<String, LlmConfiguration> llmByLocation = new LinkedHashMap<>();
        for (var step : workflow.getWorkflowSteps()) {
            String location = stepUri(step, LLM_STEP_TYPE);
            if (location != null) {
                LlmConfiguration llm = llmStore.readLlm(idOf(location), versionOf(location));
                if (llm != null) {
                    llmByLocation.put(location, llm);
                }
            }
        }
        List<String> dangling = danglingReferences(removed, oldCalls, newCalls, agent, llmByLocation);
        if (!dangling.isEmpty()) {
            AgentSetupService.recordSetupApi("sync", "dangling_reference");
            throw new DanglingReferencesException(dangling);
        }

        // ── The gate: re-applied, never weakened ──────────────────────────────────
        List<String> warnings = new ArrayList<>();
        List<String> gateRulesAdded = mergeGate(agent, request.hitlConfig(), warnings);
        try {
            HitlConfigValidation.validate(agent.getHitlConfig());
        } catch (IllegalArgumentException e) {
            throw new AgentSetupException("The merged hitlConfig is invalid: " + e.getMessage(), e);
        }

        boolean toolsChanged = !added.isEmpty() || !changed.isEmpty() || !removed.isEmpty() || !sameGroups(generatedByGroup, built)
                || !sameConfigs(generatedByGroup, built);
        if (dryRun || (!toolsChanged && gateRulesAdded.isEmpty())) {
            AgentSetupService.recordSetupApi("sync", dryRun ? "dry_run" : "unchanged");
            return new SyncResult(agentId, current, current, dryRun, added, changed, removed, gateRulesAdded, built.unusableEndpoints(),
                    built.unmatchedEndpoints(), warnings, null);
        }

        // ── Write: apicalls, LLM, workflow, agent (compatible) ────────────────────
        Map<String, Object> created = new LinkedHashMap<>(vaulted);
        List<String> createdLocations = new ArrayList<>();
        created.put("httpCallsLocations", createdLocations);
        List<String> written = new ArrayList<>();
        try {
            Map<String, String> newLocationByOld = new LinkedHashMap<>();
            List<String> addedLocations = new ArrayList<>();
            for (var entry : built.configsByGroup().entrySet()) {
                GeneratedStep old = generatedByGroup.get(entry.getKey());
                if (old == null) {
                    String location = locationOf(apiCallsStore.createApiCalls(entry.getValue()));
                    createdLocations.add(location);
                    addedLocations.add(location);
                } else if (sameJson(old.config(), entry.getValue())) {
                    newLocationByOld.put(old.location(), old.location());
                } else {
                    String location = locationOf(apiCallsStore.updateApiCalls(old.id(), old.version(), entry.getValue()));
                    written.add(location);
                    newLocationByOld.put(old.location(), location);
                }
            }
            Set<String> dropped = new LinkedHashSet<>();
            for (GeneratedStep old : generatedByGroup.values()) {
                if (!newLocationByOld.containsKey(old.location())) {
                    dropped.add(old.location());
                }
            }

            // LLM tasks: rewrite the tools list and the generated API summary only.
            Map<String, String> llmLocationByOld = new LinkedHashMap<>();
            for (var entry : llmByLocation.entrySet()) {
                LlmConfiguration llm = entry.getValue();
                boolean llmChanged = false;
                for (LlmConfiguration.Task task : llm.tasks()) {
                    List<String> tools = task.getTools();
                    if (tools == null || tools.stream().noneMatch(t -> newLocationByOld.containsKey(t) || dropped.contains(t))) {
                        continue;
                    }
                    List<String> rewritten = new ArrayList<>();
                    for (String tool : tools) {
                        if (dropped.contains(tool)) {
                            continue;
                        }
                        rewritten.add(newLocationByOld.getOrDefault(tool, tool));
                    }
                    rewritten.addAll(addedLocations);
                    if (!rewritten.equals(tools)) {
                        task.setTools(rewritten);
                        llmChanged = true;
                    }
                    if (task.getParameters() != null && task.getParameters().get("systemMessage") != null) {
                        String prompt = task.getParameters().get("systemMessage");
                        String updated = replaceApiSummary(prompt, built.apiSummary());
                        if (updated == null) {
                            warnings.add("The system prompt of LLM task '" + task.getId() + "' holds no generated API summary sync can recognise; "
                                    + "it was left unchanged.");
                        } else if (!updated.equals(prompt)) {
                            task.getParameters().put("systemMessage", updated);
                            llmChanged = true;
                        }
                    }
                }
                if (llmChanged) {
                    String location = locationOf(llmStore.updateLlm(idOf(entry.getKey()), versionOf(entry.getKey()), llm));
                    written.add(location);
                    llmLocationByOld.put(entry.getKey(), location);
                }
            }

            // Workflow: same order; new groups after the last generated step.
            var steps = new ArrayList<WorkflowConfiguration.WorkflowStep>();
            int lastGenerated = -1;
            for (var step : workflow.getWorkflowSteps()) {
                String httpcalls = stepUri(step, HTTPCALLS_STEP_TYPE);
                String llmLocation = stepUri(step, LLM_STEP_TYPE);
                if (httpcalls != null && dropped.contains(httpcalls)) {
                    continue;
                }
                if (httpcalls != null && newLocationByOld.containsKey(httpcalls)) {
                    step.setConfig(withUri(step.getConfig(), newLocationByOld.get(httpcalls)));
                    steps.add(step);
                    lastGenerated = steps.size() - 1;
                    continue;
                }
                if (llmLocation != null && llmLocationByOld.containsKey(llmLocation)) {
                    step.setConfig(withUri(step.getConfig(), llmLocationByOld.get(llmLocation)));
                }
                steps.add(step);
            }
            int insertAt = lastGenerated >= 0 ? lastGenerated + 1 : firstLlmIndex(steps);
            for (String location : addedLocations) {
                var step = new WorkflowConfiguration.WorkflowStep();
                step.setType(URI.create(HTTPCALLS_STEP_TYPE));
                step.setConfig(new LinkedHashMap<>(Map.of("uri", location)));
                steps.add(insertAt++, step);
            }
            workflow.setWorkflowSteps(steps);
            String workflowLocation = locationOf(
                    workflowStore.updateWorkflow(idOf(workflowUri.toString()), versionOf(workflowUri.toString()), workflow));
            written.add(workflowLocation);

            var workflows = new ArrayList<URI>();
            for (URI uri : agent.getWorkflows()) {
                workflows.add(uri.equals(workflowUri) ? URI.create(workflowLocation) : uri);
            }
            agent.setWorkflows(workflows);
            String agentLocation = locationOf(agentStore.updateAgent(agentId, current, agent, true));
            int newVersion = versionOf(agentLocation);
            AgentSetupService.recordSetupApi("sync", "synced");

            Map<String, Object> deployment = deploy ? setupService.deployAndWait(environment, agentId, newVersion) : null;
            return new SyncResult(agentId, current, newVersion, false, added, changed, removed, gateRulesAdded, built.unusableEndpoints(),
                    built.unmatchedEndpoints(), warnings, deployment);
        } catch (RuntimeException e) {
            // Newly created httpcalls (and a newly vaulted apiAuth) are removed; new
            // versions of existing documents stay as harmless orphans nothing references,
            // because the agent was not updated.
            setupService.rollbackCreatedResources(created, "agent-" + agentId, e);
            LOGGER.warnf("Sync of agent %s failed: %s", LogSanitizer.sanitize(agentId), LogSanitizer.sanitize(e.getMessage()));
            throw new AgentSetupException("Sync failed: " + e.getMessage() + ". Agent " + agentId + " was NOT changed (still version " + current
                    + ")." + (written.isEmpty() ? "" : " Versions written that nothing references yet: " + String.join(", ", written) + "."), e);
        }
    }

    // ── Gate ────────────────────────────────────────────────────────────────────

    /**
     * Adds the request's {@code requireApproval} patterns to the agent's gate and
     * returns those that were not there. Nothing is ever removed, and no other
     * field of the request's {@code hitlConfig} is applied — an exemption there
     * would weaken the gate.
     */
    static List<String> mergeGate(AgentConfiguration agent, AgentConfiguration.HitlConfig requested, List<String> warnings) {
        List<String> addedRules = new ArrayList<>();
        ToolApprovalsConfig requestedApprovals = requested == null ? null : requested.getToolApprovals();
        if (requestedApprovals == null || requestedApprovals.getRequireApproval() == null || requestedApprovals.getRequireApproval().isEmpty()) {
            return addedRules;
        }
        if (requestedApprovals.getExempt() != null && !requestedApprovals.getExempt().isEmpty()) {
            warnings.add("hitlConfig.toolApprovals.exempt in the sync request was ignored: sync only adds gate rules. Edit the agent to change "
                    + "exemptions.");
        }
        AgentConfiguration.HitlConfig hitl = agent.getHitlConfig();
        if (hitl == null) {
            hitl = new AgentConfiguration.HitlConfig();
            agent.setHitlConfig(hitl);
        }
        ToolApprovalsConfig approvals = hitl.getToolApprovals();
        if (approvals == null) {
            approvals = new ToolApprovalsConfig();
            hitl.setToolApprovals(approvals);
        }
        List<String> rules = approvals.getRequireApproval() == null ? new ArrayList<>() : new ArrayList<>(approvals.getRequireApproval());
        for (String pattern : requestedApprovals.getRequireApproval()) {
            if (pattern != null && !rules.contains(pattern)) {
                rules.add(pattern);
                addedRules.add(pattern);
            }
        }
        approvals.setRequireApproval(rules);
        return addedRules;
    }

    // ── Dangling references ─────────────────────────────────────────────────────

    /**
     * Every reference naming a removed tool and no remaining one. A pattern with a
     * wildcard is not a reference to a tool and is never reported.
     */
    static List<String> danglingReferences(List<String> removed, Map<String, ApiCall> oldCalls, Map<String, ApiCall> newCalls,
                                           AgentConfiguration agent, Map<String, LlmConfiguration> llms) {
        List<String> found = new ArrayList<>();
        if (removed.isEmpty()) {
            return found;
        }
        Map<String, List<String>> removedAddresses = new LinkedHashMap<>();
        removed.forEach(name -> removedAddresses.put(name, addressesOf(name, oldCalls.get(name))));
        List<String> remainingAddresses = new ArrayList<>();
        newCalls.forEach((name, call) -> remainingAddresses.addAll(addressesOf(name, call)));

        if (agent.getHitlConfig() != null && agent.getHitlConfig().getToolApprovals() != null) {
            checkPatterns(agent.getHitlConfig().getToolApprovals().getExempt(), "agent hitlConfig.toolApprovals.exempt", removedAddresses,
                    remainingAddresses, found);
        }
        for (var llm : llms.values()) {
            for (LlmConfiguration.Task task : llm.tasks()) {
                String where = "LLM task '" + task.getId() + "' ";
                if (task.getToolQuotas() != null && task.getToolQuotas().getTools() != null) {
                    for (String key : task.getToolQuotas().getTools().keySet()) {
                        if (removedAddresses.containsKey(key) && !newCalls.containsKey(key)) {
                            found.add(where + "toolQuotas.tools." + key + " (tool '" + key + "')");
                        }
                    }
                }
                if (task.getToolApprovals() != null) {
                    checkPatterns(task.getToolApprovals().getExempt(), where + "toolApprovals.exempt", removedAddresses, remainingAddresses, found);
                }
                if (task.getToolResultGuardrails() != null) {
                    checkPatterns(task.getToolResultGuardrails().getExemptTools(), where + "toolResultGuardrails.exemptTools", removedAddresses,
                            remainingAddresses, found);
                }
            }
        }
        return found;
    }

    private static void checkPatterns(List<String> patterns, String where, Map<String, List<String>> removedAddresses,
                                      List<String> remainingAddresses, List<String> found) {
        if (patterns == null) {
            return;
        }
        for (String pattern : patterns) {
            if (pattern == null || pattern.contains("*")) {
                continue;
            }
            boolean stillMatches = remainingAddresses.stream().anyMatch(address -> ToolApprovalPatterns.matches(pattern, address));
            if (stillMatches) {
                continue;
            }
            for (var entry : removedAddresses.entrySet()) {
                if (entry.getValue().stream().anyMatch(address -> ToolApprovalPatterns.matches(pattern, address))) {
                    found.add(where + " '" + pattern + "' (tool '" + entry.getKey() + "')");
                    break;
                }
            }
        }
    }

    /** The addresses the approval gate matches an httpcall tool by. */
    static List<String> addressesOf(String name, ApiCall call) {
        List<String> addresses = new ArrayList<>(3);
        if (call != null && call.getRequest() != null && call.getRequest().getMethod() != null && call.getRequest().getPath() != null) {
            addresses.add("http." + call.getRequest().getMethod().toLowerCase(Locale.ROOT) + ":"
                    + normalizeEndpointPath(call.getRequest().getPath()));
        }
        addresses.add("http:" + name);
        addresses.add(name);
        return addresses;
    }

    /**
     * The path as the approval gate keys an httpcall tool by — the rule of
     * {@code HttpCallToolsProvider.normalizeEndpointPath}, for the paths setup
     * generates: leading slash, root as {@code /}, an absolute URL reduced to its
     * path.
     */
    static String normalizeEndpointPath(String rawPath) {
        String path = rawPath.trim();
        String lower = path.toLowerCase(Locale.ROOT);
        if (lower.startsWith("http://") || lower.startsWith("https://")) {
            try {
                String extracted = URI.create(path).getPath();
                path = extracted == null ? "" : extracted;
            } catch (IllegalArgumentException unparseable) {
                return path;
            }
        }
        if (path.isEmpty()) {
            return "/";
        }
        return path.startsWith("/") ? path : "/" + path;
    }

    // ── Prompt summary ──────────────────────────────────────────────────────────

    /**
     * The prompt with its generated API summary (the unparsed block setup appended)
     * replaced by {@code newSummary}, or {@code null} when the prompt holds no such
     * block or more than one.
     */
    static String replaceApiSummary(String prompt, String newSummary) {
        String opener = "{|" + SUMMARY_START;
        int start = prompt.indexOf(opener);
        if (start < 0 || prompt.indexOf(opener, start + 1) >= 0) {
            return null;
        }
        int end = prompt.indexOf("|}", start);
        if (end < 0) {
            return null;
        }
        return prompt.substring(0, start) + TemplateEscaping.unparsedBlock(newSummary) + prompt.substring(end + 2);
    }

    // ── Helpers ─────────────────────────────────────────────────────────────────

    private static String recordedAuthHeader(Iterable<GeneratedStep> steps) {
        for (GeneratedStep step : steps) {
            String header = step.config().getOpenApiOrigin().getAuthHeader();
            if (header != null && !header.isBlank()) {
                return header;
            }
        }
        return null;
    }

    /** The value the generated calls already send in the recorded auth header. */
    private static String recordedApiAuth(Iterable<GeneratedStep> steps) {
        for (GeneratedStep step : steps) {
            String header = step.config().getOpenApiOrigin().getAuthHeader();
            if (header == null || step.config().getHttpCalls() == null) {
                continue;
            }
            for (ApiCall call : step.config().getHttpCalls()) {
                if (call.getRequest() != null && call.getRequest().getHeaders() != null && call.getRequest().getHeaders().get(header) != null) {
                    return call.getRequest().getHeaders().get(header);
                }
            }
        }
        return null;
    }

    private static Map<String, ApiCall> callsByName(Iterable<ApiCallsConfiguration> configs) {
        Map<String, ApiCall> calls = new LinkedHashMap<>();
        for (ApiCallsConfiguration config : configs) {
            if (config.getHttpCalls() != null) {
                config.getHttpCalls().forEach(call -> calls.put(call.getName(), call));
            }
        }
        return calls;
    }

    private boolean sameJson(Object a, Object b) {
        return comparison.valueToTree(a).equals(comparison.valueToTree(b));
    }

    private static boolean sameGroups(Map<String, GeneratedStep> old, McpApiToolBuilder.ApiBuildResult built) {
        return old.keySet().equals(built.configsByGroup().keySet());
    }

    private boolean sameConfigs(Map<String, GeneratedStep> old, McpApiToolBuilder.ApiBuildResult built) {
        for (var entry : built.configsByGroup().entrySet()) {
            GeneratedStep step = old.get(entry.getKey());
            if (step == null || !sameJson(step.config(), entry.getValue())) {
                return false;
            }
        }
        return true;
    }

    private static int firstLlmIndex(List<WorkflowConfiguration.WorkflowStep> steps) {
        for (int i = 0; i < steps.size(); i++) {
            if (stepUri(steps.get(i), LLM_STEP_TYPE) != null) {
                return i;
            }
        }
        return steps.size();
    }

    private static String stepUri(WorkflowConfiguration.WorkflowStep step, String type) {
        if (step == null || step.getType() == null || !type.equals(step.getType().toString()) || step.getConfig() == null) {
            return null;
        }
        Object uri = step.getConfig().get("uri");
        return uri == null ? null : uri.toString();
    }

    private static Map<String, Object> withUri(Map<String, Object> config, String uri) {
        var copy = config == null ? new LinkedHashMap<String, Object>() : new LinkedHashMap<>(config);
        copy.put("uri", uri);
        return copy;
    }

    private static String locationOf(Response response) {
        String location = response == null ? null : response.getHeaderString("Location");
        if (location == null || location.isBlank()) {
            throw new IllegalStateException("a store write did not report the location it wrote");
        }
        return location;
    }

    private static String firstNonBlank(String a, String b) {
        return a != null && !a.isBlank() ? a : b;
    }

    static String idOf(String location) {
        return AgentSetupService.extractIdFromLocation(location);
    }

    static int versionOf(String location) {
        return AgentSetupService.extractVersionFromLocation(location);
    }

    private <T> T rest(Class<T> type) {
        try {
            return restInterfaceFactory.get(type);
        } catch (RestInterfaceFactory.RestInterfaceFactoryException e) {
            throw new IllegalStateException("Failed to get REST proxy for " + type.getSimpleName(), e);
        }
    }
}
