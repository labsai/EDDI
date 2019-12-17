package ai.labs.persistence;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * @author ginccc
 */
public interface IResourceStore<T> {

    T readIncludingDeleted(String id, Integer version) throws ResourceNotFoundException, ResourceStoreException;

    @Retention(RetentionPolicy.RUNTIME) @Target(ElementType.METHOD)
    @interface ConfigurationUpdate {}

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

    IResourceId create(T content) throws ResourceStoreException;

    T read(String id, Integer version) throws ResourceNotFoundException, ResourceStoreException;

    Integer update(String id, Integer version, T content) throws ResourceStoreException, ResourceModifiedException, ResourceNotFoundException;

    void delete(String id, Integer version) throws ResourceStoreException, ResourceModifiedException, ResourceNotFoundException;

    void deleteAllPermanently(String id);

    IResourceId getCurrentResourceId(String id) throws ResourceNotFoundException;
}
