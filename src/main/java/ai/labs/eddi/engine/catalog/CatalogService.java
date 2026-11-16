/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.catalog;

import ai.labs.eddi.configs.agents.IAgentStore;
import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.configs.descriptors.model.AccessLevel;
import ai.labs.eddi.configs.descriptors.model.DocumentDescriptor;
import ai.labs.eddi.configs.descriptors.model.ResourceVisibility;
import ai.labs.eddi.configs.groups.IAgentGroupStore;
import ai.labs.eddi.configs.groups.IRestAgentGroupStore;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration;
import ai.labs.eddi.configs.variables.IGlobalVariableStore;
import ai.labs.eddi.configs.variables.model.GlobalVariable;
import ai.labs.eddi.datastore.IResourceStore.IResourceId;
import ai.labs.eddi.datastore.IResourceStore.ResourceNotFoundException;
import ai.labs.eddi.datastore.IResourceStore.ResourceStoreException;
import ai.labs.eddi.engine.api.IRestAgentProfile.RatingProfile;
import ai.labs.eddi.engine.api.OperatorRevisionCheck;
import ai.labs.eddi.engine.catalog.IRestCatalog.CatalogAgent;
import ai.labs.eddi.engine.catalog.IRestCatalog.CatalogGroup;
import ai.labs.eddi.engine.catalog.IRestCatalog.CatalogGroupMember;
import ai.labs.eddi.engine.model.Deployment;
import ai.labs.eddi.engine.ratings.ConversationRatingPolicy;
import ai.labs.eddi.engine.runtime.IAgent;
import ai.labs.eddi.engine.runtime.IAgentFactory;
import ai.labs.eddi.engine.security.ConversationReviewPolicy;
import ai.labs.eddi.engine.security.spaces.AccessScope;
import ai.labs.eddi.engine.security.spaces.ResourceAccessGuard;
import ai.labs.eddi.engine.security.spaces.WorkspaceSettings;
import ai.labs.eddi.utils.RestUtilities;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static ai.labs.eddi.utils.LogSanitizer.sanitize;

/**
 * Decides what the end-user catalog shows. See {@link IRestCatalog} for the
 * contract; the rule is the conjunction of three questions, each answered by
 * code that already exists for another surface:
 * <ol>
 * <li><b>Is it there?</b> An agent is deployed and READY in production — the
 * set {@code GET /v1/models} lists, read from {@link IAgentFactory} for the
 * same reason {@code AgentModelResolver} gives: the deployment REST facade is
 * admin/editor only. A group exists (it has a descriptor).</li>
 * <li><b>May the caller use it?</b> {@link ResourceAccessGuard} at
 * {@link AccessLevel#USE} — the check behind {@code POST /agents/{id}/start}
 * and {@code POST /groups/{id}/conversations}. Nothing new is decided here: an
 * anonymous caller holds no subject, so under enforcement only a
 * {@code published} entry admits them, exactly as on {@code /v1/models}.</li>
 * <li><b>Is it listed?</b> {@code listed: true} on its configuration, or — with
 * workspaces enforced — {@code published} or {@code internal} visibility. The
 * Platform Operator counts as listed only through the explicit flag.</li>
 * </ol>
 * Every per-entry failure drops that entry rather than failing the listing: one
 * unreadable descriptor must not empty everybody's catalog.
 */
@ApplicationScoped
public class CatalogService {

    private static final Logger LOGGER = Logger.getLogger(CatalogService.class);

    /** Descriptors read per page when listing groups. */
    static final int GROUP_PAGE_SIZE = 100;

    /**
     * Pages read at most — a bound on one catalog request, not a product limit: at
     * {@value #GROUP_PAGE_SIZE} per page this is 2,000 group descriptors.
     */
    static final int MAX_GROUP_PAGES = 20;

    private static final String GROUP_DESCRIPTOR_TYPE = RestUtilities.extractDescriptorType(IRestAgentGroupStore.resourceURI);

    private final IAgentFactory agentFactory;
    private final IAgentStore agentStore;
    private final IAgentGroupStore groupStore;
    private final IDocumentDescriptorStore descriptorStore;
    private final ResourceAccessGuard accessGuard;
    private final WorkspaceSettings workspaceSettings;
    private final ConversationReviewPolicy reviewPolicy;
    private final ConversationRatingPolicy ratingPolicy;
    private final IGlobalVariableStore variableStore;
    private final ObjectMapper objectMapper;

