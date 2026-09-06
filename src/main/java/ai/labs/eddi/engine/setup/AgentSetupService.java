/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.setup;

import ai.labs.eddi.configs.rules.IRestRuleSetStore;
import ai.labs.eddi.configs.parser.IRestParserStore;
import ai.labs.eddi.configs.parser.model.ParserConfiguration;
import ai.labs.eddi.configs.rules.model.RuleSetConfiguration;
import ai.labs.eddi.configs.rules.model.RuleGroupConfiguration;
import ai.labs.eddi.configs.rules.model.RuleConditionConfiguration;
import ai.labs.eddi.configs.rules.model.RuleConfiguration;
import ai.labs.eddi.configs.apicalls.model.OutputBuildingInstruction;
import ai.labs.eddi.configs.apicalls.model.PostResponse;
import ai.labs.eddi.configs.apicalls.model.QuickRepliesBuildingInstruction;

import ai.labs.eddi.configs.agents.IRestAgentStore;
import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.configs.descriptors.IRestDocumentDescriptorStore;
import ai.labs.eddi.configs.hitl.HitlConfigValidation;
import ai.labs.eddi.configs.descriptors.model.DocumentDescriptor;
import ai.labs.eddi.configs.apicalls.IRestApiCallsStore;
import ai.labs.eddi.configs.mcpcalls.IRestMcpCallsStore;
import ai.labs.eddi.configs.mcpcalls.model.McpCallsConfiguration;
import ai.labs.eddi.configs.patch.PatchInstruction;
import ai.labs.eddi.configs.llm.IRestLlmStore;
import ai.labs.eddi.configs.output.IRestOutputStore;
import ai.labs.eddi.configs.output.model.OutputConfiguration;
import ai.labs.eddi.configs.output.model.OutputConfigurationSet;
import ai.labs.eddi.configs.workflows.IRestWorkflowStore;
import ai.labs.eddi.configs.workflows.model.WorkflowConfiguration;
import ai.labs.eddi.engine.api.IRestAgentAdministration;
import ai.labs.eddi.engine.mcp.McpApiToolBuilder;
import ai.labs.eddi.engine.model.Deployment;
import ai.labs.eddi.engine.runtime.client.factory.IRestInterfaceFactory;
import ai.labs.eddi.engine.runtime.client.factory.RestInterfaceFactory;
import ai.labs.eddi.engine.tenancy.QuotaExceededException;
import ai.labs.eddi.modules.llm.model.LlmConfiguration;
import ai.labs.eddi.modules.llm.tools.UrlValidationUtils;
import ai.labs.eddi.modules.output.model.types.TextOutputItem;
import ai.labs.eddi.modules.templating.TemplateEscaping;
import ai.labs.eddi.secrets.ISecretProvider;
import ai.labs.eddi.secrets.SecretResolver;
import ai.labs.eddi.secrets.crypto.EnvelopeCrypto;
import ai.labs.eddi.secrets.model.SecretMetadata;
import ai.labs.eddi.secrets.model.SecretReference;
import ai.labs.eddi.utils.LogSanitizer;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.net.URI;
import java.time.Instant;
import java.util.*;
import java.util.Map;
import java.util.regex.Pattern;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonProcessingException;

/**
 * Service that encapsulates the business logic for setting up EDDI agents. Used
 * by both the MCP {@code setup_agent}/{@code create_api_agent} tools and the
 * REST {@code POST /administration/agents/setup*} endpoints.
 *
 * <p>
 * This replaces the former monolithic approach where all logic lived inside MCP
 * {@code @Tool} annotated methods.
 * </p>
 *
 * @author ginccc
 */
@ApplicationScoped
public class AgentSetupService {

    private static final Logger LOGGER = Logger.getLogger(AgentSetupService.class);

    private final IRestInterfaceFactory restInterfaceFactory;
    private final IRestAgentAdministration agentAdmin;
    private final ISecretProvider secretProvider;
    private final String ollamaDefaultBaseUrl;

    /**
     * Whether a wizard-created agent should log full LLM prompts and completions.
     * <p>
     * Defaults to {@code false}: {@code logRequests}/{@code logResponses} make
     * {@code ObservableChatModel} write conversation content — user input, system
     * prompt, model output — to the application log, and hardcoding them to
     * {@code true} gave every agent this wizard has ever created that behaviour
     * with no opt-out. Content logging is a debugging choice, not a default, so it
     * belongs in configuration: flip
     * {@code eddi.setup.llm.log-conversation-content=true} to have the wizard emit
     * the noisy variant, or edit the two parameters on the generated LLM config in
     * the Manager at any time.
     * <p>
     * Field-injected rather than a constructor parameter so that a directly
     * constructed instance (tests, non-CDI callers) gets the safe default.
     */
    @Inject
    @ConfigProperty(name = "eddi.setup.llm.log-conversation-content", defaultValue = "false")
    boolean logConversationContent;

    /**
     * {@link #vaultKeyReuse} value: reuse a vault entry that already holds the same
     * value.
     */
    static final String VAULT_KEY_REUSE_CHECKSUM = "checksum";

    /** {@link #vaultKeyReuse} value: always vault a fresh, uniquely named entry. */
    static final String VAULT_KEY_REUSE_NEVER = "never";

    /**
     * What setup does when it is handed a <b>plaintext</b> API key that the vault
     * already holds under some key name.
     * <ul>
     * <li>{@code checksum} (default) — reuse that entry and reference it, so
     * provisioning ten agents with one provider key leaves one secret in the vault
     * rather than ten copies of it. Matching is by the SHA-256 checksum the vault
     * already stores per entry, so no stored plaintext is decrypted to make the
     * decision, and only unrestricted entries ({@code allowedAgents} unset or
     * {@code ["*"]}) are candidates — reusing a narrowly granted secret would hand
     * the new agent a reference that {@code VaultGrantGate} rejects at deploy
     * time.</li>
     * <li>{@code never} — restores the pre-6.3 behaviour: every setup writes its
     * own {@code setup.<agent>.<timestamp>.apiKey} entry.</li>
     * </ul>
     * Neither value affects an {@code apiKey} that is already a
     * {@code ${vault:...}} reference (always used as-is) or a request carrying an
     * explicit {@code vaultKeyName} (always honoured) — those are caller decisions,
     * not defaults.
     * <p>
     * Field-injected for the same reason as {@link #logConversationContent}: a
     * directly constructed instance gets the default.
     */
    @Inject
    @ConfigProperty(name = "eddi.setup.vault-key-reuse", defaultValue = VAULT_KEY_REUSE_CHECKSUM)
    String vaultKeyReuse = VAULT_KEY_REUSE_CHECKSUM;

    /**
     * Cache invalidation for secrets this service writes, exactly as
     * {@code RestSecretStore} does after its own store.
     * <p>
     * It matters most on <em>creation</em>, which is counter-intuitive enough that
     * {@code RestSecretStore} spells it out: a model may already be cached having
     * resolved the reference to nothing and kept the literal {@code ${vault:...}}
     * string as its API key. This service deliberately accepts such dangling
     * references (it warns rather than refusing), so it is precisely the path that
     * later fills one in — and without invalidating, every agent already pointing
     * at that name keeps its broken cached model until the model cache expires.
     * <p>
     * Field-injected for the same reason as {@link #logConversationContent}: a
     * directly constructed instance (tests, non-CDI callers) simply has none, and
     * {@link #storeSecret} null-checks rather than requiring every call site to
     * wire a resolver it does not otherwise need.
     */
    @Inject
    SecretResolver secretResolver;

    /**
     * Strict parse, same reasoning as {@code VaultGrantGate.Mode.parseStrict}: an
     * unrecognised value fails startup rather than degrading. The degraded
     * behaviour here would be "no reuse" — exactly the vault growth this setting
     * exists to prevent, with every visible sign saying it is on. Runs only under
     * CDI; a directly constructed instance (tests) keeps the default.
     */
    @PostConstruct
    void validateVaultKeyReuse() {
        if (!VAULT_KEY_REUSE_CHECKSUM.equalsIgnoreCase(vaultKeyReuse) && !VAULT_KEY_REUSE_NEVER.equalsIgnoreCase(vaultKeyReuse)) {
            throw new IllegalArgumentException("Unknown eddi.setup.vault-key-reuse value '" + vaultKeyReuse + "'. Valid values: "
                    + VAULT_KEY_REUSE_CHECKSUM + ", " + VAULT_KEY_REUSE_NEVER);
        }
    }

    @Inject
    public AgentSetupService(IRestInterfaceFactory restInterfaceFactory, IRestAgentAdministration agentAdmin,
            ISecretProvider secretProvider,
            @ConfigProperty(name = "eddi.ollama.default-base-url", defaultValue = "http://localhost:11434") String ollamaDefaultBaseUrl) {
        this.restInterfaceFactory = restInterfaceFactory;
        this.agentAdmin = agentAdmin;
        this.secretProvider = secretProvider;
        this.ollamaDefaultBaseUrl = ollamaDefaultBaseUrl;
    }

