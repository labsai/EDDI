/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.security.spaces.rest.model;

/**
 * One space a caller can reach, ready to render.
 *
 * @param id
 *            the wire id — {@code user:<principal>} or {@code team:<group>} —
 *            to send back as the {@code space} query parameter. Opaque to the
 *            client: it carries escaping the client must not re-derive.
 * @param kind
 *            {@code personal} or {@code team}, so a client can order and icon
 *            them without parsing {@link #id()}.
 * @param label
 *            the decoded name to show a human. Never the raw id.
 *
 * @author ginccc
 */
public record SpaceInfo(String id, String kind, String label) {

    /** A caller's own space. */
    public static final String KIND_PERSONAL = "personal";

    /** A space backed by a group the caller belongs to. */
    public static final String KIND_TEAM = "team";
}
