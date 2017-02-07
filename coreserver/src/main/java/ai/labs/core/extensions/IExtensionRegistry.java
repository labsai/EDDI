package ai.labs.core.extensions;

/**
 * @author ginccc
 */
public interface IExtensionRegistry<D, E> {
    void register(String id, D extensionDescriptor);

    void remove(String id);

    E getExtension(String id) throws ExtensionRegistryException;

    class ExtensionRegistryException extends Exception {
        public ExtensionRegistryException(String message) {
            super(message);
        }

        public ExtensionRegistryException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}

