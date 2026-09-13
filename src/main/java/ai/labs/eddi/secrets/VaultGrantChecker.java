/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.secrets;

import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.configs.agents.IAgentStore;
import ai.labs.eddi.configs.apicalls.IApiCallsStore;
import ai.labs.eddi.configs.llm.ILlmStore;
import ai.labs.eddi.configs.connections.IConnectionStore;
import ai.labs.eddi.configs.connections.model.ConnectionConfiguration;
import ai.labs.eddi.configs.mcpcalls.IMcpCallsStore;
import ai.labs.eddi.connections.model.ConnectionReference;
import ai.labs.eddi.configs.rag.IRagStore;
import ai.labs.eddi.configs.variables.GlobalVariableResolver;
import ai.labs.eddi.configs.workflows.IWorkflowStore;
import ai.labs.eddi.configs.workflows.model.WorkflowConfiguration;
import ai.labs.eddi.datastore.IResourceStore.IResourceId;
import ai.labs.eddi.secrets.model.SecretMetadata;
import ai.labs.eddi.secrets.model.SecretReference;
import ai.labs.eddi.utils.RestUtilities;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import static ai.labs.eddi.utils.LogSanitizer.sanitize;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Checks, at deployment time, that every vault reference an agent's
 * configuration names is actually granted to that agent.
 * <p>
 * <b>Why this exists.</b> {@code SecretMetadata.allowedAgents} was documented
 * as "for visibility only — enforcement is via configuration authorship, not
 * runtime resolution". That access model rests on an assumption that no longer
 * holds everywhere: it assumes a <em>human admin</em> authors agent
 * configurations. {@code create_sub_agent} lets an LLM author one. An operator
 * who scopes a secret to one agent therefore got no enforcement at all — the
 * field was decorative.
 * <p>
 * <b>Why deployment time, and not resolution time.</b> Resolution is the
 * tempting place and the wrong one:
 * <ul>
 * <li>{@link SecretResolver} sees only a string. It has no agent identity, and
 * its ~12 call sites include several that legitimately run outside any
 * conversation (embedding-store construction, channel routing, agent
 * signing).</li>
 * <li>{@code ChatModelRegistry} caches the built model keyed on the
 * <em>unresolved</em> parameters, so two agents sharing a config share a cache
 * entry. A check behind that cache runs for whichever agent built the model
 * first and is silently skipped for every other one — enforcement that looks
 * real and is not.</li>
 * </ul>
 * The binding between an agent and a secret is established in the agent's
 * <em>configuration</em>, so that is where it can be checked completely, once,
 * and without a cache in the way.
 * <p>
 * <b>What it cannot do.</b> An agent already deployed before its grant was
 * narrowed keeps resolving until it is redeployed. This is a deploy-time gate,
 * not a revocation mechanism.
 */
@ApplicationScoped
public class VaultGrantChecker {

    private static final Logger LOGGER = Logger.getLogger(VaultGrantChecker.class);

    /** Grants every agent access — the default written by the setup wizard. */
    static final String WILDCARD = "*";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Finds a ${connection:…} so the connection's own vault reference can be
     * followed.
     */
    private static final Pattern CONNECTION_PATTERN = Pattern.compile(ConnectionReference.CONNECTION_PATTERN);

    /**
     * Finds a {@code ${vars:…}} so it can be expanded before the vault scan.
     * {@code CredentialReferenceResolver} resolves global variables FIRST and a
     * variable may expand to a vault reference, so a {@code clientSecret} of
     * {@code ${vars:x}} was invisible to grant enforcement — one indirection
     * defeating the check the reference-only rule exists to feed.
     */
    private static final Pattern VARS_PATTERN = Pattern.compile("\\$\\{vars:[^}]+\\}");

    private final ISecretProvider secretProvider;
    private final IAgentStore agentStore;
    private final IWorkflowStore workflowStore;
    private final ILlmStore llmStore;
    private final IApiCallsStore apiCallsStore;
    private final IMcpCallsStore mcpCallsStore;
    private final IRagStore ragStore;
    private final IConnectionStore connectionStore;
    private final GlobalVariableResolver globalVariableResolver;

    @Inject
    public VaultGrantChecker(ISecretProvider secretProvider, IAgentStore agentStore, IWorkflowStore workflowStore, ILlmStore llmStore,
            IApiCallsStore apiCallsStore, IMcpCallsStore mcpCallsStore, IRagStore ragStore, IConnectionStore connectionStore,
            GlobalVariableResolver globalVariableResolver) {
        this.secretProvider = secretProvider;
        this.agentStore = agentStore;
        this.workflowStore = workflowStore;
        this.llmStore = llmStore;
        this.apiCallsStore = apiCallsStore;
        this.mcpCallsStore = mcpCallsStore;
        this.ragStore = ragStore;
        this.connectionStore = connectionStore;
        this.globalVariableResolver = globalVariableResolver;
    }

