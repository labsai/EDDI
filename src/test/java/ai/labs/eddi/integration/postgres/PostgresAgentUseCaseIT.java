package ai.labs.eddi.integration.postgres;

import ai.labs.eddi.integration.BaseIntegrationIT;
import ai.labs.eddi.integration.ContainerBaseIT;
import io.restassured.RestAssured;
import io.restassured.config.HttpClientConfig;
import io.restassured.config.RestAssuredConfig;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

/**
 * Runs Agent Use Case tests against PostgreSQL using Testcontainers.
 * <p>
 * Mirrors {@link ai.labs.eddi.integration.AgentUseCaseIT} but with PostgreSQL
 * as the datastore instead of MongoDB. Uses the same Docker-based approach for
 * consistent, platform-independent testing.
 */
@Testcontainers
public class PostgresAgentUseCaseIT extends BaseIntegrationIT {

    static final Network NETWORK = Network.newNetwork();

    @SuppressWarnings("resource")
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withNetwork(NETWORK)
            .withNetworkAliases("postgres")
            .withDatabaseName("eddi")
            .withUsername("eddi")
            .withPassword("eddi");

    @SuppressWarnings("resource")
    @Container
    static final GenericContainer<?> EDDI = new GenericContainer<>(ContainerBaseIT.buildEddiImage("eddi-pg-it"))
            .withNetwork(NETWORK)
            .withExposedPorts(7070)
            .withEnv("EDDI_DATASTORE_TYPE", "postgres")
            // Active the Postgres profile explicitly for test loopbacks
            .withEnv("QUARKUS_PROFILE", "postgres")
            // Explicitly activate the datasource. Otherwise, Quarkus may treat it as
            // a deactivated synthetic bean, causing the Agents Readiness health check
            // and internal schedule pollers to crash with a 503 HTTP status loop.
            .withEnv("QUARKUS_DATASOURCE_ACTIVE", "true")
            .withEnv("QUARKUS_DATASOURCE_DB_KIND", "postgresql")
            .withEnv("QUARKUS_DATASOURCE_JDBC_URL", "jdbc:postgresql://postgres:5432/eddi")
            .withEnv("QUARKUS_DATASOURCE_USERNAME", "eddi")
            .withEnv("QUARKUS_DATASOURCE_PASSWORD", "eddi")
            .withEnv("QUARKUS_MONGODB_DEVSERVICES_ENABLED", "false")
            .withEnv("QUARKUS_MONGODB_HEALTH_ENABLED", "false")
            .withEnv("QUARKUS_OIDC_TENANT_ENABLED", "false")
            .withEnv("AUTHORIZATION_ENABLED", "false")
            .dependsOn(POSTGRES)
            .withLogConsumer(
                    new org.testcontainers.containers.output.Slf4jLogConsumer(org.slf4j.LoggerFactory.getLogger(PostgresAgentUseCaseIT.class)))
            .waitingFor(Wait.forHttp("/q/health/ready")
                    .forPort(7070)
                    .withStartupTimeout(Duration.ofSeconds(120)));

    @BeforeAll
    static void configureRestAssured() {
        RestAssured.baseURI = "http://localhost";
        RestAssured.port = EDDI.getMappedPort(7070);
        RestAssured.config = RestAssuredConfig.config().httpClient(
                HttpClientConfig.httpClientConfig()
                        .setParam("http.socket.timeout", 600_000)
                        .setParam("http.connection.timeout", 10_000));
    }

    private static ResourceId weatherAgentId;
    private static boolean agentImported = false;
    private static final List<String> conversationIds = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        if (!agentImported) {
            weatherAgentId = importAgent("weather_agent_v1");
            agentImported = true;
        }
    }

    @AfterAll
    static void cleanup() {
        if (weatherAgentId != null) {
            undeployAgentQuietly(weatherAgentId.id(), weatherAgentId.version());
        }
    }

    @Test
    @DisplayName("should handle multi-turn weather conversation with property extraction (PostgreSQL)")
    void weatherAgent() {
        String testUserId = "testUser" + System.currentTimeMillis();
        ResourceId conversationId = createConversation(weatherAgentId.id(), testUserId);
        conversationIds.add(conversationId.id());

        sendUserInput(weatherAgentId.id(), conversationId.id(), "weather", true, true);

        Response response = sendUserInput(weatherAgentId.id(), conversationId.id(), "Vienna", true, false);

        response.then().assertThat().body("agentId", equalTo(weatherAgentId.id())).body("agentVersion", equalTo(weatherAgentId.version()))
                .body("conversationOutputs[1].input", equalTo("weather")).body("conversationOutputs[1].actions[0]", equalTo("ask_for_city"))
                .body("conversationOutputs[2].input", equalTo("Vienna")).body("conversationOutputs[2].actions[0]", equalTo("current_weather_in_city"))
                .body("conversationProperties.chosenCity.valueString", equalTo("Vienna"))
                .body("conversationProperties.chosenCity.scope", equalTo("conversation"));
    }

    @Test
    @DisplayName("should support managed Agent API with Agent triggers (PostgreSQL)")
    void useAgentManagement() throws IOException {
        String intent = "weather-agent";
        String userId = "12345";

        given().delete("/AgentTriggerStore/agenttriggers/" + intent);

        given().contentType(ContentType.JSON).body(String.format(load("useCases/AgentDeployment.json"), weatherAgentId.id()))
                .post("/AgentTriggerStore/agenttriggers").then().statusCode(anyOf(equalTo(200), equalTo(201)));

        given().post("/agents/managed/" + intent + "/" + userId + "/endConversation");

        Response response = given().contentType(ContentType.JSON).body("{\"input\":\"weather\"}").queryParam("returnCurrentStepOnly", "false")
                .post("/agents/managed/" + intent + "/" + userId);

        response.then().assertThat().statusCode(200).body("agentId", equalTo(weatherAgentId.id()))
                .body("agentVersion", equalTo(weatherAgentId.version())).body("conversationSteps[1].conversationStep[1].key", equalTo("actions"))
                .body("conversationSteps[1].conversationStep[1].value[0]", equalTo("ask_for_city"));
    }

    // ==================== Helpers ====================

    private ResourceId importAgent(String filename) throws Exception {
        URL resource = Thread.currentThread().getContextClassLoader().getResource("tests/useCases/" + filename + ".zip");
        if (resource == null) {
            throw new IOException("Test resource not found: tests/useCases/" + filename + ".zip");
        }
        File file = new File(resource.getFile().replaceFirst("^/([A-Z]:)", "$1"));

        Response response = given()
                .contentType("application/zip").body(file).post("/backup/import");

        response.then().statusCode(201);

        String resourceUri = response.getHeader("location");
        if (resourceUri == null || resourceUri.isBlank()) {
            throw new RuntimeException("Import response missing Location header. " + "Status: " + response.getStatusCode() + ", Headers: "
                    + response.headers().asList());
        }

        ResourceId resourceId = extractResourceId(resourceUri);
        deployAgent(resourceId.id(), resourceId.version());
        return resourceId;
    }

    private void deployAgent(String id, int version) throws InterruptedException {
        given().post(String.format("administration/production/deploy/%s?version=%s&autoDeploy=false&waitForCompletion=true", id, version));

        for (int i = 0; i < 120; i++) {
            Response response = given().get(String.format("administration/production/deploymentstatus/%s?version=%s&format=text", id, version));
            String status = response.getBody().print().trim();
            if ("READY".equals(status))
                return;
            if ("ERROR".equals(status))
                throw new RuntimeException("Agent deployment failed");
            Thread.sleep(500);
        }
        throw new RuntimeException("Agent deployment timed out");
    }
}
