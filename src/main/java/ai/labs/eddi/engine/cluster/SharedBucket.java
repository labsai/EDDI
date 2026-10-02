/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.cluster;

import java.time.Duration;

/**
 * The shape of one shared bucket: its logical name (prefixed with
 * {@code eddi.nats.prefix} on the server), the age after which an untouched
 * value expires, and the largest value it accepts.
 *
 * @param name
 *            logical name, upper case, e.g. {@code LEASES}
 * @param ttl
 *            server-side expiry of a value that is not rewritten
 * @param maxValueSize
 *            bytes; {@code -1} for the server default
 */
public record SharedBucket(String name, Duration ttl, int maxValueSize) {

    public static final SharedBucket LEASES = new SharedBucket("LEASES", Duration.ofSeconds(20), 4096);
    public static final SharedBucket NODES = new SharedBucket("NODES", Duration.ofSeconds(30), 8192);

    public SharedBucket {
        if (!name.matches("[A-Z0-9_]+")) {
            throw new IllegalArgumentException("bucket name must be [A-Z0-9_]+: " + name);
        }
    }

    public SharedBucket withTtl(Duration newTtl) {
        return new SharedBucket(name, newTtl, maxValueSize);
    }
}