    /**
     * Create a fully configured and optionally deployed agent: parser, behaviour
     * rules, LLM config, optional MCP calls and output set, workflow and agent, in
     * one call.
     *
     * @param request
     *            the setup parameters
     * @return result with created resource IDs
     * @throws AgentSetupException
     *             if the setup fails
     */
    public SetupResult setupAgent(SetupAgentRequest request) throws AgentSetupException {
        // Validate required params
        validateNameAndPrompt(request.agentName(), request.systemPrompt());
        boolean isLocalLLM = isLocalLlmProvider(request.provider());
        // vaultKeyName alone is enough: it names a key the vault already holds, which
        // is the whole point of provisioning a second agent against an existing one.
        if (!isLocalLLM && isNullOrBlank(request.apiKey()) && isNullOrBlank(request.vaultKeyName())) {
            throw new AgentSetupException(
                    "API key is required for cloud LLM providers (anthropic, openai, gemini) — pass apiKey, or vaultKeyName to reuse a key "
                            + "already in the vault");
        }
        // Validate the HITL config HERE, before a single resource exists — same
        // reasoning as createApiAgent: AgentStore.create validates it too, but only
        // at step 7, and a bad pattern would otherwise surface after the parser,
        // behaviour, LLM and workflow had all been created, leaving every one of
        // them orphaned.
        try {
            HitlConfigValidation.validate(request.hitlConfig());
        } catch (IllegalArgumentException e) {
            throw new AgentSetupException("Invalid hitlConfig: " + e.getMessage(), e);
        }
        validateMcpServerUrls(request.mcpServerUrls());

        var params = resolveParamsValidated(request.provider(), request.model(), request.deploy(), request.environment());
        boolean toolsEnabled = request.enableBuiltInTools() != null && request.enableBuiltInTools();
        boolean quickReplies = request.enableQuickReplies() != null && request.enableQuickReplies();
        boolean sentiment = request.enableSentimentAnalysis() != null && request.enableSentimentAnalysis();
        String promptResponseJson = buildPromptResponseJson(quickReplies, sentiment);

        var createdResources = new LinkedHashMap<String, Object>();

        // --- Step 0: Resolve the API key against the vault ---
        // Outside the try, for the same reason the HITL config is validated above: an
        // unusable vaultKeyName (missing, or holding a different value) has to fail
        // while rollback still has nothing to undo, not after a parser, a ruleset and
        // a workflow already exist. Outside also keeps the message intact — a caller
        // reading a 400 wants "vaultKeyName 'x' does not exist", not that sentence
        // wrapped in "Failed to set up agent".
        String effectiveApiKey = vaultApiKey(request.apiKey(), request.agentName(), request.vaultKeyName(), createdResources);

        try {
            // --- Step 1: Create Parser ---
            var parserConfig = createParserConfig();
            Response parserResponse = getRestStore(IRestParserStore.class).createParser(parserConfig);
            String parserLocation = parserResponse.getHeaderString("Location");
            String parserId = extractIdFromLocation(parserLocation);
            int parserVersion = extractVersionFromLocation(parserLocation);
            createdResources.put("parserLocation", parserLocation);
            patchDescriptor(parserId, parserVersion, request.agentName());

            // --- Step 2: Create Behavior Rules ---
            var behaviorConfig = createBehaviorConfig();
            Response behaviorResponse = getRestStore(IRestRuleSetStore.class).createRuleSet(behaviorConfig);
            String behaviorLocation = behaviorResponse.getHeaderString("Location");
            String behaviorId = extractIdFromLocation(behaviorLocation);
            int behaviorVersion = extractVersionFromLocation(behaviorLocation);
            createdResources.put("behaviorLocation", behaviorLocation);
            patchDescriptor(behaviorId, behaviorVersion, request.agentName());

            // --- Step 3: Create LLM Configuration ---
            var llmConfig = createLlmConfig(params.providerType, params.modelId, effectiveApiKey, request.systemPrompt(), toolsEnabled,
                    request.builtInToolsWhitelist(), request.baseUrl(), promptResponseJson, quickReplies, sentiment, null);
            Response llmResponse = getRestStore(IRestLlmStore.class).createLlm(llmConfig);
            String langchainLocation = llmResponse.getHeaderString("Location");
            String langchainId = extractIdFromLocation(langchainLocation);
            int langchainVersion = extractVersionFromLocation(langchainLocation);
            createdResources.put("langchainLocation", langchainLocation);
            patchDescriptor(langchainId, langchainVersion, request.agentName());

            // --- Step 4: Create MCP Calls Configurations (if MCP server URLs provided) ---
            List<String> mcpCallsLocations = createMcpCallsResources(request.mcpServerUrls(), request.agentName(), createdResources);

            // --- Step 5: Create Output Set (if intro message provided) ---
            String outputLocation = null;
            if (request.introMessage() != null && !request.introMessage().isBlank()) {
                var outputConfig = createOutputConfig(request.introMessage());
                Response outputResponse = getRestStore(IRestOutputStore.class).createOutputSet(outputConfig);
                outputLocation = outputResponse.getHeaderString("Location");
                String outputId = extractIdFromLocation(outputLocation);
                int outputVersion = extractVersionFromLocation(outputLocation);
                createdResources.put("outputLocation", outputLocation);
                patchDescriptor(outputId, outputVersion, request.agentName());
            }

            // --- Step 6: Create Workflow ---
            var workflowConfig = createWorkflowConfig(parserLocation, behaviorLocation, null, mcpCallsLocations, langchainLocation, outputLocation);
            Response workflowResponse = getRestStore(IRestWorkflowStore.class).createWorkflow(workflowConfig);
            String workflowLocation = workflowResponse.getHeaderString("Location");
            String workflowId = extractIdFromLocation(workflowLocation);
            int workflowVersion = extractVersionFromLocation(workflowLocation);
            createdResources.put("packageLocation", workflowLocation);
            patchDescriptor(workflowId, workflowVersion, request.agentName());

            // --- Step 7: Create Agent ---
            var agentConfig = new AgentConfiguration();
            agentConfig.setWorkflows(List.of(URI.create(workflowLocation)));
            // The gate is installed on v1 of the agent document. It has to be created
            // WITH the agent rather than PUT afterwards: an update writes version + 1 and
            // leaves the ungated v1 reachable by a redeploy, so a two-step provision would
            // ship an agent that can be returned to an ungated state.
            agentConfig.setHitlConfig(request.hitlConfig());
            Response agentResponse = getRestStore(IRestAgentStore.class).createAgent(agentConfig);
            String agentLocation = agentResponse.getHeaderString("Location");
            String agentId = extractIdFromLocation(agentLocation);
            int agentVersion = extractVersionFromLocation(agentLocation);
            createdResources.put("agentLocation", agentLocation);
            patchDescriptor(agentId, agentVersion, request.agentName());

            // --- Step 8: Deploy ---
            var resultBuilder = SetupResult.builder().action("setup_complete").agentId(agentId != null ? agentId : "unknown")
                    .agentName(request.agentName()).provider(params.providerType).model(params.modelId)
                    // So the next agent can be put on this same credential without the
                    // caller having to go digging for the generated key name.
                    .apiKeyVaultReference(vaultReferenceOrNull(effectiveApiKey));

            if (quickReplies)
                resultBuilder.quickRepliesEnabled(true);
            if (sentiment)
                resultBuilder.sentimentAnalysisEnabled(true);

            if (params.shouldDeploy && agentId != null) {
                var deployResult = deployAndWait(params.env, agentId, agentVersion);
                createdResources.putAll(deployResult);
                resultBuilder.deployed((Boolean) deployResult.getOrDefault("deployed", false));
                resultBuilder.deploymentStatus((String) deployResult.getOrDefault("deploymentStatus", "UNKNOWN"));
            }

            resultBuilder.resources(createdResources);
            return resultBuilder.build();

        } catch (Exception e) {
            // No separate AgentSetupException arm here, unlike createApiAgent: nothing
            // inside this try declares one, and every vault error is raised at step 0
            // above, outside it. A catch for an unreachable case is not symmetry.
            rollbackCreatedResources(createdResources, request.agentName(), e);
            throw new AgentSetupException("Failed to set up agent: " + e.getMessage(), e);
        }
    }

    /**
     * Best-effort compensating delete for a setup that failed part-way.
     * <p>
     * Setup creates six to eight documents across as many stores and has no
     * transaction. The failure path used to wrap-and-rethrow, leaving every
     * document created before the failing step orphaned — a parser, a ruleset, an
     * LLM config, MCP calls, an output set and a workflow, with nothing referencing
     * them and nothing to find them by. This path is LLM-reachable through
     * {@code create_sub_agent} and retryable, so a failure loop was unbounded
     * storage growth.
     * <p>
     * Reverse creation order, and every individual delete is isolated: a
     * compensating action must never mask the original failure, which is what the
     * caller actually needs to see.
     */
    private void rollbackCreatedResources(Map<String, Object> createdResources, String agentName, Exception cause) {
        if (createdResources.isEmpty()) {
            return;
        }
        LOGGER.warnf("Agent setup for '%s' failed (%s) — rolling back %d already-created resource(s)",
                LogSanitizer.sanitize(agentName), LogSanitizer.sanitize(cause.getMessage()), createdResources.size());

        // Flattened, because not every recorded value is a scalar: createApiAgent
        // records its generated api-call locations as a List, and a String-only loop
        // silently left every one of those documents behind.
        var locations = new ArrayList<String>();
        for (Object value : createdResources.values()) {
            if (value instanceof String uri) {
                locations.add(uri);
            } else if (value instanceof Collection<?> many) {
                many.stream().filter(String.class::isInstance).map(String.class::cast).forEach(locations::add);
            }
        }
        Collections.reverse(locations);
        for (String uri : locations) {
            if (uri.isBlank()) {
                continue;
            }
            try {
                deleteCreatedResource(uri);
            } catch (Exception e) {
                LOGGER.warnf("Rollback could not delete '%s': %s — it is orphaned and must be removed manually",
                        LogSanitizer.sanitize(uri), LogSanitizer.sanitize(e.getMessage()));
            }
        }

        // The auto-vaulted secret is created BEFORE the LLM document, so a later
        // failure would leave a unique setup.<name>.<timestamp>.apiKey behind and a
        // retry loop would grow the vault without bound. Only ever set for an entry
        // no other setup can be referencing — see vaultApiKey for when that holds.
        Object vaultedKey = createdResources.get(VAULTED_SECRET_KEY);
        if (vaultedKey instanceof String keyName) {
            try {
                secretProvider.delete(new SecretReference(SecretReference.DEFAULT_TENANT, keyName));
            } catch (Exception e) {
                LOGGER.warnf("Rollback could not remove the auto-vaulted secret '%s': %s",
                        LogSanitizer.sanitize(keyName), LogSanitizer.sanitize(e.getMessage()));
            }
        }
    }

    /**
     * {@code createdResources} key under which a secret vaulted by THIS invocation
     * is recorded, so rollback can remove it. Not a resource location, so
     * {@link #deleteCreatedResource} skips it — it is handled explicitly.
     */
    static final String VAULTED_SECRET_KEY = "vaultedSecretKeyName";

