/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.security.spaces.rest.model;

import java.util.List;

/**
 * What the caller's client needs to know about workspaces before drawing
 * anything.
 *
 * <h3>Why a client cannot work this out for itself</h3> Whether enforcement is
 * on is a server setting with no wire evidence: a deployment with workspaces
 * disabled returns descriptors that look exactly like a deployment where
 * everything predates ownership. A UI guessing from that shows a Share action
 * that cannot work, which is worse than not offering it.
 * <p>
 * The space list is served rather than derived from the token for the same
 * reason. A client that re-implements the subject encoding will eventually
 * encode one differently, and that failure is silent — it selects a space
 * matching nothing, which reads as "you have no agents" rather than as an
 * error. Serving the same set the listing scope is built from removes the
 * second source of truth.
 *
 * @param enabled
 *            whether enforcement is active. False means every caller sees
 *            everything and sharing is inert, so a client should hide sharing
 *            controls entirely rather than offer ones that do nothing.
 * @param principal
 *            the caller's principal name, or {@code null} when anonymous. This
 *            is the value stamped as {@code ownerId}, so it is what a client
 *            must compare against to decide "this is mine" — not a display name
 *            from the token, which may differ.
 * @param defaultSpace
 *            the space new resources by this caller are filed in, or
 *            {@code null} when anonymous.
 * @param spaces
 *            every space the caller can reach, personal first. Empty when
 *            anonymous — the backend resolves an anonymous caller to no spaces
 *            at all, so a client must not offer a space filter the server will
 *            not honour.
 * @param seesEverything
 *            whether this caller's reach is unlimited (an administrator, or
 *            enforcement disabled). A client can use this to explain why a
 *            listing shows other people's resources.
 *
 * @author ginccc
 */
public record WorkspaceInfo(boolean enabled,
        String principal,
        String defaultSpace,
        List<SpaceInfo> spaces,
        boolean seesEverything) {

    public WorkspaceInfo {
        spaces = spaces == null ? List.of() : List.copyOf(spaces);
    }
}
