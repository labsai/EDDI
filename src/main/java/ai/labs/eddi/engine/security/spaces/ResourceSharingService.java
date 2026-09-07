/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.security.spaces;

import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.configs.descriptors.model.AccessLevel;
import ai.labs.eddi.configs.descriptors.model.DocumentDescriptor;
import ai.labs.eddi.configs.descriptors.model.ResourceGrant;
import ai.labs.eddi.configs.descriptors.model.ResourceVisibility;
import ai.labs.eddi.datastore.IResourceStore.ResourceNotFoundException;
import ai.labs.eddi.datastore.IResourceStore.ResourceStoreException;
import io.quarkus.security.ForbiddenException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.ServiceUnavailableException;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import static ai.labs.eddi.utils.LogSanitizer.sanitize;

/**
 * Grants, revokes and reports sharing on configuration resources.
 *
 * <h3>Sharing an agent shares the graph beneath it</h3> A grant on the agent
 * alone would give the recipient a name and a list of URIs they cannot resolve.
 * So every share and every revoke walks {@link ConfigGraphResolver} and applies
 * the same change to each referenced resource — but only to those the caller
 * themselves may re-share. You cannot pass on access you were merely lent: a
 * colleague's LLM config that you can see but do not own is skipped, and named
 * in the result, rather than silently widened.
 *
 * <h3>Revoke is reference-counted by construction</h3> Revoking removes the
 * subject's grant from the root and from each reachable resource, which is
 * correct even when two shared agents share a rule set: the second agent's own
 * grant on that rule set is a separate {@link ResourceGrant} for the same
 * subject only if it was granted through that agent, in which case re-sharing
 * the second agent restores it. Grants are keyed by subject, so the last revoke
 * wins — the alternative, tracking which share introduced which grant, buys
 * correctness in a case (two shares of overlapping graphs to the same person)
 * that a human would in any case expect to behave exactly like this.
 *
 * @author ginccc
 */
@ApplicationScoped
public class ResourceSharingService {

    private static final Logger LOGGER = Logger.getLogger(ResourceSharingService.class);

    private static final String RESOURCE_TYPE = "resource";

    private final IDocumentDescriptorStore documentDescriptorStore;
    private final ResourceAccessGuard accessGuard;
    private final ConfigGraphResolver graphResolver;

    @Inject
    public ResourceSharingService(IDocumentDescriptorStore documentDescriptorStore, ResourceAccessGuard accessGuard,
            ConfigGraphResolver graphResolver) {
        this.documentDescriptorStore = documentDescriptorStore;
        this.accessGuard = accessGuard;
        this.graphResolver = graphResolver;
    }

    /**
     * What a caller is allowed to know about how a resource is shared.
     *
     * @param resourceId
     *            the resource
     * @param ownerId
     *            the recorded owner, or {@code null} for legacy data
     * @param spaceId
     *            the space the resource is filed under
     * @param visibility
     *            its {@link ResourceVisibility} wire name
     * @param grants
     *            explicit shares
     * @param callerLevel
     *            what the calling user may do with it
     */
    public record ShareInfo(String resourceId, String ownerId, String spaceId, String visibility, List<ResourceGrant> grants,
            String callerLevel) {
    }

    /**
     * One resource a share touched — or declined to.
     *
     * @param id
     *            the resource id
     * @param name
     *            its descriptor name, or {@code null} when it has none. Carried so
     *            a share dialog can say "also granted on Support Rules" rather than
     *            "also granted on 1111111111111111111111", which is the difference
     *            between a confirmation a person can check and one they can only
     *            accept. Without it the Manager would issue one descriptor read per
     *            entry to render the same sentence.
     */
    public record ShareTarget(String id, String name) {
    }

    /**
     * The outcome of a share or revoke.
     *
     * @param updated
     *            resources actually changed, including the root
     * @param skipped
     *            resources reachable from the root that the caller may not
     *            re-share, and which were therefore left alone
     */
    public record ShareResult(List<ShareTarget> updated, List<ShareTarget> skipped) {

