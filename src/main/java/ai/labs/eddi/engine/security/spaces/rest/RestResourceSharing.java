/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.security.spaces.rest;

import ai.labs.eddi.configs.descriptors.model.AccessLevel;
import ai.labs.eddi.configs.descriptors.model.ResourceVisibility;
import ai.labs.eddi.engine.security.spaces.ResourceAccessGuard;
import ai.labs.eddi.engine.security.spaces.ResourceSharingService;
import ai.labs.eddi.engine.security.spaces.Subjects;
import ai.labs.eddi.engine.security.spaces.directory.UserDirectory;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.core.Response;

/**
 * @author ginccc
 */
@ApplicationScoped
public class RestResourceSharing implements IRestResourceSharing {

    private final ResourceSharingService sharingService;
    private final UserDirectory directory;
    private final ResourceAccessGuard accessGuard;

    @Inject
    public RestResourceSharing(ResourceSharingService sharingService, UserDirectory directory, ResourceAccessGuard accessGuard) {
        this.sharingService = sharingService;
        this.directory = directory;
        this.accessGuard = accessGuard;
    }

    @Override
    public Response readShares(String id) {
        return Response.ok(sharingService.describe(id)).build();
    }

    @Override
    public Response share(String id, String subject, String level, Boolean cascade, Boolean dryRun) {
        // Resolved against the user directory rather than taken as typed: a grant
        // for a principal nobody holds is stored, answered with 200, and reaches
        // nobody — which is what sharing with an email address used to do.
        String normalizedSubject = directory.resolveSubject(subject, accessGuard.callerSpaces(), accessGuard.isAdmin());
        AccessLevel accessLevel = AccessLevel.parseOrNull(level);
        if (accessLevel == null) {
            throw new BadRequestException("level must be one of USE, VIEW, EDIT, OWN — was '" + level + "'");
        }
        return Response.ok(sharingService.share(id, normalizedSubject, accessLevel, !Boolean.FALSE.equals(cascade), Boolean.TRUE.equals(dryRun)))
                .build();
    }

    @Override
    public Response revoke(String id, String subject, Boolean cascade, Boolean dryRun) {
        return Response.ok(sharingService.revoke(id, requireSubject(subject), !Boolean.FALSE.equals(cascade), Boolean.TRUE.equals(dryRun)))
                .build();
    }

    @Override
    public Response setVisibility(String id, String visibility, Boolean cascade, Boolean dryRun) {
        ResourceVisibility parsed = ResourceVisibility.parseOrNull(visibility);
        if (parsed == null) {
            throw new BadRequestException("visibility must be one of private, space, published — was '" + visibility + "'");
        }
        return Response.ok(sharingService.setVisibility(id, parsed, !Boolean.FALSE.equals(cascade), Boolean.TRUE.equals(dryRun))).build();
    }

    @Override
    public Response moveToSpace(String id, String spaceId, Boolean cascade, Boolean dryRun) {
        if (spaceId == null || spaceId.isBlank()) {
            throw new BadRequestException("spaceId is required — one of the spaces GET /workspaces lists");
        }
        return Response.ok(sharingService.moveToSpace(id, spaceId.trim(), !Boolean.FALSE.equals(cascade), Boolean.TRUE.equals(dryRun))).build();
    }

    @Override
    public Response transferOwnership(String id, String ownerId, String spaceId, Boolean cascade, Boolean dryRun) {
        if (ownerId == null || ownerId.isBlank()) {
            throw new BadRequestException("ownerId is required");
        }
        String owner = directory.resolvePrincipal(ownerId);
        return Response.ok(sharingService.transferOwnership(id, owner, spaceId, !Boolean.FALSE.equals(cascade), Boolean.TRUE.equals(dryRun)))
                .build();
    }

    /**
     * Normalises and validates a subject <em>without</em> consulting the directory
     * — for revoking, which must work for a grant that names nobody the directory
     * knows, since removing such a grant is exactly the fix.
     * <p>
     * A bare name is accepted and read as a user, because that is what people type
     * and rejecting it would make the API needlessly ceremonious. Everything else
     * must carry a known prefix — an unrecognised one is rejected rather than
     * guessed at, since a typo that silently became a subject nobody holds would
     * look exactly like a successful share.
     */
    private static String requireSubject(String subject) {
        if (subject == null || subject.isBlank()) {
            throw new BadRequestException("subject is required, e.g. 'user:alice@example.com' or 'team:engineering'");
        }
        String trimmed = subject.trim();
        if (trimmed.startsWith(Subjects.USER_PREFIX)) {
            String principal = trimmed.substring(Subjects.USER_PREFIX.length());
            String normalized = Subjects.user(principal);
            if (normalized == null) {
                throw new BadRequestException("subject 'user:' needs a principal name after the prefix");
            }
            return normalized;
        }
        if (trimmed.startsWith(Subjects.TEAM_PREFIX)) {
            String group = trimmed.substring(Subjects.TEAM_PREFIX.length());
            String normalized = Subjects.team(group);
            if (normalized == null) {
                throw new BadRequestException("subject 'team:' needs a group name after the prefix");
            }
            return normalized;
        }
        if (trimmed.contains(":")) {
            throw new BadRequestException("subject must start with 'user:' or 'team:' — was '" + trimmed + "'");
        }
        return Subjects.user(trimmed);
    }
}
