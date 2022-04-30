package ai.labs.eddi.integrationtests;

import ai.labs.eddi.datastore.IResourceStore.IResourceId;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import org.junit.jupiter.api.*;

import javax.enterprise.context.ApplicationScoped;
import java.io.IOException;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;

/**
 * @author ginccc
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@ApplicationScoped
public class RestSemanticParserTest extends BaseCRUDOperations {
    private static final String ROOT_PATH = "/parserstore/parsers/";
    private static final String RESOURCE_URI = "eddi://ai.labs.parser" + ROOT_PATH;
    private static final String REGULARDICTIONARY_PATH = "/regulardictionarystore/regulardictionaries/";

    private String REGULAR_DICTIONARY;
    private String PARSER_CONFIG;

    private String regularDictionaryId;
    private Integer regularDictionaryVersion;
    private IResourceId resourceId;

    @BeforeEach
    public void setup() throws IOException, InterruptedException {
        // load test resources
        REGULAR_DICTIONARY = load("parser/simpleRegularDictionary.json");
        PARSER_CONFIG = load("parser/parserConfiguration.json");
    }

    @Test
    @Order(1)
    public void createRegularDictionary() {
        resourceId = assertCreate(REGULAR_DICTIONARY, REGULARDICTIONARY_PATH,
                "eddi://ai.labs.regulardictionary" + REGULARDICTIONARY_PATH);
        regularDictionaryId = resourceId.getId();
        regularDictionaryVersion = resourceId.getVersion();
    }

    @Test
    @Order(2)
    public void createSemanticParserConfig() {
        String parserConfig = PARSER_CONFIG;
        parserConfig = parserConfig.replaceAll("<UNIQUE_ID>", resourceId.getId());
        parserConfig = parserConfig.replace("<VERSION>", resourceId.getVersion().toString());
        assertCreate(parserConfig, ROOT_PATH, RESOURCE_URI);
    }

    @Test
    @Order(3)
    public void runParserOnWord() {
        //test
        Response response = given().
                body("hello").
                contentType(ContentType.JSON).
                post("/parser/" + resourceId.getId() + VERSION_STRING + resourceId.getVersion());

        //assert
        response.then().
                assertThat().
                statusCode(equalTo(200)).
                body("expressions", hasItem("greeting(hello)"));
    }

    @Test
    @Order(4)
    public void runParserOnPhrase() {
        //test
        Response response = given().
                body("good afternoon").
                contentType(ContentType.JSON).
                post("/parser/" + resourceId.getId() + VERSION_STRING + resourceId.getVersion());

        //assert
        response.then().
                assertThat().
                statusCode(equalTo(200)).
                body("expressions", hasItem("greeting(good_afternoon)"));
    }

    @Test
    @Order(5)
    public void runParserOnWordWithSpellingMistake() {
        //test
        Response response = given().
                body("helo").
                contentType(ContentType.JSON).
                post("/parser/" + resourceId.getId() + VERSION_STRING + resourceId.getVersion());

        //assert
        response.then().
                assertThat().
                statusCode(equalTo(200)).
                body("expressions", hasItem("greeting(hello)"));
    }

    @AfterEach
    public void deleteConfigFiles() {
        //clean up regular dictionary
        String requestUri = REGULARDICTIONARY_PATH + regularDictionaryId + VERSION_STRING + regularDictionaryVersion;
        given().delete(requestUri).then().statusCode(200);
        given().get(requestUri).then().statusCode(404);

        //cleanup parser config
        assertDelete(ROOT_PATH, resourceId);
    }
}