        /** Ids only — the shape callers that just need to count or compare want. */
        public List<String> updatedIds() {
            return updated.stream().map(ShareTarget::id).toList();
        }

        /** Ids only, for the resources left alone. */
        public List<String> skippedIds() {
            return skipped.stream().map(ShareTarget::id).toList();
        }
    }

    /**
     * Reports how a resource is shared.
     * <p>
     * Readable at {@link AccessLevel#VIEW}, but the <em>grant list</em> only at
     * {@link AccessLevel#OWN}. A {@code published} resource grants VIEW to
     * everyone, so returning its grants to any reader would publish every subject
     * on it — real principal names and Keycloak team names — to the whole
     * deployment. Owner, space and the caller's own level are enough for a
     * recipient to understand why they can see something and whom to ask about it.
     */
    public ShareInfo describe(String resourceId) {
        accessGuard.requireAccess(resourceId, AccessLevel.VIEW, RESOURCE_TYPE);
        DocumentDescriptor descriptor = loadOrThrow(resourceId);
        AccessLevel level = accessGuard.effectiveLevel(descriptor);
        boolean maySeeGrants = level != null && level.includes(AccessLevel.OWN);
        List<ResourceGrant> grants = maySeeGrants && descriptor.getGrants() != null ? List.copyOf(descriptor.getGrants()) : List.of();
        return new ShareInfo(resourceId, descriptor.getOwnerId(), descriptor.getSpaceId(),
                descriptor.getVisibility() == null ? ResourceVisibility.space.wireName() : descriptor.getVisibility(),
                grants, level == null ? null : level.name());
    }

    /**
     * Grants {@code subject} the given level on {@code resourceId} and, when
     * {@code cascade}, on everything reachable from it.
     *
     * @throws ForbiddenException
     *             if the caller does not own the root
     */
    public ShareResult share(String resourceId, String subject, AccessLevel level, boolean cascade) {
        // Re-sharing changes who can reach the resource, which is an owner's decision
        // — EDIT deliberately does not carry it. See AccessLevel.
        accessGuard.requireAccess(resourceId, AccessLevel.OWN, RESOURCE_TYPE);

        String grantedBy = accessGuard.currentPrincipal();
        List<ShareTarget> updated = new ArrayList<>();
        List<ShareTarget> skipped = new ArrayList<>();

        applyGrant(resourceId, subject, level, grantedBy, updated, skipped);
        for (String referenced : targets(resourceId, cascade)) {
            applyGrant(referenced, subject, level, grantedBy, updated, skipped);
        }
        return new ShareResult(updated, skipped);
    }

    /** Removes {@code subject}'s grant, mirroring {@link #share}. */
    public ShareResult revoke(String resourceId, String subject, boolean cascade) {
        accessGuard.requireAccess(resourceId, AccessLevel.OWN, RESOURCE_TYPE);

        List<ShareTarget> updated = new ArrayList<>();
        List<ShareTarget> skipped = new ArrayList<>();

        applyRevoke(resourceId, subject, updated, skipped);
        for (String referenced : targets(resourceId, cascade)) {
            applyRevoke(referenced, subject, updated, skipped);
        }
        return new ShareResult(updated, skipped);
    }

    /**
     * Sets a resource's visibility, mirroring {@link #share}.
     * <p>
     * Cascades for the same reason a grant does: publishing an agent whose rule
     * sets stay private publishes something nobody can actually use.
     */
    public ShareResult setVisibility(String resourceId, ResourceVisibility visibility, boolean cascade) {
        accessGuard.requireAccess(resourceId, AccessLevel.OWN, RESOURCE_TYPE);

        List<ShareTarget> updated = new ArrayList<>();
        List<ShareTarget> skipped = new ArrayList<>();

        applyVisibility(resourceId, visibility, updated, skipped);
        for (String referenced : targets(resourceId, cascade)) {
            applyVisibility(referenced, visibility, updated, skipped);
        }
        return new ShareResult(updated, skipped);
    }