    /**
     * Deletes one resource created during setup, dispatched by its Location URI.
     */
    private void deleteCreatedResource(String location) {
        String id = extractIdFromLocation(location);
        if (id == null) {
            return;
        }
        // Permanent, never cascading: these documents were created moments ago by
        // this very call and nothing else references them, so a soft delete would
        // leave the debris this rollback exists to remove — while a cascade could
        // reach resources it does not own.
        Integer version = extractVersionFromLocation(location);
        if (location.contains("/agentstore/")) {
            getRestStore(IRestAgentStore.class).deleteAgent(id, version, true, false);
        } else if (location.contains("/workflowstore/")) {
            getRestStore(IRestWorkflowStore.class).deleteWorkflow(id, version, true, false);
        } else if (location.contains("/llmstore/")) {
            getRestStore(IRestLlmStore.class).deleteLlm(id, version, true);
        } else if (location.contains("/mcpcallsstore/")) {
            getRestStore(IRestMcpCallsStore.class).deleteMcpCalls(id, version, true);
        } else if (location.contains("/outputstore/")) {
            getRestStore(IRestOutputStore.class).deleteOutputSet(id, version, true);
        } else if (location.contains("/rulestore/")) {
            getRestStore(IRestRuleSetStore.class).deleteRuleSet(id, version, true);
        } else if (location.contains("/parserstore/")) {
            getRestStore(IRestParserStore.class).deleteParser(id, version, true);
        } else if (location.contains("/apicallstore/")) {
            getRestStore(IRestApiCallsStore.class).deleteApiCalls(id, version, true);
        }
    }

    /**
     * Create an API agent from an OpenAPI specification. Parses the spec, generates
     * ApiCalls configurations grouped by tag, and creates a fully deployed agent.
     *
     * @param request
     *            the API agent creation parameters
     * @return result with created resource IDs
     * @throws AgentSetupException
     *             if the setup fails
     */
    public SetupResult createApiAgent(CreateApiAgentRequest request) throws AgentSetupException {
        // Validate required params
        validateNameAndPrompt(request.agentName(), request.systemPrompt());
        if (request.openApiSpec() == null || request.openApiSpec().isBlank()) {
            throw new AgentSetupException("OpenAPI spec is required");
        }
        boolean isLocalLLM = isLocalLlmProvider(request.provider());
        // See setupAgent: vaultKeyName alone names a key the vault already holds.
        if (!isLocalLLM && isNullOrBlank(request.apiKey()) && isNullOrBlank(request.vaultKeyName())) {
            throw new AgentSetupException(
                    "API key is required for cloud LLM providers — pass apiKey, or vaultKeyName to reuse a key already in the vault");
        }
        // Scheme-level check only. Full SSRF validation would reject loopback and
        // private addresses, which is precisely where a local LLM provider lives —
        // the reason this field exists.
        if (request.llmBaseUrl() != null && !request.llmBaseUrl().isBlank() && !UrlValidationUtils.isValidHttpUrl(request.llmBaseUrl())) {
            throw new AgentSetupException("llmBaseUrl must be a valid http(s) URL");
        }
        // Validate the HITL config HERE, before a single resource exists.
        // AgentStore.create validates it too, but only at step 7 — so an unusable
        // approval pattern would surface after the apicalls, parser, behaviour, LLM
        // and workflow had all been created, leaving every one of them orphaned.
        // The caller gets the same actionable message either way; only the debris
        // differs.
        try {
            HitlConfigValidation.validate(request.hitlConfig());
        } catch (IllegalArgumentException e) {
            throw new AgentSetupException("Invalid hitlConfig: " + e.getMessage(), e);
        }
        validateMcpServerUrls(request.mcpServerUrls());
        // Same up-front reasoning as hitlConfig: an out-of-range value must fail
        // before the first resource exists, not after six of them do.
        validateMaxToolIterations(request.maxToolIterations());

        var params = resolveParamsValidated(request.provider(), request.model(), request.deploy(), request.environment());
        var createdResources = new LinkedHashMap<String, Object>();

        // --- Step 0a: Parse the OpenAPI spec ---
        // Before the vault, deliberately. Parsing creates nothing, so ordering it
        // first means an unparseable spec — the likeliest way this call fails — never
        // reaches the vault at all, rather than relying on rollback to remove a secret
        // it should not have written. Key resolution still precedes every store call.
        McpApiToolBuilder.ApiBuildResult buildResult;
        try {
            buildResult = McpApiToolBuilder.parseAndBuild(request.openApiSpec(), request.endpoints(), request.apiBaseUrl(), request.apiAuth(),
                    request.apiAuthHeader());
        } catch (IllegalArgumentException e) {
            throw new AgentSetupException("OpenAPI parsing failed: " + e.getMessage(), e);
        }

        // --- Step 0b: Resolve the API key against the vault ---
        // Outside the try, so an unusable vaultKeyName fails while rollback still has
        // nothing to undo and its message reaches the caller unwrapped. See setupAgent
        // for the full note.
        String effectiveApiKey = vaultApiKey(request.apiKey(), request.agentName(), request.vaultKeyName(), createdResources);

        try {

            // --- Step 2: Create ApiCalls resources (one per group) ---
            var httpCallsLocations = new ArrayList<String>();
            var groupNames = new ArrayList<String>();
            for (var entry : buildResult.configsByGroup().entrySet()) {
                String groupName = entry.getKey();
                var config = entry.getValue();
                Response httpCallsResponse = getRestStore(IRestApiCallsStore.class).createApiCalls(config);
                String httpCallsLocation = httpCallsResponse.getHeaderString("Location");
                httpCallsLocations.add(httpCallsLocation);
                groupNames.add(groupName);

                String httpCallsId = extractIdFromLocation(httpCallsLocation);
                int httpCallsVersion = extractVersionFromLocation(httpCallsLocation);
                patchDescriptor(httpCallsId, httpCallsVersion, request.agentName() + " - " + groupName);
            }
            createdResources.put("httpCallsGroups", groupNames);
            createdResources.put("httpCallsLocations", httpCallsLocations);

            // --- Step 3: Create Parser ---
            var parserConfig = createParserConfig();
            Response parserResponse = getRestStore(IRestParserStore.class).createParser(parserConfig);
            String parserLocation = parserResponse.getHeaderString("Location");
            createdResources.put("parserLocation", parserLocation);
            patchDescriptor(extractIdFromLocation(parserLocation), extractVersionFromLocation(parserLocation), request.agentName());

            // --- Step 4: Create Behavior Rules ---
            var behaviorConfig = createBehaviorConfig();
            Response behaviorResponse = getRestStore(IRestRuleSetStore.class).createRuleSet(behaviorConfig);
            String behaviorLocation = behaviorResponse.getHeaderString("Location");
            createdResources.put("behaviorLocation", behaviorLocation);
            patchDescriptor(extractIdFromLocation(behaviorLocation), extractVersionFromLocation(behaviorLocation), request.agentName());

            String enrichedPrompt = enrichSystemPrompt(request.systemPrompt(), buildResult.apiSummary());
            boolean quickReplies = request.enableQuickReplies() != null && request.enableQuickReplies();
            boolean sentiment = request.enableSentimentAnalysis() != null && request.enableSentimentAnalysis();
            String promptResponseJson = buildPromptResponseJson(quickReplies, sentiment);
            // 7th slot is the LLM's own base URL — not apiBaseUrl, which is the target
            // server of the generated tools. Passing null here left local providers
            // (Ollama, Jlama) with no endpoint to reach.
            var llmConfig = createLlmConfig(params.providerType, params.modelId, effectiveApiKey, enrichedPrompt, false, null,
                    request.llmBaseUrl(), promptResponseJson, quickReplies, sentiment, httpCallsLocations);
            // Set post-build rather than threading a 12th parameter through
            // createLlmConfig: the task is mutable, only this path accepts the value,
            // and every other caller (setupAgent, four test sites) keeps the engine
            // default untouched. Validated up front — see validateMaxToolIterations.
            if (request.maxToolIterations() != null) {
                llmConfig.tasks().getFirst().setMaxToolIterations(request.maxToolIterations());
            }
            Response llmResponse = getRestStore(IRestLlmStore.class).createLlm(llmConfig);
            String langchainLocation = llmResponse.getHeaderString("Location");
            createdResources.put("langchainLocation", langchainLocation);
            patchDescriptor(extractIdFromLocation(langchainLocation), extractVersionFromLocation(langchainLocation), request.agentName());

            // --- Step 5b: Create MCP Calls Configurations (if MCP URLs provided) ---
            List<String> mcpCallsLocations = createMcpCallsResources(request.mcpServerUrls(), request.agentName(), createdResources);

            // --- Step 6: Create Workflow (with httpcalls in pipeline) ---
            var workflowConfig = createWorkflowConfig(parserLocation, behaviorLocation, httpCallsLocations, mcpCallsLocations, langchainLocation,
                    null);
            Response workflowResponse = getRestStore(IRestWorkflowStore.class).createWorkflow(workflowConfig);
            String workflowLocation = workflowResponse.getHeaderString("Location");
            createdResources.put("packageLocation", workflowLocation);
            patchDescriptor(extractIdFromLocation(workflowLocation), extractVersionFromLocation(workflowLocation), request.agentName());

            // --- Step 7: Create Agent ---
            var agentConfig = new AgentConfiguration();
            agentConfig.setWorkflows(List.of(URI.create(workflowLocation)));
            // The gate is installed on v1 of the agent document. It has to be created
            // WITH the agent rather than PUT afterwards: an update writes version + 1 and
            // leaves the ungated v1 reachable by a redeploy, so a two-step provision would
            // ship an agent that can be returned to an ungated state.
            agentConfig.setHitlConfig(request.hitlConfig());
            Response agentResponse = getRestStore(IRestAgentStore.class).createAgent(agentConfig);
            String agentLocation = agentResponse.getHeaderString("Location");
            String agentId = extractIdFromLocation(agentLocation);
            int agentVersion = extractVersionFromLocation(agentLocation);
            createdResources.put("agentLocation", agentLocation);
            patchDescriptor(agentId, agentVersion, request.agentName());

            // --- Step 8: Deploy ---
            var resultBuilder = SetupResult.builder().action("api_agent_created").agentId(agentId != null ? agentId : "unknown")
                    .agentName(request.agentName()).provider(params.providerType).model(params.modelId).endpointCount(buildResult.endpointCount())
                    .groups(groupNames)
                    // See setupAgent: hands the caller the reference to reuse next time.
                    .apiKeyVaultReference(vaultReferenceOrNull(effectiveApiKey));

            if (params.shouldDeploy && agentId != null) {
                var deployResult = deployAndWait(params.env, agentId, agentVersion);
                createdResources.putAll(deployResult);
                resultBuilder.deployed((Boolean) deployResult.getOrDefault("deployed", false));
                resultBuilder.deploymentStatus((String) deployResult.getOrDefault("deploymentStatus", "UNKNOWN"));
            }

            resultBuilder.resources(createdResources);
            return resultBuilder.build();

        } catch (AgentSetupException e) {
            // Rethrown unwrapped, so the caller sees the real reason rather than it
            // nested inside "Failed to create API agent:" — but it still has to roll
            // back. Without this, every AgentSetupException raised AFTER the first
            // store (an unparseable spec, a rejected maxToolIterations) orphaned
            // everything created up to that point, and once key resolution moved
            // ahead of the try that included the vaulted secret.
            rollbackCreatedResources(createdResources, request.agentName(), e);
            throw e;
        } catch (Exception e) {
            rollbackCreatedResources(createdResources, request.agentName(), e);
            throw new AgentSetupException("Failed to create API agent: " + e.getMessage(), e);
        }
    }

