package ai.labs.eddi.integration;

import io.restassured.RestAssured;
import io.restassured.config.HttpClientConfig;
import io.restassured.config.RestAssuredConfig;
import org.junit.jupiter.api.BeforeAll;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.file.Path;
import java.time.Duration;

/**
 * Base class for container-based integration tests.
 * <p>
 * Uses Testcontainers to start MongoDB + EDDI in real Docker containers,
 * providing true black-box E2E testing. This replaces the broken
 * {@code @QuarkusTest} approach that fails on Windows (JaCoCo path quoting) and
 * for MCP-enabled builds (CDI augmentation).
 * <p>
 * <b>Prerequisites:</b>
 * <ul>
 * <li>Docker Desktop must be running</li>
 * <li>The application JAR must be pre-built
 * ({@code mvn package -DskipTests})</li>
 * </ul>
 * <p>
 * The containers are started once per test class (static fields) and shared
 * across all test methods in that class.
 */
@Testcontainers
public abstract class ContainerBaseIT extends BaseIntegrationIT {

    static final Network NETWORK = Network.newNetwork();

    @Container
    static final MongoDBContainer MONGO = new MongoDBContainer("mongo:6.0")
            .withNetwork(NETWORK)
            .withNetworkAliases("mongodb");

    @SuppressWarnings("resource")
    @Container
    static final GenericContainer<?> EDDI = new GenericContainer<>(
            new ImageFromDockerfile("eddi-it", false)
                    .withFileFromPath(".", Path.of("."))
                    .withDockerfilePath("src/main/docker/Dockerfile.jvm"))
            .withNetwork(NETWORK)
            .withExposedPorts(7070)
            .withEnv("MONGODB_CONNECTIONSTRING",
                    "mongodb://mongodb:27017/eddi?retryWrites=true&w=majority&connectTimeoutMS=10000&socketTimeoutMS=30000")
            // Disable auth for tests
            .withEnv("QUARKUS_OIDC_TENANT_ENABLED", "false")
            .withEnv("AUTHORIZATION_ENABLED", "false")
            .dependsOn(MONGO)
            .waitingFor(Wait.forHttp("/q/health/ready")
                    .forPort(7070)
                    .withStartupTimeout(Duration.ofSeconds(120)));

    /**
     * Point RestAssured at the EDDI container's mapped port. Runs once before all
     * tests in the class.
     */
    @BeforeAll
    static void configureRestAssured() {
        RestAssured.baseURI = "http://localhost";
        RestAssured.port = EDDI.getMappedPort(7070);
        RestAssured.config = RestAssuredConfig.config().httpClient(
                HttpClientConfig.httpClientConfig()
                        .setParam("http.socket.timeout", 600_000)
                        .setParam("http.connection.timeout", 10_000));
    }
}
