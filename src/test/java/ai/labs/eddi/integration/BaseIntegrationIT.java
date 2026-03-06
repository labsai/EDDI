package ai.labs.eddi.integration;

import io.restassured.http.ContentType;
import io.restassured.response.Response;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

/**
 * Base class for all integration tests.
 * <p>
 * Provides helper methods ported from the original {@code BaseCRUDOperations}
 * in the EDDI-integration-tests repo:
 * <ul>
 * <li>{@link #load(String)} — load JSON test resources</li>
 * <li>{@link #createResource(String, String)} — POST to create and return
 * Location</li>
 * <li>{@link #deployBot(String, Integer)} — deploy bot and poll until
 * READY</li>
 * <li>{@link #createConversation(String, String)} — start a new
 * conversation</li>
 * <li>{@link #sendUserInput(String, String, String, String, boolean, boolean)}
 * — say with REST</li>
 * </ul>
 */
public abstract class BaseIntegrationIT {

    protected static final String VERSION_STRING = "?version=";

    // ==================== Resource Loading ====================

    protected static String load(String filename) throws IOException {
        var url = Thread.currentThread().getContextClassLoader().getResource("tests/" + filename);
        if (url == null) {
            throw new IOException("Test resource not found: tests/" + filename);
        }
        return Files.readString(Path.of(url.getPath().replaceFirst("^/([A-Z]:)", "$1")));
    }

    // ==================== CRUD Helpers ====================

    protected String createResource(String body, String path) {
        Response response = given()
                .body(body)
                .contentType(ContentType.JSON)
                .post(path);
        response.then().statusCode(201);
        return response.getHeader("location");
    }

    protected void assertCreate(String body, String path, String resourceUri,
            ResourceId[] outResourceId) {
        Response response = given()
                .body(body)
                .contentType(ContentType.JSON)
                .post(path);

        response.then().assertThat()
                .statusCode(equalTo(201))
                .header("location", startsWith(resourceUri))
                .header("location", endsWith(VERSION_STRING + "1"));

        String location = response.getHeader("location");
        outResourceId[0] = extractResourceId(location);
    }

    protected void assertRead(String path, ResourceId resourceId) {
        given()
                .get(path + resourceId.id() + VERSION_STRING + resourceId.version())
                .then().assertThat().statusCode(200);
    }

    protected Response assertUpdate(String body, String path, String resourceUri,
            ResourceId[] resourceId) {
        Response response = given()
                .body(body)
                .contentType(ContentType.JSON)
                .put(path + resourceId[0].id() + VERSION_STRING + resourceId[0].version());

        response.then().assertThat()
                .statusCode(200)
                .header("location", startsWith(resourceUri))
                .header("location", endsWith(VERSION_STRING + "2"));

        String location = response.getHeader("location");
        resourceId[0] = extractResourceId(location);

        return given()
                .get(path + resourceId[0].id() + VERSION_STRING + resourceId[0].version());
    }

    protected Response assertPatch(String body, String path, String resourceUri,
            ResourceId[] resourceId) {
        Response response = given()
                .body(body)
                .contentType(ContentType.JSON)
                .patch(path + resourceId[0].id() + VERSION_STRING + resourceId[0].version());

        response.then().assertThat()
                .statusCode(200)
                .header("location", startsWith(resourceUri))
                .header("location", endsWith(VERSION_STRING + "3"));

        String location = response.getHeader("location");
        resourceId[0] = extractResourceId(location);

        return given()
                .get(path + resourceId[0].id() + VERSION_STRING + resourceId[0].version());
    }

    protected void assertDelete(String path, ResourceId resourceId) {
        String requestUri = path + resourceId.id() + VERSION_STRING + resourceId.version();
        given().delete(requestUri);
        given().get(requestUri).then().statusCode(404);
    }

    // ==================== Bot Deployment Helpers ====================

    protected void deployBot(String id, Integer version) throws InterruptedException {
        given().post(String.format("administration/unrestricted/deploy/%s?version=%s&autoDeploy=false", id, version));

        for (int i = 0; i < 60; i++) { // max 30 seconds
            Response response = given().accept(ContentType.TEXT)
                    .get(String.format("administration/unrestricted/deploymentstatus/%s?version=%s", id, version));
            String status = response.getBody().print().trim();
            if ("READY".equals(status))
                return;
            if ("ERROR".equals(status)) {
                throw new RuntimeException(String.format("Bot deployment failed (id=%s, version=%s)", id, version));
            }
            Thread.sleep(500);
        }
        throw new RuntimeException("Bot deployment timed out");
    }

    protected ResourceId createConversation(String botId, String userId) {
        Response response = given().post("bots/unrestricted/" + botId + "?userId=" + userId);
        String location = response.getHeader("location");
        return extractResourceId(location);
    }

    protected Response sendUserInput(String botId, String conversationId,
            String userInput,
            boolean returnDetailed, boolean returnCurrentStepOnly) {
        return given()
                .contentType(ContentType.TEXT)
                .body(userInput)
                .post(String.format("bots/unrestricted/%s/%s?returnDetailed=%s&returnCurrentStepOnly=%s",
                        botId, conversationId, returnDetailed, returnCurrentStepOnly));
    }

    protected Response getConversationLog(String botId, String conversationId,
            boolean returnDetailed) {
        return given()
                .get(String.format("bots/unrestricted/%s/%s?returnDetailed=%s",
                        botId, conversationId, returnDetailed));
    }

    // ==================== URI Utilities ====================

    protected static ResourceId extractResourceId(String uriString) {
        URI uri = URI.create(uriString);
        String path = uri.getPath();
        String id = path.substring(path.lastIndexOf('/') + 1);
        String query = uri.getQuery();
        int version = 1;
        if (query != null && query.startsWith("version=")) {
            version = Integer.parseInt(query.substring("version=".length()));
        }
        return new ResourceId(id, version);
    }

    // ==================== Cleanup Helpers ====================

    /**
     * Undeploy a bot, ignoring errors (for use in @AfterAll).
     */
    protected static void undeployBotQuietly(String id, int version) {
        try {
            given().post(String.format("administration/unrestricted/undeploy/%s?version=%s", id, version));
        } catch (Exception ignored) {
        }
    }

    /**
     * Delete a resource by path and ID, ignoring errors (for use in @AfterAll).
     */
    protected static void deleteResourceQuietly(String path, String id, int version) {
        try {
            given().delete(path + id + VERSION_STRING + version);
        } catch (Exception ignored) {
        }
    }

    public record ResourceId(String id, int version) {
    }
}
