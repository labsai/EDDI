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
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.datastore.IResourceStore.IResourceId;
import ai.labs.eddi.datastore.IResourceStore.ResourceNotFoundException;
import ai.labs.eddi.secrets.model.ReferenceScanner;
import ai.labs.eddi.secrets.model.SecretMetadata;
import ai.labs.eddi.secrets.model.SecretReference;
import ai.labs.eddi.utils.RestUtilities;
import com.fasterxml.jackson.databind.JsonNode;
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
 * not a revocation mechanism. A reference to a secret that does not exist yet
 * is let through — promoting a workflow to an instance that does not hold the
 * source's secret, then creating it, is a supported flow — so the grant such a
 * secret is later created with is checked at <em>creation</em> instead: the
 * create endpoints refuse a new secret whose grant leaves out a deployed agent
 * that already references it (see {@link VaultGrantImpactAnalyzer}).
 */
@ApplicationScoped
public class VaultGrantChecker {

    private static final Logger LOGGER = Logger.getLogger(VaultGrantChecker.class);

    /**
     * Grants every agent access — the default written by the setup wizard. Aliases
     * {@link SecretMetadata#WILDCARD_AGENT} rather than repeating the literal, so
     * there stays exactly one definition of "everyone".
     */
    static final String WILDCARD = SecretMetadata.WILDCARD_AGENT;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // Every finder below is a ReferenceScanner, not a regex: find() over these
    // patterns rescans the rest of the text at each unterminated opener, and the
    // scanned text includes editor-written variable values appended verbatim.

    /**
     * Finds a {@code ${vars:…}} so it can be expanded before the vault scan.
     * {@code CredentialReferenceResolver} resolves global variables FIRST and a
     * variable may expand to a vault reference, so a {@code clientSecret} of
     * {@code ${vars:x}} was invisible to grant enforcement — one indirection
     * defeating the check the reference-only rule exists to feed.
     */
    private static final ReferenceScanner VARS_REFERENCES = ReferenceScanner.of("vars");

