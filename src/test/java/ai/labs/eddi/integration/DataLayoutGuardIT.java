/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integration;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoDatabase;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.startupcheck.StartupCheckStrategy;
import org.testcontainers.containers.wait.strategy.AbstractWaitStrategy;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.mongodb.MongoDBContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The floor guard in the real image (plan §14.3, ADR 0001 DV-11): with
 * {@code layout.required} above what the binary supports, EDDI must exit
 * non-zero before anything writes, on both datastores.
 * <p>
 * The passing case needs no test of its own: every other IT starts EDDI on a
 * database without a layout record.
 */
class DataLayoutGuardIT {

    private static final Duration EXIT_TIMEOUT = Duration.ofSeconds(180);
    /** Built once and shared by both tests. */
    private static final ImageFromDockerfile IMAGE = ContainerBaseIT.buildEddiImage("eddi-layout-guard-it");

    @Test
    @SuppressWarnings("resource")
    void mongoRefusesARaisedLayoutBeforeAnythingWrites() throws Exception {
        try (Network network = Network.newNetwork();
                MongoDBContainer mongo = new MongoDBContainer("mongo:7.0.43").withNetwork(network).withNetworkAliases("mongodb")) {
            mongo.start();
            try (MongoClient client = MongoClients.create(mongo.getConnectionString())) {
                MongoDatabase eddi = client.getDatabase("eddi");
                eddi.getCollection("org_platform_state")
                        .insertOne(new Document("_id", "org-platform").append("markerState", "ENABLED").append("requiredLayout", 1));

                GenericContainer<?> app = eddi(network).withEnv("MONGODB_CONNECTIONSTRING", "mongodb://mongodb:27017/eddi");
                assertRefusedAndExited(app);

                List<String> collections = eddi.listCollectionNames().into(new ArrayList<>());
                assertEquals(List.of("org_platform_state"), collections, "nothing may write before the guard");
            }
        }
    }

    @Test
    @SuppressWarnings("resource")
    void postgresRefusesARaisedLayoutBeforeAnythingWrites() throws Exception {
        try (Network network = Network.newNetwork();
                PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine").withNetwork(network)
                        .withNetworkAliases("postgres").withDatabaseName("eddi").withUsername("eddi").withPassword("eddi")) {
            postgres.start();
            try (Connection connection = DriverManager.getConnection(postgres.getJdbcUrl(), "eddi", "eddi");
                    Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE org_platform_state (id TEXT PRIMARY KEY, marker_state TEXT NOT NULL, "
                        + "required_layout INTEGER NOT NULL, updated_at TIMESTAMPTZ NOT NULL DEFAULT now())");
                statement.execute("INSERT INTO org_platform_state (id, marker_state, required_layout) VALUES ('org-platform', 'ENABLED', 1)");

                GenericContainer<?> app = eddi(network).withEnv("EDDI_DATASTORE_TYPE", "postgres").withEnv("QUARKUS_PROFILE", "postgres")
                        .withEnv("QUARKUS_DATASOURCE_ACTIVE", "true").withEnv("QUARKUS_DATASOURCE_DB_KIND", "postgresql")
                        .withEnv("QUARKUS_DATASOURCE_JDBC_URL", "jdbc:postgresql://postgres:5432/eddi")
                        .withEnv("QUARKUS_DATASOURCE_USERNAME", "eddi").withEnv("QUARKUS_DATASOURCE_PASSWORD", "eddi")
                        .withEnv("QUARKUS_MONGODB_DEVSERVICES_ENABLED", "false").withEnv("QUARKUS_MONGODB_HEALTH_ENABLED", "false");
                assertRefusedAndExited(app);

                try (ResultSet tables = statement.executeQuery("SELECT string_agg(tablename, ',') FROM pg_tables WHERE schemaname = 'public'")) {
                    tables.next();
                    assertEquals("org_platform_state", tables.getString(1), "nothing may create a table before the guard");
                }
            }
        }
    }

    @SuppressWarnings("resource")
    private static GenericContainer<?> eddi(Network network) {
        return new GenericContainer<>(IMAGE).withNetwork(network)
                .withEnv("QUARKUS_OIDC_TENANT_ENABLED", "false").withEnv("AUTHORIZATION_ENABLED", "false")
                .withEnv("EDDI_SECURITY_ALLOW_UNAUTHENTICATED", "true").withEnv("EDDI_MCP_ALLOW_UNAUTHENTICATED", "true")
                .withEnv("EDDI_SECRETSTORE_ALLOW_UNAUTHENTICATED", "true").withEnv("EDDI_ORG_LAYOUT_GUARD_WAIT", "PT5S")
                // the container is expected to exit: no readiness wait, and an exit is not a
                // launch failure
                .waitingFor(new AbstractWaitStrategy() {
                    @Override
                    protected void waitUntilReady() {
                    }
                }).withStartupCheckStrategy(new StartupCheckStrategy() {
                    @Override
                    public StartupStatus checkStartupState(DockerClient dockerClient, String containerId) {
                        return StartupStatus.SUCCESSFUL;
                    }
                });
    }

    /**
     * Starts the app, waits for the refusal and for the process to exit, and checks
     * the exit code.
     */
    private static void assertRefusedAndExited(GenericContainer<?> app) throws InterruptedException {
        try {
            app.start();
            long deadline = System.nanoTime() + EXIT_TIMEOUT.toNanos();
            InspectContainerResponse.ContainerState state = app.getDockerClient().inspectContainerCmd(app.getContainerId()).exec().getState();
            while (Boolean.TRUE.equals(state.getRunning()) && System.nanoTime() < deadline) {
                Thread.sleep(500);
                state = app.getDockerClient().inspectContainerCmd(app.getContainerId()).exec().getState();
            }
            assertFalse(Boolean.TRUE.equals(state.getRunning()), "EDDI kept running on a layout it does not support");
            assertNotEquals(0L, state.getExitCodeLong(), "the refusal must exit non-zero");
            String logs = app.getLogs();
            assertTrue(logs.contains("[SECURITY] The data layout requires level 1"), logs);
        } finally {
            app.stop();
        }
    }
}
