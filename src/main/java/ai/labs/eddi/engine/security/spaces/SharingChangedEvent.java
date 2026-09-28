/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.security.spaces;

import java.util.List;

/**
 * Fired (synchronously, as a CDI event) after {@link ResourceSharingService}
 * has written a change to who may reach one or more resources — a grant, a
 * revoke, a visibility change or an ownership transfer.
 * <p>
 * Lets caches that hold a decision derived from descriptor ownership drop it at
 * once instead of serving a withdrawn share until their TTL runs out — without
 * this package depending on every such cache. Delivered on the node that made
 * the change only; other nodes still converge through their caches' TTLs.
 *
 * @param resourceIds
 *            the resources whose descriptors were rewritten
 */
public record SharingChangedEvent(List<String> resourceIds) {

    public SharingChangedEvent {
        resourceIds = resourceIds == null ? List.of() : List.copyOf(resourceIds);
    }
}
