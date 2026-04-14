package ai.labs.eddi.integration;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.*;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

/**
 * Integration test for Property Setter Configuration CRUD operations.
 * <p>
 * Tests Create → Read → Update → Delete lifecycle for
 * {@code /propertysetterstore/propertysetters}.
 */
@QuarkusTest
@TestProfile(IntegrationTestProfile.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class PropertySetterCrudIT extends BaseIntegrationIT {

    private static final String ROOT_PATH = "/propertysetterstore/propertysetters/";
    private static final String RESOURCE_URI = "eddi://ai.labs.property" + ROOT_PATH;

    private static final ResourceId[] resourceId = new ResourceId[1];

    @AfterAll
    static void cleanup() {
        if (resourceId[0] != null) {
            deleteResourceQuietly(ROOT_PATH, resourceId[0].id(), resourceId[0].version());
        }
    }

    private static final String CREATE_JSON = """
            {
              "setOnActions": [
                {
                  "actions": ["set_city"],
                  "setProperties": [
                    {
                      "name": "chosenCity",
                      "valueString": "[[inputNoExpressions]]",
                      "scope": "conversation"
                    }
                  ]
                }
              ]
            }
            """;

    private static final String UPDATE_JSON = """
            {
              "setOnActions": [
                {
                  "actions": ["set_city"],
                  "setProperties": [
                    {
                      "name": "chosenCity",
                      "valueString": "[[inputNoExpressions]]",
                      "scope": "longTerm"
                    }
                  ]
                },
                {
                  "actions": ["set_language"],
                  "setProperties": [
                    {
                      "name": "preferredLanguage",
                      "valueString": "[[inputNoExpressions]]",
                      "scope": "longTerm"
                    }
                  ]
                }
              ]
            }
            """;

    @Test
    @Order(1)
    @DisplayName("Create property setter configuration")
    void createPropertySetter() {
        assertCreate(CREATE_JSON, ROOT_PATH, RESOURCE_URI, resourceId);
    }

    @Test
    @Order(2)
    @DisplayName("Read created property setter configuration")
    void readPropertySetter() {
        assertRead(ROOT_PATH, resourceId[0]);
    }

    @Test
    @Order(3)
    @DisplayName("Read property setter should have correct structure")
    void readPropertySetter_verifyStructure() {
        given().get(ROOT_PATH + resourceId[0].id() + VERSION_STRING + resourceId[0].version())
                .then().assertThat()
                .statusCode(200)
                .body("setOnActions[0].actions[0]", equalTo("set_city"))
                .body("setOnActions[0].setProperties[0].name", equalTo("chosenCity"))
                .body("setOnActions[0].setProperties[0].scope", equalTo("conversation"));
    }

    @Test
    @Order(4)
    @DisplayName("Update property setter configuration")
    void updatePropertySetter() {
        assertUpdate(UPDATE_JSON, ROOT_PATH, RESOURCE_URI, resourceId)
                .then().assertThat()
                .body("setOnActions.size()", equalTo(2))
                .body("setOnActions[0].setProperties[0].scope", equalTo("longTerm"))
                .body("setOnActions[1].actions[0]", equalTo("set_language"));
    }

    @Test
    @Order(5)
    @DisplayName("Delete property setter configuration")
    void deletePropertySetter() {
        assertDelete(ROOT_PATH, resourceId[0]);
    }
}
