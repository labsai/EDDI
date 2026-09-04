/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
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

import java.nio.file.Files;
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

    /**
     * The same tag docker-compose.yml pins and the release smoke test now starts,
     * so the pre-publish gate exercises the MongoDB users actually run. It was
     * {@code mongo:6.0} (EOL July 2025) while every other Mongo pin in the repo
     * said 7.0.14, which left a 7.x-specific regression with nowhere to be caught.
     */
    @SuppressWarnings("resource")
    @Container
    static final MongoDBContainer MONGO = new MongoDBContainer("mongo:7.0.14")
            .withNetwork(NETWORK)
            .withNetworkAliases("mongodb");

    @SuppressWarnings("resource")
    @Container
    static final GenericContainer<?> EDDI = new GenericContainer<>(buildEddiImage("eddi-it"))
            .withNetwork(NETWORK)
            .withExposedPorts(7070)
            .withEnv("MONGODB_CONNECTIONSTRING",
                    "mongodb://mongodb:27017/eddi?retryWrites=true&w=majority&connectTimeoutMS=10000&socketTimeoutMS=30000")
            // Disable auth for tests
            .withEnv("QUARKUS_OIDC_TENANT_ENABLED", "false")
            .withEnv("AUTHORIZATION_ENABLED", "false")
            .withEnv("EDDI_SECURITY_ALLOW_UNAUTHENTICATED", "true")
            .withEnv("EDDI_MCP_ALLOW_UNAUTHENTICATED", "true")
            .withEnv("EDDI_SECRETSTORE_ALLOW_UNAUTHENTICATED", "true")
            .dependsOn(MONGO)
            .waitingFor(Wait.forHttp("/q/health/ready")
                    .forPort(7070)
                    .withStartupTimeout(Duration.ofSeconds(180)));

    /**
     * Builds the EDDI Docker image from <b>the production Dockerfile</b>
     * ({@code src/main/docker/Dockerfile}), rewritten only where the ITs' build
     * context differs from the release one.
     * <p>
     * This used to embed a whole second Dockerfile as a text block, so the image
     * the ITs proved bootable was not the image that gets pushed — and the copy had
     * already drifted (see {@link EddiImageDockerfile} for the three divergences).
     * {@link EddiImageDockerfile#forTestContext()} performs the one transformation
     * the context genuinely needs and fails loudly if the production file stops
     * matching, rather than quietly building something else.
     * <p>
     * <b>Why the context is assembled file by file.</b> Sending the project root
     * would include the root {@code .dockerignore}, whose deny-all {@code *} plus
     * allowlist form the classic builder cannot re-include paths through —
     * {@code COPY failed: file not found}. Adding only the needed directories
     * avoids that and keeps the context at ~250 MB instead of the full tree.
     * {@code target/quarkus-app} is mapped in as {@code quarkus-app}, which is the
     * sole reason the COPY prefix is rewritten; {@code licenses/} and {@code docs/}
     * keep their names and are used verbatim.
     *
     * @param imageName
     *            Docker image name for caching
     * @return configured {@link ImageFromDockerfile} ready for container use
     */
    public static ImageFromDockerfile buildEddiImage(String imageName) {
        Path quarkusAppDir = Path.of("target/quarkus-app");
        if (!Files.isDirectory(quarkusAppDir)) {
            throw new IllegalStateException(
                    "target/quarkus-app/ not found. Run 'mvn package -DskipTests' before running container-based ITs.");
        }

        var image = new ImageFromDockerfile(imageName, false)
                .withFileFromString("Dockerfile", EddiImageDockerfile.forTestContext())
                .withFileFromPath("quarkus-app", quarkusAppDir);

        // licenses/ and docs/ are required by the Dockerfile COPY instructions.
        // They exist in git, but provide graceful fallbacks for edge cases.
        Path licensesDir = Path.of("licenses");
        if (Files.isDirectory(licensesDir)) {
            image.withFileFromPath("licenses", licensesDir);
        } else {
            image.withFileFromString("licenses/THIRD-PARTY.txt", "Integration test build\n");
        }

        Path docsDir = Path.of("docs");
        if (Files.isDirectory(docsDir)) {
            image.withFileFromPath("docs", docsDir);
        } else {
            image.withFileFromString("docs/README.md", "Integration test build\n");
        }

        return image;
    }

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
