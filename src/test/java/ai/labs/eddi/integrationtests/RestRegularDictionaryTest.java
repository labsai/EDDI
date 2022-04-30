package ai.labs.eddi.integrationtests;

import ai.labs.eddi.datastore.IResourceStore.IResourceId;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.*;

import javax.enterprise.context.ApplicationScoped;
import java.io.IOException;

import static org.hamcrest.Matchers.hasItem;

/**
 * @author ginccc
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@ApplicationScoped
public class RestRegularDictionaryTest extends BaseCRUDOperations {
    private static final String ROOT_PATH = "/regulardictionarystore/regulardictionaries/";
    private static final String RESOURCE_URI = "eddi://ai.labs.regulardictionary" + ROOT_PATH;

    private String TEST_JSON;
    private String TEST_JSON2;
    private String PATCH_JSON;
    private IResourceId resourceId;

    @BeforeEach
    public void setup() throws IOException, InterruptedException {
        // load test resources
        TEST_JSON = load("regularDictionary/createRegularDictionary.json");
        TEST_JSON2 = load("regularDictionary/updateRegularDictionary.json");
        PATCH_JSON = load("regularDictionary/patchRegularDictionary.json");
    }

    @Test()
    @Order(1)
    public void createRegularDictionary() {
        resourceId = assertCreate(TEST_JSON, ROOT_PATH, RESOURCE_URI);
    }

    @Test
    @Order(2)
    public void readRegularDictionary() {
        assertRead(ROOT_PATH, resourceId).
                body("words.word", hasItem("testword")).
                body("words.expressions", hasItem("test_exp")).
                body("words.frequency", hasItem(0)).
                body("phrases.phrase", hasItem("Test Phrase")).
                body("phrases.expressions", hasItem("phrase_exp"));
    }

    @Test
    @Order(3)
    public void updateRegularDictionary() {
        assertUpdate(TEST_JSON2, ROOT_PATH, RESOURCE_URI, resourceId).
                body("words.word", hasItem("testword2")).
                body("words.expressions", hasItem("test_exp2")).
                body("words.frequency", hasItem(1)).
                body("phrases.phrase", hasItem("Test Phrase2")).
                body("phrases.expressions", hasItem("phrase_exp2"));
    }

    @Test
    @Order(4)
    public void patchRegularDictionary() {
        assertPatch(PATCH_JSON, ROOT_PATH, RESOURCE_URI, resourceId).
                body("words.word", hasItem("testword2")).
                body("words.expressions", hasItem("test_exp3")).
                body("words.frequency", hasItem(2)).
                body("phrases.phrase", hasItem("Test Phrase2")).
                body("phrases.expressions", hasItem("phrase_exp3"));
    }


    @Test
    @Order(5)
    public void deleteRegularDictionary() {
        assertDelete(ROOT_PATH, resourceId);
    }
}
