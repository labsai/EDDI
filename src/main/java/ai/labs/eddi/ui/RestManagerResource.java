package ai.labs.eddi.ui;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.InternalServerErrorException;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * @author ginccc
 */
@ApplicationScoped
public class RestManagerResource implements IRestManagerResource {
    private static final Logger LOGGER = Logger.getLogger(RestManagerResource.class);

    @Override
    public Response fetchManagerResources() {
        return fetchManagerResources("/manage.html");
    }

    @Override
    public Response fetchManagerResources(String path) {
        try {
            // Strip leading "./" or "././" for clarity
            while (path.startsWith("./")) {
                path = path.substring(2);
            }

            // Normalize the path to resolve relative elements
            Path resourcePath = Paths.get("META-INF/resources", path).normalize();

            // Prevent directory traversal: normalized path must stay under the base
            Path basePath = Paths.get("META-INF/resources").normalize();
            if (!resourcePath.startsWith(basePath)) {
                throw new SecurityException("Directory traversal attempt detected");
            }

            // Disallow characters in file names that may be used maliciously
            // (avoids regex to prevent polynomial ReDoS — CodeQL java/polynomial-redos)
            String invalidChars = "<>|:*?\"\0";
            for (char c : path.toCharArray()) {
                if (invalidChars.indexOf(c) >= 0) {
                    throw new SecurityException("Invalid characters in file path");
                }
            }

            // Attempt to load the file from the resources folder
            InputStream fileStream = Thread.currentThread().getContextClassLoader().getResourceAsStream(resourcePath.toString());

            // If the file doesn't exist, fallback to "manage.html"
            if (fileStream == null) {
                fileStream = Thread.currentThread().getContextClassLoader().getResourceAsStream("META-INF/resources/manage.html");

                if (fileStream == null) {
                    throw new FileNotFoundException("manage.html not found in META-INF/resources");
                }
            }

            // Return the file (or manage.html) as a response
            return Response.ok(fileStream).build();

        } catch (SecurityException e) {
            LOGGER.error("Blocked resource access attempt: " + path, e);
            throw new ForbiddenException("Access to the requested resource is forbidden");
        } catch (IOException e) {
            LOGGER.error("Failed to serve resource: " + path, e);
            throw new InternalServerErrorException("An error occurred while accessing the resource");
        }
    }
}
