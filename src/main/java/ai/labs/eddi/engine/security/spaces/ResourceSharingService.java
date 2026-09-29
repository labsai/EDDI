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
import ai.labs.eddi.engine.security.spaces.directory.DirectoryUser;
import ai.labs.eddi.engine.security.spaces.directory.UserDirectory;
import ai.labs.eddi.engine.security.spaces.notifications.WorkspaceNotifications;
import io.quarkus.security.ForbiddenException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.ServiceUnavailableException;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
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
    private final UserDirectory directory;
    private final WorkspaceNotifications notifications;
    private final Event<SharingChangedEvent> sharingChanged;

    @Inject
    public ResourceSharingService(IDocumentDescriptorStore documentDescriptorStore, ResourceAccessGuard accessGuard,
            ConfigGraphResolver graphResolver, UserDirectory directory, WorkspaceNotifications notifications,
            Event<SharingChangedEvent> sharingChanged) {
        this.documentDescriptorStore = documentDescriptorStore;
        this.accessGuard = accessGuard;
        this.graphResolver = graphResolver;
        this.directory = directory;
        this.notifications = notifications;
        this.sharingChanged = sharingChanged;
    }

    /**
     * What a caller is allowed to know about how a resource is shared.
     *
     * @param resourceId
     *            the resource
     * @param ownerId
     *            the recorded owner, or {@code null} for legacy data
     * @param ownerLabel
     *            the owner as a person would recognise them — their name from the
     *            user directory, else the principal
     * @param spaceId
     *            the space the resource is filed under
     * @param visibility
     *            its {@link ResourceVisibility} wire name
     * @param grants
     *            explicit shares, each with a display label
     * @param callerLevel
     *            what the calling user may do with it
     */
    public record ShareInfo(String resourceId, String ownerId, String ownerLabel, String spaceId, String visibility, List<GrantView> grants,
            String callerLevel) {
    }

    /**
     * One grant as the share dialog shows it.
     *
     * @param subject
     *            {@code user:<principal>} or {@code team:<group>} — what a revoke
     *            sends back
     * @param kind
     *            {@code user} or {@code team}
     * @param label
     *            a person's name, or a team's decoded name
     * @param detail
     *            a secondary line — the person's email, when the deployment exposes
     *            it — or {@code null}
     * @param known
     *            whether the subject still names somebody the directory knows. A
     *            grant made before the directory existed can name a principal that
     *            never signs in; showing that lets the owner remove it
     */
    public record GrantView(String subject, String level, String grantedBy, Date grantedOn, String kind, String label, String detail,
            boolean known) {
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
     *            resources actually changed, including the root — or, for a dry
     *            run, the ones that would be
     * @param skipped
     *            resources reachable from the root that the caller may not
     *            re-share, and which were therefore left alone
     * @param dryRun
     *            whether this is a preview: nothing was written
     */
    public record ShareResult(List<ShareTarget> updated, List<ShareTarget> skipped, boolean dryRun) {

        /** A result of a change that was actually applied. */
        public ShareResult(List<ShareTarget> updated, List<ShareTarget> skipped) {
            this(updated, skipped, false);
        }

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

        Set<String> principals = new LinkedHashSet<>();
        if (descriptor.getOwnerId() != null) {
            principals.add(descriptor.getOwnerId());
        }
        for (ResourceGrant grant : grants) {
            if (grant != null && grant.getSubject() != null && grant.getSubject().startsWith(Subjects.USER_PREFIX)) {
                principals.add(Subjects.decode(grant.getSubject().substring(Subjects.USER_PREFIX.length())));
            }
        }
        Map<String, DirectoryUser> people = directory.lookup(principals);

        List<GrantView> views = new ArrayList<>(grants.size());
        for (ResourceGrant grant : grants) {
            if (grant != null && grant.getSubject() != null) {
                views.add(view(grant, people));
            }
        }
        String ownerId = descriptor.getOwnerId();
        DirectoryUser owner = ownerId == null ? null : people.get(ownerId);
        return new ShareInfo(resourceId, ownerId, owner == null ? ownerId : owner.label(), descriptor.getSpaceId(),
                descriptor.getVisibility() == null ? ResourceVisibility.space.wireName() : descriptor.getVisibility(),
                List.copyOf(views), level == null ? null : level.name());
    }

    private GrantView view(ResourceGrant grant, Map<String, DirectoryUser> people) {
        String subject = grant.getSubject();
        if (subject.startsWith(Subjects.TEAM_PREFIX)) {
            String team = Subjects.decode(subject.substring(Subjects.TEAM_PREFIX.length()));
            return new GrantView(subject, grant.getLevel(), grant.getGrantedBy(), grant.getGrantedOn(), "team", team, null, true);
        }
        String principal = subject.startsWith(Subjects.USER_PREFIX)
                ? Subjects.decode(subject.substring(Subjects.USER_PREFIX.length()))
                : subject;
        DirectoryUser person = people.get(principal);
        // Unknown is only a finding when the directory is running: without it,
        // nobody is known and flagging every grant would be noise.
        boolean known = person != null || !directory.isActive();
        return new GrantView(subject, grant.getLevel(), grant.getGrantedBy(), grant.getGrantedOn(), "user",
                person == null ? principal : person.label(), directory.detailFor(person), known);
    }

    /**
     * Grants {@code subject} the given level on {@code resourceId} and, when
     * {@code cascade}, on everything reachable from it.
     *
     * @throws ForbiddenException
     *             if the caller does not own the root
     */
    public ShareResult share(String resourceId, String subject, AccessLevel level, boolean cascade) {
        return share(resourceId, subject, level, cascade, false);
    }

    /**
     * As {@link #share(String, String, AccessLevel, boolean)}; with {@code dryRun},
     * reports what would change and writes nothing.
     * <p>
     * The preview exists because a cascade is invisible in the request: sharing a
     * group also shares every agent in it, and every workflow and rule set beneath
     * those. The share dialog shows that list before anything is granted.
     */
    public ShareResult share(String resourceId, String subject, AccessLevel level, boolean cascade, boolean dryRun) {
        // Re-sharing changes who can reach the resource, which is an owner's decision
        // — EDIT deliberately does not carry it. See AccessLevel.
        accessGuard.requireAccess(resourceId, AccessLevel.OWN, RESOURCE_TYPE);

        String grantedBy = accessGuard.currentPrincipal();
        List<ShareTarget> updated = new ArrayList<>();
        List<ShareTarget> skipped = new ArrayList<>();

        applyGrant(resourceId, subject, level, grantedBy, updated, skipped, dryRun);
        for (String referenced : targets(resourceId, cascade)) {
            applyGrant(referenced, subject, level, grantedBy, updated, skipped, dryRun);
        }
        if (!dryRun && notifications != null && updated.stream().anyMatch(target -> target.id().equals(resourceId))) {
            announce(resourceId, subject, level);
        }
        return notifyChanged(new ShareResult(updated, skipped, dryRun));
    }

    /** Removes {@code subject}'s grant, mirroring {@link #share}. */
    public ShareResult revoke(String resourceId, String subject, boolean cascade) {
        return revoke(resourceId, subject, cascade, false);
    }

    /** As {@link #revoke(String, String, boolean)}; a dry run writes nothing. */
    public ShareResult revoke(String resourceId, String subject, boolean cascade, boolean dryRun) {
        accessGuard.requireAccess(resourceId, AccessLevel.OWN, RESOURCE_TYPE);

        List<ShareTarget> updated = new ArrayList<>();
        List<ShareTarget> skipped = new ArrayList<>();

        applyRevoke(resourceId, subject, updated, skipped, dryRun);
        for (String referenced : targets(resourceId, cascade)) {
            applyRevoke(referenced, subject, updated, skipped, dryRun);
        }
        return notifyChanged(new ShareResult(updated, skipped, dryRun));
    }

    /**
     * Sets a resource's visibility, mirroring {@link #share}.
     * <p>
     * Cascades for the same reason a grant does: publishing an agent whose rule
     * sets stay private publishes something nobody can actually use.
     */
    public ShareResult setVisibility(String resourceId, ResourceVisibility visibility, boolean cascade) {
        return setVisibility(resourceId, visibility, cascade, false);
    }

    /**
     * As {@link #setVisibility(String, ResourceVisibility, boolean)}; a dry run
     * writes nothing.
     */
    public ShareResult setVisibility(String resourceId, ResourceVisibility visibility, boolean cascade, boolean dryRun) {
        accessGuard.requireAccess(resourceId, AccessLevel.OWN, RESOURCE_TYPE);

        List<ShareTarget> updated = new ArrayList<>();
        List<ShareTarget> skipped = new ArrayList<>();

        applyVisibility(resourceId, visibility, updated, skipped, dryRun);
        for (String referenced : targets(resourceId, cascade)) {
            applyVisibility(referenced, visibility, updated, skipped, dryRun);
        }
        return notifyChanged(new ShareResult(updated, skipped, dryRun));
    }

    /**
     * Files a resource the caller owns — and, with {@code cascade}, everything
     * beneath it that they also own — under another space they belong to.
     * <p>
     * This is how personal work becomes team work without an administrator:
     * ownership stays with the caller, so delete and re-share stay theirs, while
     * everybody in the team space gains edit access through the space. It was
     * previously only reachable through the admin-only ownership transfer, which
     * made the most common collaboration step a support ticket.
     *
     * @param spaceId
     *            the target space — one of the caller's own, unless they are an
     *            administrator
     * @throws ForbiddenException
     *             if the caller does not own the root, or does not belong to the
     *             target space
     */
    public ShareResult moveToSpace(String resourceId, String spaceId, boolean cascade, boolean dryRun) {
        accessGuard.requireAccess(resourceId, AccessLevel.OWN, RESOURCE_TYPE);
        if (spaceId == null || spaceId.isBlank()) {
            throw new IllegalArgumentException("A target space is required");
        }
        String target = spaceId.trim();
        if (!isCanonicalSpace(target)) {
            // Administrators skip the membership check below, so without this a typo'd
            // prefix or an unnormalised group path filed the whole cascade under a space
            // id nobody can ever hold — invisible to every team.
            throw new IllegalArgumentException("Not a space id: '" + sanitize(target) + "'. Use user:<principal> or team:<group>, as "
                    + "GET /workspaces lists them.");
        }
        if (!accessGuard.isAdmin() && !accessGuard.callerSpaces().spaces().contains(target)) {
            // Filing into a space you are not in would let anybody plant resources in a
            // team's workspace, and hand its members edit access to your work.
            throw new ForbiddenException("You can only move resources into a space you belong to");
        }

        List<ShareTarget> updated = new ArrayList<>();
        List<ShareTarget> skipped = new ArrayList<>();
        mutate(resourceId, updated, skipped, descriptor -> descriptor.setSpaceId(target), dryRun);
        for (String referenced : targets(resourceId, cascade)) {
            mutate(referenced, updated, skipped, descriptor -> descriptor.setSpaceId(target), dryRun);
        }
        return notifyChanged(new ShareResult(updated, skipped, dryRun));
    }

    /**
     * Transfers ownership. Administrators only — the point of this operation is to
     * recover a resource whose owner has left, which by definition cannot require
     * that owner's cooperation.
     */
    public ShareResult transferOwnership(String resourceId, String newOwnerId, String newSpaceId, boolean cascade) {
        return transferOwnership(resourceId, newOwnerId, newSpaceId, cascade, false);
    }

    /**
     * As {@link #transferOwnership(String, String, String, boolean)}; a dry run
     * writes nothing.
     */
    public ShareResult transferOwnership(String resourceId, String newOwnerId, String newSpaceId, boolean cascade, boolean dryRun) {
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
                if (!dryRun) {
                    writeBack(id, descriptor, loaded.version());
                }
                updated.add(new ShareTarget(id, descriptor.getName()));
            } catch (Exception e) {
                LOGGER.warnf("Could not transfer ownership of %s: %s", sanitize(id), e.getMessage());
                skipped.add(new ShareTarget(id, null));
            }
        }
        return notifyChanged(new ShareResult(updated, skipped, dryRun));
    }

    /**
     * Tells ownership-derived caches (prompt snippet scoping, for one) that access
     * changed, so a revoked or unpublished resource stops being served now rather
     * than when their TTL expires. A failing observer must not undo a sharing
     * change that has already been written, so it is logged and swallowed. A dry
     * run changed nothing, so it announces nothing.
     */
    private ShareResult notifyChanged(ShareResult result) {
        if (!result.dryRun() && !result.updated().isEmpty() && sharingChanged != null) {
            try {
                sharingChanged.fire(new SharingChangedEvent(result.updatedIds()));
            } catch (RuntimeException e) {
                LOGGER.warnf("A sharing-change observer failed; dependent caches converge on their TTL instead: %s", e.getMessage());
            }
        }
        return result;
    }

    /**
     * Tells the recipient. Best effort: the grant is already written, and a share
     * that succeeded must not be reported as failed because an inbox was down.
     */
    private void announce(String resourceId, String subject, AccessLevel level) {
        try {
            VersionedDescriptor root = loadOrNull(resourceId);
            DocumentDescriptor descriptor = root == null ? null : root.descriptor();
            notifications.onShared(resourceId, descriptor == null || descriptor.getResource() == null ? null : descriptor.getResource().toString(),
                    descriptor == null ? null : descriptor.getName(), subject, level);
        } catch (Exception e) {
            LOGGER.warnf("Could not announce the share of %s: %s", sanitize(resourceId), e.getMessage());
        }
    }

    /**
     * Whether {@code spaceId} is a space id in the one form the access index uses —
     * {@code user:<principal>} or {@code team:<normalised group>}, encoded. A form
     * that re-encodes differently would match no member's spaces.
     */
    static boolean isCanonicalSpace(String spaceId) {
        if (spaceId.startsWith(Subjects.USER_PREFIX) && spaceId.length() > Subjects.USER_PREFIX.length()) {
            return spaceId.equals(Subjects.personalSpace(Subjects.decode(spaceId.substring(Subjects.USER_PREFIX.length()))));
        }
        if (spaceId.startsWith(Subjects.TEAM_PREFIX) && spaceId.length() > Subjects.TEAM_PREFIX.length()) {
            return spaceId.equals(Subjects.teamSpace(Subjects.decode(spaceId.substring(Subjects.TEAM_PREFIX.length()))));
        }
        return false;
    }

    private Set<String> targets(String resourceId, boolean cascade) {
        return cascade ? graphResolver.referencedResourceIds(resourceId) : Set.of();
    }

    private void applyGrant(String id, String subject, AccessLevel level, String grantedBy, List<ShareTarget> updated, List<ShareTarget> skipped,
                            boolean dryRun) {
        mutate(id, updated, skipped, descriptor -> {
            List<ResourceGrant> grants = descriptor.getGrants() == null ? new ArrayList<>() : new ArrayList<>(descriptor.getGrants());
            // One grant per subject: re-sharing at a different level replaces rather than
            // accumulates, so revoking once is enough to actually revoke.
            grants.removeIf(grant -> grant == null || subject.equals(grant.getSubject()));
            grants.add(new ResourceGrant(subject, level.name(), grantedBy, new Date(System.currentTimeMillis())));
            descriptor.setGrants(grants);
        }, dryRun);
    }

    private void applyRevoke(String id, String subject, List<ShareTarget> updated, List<ShareTarget> skipped, boolean dryRun) {
        mutate(id, updated, skipped, descriptor -> {
            if (descriptor.getGrants() == null) {
                return;
            }
            List<ResourceGrant> grants = new ArrayList<>(descriptor.getGrants());
            grants.removeIf(grant -> grant == null || subject.equals(grant.getSubject()));
            descriptor.setGrants(grants);
        }, dryRun);
    }

    private void applyVisibility(String id, ResourceVisibility visibility, List<ShareTarget> updated, List<ShareTarget> skipped,
                                 boolean dryRun) {
        mutate(id, updated, skipped, descriptor -> descriptor.setVisibility(visibility.wireName()), dryRun);
    }

    /**
     * Loads, checks the caller may re-share, mutates, and writes back — rebuilding
     * the access index every time, because a descriptor whose structured fields and
     * index disagree is invisible in listings it should appear in.
     */
    private void mutate(String id, List<ShareTarget> updated, List<ShareTarget> skipped, Consumer<DocumentDescriptor> change, boolean dryRun) {
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
        if (dryRun) {
            // Everything up to the write — including the ownership check that decides
            // updated versus skipped — runs for a preview, so a preview and the real
            // change report the same lists.
            updated.add(new ShareTarget(id, descriptor.getName()));
            return;
        }
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
