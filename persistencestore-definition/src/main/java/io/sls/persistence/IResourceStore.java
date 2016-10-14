package io.sls.persistence;

/**
 * User: Michael
 * Date: 13.08.12
 * Time: 18:10
 */
public interface IResourceStore<T> {

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

    IResourceId create(T content) throws ResourceStoreException;

    T read(String id, Integer version) throws ResourceNotFoundException, ResourceStoreException;

    Integer update(String id, Integer version, T content) throws ResourceStoreException, ResourceModifiedException, ResourceNotFoundException;

    void delete(String id, Integer version) throws ResourceStoreException, ResourceModifiedException, ResourceNotFoundException;

    void deleteAllPermanently(String id);

    IResourceId getCurrentResourceId(String id) throws ResourceNotFoundException;
}
