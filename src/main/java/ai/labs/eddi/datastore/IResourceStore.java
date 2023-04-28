package ai.labs.eddi.datastore;

import jakarta.interceptor.InterceptorBinding;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * @author ginccc
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

    Integer update(String id, Integer version, T content) throws IResourceStore.ResourceStoreException, IResourceStore.ResourceModifiedException, IResourceStore.ResourceNotFoundException;

    void delete(String id, Integer version) throws IResourceStore.ResourceStoreException, IResourceStore.ResourceModifiedException, IResourceStore.ResourceNotFoundException;

    void deleteAllPermanently(String id);

    IResourceStore.IResourceId getCurrentResourceId(String id) throws IResourceStore.ResourceNotFoundException;
}
