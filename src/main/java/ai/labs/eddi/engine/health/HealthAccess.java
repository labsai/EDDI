/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.health;

import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.configs.descriptors.model.AccessLevel;
import ai.labs.eddi.configs.descriptors.model.DocumentDescriptor;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.engine.exception.ErrorResponses;
import ai.labs.eddi.engine.health.HealthPortfolioService.AgentRef;
import ai.labs.eddi.engine.health.model.HealthMarker;
import ai.labs.eddi.engine.security.spaces.ResourceAccessGuard;
import ai.labs.eddi.engine.security.spaces.SpaceTenants;
import ai.labs.eddi.utils.RestUtilities;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.InternalServerErrorException;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.ArrayList;
import java.util.List;

/**
 * Who may read which part of Agent Health — the rules of plan §6.4, in one
 * place for REST and MCP:
 * <ul>
 * <li>the whole view needs {@code VIEW} on the agent (like {@code GET
 * /agents/{agentId}/usage}) — else 403; an unknown agent is 404;</li>
 * <li>the quality section (ratings) follows the ratings rule exactly: role
 * {@code eddi-admin} or {@code eddi-editor} (viewers are excluded), and
 * {@code EDIT} on the agent unless the caller sees every agent. A caller who
 * fails it gets the section {@code restricted}, not a 403;</li>
 * <li>the portfolio lists only agents the caller may {@code VIEW}.</li>
 * </ul>
 * With {@code authorization.enabled=false} the role part admits everybody, as
 * {@code @RolesAllowed} does.
 */
@ApplicationScoped
public class HealthAccess {

    static final String AGENT_DESCRIPTOR_TYPE = "ai.labs.agent";
    static final String RATING_ADMIN = "eddi-admin";
    static final String RATING_EDITOR = "eddi-editor";
    /** Descriptors read per page while listing the portfolio. */
    static final int PAGE = 200;
    /** The most agents a portfolio looks at. */
    public static final int MAX_AGENTS = 5_000;

    private final ResourceAccessGuard accessGuard;
    private final IDocumentDescriptorStore descriptorStore;
    private final SecurityIdentity identity;
    private final boolean authEnabled;

    @Inject
    public HealthAccess(ResourceAccessGuard accessGuard, IDocumentDescriptorStore descriptorStore, SecurityIdentity identity,
            @ConfigProperty(name = "authorization.enabled", defaultValue = "false") boolean authEnabled) {
        this.accessGuard = accessGuard;
        this.descriptorStore = descriptorStore;
        this.identity = identity;
        this.authEnabled = authEnabled;
    }

    /**
     * Requires {@code VIEW} on the agent and answers its name; 403 without access,
     * 404 for an agent that does not exist.
     */
    public String requireView(String agentId) {
        if (agentId == null || agentId.isBlank()) {
            throw new NotFoundException(notFound("Agent not found"));
        }
        accessGuard.requireAccess(agentId, AccessLevel.VIEW, "agent");
        try {
            DocumentDescriptor descriptor = descriptorStore.readCurrentDescriptor(agentId);
            return descriptor == null ? null : descriptor.getName();
        } catch (IResourceStore.ResourceNotFoundException e) {
            throw new NotFoundException(notFound("Agent not found: " + agentId));
        } catch (IResourceStore.ResourceStoreException e) {
            throw new InternalServerErrorException("Could not read the agent", e);
        }
    }

    private static Response notFound(String message) {
        return Response.status(Response.Status.NOT_FOUND).type(MediaType.APPLICATION_JSON_TYPE)
                .entity(ErrorResponses.body("not_found", message)).build();
    }

    /**
     * The global-variable tenants whose change markers the agent's charts show:
     * {@code default}, and the tenant of the agent's space when it has one. A
     * descriptor that cannot be read yields {@code default} only.
     */
    public List<String> markerTenants(String agentId) {
        List<String> tenants = new ArrayList<>(List.of(HealthMarker.DEFAULT_TENANT));
        try {
            DocumentDescriptor descriptor = descriptorStore.readCurrentDescriptor(agentId);
            if (descriptor != null && descriptor.getSpaceId() != null && !descriptor.getSpaceId().isBlank()) {
                tenants.add(SpaceTenants.tenantFor(descriptor.getSpaceId()));
            }
        } catch (IResourceStore.ResourceStoreException | IResourceStore.ResourceNotFoundException | IllegalArgumentException e) {
            // default only
        }
        return tenants;
    }

    /** Whether the caller may read the agent's ratings (the quality section). */
    public boolean mayReadRatings(String agentId) {
        if (authEnabled && (identity == null || identity.isAnonymous() || !identity.hasRole(RATING_ADMIN) && !identity.hasRole(RATING_EDITOR))) {
            return false;
        }
        return accessGuard.seesEverything() || accessGuard.hasAccess(agentId, AccessLevel.EDIT);
    }

    /**
     * The agents a portfolio looks at, and whether the caller may view more than
     * {@link #MAX_AGENTS} — the agents past the cap are left out.
     */
    public record ViewableAgents(List<AgentRef> agents, boolean truncated) {
    }

    /**
     * The agents the caller may view, at most {@link #MAX_AGENTS}, in descriptor
     * order. Reading stops at the first viewable agent past the cap, which is what
     * {@code truncated} reports.
     */
    public ViewableAgents viewableAgents() {
        List<AgentRef> agents = new ArrayList<>();
        try {
            for (int index = 0;; index++) {
                List<DocumentDescriptor> page = descriptorStore.readDescriptors(AGENT_DESCRIPTOR_TYPE, "", index, PAGE, false,
                        accessGuard.listingScope());
                for (DocumentDescriptor descriptor : page) {
                    if (descriptor.getResource() == null || !accessGuard.canAccess(descriptor, AccessLevel.VIEW)) {
                        continue;
                    }
                    IResourceStore.IResourceId id = RestUtilities.extractResourceId(descriptor.getResource());
                    if (id != null && id.getId() != null) {
                        if (agents.size() == MAX_AGENTS) {
                            return new ViewableAgents(agents, true);
                        }
                        agents.add(new AgentRef(id.getId(), descriptor.getName()));
                    }
                }
                if (page.size() < PAGE) {
                    break;
                }
            }
        } catch (IResourceStore.ResourceNotFoundException e) {
            return new ViewableAgents(agents, false);
        } catch (IResourceStore.ResourceStoreException e) {
            throw new InternalServerErrorException("Could not list the agents", e);
        }
        return new ViewableAgents(agents, false);
    }
}
