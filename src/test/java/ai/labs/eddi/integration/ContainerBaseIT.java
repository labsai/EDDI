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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.jboss.logmanager.LogManager;

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

    @SuppressWarnings("resource")
    @Container
    static final MongoDBContainer MONGO = new MongoDBContainer("mongo:6.0")
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
     * Reads the base image reference — tag <em>and</em> digest pin — out of the
     * production Dockerfile instead of restating it here.
     * <p>
     * The inline Dockerfile in {@link #buildEddiImage(String)} exists to mirror
     * production, and a mirror that can drift is worse than no mirror: a second
     * copy of the reference goes stale on the next digest bump without anything
     * failing, and the container ITs quietly start certifying an OS layer nothing
     * ships. Parsing the real {@code FROM} line means the pin comes along for free
     * and cannot be forgotten. Takes the <em>last</em> {@code FROM}, matching how
     * {@code base-image-check.yml} identifies the runtime stage.
     *
     * @return the image reference, e.g.
     *         {@code registry.access.redhat.com/ubi10/openjdk-25-runtime:1.24@sha256:...}
     */
    private static String productionBaseImage() {
        Path dockerfile = Path.of("src/main/docker/Dockerfile");
        List<String> lines;
        try {
            lines = Files.readAllLines(dockerfile);
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Could not read " + dockerfile + " to determine the production base image. "
                            + "Container-based ITs must run from the project root.",
                    e);
        }

        return lines.stream()
                .map(String::strip)
                .filter(line -> line.startsWith("FROM "))
                // Drop a trailing "AS <stage>" so a future multi-stage build still parses.
                .map(line -> line.substring("FROM ".length()).strip().split("\\s+")[0])
                .reduce((first, second) -> second)
                .orElseThrow(() -> new IllegalStateException("No FROM instruction found in " + dockerfile));
    }

    /**
     * Builds the EDDI Docker image using a test-specific inline Dockerfile with
     * flat build context paths.
     * <p>
     * <b>Why not use the production Dockerfile?</b> The production Dockerfile
     * ({@code src/main/docker/Dockerfile}) uses
     * {@code COPY target/quarkus-app/lib/ ...} — paths nested under
     * {@code target/}. When Testcontainers sends the project root as build context,
     * the project's {@code .dockerignore} (which uses a deny-all {@code *} +
     * exception pattern) is included in the tar. Docker/BuildKit processes this
     * {@code .dockerignore} and fails to re-include paths under excluded parent
     * directories, causing {@code COPY failed: file not found}.
     * <p>
     * This method avoids the problem entirely by:
     * <ol>
     * <li>Using an inline Dockerfile with <b>flat</b> COPY paths
     * ({@code quarkus-app/lib/} instead of {@code target/quarkus-app/lib/})</li>
     * <li>Adding only needed directories to the build context — no
     * {@code .dockerignore} is present in the context</li>
     * <li>Keeping the context minimal (~250 MB instead of the full project
     * tree)</li>
     * </ol>
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

        // Inline Dockerfile — mirrors the production Dockerfile but uses
        // flat COPY source paths (quarkus-app/ instead of target/quarkus-app/)
        // because target/quarkus-app is mapped to quarkus-app in the build context.
        String testDockerfile = """
                FROM %s
                ENV LANG='C.utf8' LANGUAGE='C.utf8'
                USER root
                RUN mkdir -p /deployments/tmp/import && \\
                    chown -R 185:0 /deployments/tmp && \\
                    chmod -R 775 /deployments/tmp
                COPY --chown=185 quarkus-app/lib/ /deployments/lib/
                COPY --chown=185 quarkus-app/*.jar /deployments/
                COPY --chown=185 quarkus-app/app/ /deployments/app/
                COPY --chown=185 quarkus-app/quarkus/ /deployments/quarkus/
                COPY --chown=185 licenses/ /licenses/
                COPY --chown=185 docs/ /deployments/docs/
                USER 185
                EXPOSE 7070
                ENV JAVA_OPTS_APPEND="-Dquarkus.http.host=0.0.0.0 -Djava.util.logging.manager=LogManager -Dfile.encoding=UTF8 -Deddi.docs.path=/deployments/docs"
                ENV JAVA_APP_JAR="/deployments/quarkus-run.jar"
                ENTRYPOINT [ "/opt/jboss/container/java/run/run-java.sh" ]
                """
                .formatted(productionBaseImage());

        var image = new ImageFromDockerfile(imageName, false)
                .withFileFromString("Dockerfile", testDockerfile)
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