    @Inject
    public CatalogService(IAgentFactory agentFactory, IAgentStore agentStore, IAgentGroupStore groupStore,
            IDocumentDescriptorStore descriptorStore, ResourceAccessGuard accessGuard, WorkspaceSettings workspaceSettings,
            ConversationReviewPolicy reviewPolicy, ConversationRatingPolicy ratingPolicy, IGlobalVariableStore variableStore,
            ObjectMapper objectMapper) {
        this.agentFactory = agentFactory;
        this.agentStore = agentStore;
        this.groupStore = groupStore;
        this.descriptorStore = descriptorStore;
        this.accessGuard = accessGuard;
        this.workspaceSettings = workspaceSettings;
        this.reviewPolicy = reviewPolicy;
        this.ratingPolicy = ratingPolicy;
        this.variableStore = variableStore;
        this.objectMapper = objectMapper;
    }

    /** The agents of the catalog, ordered by name. */
    public List<CatalogAgent> listAgents() {
        List<IAgent> deployed;
        try {
            deployed = agentFactory.getAllLatestAgents(Deployment.Environment.production);
        } catch (Exception e) {
            LOGGER.errorf("Could not list deployed agents for the catalog: %s", sanitize(e.getMessage()));
            return List.of();
        }
        if (deployed == null || deployed.isEmpty()) {
            return List.of();
        }

        String operatorAgentId = operatorAgentId();
        Map<String, CatalogAgent> byId = new LinkedHashMap<>();
        for (IAgent agent : deployed) {
            if (agent == null || agent.getDeploymentStatus() != Deployment.Status.READY || byId.containsKey(agent.getAgentId())) {
                continue;
            }
            CatalogAgent entry = toCatalogAgent(agent, operatorAgentId);
            if (entry != null) {
                byId.put(entry.agentId(), entry);
            }
        }
        List<CatalogAgent> agents = new ArrayList<>(byId.values());
        agents.sort(Comparator.comparing(a -> sortKey(a.name(), a.agentId())));
        return agents;
    }

    private CatalogAgent toCatalogAgent(IAgent agent, String operatorAgentId) {
        String agentId = agent.getAgentId();
        Integer version = agent.getAgentVersion();
        try {
            DocumentDescriptor descriptor = currentDescriptor(agentId);
            AccessLevel level = descriptor != null ? accessGuard.effectiveLevel(descriptor) : accessGuard.currentLevel(agentId);
            if (level == null || !level.includes(AccessLevel.USE)) {
                return null;
            }

            AgentConfiguration config = agentStore.read(agentId, version);
            Boolean listedFlag = config == null ? null : config.getListed();
            boolean isOperator = agentId.equals(operatorAgentId);
            if (!isListed(listedFlag, descriptor, isOperator)) {
                return null;
            }

            String notice = reviewPolicy.noticeFor(agentId, version).orElse(null);
            RatingProfile rating = ratingPolicy.ratingConfigOf(agentId, version).map(RatingProfile::of).orElse(null);
            return new CatalogAgent(agentId, descriptor == null ? null : descriptor.getName(),
                    descriptor == null ? null : descriptor.getDescription(), notice, rating, level.name());
        } catch (ResourceStoreException | ResourceNotFoundException | RuntimeException e) {
            LOGGER.debugf("Leaving agent %s out of the catalog: %s", sanitize(agentId), sanitize(e.getMessage()));
            return null;
        }
    }

    /** The groups of the catalog, ordered by name. */
    public List<CatalogGroup> listGroups() {
        Map<String, DocumentDescriptor> latestById = new LinkedHashMap<>();
        Map<String, Integer> versionById = new LinkedHashMap<>();
        try {
            for (int page = 0; page < MAX_GROUP_PAGES; page++) {
                // Unrestricted on purpose, then filtered entry by entry with the USE
                // check: the listing scope answers "what may the caller read", and an
                // `internal` group may be USED by every signed-in caller without being
                // readable by them.
                List<DocumentDescriptor> descriptors = descriptorStore.readDescriptors(GROUP_DESCRIPTOR_TYPE, "", page,
                        GROUP_PAGE_SIZE, false, AccessScope.unrestricted());
                if (descriptors == null || descriptors.isEmpty()) {
                    break;
                }
                for (DocumentDescriptor descriptor : descriptors) {
                    IResourceId resourceId = descriptor == null ? null : RestUtilities.extractResourceId(descriptor.getResource());
                    if (resourceId == null || resourceId.getId() == null) {
                        continue;
                    }
                    int version = resourceId.getVersion() == null ? 1 : resourceId.getVersion();
                    Integer known = versionById.get(resourceId.getId());
                    if (known == null || version > known) {
                        versionById.put(resourceId.getId(), version);
                        latestById.put(resourceId.getId(), descriptor);
                    }
                }
                if (descriptors.size() < GROUP_PAGE_SIZE) {
                    break;
                }
            }
        } catch (ResourceStoreException | ResourceNotFoundException | RuntimeException e) {
            LOGGER.errorf("Could not list groups for the catalog: %s", sanitize(e.getMessage()));
            return List.of();
        }

        List<CatalogGroup> groups = new ArrayList<>();
        for (var entry : latestById.entrySet()) {
            CatalogGroup group = toCatalogGroup(entry.getKey(), versionById.get(entry.getKey()), entry.getValue());
            if (group != null) {
                groups.add(group);
            }
        }
        groups.sort(Comparator.comparing(g -> sortKey(g.name(), g.groupId())));
        return groups;
    }