    /**
     * Transfers ownership. Administrators only — the point of this operation is to
     * recover a resource whose owner has left, which by definition cannot require
     * that owner's cooperation.
     */
    public ShareResult transferOwnership(String resourceId, String newOwnerId, String newSpaceId, boolean cascade) {
        if (!accessGuard.isAdmin()) {
            throw new ForbiddenException("Only an administrator may transfer ownership");
        }
        // Validated here rather than only at the REST edge, because this is a public
        // bean any in-process caller can reach. A blank owner combined with cascade
        // would make the agent and its entire config graph unowned — which under the
        // default legacy-visibility policy means owned by everybody.
        if (newOwnerId == null || newOwnerId.isBlank()) {
            throw new IllegalArgumentException("A new owner is required: transferring ownership to nobody would make the resource "
                    + "unowned, which the legacy-visibility policy treats as reachable by everyone.");
        }
        String owner = newOwnerId.trim();

        List<ShareTarget> updated = new ArrayList<>();
        List<ShareTarget> skipped = new ArrayList<>();

        Set<String> all = new LinkedHashSet<>();
        all.add(resourceId);
        all.addAll(targets(resourceId, cascade));

        for (String id : all) {
            try {
                VersionedDescriptor loaded = loadOrNull(id);
                if (loaded == null) {
                    skipped.add(new ShareTarget(id, null));
                    continue;
                }
                DocumentDescriptor descriptor = loaded.descriptor();
                descriptor.setOwnerId(owner);
                descriptor.setSpaceId(newSpaceId == null || newSpaceId.isBlank() ? Subjects.personalSpace(owner) : newSpaceId.trim());
                writeBack(id, descriptor, loaded.version());
                updated.add(new ShareTarget(id, descriptor.getName()));
            } catch (Exception e) {
                LOGGER.warnf("Could not transfer ownership of %s: %s", sanitize(id), e.getMessage());
                skipped.add(new ShareTarget(id, null));
            }
        }
        return new ShareResult(updated, skipped);
    }

    private Set<String> targets(String resourceId, boolean cascade) {
        return cascade ? graphResolver.referencedResourceIds(resourceId) : Set.of();
    }

    private void applyGrant(String id, String subject, AccessLevel level, String grantedBy, List<ShareTarget> updated, List<ShareTarget> skipped) {
        mutate(id, updated, skipped, descriptor -> {
            List<ResourceGrant> grants = descriptor.getGrants() == null ? new ArrayList<>() : new ArrayList<>(descriptor.getGrants());
            // One grant per subject: re-sharing at a different level replaces rather than
            // accumulates, so revoking once is enough to actually revoke.
            grants.removeIf(grant -> grant == null || subject.equals(grant.getSubject()));
            grants.add(new ResourceGrant(subject, level.name(), grantedBy, new Date(System.currentTimeMillis())));
            descriptor.setGrants(grants);
        });
    }

    private void applyRevoke(String id, String subject, List<ShareTarget> updated, List<ShareTarget> skipped) {
        mutate(id, updated, skipped, descriptor -> {
            if (descriptor.getGrants() == null) {
                return;
            }
            List<ResourceGrant> grants = new ArrayList<>(descriptor.getGrants());
            grants.removeIf(grant -> grant == null || subject.equals(grant.getSubject()));
            descriptor.setGrants(grants);
        });
    }

    private void applyVisibility(String id, ResourceVisibility visibility, List<ShareTarget> updated, List<ShareTarget> skipped) {
        mutate(id, updated, skipped, descriptor -> descriptor.setVisibility(visibility.wireName()));
    }

