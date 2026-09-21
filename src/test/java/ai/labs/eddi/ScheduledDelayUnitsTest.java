/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi;

import io.quarkus.scheduler.Scheduled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code @Scheduled(delay = N)} is measured in <em>minutes</em>, not seconds.
 * <p>
 * {@link Scheduled#delayUnit()} defaults to {@link TimeUnit#MINUTES}, and
 * nothing about the annotation site says so. Two jobs were written as though it
 * were seconds: the deployment check started ten minutes after boot rather than
 * ten seconds, and the daily maintenance job — which undeploys superseded agent
 * versions and ends idle conversations — first ran five hours in, so a pod
 * restarted more often than that never ran it at all and old versions stayed
 * deployed indefinitely.
 * <p>
 * The string form {@code delayed = "10s"} carries its unit, which is why every
 * other scheduled method in this repository already uses it. This test makes
 * that the rule rather than the convention.
 */
@DisplayName("scheduled delay units")
class ScheduledDelayUnitsTest {

    /**
     * The numeric form. Matches {@code delay = 10} and {@code delay=10} but not
     * {@code delayed = "10s"}, because {@code delay} must be followed by optional
     * whitespace and then {@code =}.
     */
    private static final Pattern NUMERIC_DELAY = Pattern.compile("@Scheduled\\([^)]*\\bdelay\\s*=", Pattern.DOTALL);

    /** Any scheduled method, so the test can prove it is looking at something. */
    private static final Pattern ANY_SCHEDULED = Pattern.compile("@Scheduled\\(");

    private static Path repoRoot() {
        Path root = Path.of("").toAbsolutePath();
        assertTrue(Files.isRegularFile(root.resolve("pom.xml")), "expected the working directory to be the project root, was " + root);
        return root;
    }

    private static List<Path> javaSources() {
        Path src = repoRoot().resolve(Path.of("src", "main", "java"));
        List<Path> found = new ArrayList<>();
        try {
            Files.walkFileTree(src, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (file.getFileName().toString().endsWith(".java")) {
                        found.add(file);
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return found;
    }

    private static String read(Path p) {
        try {
            return Files.readString(p, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Pins the premise. If a future Quarkus changes the default unit to seconds,
     * the rule below stops being about correctness and this test should be
     * revisited rather than silently kept.
     */
    @Test
    @DisplayName("Quarkus still measures the numeric delay in minutes")
    void numericDelayIsStillMinutes() throws NoSuchMethodException {
        assertEquals(TimeUnit.MINUTES, Scheduled.class.getMethod("delayUnit").getDefaultValue(),
                "the numeric delay's default unit has changed; the rule this test enforces may no longer be needed");
    }

    @Test
    @DisplayName("no scheduled method uses the unit-less numeric delay")
    void noSchedulerUsesTheNumericDelay() {
        List<String> offenders = new ArrayList<>();
        int scheduledMethods = 0;
        for (Path file : javaSources()) {
            String body = read(file);
            Matcher any = ANY_SCHEDULED.matcher(body);
            while (any.find()) {
                scheduledMethods++;
            }
            if (NUMERIC_DELAY.matcher(body).find()) {
                offenders.add(repoRoot().relativize(file).toString());
            }
        }

        assertTrue(scheduledMethods > 5, "expected to find the project's scheduled methods; found only " + scheduledMethods
                + ". The pattern has probably drifted from how they are annotated.");
        assertTrue(offenders.isEmpty(), "these use @Scheduled(delay = N), which is N MINUTES rather than N seconds. Use the "
                + "string form, delayed = \"10s\", which carries its unit: " + offenders);
    }
}
