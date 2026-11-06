/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.userevents;

import ai.labs.eddi.engine.cluster.KvKeys;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Who may receive one event — decided where it is raised, from the record that
 * is authoritative for it (the conversation's owner, the discussion's roster),
 * and checked again for every connection at fan-out.
 * <p>
 * Principals travel as {@link KvKeys#sha256} hashes, the form the other
 * user-keyed cluster events already use, so a user id is never written into the
 * cluster event stream in clear. A connection matches on the hash of its own
 * principal.
 *
 * @param principalHashes
 *            hashed principals named by the record: the owner, a HUMAN member
 * @param approvers
 *            also every holder of {@code eddi-admin} or {@code eddi-approver} —
 *            the people who may decide any pending approval
 * @param admins
 *            also every holder of {@code eddi-admin} — the deployment's own
 *            notices
 * @param useResourceId
 *            a resource a principal-matched recipient must still be allowed to
 *            {@code USE} (with workspaces enforced), unless they are an admin:
 *            the agent of a paused 1:1 conversation, which its owner can resume
 *            only while they may run it. {@code null} for no such check
 * @param yourTurnHash
 *            for {@link UserEvent#KIND_TEAM_WAITING_FOR_HUMAN}: the hashed
 *            principal whose turn it is, so that recipient's copy says
 *            {@code yourTurn: true}; never sent to anyone
 */
public record UserEventAudience(Set<String> principalHashes, boolean approvers, boolean admins, String useResourceId, String yourTurnHash) {

    public UserEventAudience {
        principalHashes = principalHashes == null ? Set.of() : Set.copyOf(principalHashes);
    }

    /** The named principals, hashed; blank and null entries are skipped. */
    public static UserEventAudience principals(Collection<String> principals) {
        return new UserEventAudience(hashAll(principals), false, false, null, null);
    }

    /** One principal; empty when it is blank. */
    public static UserEventAudience principal(String principal) {
        return principals(principal == null ? List.of() : List.of(principal));
    }

    /** The deployment's administrators only. */
    public static UserEventAudience administrators() {
        return new UserEventAudience(Set.of(), false, true, null, null);
    }

    /** This audience, plus every admin and approver. */
    public UserEventAudience andApprovers() {
        return new UserEventAudience(principalHashes, true, admins, useResourceId, yourTurnHash);
    }

    /**
     * This audience, its principal matches also needing {@code USE} on a resource.
     */
    public UserEventAudience requiringUse(String resourceId) {
        return new UserEventAudience(principalHashes, approvers, admins, resourceId, yourTurnHash);
    }

    /** This audience, marking {@code principal}'s copy as their turn. */
    public UserEventAudience withYourTurn(String principal) {
        return new UserEventAudience(principalHashes, approvers, admins, useResourceId, principal == null || principal.isBlank()
                ? null
                : KvKeys.sha256(principal.trim()));
    }

    /** Whether nobody at all could receive it. */
    public boolean isEmpty() {
        return principalHashes.isEmpty() && !approvers && !admins;
    }

    static Set<String> hashAll(Collection<String> principals) {
        Set<String> hashes = new LinkedHashSet<>();
        if (principals != null) {
            principals.stream().filter(Objects::nonNull).map(String::trim).filter(p -> !p.isEmpty()).map(KvKeys::sha256).forEach(hashes::add);
        }
        return hashes;
    }

    Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("principals", new ArrayList<>(principalHashes));
        map.put("approvers", approvers);
        map.put("admins", admins);
        if (useResourceId != null) {
            map.put("useResourceId", useResourceId);
        }
        if (yourTurnHash != null) {
            map.put("yourTurn", yourTurnHash);
        }
        return map;
    }

    static UserEventAudience fromMap(Map<?, ?> map) {
        if (map == null) {
            return null;
        }
        Set<String> hashes = new LinkedHashSet<>();
        if (map.get("principals") instanceof Collection<?> list) {
            list.stream().filter(String.class::isInstance).map(String.class::cast).forEach(hashes::add);
        }
        return new UserEventAudience(hashes, Boolean.TRUE.equals(map.get("approvers")), Boolean.TRUE.equals(map.get("admins")),
                map.get("useResourceId") instanceof String s ? s : null, map.get("yourTurn") instanceof String t ? t : null);
    }
}