    /**
     * Ceiling for a caller-supplied {@code maxToolIterations}. Each iteration is a
     * full LLM round-trip carrying the tool context, so the value multiplies both
     * cost and worst-case turn latency; nothing in the engine bounds the task field
     * itself ({@code ToolLoopRunner} honours whatever the config says). 100 is ten
     * times the engine default — and deliberately exactly the value the Platform
     * Operator provisions with: an operator turn is one admin task of arbitrary
     * length (an agent build via granular endpoints already burns ~22 calls), and
     * it runs under the HITL gate, which paces every write regardless of budget.
     * The ceiling exists to cap a typo like 5000, not to second-guess a legitimate
     * long chain; raising it is a deliberate one-line change, not a config knob.
     */
    public static final int MAX_TOOL_ITERATIONS = 100;

    /**
     * Same up-front contract as the other validators: reject before the first
     * resource exists. {@code null} is valid and keeps the engine default.
     */
    private void validateMaxToolIterations(Integer maxToolIterations) throws AgentSetupException {
        if (maxToolIterations == null) {
            return;
        }
        if (maxToolIterations < 1 || maxToolIterations > MAX_TOOL_ITERATIONS) {
            throw new AgentSetupException(
                    "maxToolIterations must be between 1 and " + MAX_TOOL_ITERATIONS + " (was " + maxToolIterations
                            + "); omit it to use the engine default");
        }
    }

    /**
     * Validates every MCP server URL before any of them is written.
     * <p>
     * {@code McpCallsConfiguration.validate()} rejects a non-http(s) URL at save
     * time, so without this the second bad URL in a list would abort the run with
     * the first one's resource already persisted — and in {@code createApiAgent}
     * with the apicalls, parser, behaviour and LLM resources persisted too. Same
     * reasoning as the up-front {@code hitlConfig} check: a config error is cheap
     * to detect before the first write, and the caller gets the same message with
     * no debris.
     */
    private void validateMcpServerUrls(String mcpServerUrls) throws AgentSetupException {
        if (mcpServerUrls == null || mcpServerUrls.isBlank()) {
            return;
        }
        for (String url : mcpServerUrls.split(",")) {
            String trimmed = url.trim();
            if (trimmed.isEmpty())
                continue;
            try {
                createMcpCallsConfig(trimmed).validate();
            } catch (IllegalArgumentException e) {
                throw new AgentSetupException("Invalid mcpServerUrls entry '" + trimmed + "': " + e.getMessage(), e);
            }
        }
    }

    /**
     * Creates one McpCalls resource per comma-separated server URL, recording each
     * location in {@code createdResources}. Returns null when no URLs were given,
     * which is what {@code createWorkflowConfig} expects for "no MCP step".
     * <p>
     * Shared by {@code setupAgent} and {@code createApiAgent} so an API agent can
     * hold both the tools generated from its OpenAPI spec and an MCP server's —
     * previously only the former, which made "REST plus MCP" unreachable through
     * the wizard.
     */

    private List<String> createMcpCallsResources(String mcpServerUrls, String agentName, Map<String, Object> createdResources)
            throws Exception {
        if (mcpServerUrls == null || mcpServerUrls.isBlank()) {
            return null;
        }
        var locations = new ArrayList<String>();
        for (String url : mcpServerUrls.split(",")) {
            String trimmed = url.trim();
            if (trimmed.isEmpty())
                continue;
            var mcpConfig = createMcpCallsConfig(trimmed);
            Response mcpResponse = getRestStore(IRestMcpCallsStore.class).createMcpCalls(mcpConfig);
            String mcpLocation = mcpResponse.getHeaderString("Location");
            locations.add(mcpLocation);
            createdResources.put("mcpCallsLocation_" + locations.size(), mcpLocation);
            patchDescriptor(extractIdFromLocation(mcpLocation), extractVersionFromLocation(mcpLocation), agentName);
        }
        return locations;
    }

    // ==================== Config Builders ====================

    /**
     * Create a minimal parser config with basic built-in dictionaries.
     */
    public ParserConfiguration createParserConfig() {
        var config = new ParserConfiguration();
        config.setExtensions(Map.of("dictionaries", List.of(), "corrections", List.of()));
        return config;
    }

    /**
     * Create behavior rules: catch-all inputmatcher(*) → send_message action.
     */
    public RuleSetConfiguration createBehaviorConfig() {
        var condition = new RuleConditionConfiguration();
        condition.setType("inputmatcher");
        condition.setConfigs(Map.of("expressions", "*"));

        var rule = new RuleConfiguration();
        rule.setName("Send Message to LLM");
        rule.setActions(List.of("send_message"));
        rule.setConditions(List.of(condition));

        var group = new RuleGroupConfiguration();
        group.setRules(List.of(rule));

        var config = new RuleSetConfiguration();
        config.setExpressionsAsActions(true);
        config.setBehaviorGroups(List.of(group));
        return config;
    }

    /**
     * Appends the generated API endpoint summary to the caller's system prompt, so
     * the LLM knows which endpoints exist and how to use them.
     * <p>
     * The summary is wrapped in a Qute unparsed block; the caller's own prompt is
     * not. A summary line is a raw OpenAPI path, and a path parameter is
     * indistinguishable from a Qute expression —
     * {@code /administration/docs/{name}} <em>is</em> <code>{name}</code>.
     * {@code LlmTask} renders the system prompt on every turn, so an unescaped
     * summary failed the render with {@code Key "name" not found}, naming a key
     * nobody wrote in a prompt fragment the agent's author never saw.
     * <p>
     * The turn then continued, which is the part worth knowing:
     * {@code runTemplateEngineOnParams} logs the failure per parameter and leaves
     * the RAW value in place, so the model was sent the whole system prompt
     * unrendered — including the caller's own <code>{#if}</code> sections,
     * verbatim. A silently degraded prompt on every turn, plus a stack trace per
     * turn, rather than a clean failure. Escaping only the generated half leaves
     * the caller's prompt a live template, which is what it is meant to be: the
     * Manager's operator prompt uses <code>{#if context.screen}</code>.
     */
    static String enrichSystemPrompt(String systemPrompt, String apiSummary) {
        return systemPrompt + "\n\n" + TemplateEscaping.unparsedBlock(apiSummary);
    }

    /**
     * Create LLM config with the specified model, system prompt, and tool settings.
     */
    public LlmConfiguration createLlmConfig(String modelType, String modelId, String apiKey, String systemPrompt, boolean enableTooling,
                                            String toolsWhitelist, String baseUrl, String promptResponseJson, boolean quickReplies, boolean sentiment,
                                            List<String> toolUris) {
        var task = new LlmConfiguration.Task();
        task.setActions(List.of("send_message"));
        task.setId(modelType);
        task.setType(modelType);
        task.setDescription("LLM integration via " + modelType);

        String effectiveSystemPrompt = systemPrompt;
        if (promptResponseJson != null) {
            effectiveSystemPrompt = systemPrompt + "\n\n" + promptResponseJson;
        }

        var params = new LinkedHashMap<String, String>();
        params.put("systemMessage", effectiveSystemPrompt);
        params.put("addToOutput", promptResponseJson == null ? "true" : "false");
        params.put("timeout", "60000");
        // No "temperature" is written. It used to be pinned to 0.3 for every provider
        // and every model, which is not a value this service is in a position to have
        // an opinion about: current frontier models REJECT the parameter outright
        // ("`temperature` is deprecated for this model" — a 400 from Anthropic on the
        // default model, so every turn of a wizard-created agent failed). Omitting it
        // lets each provider apply its own default; an agent designer who wants a
        // specific sampling temperature adds it to the generated config, where it is
        // an explicit choice rather than an invisible inherited one.
        // Written explicitly (rather than omitted) so the setting is visible and
        // flippable on the generated config in the Manager. See
        // #logConversationContent for why the default is off.
        params.put("logRequests", String.valueOf(logConversationContent));
        params.put("logResponses", String.valueOf(logConversationContent));

        if (promptResponseJson != null) {
            params.put("convertToObject", "true");
            task.setResponseObjectName("aiOutput");
        }

        // No builder-level "responseFormat" parameter is injected here. It was only
        // ever
        // read by OpenAILanguageModelBuilder (dead for mistral and azure-openai), and
        // it
        // bakes JSON mode into a CACHED model that is then reused for tool-calling and
        // streaming requests — the shape that produced the Gemini 400 (see
        // docs/changelog.md, 2026-04-02). convertToObject alone now drives API-level
        // JSON,
        // applied per request by JsonResponseFormatPolicy for every execution mode.
        // A hand-written config may still set responseFormat=json; the builder still
        // honours it.

        switch (modelType) {
            case "ollama" -> {
                params.put("model", modelId);
                String effectiveBaseUrl = (baseUrl != null && !baseUrl.isBlank()) ? baseUrl : ollamaDefaultBaseUrl;
                params.put("baseUrl", effectiveBaseUrl);
            }
            case "jlama" -> {
                params.put("modelName", modelId);
                if (apiKey != null && !apiKey.isBlank()) {
                    params.put("authToken", apiKey);
                }
            }
            case "bedrock" -> {
                params.put("modelId", modelId);
                // Auth via AWS credential chain (env vars, IAM roles, ~/.aws/credentials)
            }
            case "azure-openai" -> {
                params.put("deploymentName", modelId);
                if (apiKey != null && !apiKey.isBlank()) {
                    params.put("apiKey", apiKey);
                }
                if (baseUrl != null && !baseUrl.isBlank()) {
                    params.put("endpoint", baseUrl);
                }
            }
            case "oracle-genai" -> {
                params.put("modelName", modelId);
                // Auth via OCI config file (~/.oci/config)
            }
            default -> {
                params.put("modelName", modelId);
                if (apiKey != null && !apiKey.isBlank()) {
                    params.put("apiKey", apiKey);
                }
                if (baseUrl != null && !baseUrl.isBlank()) {
                    params.put("baseUrl", baseUrl);
                }
            }
        }

        task.setParameters(params);

        if (enableTooling) {
            task.setEnableBuiltInTools(true);
            if (toolsWhitelist != null && !toolsWhitelist.isBlank()) {
                task.setBuiltInToolsWhitelist(List.of(toolsWhitelist.split(",")).stream().map(String::trim).filter(s -> !s.isEmpty()).toList());
            }
        }

        // Wire httpcall URIs as EddiToolBridge tools (used by API agents)
        if (toolUris != null && !toolUris.isEmpty()) {
            task.setTools(toolUris);
        }

        task.setConversationHistoryLimit(10);

        if (promptResponseJson != null) {
            task.setPostResponse(buildPostResponse(quickReplies, sentiment));
        }

        return new LlmConfiguration(List.of(task));
    }

