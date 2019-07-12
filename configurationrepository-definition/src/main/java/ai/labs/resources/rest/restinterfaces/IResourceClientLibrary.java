package ai.labs.resources.rest.restinterfaces;

import ai.labs.exception.ServiceException;

import javax.ws.rs.core.Response;
import java.net.URI;

/**
 * @author ginccc
 */
public interface IResourceClientLibrary {
    void init() throws ResourceClientLibraryException;

    <T> T getResource(URI uri, Class<T> clazz) throws ServiceException;

    Response duplicateResource(URI uri) throws ServiceException;

    class ResourceClientLibraryException extends RuntimeException {
        public ResourceClientLibraryException(String message, Exception e) {
            super(message, e);
        }
    }
}
