/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.audit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ties the audit ledger's default dead-letter path to the shipped container
 * image.
 * <p>
 * {@code StandardOpenOption.CREATE} creates the file, never its parent
 * directory. The default {@code eddi.audit.dead-letter-path} lives under
 * {@code /opt/eddi/data}, which the image did not create and which {@code UID
 * 185} cannot create under {@code /opt} at runtime — so on the documented
 * {@code docker run} / docker-compose quick start every dead-letter write threw
 * {@code NoSuchFileException} into a swallowed catch, and the audit entries the
 * ledger had to abandon were gone outright rather than recoverable. Only the
 * Kubernetes manifests mount a volume there. That is the sink
 * {@code AuditLedgerService.undeliveredSequences} calls "the durable evidence"
 * and that {@code docs/incident-response.md} tells an operator to go and read.
 * <p>
 * A Dockerfile is not unit-testable in the sense of running it, but the
 * invariant that matters is structural and the repository already tests project
 * files this way (see {@code ai.labs.eddi.docs.MetricsDashboardCoverageTest},
 * which scans the Java sources to keep a dashboard honest). The default path is
 * read out of {@link AuditLedgerService} rather than hard-coded here, so moving
 * the default without provisioning the new location fails the build instead of
 * shipping a silently unwritable sink.
 */
@DisplayName("audit dead-letter sink — container image provisioning")
class AuditDeadLetterImageProvisioningTest {

    private static final Path DOCKERFILE = Path.of("src", "main", "docker", "Dockerfile");
    private static final Path LEDGER_SOURCE = Path.of("src", "main", "java", "ai", "labs", "eddi",
            "engine", "audit", "AuditLedgerService.java");

    /** The {@code @ConfigProperty} default for the dead-letter file. */
    private static final Pattern DEAD_LETTER_DEFAULT = Pattern.compile(
            "\"eddi\\.audit\\.dead-letter-path\"\\s*,\\s*defaultValue\\s*=\\s*\"([^\"]+)\"");

    /**
     * The UID the image finally drops to — everything it writes must be writable by
     * it.
     */
    private static final Pattern RUNTIME_USER = Pattern.compile("(?m)^USER\\s+(\\d+)\\s*$");

    @Test
    @DisplayName("the image creates the default dead-letter directory and hands it to the runtime user")
    void imageProvisionsTheDefaultDeadLetterDirectory() throws IOException {
        String dockerfile = Files.readString(DOCKERFILE, StandardCharsets.UTF_8);
        String defaultPath = defaultDeadLetterPath();

        // The parent is what has to exist; the file itself is created on first write.
        String directory = defaultPath.substring(0, defaultPath.lastIndexOf('/'));
        assertTrue(directory.startsWith("/"),
                "the default must be an absolute path so the image can provision it, was: " + defaultPath);

        assertTrue(dockerfile.contains("mkdir -p " + directory),
                "the image must create " + directory + " — the runtime user cannot create it under /opt itself");

        String runtimeUid = runtimeUid(dockerfile);
        assertTrue(coversDirectory(dockerfile, "chown -R " + runtimeUid + ":0 ", directory),
                "and give " + directory + " (or an ancestor) to UID " + runtimeUid + ", the user the image drops to");
        assertTrue(coversDirectory(dockerfile, "chmod -R 775 ", directory),
                "with group-write, matching how /deployments/tmp is provisioned for the same reason");
    }

    /**
     * Whether a {@code RUN} step applies {@code command} recursively to the
     * directory or to one of its ancestors — {@code chown -R 185:0 /opt/eddi}
     * covers {@code /opt/eddi/data}.
     */
    private static boolean coversDirectory(String dockerfile, String command, String directory) {
        Matcher m = Pattern.compile(Pattern.quote(command) + "(\\S+)").matcher(dockerfile);
        while (m.find()) {
            String target = m.group(1);
            if (directory.equals(target) || directory.startsWith(target + "/")) {
                return true;
            }
        }
        return false;
    }

    /**
     * The provisioning has to happen while the image is still {@code root} and
     * before the final {@code USER}; a {@code mkdir} under {@code /opt} after the
     * drop would fail the build, and one before {@code USER root} would run as
     * whatever the base image left behind.
     */
    @Test
    @DisplayName("the directory is created as root, before the image drops privileges")
    void directoryIsCreatedWhileStillRoot() throws IOException {
        String dockerfile = Files.readString(DOCKERFILE, StandardCharsets.UTF_8);
        String defaultPath = defaultDeadLetterPath();
        String directory = defaultPath.substring(0, defaultPath.lastIndexOf('/'));

        int rootAt = dockerfile.indexOf("USER root");
        int mkdirAt = dockerfile.indexOf("mkdir -p " + directory);
        Matcher user = RUNTIME_USER.matcher(dockerfile);
        assertTrue(user.find(), "the image must drop to a non-root UID");
        int dropAt = user.start();

        assertTrue(rootAt >= 0 && rootAt < mkdirAt, "the mkdir must come after USER root");
        assertTrue(mkdirAt < dropAt, "and before the image drops to UID " + user.group(1));
    }

    private static String defaultDeadLetterPath() throws IOException {
        Matcher m = DEAD_LETTER_DEFAULT.matcher(Files.readString(LEDGER_SOURCE, StandardCharsets.UTF_8));
        assertTrue(m.find(), "eddi.audit.dead-letter-path must declare a defaultValue in AuditLedgerService");
        return m.group(1);
    }

    private static String runtimeUid(String dockerfile) {
        Matcher m = RUNTIME_USER.matcher(dockerfile);
        assertTrue(m.find(), "the image must declare the UID it runs as");
        String uid = m.group(1);
        assertEquals("185", uid, "the Red Hat UBI runtime user; change this test deliberately if the image moves");
        return uid;
    }
}