    private CatalogGroup toCatalogGroup(String groupId, Integer version, DocumentDescriptor descriptor) {
        try {
            AccessLevel level = accessGuard.effectiveLevel(descriptor);
            if (level == null || !level.includes(AccessLevel.USE)) {
                return null;
            }
            AgentGroupConfiguration config = groupStore.read(groupId, version);
            if (config == null || !isListed(config.getListed(), descriptor, false)) {
                return null;
            }
            List<CatalogGroupMember> members = new ArrayList<>();
            if (config.getMembers() != null) {
                for (var member : config.getMembers()) {
                    if (member == null) {
                        continue;
                    }
                    members.add(new CatalogGroupMember(member.agentId(), member.displayName(), member.role(),
                            member.memberType() == null ? null : member.memberType().name(), member.speakingOrder()));
                }
            }
            String name = config.getName() != null && !config.getName().isBlank() ? config.getName() : descriptor.getName();
            String description = config.getDescription() != null && !config.getDescription().isBlank()
                    ? config.getDescription()
                    : descriptor.getDescription();
            return new CatalogGroup(groupId, version, name, description, config.getStyle() == null ? null : config.getStyle().name(),
                    members, level.name());
        } catch (ResourceStoreException | ResourceNotFoundException | RuntimeException e) {
            LOGGER.debugf("Leaving group %s out of the catalog: %s", sanitize(groupId), sanitize(e.getMessage()));
            return null;
        }
    }

    /**
     * Whether an entry is offered at all. The explicit flag always counts; the
     * visibility rule applies only while workspaces are enforced — with them off,
     * visibility is not consulted anywhere else either, and every pre-workspace
     * resource would otherwise list on its stored default — and never for the
     * Platform Operator.
     */
    boolean isListed(Boolean listedFlag, DocumentDescriptor descriptor, boolean isOperator) {
        if (Boolean.TRUE.equals(listedFlag)) {
            return true;
        }
        if (isOperator || !workspaceSettings.isEnforcing() || descriptor == null) {
            return false;
        }
        ResourceVisibility visibility = descriptor.resourceVisibility();
        return visibility == ResourceVisibility.published || visibility == ResourceVisibility.internal;
    }

    private DocumentDescriptor currentDescriptor(String resourceId) throws ResourceStoreException {
        try {
            return descriptorStore.readCurrentDescriptor(resourceId);
        } catch (ResourceNotFoundException e) {
            // No descriptor: the guard's legacy fallback decides access, and nothing is
            // listed by visibility.
            return null;
        }
    }

    /**
     * The Platform Operator's agent id, from the global variable the Manager
     * provisions it into; null when there is none or it cannot be read.
     */
    String operatorAgentId() {
        try {
            GlobalVariable variable = variableStore.get(GlobalVariable.DEFAULT_TENANT, OperatorRevisionCheck.OPERATOR_VARIABLE);
            if (variable == null || variable.value() == null || variable.value().isBlank()) {
                return null;
            }
            JsonNode agentId = objectMapper.readTree(variable.value()).path("agentId");
            return agentId.isTextual() && !agentId.asText().isBlank() ? agentId.asText() : null;
        } catch (Exception e) {
            LOGGER.debugf("Could not read the Platform Operator's agent id for the catalog: %s", sanitize(e.getMessage()));
            return null;
        }
    }

    private static String sortKey(String name, String id) {
        return (name == null || name.isBlank() ? id : name).toLowerCase(Locale.ROOT);
    }
}
