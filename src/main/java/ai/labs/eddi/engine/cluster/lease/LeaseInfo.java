/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.cluster.lease;

/**
 * The current holder of a lease, as stored in the {@code LEASES} bucket.
 *
 * @param node
 *            holder's node id
 * @param boot
 *            holder's boot id (changes on every restart)
 * @param revision
 *            the stored revision (moves on every heartbeat)
 * @param since
 *            epoch millis when the holder acquired it
 */
public record LeaseInfo(String node, String boot, long revision, long since) {
}
