/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.security.spaces;

import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.configs.descriptors.model.AccessLevel;
import ai.labs.eddi.configs.descriptors.model.DocumentDescriptor;
import ai.labs.eddi.configs.descriptors.model.ResourceVisibility;
import ai.labs.eddi.datastore.IResourceStore.ResourceNotFoundException;
import ai.labs.eddi.datastore.IResourceStore.ResourceStoreException;
import ai.labs.eddi.engine.security.OwnershipValidator;
import io.quarkus.security.ForbiddenException;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.Date;

import static ai.labs.eddi.utils.LogSanitizer.sanitize;

/**
 * Single source of truth for <em>who may do what with a configuration
 * resource</em> — agents, workflows, rule sets, LLM configs, output sets,
 * dictionaries, api calls, mcp calls, RAG configs, prompt snippets, channels,
 * connections and agent groups alike.
 * <p>
 * The direct sibling of
 * {@link ai.labs.eddi.engine.security.ConversationAccessGuard}: same
 * composition (delegate role and identity questions to
 * {@link OwnershipValidator}, resolve the owner through the resource's
 * descriptor), same pairing of a throwing check for single-resource operations
 * with a non-throwing one for listings.
 *
 * <h3>Where this is and is not applied</h3> It guards the <em>authoring</em>
 * surface: {@code RestVersionInfo} and the {@code IRest*Store} facades. MCP
 * tools that hold an injected facade — {@code McpConversationTools}'s agent
 * store, {@code McpGroupTools}'s group store, {@code McpAdminTools}'s
 * {@code IRestAgentAdministration} — call those beans in-process and inherit
 * it. MCP tools that resolve a store through {@code IRestInterfaceFactory}
 * instead do <em>not</em>: that builds a REST client and makes a loopback HTTP
 * call, so the endpoint's own checks apply to a request that carries no
 * credentials — which is a pre-existing problem with internal loopback calls,
 * not something this guard introduces or fixes.
 * <p>
 * It is deliberately absent from the engine's own resolution path —
 * {@code ResourceClientLibrary.getResource} reads through the stores directly,
 * because the identity on a conversation turn is whoever is chatting, who does
 * not own the agent they are talking to.
 *
 * @author ginccc
 */
@ApplicationScoped
public class ResourceAccessGuard {

    private static final Logger LOGGER = Logger.getLogger(ResourceAccessGuard.class);

    private final SecurityIdentity identity;
    private final OwnershipValidator ownershipValidator;
    private final SpaceContext spaceContext;
    private final WorkspaceSettings settings;
    private final IDocumentDescriptorStore documentDescriptorStore;

    @Inject
    public ResourceAccessGuard(SecurityIdentity identity, OwnershipValidator ownershipValidator, SpaceContext spaceContext,
            WorkspaceSettings settings, IDocumentDescriptorStore documentDescriptorStore) {
        this.identity = identity;
        this.ownershipValidator = ownershipValidator;
        this.spaceContext = spaceContext;
        this.settings = settings;
        this.documentDescriptorStore = documentDescriptorStore;
    }

    /**
     * Whether this caller sees every resource regardless of owner — an
     * administrator, or anybody at all when workspaces are not being enforced.
     * <p>
     * Lets a listing skip the access predicate entirely rather than building one
     * that would match everything anyway.
     */
    public boolean seesEverything() {
        return !settings.isEnforcing() || ownershipValidator.isAdmin(identity);
    }

    /**
     * The scope to hand to
     * {@link IDocumentDescriptorStore#readDescriptors(String, String, Integer, Integer, boolean, AccessScope)}.
     */
    public AccessScope listingScope() {
        if (seesEverything()) {
            return AccessScope.unrestricted();
        }
        return AccessScope.forCaller(spaceContext.current(), settings.admitsLegacy());
    }

