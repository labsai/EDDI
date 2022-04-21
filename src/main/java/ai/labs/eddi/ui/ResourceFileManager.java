package ai.labs.eddi.ui;

import org.jboss.logging.Logger;

import javax.enterprise.context.ApplicationScoped;
import javax.ws.rs.InternalServerErrorException;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.net.URL;

/**
 * @author ginccc
 */
@ApplicationScoped
public class ResourceFileManager implements IResourceFileManager {
    private static final Logger LOGGER = Logger.getLogger(ResourceFileManager.class);

    @Override
    public InputStream getResourceAsInputStream(String path) throws IResourceFileManager.NotFoundException {
        try {
            File file = getFileFromResource(path);
            return new FileInputStream(file);
        } catch (IOException e) {
            LOGGER.error(e.getLocalizedMessage(), e);
            throw new IResourceFileManager.NotFoundException(e.getLocalizedMessage(), e);
        } catch (URISyntaxException e) {
            LOGGER.error(e.getLocalizedMessage(), e);
            throw new InternalServerErrorException();
        }
    }

    private File getFileFromResource(String filePath) throws URISyntaxException {

        ClassLoader classLoader = getClass().getClassLoader();
        URL resource = classLoader.getResource(filePath);
        if (resource == null) {
            throw new IllegalArgumentException("file not found! " + filePath);
        } else {
            return new File(resource.toURI());
        }

    }
}
