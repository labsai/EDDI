/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.secrets;

import ai.labs.eddi.secrets.model.SecretMetadata;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Pattern;

/**
 * The rules an {@code allowedAgents} list is held to when it arrives in a REST
 * body — shared by the tenant-level secret store and the space-scoped one so
 * the two cannot drift on what a grant may look like, or on when one is
 * required. When an {@code allowedHosts} list is required is decided here too,
 * for the same reason; what an entry may look like is
 * {@link VaultHostAllowlist}'s.
 */
public final class VaultGrantRequests {

    private static final Pattern VALID_ID = Pattern.compile("[a-zA-Z0-9._\\-]{1,128}");

    /**
     * Upper bound on a grant list. Not a security control — an admin who can edit
     * grants can already delete the vault — but an unbounded list gets written by
     * mistake far more often than on purpose, and every entry is walked on every
     * deployment's grant check.
     */
    public static final int MAX_ALLOWED_AGENTS = 500;

    private VaultGrantRequests() {
    }

    /**
     * The grant a create must carry under {@code eddi.vault.grant-enforcement}.
     * <p>
     * Under {@code enforce} a secret stored without a list used to be granted to
     * every agent by a default three layers down, which is the one grant an
     * operator running in enforce mode has said they do not want applied silently.
     * So a create without a list is refused, as {@code PUT …/grant} already refuses
     * an edit without one: the wildcard is still available, as {@code ["*"]},
     * written out. {@code warn} and {@code off} keep the old convenience — those
     * modes have opted out of the control.
     * <p>
     * Only a <em>create</em>. On a rotation an omitted list keeps the stored grant,
     * which is the behaviour the Manager's rotate dialog relies on and which never
     * widens anything.
     *
     * @throws IllegalArgumentException
     *             with the message the 400 carries
     */
    public static void requireGrantOnCreate(VaultGrantGate.Mode mode, List<String> allowedAgents) {
        if (mode != VaultGrantGate.Mode.ENFORCE) {
            return;
        }
        if (allowedAgents == null) {
            throw new IllegalArgumentException("allowedAgents is required when creating a secret under eddi.vault.grant-enforcement=enforce: "
                    + "list the agent ids that may use it, or send [\"*\"] to grant every agent explicitly. Nothing is granted by default.");
        }
        if (allowedAgents.isEmpty()) {
            throw new IllegalArgumentException("allowedAgents must not be empty: an empty list means every agent. Send [\"*\"] if that is what "
                    + "you intend, or list the agents that may use this secret.");
        }
    }

    /**
     * The destination list a create must carry under
     * {@code eddi.vault.grant-enforcement}, the host-side twin of
     * {@link #requireGrantOnCreate}.
     * <p>
     * {@code allowedAgents} limits which agents may reference a secret, but every
     * editor can write a configuration that sends a referenced secret to a URL of
     * their choosing, so a secret with no {@code allowedHosts} goes wherever the
     * next config edit points it (security review 2026-10-17, H1). Under
     * {@code enforce} a create therefore says where the secret may go, or says
     * {@code ["*"]} to allow any host on purpose. {@code []} is refused too: every
     * layer reads it as unrestricted, so a list filtered down to nothing must not
     * create an open secret with a 201. {@code warn} and {@code off} keep the old
     * behaviour.
     * <p>
     * Only a <em>create</em>: a rotation that omits the list keeps the stored one.
     *
     * @param allowedHosts
     *            the list exactly as the request body carried it, before
     *            {@link VaultHostAllowlist#requestedHosts} turned {@code ["*"]}
     *            into the stored empty list
     * @throws IllegalArgumentException
     *             with the message the 400 carries
     */
    public static void requireHostsOnCreate(VaultGrantGate.Mode mode, List<String> allowedHosts) {
        if (mode != VaultGrantGate.Mode.ENFORCE) {
            return;
        }
        if (allowedHosts == null) {
            throw new IllegalArgumentException("allowedHosts is required when creating a secret under eddi.vault.grant-enforcement=enforce: "
                    + "list the host or host:port values it may be sent to, or send [\"*\"] to allow any host explicitly. Without it, "
                    + "any configuration that references the secret could send it anywhere.");
        }
        if (allowedHosts.isEmpty()) {
            throw new IllegalArgumentException("allowedHosts must not be empty: an empty list means any host. Send [\"*\"] if that is what "
                    + "you intend, or list the hosts this secret may be sent to.");
        }
    }

    /**
     * A grant list from a request body, validated and de-duplicated. {@code null}
     * comes back as {@code null} — whether that is allowed is the caller's rule
     * ({@link #requireGrantOnCreate}, or "required" on a grant edit).
     * <p>
     * An <em>empty</em> list is refused. Every other layer reads {@code []} as
     * unrestricted, so accepting it would let a client that filtered its list down
     * to nothing open the secret to everyone with a 200. The only way to say "all
     * agents" is {@code ["*"]}, out loud.
     *
     * @throws IllegalArgumentException
     *             with an operator-readable message
     */
    public static List<String> validatedGrant(List<String> allowedAgents) {
        if (allowedAgents == null) {
            return null;
        }
        if (allowedAgents.isEmpty()) {
            throw new IllegalArgumentException("allowedAgents must not be empty: an empty list means every agent. "
                    + "Send [\"*\"] if that is what you intend, or list the agents that may use this secret.");
        }
        if (allowedAgents.size() > MAX_ALLOWED_AGENTS) {
            throw new IllegalArgumentException("allowedAgents must not contain more than " + MAX_ALLOWED_AGENTS + " entries.");
        }
        // LinkedHashSet: duplicates dropped, operator's order kept, so the list reads
        // back the way it was written.
        var distinct = new LinkedHashSet<String>();
        for (String agentId : allowedAgents) {
            if (agentId == null || agentId.isBlank()) {
                throw new IllegalArgumentException("allowedAgents must not contain blank entries.");
            }
            String trimmed = agentId.trim();
            if (!SecretMetadata.WILDCARD_AGENT.equals(trimmed) && !VALID_ID.matcher(trimmed).matches()) {
                throw new IllegalArgumentException(
                        "allowedAgents entries must be agent IDs matching [a-zA-Z0-9._-]{1,128}, or \"*\" for all agents. Got: " + trimmed
                                + ". If this entry is already on the secret's grant, leave it out of the replacement list — it can "
                                + "never match an agent ID.");
            }
            distinct.add(trimmed);
        }
        return new ArrayList<>(distinct);
    }
}
