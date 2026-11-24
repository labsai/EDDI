/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.org;

import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The id of an organization, the customer isolation boundary (ADR 0001, LOCK
 * 1).
 * <p>
 * An id matches {@code ^[a-z0-9][a-z0-9-]{1,62}$}. It is immutable and opaque,
 * and it is never reused after the org is deleted. {@link #DEFAULT} is the one
 * org every single-tenant deployment has; it is a constant and cannot be
 * configured. The ids in {@link #RESERVED} can never be created;
 * {@code default} exists only because every deployment already has it.
 * <p>
 * An org id never appears inside a public id: resource, conversation and other
 * ids stay globally unique on their own.
 */
public record OrgId(String value) {

    private static final Pattern FORMAT = Pattern.compile("^[a-z0-9][a-z0-9-]{1,62}$");

    /**
     * The org of every single-tenant deployment, and the first org after
     * enablement.
     */
    public static final OrgId DEFAULT = new OrgId("default");

    /**
     * Ids no org can be created with. {@code default} pre-exists and is listed too.
     */
    public static final Set<String> RESERVED = Set.of("default", "platform", "current", "system", "eddi", "admin", "api");

    public OrgId {
        Objects.requireNonNull(value, "org id");
        if (!FORMAT.matcher(value).matches()) {
            throw new IllegalArgumentException("Invalid org id: it must match " + FORMAT.pattern());
        }
    }

    /** Parses and validates an org id. */
    public static OrgId of(String value) {
        return new OrgId(value);
    }

    /** True when an org with this id may be created: valid and not reserved. */
    public static boolean isCreatable(String value) {
        return value != null && FORMAT.matcher(value).matches() && !RESERVED.contains(value);
    }

    public boolean isDefault() {
        return DEFAULT.equals(this);
    }

    @Override
    public String toString() {
        return value;
    }
}
