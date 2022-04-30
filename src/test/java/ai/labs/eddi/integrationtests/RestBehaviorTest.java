package ai.labs.eddi.integrationtests;


import ai.labs.eddi.datastore.IResourceStore.IResourceId;
import io.quarkus.test.junit.QuarkusTest;
import org.jboss.logging.Logger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import javax.enterprise.context.ApplicationScoped;

import static org.hamcrest.Matchers.equalTo;

/**
 * @author ginccc
 */
@QuarkusTest
@TestMethodOrder(OrderAnnotation.class)
@ApplicationScoped
public class RestBehaviorTest extends BaseCRUDOperations {
    private static final String ROOT_PATH = "/behaviorstore/behaviorsets/";
    private static final String RESOURCE_URI = "eddi://ai.labs.behavior" + ROOT_PATH;
    private static final Logger LOGGER = Logger.getLogger(RestBehaviorTest.class);

    private String TEST_JSON;
    private String TEST_JSON2;

    private IResourceId resourceId;

    @BeforeEach
    public void setup() {
        try {
            // load test resources
            TEST_JSON = load("behavior/createBehavior.json");
            TEST_JSON2 = load("behavior/updateBehavior.json");
        } catch (Exception e) {
            LOGGER.error(e.getLocalizedMessage(), e);
        }
    }

    @Test
    @Order(1)
    public void createBehavior() {
        resourceId = assertCreate(TEST_JSON, ROOT_PATH, RESOURCE_URI);
        LOGGER.info("create - resourceId: " + resourceId);
    }

    @Test
    @Order(2)
    public void readBehavior() {
        LOGGER.info("read - resourceId: " + resourceId);
        assertRead(ROOT_PATH, resourceId).
                body("behaviorGroups[0].name", equalTo("Smalltalk")).
                body("behaviorGroups[0].behaviorRules[0].conditions[0].type", equalTo("negation"));
    }

    @Test
    @Order(3)
    public void updateBehavior() {
        assertUpdate(TEST_JSON2, ROOT_PATH, RESOURCE_URI, resourceId).
                body("behaviorGroups[0].behaviorRules[0].name", equalTo("Welcome_changed"));
    }

    @Test
    @Order(4)
    public void deleteBehavior() {
        assertDelete(ROOT_PATH, resourceId);
    }
}
