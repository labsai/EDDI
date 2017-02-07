package ai.labs.staticresources.rest;

import java.io.InputStream;

/**
 * @author ginccc
 */
public interface IResourceFileManager {


    String getResourceAsString(String... paths) throws NotFoundException;

    InputStream getResourceAsInputStream(String... paths) throws NotFoundException;

    class NotFoundException extends Exception {
        public NotFoundException(String message, Exception e) {
            super(message, e);
        }
    }
}
