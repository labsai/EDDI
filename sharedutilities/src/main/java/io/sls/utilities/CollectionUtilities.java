package io.sls.utilities;

import java.util.Collection;

/**
 * Copyright by Spoken Language System. All rights reserved.
 * User: jarisch
 * Date: 26.11.12
 * Time: 16:54
 */
public final class CollectionUtilities {
    private CollectionUtilities() {
        //utility class constructor
    }

    public static void addAllWithoutDuplicates(Collection collection, Collection addTo) {
        for (Object obj : addTo) {
            if (!collection.contains(obj)) {
                collection.add(obj);
            }
        }
    }
}
