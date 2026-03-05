package ai.labs.eddi.engine.memory;

import java.util.Objects;

/**
 * Type-safe key for accessing data in
 * {@link IConversationMemory.IConversationStep}.
 * <p>
 * Binds a string key name to a Java type at compile time, eliminating unchecked
 * casts
 * and providing discoverability via the {@link MemoryKeys} registry.
 * <p>
 * The {@code isPublic} flag determines whether data stored under this key
 * should be included in client responses (see {@link IData#isPublic()}).
 *
 * @param <T> the type of value stored under this key
 */
public final class MemoryKey<T> {
    private final String key;
    private final boolean isPublic;

    private MemoryKey(String key, boolean isPublic) {
        this.key = Objects.requireNonNull(key, "key must not be null");
        this.isPublic = isPublic;
    }

    /**
     * Creates a private (non-public) memory key.
     * Data stored under this key will NOT be sent to the client.
     */
    public static <T> MemoryKey<T> of(String key) {
        return new MemoryKey<>(key, false);
    }

    /**
     * Creates a public memory key.
     * Data stored under this key WILL be included in client responses.
     */
    public static <T> MemoryKey<T> ofPublic(String key) {
        return new MemoryKey<>(key, true);
    }

    /** Returns the string key name used for storage. */
    public String key() {
        return key;
    }

    /** Whether data under this key should be visible to the client. */
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
