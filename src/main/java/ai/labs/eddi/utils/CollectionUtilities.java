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

    /**
     * Append every element of {@code source} that {@code target} does not already
     * contain, in order, mutating {@code target}.
     * <p>
     * The parameters were named {@code (collection, addTo)} while the body added
     * <em>into</em> the first from the second — the reverse of what the names said.
     * Both are {@code List<String>}, so the compiler cannot catch a caller that
     * believes the names; every existing caller happens to pass (target, source),
     * which is the only reason nothing was broken.
     */
    public static void addAllWithoutDuplicates(List<String> target, List<String> source) {
        source.stream().filter(value -> !target.contains(value)).forEach(target::add);
    }
}