    /**
     * Convenience overload that reads the agent configuration itself, so callers on
     * the deployment path need only the id and version. A configuration that cannot
     * be read yields no violations — see the class contract on uncertainty.
     */
    public List<String> findUngrantedReferences(String agentId, Integer agentVersion) {
        try {
            return findUngrantedReferences(agentStore.read(agentId, agentVersion), agentId);
        } catch (Exception e) {
            LOGGER.debugf("Could not read agent '%s' v%s for the vault-grant check: %s", sanitize(agentId), agentVersion,
                    sanitize(e.getMessage()));
            return List.of();
        }
    }

    /**
     * Every vault reference in {@code agentConfiguration}'s extension configs that
     * is NOT granted to {@code agentId} — plus every {@code ${connection:…}} whose
     * document could not be read.
     * <p>
     * Only a provable violation is reported for a <em>secret</em>. A secret whose
     * metadata cannot be read — vault disabled, secret absent, provider error — is
     * skipped: "could not determine the grant" is not "the grant is missing", and a
     * deployment gate that fires on a transient store failure is worse than the
     * hole it closes.
     * <p>
     * A <em>connection</em> that cannot be read is the opposite case and fails
     * closed. The hop exists because a connection is an indirect vault reference;
     * skipping an unreadable one means "whatever secrets it holds are granted",
     * which is exactly the outcome an agent naming somebody else's connection is
     * after. The violation names the connection so the operator knows what to fix.
     *
     * @return the offending references, empty when the agent is fully granted
     */
    public List<String> findUngrantedReferences(AgentConfiguration agentConfiguration, String agentId) {
        if (agentConfiguration == null || agentId == null || !secretProvider.isAvailable()) {
            return List.of();
        }

        List<String> violations = new ArrayList<>();
        for (String reference : collectVaultReferences(agentConfiguration, violations)) {
            if (!isGranted(reference, agentId)) {
                violations.add(reference);
            }
        }
        return violations;
    }

    /**
     * Whether {@code agentId} may use {@code reference}. Absent, empty and wildcard
     * grant lists all allow — those are the shapes that mean "not restricted", and
     * the wizard writes the wildcard for every key it vaults.
     */
    private boolean isGranted(String reference, String agentId) {
        SecretMetadata metadata;
        try {
            metadata = secretProvider.getMetadata(SecretReference.parse(reference));
        } catch (Exception e) {
            LOGGER.debugf("Could not read grant metadata for %s (%s) — not treating it as a violation",
                    sanitize(reference), e.getClass().getSimpleName());
            return true;
        }
        if (metadata == null || metadata.allowedAgents() == null || metadata.allowedAgents().isEmpty()) {
            return true;
        }
        return metadata.allowedAgents().stream()
                .anyMatch(granted -> WILDCARD.equals(granted) || (granted != null && granted.equals(agentId)));
    }

    /**
     * Every distinct {@code ${vault:...}} reference reachable from the agent's
     * workflows.
     * <p>
     * Each extension config is serialized and scanned as a whole rather than having
     * its secret-bearing fields enumerated. Enumeration is how this kind of check
     * rots: a new credential field is added somewhere and the scanner silently
     * stops covering it.
     */
    private Set<String> collectVaultReferences(AgentConfiguration agentConfiguration, List<String> unreadableConnections) {
        Set<String> references = new LinkedHashSet<>();

        // The agent document FIRST. AgentConfiguration.DreamConfig.parameters
        // explicitly supports vault references and is handed to ChatModelRegistry by
        // DreamService, so a Dream credential lives outside every workflow resource
        // and was invisible to a workflow-only traversal.
        scanForVaultReferences(agentConfiguration, references, ConnectionReference.DEFAULT_TENANT);

        if (agentConfiguration.getWorkflows() == null) {
            return references;
        }

        for (URI workflowUri : agentConfiguration.getWorkflows()) {
            WorkflowConfiguration workflow = readWorkflow(workflowUri);
            if (workflow == null || workflow.getWorkflowSteps() == null) {
                continue;
            }
            for (WorkflowConfiguration.WorkflowStep step : workflow.getWorkflowSteps()) {
                Object configuredUri = step.getConfig() != null ? step.getConfig().get("uri") : null;
                if (step.getType() == null || configuredUri == null) {
                    continue;
                }
                Object extensionConfig = readExtensionConfig(step.getType().toString(), configuredUri.toString());
                if (extensionConfig != null) {
                    scanForVaultReferences(extensionConfig, references, ConnectionReference.DEFAULT_TENANT);
                    // A ${connection:name} is an INDIRECT vault reference: the
                    // connection document holds the ${vault:…} client secret, and
                    // without following the hop an agent could use a credential it was
                    // never granted simply by naming a connection somebody else made.
                    scanReferencedConnections(extensionConfig, references, unreadableConnections);
                }
            }
        }
        return references;
    }

    private WorkflowConfiguration readWorkflow(URI workflowUri) {
        try {
            IResourceId id = RestUtilities.extractResourceId(workflowUri);
            return id == null ? null : workflowStore.read(id.getId(), id.getVersion());
        } catch (Exception e) {
            LOGGER.debugf("Could not read workflow %s while checking vault grants: %s", sanitize(String.valueOf(workflowUri)),
                    sanitize(e.getMessage()));
            return null;
        }
    }

