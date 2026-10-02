/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.deploy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The third-party images EDDI ships next to itself — MongoDB and Keycloak — are
 * pinned to ONE full patch version across every delivery path: the compose
 * files (root and the Manager's), the Kustomize manifests and the Helm chart.
 * <p>
 * They had drifted three ways: compose pinned {@code mongo:7.0.14} while Helm
 * and Kustomize floated on {@code mongo:7.0}, the Manager's stacks ran
 * {@code mongo:6.0} (end of life), and Keycloak was {@code 26.0} — a minor line
 * that no longer receives fixes — in the user-facing stacks but {@code 26.7} in
 * the auth E2E tier, so the gate tested a different identity provider from the
 * one users ran. A floating minor tag also changes under a deployment without a
 * diff.
 * <p>
 * The same class pins the single-writer update strategy: EDDI runs one replica,
 * and a surge pod during a rollout would be a second JVM against the same
 * database.
 */
class ThirdPartyImagePinsTest {

    private static final Pattern MONGO_IMAGE = Pattern.compile("(?<![\\w./-])mongo:([\\w.\\-]+)");
    private static final Pattern KEYCLOAK_IMAGE = Pattern.compile("quay\\.io/keycloak/keycloak:([\\w.\\-]+)");
    private static final Pattern PATCH_VERSION = Pattern.compile("\\d+\\.\\d+\\.\\d+");
    private static final YAMLMapper YAML = new YAMLMapper();

    @Test
    @DisplayName("MongoDB is one full patch version on every delivery path and release gate")
    void mongoIsPinnedOnce() throws IOException {
        TreeMap<String, TreeSet<String>> versions = collect(MONGO_IMAGE, true);
        JsonNode helm = helmValues().path("mongodb").path("image");
        assertEquals("mongo", helm.path("repository").asText(), "helm mongodb.image.repository");
        versions.computeIfAbsent(helm.path("tag").asText(), v -> new TreeSet<>()).add("helm/eddi/values.yaml");
        assertSinglePatchVersion("MongoDB", versions);
    }

    @Test
    @DisplayName("Keycloak is one full patch version on every delivery path")
    void keycloakIsPinnedOnce() throws IOException {
        TreeMap<String, TreeSet<String>> versions = collect(KEYCLOAK_IMAGE, false);
        JsonNode helm = helmValues().path("keycloak").path("image");
        assertEquals("quay.io/keycloak/keycloak", helm.path("repository").asText(), "helm keycloak.image.repository");
        versions.computeIfAbsent(helm.path("tag").asText(), v -> new TreeSet<>()).add("helm/eddi/values.yaml");
        assertSinglePatchVersion("Keycloak", versions);
    }

    @Test
    @DisplayName("an upgrade never runs a second EDDI JVM next to the first")
    void eddiIsRecreatedNotSurged() throws IOException {
        JsonNode strategy = helmValues().path("eddi").path("updateStrategy");
        assertEquals("Recreate", strategy.path("type").asText(),
                "helm eddi.updateStrategy must default to Recreate: a RollingUpdate surge pod is a second EDDI JVM "
                        + "against the same database for the length of every rollout");
        String template = read(Path.of("helm", "eddi", "templates", "deployment.yaml"));
        assertTrue(template.contains("toYaml .Values.eddi.updateStrategy"),
                "the Helm Deployment must render eddi.updateStrategy rather than a hard-coded strategy");
        assertFalse(template.contains("maxSurge"), "the Helm Deployment still hard-codes a surge");

        for (Path manifest : List.of(Path.of("k8s", "base", "eddi-deployment.yaml"), Path.of("k8s", "quickstart.yaml"))) {
            JsonNode deployment = null;
            for (JsonNode document : YAML.readValues(YAML.createParser(manifest.toFile()), JsonNode.class).readAll()) {
                if ("Deployment".equals(document.path("kind").asText()) && "eddi".equals(document.path("metadata").path("name").asText())) {
                    deployment = document;
                }
            }
            assertTrue(deployment != null, manifest + " has no `eddi` Deployment");
            assertEquals("Recreate", deployment.path("spec").path("strategy").path("type").asText(),
                    manifest + ": the eddi Deployment must use the Recreate strategy");
        }
    }

    private static void assertSinglePatchVersion(String what, TreeMap<String, TreeSet<String>> versions) {
        assertFalse(versions.isEmpty(), "no " + what + " image reference found — the sweep would be vacuous");
        assertEquals(1, versions.size(), what + " is pinned to more than one version: " + versions
                + ". Users, the Helm chart, the Kustomize manifests and the release gates have to run the same one.");
        String version = versions.firstKey();
        assertTrue(PATCH_VERSION.matcher(version).matches(), what + " is pinned to `" + version + "` in "
                + versions.get(version) + " — a floating tag that moves under a deployment without a diff. Pin a full "
                + "MAJOR.MINOR.PATCH release.");
    }

    /**
     * Every image reference in the compose files (root and {@code ui/*}), the
     * Kustomize manifests and, when {@code includeWorkflows}, the workflows —
     * comments excluded, since they describe history.
     */
    private static TreeMap<String, TreeSet<String>> collect(Pattern image, boolean includeWorkflows) throws IOException {
        List<Path> sources = new ArrayList<>();
        sources.addAll(list(Path.of(""), 1, "docker-compose", ".yml"));
        sources.addAll(list(Path.of("ui"), 2, "docker-compose", ".yml"));
        sources.addAll(list(Path.of("k8s"), 10, "", ".yaml"));
        if (includeWorkflows) {
            sources.addAll(list(Path.of(".github", "workflows"), 1, "", ".yml"));
        }
        TreeMap<String, TreeSet<String>> versions = new TreeMap<>();
        for (Path file : sources) {
            Matcher matcher = image.matcher(withoutComments(read(file)));
            while (matcher.find()) {
                versions.computeIfAbsent(matcher.group(1), v -> new TreeSet<>()).add(file.toString().replace('\\', '/'));
            }
        }
        return versions;
    }

    private static List<Path> list(Path root, int depth, String prefix, String suffix) throws IOException {
        try (Stream<Path> files = Files.walk(root, depth)) {
            return files.filter(Files::isRegularFile)
                    .filter(p -> !p.toString().contains("node_modules"))
                    .filter(p -> p.getFileName().toString().startsWith(prefix) && p.getFileName().toString().endsWith(suffix))
                    .sorted()
                    .toList();
        }
    }

    private static String withoutComments(String yaml) {
        StringBuilder out = new StringBuilder();
        for (String line : yaml.split("\n", -1)) {
            out.append(line.strip().startsWith("#") ? "" : line).append('\n');
        }
        return out.toString();
    }

    private static JsonNode helmValues() throws IOException {
        return YAML.readTree(Path.of("helm", "eddi", "values.yaml").toFile());
    }

    private static String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
