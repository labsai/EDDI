package ai.labs.eddi.integration;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.*;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

/**
 * Integration test for Prompt Snippet CRUD operations.
 * <p>
 * Tests Create → Read → Update → Delete lifecycle for
 * {@code /snippetstore/snippets}, including name validation.
 */
@QuarkusTest
@TestProfile(IntegrationTestProfile.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class PromptSnippetCrudIT extends BaseIntegrationIT {

    private static final String ROOT_PATH = "/snippetstore/snippets/";
    private static final String RESOURCE_URI = "eddi://ai.labs.snippet" + ROOT_PATH;

    private static final ResourceId[] resourceId = new ResourceId[1];

    private static final String CREATE_JSON = """
            {
              "name": "test_greeting",
              "content": "You are a helpful assistant that always greets users warmly.",
              "description": "A test greeting snippet"
            }
            """;

    private static final String UPDATE_JSON = """
            {
              "name": "test_greeting",
              "content": "You are a professional assistant that greets users formally.",
              "description": "An updated test greeting snippet"
            }
            """;

    @Test
    @Order(1)
    @DisplayName("Create prompt snippet")
    void createSnippet() {
        assertCreate(CREATE_JSON, ROOT_PATH, RESOURCE_URI, resourceId);
    }

    @Test
    @Order(2)
    @DisplayName("Read created prompt snippet")
    void readSnippet() {
        assertRead(ROOT_PATH, resourceId[0]);
    }

    @Test
    @Order(3)
    @DisplayName("Read snippet should have correct structure")
    void readSnippet_verifyStructure() {
        given().get(ROOT_PATH + resourceId[0].id() + VERSION_STRING + resourceId[0].version())
                .then().assertThat()
                .statusCode(200)
                .body("name", equalTo("test_greeting"))
                .body("content", containsString("greets users warmly"));
    }

    @Test
    @Order(4)
    @DisplayName("Update prompt snippet")
    void updateSnippet() {
        assertUpdate(UPDATE_JSON, ROOT_PATH, RESOURCE_URI, resourceId)
                .then().assertThat()
                .body("content", containsString("greets users formally"));
    }

    @Test
    @Order(5)
    @DisplayName("Snippet descriptors should return list")
    void readDescriptors() {
        given().get(ROOT_PATH + "descriptors")
                .then().assertThat()
                .statusCode(200)
                .contentType(ContentType.JSON);
    }

    @Test
    @Order(6)
    @DisplayName("Delete prompt snippet")
    void deleteSnippet() {
        assertDelete(ROOT_PATH, resourceId[0]);
    }
}
