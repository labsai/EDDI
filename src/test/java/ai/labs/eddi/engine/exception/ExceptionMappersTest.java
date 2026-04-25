package ai.labs.eddi.engine.exception;

import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.engine.gdpr.ProcessingRestrictedException;
import ai.labs.eddi.engine.gdpr.ProcessingRestrictedExceptionMapper;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for all JAX-RS exception mappers.
 */
@DisplayName("Exception Mappers Tests")
class ExceptionMappersTest {

    @Nested
    @DisplayName("ResourceStoreExceptionMapper")
    class StoreMapper {

        @Test
        @DisplayName("should return 500 with exception message")
        void returns500() {
            var mapper = new ResourceStoreExceptionMapper();
            var ex = new IResourceStore.ResourceStoreException("DB connection failed");

            Response response = mapper.toResponse(ex);

            assertEquals(500, response.getStatus());
            assertEquals("DB connection failed", response.getEntity());
        }
    }

    @Nested
    @DisplayName("IllegalArgumentExceptionMapper")
    class IllegalArgMapper {

        @Test
        @DisplayName("should return 400 with exception message")
        void returns400() {
            var mapper = new IllegalArgumentExceptionMapper();
            var ex = new IllegalArgumentException("Invalid parameter");

            Response response = mapper.toResponse(ex);

            assertEquals(400, response.getStatus());
            assertEquals("Invalid parameter", response.getEntity());
        }
    }

    @Nested
    @DisplayName("ResourceNotFoundExceptionMapper")
    class NotFoundMapper {

        @Test
        @DisplayName("should return 404 with exception message")
        void returns404() {
            var mapper = new ResourceNotFoundExceptionMapper();
            var ex = new IResourceStore.ResourceNotFoundException("Resource not found");

            Response response = mapper.toResponse(ex);

            assertEquals(404, response.getStatus());
            assertEquals("Resource not found", response.getEntity());
        }
    }

    @Nested
    @DisplayName("ResourceModifiedExceptionMapper")
    class ModifiedMapper {

        @Test
        @DisplayName("should return 409 Conflict with exception message")
        void returns409() {
            var mapper = new ResourceModifiedExceptionMapper();
            var ex = new IResourceStore.ResourceModifiedException("Resource was modified");

            Response response = mapper.toResponse(ex);

            assertEquals(409, response.getStatus());
            assertEquals("Resource was modified", response.getEntity());
        }
    }

    @Nested
    @DisplayName("ResourceAlreadyExistsExceptionMapper")
    class AlreadyExistsMapper {

        @Test
        @DisplayName("should return 409 Conflict with exception message")
        void returns409() {
            var mapper = new ResourceAlreadyExistsExceptionMapper();
            var ex = new IResourceStore.ResourceAlreadyExistsException("Already exists");

            Response response = mapper.toResponse(ex);

            assertEquals(409, response.getStatus());
            assertEquals("Already exists", response.getEntity());
        }
    }

    @Nested
    @DisplayName("ProcessingRestrictedExceptionMapper")
    class GdprMapper {

        @Test
        @DisplayName("should return 403 Forbidden with JSON body")
        @SuppressWarnings("unchecked")
        void returns403() {
            var mapper = new ProcessingRestrictedExceptionMapper();
            var ex = new ProcessingRestrictedException("User requested data restriction");

            Response response = mapper.toResponse(ex);

            assertEquals(403, response.getStatus());
            var entity = (Map<String, String>) response.getEntity();
            assertEquals("processing_restricted", entity.get("error"));
            assertEquals("User requested data restriction", entity.get("message"));
        }
    }
}
