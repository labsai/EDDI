/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.testing;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Runs a {@code main} class from the test classpath in a child JVM, for tests
 * that must prove a heap bound: the child gets its own {@code -Xmx}, whatever
 * heap surefire or failsafe run with, and needs no {@code pom.xml} change.
 * <p>
 * The classpath goes through an {@code @argfile} (on Windows it exceeds the
 * command-line limit), and the child's output goes to a file, so a full pipe
 * can never block it.
 */
public final class ChildJvm {

    /**
     * How the child ended.
     *
     * @param exitCode
     *            the exit code, or {@code -1} if it was killed on timeout
     * @param output
     *            stdout and stderr, interleaved
     * @param timedOut
     *            whether it was killed on timeout
     */
    public record Result(int exitCode, String output, boolean timedOut) {

        /** The last non-blank line of the output, or empty. */
        public String lastLine() {
            List<String> lines = output.lines().filter(line -> !line.isBlank()).toList();
            return lines.isEmpty() ? "" : lines.getLast().strip();
        }
    }

    private ChildJvm() {
    }

    /**
     * @param mainClass
     *            a class with a {@code main} method, on this JVM's classpath
     * @param jvmOptions
     *            e.g. {@code -Xmx128m}
     * @param args
     *            the program arguments
     * @param timeout
     *            after which the child is killed
     */
    public static Result run(Class<?> mainClass, List<String> jvmOptions, List<String> args, Duration timeout)
            throws IOException, InterruptedException {
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        Path java = Path.of(System.getProperty("java.home"), "bin", windows ? "java.exe" : "java");
        Path argFile = Files.createTempFile("eddi-child-jvm-", ".args");
        Path output = Files.createTempFile("eddi-child-jvm-", ".out");
        try {
            Files.writeString(argFile, "-cp\n" + quote(System.getProperty("java.class.path")) + "\n", StandardCharsets.UTF_8);
            List<String> command = new ArrayList<>();
            command.add(java.toString());
            command.addAll(jvmOptions);
            command.add("@" + argFile);
            command.add(mainClass.getName());
            command.addAll(args);
            Process process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(output.toFile()).start();
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                process.waitFor(10, TimeUnit.SECONDS);
                return new Result(-1, Files.readString(output, StandardCharsets.UTF_8), true);
            }
            return new Result(process.exitValue(), Files.readString(output, StandardCharsets.UTF_8), false);
        } finally {
            Files.deleteIfExists(argFile);
            Files.deleteIfExists(output);
        }
    }

    /** In an argfile, a quoted token treats a backslash as an escape character. */
    private static String quote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