    /**
     * What the caller may do with an already-loaded descriptor, or {@code null} for
     * nothing. Returns {@link AccessLevel#OWN} whenever {@link #seesEverything()}.
     */
    public AccessLevel effectiveLevel(DocumentDescriptor descriptor) {
        if (seesEverything()) {
            return AccessLevel.OWN;
        }
        return DescriptorAccess.effectiveLevel(descriptor, spaceContext.current(), settings.admitsLegacy());
    }

    /**
     * Non-throwing check for filtering an already-materialised list, mirroring
     * {@link #requireAccess} exactly so a caller never lists something they could
     * not read.
     */
    public boolean canAccess(DocumentDescriptor descriptor, AccessLevel required) {
        AccessLevel granted = effectiveLevel(descriptor);
        return granted != null && granted.includes(required);
    }

    /**
     * Asserts that the caller holds at least {@code required} on the resource.
     *
     * @param resourceId
     *            the resource's id (not its version — ownership belongs to the
     *            resource)
     * @param required
     *            the level the operation demands
     * @param resourceType
     *            human-readable type, for the error message
     * @return the level the caller actually holds, which may exceed
     *         {@code required}. Callers that also redact a response should pass
     *         this to {@link #redactUnlessOwner} rather than re-deciding against
     *         the descriptor they are about to return: that one may be an older
     *         version, whose recorded owner and grants can differ from the current
     *         ones this check used.
     *
     * @throws ForbiddenException
     *             if the caller's level is insufficient, or if the descriptor
     *             cannot be loaded at all — an unverifiable owner is not an absent
     *             owner
     */
    public AccessLevel requireAccess(String resourceId, AccessLevel required, String resourceType) {
        if (seesEverything()) {
            return AccessLevel.OWN;
        }
        if (resourceId == null || resourceId.isBlank()) {
            return null; // nothing addressed; the operation itself will fail on its own terms
        }

        DocumentDescriptor descriptor;
        try {
            descriptor = documentDescriptorStore.readCurrentDescriptor(resourceId);
        } catch (ResourceNotFoundException e) {
            return requireLegacyFallback(resourceId, required, resourceType);
        } catch (ResourceStoreException e) {
            LOGGER.warnf("Could not load descriptor for access check on %s: %s", sanitize(resourceId), e.getMessage());
            throw new ForbiddenException("Access denied: unable to verify ownership of this " + resourceType);
        }

        AccessLevel granted = DescriptorAccess.effectiveLevel(descriptor, spaceContext.current(), settings.admitsLegacy());
        if (granted == null || !granted.includes(required)) {
            LOGGER.warnf("Access check failed: caller denied %s access to %s", required, resourceType);
            LOGGER.debugf("Access detail: resourceId='%s', granted='%s', required='%s'", sanitize(resourceId), granted, required);
            throw new ForbiddenException("Access denied: you do not have " + required.name().toLowerCase() + " access to this " + resourceType);
        }
        return granted;
    }

    /**
     * Asserts the caller may <em>talk to</em> an agent — the
     * {@link AccessLevel#USE} gate on {@code POST /agents/{agentId}/start}.
     *
     * <h3>Separate from reading the configuration, on purpose</h3> Using an agent
     * and reading how it was built are different acts, and the common share is the
     * first one. A recipient with {@code USE} can hold a conversation and cannot
     * read the system prompt, the tool list, or the vault references behind them.
     *
     * <h3>What happens to public agents</h3> An anonymous caller — the production
     * chat endpoints are {@code permit} by HTTP policy — holds no subject and no
     * space, so only a {@code published} agent admits them. Agents that predate
     * ownership stay reachable under the legacy-visibility policy, so enabling
     * workspaces does not silently take an existing public bot offline; an agent
     * created <em>after</em> enforcement must be published deliberately, which the
     * refusal message says.
     */
    public void requireAgentUseAccess(String agentId) {
        requireUseAccess(agentId, "agent");
    }