    /**
     * Loads, checks the caller may re-share, mutates, and writes back — rebuilding
     * the access index every time, because a descriptor whose structured fields and
     * index disagree is invisible in listings it should appear in.
     */
    private void mutate(String id, List<ShareTarget> updated, List<ShareTarget> skipped, Consumer<DocumentDescriptor> change) {
        VersionedDescriptor loaded;
        try {
            loaded = loadOrNull(id);
        } catch (Exception e) {
            LOGGER.warnf("Could not load descriptor %s while updating sharing: %s", sanitize(id), e.getMessage());
            skipped.add(new ShareTarget(id, null));
            return;
        }
        if (loaded == null) {
            skipped.add(new ShareTarget(id, null));
            return;
        }
        DocumentDescriptor descriptor = loaded.descriptor();
        // You cannot pass on access you were only lent. A referenced resource the
        // caller can read but does not own is left exactly as it was, and reported.
        if (!accessGuard.canAccess(descriptor, AccessLevel.OWN)) {
            skipped.add(new ShareTarget(id, descriptor.getName()));
            return;
        }
        change.accept(descriptor);
        try {
            writeBack(id, descriptor, loaded.version());
            updated.add(new ShareTarget(id, descriptor.getName()));
        } catch (Exception e) {
            LOGGER.warnf("Could not write sharing change to %s: %s", sanitize(id), e.getMessage());
            skipped.add(new ShareTarget(id, descriptor.getName()));
        }
    }

    /**
     * Writes the descriptor back at the version it was read from.
     * <p>
     * <b>Not</b> at whatever the current version happens to be at write time. The
     * descriptor object in hand was read at version N; if a {@code PUT} on the
     * resource lands in between, {@code DocumentDescriptorFilter} creates version
     * N+1 with {@code resource} re-pointed at it — and writing our stale object
     * over N+1 would leave the descriptor naming the wrong version of its own
     * resource.
     * <p>
     * {@code setDescriptor} writes in place rather than creating a version, because
     * sharing is metadata about the resource rather than a new revision of it;
     * versioning it would make every share look like a config change in the
     * resource's history.
     * <p>
     * Concurrent shares of the same resource still race — the store offers no
     * compare-and-set on this path — so the last write wins on the grant list. Two
     * people re-sharing one resource in the same instant is not a case worth a
     * lock; two people sharing <em>different</em> resources, which is the common
     * one, does not interact at all.
     */
    private void writeBack(String id, DocumentDescriptor descriptor, int version) throws ResourceStoreException, ResourceNotFoundException {
        accessGuard.stampModification(descriptor);
        documentDescriptorStore.setDescriptor(id, version, descriptor);
    }

    /**
     * A descriptor and the version it was read at, so a write can go back to
     * exactly that version rather than to whatever is current by then.
     */
    private record VersionedDescriptor(DocumentDescriptor descriptor, int version) {
    }

    private VersionedDescriptor loadOrNull(String id) throws ResourceStoreException, ResourceNotFoundException {
        var current = documentDescriptorStore.getCurrentResourceId(id);
        DocumentDescriptor descriptor = documentDescriptorStore.readDescriptor(id, current.getVersion());
        return descriptor == null ? null : new VersionedDescriptor(descriptor, current.getVersion());
    }

    /**
     * Load a descriptor for a caller that has already passed
     * {@code accessGuard.requireAccess}.
     * <p>
     * A {@link ResourceStoreException} is the store's I/O failure type — a MongoDB
     * failover, an exhausted PostgreSQL pool — not an access decision. It used to
     * be translated into {@link ForbiddenException}, so during an outage the
     * sharing dialog told an operator they were not allowed to view the sharing
     * state of a resource they in fact own, and the outage never showed up in
     * monitoring keyed on 5xx. By the time this runs the authorization question is
     * settled; anything thrown here is infrastructure, and 503 says so.
     */
    private DocumentDescriptor loadOrThrow(String id) {
        try {
            VersionedDescriptor loaded = loadOrNull(id);
            if (loaded == null) {
                throw new NotFoundException("No such resource: " + id);
            }
            return loaded.descriptor();
        } catch (ResourceNotFoundException e) {
            throw new NotFoundException("No such resource: " + id);
        } catch (ResourceStoreException e) {
            LOGGER.errorf(e, "Could not read the sharing state of resource %s", sanitize(id));
            throw new ServiceUnavailableException("Unable to read the sharing state of this resource right now");
        }
    }
}
