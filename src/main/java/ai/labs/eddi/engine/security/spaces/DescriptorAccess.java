/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.security.spaces;

import ai.labs.eddi.configs.descriptors.model.AccessLevel;
import ai.labs.eddi.configs.descriptors.model.DocumentDescriptor;
import ai.labs.eddi.configs.descriptors.model.ResourceGrant;
import ai.labs.eddi.configs.descriptors.model.ResourceVisibility;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The access policy itself, expressed twice and only twice: as a level for one
 * caller against one descriptor ({@link #effectiveLevel}), and as the
 * materialised token index a listing query filters on ({@link #rebuildIndex}).
 * <p>
 * Both are pure functions of the descriptor's {@code ownerId}, {@code spaceId},
 * {@code visibility} and {@code grants}. Keeping them in one class is what
 * makes it checkable that the two agree — the failure mode of a materialised
 * ACL is a listing that admits something the read path denies, or the reverse.
 *
 * <h3>They may disagree in exactly one direction</h3> The index is rebuilt on
 * write, so a descriptor written before this code existed has none. A listing
 * may therefore <em>omit</em> a resource whose {@link #effectiveLevel} would
 * admit it, until that descriptor is next written or
 * {@code WorkspaceBackfillMigration} runs. The reverse — listed but not
 * readable — would be a leak, and cannot happen: every token the index contains
 * is one {@link #effectiveLevel} also honours.
 *
 * @author ginccc
 */
public final class DescriptorAccess {

    private DescriptorAccess() {
        // utility class
    }

    /**
     * What {@code caller} may do with {@code descriptor}, or {@code null} for no
     * access at all.
     *
     * @param descriptor
     *            the resource's descriptor; {@code null} yields {@code null}
     * @param caller
     *            the caller's identity — principals, spaces and subjects
     * @param admitLegacy
     *            whether an unowned, space-less descriptor (data predating
     *            ownership) is admitted. Operator policy — see
     *            {@code eddi.workspaces.legacy-visibility}.
     */
    public static AccessLevel effectiveLevel(DocumentDescriptor descriptor, CallerSpaces caller, boolean admitLegacy) {
        if (descriptor == null || caller == null) {
            return null;
        }

        if (isUnowned(descriptor)) {
            // Legacy data: no owner, no space, nothing to compare against. Treated the way
            // OwnershipValidator.requireOwnerOrAdmin already treats an unowned resource, so
            // an upgrade does not hide every pre-existing agent — but an operator can close
            // it by setting eddi.workspaces.legacy-visibility=admin-only.
            return admitLegacy ? AccessLevel.OWN : null;
        }

        AccessLevel best = null;

        String ownerId = descriptor.getOwnerId();
        boolean callerIsOwner = ownerId != null && !ownerId.isBlank() && caller.isSelf(ownerId);
        if (callerIsOwner) {
            best = AccessLevel.OWN;
        }

        ResourceVisibility visibility = descriptor.resourceVisibility();
        if (visibility == null) {
            // A descriptor carrying an owner or a space but no visibility predates the
            // field, or came from a client that omitted it. Default to `space`, which is
            // what a newly created resource gets — not `published`, which would widen
            // access as a side effect of a missing field.
            visibility = ResourceVisibility.space;
        }

        if (visibility == ResourceVisibility.published) {
            best = higher(best, AccessLevel.VIEW);
        }

        if (visibility == ResourceVisibility.space) {
            String spaceId = descriptor.getSpaceId();
            if (spaceId != null && !spaceId.isBlank() && caller.spaces().contains(spaceId)) {
                // A personal space has one member, so this is the owner again; a team space is
                // where it actually widens. EDIT rather than OWN for a teammate: deleting or
                // re-sharing someone else's agent stays with whoever made it.
                best = higher(best, callerIsOwner ? AccessLevel.OWN : AccessLevel.EDIT);
            }
        }

        List<ResourceGrant> grants = descriptor.getGrants();
        if (grants != null) {
            for (ResourceGrant grant : grants) {
                if (grant == null || grant.getSubject() == null) {
                    continue;
                }
                if (caller.subjects().contains(grant.getSubject())) {
                    best = higher(best, grant.accessLevel());
                }
            }
        }

        return best;
    }

    /**
     * Whether the descriptor records no ownership at all — the shape of every
     * descriptor written before this feature existed, and of the rows the backfill
     * migration stamps with {@link Subjects#LEGACY}.
     */
    public static boolean isUnowned(DocumentDescriptor descriptor) {
        if (descriptor == null) {
            return false;
        }
        boolean noOwner = descriptor.getOwnerId() == null || descriptor.getOwnerId().isBlank();
        boolean noSpace = descriptor.getSpaceId() == null || descriptor.getSpaceId().isBlank()
                || Subjects.LEGACY.equals(descriptor.getSpaceId());
        return noOwner && noSpace;
    }

    /**
     * Recomputes {@link DocumentDescriptor#getAccessIndex()} from the structured
     * fields. Call after <em>any</em> change to owner, space, visibility or grants
     * — a descriptor written without this leaves the resource missing from listings
     * it should appear in.
     *
     * @return the same descriptor, for chaining
     */
    public static DocumentDescriptor rebuildIndex(DocumentDescriptor descriptor) {
        if (descriptor == null) {
            return null;
        }
        descriptor.setAccessIndex(buildIndex(descriptor));
        return descriptor;
    }

    /** The token index {@link #rebuildIndex} stores. Visible for testing. */
    public static String buildIndex(DocumentDescriptor descriptor) {
        Set<String> tokens = new LinkedHashSet<>();

        if (isUnowned(descriptor)) {
            tokens.add(Subjects.LEGACY);
        }

        String ownerId = descriptor.getOwnerId();
        if (ownerId != null && !ownerId.isBlank()) {
            tokens.add(Subjects.OWNER_TOKEN_PREFIX + Subjects.encode(ownerId));
        }

        ResourceVisibility visibility = descriptor.resourceVisibility();
        if (visibility == null) {
            visibility = ResourceVisibility.space;
        }

        if (visibility == ResourceVisibility.published) {
            tokens.add(Subjects.TOKEN_ALL);
        }

        String spaceId = descriptor.getSpaceId();
        if (visibility == ResourceVisibility.space && spaceId != null && !spaceId.isBlank() && !Subjects.LEGACY.equals(spaceId)) {
            tokens.add(Subjects.SPACE_TOKEN_PREFIX + Subjects.encode(spaceId));
        }

        List<ResourceGrant> grants = descriptor.getGrants();
        if (grants != null) {
            for (ResourceGrant grant : grants) {
                if (grant != null && grant.getSubject() != null && !grant.getSubject().isBlank() && grant.accessLevel() != null) {
                    tokens.add(grant.getSubject());
                }
            }
        }

        if (tokens.isEmpty()) {
            // Only reachable for a descriptor with an owner-less non-legacy space and
            // private visibility. An empty index matches no listing at all, and a resource
            // nobody can list is a worse outcome than one only admins see.
            tokens.add(Subjects.LEGACY);
        }

        StringBuilder out = new StringBuilder();
        out.append(Subjects.DELIMITER);
        for (String token : tokens) {
            out.append(token).append(Subjects.DELIMITER);
        }
        return out.toString();
    }

    /**
     * Every index token that would admit {@code caller}. This is the OR-group a
     * listing query is built from, and it must stay the exact mirror of
     * {@link #effectiveLevel}.
     */
    public static List<String> admittingTokens(CallerSpaces caller, boolean admitLegacy) {
        List<String> tokens = new ArrayList<>();
        tokens.add(Subjects.TOKEN_ALL);
        if (admitLegacy) {
            tokens.add(Subjects.LEGACY);
        }
        if (caller == null) {
            return tokens;
        }
        for (String self : caller.selfPrincipals()) {
            tokens.add(Subjects.OWNER_TOKEN_PREFIX + Subjects.encode(self));
        }
        for (String space : caller.spaces()) {
            tokens.add(Subjects.SPACE_TOKEN_PREFIX + Subjects.encode(space));
        }
        tokens.addAll(caller.subjects());
        return tokens;
    }

    private static AccessLevel higher(AccessLevel a, AccessLevel b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        return a.ordinal() >= b.ordinal() ? a : b;
    }
}
