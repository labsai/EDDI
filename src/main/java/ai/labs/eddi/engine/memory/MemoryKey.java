/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.memory;

import java.util.Objects;

/**
 * Type-safe key for accessing data in
 * {@link IConversationMemory.IConversationStep}.
 * <p>
 * Binds a string key name to a Java type at compile time, eliminating unchecked
 * casts and providing discoverability via the {@link MemoryKeys} registry.
 * <p>
 * The {@code isPublic} flag is copied onto the data stored under this key (see
 * {@link IData#isPublic()}). It is a marker only and does not decide what a
 * client response includes; that is chosen by key prefix when the conversation
 * is projected.
 *
 * @param <T>
 *            the type of value stored under this key
 */
public final class MemoryKey<T> {
    private final String key;
    private final boolean isPublic;

    private MemoryKey(String key, boolean isPublic) {
        this.key = Objects.requireNonNull(key, "key must not be null");
        this.isPublic = isPublic;
    }

    /**
     * Creates a memory key whose data is flagged non-public (see
     * {@link IData#isPublic()}; the flag does not itself hide the data).
     */
    public static <T> MemoryKey<T> of(String key) {
        return new MemoryKey<>(key, false);
    }

    /**
     * Creates a memory key whose data is flagged public (see
     * {@link IData#isPublic()}; the flag does not itself expose the data).
     */
    public static <T> MemoryKey<T> ofPublic(String key) {
        return new MemoryKey<>(key, true);
    }

    /** Returns the string key name used for storage. */
    public String key() {
        return key;
    }

    /**
     * The public flag given to data stored under this key; a marker, not a filter.
     */
    public boolean isPublic() {
        return isPublic;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (!(o instanceof MemoryKey<?> that))
            return false;
        return key.equals(that.key);
    }

    @Override
    public int hashCode() {
        return key.hashCode();
    }

    @Override
    public String toString() {
        return "MemoryKey{" + key + (isPublic ? ", public" : "") + '}';
    }
}