    /**
     * The provider an omitted {@code provider} resolves to. Public because a caller
     * that must validate against its own allow-list has to know the value this
     * service will actually use — guarding the raw argument instead let a caller
     * skip the allow-list entirely by omitting the parameter.
     */
    public static final String DEFAULT_PROVIDER = "anthropic";

    /** The model an omitted {@code model} resolves to. */
    public static final String DEFAULT_MODEL = "claude-sonnet-4-6";

    /** Upper bound on a setup-supplied agent name. */
    public static final int MAX_AGENT_NAME_LENGTH = 200;

    /**
     * Upper bound on a setup-supplied system prompt. Generous — a real system
     * prompt can be long — but bounded: this input reaches the service from
     * {@code create_sub_agent}, i.e. it is LLM-authored and retryable.
     */
    public static final int MAX_SYSTEM_PROMPT_LENGTH = 100_000;

    /**
     * Validates the two free-text fields every setup flow requires.
     * <p>
     * Both were checked only for blankness. They are LLM-supplied on the
     * {@code create_sub_agent} path, where a failure is retryable, and they are
     * persisted — the name into descriptors and into the vault key namespace, the
     * prompt verbatim into the LLM config. An unbounded value is a storage-growth
     * and log-noise vector with no legitimate use.
     */
    private void validateNameAndPrompt(String agentName, String systemPrompt) throws AgentSetupException {
        if (agentName == null || agentName.isBlank()) {
            throw new AgentSetupException("Agent name is required");
        }
        if (agentName.length() > MAX_AGENT_NAME_LENGTH) {
            throw new AgentSetupException(
                    "Agent name is too long (" + agentName.length() + " characters, maximum " + MAX_AGENT_NAME_LENGTH + ")");
        }
        if (systemPrompt == null || systemPrompt.isBlank()) {
            throw new AgentSetupException("System prompt is required");
        }
        if (systemPrompt.length() > MAX_SYSTEM_PROMPT_LENGTH) {
            throw new AgentSetupException(
                    "System prompt is too long (" + systemPrompt.length() + " characters, maximum " + MAX_SYSTEM_PROMPT_LENGTH + ")");
        }
    }

    /**
     * The LLM credential a sub-agent should inherit from its parent, or
     * {@code null} when there is nothing safe to inherit.
     * <p>
     * {@code create_sub_agent} passed {@code null} for {@code apiKey} with the
     * comment "inherited from vault", and nothing implemented that inheritance — so
     * every sub-agent creation for a provider that needs a key (which includes the
     * default, {@code anthropic}) failed outright on the required-API-key check.
     * The tool's own parameter documentation promised the same behaviour.
     * <p>
     * <b>Only a vault REFERENCE is inherited, never a plaintext key.</b> When the
     * vault is unavailable {@code vaultApiKey} falls back to storing the key in
     * clear, and copying such a value into a second config would multiply the blast
     * radius of that fallback instead of referencing one secret from two places. A
     * parent whose key is plaintext therefore inherits nothing, and the caller gets
     * the ordinary "API key is required" error.
     *
     * @return the parent's {@code ${vault:...}} reference, or {@code null}
     */
    public ParentLlmProfile resolveParentLlmProfile(String parentAgentId) {
        if (parentAgentId == null || parentAgentId.isBlank()) {
            return null;
        }
        try {
            // The version must be resolved first. RestVersionInfo.read does
            // checkNotNull(version), so readAgent(id, null) ALWAYS throws — which this
            // method's catch swallowed, making inheritance silently return null and
            // leaving create_sub_agent failing with "API key is required", i.e. the
            // exact defect it was written to fix.
            IRestAgentStore agentRestStore = getRestStore(IRestAgentStore.class);
            Integer currentVersion = agentRestStore.getCurrentVersion(parentAgentId);
            if (currentVersion == null) {
                return null;
            }
            AgentConfiguration parent = agentRestStore.readAgent(parentAgentId, currentVersion);
            if (parent == null || parent.getWorkflows() == null) {
                return null;
            }
            for (URI workflowUri : parent.getWorkflows()) {
                LlmConfiguration.Task task = firstLlmTaskOf(workflowUri);
                if (task == null) {
                    continue;
                }
                Map<String, String> parameters = task.getParameters() != null ? task.getParameters() : Map.of();
                String credential = firstNonBlank(parameters.get("apiKey"), parameters.get("authToken"));
                // A reference, not a secret — see the method Javadoc.
                // Full-pattern match, not isVaultReference: that only asks whether the
                // value CONTAINS "${vault:", so "plaintext${vault:key}" would be
                // treated as a safe reference and the plaintext half copied into the
                // child's config — the exact thing this method promises never to do.
                if (credential != null && !SecretReference.compiledPattern().matcher(credential).matches()) {
                    credential = null;
                }
                String model = firstNonBlank(parameters.get("modelName"), parameters.get("model"), parameters.get("modelId"),
                        parameters.get("deploymentName"));
                if (task.getType() == null && model == null && credential == null) {
                    // Nothing inheritable here. Returning an all-null profile would make
                    // the caller's `parentProfile != null` mean "inheritance available"
                    // and resolve provider and model to null, while also skipping every
                    // remaining workflow.
                    continue;
                }
                return new ParentLlmProfile(task.getType(), model, credential);
            }
        } catch (Exception e) {
            LOGGER.warnf("Could not resolve the LLM profile of parent agent '%s' for inheritance: %s",
                    LogSanitizer.sanitize(parentAgentId), e.getMessage());
        }
        return null;
    }

    /** The first LLM task reachable from a workflow, or {@code null}. */
    private LlmConfiguration.Task firstLlmTaskOf(URI workflowUri) {
        try {
            String workflowId = extractIdFromLocation(workflowUri.toString());
            if (workflowId == null) {
                return null;
            }
            WorkflowConfiguration workflow = getRestStore(IRestWorkflowStore.class).readWorkflow(workflowId,
                    extractVersionFromLocation(workflowUri.toString()));
            if (workflow == null || workflow.getWorkflowSteps() == null) {
                return null;
            }
            for (WorkflowConfiguration.WorkflowStep step : workflow.getWorkflowSteps()) {
                if (step.getType() == null || !step.getType().toString().contains("ai.labs.llm")) {
                    continue;
                }
                Object uri = step.getConfig() != null ? step.getConfig().get("uri") : null;
                if (uri == null) {
                    continue;
                }
                String llmId = extractIdFromLocation(uri.toString());
                if (llmId == null) {
                    continue;
                }
                LlmConfiguration llm = getRestStore(IRestLlmStore.class).readLlm(llmId, extractVersionFromLocation(uri.toString()));
                if (llm != null && llm.tasks() != null && !llm.tasks().isEmpty()) {
                    return llm.tasks().getFirst();
                }
            }
        } catch (Exception e) {
            LOGGER.debugf("Could not read the LLM task of workflow '%s': %s", LogSanitizer.sanitize(String.valueOf(workflowUri)),
                    e.getMessage());
        }
        return null;
    }

    private static boolean isNullOrBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    /**
     * A parent agent's LLM identity, as far as it can be inherited.
     *
     * @param apiKeyReference
     *            always a {@code ${vault:...}} reference or {@code null} — never a
     *            plaintext secret
     */
    public record ParentLlmProfile(String provider, String model, String apiKeyReference) {
    }

