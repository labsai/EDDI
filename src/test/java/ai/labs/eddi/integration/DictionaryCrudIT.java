package ai.labs.eddi.integration;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.*;

import java.io.IOException;

import static org.hamcrest.Matchers.equalTo;

/**
 * Integration test for Regular Dictionary CRUD operations.
 * <p>
 * Ported from {@code RestRegularDictionaryTest} in EDDI-integration-tests.
 */
@QuarkusTest
@TestProfile(IntegrationTestProfile.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class DictionaryCrudIT extends BaseIntegrationIT {

    private static final String ROOT_PATH = "/dictionarystore/dictionaries/";
    private static final String RESOURCE_URI = "eddi://ai.labs.dictionary" + ROOT_PATH;

    private static String TEST_JSON;
    private static String TEST_JSON2;
    private static String PATCH_JSON;
    private static final ResourceId[] resourceId = new ResourceId[1];

    @BeforeAll
    static void loadResources() throws IOException {
        TEST_JSON = load("dictionary/createDictionary.json");
        TEST_JSON2 = load("dictionary/updateDictionary.json");
        PATCH_JSON = load("dictionary/patchDictionary.json");
    }

    @Test
    @Order(1)
    @DisplayName("Create regular dictionary")
    void createDictionary() {
        assertCreate(TEST_JSON, ROOT_PATH, RESOURCE_URI, resourceId);
    }

    @Test
    @Order(2)
    @DisplayName("Read created regular dictionary")
    void readDictionary() {
        assertRead(ROOT_PATH, resourceId[0]);
    }

    @Test
    @Order(3)
    @DisplayName("Update regular dictionary")
    void updateDictionary() {
        assertUpdate(TEST_JSON2, ROOT_PATH, RESOURCE_URI, resourceId)
                .then().assertThat()
                .body("words[0].word", equalTo("testword2"));
    }

    @Test
    @Order(4)
    @DisplayName("Patch regular dictionary")
    void patchDictionary() {
        assertPatch(PATCH_JSON, ROOT_PATH, RESOURCE_URI, resourceId);
    }

    @Test
    @Order(5)
    @DisplayName("Delete regular dictionary")
    void deleteDictionary() {
        assertDelete(ROOT_PATH, resourceId[0]);
    }
}
