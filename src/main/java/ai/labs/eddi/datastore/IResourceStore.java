/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.datastore;

import jakarta.interceptor.InterceptorBinding;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Versioned CRUD contract every configuration store in the project extends.
 * <p>
 * A resource is identified by an id plus an integer version: {@link #update}
 * returns the new version rather than mutating in place, and {@link #read}
 * takes the version it wants, so a conversation pinned to an older
 * configuration keeps resolving that exact revision. {@link #delete} marks a
 * version deleted and leaves it readable through {@link #readIncludingDeleted};
 * only {@link #deleteAllPermanently} removes the history.
 * <p>
 * Nested here because they belong to the contract rather than any one
 * implementation: {@link IResourceId} (the id/version pair), the checked
 * exceptions callers are expected to handle, and {@link ConfigurationUpdate},
 * the interceptor binding that fires when a store mutates a configuration.
 *
 * @param <T>
 *            the configuration model this store persists
 */
public interface IResourceStore<T> {

    T readIncludingDeleted(String id, Integer version) throws ResourceNotFoundException, ResourceStoreException;

    @InterceptorBinding
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.TYPE, ElementType.METHOD})
    @interface ConfigurationUpdate {
    }

    interface IResourceId {
        String getId();

        Integer getVersion();
    }

    class ResourceModifiedException extends Exception {

        public ResourceModifiedException(String message) {
            super(message);
        }

        public ResourceModifiedException(String message, Throwable e) {
            super(message, e);
        }
    }

    class ResourceStoreException extends Exception {

        public ResourceStoreException(String message) {
            super(message);
        }

        public ResourceStoreException(String message, Throwable e) {
            super(message, e);
        }
    }

    class ResourceNotFoundException extends Exception {

        public ResourceNotFoundException(String message) {
            super(message);
        }
    }

    class ResourceAlreadyExistsException extends Exception {

        public ResourceAlreadyExistsException(String message) {
            super(message);
        }
    }

    IResourceStore.IResourceId create(T content) throws IResourceStore.ResourceStoreException;

    T read(String id, Integer version) throws IResourceStore.ResourceNotFoundException, IResourceStore.ResourceStoreException;

    Integer update(String id, Integer version, T content)
            throws IResourceStore.ResourceStoreException, IResourceStore.ResourceModifiedException, IResourceStore.ResourceNotFoundException;

    void delete(String id, Integer version)
            throws IResourceStore.ResourceStoreException, IResourceStore.ResourceModifiedException, IResourceStore.ResourceNotFoundException;

    void deleteAllPermanently(String id);

    IResourceStore.IResourceId getCurrentResourceId(String id) throws IResourceStore.ResourceNotFoundException;
}
