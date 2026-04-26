/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.utils;

import java.util.List;

/**
 * @author ginccc
 */
public final class CollectionUtilities {
    private CollectionUtilities() {
        // utility class constructor
    }

    public static void addAllWithoutDuplicates(List<String> collection, List<String> addTo) {
        addTo.stream().filter(value -> !collection.contains(value)).forEach(collection::add);
    }
}
