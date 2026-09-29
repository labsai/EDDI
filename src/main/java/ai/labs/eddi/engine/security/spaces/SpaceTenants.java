/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.security.spaces;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.regex.Pattern;

/**
 * The vault and global-variable tenant that belongs to a space.
 *
 * <h3>Why a derived id and not the space id itself</h3> Both stores accept
 * tenant ids of {@code [a-zA-Z0-9._-]} only, and a reference to one appears
 * verbatim in agent configuration — {@code ${vault:<tenant>/openai}}. A space
 * id such as {@code team:eng/backend} or {@code user:alice@example.com} fits
 * neither. So each space gets a tenant id that is readable where it can be
 * ({@code t.engineering.3f9a1c2b7d04e615}) and unique always: the slug drops
 * characters the stores refuse, which could make two spaces look alike, so
 * sixteen hex characters (64 bits) of a hash of the <em>exact</em> space id are
 * always appended. Eight were not enough: two principals that slug alike
 * ({@code alice.smith@corp.com}, {@code alice@smith.corp.com}) collided after
 * about 2^32 attempts, and a collision is membership of the other tenant. Two
 * spaces can therefore never share a tenant, which is the property the access
 * checks on these tenants rely on.
 *
 * <h3>Why this is enough to authorize</h3> The id is public — it is in every
 * reference — so knowing it grants nothing. What authorizes is membership:
 * writing a space's secrets or variables requires being in the space, and
 * deploying an agent that references them requires the deployer to be in it
 * too. Both checks compute the caller's own tenants from their spaces and
 * compare; nothing ever parses a space back out of a tenant id.
 */
public final class SpaceTenants {

    /** Tenant ids this class produces, and only those. */
    private static final Pattern SPACE_TENANT = Pattern.compile("^[tu]\\.[A-Za-z0-9_-]{1,48}\\.[0-9a-f]{16}$");

    private static final int MAX_SLUG = 48;

    private SpaceTenants() {
    }

    /**
     * The tenant id for a space.
     *
     * @param spaceId
     *            {@code user:<principal>} or {@code team:<group>}, as
     *            {@link Subjects} builds them
     * @throws IllegalArgumentException
     *             for anything else
     */
    public static String tenantFor(String spaceId) {
        if (spaceId == null) {
            throw new IllegalArgumentException("A space is required");
        }
        String kind;
        String name;
        if (spaceId.startsWith(Subjects.TEAM_PREFIX)) {
            kind = "t";
            name = Subjects.decode(spaceId.substring(Subjects.TEAM_PREFIX.length()));
        } else if (spaceId.startsWith(Subjects.USER_PREFIX)) {
            kind = "u";
            name = Subjects.decode(spaceId.substring(Subjects.USER_PREFIX.length()));
        } else {
            throw new IllegalArgumentException("Not a space id: " + spaceId);
        }
        return kind + "." + slug(name) + "." + hash(spaceId);
    }

    /**
     * Whether a tenant id is one {@link #tenantFor} produces — a space's, as
     * opposed to {@code default} or an administrator-made tenant.
     */
    public static boolean isSpaceTenant(String tenantId) {
        return tenantId != null && SPACE_TENANT.matcher(tenantId).matches();
    }

    private static String slug(String name) {
        StringBuilder out = new StringBuilder(Math.min(name.length(), MAX_SLUG));
        for (int i = 0; i < name.length() && out.length() < MAX_SLUG; i++) {
            char c = name.charAt(i);
            boolean safe = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_' || c == '-';
            out.append(safe ? c : '_');
        }
        return out.isEmpty() ? "_" : out.toString();
    }

    private static String hash(String spaceId) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(spaceId.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 8);
        } catch (NoSuchAlgorithmException e) {
            // Every JVM ships SHA-256; this cannot happen.
            throw new IllegalStateException(e);
        }
    }
}
