package io.sls.core.extensions;

/**
 * User: jarisch
 * Date: 11.09.12
 * Time: 10:36
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

