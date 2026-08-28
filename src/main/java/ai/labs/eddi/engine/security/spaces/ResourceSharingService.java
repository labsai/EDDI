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
     * The outcome of a share or revoke.
     *
     * @param updated
     *            resources actually changed, including the root
     * @param skipped
     *            resources reachable from the root that the caller may not
     *            re-share, and which were therefore left alone
     */
    public record ShareResult(List<String> updated, List<String> skipped) {
    }

    /** Reports how a resource is shared. Requires read access. */
    public ShareInfo describe(String resourceId) {
        accessGuard.requireAccess(resourceId, AccessLevel.VIEW, RESOURCE_TYPE);
        DocumentDescriptor descriptor = loadOrThrow(resourceId);
        AccessLevel level = accessGuard.effectiveLevel(descriptor);
        return new ShareInfo(resourceId, descriptor.getOwnerId(), descriptor.getSpaceId(),
                descriptor.getVisibility() == null ? ResourceVisibility.space.wireName() : descriptor.getVisibility(),
                descriptor.getGrants() == null ? List.of() : List.copyOf(descriptor.getGrants()),
                level == null ? null : level.name());
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
        List<String> updated = new ArrayList<>();
        List<String> skipped = new ArrayList<>();

        applyGrant(resourceId, subject, level, grantedBy, updated, skipped);
        for (String referenced : targets(resourceId, cascade)) {
            applyGrant(referenced, subject, level, grantedBy, updated, skipped);
        }
        return new ShareResult(updated, skipped);
    }

    /** Removes {@code subject}'s grant, mirroring {@link #share}. */
    public ShareResult revoke(String resourceId, String subject, boolean cascade) {
        accessGuard.requireAccess(resourceId, AccessLevel.OWN, RESOURCE_TYPE);

        List<String> updated = new ArrayList<>();
        List<String> skipped = new ArrayList<>();

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

        List<String> updated = new ArrayList<>();
        List<String> skipped = new ArrayList<>();

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

        List<String> updated = new ArrayList<>();
        List<String> skipped = new ArrayList<>();

        Set<String> all = new LinkedHashSet<>();
        all.add(resourceId);
        all.addAll(targets(resourceId, cascade));

        for (String id : all) {
            try {
                DocumentDescriptor descriptor = loadOrNull(id);
                if (descriptor == null) {
                    skipped.add(id);
                    continue;
                }
                descriptor.setOwnerId(newOwnerId);
                descriptor.setSpaceId(newSpaceId == null || newSpaceId.isBlank() ? Subjects.personalSpace(newOwnerId) : newSpaceId);
                writeBack(id, descriptor);
                updated.add(id);
            } catch (Exception e) {
                LOGGER.warnf("Could not transfer ownership of %s: %s", sanitize(id), e.getMessage());
                skipped.add(id);
            }
        }
        return new ShareResult(updated, skipped);
    }

    private Set<String> targets(String resourceId, boolean cascade) {
        return cascade ? graphResolver.referencedResourceIds(resourceId) : Set.of();
    }

    private void applyGrant(String id, String subject, AccessLevel level, String grantedBy, List<String> updated, List<String> skipped) {
        mutate(id, updated, skipped, descriptor -> {
            List<ResourceGrant> grants = descriptor.getGrants() == null ? new ArrayList<>() : new ArrayList<>(descriptor.getGrants());
            // One grant per subject: re-sharing at a different level replaces rather than
            // accumulates, so revoking once is enough to actually revoke.
            grants.removeIf(grant -> grant == null || subject.equals(grant.getSubject()));
            grants.add(new ResourceGrant(subject, level.name(), grantedBy, new Date(System.currentTimeMillis())));
            descriptor.setGrants(grants);
        });
    }

    private void applyRevoke(String id, String subject, List<String> updated, List<String> skipped) {
        mutate(id, updated, skipped, descriptor -> {
            if (descriptor.getGrants() == null) {
                return;
            }
            List<ResourceGrant> grants = new ArrayList<>(descriptor.getGrants());
            grants.removeIf(grant -> grant == null || subject.equals(grant.getSubject()));
            descriptor.setGrants(grants);
        });
    }

    private void applyVisibility(String id, ResourceVisibility visibility, List<String> updated, List<String> skipped) {
        mutate(id, updated, skipped, descriptor -> descriptor.setVisibility(visibility.wireName()));
    }

    /**
     * Loads, checks the caller may re-share, mutates, and writes back — rebuilding
     * the access index every time, because a descriptor whose structured fields and
     * index disagree is invisible in listings it should appear in.
     */
    private void mutate(String id, List<String> updated, List<String> skipped, Consumer<DocumentDescriptor> change) {
        DocumentDescriptor descriptor;
        try {
            descriptor = loadOrNull(id);
        } catch (Exception e) {
            LOGGER.warnf("Could not load descriptor %s while updating sharing: %s", sanitize(id), e.getMessage());
            skipped.add(id);
            return;
        }
        if (descriptor == null) {
            skipped.add(id);
            return;
        }
        // You cannot pass on access you were only lent. A referenced resource the
        // caller can read but does not own is left exactly as it was, and reported.
        if (!accessGuard.canAccess(descriptor, AccessLevel.OWN)) {
            skipped.add(id);
            return;
        }
        change.accept(descriptor);
        try {
            writeBack(id, descriptor);
            updated.add(id);
        } catch (Exception e) {
            LOGGER.warnf("Could not write sharing change to %s: %s", sanitize(id), e.getMessage());
            skipped.add(id);
        }
    }

    private void writeBack(String id, DocumentDescriptor descriptor) throws ResourceStoreException, ResourceNotFoundException {
        accessGuard.stampModification(descriptor);
        var current = documentDescriptorStore.getCurrentResourceId(id);
        // setDescriptor writes in place rather than creating a version: sharing is
        // metadata about the resource, not a new revision of it, and versioning it
        // would make every share look like a config change in the resource's history.
        documentDescriptorStore.setDescriptor(id, current.getVersion(), descriptor);
    }

    private DocumentDescriptor loadOrNull(String id) throws ResourceStoreException, ResourceNotFoundException {
        return documentDescriptorStore.readCurrentDescriptor(id);
    }

    private DocumentDescriptor loadOrThrow(String id) {
        try {
            DocumentDescriptor descriptor = loadOrNull(id);
            if (descriptor == null) {
                throw new NotFoundException("No such resource: " + id);
            }
            return descriptor;
        } catch (ResourceNotFoundException e) {
            throw new NotFoundException("No such resource: " + id);
        } catch (ResourceStoreException e) {
            throw new ForbiddenException("Unable to read the sharing state of this resource");
        }
    }
}