    /**
     * A vault reference that names its tenant explicitly. Vault only, and variables
     * are found with their own grammar
     * ({@link GlobalVariableResolver#VARS_PATTERN}): one scanner for both let a
     * malformed <code>${vars:a</code> opener swallow the
     * <code>${vault:t.other…/key}</code> after it into one bogus tenant, while the
     * runtime, which resolves each namespace with its own rules, still resolved the
     * inner reference — and the space-membership check never saw its tenant.
     */
    private static final ReferenceScanner VAULT_TENANT_REFERENCES = ReferenceScanner.withTenant("vault", "eddivault");

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
        } catch (GlobalVariableResolver.AssembledReferenceException e) {
            // A verdict, not an unreadable configuration: the catch-all below would
            // turn it into "no violations" and let the agent deploy.
            return List.of("a reference assembled from global variables (" + e.getMessage() + ")");
        } catch (Exception e) {
            LOGGER.debugf("Could not read agent '%s' v%s for the vault-grant check: %s", sanitize(agentId), agentVersion,
                    sanitize(e.getMessage()));
            return List.of();
        }
    }

    /**
     * Every vault reference in {@code agentConfiguration}'s extension configs that
     * is NOT granted to {@code agentId} — plus every {@code ${connection:…}},
     * workflow, extension config or {@code ${vars:…}} that could not be read.
     * <p>
     * Only a provable violation is reported for a <em>secret</em>. A secret whose
     * metadata cannot be read — vault disabled, secret absent, provider error — is
     * skipped: "could not determine the grant" is not "the grant is missing", and a
     * deployment gate that fires on a transient store failure is worse than the
     * hole it closes.
     * <p>
     * An absent secret has no grant to check yet, and deploying before it exists is
     * a supported flow (agent sync promotes a workflow to an instance that does not
     * hold the source's secrets). The resolver does not check grants, so the grant
     * such a secret is later created with is checked when it is created: the create
     * endpoints ask {@link VaultGrantImpactAnalyzer} which deployed agents already
     * reference it, and refuse a grant that leaves one out.
     * <p>
     * A <em>connection</em>, workflow or extension config that cannot be read is
     * the opposite case and fails closed. The hop exists because a connection is an
     * indirect vault reference; skipping an unreadable one means "whatever secrets
     * it holds are granted", which is exactly the outcome an agent naming somebody
     * else's connection is after. The violation names the connection so the
     * operator knows what to fix.
     *
     * @return the offending references, empty when the agent is fully granted
     */
    public List<String> findUngrantedReferences(AgentConfiguration agentConfiguration, String agentId) {
        if (agentConfiguration == null || agentId == null || !secretProvider.isAvailable()) {
            return List.of();
        }

        List<String> violations = new ArrayList<>();
        List<String> unreadable = new ArrayList<>();
        for (String reference : collectVaultReferences(agentConfiguration, violations, unreadable)) {
            if (!isGranted(reference, agentId)) {
                violations.add(reference);
            }
        }
        // Fail CLOSED on a workflow, extension config or variable that could not be
        // read. These used to be collected and then dropped, so a config the scan
        // could not inspect deployed unchecked, while the runtime, reading the same
        // reference (or holding it in a cache from an earlier build), resolved
        // whatever secrets it names. A config the runtime cannot read either fails
        // the deployment anyway, so refusing here costs nothing in that case.
        for (String resource : unreadable) {
            violations.add(resource + " (could not be read, so the vault references it may hold cannot be verified)");
        }
        return violations;
    }

    /**
     * What {@link #checkReferences} could establish about one agent.
     */
    public enum ReferenceCheck {
        /** The agent's configuration names the secret. */
        REFERENCES,
        /** Every part of the configuration was read, and none names the secret. */
        DOES_NOT_REFERENCE,
        /**
         * Some part of the configuration could not be read — the agent, a workflow, an
         * extension config or a connection — so the answer is not known. Reported
         * separately because "does not reference" is a promise that narrowing the grant
         * breaks nothing, and an unread config cannot back it.
         */
        UNKNOWN
    }

    /**
     * Whether {@code agentId}'s configuration names {@code secret} anywhere the
     * deploy-time check would look.
     *
     * @return true only when the reference was actually found; see
     *         {@link #checkReferences} for the answer that distinguishes "no" from
     *         "could not tell"
     */
    public boolean references(String agentId, Integer agentVersion, SecretReference secret) {
        return checkReferences(agentId, agentVersion, secret) == ReferenceCheck.REFERENCES;
    }

    /**
     * Whether {@code agentId}'s configuration names {@code secret} anywhere the
     * deploy-time check would look — or that this could not be established.
     * <p>
     * Deliberately the inverse question to {@link #findUngrantedReferences}: it
     * asks what an agent <em>uses</em>, with no reference to what it is allowed to
     * use. That is what an operator about to narrow a grant needs — "who would this
     * break?" cannot be answered by the current grant list, because the current
     * grant list is the thing being replaced.
     * <p>
     * Reuses the same traversal, including the {@code ${connection:…}} hop and
     * {@code ${vars:…}} expansion, so an agent that reaches a secret indirectly
     * counts here exactly as it counts at deployment.
     * <p>
     * An unreadable configuration used to answer plain "no", and the impact
     * analysis then reported an agent it had not been able to inspect as unaffected
     * — with {@code complete=true}. It now answers {@link ReferenceCheck#UNKNOWN},
     * unless the part that <em>could</em> be read already names the secret.
     */
    public ReferenceCheck checkReferences(String agentId, Integer agentVersion, SecretReference secret) {
        if (agentId == null || secret == null) {
            return ReferenceCheck.DOES_NOT_REFERENCE;
        }
        AgentConfiguration agentConfiguration;
        try {
            agentConfiguration = agentStore.read(agentId, agentVersion);
        } catch (Exception e) {
            LOGGER.debugf("Could not read agent '%s' v%s while looking for references to %s: %s", sanitize(agentId), agentVersion,
                    sanitize(secret.toReferenceString()), sanitize(e.getMessage()));
            return ReferenceCheck.UNKNOWN;
        }
        if (agentConfiguration == null) {
            return ReferenceCheck.UNKNOWN;
        }
        List<String> unreadable = new ArrayList<>();
        Set<String> found;
        try {
            found = collectVaultReferences(agentConfiguration, unreadable, unreadable);
        } catch (GlobalVariableResolver.AssembledReferenceException e) {
            // The agent assembles a reference from variables, so which secret it
            // reaches depends on their values: the answer is not known.
            return ReferenceCheck.UNKNOWN;
        }
        for (String reference : found) {
            try {
                if (secret.equals(SecretReference.parse(reference))) {
                    return ReferenceCheck.REFERENCES;
                }
            } catch (IllegalArgumentException e) {
                LOGGER.debugf("Ignoring unparseable vault reference %s in agent '%s'", sanitize(reference), sanitize(agentId));
            }
        }
        return unreadable.isEmpty() ? ReferenceCheck.DOES_NOT_REFERENCE : ReferenceCheck.UNKNOWN;
    }

    /**
     * Every tenant the agent's configuration names explicitly in a
     * {@code ${vault:<tenant>/…}} or {@code ${vars:<tenant>/…}} reference —
     * including those that only appear once variables are expanded, twice over,
     * since a variable may hold a reference to another variable or to a secret.
     * <p>
     * Feeds the workspace check that a deployer belongs to every space whose
     * secrets or variables an agent uses. Reuses this class's traversal because it
     * is the one that already reaches the agent document, every workflow step's
     * extension config, and the variable expansion the runtime performs.
     *
     * @return the tenant ids; empty when the configuration cannot be read, which
     *         the deployment itself then fails on
     */
    public Set<String> referencedTenants(String agentId, Integer agentVersion) {
        AgentConfiguration agentConfiguration;
        try {
            agentConfiguration = agentStore.read(agentId, agentVersion);
        } catch (ResourceNotFoundException e) {
            // Not there: nothing to deploy, and the deployment's own existence check
            // answers 404 — refusing here would turn that into a 403.
            return Set.of();
        } catch (Exception e) {
            // A store failure is not "names no tenant": fail closed.
            LOGGER.debugf("Could not read agent '%s' v%s for the space-reference check: %s", sanitize(agentId), agentVersion,
                    sanitize(e.getMessage()));
            throw new UnverifiableReferencesException("agent configuration");
        }
        Set<String> tenants = new LinkedHashSet<>();
        if (agentConfiguration == null) {
            return tenants;
        }
        Set<String> visitedConnections = new LinkedHashSet<>();
        collectTenants(agentConfiguration, tenants, ConnectionReference.DEFAULT_TENANT, visitedConnections);
        if (agentConfiguration.getWorkflows() != null) {
            for (URI workflowUri : agentConfiguration.getWorkflows()) {
                List<String> unreadable = new ArrayList<>();
                WorkflowConfiguration workflow = readWorkflow(workflowUri, unreadable);
                if (!unreadable.isEmpty()) {
                    // Fail closed: "could not read it" is not "it names no tenant", and a
                    // workflow cached from an earlier build can still resolve whatever it
                    // names at runtime.
                    throw new UnverifiableReferencesException("workflow " + workflowUri);
                }
                if (workflow == null || workflow.getWorkflowSteps() == null) {
                    continue;
                }
                for (WorkflowConfiguration.WorkflowStep step : workflow.getWorkflowSteps()) {
                    Object configuredUri = step.getConfig() != null ? step.getConfig().get("uri") : null;
                    if (step.getType() == null || configuredUri == null) {
                        continue;
                    }
                    // readExtensionConfig records a scanned type it could not read; parser,
                    // rules and output steps are not read at all and record nothing.
                    Object extensionConfig = readExtensionConfig(step.getType().toString(), configuredUri.toString(), unreadable);
                    if (!unreadable.isEmpty()) {
                        throw new UnverifiableReferencesException("extension configuration " + configuredUri);
                    }
                    if (extensionConfig != null) {
                        collectTenants(extensionConfig, tenants, ConnectionReference.DEFAULT_TENANT, visitedConnections);
                    }
                }
            }
        }
        return tenants;
    }

    /**
     * Adds every tenant {@code config} names, following {@code ${connection:…}}
     * into the connection document — the same hop the grant check makes. A
     * connection that holds {@code ${vault:t.finance…/key}} is otherwise a way to
     * use another team's secret from an agent that names only the connection.
     *
     * @param tenantId
     *            the tenant a short-form {@code ${vars:key}} resolves in — the
     *            connection's own for a connection document
     * @param visitedConnections
     *            each connection is scanned once, which also ends a cycle
     */
    private void collectTenants(Object config, Set<String> sink, String tenantId, Set<String> visitedConnections) {
        List<String> unresolved = new ArrayList<>();
        List<String> scanned = new ArrayList<>();
        for (String value : stringValues(config)) {
            // Twice over: a variable may hold a reference to another variable.
            for (String expanded : withVariablesExpanded(value, tenantId, unresolved)) {
                scanned.addAll(withVariablesExpanded(expanded, tenantId, unresolved));
            }
        }
        if (!unresolved.isEmpty()) {
            // A variable that could not be read may hold a reference to any tenant.
            throw new UnverifiableReferencesException("variable " + unresolved.get(0));
        }
        List<ReferenceScanner.Match> connectionMatches = new ArrayList<>();
        for (String text : scanned) {
            for (ReferenceScanner.Match match : VAULT_TENANT_REFERENCES.findAll(text)) {
                sink.add(match.tenant());
            }
            // The resolver's own pattern: its classes exclude '$', '{' and '}', so an
            // opener inside a malformed one is still found, as the runtime finds it.
            Matcher variables = GlobalVariableResolver.VARS_PATTERN.matcher(text);
            while (variables.find()) {
                if (variables.group(1) != null) {
                    sink.add(variables.group(1));
                }
            }
            connectionMatches.addAll(ConnectionReference.findAll(text));
        }
        for (ReferenceScanner.Match connections : connectionMatches) {
            ConnectionReference reference = ConnectionReference.of(connections);
            String connectionTenant = reference.tenantId();
            String name = reference.name();
            if (!visitedConnections.add(connectionTenant + "/" + name)) {
                continue;
            }
            try {
                ConnectionConfiguration connection = connectionStore.readByName(connectionTenant, name);
                if (connection != null) {
                    collectTenants(connection, sink, ConnectionConfiguration.effectiveTenant(connection), visitedConnections);
                }
            } catch (Exception e) {
                // Fail closed: a connection cached from an earlier read can still resolve
                // whatever it names at runtime. An absent one (null above) is fine — the
                // runtime refuses it as NOT_FOUND.
                LOGGER.debugf("Could not read connection '%s' for the space-reference check: %s", sanitize(name), sanitize(e.getMessage()));
                throw new UnverifiableReferencesException("connection " + connectionTenant + "/" + name);
            }
        }
    }

    /**
     * The grant metadata of {@code secret}, or {@code null} when the vault is off
     * or the metadata cannot be read. Never a value: {@link SecretMetadata} has no
     * field for one.
     */
    public SecretMetadata metadataOf(SecretReference secret) {
        if (secret == null || !secretProvider.isAvailable()) {
            return null;
        }
        try {
            return secretProvider.getMetadata(secret);
        } catch (Exception e) {
            LOGGER.debugf("Could not read grant metadata for %s (%s)", sanitize(secret.toReferenceString()), e.getClass().getSimpleName());
            return null;
        }
    }

    /**
     * Whether {@code agentId} may use {@code reference}. Absent, empty and wildcard
     * grant lists all allow — those are the shapes that mean "not restricted", and
     * the wizard writes the wildcard for every key it vaults. A secret that does
     * not exist, or whose grant cannot be read, is not a violation (see
     * {@link #findUngrantedReferences(AgentConfiguration, String)}).
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
     * Every string value of each extension config is scanned rather than having its
     * secret-bearing fields enumerated. Enumeration is how this kind of check rots:
     * a new credential field is added somewhere and the scanner silently stops
     * covering it.
     */
    private Set<String> collectVaultReferences(AgentConfiguration agentConfiguration, List<String> unreadableConnections,
                                               List<String> unreadableResources) {
        Set<String> references = new LinkedHashSet<>();

        // The agent document FIRST. AgentConfiguration.DreamConfig.parameters
        // explicitly supports vault references and is handed to ChatModelRegistry by
        // DreamService, so a Dream credential lives outside every workflow resource
        // and was invisible to a workflow-only traversal.
        scanForVaultReferences(agentConfiguration, references, ConnectionReference.DEFAULT_TENANT, unreadableResources);

        if (agentConfiguration.getWorkflows() == null) {
            return references;
        }

        for (URI workflowUri : agentConfiguration.getWorkflows()) {
            WorkflowConfiguration workflow = readWorkflow(workflowUri, unreadableResources);
            if (workflow == null || workflow.getWorkflowSteps() == null) {
                continue;
            }
            for (WorkflowConfiguration.WorkflowStep step : workflow.getWorkflowSteps()) {
                Object configuredUri = step.getConfig() != null ? step.getConfig().get("uri") : null;
                if (step.getType() == null || configuredUri == null) {
                    continue;
                }
                Object extensionConfig = readExtensionConfig(step.getType().toString(), configuredUri.toString(), unreadableResources);
                if (extensionConfig != null) {
                    scanForVaultReferences(extensionConfig, references, ConnectionReference.DEFAULT_TENANT, unreadableResources);
                    // A ${connection:name} is an INDIRECT vault reference: the
                    // connection document holds the ${vault:…} client secret, and
                    // without following the hop an agent could use a credential it was
                    // never granted simply by naming a connection somebody else made.
                    scanReferencedConnections(extensionConfig, references, unreadableConnections, unreadableResources);
                }
            }
        }
        return references;
    }

    private WorkflowConfiguration readWorkflow(URI workflowUri, List<String> unreadableResources) {
        try {
            IResourceId id = RestUtilities.extractResourceId(workflowUri);
            WorkflowConfiguration workflow = id == null ? null : workflowStore.read(id.getId(), id.getVersion());
            if (workflow == null) {
                // A store answers absence with ResourceNotFoundException, so null is not
                // a confirmed absence: the workflow was not inspected.
                unreadableResources.add(String.valueOf(workflowUri));
            }
            return workflow;
        } catch (Exception e) {
            LOGGER.debugf("Could not read workflow %s while checking vault grants: %s", sanitize(String.valueOf(workflowUri)),
                    sanitize(e.getMessage()));
            unreadableResources.add(String.valueOf(workflowUri));
            return null;
        }
    }

    /**
     * A resource the space-reference check needed to read could not be read, so
     * which tenants the agent names is unknown. The deployment is refused rather
     * than let through.
     */
    public static final class UnverifiableReferencesException extends IllegalStateException {
        public UnverifiableReferencesException(String what) {
            super("Could not read the " + what + " to check which spaces' secrets and variables it uses");
        }
    }

    /**
     * The extension config behind a workflow step, or {@code null}.
     * <p>
     * The URI is normalised exactly as the runtime does it
     * ({@code ResourceUris.require} trims it), and the store is chosen by the
     * config URI's host, the key {@code ResourceClientLibrary.getResource} reads
     * with. Parsing the raw value here while the runtime trimmed it meant a
     * {@code " eddi://ai.labs.llm/…"} failed to parse, was dropped as unreadable
     * and deployed unchecked, then loaded fine at runtime.
     */
    private Object readExtensionConfig(String stepType, String configUri, List<String> unreadableResources) {
        URI uri;
        try {
            uri = URI.create(configUri.trim());
        } catch (IllegalArgumentException e) {
            // Not knowable which store it names. The runtime refuses the same value,
            // so failing closed here refuses nothing that would have deployed.
            LOGGER.debugf("Unparseable %s config URI %s while checking vault grants", sanitize(stepType), sanitize(configUri));
            unreadableResources.add(configUri);
            return null;
        }
        IResourceStore<?> store = storeFor(uri.getHost());
        if (store == null) {
            // Not a config type that can carry a vault reference.
            return null;
        }
        try {
            IResourceId id = RestUtilities.extractResourceId(uri);
            Object config = id == null ? null : store.read(id.getId(), id.getVersion());
            if (config == null) {
                // A store answers absence with ResourceNotFoundException, so null is not
                // a confirmed absence: the config was not inspected.
                unreadableResources.add(configUri);
            }
            return config;
        } catch (Exception e) {
            LOGGER.debugf("Could not read %s config %s while checking vault grants: %s", sanitize(stepType), sanitize(configUri),
                    sanitize(e.getMessage()));
            unreadableResources.add(configUri);
            return null;
        }
    }

    /**
     * The store a config URI with this host is read from, or {@code null} for a
     * config type not scanned.
     * <p>
     * An exact match on the host, mirroring the keys of
     * {@code ResourceClientLibrary}'s store map, which is what decides at runtime
     * which document a step loads. It used to be {@code contains()} on the step
     * <em>type</em>: the {@code ai.labs.langchain} alias (an LLM step) matched
     * nothing and went unscanned, and a type such as
     * {@code eddi://ai.labs.mcpcalls?x=ai.labs.llm} matched the wrong branch, read
     * the wrong store, failed and was dropped. The step type no longer matters:
     * whichever task runs, the config it loads is the one this URI names.
     */
    private IResourceStore<?> storeFor(String configHost) {
        if (configHost == null) {
            return null;
        }
        return switch (configHost) {
            case "ai.labs.llm" -> llmStore;
            case "ai.labs.httpcalls", "ai.labs.apicalls" -> apiCallsStore;
            case "ai.labs.mcpcalls" -> mcpCallsStore;
            // RagConfiguration carries embedding-model and vector-store credentials,
            // both resolved through SecretResolver at runtime.
            case "ai.labs.rag" -> ragStore;
            default -> null;
        };
    }

    /**
     * Follows every {@code ${connection:name}} in a config and scans the connection
     * document it names.
     * <p>
     * Every string value is scanned on both hops, deliberately: enumerating which
     * fields may carry a connection reference is exactly the kind of check that
     * rots when a new field is added, and the traversal above already made that
     * argument for vault references.
     */
    private void scanReferencedConnections(Object config, Set<String> sink, List<String> unreadableConnections,
                                           List<String> unreadableResources) {
        // Variables expanded first, as for the vault scan: a ${vars:x} whose value is
        // ${connection:jira} otherwise hides the whole hop, and with it every secret
        // the connection holds.
        List<ReferenceScanner.Match> matches = new ArrayList<>();
        for (String value : stringValues(config)) {
            for (String text : withVariablesExpanded(value, ConnectionReference.DEFAULT_TENANT, unreadableResources)) {
                matches.addAll(ConnectionReference.findAll(text));
            }
        }
        Set<String> alreadyScanned = new LinkedHashSet<>();
        for (ReferenceScanner.Match match : matches) {
            ConnectionReference reference = ConnectionReference.of(match);
            String tenantId = reference.tenantId();
            String name = reference.name();
            if (!alreadyScanned.add(tenantId + "/" + name)) {
                continue;
            }
            try {
                ConnectionConfiguration connection = connectionStore.readByName(tenantId, name);
                if (connection != null) {
                    // In the connection's own tenant: a short-form ${vars:x} in a connection
                    // filed under "acme" resolves against acme's variables at runtime, and
                    // the same name in the default tenant can hold something else entirely.
                    scanForVaultReferences(connection, sink, ConnectionConfiguration.effectiveTenant(connection), unreadableResources);
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
                unreadableConnections.add(match.text() + " (connection '" + name + "' could not be read: " + e.getClass().getSimpleName()
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
     * @param unresolved
     *            receives every {@code ${vars:…}} that could not be expanded
     */
    private void scanForVaultReferences(Object config, Set<String> sink, String tenantId, List<String> unresolved) {
        for (String value : stringValues(config)) {
            for (String text : withVariablesExpanded(value, tenantId, unresolved)) {
                for (ReferenceScanner.Match match : SecretReference.scanner().findAll(text)) {
                    sink.add(match.text());
                }
            }
        }
    }

    /**
     * Every string value in {@code config}, at any depth, each to be scanned on its
     * own.
     * <p>
     * Not the serialized document as one string. The runtime resolves each
     * parameter value separately, so an unterminated <code>${vault:x</code> in one
     * field must not be able to swallow a real reference in a later field: scanning
     * the whole document let that opener run on to the next field's closing brace,
     * producing one composite match no grant lookup could resolve (which counts as
     * granted) while the runtime resolved the later reference unchecked.
     */
    private static List<String> stringValues(Object config) {
        List<String> values = new ArrayList<>();
        JsonNode tree;
        try {
            tree = MAPPER.valueToTree(config);
        } catch (IllegalArgumentException e) {
            return values;
        }
        collectStrings(tree, values);
        return values;
    }

    private static void collectStrings(JsonNode node, List<String> sink) {
        if (node == null) {
            return;
        }
        if (node.isTextual()) {
            sink.add(node.textValue());
        } else if (node.isContainerNode()) {
            for (JsonNode child : node) {
                collectStrings(child, sink);
            }
        }
    }

    /**
     * The value followed by the expansion of every {@code ${vars:…}} in it, so a
     * scan sees both what is written and what the runtime resolves it to. Each is a
     * separate entry, scanned on its own, for the reason {@link #stringValues}
     * gives. One helper for the vault scan and the connection scan: expanding for
     * only one of them is how a variable came to hide a connection hop.
     * <p>
     * The value is also resolved in place, the way the runtime does it. Each
     * expansion on its own line could not show a reference split across two
     * variables; resolving in place refuses one
     * ({@link GlobalVariableResolver.AssembledReferenceException}), so such an
     * agent fails to deploy instead of failing its first turn. That exception is
     * let through deliberately — it is a verdict, not an unreadable variable.
     *
     * @param tenantId
     *            the tenant a short-form {@code ${vars:key}} resolves in
     * @param unresolved
     *            receives every {@code ${vars:…}} that could not be expanded: its
     *            value was not scanned, so a "does not reference" answer that
     *            ignored it would be a guess
     */
    private List<String> withVariablesExpanded(String value, String tenantId, List<String> unresolved) {
        if (globalVariableResolver == null || value == null || !value.contains("${")) {
            return value == null ? List.of() : List.of(value);
        }
        List<String> scanned = new ArrayList<>();
        scanned.add(value);
        String inPlace = globalVariableResolver.resolveValue(value, tenantId);
        if (inPlace != null && !inPlace.equals(value)) {
            scanned.add(inPlace);
        }
        for (ReferenceScanner.Match vars : VARS_REFERENCES.findAll(value)) {
            String variable = vars.text();
            String expanded;
            try {
                expanded = globalVariableResolver.resolveValue(variable, tenantId);
            } catch (Exception e) {
                LOGGER.debugf("Could not expand %s while checking vault grants: %s", sanitize(variable), sanitize(e.getMessage()));
                unresolved.add(variable);
                continue;
            }
            if (expanded != null && !expanded.equals(variable)) {
                scanned.add(expanded);
            }
        }
        return scanned;
    }
}
