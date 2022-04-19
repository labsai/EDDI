package ai.labs.eddi.ui;

import java.io.InputStream;

/**
 * @author ginccc
 */
public interface IResourceFileManager {
    InputStream getResourceAsInputStream(String path) throws NotFoundException;

    class NotFoundException extends Exception {
        public NotFoundException(String message, Exception e) {
            super(message, e);
        }
    }
}
