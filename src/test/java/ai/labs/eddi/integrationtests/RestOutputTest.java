package ai.labs.eddi.integrationtests;

import ai.labs.eddi.datastore.IResourceStore.IResourceId;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.*;

import javax.enterprise.context.ApplicationScoped;
import java.io.IOException;

import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.equalTo;

/**
 * @author ginccc
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@ApplicationScoped
public class RestOutputTest extends BaseCRUDOperations {
    private static final String ROOT_PATH = "/outputstore/outputsets/";
    private static final String RESOURCE_URI = "eddi://ai.labs.output" + ROOT_PATH;

    private String TEST_JSON;
    private String TEST_JSON2;
    private String PATCH_JSON;
    private IResourceId resourceId;

    @BeforeEach
    public void setup() throws IOException, InterruptedException {
        // load test resources
        TEST_JSON = load("output/createOutput.json");
        TEST_JSON2 = load("output/updateOutput.json");
        PATCH_JSON = load("output/patchOutput.json");
    }

    @Test()
    @Order(1)
    public void createOutput() {
        resourceId = assertCreate(TEST_JSON, ROOT_PATH, RESOURCE_URI);
    }

    @Test
    @Order(2)
    public void readOutput() {
        assertRead(ROOT_PATH, resourceId).
                body("outputSet[1].action", equalTo("greet")).
                body("outputSet[1].outputs[0].type", equalTo("text")).
                body("outputSet[1].outputs[0].valueAlternatives[1]", equalTo("Hey you!"));
    }

    @Test
    @Order(3)
    public void updateOutput() {
        assertUpdate(TEST_JSON2, ROOT_PATH, RESOURCE_URI, resourceId).
                body("outputSet[0].outputs[0].valueAlternatives[0]", endsWith("--changed!"));
    }

    @Test
    @Order(4)
    public void patchOutput() {
        assertPatch(PATCH_JSON, ROOT_PATH, RESOURCE_URI, resourceId).
                body("outputSet[5].outputs[0].valueAlternatives[0]", endsWith("--changed-again!"));
    }

    @Test
    @Order(5)
    public void deleteOutput() {
        assertDelete(ROOT_PATH, resourceId);
    }
}
