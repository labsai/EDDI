/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.datastore;

import java.util.List;

/**
 * The answer of a bounded reverse lookup — which resource versions name a given
 * URI — split by where the hit came from, without reading any of them.
 *
 * @param live
 *            versions found in the current collection: each is its resource's
 *            current version, and the resource is not deleted
 * @param historical
 *            versions found in history: older versions, and versions of
 *            resources that were deleted since. They still matter where
 *            something pins them (a deployed agent version names exact workflow
 *            versions)
 */
public record ReferringVersions(List<IResourceStore.IResourceId> live, List<IResourceStore.IResourceId> historical) {

    public ReferringVersions {
        live = List.copyOf(live);
        historical = List.copyOf(historical);
    }

    public static ReferringVersions none() {
        return new ReferringVersions(List.of(), List.of());
    }
}