    /**
     * Resolve the value that goes into the generated LLM config's {@code apiKey}
     * parameter, vaulting the plaintext only when the vault does not already hold
     * it.
     * <p>
     * Four inputs, in the order they are considered:
     * <ol>
     * <li><b>{@code vaultKeyName} given</b> — the caller names the entry, so the
     * caller decides. An existing entry is referenced (and, when {@code apiKey} is
     * also given, must hold the same value — setup will not silently overwrite a
     * secret other agents already point at); a missing one is created under exactly
     * that name from the supplied {@code apiKey}. This is the field that makes
     * "provision N agents against one key" a single, stable, human-readable
     * reference.</li>
     * <li><b>{@code apiKey} is already a {@code ${vault:...}} reference</b> — used
     * as-is, never re-vaulted. Note the {@link String#trim()} below: the check is a
     * full-string match, so before it a value pasted out of a UI list carried its
     * trailing newline into the "not a reference" branch and was vaulted <em>as
     * plaintext whose content is a reference</em> — a fresh, useless key on every
     * setup, which is the very thing this method exists to prevent.</li>
     * <li><b>{@code apiKey} is plaintext the vault already holds</b> — reused by
     * checksum, unless {@code eddi.setup.vault-key-reuse=never}. See
     * {@link #vaultKeyReuse}.</li>
     * <li><b>{@code apiKey} is plaintext the vault does not hold</b> — stored under
     * the generated {@code setup.<agent>.<timestamp>.apiKey} name, as before.</li>
     * </ol>
     *
     * @param vaultKeyName
     *            explicit vault key name (or full {@code ${vault:...}} reference)
     *            to store under and reuse; null or blank to let this method choose
     * @param createdResources
     *            when non-null, the key name of a secret <b>newly created</b> by
     *            this call — and that no other setup can find — is recorded here so
     *            a failed setup can remove it again. A <em>reused</em> entry, a
     *            caller-<em>named</em> entry, and (under {@code checksum} reuse) a
     *            generated one are deliberately never recorded: rollback deletes
     *            what it finds there, and a key another agent may already point at
     *            must survive the failure of the one that happened to create it.
     *            Also used to carry non-fatal warnings (see
     *            {@link #VAULT_GRANT_WARNING}) into {@code SetupResult.resources}.
     */
    private String vaultApiKey(String apiKey, String agentName, String vaultKeyName, Map<String, Object> createdResources)
            throws AgentSetupException {
        // Trimmed once, here, and used for every subsequent decision AND as the stored
        // value: a key with a stray newline is not a usable credential either.
        String key = apiKey == null ? null : apiKey.trim();
        String requestedName = vaultKeyName == null ? null : vaultKeyName.trim();

        if (requestedName != null && !requestedName.isEmpty()) {
            return useNamedVaultKey(requestedName, key, agentName, createdResources);
        }

        if (key == null || key.isEmpty()) {
            return apiKey;
        }

        // Already a vault reference — use it directly, don't re-vault (supports legacy
        // ${eddivault:...})
        if (isVaultReference(key)) {
            LOGGER.infof("API key for agent '%s' is already a vault reference — using as-is.", LogSanitizer.sanitize(agentName));
            checkReferencedSecret(SecretReference.parse(key), agentName, createdResources);
            return key;
        }

        if (!secretProvider.isAvailable()) {
            LOGGER.warn("Secrets Vault is not configured — API key will be stored in plaintext. "
                    + "Set EDDI_VAULT_MASTER_KEY to enable encrypted storage.");
            return key;
        }

        String reusable = findReusableSecret(key);
        if (reusable != null) {
            LOGGER.infof("API key for agent '%s' is already in the vault as '%s' — reusing it instead of storing a second copy.",
                    LogSanitizer.sanitize(agentName), LogSanitizer.sanitize(reusable));
            return reusable;
        }

        try {
            // Namespace: setup.<sanitized-agent-name>.<timestamp>-<random>.apiKey
            //
            // The timestamp alone did not make this unique. Two setups for agents with
            // the same name, landing in the same millisecond, produced the same key
            // name — and secretProvider.store is an UPSERT, so the second silently
            // overwrote the first, leaving one agent referencing the other's
            // credential. The random suffix is what actually prevents that; the
            // timestamp stays because it tells an operator reading the vault when the
            // entry was made.
            String sanitizedName = agentName.toLowerCase().replaceAll("[^a-z0-9]", "-");
            String keyName = "setup." + sanitizedName + "." + System.currentTimeMillis() + "-"
                    + UUID.randomUUID().toString().substring(0, 8) + ".apiKey";
            // Rollback deletes what is recorded here, and that is only safe while nobody
            // else can be referencing the entry. Under `never` that holds: the name is
            // unique and no other setup will find it. Under `checksum` it does not — the
            // moment a second setup with the same key runs, this entry is THEIR
            // reference too, and a rollback triggered by this setup's later failure
            // would break every agent that reused it in the meantime. The growth this
            // rollback was added to prevent (a retry loop minting a key per attempt)
            // cannot happen under `checksum` anyway: the retry finds this very entry by
            // value and reuses it. So under `checksum` the entry is left in place, and
            // is either reused by the retry or is one harmless orphan.
            Map<String, Object> rollbackRegistry = VAULT_KEY_REUSE_NEVER.equalsIgnoreCase(vaultKeyReuse) ? createdResources : null;
            return storeSecret(new SecretReference(SecretReference.DEFAULT_TENANT, keyName), key, agentName, rollbackRegistry);
        } catch (ISecretProvider.SecretProviderException e) {
            LOGGER.error("Failed to vault API key for agent '" + LogSanitizer.sanitize(agentName) + "': " + e.getMessage()
                    + " — falling back to plaintext storage.");
            return key;
        }
    }

    /**
     * Honour an explicit {@code vaultKeyName}. Unlike the generated-name path this
     * one fails loudly rather than degrading: a caller who named a key asked for
     * one specific shared secret, and quietly writing a plaintext key or a second
     * copy under a different name would defeat the reason they named it.
     */
    private String useNamedVaultKey(String requestedName, String key, String agentName, Map<String, Object> createdResources)
            throws AgentSetupException {
        // Accept both "my-openai-key" and "${vault:my-openai-key}" — the Manager and
        // the stored configs show the reference form, so pasting that form into a
        // field labelled "key name" is the obvious mistake to absorb rather than
        // reject.
        SecretReference ref = isVaultReference(requestedName)
                ? SecretReference.parse(requestedName)
                : new SecretReference(SecretReference.DEFAULT_TENANT, requestedName);
        // Same charset the secrets REST API enforces on create. Not decoration: the
        // value is about to be embedded in "${vault:<tenant>/<key>}", where a '/' in a
        // BARE name would silently re-parse as a tenant separator and a '}' would
        // truncate the reference — the agent would then resolve a different secret, or
        // none. Multi-tenant callers use the ${vault:tenant/key} form, which is parsed
        // above rather than guessed at here.
        validateSecretName(ref.tenantId(), "tenant in vaultKeyName");
        validateSecretName(ref.keyName(), "vaultKeyName");

        // Two different keys named in one request. Honouring vaultKeyName and dropping
        // the apiKey reference on the floor would deploy an agent against a credential
        // the caller did not pick, so say so instead.
        if (key != null && isVaultReference(key) && !SecretReference.parse(key).equals(ref)) {
            throw new AgentSetupException("apiKey references vault key '" + SecretReference.parse(key).keyName()
                    + "' but vaultKeyName says '" + ref.keyName() + "'. Pass one or the other.");
        }

        if (!secretProvider.isAvailable()) {
            throw new AgentSetupException("vaultKeyName '" + ref.keyName() + "' cannot be used: the secrets vault is unavailable or "
                    + "disabled. Set EDDI_VAULT_MASTER_KEY, or omit vaultKeyName to pass the key through as plaintext.");
        }

        SecretMetadata existing;
        try {
            existing = secretProvider.getMetadata(ref);
        } catch (ISecretProvider.SecretNotFoundException e) {
            existing = null;
        } catch (ISecretProvider.SecretProviderException e) {
            throw new AgentSetupException("Could not read vault key '" + ref.keyName() + "': " + e.getMessage(), e);
        }

        boolean haveNewPlaintext = key != null && !key.isEmpty() && !isVaultReference(key);

        if (existing != null) {
            if (haveNewPlaintext && !EnvelopeCrypto.sha256Hex(key).equals(existing.checksum())) {
                throw new AgentSetupException("vaultKeyName '" + ref.keyName() + "' already holds a value that does not match the "
                        + "apiKey supplied. Setup will not overwrite it, because other agents may reference it. Use a different "
                        + "vaultKeyName, omit apiKey to reuse the stored value, or rotate the key through the secrets API first.");
            }
            LOGGER.infof("Agent '%s' reuses existing vault key '%s'.", LogSanitizer.sanitize(agentName),
                    LogSanitizer.sanitize(ref.keyName()));
            warnIfRestricted(existing, agentName, createdResources);
            return ref.toReferenceString();
        }

        if (!haveNewPlaintext) {
            throw new AgentSetupException("vaultKeyName '" + ref.keyName() + "' does not exist in the vault and no plaintext apiKey was "
                    + "supplied to create it. Supply the key in apiKey to store it under that name, or name an existing vault key.");
        }

        try {
            // Deliberately NOT registered for rollback (null, not createdResources).
            // Rollback exists to stop a retry loop growing the vault without bound, and
            // a caller-chosen name cannot: a retry reuses the same name. Deleting it
            // would be the more dangerous act — between this store and the failure that
            // triggers rollback, a concurrent setup may already have reused the entry,
            // and rollback would pull the key out from under an agent that is not ours.
            // Leaving it also means the retry finds the key already in place.
            String reference = storeSecret(ref, key, agentName, null);
            verifyStoredValue(ref, key);
            return reference;
        } catch (ISecretProvider.SecretProviderException e) {
            throw new AgentSetupException("Could not store the API key under vault key '" + ref.keyName() + "': " + e.getMessage(), e);
        }
    }

    /**
     * Read the entry back and fail if it does not hold what was just written.
     * <p>
     * The absent-then-create sequence above is not atomic: {@code store} is an
     * UPSERT (every {@link ISecretProvider} caller uses it that way — there is no
     * create-if-absent in the SPI), so two setups naming the same key with
     * different values can both find it missing and both write. Without this check
     * the loser proceeds and provisions an agent pointing at the winner's
     * credential.
     * <p>
     * This narrows the window rather than closing it: a write that lands after this
     * read is still missed. Closing it properly needs a conditional insert in the
     * persistence layer (Mongo and Postgres both), which is an SPI change shared
     * with three other callers and does not belong in this one — tracked in issue
     * #700. What it does buy is that the common interleaving fails loudly, here,
     * before a single document is created — instead of silently.
     */
    private void verifyStoredValue(SecretReference ref, String expectedPlaintext) throws AgentSetupException {
        try {
            SecretMetadata written = secretProvider.getMetadata(ref);
            if (written.checksum() != null && !EnvelopeCrypto.sha256Hex(expectedPlaintext).equals(written.checksum())) {
                throw new AgentSetupException("Vault key '" + ref.keyName() + "' was written concurrently by another setup and now holds "
                        + "a different value. Nothing was created; retry, or choose a vaultKeyName that is not in contention.");
            }
        } catch (ISecretProvider.SecretNotFoundException | ISecretProvider.SecretProviderException e) {
            LOGGER.debugf("Could not read back vault key '%s' after storing it: %s", LogSanitizer.sanitize(ref.keyName()), e.getMessage());
        }
    }

    /**
     * Write one secret and record it for rollback. Split out so the named and the
     * generated path cannot drift on the grant list or the rollback bookkeeping.
     */
    private String storeSecret(SecretReference ref, String plaintext, String agentName, Map<String, Object> createdResources)
            throws ISecretProvider.SecretProviderException {
        // "*" is deliberate. allowedAgents IS enforced now (VaultGrantGate, at
        // deploy time), so this list is a real access-control decision — but the
        // agent being set up does not have an ID yet at this point, and the key is
        // created for whichever agent this setup produces. Narrowing it here would
        // have to guess that ID, and guessing wrong blocks the very agent the key
        // was vaulted for.
        //
        // Operators who want a narrow grant set it after setup, via the secrets
        // REST API; see docs/secrets-vault.md "Agent Grants".
        secretProvider.store(ref, plaintext, "Auto-vaulted by AgentSetupService for agent: " + agentName, List.of("*"));
        if (secretResolver != null) {
            secretResolver.invalidateCache(ref);
        }
        // Recorded only once the write succeeded: a name whose store threw does not
        // exist, and rollback would log a spurious "could not remove" warning for it.
        if (createdResources != null) {
            createdResources.put(VAULTED_SECRET_KEY, ref.keyName());
        }
        LOGGER.infof("API key vaulted for agent '%s' (key: %s)", LogSanitizer.sanitize(agentName), LogSanitizer.sanitize(ref.keyName()));
        return ref.toReferenceString();
    }

