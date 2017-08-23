package ai.labs.utilities;

import java.util.List;

/**
 * @author ginccc
 */
public final class CollectionUtilities {
    private CollectionUtilities() {
        //utility class constructor
    }

    public static void addAllWithoutDuplicates(List<String> collection, List<String> addTo) {
        for (String value : addTo) {
            if (!collection.contains(value)) {
                collection.add(value);
            }
        }
    }
}