    /**
     * As {@link #requireAgentUseAccess}, for a target that is not an agent.
     * <p>
     * Group discussions are reachable by the same routes agents are — a channel
     * target, a trigger — and a group is a guarded descriptor like any other, so
     * the same check applies. The label only shapes the refusal message; passing
     * the wrong one tells the caller to go ask the owner of the wrong thing.
     */
    public void requireUseAccess(String resourceId, String resourceTypeLabel) {
        if (seesEverything()) {
            return;
        }
        if (resourceId == null || resourceId.isBlank()) {
            return;
        }

        DocumentDescriptor descriptor;
        try {
            descriptor = documentDescriptorStore.readCurrentDescriptor(resourceId);
        } catch (ResourceNotFoundException e) {
            requireLegacyFallback(resourceId, AccessLevel.USE, resourceTypeLabel);
            return;
        } catch (ResourceStoreException e) {
            LOGGER.warnf("Could not load descriptor for use check on %s %s: %s", resourceTypeLabel, sanitize(resourceId),
                    e.getMessage());
            throw new ForbiddenException("Access denied: unable to verify access to this " + resourceTypeLabel);
        }

        AccessLevel granted = DescriptorAccess.effectiveLevel(descriptor, spaceContext.current(), settings.admitsLegacy());
        if (granted == null || !granted.includes(AccessLevel.USE)) {
            LOGGER.warnf("Use check failed: caller denied conversation access to a %s", resourceTypeLabel);
            LOGGER.debugf("Use detail: resourceId='%s', type='%s', granted='%s'", sanitize(resourceId), resourceTypeLabel, granted);
            throw new ForbiddenException("Access denied: you do not have access to this " + resourceTypeLabel
                    + ". Ask its owner to share it with you, or have them publish it if it is meant to be public.");
        }
    }

    /**
     * What to do when a resource has no descriptor at all.
     *
     * <h3>Read-only, never write</h3> A missing descriptor is not the same as an
     * unowned one. Resources can predate the descriptor filter, and some creation
     * paths never produce one — {@code AgentSetupService} reaches the stores over
     * an unauthenticated loopback call, for instance. Treating that as
     * {@link AccessLevel#OWN} under the default {@code legacy-visibility=shared}
     * would hand every editor delete, undeploy and re-share on any such resource,
     * which is a fail-open answer to a question we could not answer at all.
     * <p>
     * So the fallback admits reading and using, and refuses anything above it
     * regardless of the legacy policy. That keeps an upgraded deployment working —
     * nothing disappears, nothing stops answering — without letting an absent
     * record grant authority.
     * <p>
     * Logged at WARN rather than DEBUG: a resource with no descriptor is a gap an
     * operator should be able to find and close, not something to discover from a
     * support ticket.
     */
    private AccessLevel requireLegacyFallback(String resourceId, AccessLevel required, String resourceType) {
        if (settings.admitsLegacy() && !required.includes(AccessLevel.EDIT)) {
            LOGGER.debugf("No descriptor for %s %s; admitting %s under legacy-visibility", resourceType, sanitize(resourceId), required);
            // Exactly what was asked for and no more. Reporting OWN here would let a
            // caller redact-check their way into a grant list on a resource whose owner
            // we could not establish in the first place.
            return required;
        }
        LOGGER.warnf("No descriptor recorded for %s '%s' — refusing %s access. A resource with no descriptor has no owner, "
                + "so it cannot be edited, deleted or re-shared until one is assigned.", resourceType, sanitize(resourceId), required);
        throw new ForbiddenException("Access denied: this " + resourceType + " has no recorded owner");
    }

