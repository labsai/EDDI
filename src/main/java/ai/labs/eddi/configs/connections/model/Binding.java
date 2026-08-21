/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.connections.model;

/**
 * Whose credential a connection resolves.
 * <p>
 * This one field is what makes "an org-wide Amplitude key" and "each end user's
 * own Google Drive" the same system rather than two features. It is a per-
 * connection config choice, never a global mode.
 */
public enum Binding {

    /**
     * One grant, shared by every user of every agent that references the
     * connection. The right answer for analytics keys, service accounts and
     * anything where the agent acts as itself rather than as a person.
     */
    SERVICE,

    /**
     * The calling user's own grant. Resolution fails closed when there is no
     * <em>verified</em> principal — a scheduled turn, a trigger, or a caller whose
     * identity is only self-asserted — rather than falling back to the service
     * grant, because sending the wrong authority is worse than sending none.
     */
    PER_USER
}