    /** The extension config behind a workflow step, or {@code null}. */
    private Object readExtensionConfig(String stepType, String configUri) {
        try {
            IResourceId id = RestUtilities.extractResourceId(URI.create(configUri));
            if (id == null) {
                return null;
            }
            if (stepType.contains("ai.labs.llm")) {
                return llmStore.read(id.getId(), id.getVersion());
            }
            if (stepType.contains("ai.labs.httpcalls") || stepType.contains("ai.labs.apicalls")) {
                return apiCallsStore.read(id.getId(), id.getVersion());
            }
            if (stepType.contains("ai.labs.mcpcalls")) {
                return mcpCallsStore.read(id.getId(), id.getVersion());
            }
            if (stepType.contains("ai.labs.rag")) {
                // RagConfiguration carries embedding-model and vector-store
                // credentials, both resolved through SecretResolver at runtime.
                return ragStore.read(id.getId(), id.getVersion());
            }
        } catch (Exception e) {
            LOGGER.debugf("Could not read %s config %s while checking vault grants: %s", sanitize(stepType), sanitize(configUri),
                    sanitize(e.getMessage()));
        }
        return null;
    }

    /**
     * Follows every {@code ${connection:name}} in a config and scans the connection
     * document it names.
     * <p>
     * Serialize-and-scan on both hops, deliberately: enumerating which fields may
     * carry a connection reference is exactly the kind of check that rots when a
     * new field is added, and the traversal above already made that argument for
     * vault references.
     */
    private void scanReferencedConnections(Object config, Set<String> sink, List<String> unreadableConnections) {
        String serialized;
        try {
            serialized = MAPPER.writeValueAsString(config);
        } catch (Exception e) {
            return;
        }
        Matcher matcher = CONNECTION_PATTERN.matcher(serialized);
        Set<String> alreadyScanned = new LinkedHashSet<>();
        while (matcher.find()) {
            String tenantId = matcher.group(1) != null ? matcher.group(1) : ConnectionReference.DEFAULT_TENANT;
            String name = matcher.group(2);
            if (!alreadyScanned.add(tenantId + "/" + name)) {
                continue;
            }
            try {
                ConnectionConfiguration connection = connectionStore.readByName(tenantId, name);
                if (connection != null) {
                    // In the connection's own tenant: a short-form ${vars:x} in a connection
                    // filed under "acme" resolves against acme's variables at runtime, and
                    // the same name in the default tenant can hold something else entirely.
                    scanForVaultReferences(connection, sink, ConnectionConfiguration.effectiveTenant(connection));
                } else {
                    // Absent, not unreadable: there is no document, so there is no
                    // secret to be ungranted for. The runtime refuses it as NOT_FOUND.
                    LOGGER.debugf("Connection %s referenced by the agent does not exist; nothing to check", sanitize(name));
                }
            } catch (Exception e) {
                // Fail CLOSED. Continuing at DEBUG meant an unreadable connection's
                // secrets counted as granted — the one outcome this hop exists to
                // prevent — and the deployment went through with nothing in the log
                // above DEBUG.
                LOGGER.warnf("Could not read connection '%s' while checking vault grants (%s); its vault references cannot be verified, "
                        + "so the deployment is refused", sanitize(name), e.getClass().getSimpleName());
                unreadableConnections.add(matcher.group(0) + " (connection '" + name + "' could not be read: " + e.getClass().getSimpleName()
                        + ", so its vault references cannot be verified)");
            }
        }
    }

    /**
     * Scans a serialized config for {@code ${vault:…}}, expanding every
     * {@code ${vars:…}} first and scanning the expansion too — a global variable
     * may hold a vault reference, and the runtime resolves variables before vault
     * references, so the expanded form is what actually reaches the vault.
     *
     * @param tenantId
     *            the tenant a short-form {@code ${vars:key}} resolves in — the
     *            connection's own for a connection document, the default for
     *            everything else
     */
    private void scanForVaultReferences(Object config, Set<String> sink, String tenantId) {
        String serialized;
        try {
            serialized = MAPPER.writeValueAsString(config);
        } catch (Exception e) {
            return;
        }
        Matcher matcher = SecretReference.compiledPattern().matcher(serialized);
        while (matcher.find()) {
            sink.add(matcher.group(0));
        }
        if (globalVariableResolver == null) {
            return;
        }
        Matcher vars = VARS_PATTERN.matcher(serialized);
        while (vars.find()) {
            String expanded;
            try {
                expanded = globalVariableResolver.resolveValue(vars.group(0), tenantId);
            } catch (Exception e) {
                LOGGER.debugf("Could not expand %s while checking vault grants: %s", sanitize(vars.group(0)), sanitize(e.getMessage()));
                continue;
            }
            if (expanded == null || expanded.equals(vars.group(0))) {
                continue;
            }
            Matcher inExpansion = SecretReference.compiledPattern().matcher(expanded);
            while (inExpansion.find()) {
                sink.add(inExpansion.group(0));
            }
        }
    }
}