    /**
     * Find a vault entry that already holds {@code plaintext}, so setup references
     * it instead of storing a duplicate.
     * <p>
     * Matching is on the SHA-256 checksum the vault stores alongside every entry —
     * nothing is decrypted, and the digest being compared is one this call computes
     * from a value it was already handed, so the comparison reveals nothing the
     * caller did not already know.
     * <p>
     * Only unrestricted entries qualify. A secret granted to specific agents was
     * scoped deliberately, and handing its reference to a new agent would produce a
     * config that {@code VaultGrantGate} refuses at deploy time — a reuse that
     * "works" until the moment it matters.
     * <p>
     * <b>Convergence is eventual, not immediate.</b> The scan and the write that
     * follows it are separate operations, so N setups running <em>concurrently</em>
     * with the same first-time key can all see nothing to reuse and each store
     * their own entry. Every later setup then finds one of them and reuses it, so
     * the vault stops growing — but a parallel bulk provision can still leave more
     * than one copy. Collapsing those would need a checksum reservation in the
     * persistence layer; sequential provisioning, which is what the wizard and the
     * setup API actually do, converges on the first entry immediately.
     *
     * @return the reference string of the entry to reuse, or null
     */
    private String findReusableSecret(String plaintext) {
        if (!VAULT_KEY_REUSE_CHECKSUM.equalsIgnoreCase(vaultKeyReuse)) {
            return null;
        }
        try {
            String checksum = EnvelopeCrypto.sha256Hex(plaintext);
            return secretProvider.listKeys(SecretReference.DEFAULT_TENANT).stream()
                    .filter(metadata -> checksum.equals(metadata.checksum()))
                    .filter(AgentSetupService::isUnrestricted)
                    // Oldest first, key name as tie-break: repeated setups with the same key
                    // must converge on ONE entry, so the choice cannot depend on listing order.
                    .min(Comparator
                            .<SecretMetadata, Instant>comparing(
                                    metadata -> metadata.createdAt() == null ? Instant.EPOCH : metadata.createdAt())
                            .thenComparing(SecretMetadata::keyName))
                    .map(metadata -> new SecretReference(
                            metadata.tenantId() == null ? SecretReference.DEFAULT_TENANT : metadata.tenantId(), metadata.keyName())
                            .toReferenceString())
                    .orElse(null);
        } catch (Exception e) {
            // Reuse is an optimisation, never a gate: if the vault cannot be listed,
            // fall through and store a new entry rather than failing the setup.
            LOGGER.debugf("Could not scan the vault for a reusable API key: %s", e.getMessage());
            return null;
        }
    }

    /**
     * {@code SetupResult.resources} key under which a non-fatal problem with the
     * vault key the new agent points at is returned to the caller — the key does
     * not exist, or it is granted only to other agents. Both end the same way, in
     * an agent that was created successfully and cannot use its credential, so both
     * belong in the response: {@code resources} is already the channel for
     * {@code deployWarning}/{@code deployError}, and a server-side log line alone
     * does not reach the caller. Only one vault key is resolved per setup, so the
     * two cases cannot collide over this entry.
     */
    static final String VAULT_WARNING = "vaultWarning";

    /**
     * A pass-through {@code apiKey} reference is accepted as-is — that has always
     * been the contract, and callers may legitimately vault the key after setup or
     * hold it under a tenant this check cannot see. But by far the commonest cause
     * of a dangling reference is a typo in a paste, and the result is an agent that
     * deploys fine and fails on its first turn. Say so now, while the caller is
     * still looking. Likewise for a grant the new agent cannot satisfy — see
     * {@link #warnIfRestricted}.
     */
    private void checkReferencedSecret(SecretReference ref, String agentName, Map<String, Object> resources) {
        if (!secretProvider.isAvailable()) {
            return;
        }
        try {
            warnIfRestricted(secretProvider.getMetadata(ref), agentName, resources);
        } catch (ISecretProvider.SecretNotFoundException e) {
            warn(resources, agentName, "Vault key '" + ref.keyName() + "' does not exist. The agent was created, but cannot resolve its "
                    + "API key at runtime until that secret is stored (secrets REST API).");
        } catch (ISecretProvider.SecretProviderException e) {
            LOGGER.debugf("Could not check vault key '%s': %s", LogSanitizer.sanitize(ref.keyName()), e.getMessage());
        }
    }

    /**
     * The checksum path simply skips a narrowly granted entry, but a caller who
     * NAMED one, or pasted its reference, meant it — and a brand-new agent's ID
     * cannot be in any grant list that already exists, so under
     * {@code eddi.vault.grant-enforcement=enforce} the deployment WILL be blocked.
     * That is not a reason to refuse the setup: the legitimate flow is exactly
     * setup without deploy, widen the grant to the new agent ID, then deploy. It is
     * a reason to say so now, in the response as well as the log, instead of
     * letting the caller discover it as {@code deployed: false}.
     */
    private void warnIfRestricted(SecretMetadata metadata, String agentName, Map<String, Object> resources) {
        if (metadata == null || isUnrestricted(metadata)) {
            return;
        }
        warn(resources, agentName, "Vault key '" + metadata.keyName() + "' is granted only to " + metadata.allowedAgents()
                + ". The agent being created cannot be on that list yet, so with eddi.vault.grant-enforcement=enforce its "
                + "deployment will be blocked until the grant is widened to '*' or to the new agent's ID (secrets REST API, "
                + "PATCH allowedAgents).");
    }

    /** Log a non-fatal vault problem and return it to the caller. */
    private void warn(Map<String, Object> resources, String agentName, String warning) {
        LOGGER.warnf("Agent '%s': %s", LogSanitizer.sanitize(agentName), LogSanitizer.sanitize(warning));
        if (resources != null) {
            resources.put(VAULT_WARNING, warning);
        }
    }

    /**
     * Same charset the secrets REST API enforces, so a name created here stays
     * addressable there.
     */
    private static final Pattern VALID_SECRET_NAME = Pattern.compile("[a-zA-Z0-9._-]{1,128}");

    private static void validateSecretName(String value, String label) throws AgentSetupException {
        if (value == null || !VALID_SECRET_NAME.matcher(value).matches()) {
            throw new AgentSetupException(label + " must match [a-zA-Z0-9._-]{1,128}, got: " + value);
        }
    }

    /**
     * The reference, or null when the value is a plaintext key. Guards what reaches
     * {@link SetupResult}: with the vault disabled the "effective api key" IS the
     * caller's secret, and echoing that back in an HTTP response body would put it
     * through every proxy log between here and the client.
     */
    private static String vaultReferenceOrNull(String effectiveApiKey) {
        return effectiveApiKey != null && isVaultReference(effectiveApiKey) ? effectiveApiKey : null;
    }

    /**
     * {@code allowedAgents} unset, empty or {@code ["*"]} — usable by any agent.
     */
    private static boolean isUnrestricted(SecretMetadata metadata) {
        List<String> allowed = metadata.allowedAgents();
        return allowed == null || allowed.isEmpty() || allowed.contains("*");
    }

    /**
     * True when the whole string is a {@code ${vault:...}} reference, not merely
     * contains one — {@code "plaintext${vault:key}"} is not a reference.
     */
    private static boolean isVaultReference(String value) {
        return SecretReference.isVaultReference(value) && SecretReference.compiledPattern().matcher(value).matches();
    }

    /**
     * Build the postResponse that extracts structured data from LLM JSON output.
     */
    public PostResponse buildPostResponse(boolean quickReplies, boolean sentiment) {
        var postResponse = new PostResponse();

        var outputInstruction = new OutputBuildingInstruction();
        outputInstruction.setIterationObjectName("obj");
        outputInstruction.setTemplateFilterExpression("");
        outputInstruction.setOutputType("text");
        outputInstruction.setOutputValue("{aiOutput.htmlResponseText}");
        postResponse.setOutputBuildInstructions(List.of(outputInstruction));

        if (quickReplies) {
            var qrInstruction = new QuickRepliesBuildingInstruction();
            qrInstruction.setPathToTargetArray("aiOutput.quickReplies");
            qrInstruction.setIterationObjectName("quickReply");
            qrInstruction.setTemplateFilterExpression("");
            qrInstruction.setQuickReplyValue("{quickReply}");
            qrInstruction.setQuickReplyExpressions("trigger(quick_reply)");
            postResponse.setQrBuildInstructions(List.of(qrInstruction));
        }

        return postResponse;
    }

    /**
     * Create output set with a CONVERSATION_START intro message.
     */
    public OutputConfigurationSet createOutputConfig(String introMessage) {
        var textItem = new TextOutputItem(introMessage, 0);

        var output = new OutputConfiguration.Output();
        output.setValueAlternatives(List.of(textItem));

        var outputEntry = new OutputConfiguration();
        outputEntry.setAction("CONVERSATION_START");
        outputEntry.setTimesOccurred(0);
        outputEntry.setOutputs(List.of(output));

        var outputSet = new OutputConfigurationSet();
        outputSet.setOutputSet(List.of(outputEntry));
        return outputSet;
    }

    /**
     * Create a minimal McpCallsConfiguration for a single MCP server URL.
     */
    public McpCallsConfiguration createMcpCallsConfig(String mcpServerUrl) {
        var config = new McpCallsConfiguration();
        config.setMcpServerUrl(mcpServerUrl);
        config.setTransport("http");
        config.setTimeoutMs(30000L);
        return config;
    }

