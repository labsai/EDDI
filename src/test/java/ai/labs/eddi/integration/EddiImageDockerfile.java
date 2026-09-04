/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integration;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Derives the container ITs' Dockerfile from the production one, so the image
 * the ITs prove bootable is the image that gets pushed.
 * <p>
 * It used to be a hand-copied duplicate: {@code ContainerBaseIT} embedded a
 * whole Dockerfile as a Java text block, every container-based IT ran against
 * <em>that</em>, and it had already drifted three ways from
 * {@code src/main/docker/Dockerfile} — no digest on the base image (so the ITs
 * could validate a different base layer than the release is pinned to, which is
 * the one thing the digest pin exists to prevent), the whole {@code docs/} tree
 * instead of the pruned top-level pages, and no {@code HEALTHCHECK}. The
 * {@code ENV} and {@code ENTRYPOINT} lines were byte-identical, which is
 * exactly what made the drift easy to miss. Any regression confined to the
 * production Dockerfile — a changed COPY path, a new ENV, a wrong
 * {@code /deployments} layout after a Quarkus packaging change — passed every
 * pre-publish gate, because nothing that ran the production Dockerfile ever
 * started a container from it.
 * <p>
 * <b>The one transformation.</b> Testcontainers builds from a context assembled
 * file by file rather than from the repository root, and {@code target/} is
 * mapped in as {@code quarkus-app} (the root {@code .dockerignore} is a
 * deny-all plus allowlist, which the classic builder cannot re-include paths
 * through). So the {@code target/quarkus-app/} prefix on the four COPY sources
 * is rewritten to {@code quarkus-app/} and nothing else changes.
 * {@code licenses/} and {@code docs/} are supplied to the context under their
 * own names, so those COPY lines are used verbatim.
 *
 * @see ContainerBaseIT#buildEddiImage(String)
 */
final class EddiImageDockerfile {

    /** The single source of truth for the published image. */
    static final Path PRODUCTION_DOCKERFILE = Path.of("src", "main", "docker", "Dockerfile");

    /** COPY source prefix in the production build context. */
    static final String PRODUCTION_APP_PREFIX = "target/quarkus-app/";

    /** The same directory as Testcontainers maps it into the test context. */
    static final String TEST_APP_PREFIX = "quarkus-app/";

    /**
     * Every top-level name {@link ContainerBaseIT#buildEddiImage(String)} puts into
     * the ITs' build context.
     * <p>
     * The context is assembled entry by entry rather than being the repository
     * root, so a COPY whose source is not under one of these fails the image build
     * with {@code COPY failed: file not found} — inside Testcontainers, on the
     * first CI run after {@code src/main/docker/Dockerfile} changed, presenting as
     * an image-build error rather than as the test regression it is.
     * {@code EddiImageDockerfileTest} checks the derived file against this list in
     * the ordinary {@code mvn test} pass instead, so the failure lands where the
     * change was made.
     */
    static final List<String> CONTEXT_ROOTS = List.of("quarkus-app", "licenses", "docs");

    private EddiImageDockerfile() {
    }

    /**
     * Reads the production Dockerfile and returns it rewritten for the ITs' build
     * context.
     *
     * @throws IllegalStateException
     *             if the production Dockerfile is missing, or if it no longer
     *             copies from {@code target/quarkus-app/} — either means this
     *             derivation has stopped tracking the real file, and silently
     *             building something else is the failure mode being fixed here.
     */
    static String forTestContext() {
        return forTestContext(read(PRODUCTION_DOCKERFILE));
    }

    /** Package-visible for the unit test, which supplies its own input. */
    static String forTestContext(String productionDockerfile) {
        if (!productionDockerfile.contains(PRODUCTION_APP_PREFIX)) {
            throw new IllegalStateException(PRODUCTION_DOCKERFILE
                    + " no longer copies from '" + PRODUCTION_APP_PREFIX
                    + "'. Update EddiImageDockerfile to match it rather than letting the ITs build a different image.");
        }
        String rewritten = productionDockerfile.replace(PRODUCTION_APP_PREFIX, TEST_APP_PREFIX);
        if (rewritten.contains(PRODUCTION_APP_PREFIX)) {
            throw new IllegalStateException("Rewrite left a '" + PRODUCTION_APP_PREFIX + "' path behind");
        }
        return rewritten;
    }

    private static String read(Path path) {
        if (!Files.isRegularFile(path)) {
            throw new IllegalStateException("Production Dockerfile not found at " + path.toAbsolutePath()
                    + ". Container ITs run with the project root as the working directory.");
        }
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + path.toAbsolutePath(), e);
        }
    }
}
