/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.descriptors.model;

/**
 * How much of a shared resource a subject may do something with.
 * <p>
 * Ordered least to most: each level implies every level below it, which is what
 * {@link #includes} tests. The ordinal is deliberately the ordering — do not
 * reorder the constants without also fixing every persisted grant, since grants
 * are stored by name and compared by rank.
 *
 * <h3>Why USE and VIEW are separate</h3> An agent's configuration names its
 * system prompt, its tools, its MCP servers and its vault references. Letting a
 * colleague <em>talk to</em> an agent is a different act from letting them
 * <em>read how it was built</em>, and the first is by far the more common
 * request. A model with a single "shared" flag has to answer both with the same
 * permission, and in practice answers them with the more generous one.
 *
 * @author ginccc
 */
public enum AccessLevel {

    /**
     * Start conversations with the deployed agent, and see its name and
     * description. Grants nothing about the configuration itself — not the agent
     * document, not any workflow or rule set it references.
     */
    USE,

    /**
     * Read the resource and the configuration graph beneath it. Does not permit any
     * modification, deployment, or re-sharing.
     */
    VIEW,

    /**
     * Everything {@link #VIEW} permits, plus updating the resource and deploying
     * it. Deliberately excludes deletion and re-sharing: those change who can reach
     * the resource at all, which stays with the owner.
     */
    EDIT,

    /**
     * Full control, including delete and changing who else has access. Held by the
     * creator, and transferable by an administrator.
     */
    OWN;

    /**
     * Whether holding this level satisfies a requirement for {@code required}.
     *
     * @param required
     *            the level the operation demands
     * @return {@code true} when this level is at least as permissive
     */
    public boolean includes(AccessLevel required) {
        return required != null && this.ordinal() >= required.ordinal();
    }

    /**
     * Parses a stored level, tolerating case and returning {@code null} rather than
     * throwing for a value written by a newer version of EDDI.
     * <p>
     * A grant whose level cannot be parsed must not be treated as {@link #OWN} by
     * accident, and must not blow up a listing either — callers treat {@code null}
     * as "grants nothing".
     */
    public static AccessLevel parseOrNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        for (AccessLevel level : values()) {
            if (level.name().equalsIgnoreCase(value.trim())) {
                return level;
            }
        }
        return null;
    }
}