    /**
     * Create workflow with parser + behavior + [httpcalls...] + [mcpcalls...] +
     * langchain [+ output].
     */
    public WorkflowConfiguration createWorkflowConfig(String parserLocation, String behaviorLocation, List<String> httpCallsLocations,
                                                      List<String> mcpCallsLocations, String langchainLocation, String outputLocation) {
        var extensions = new ArrayList<WorkflowConfiguration.WorkflowStep>();

        if (parserLocation != null) {
            var parser = new WorkflowConfiguration.WorkflowStep();
            parser.setType(URI.create("eddi://ai.labs.parser"));
            parser.setConfig(Map.of("uri", parserLocation));
            extensions.add(parser);
        }

        var behavior = new WorkflowConfiguration.WorkflowStep();
        behavior.setType(URI.create("eddi://ai.labs.behavior"));
        behavior.setConfig(Map.of("uri", behaviorLocation));
        extensions.add(behavior);

        if (httpCallsLocations != null) {
            for (String httpCallsLocation : httpCallsLocations) {
                var httpCalls = new WorkflowConfiguration.WorkflowStep();
                httpCalls.setType(URI.create("eddi://ai.labs.httpcalls"));
                httpCalls.setConfig(Map.of("uri", httpCallsLocation));
                extensions.add(httpCalls);
            }
        }

        if (mcpCallsLocations != null) {
            for (String mcpCallsLocation : mcpCallsLocations) {
                var mcpCalls = new WorkflowConfiguration.WorkflowStep();
                mcpCalls.setType(URI.create("eddi://ai.labs.mcpcalls"));
                mcpCalls.setConfig(Map.of("uri", mcpCallsLocation));
                extensions.add(mcpCalls);
            }
        }

        var langchain = new WorkflowConfiguration.WorkflowStep();
        langchain.setType(URI.create("eddi://ai.labs.llm"));
        langchain.setConfig(Map.of("uri", langchainLocation));
        extensions.add(langchain);

        if (outputLocation != null) {
            var output = new WorkflowConfiguration.WorkflowStep();
            output.setType(URI.create("eddi://ai.labs.output"));
            output.setConfig(Map.of("uri", outputLocation));
            extensions.add(output);
        }

        var config = new WorkflowConfiguration();
        config.setWorkflowSteps(extensions);
        return config;
    }

    // ==================== Static Utility Methods ====================

    /**
     * Check if the given provider is a local LLM (no API key needed).
     */
    public static boolean isLocalLlmProvider(String provider) {
        if (provider == null || provider.isBlank())
            return false;
        String normalized = provider.trim().toLowerCase();
        // Providers that don't require an apiKey parameter:
        // - ollama/jlama: local inference
        // - bedrock: AWS credential chain (env vars, IAM roles)
        // - oracle-genai: OCI config file (~/.oci/config)
        return "ollama".equals(normalized) || "jlama".equals(normalized) || "bedrock".equals(normalized) || "oracle-genai".equals(normalized);
    }

    /**
     * Build the promptResponseJson format instruction for the LLM. Returns null if
     * neither feature is enabled.
     */
    public static String buildPromptResponseJson(boolean quickReplies, boolean sentiment) {
        if (!quickReplies && !sentiment) {
            return null;
        }

        var schema = new LinkedHashMap<String, Object>();
        schema.put("htmlResponseText", "String - your main reply to the user, optionally formatted with basic inline HTML tags for readability.");

        if (quickReplies) {
            schema.put("quickReplies",
                    List.of("short, button-like suggestions for how the user might want to respond next: "
                            + "Provide 2-4 concise quick reply buttons that are relevant to your latest answer "
                            + "and any recent user input. They should prompt fast responses or encourage deeper "
                            + "exploration (e.g., 'Yes, I agree', 'Tell me more')"));
        }

        if (sentiment) {
            var sentimentObj = new LinkedHashMap<String, Object>();
            sentimentObj.put("score", "Float - range from -1.0 (very negative) to +1.0 (very positive)");
            sentimentObj.put("trend", "String - e.g., 'improved', 'worsened', or 'unchanged'");
            sentimentObj.put("emotions", List.of("String - e.g., 'anger', 'joy', 'frustration', etc."));
            sentimentObj.put("intent", "String - e.g., 'complaint', 'question', 'feedback', 'feature_request'");
            sentimentObj.put("urgency", "String - 'low', 'medium', or 'high'");
            sentimentObj.put("confidence", "Float - 0.0 to 1.0, how confident you are in the sentiment assessment");
            sentimentObj.put("topicTags", List.of("String - e.g., 'billing', 'shipping', 'product_quality', 'account'"));
            sentimentObj.put("userFeedback", "String - direct user feedback if present; otherwise empty");
            schema.put("sentiment", sentimentObj);
        }

        try {
            String jsonSchema = new ObjectMapper().writeValueAsString(schema);
            return "Response with one single valid JSON Object (without wrapping it in "
                    + "any formatting or markdown). Always use the following json structure as response:" + jsonSchema;
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize JSON schema", e);
        }
    }

    // ==================== Private Helpers ====================

    record ResolvedParams(String providerType, String modelId, boolean shouldDeploy, Deployment.Environment env) {
    }

    /**
     * {@link #resolveParams} with the environment rejection mapped onto the
     * service's validation channel, so an unknown environment is reported to the
     * caller as a bad request naming the valid values instead of escaping as an
     * unchecked exception (a 500 / "check server logs").
     */
    ResolvedParams resolveParamsValidated(String provider, String model, Boolean deploy, String environment) throws AgentSetupException {
        try {
            return resolveParams(provider, model, deploy, environment);
        } catch (IllegalArgumentException e) {
            throw new AgentSetupException(e.getMessage(), e);
        }
    }

    /**
     * Resolve the caller-supplied setup parameters, applying defaults.
     * <p>
     * The environment is parsed with {@link Deployment.Environment#parseStrict} —
     * the single strict parser shared with the MCP tools. An unknown value (a typo
     * such as {@code "staging"}) is rejected instead of silently resolving to
     * production, which would create <em>and deploy</em> the agent to the live
     * environment. Callers turn the {@link IllegalArgumentException} into an
     * {@link AgentSetupException}, i.e. a 400 with an actionable message.
     *
     * @throws IllegalArgumentException
     *             if {@code environment} is neither blank nor a known environment
     */
    ResolvedParams resolveParams(String provider, String model, Boolean deploy, String environment) {
        return new ResolvedParams(provider != null && !provider.isBlank() ? provider.trim().toLowerCase() : DEFAULT_PROVIDER,
                model != null && !model.isBlank() ? model.trim() : DEFAULT_MODEL, deploy == null || deploy,
                Deployment.Environment.parseStrict(environment));
    }

    /**
     * Deploy a Agent and wait for completion.
     */
    Map<String, Object> deployAndWait(Deployment.Environment env, String agentId, int agentVersion) {
        var result = new LinkedHashMap<String, Object>();
        result.put("environment", env.name());
        try {
            Response response = agentAdmin.deployAgent(env, agentId, agentVersion, true, true);
            int httpStatus = response.getStatus();

            if (httpStatus == 200) {
                try {
                    @SuppressWarnings("unchecked")
                    var body = (Map<String, Object>) response.getEntity();
                    String deployStatus = body != null && body.containsKey("status") ? body.get("status").toString() : "UNKNOWN";
                    result.put("deployed", "READY".equals(deployStatus));
                    result.put("deploymentStatus", deployStatus);
                    if (body != null && body.containsKey("error")) {
                        result.put("deployError", body.get("error").toString());
                    }
                    if (!"READY".equals(deployStatus)) {
                        String warning = "Agent created but deployment status is " + deployStatus + ". Check Agent configuration and credentials.";
                        if (body != null && body.containsKey("error")) {
                            warning += " Error: " + body.get("error");
                        }
                        result.put("deployWarning", warning);
                    }
                } catch (Exception parseError) {
                    LOGGER.debug("Could not parse deploy response", parseError);
                    result.put("deployed", false);
                    result.put("deploymentStatus", "UNKNOWN");
                    result.put("deployWarning", "Deploy returned 200 but could not parse status.");
                }
            } else if (httpStatus == 202) {
                result.put("deployed", false);
                result.put("deploymentStatus", "IN_PROGRESS");
                result.put("deployWarning", "Deployment accepted but not yet complete.");
            } else {
                result.put("deployed", false);
                result.put("deployError", "Unexpected deploy response: HTTP " + httpStatus);
            }
        } catch (QuotaExceededException quotaError) {
            // agentAdmin is the CDI bean, not an HTTP proxy, so
            // QuotaExceededExceptionMapper
            // never runs here. Surface the reason verbatim — it is actionable ("undeploy an
            // agent first") and the generic branch below would hide it behind "check the
            // logs",
            // leaving an agent designer or a sub-agent-creating model unable to
            // self-correct.
            LOGGER.warn("Deploy denied by quota for Agent " + agentId + ": " + quotaError.getMessage());
            result.put("deployed", false);
            result.put("deployError", quotaError.getMessage());
        } catch (Exception deployError) {
            LOGGER.warn("Deploy failed for Agent " + agentId, deployError);
            result.put("deployed", false);
            result.put("deployError", "Deployment failed. Check server logs for details.");
        }
        return result;
    }

    private void patchDescriptor(String id, int version, String name) {
        if (id == null)
            return;
        try {
            var patchDoc = new DocumentDescriptor();
            patchDoc.setName(name);

            var patch = new PatchInstruction<DocumentDescriptor>();
            patch.setOperation(PatchInstruction.PatchOperation.SET);
            patch.setDocument(patchDoc);
            getRestStore(IRestDocumentDescriptorStore.class).patchDescriptor(id, version, patch);
        } catch (Exception e) {
            LOGGER.warn("patchDescriptor failed for " + id, e);
        }
    }

    private <T> T getRestStore(Class<T> clazz) {
        try {
            return restInterfaceFactory.get(clazz);
        } catch (RestInterfaceFactory.RestInterfaceFactoryException e) {
            throw new RuntimeException("Failed to get REST proxy for " + clazz.getSimpleName(), e);
        }
    }

    static String extractIdFromLocation(String location) {
        if (location == null || location.isBlank())
            return null;
        String path = location.contains("?") ? location.substring(0, location.indexOf('?')) : location;
        int lastSlash = path.lastIndexOf('/');
        return lastSlash >= 0 && lastSlash < path.length() - 1 ? path.substring(lastSlash + 1) : null;
    }

    static int extractVersionFromLocation(String location) {
        if (location == null || !location.contains("version="))
            return 1;
        try {
            int idx = location.indexOf("version=") + "version=".length();
            int end = location.indexOf('&', idx);
            String ver = end > 0 ? location.substring(idx, end) : location.substring(idx);
            return Integer.parseInt(ver.trim());
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    /**
     * Exception thrown when agent setup fails.
     */
    public static class AgentSetupException extends Exception {
        public AgentSetupException(String message) {
            super(message);
        }

        public AgentSetupException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
