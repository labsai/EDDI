package ai.labs.utilities;

import java.util.Collection;

/**
 * @author ginccc
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