    /**
     * Stamps ownership onto a descriptor being created, and materialises its access
     * index.
     * <p>
     * Runs whenever authentication is on, <em>not</em> only when enforcement is —
     * so an operator can deploy, let attribution accumulate and be verified, and
     * only then switch enforcement on. Flipping enforcement against unstamped data
     * is what would hide people's own work from them.
     *
     * @return the same descriptor, for chaining
     */
    public DocumentDescriptor stampNewDescriptor(DocumentDescriptor descriptor) {
        if (descriptor == null) {
            return null;
        }
        if (settings.isStampingOwnership()) {
            String principal = spaceContext.currentPrincipal();
            if (principal != null) {
                if (descriptor.getOwnerId() == null || descriptor.getOwnerId().isBlank()) {
                    // Trimmed, because CallerSpaces.of and defaultWriteSpace both trim: an owner
                    // stored with surrounding whitespace would never match its own principal
                    // again, and the owner would silently lose their own resource.
                    descriptor.setOwnerId(principal.trim());
                }
                if (descriptor.getSpaceId() == null || descriptor.getSpaceId().isBlank()) {
                    descriptor.setSpaceId(spaceContext.defaultWriteSpace());
                }
                if (descriptor.getVisibility() == null || descriptor.getVisibility().isBlank()) {
                    descriptor.setVisibility(ResourceVisibility.space.wireName());
                }
            }
        }
        return DescriptorAccess.rebuildIndex(descriptor);
    }

    /**
     * Records the caller as the last modifier and refreshes the access index.
     * <p>
     * The index refresh matters even when nothing about sharing changed: a
     * descriptor whose index predates this feature acquires one the first time it
     * is written, which is what lets an existing deployment converge without
     * waiting for the backfill migration.
     */
    public DocumentDescriptor stampModification(DocumentDescriptor descriptor) {
        if (descriptor == null) {
            return null;
        }
        descriptor.setLastModifiedOn(new Date(System.currentTimeMillis()));
        return DescriptorAccess.rebuildIndex(descriptor);
    }

    /**
     * Strips what a non-owner has no business reading from a descriptor about to be
     * serialised: the grant list and the access index.
     * <p>
     * {@code ResourceSharingService.describe} already discloses grants only at
     * {@link AccessLevel#OWN} — but descriptors also leave the building through
     * every listing and every direct descriptor read, and a {@code published}
     * resource is listable by everyone. Without this, the sharing endpoint's
     * restraint is theatre: the same subjects — real principal and team names —
     * ride out in the list JSON. Owner, space and visibility stay: the Manager's
     * owner column needs them, and "owned by alice, space-visible" is exactly what
     * a recipient needs to understand why they can see something.
     * <p>
     * Mutates the given instance, which is safe because descriptors are
     * deserialised fresh per read — but for that reason this must never be called
     * on a descriptor that will be written back.
     *
     * @return the same descriptor, for chaining
     */
    public DocumentDescriptor redactForCaller(DocumentDescriptor descriptor) {
        if (descriptor == null || seesEverything() || canAccess(descriptor, AccessLevel.OWN)) {
            return descriptor;
        }
        return redact(descriptor);
    }

    /**
     * As {@link #redactForCaller}, but told what the caller holds instead of
     * working it out from the descriptor in hand.
     * <p>
     * For a versioned read the two are not the same question. Sharing writes land
     * on the <em>current</em> version, so an older version's descriptor can still
     * name a previous owner and carry that era's grant list — and deciding
     * redaction against it would disclose those to somebody who no longer owns the
     * resource. Pass the level {@link #requireAccess} returned, which was decided
     * against the current descriptor.
     *
     * @return the same descriptor, for chaining
     */
    public DocumentDescriptor redactUnlessOwner(DocumentDescriptor descriptor, AccessLevel callerLevel) {
        if (descriptor == null || seesEverything() || (callerLevel != null && callerLevel.includes(AccessLevel.OWN))) {
            return descriptor;
        }
        return redact(descriptor);
    }

    private static DocumentDescriptor redact(DocumentDescriptor descriptor) {
        descriptor.setGrants(null);
        descriptor.setAccessIndex(null);
        return descriptor;
    }

    /** The caller's principal name, or {@code null} when unauthenticated. */
    public String currentPrincipal() {
        return spaceContext.currentPrincipal();
    }

    /** Whether the caller holds the admin role (or authorization is disabled). */
    public boolean isAdmin() {
        return ownershipValidator.isAdmin(identity);
    }

    /** The workspace settings this guard enforces. */
    public WorkspaceSettings settings() {
        return settings;
    }

    /** The caller's spaces and subjects. */
    public CallerSpaces callerSpaces() {
        return spaceContext.current();
    }
}
