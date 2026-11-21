/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.deploy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Keeps the interim Java4Shell mitigation, {@code -XX:-UseBASE64Intrinsics}, on
 * every JVM this repository ships that decodes attacker-controlled Base64.
 *
 * <p>
 * CVE-2026-114938 (Red Hat RHSB-2026-018) is memory corruption in the AVX-512
 * VBMI accelerated path of {@code java.util.Base64.Decoder}, reachable with
 * malformed input and exploitable for code execution. EDDI decodes such input
 * before authentication (MIME encoded words and DKIM signatures of inbound
 * mail, webhook signature headers), and so does Keycloak. The base images
 * pinned here carry OpenJDK 25.0.5 (EDDI, demo) and 21.0.12.1 (Keycloak
 * 26.8.0); the fixes are 25.0.5.1 and 21.0.13.1, and Red Hat had not published
 * a fixed UBI image when this was written.
 *
 * <p>
 * Dropping the flag fails nothing else: the JVM runs, every test passes, and
 * the host is exposed again. So this test pins it in each of the five places.
 * <b>When the pinned images carry a fixed JDK, remove the flag from all five
 * and delete this test in the same change.</b>
 *
 * <p>
 * Plain file reads, no Docker, so it runs in the ordinary {@code mvn test}.
 * That the flag also takes effect was checked by hand with
 * {@code java -XX:-UseBASE64Intrinsics -XX:+PrintFlagsFinal -version} in each
 * pinned image ({@code UseBASE64Intrinsics = false {product} {command line}}):
 * it is a product flag there, so no {@code -XX:+UnlockDiagnosticVMOptions} is
 * needed.
 */
@DisplayName("Java4Shell (CVE-2026-114938) mitigation")
class Java4ShellMitigationTest {

    private static final String FLAG = "-XX:-UseBASE64Intrinsics";

    private static final Path DOCKERFILE = Path.of("src", "main", "docker", "Dockerfile");
    private static final Path DEMO_DOCKERFILE = Path.of("src", "main", "docker", "Dockerfile.demo");
    private static final Path COMPOSE_AUTH = Path.of("docker-compose.auth.yml");
    private static final Path HELM_KEYCLOAK = Path.of("helm", "eddi", "templates", "keycloak.yaml");
    private static final Path K8S_KEYCLOAK = Path.of("k8s", "overlays", "auth", "keycloak-statefulset.yaml");

    private static String read(Path path) {
        try {
            // Normalised so the patterns below hold on a CRLF (Windows) checkout as well.
            return Files.readString(path, StandardCharsets.UTF_8).replace("\r\n", "\n");
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + path, e);
        }
    }

    private static String activeLineStartingWith(Path path, String prefix) {
        List<String> lines = read(path).lines().map(String::strip).filter(line -> line.startsWith(prefix)).toList();
        if (lines.size() != 1) {
            fail("expected exactly one active '" + prefix + "' line in " + path + ", found " + lines.size());
        }
        return lines.get(0);
    }

    @Test
    @DisplayName("the production image carries the flag on JDK_JAVA_OPTIONS, which survives JAVA_OPTS overrides")
    void productionImage() {
        String line = activeLineStartingWith(DOCKERFILE, "ENV JDK_JAVA_OPTIONS=");
        assertTrue(line.contains(FLAG), DOCKERFILE + ": JDK_JAVA_OPTIONS must carry " + FLAG
                + " until the base image pin carries OpenJDK 25.0.5.1 or later. Found: " + line);
    }

    @Test
    @DisplayName("the demo image passes the flag on its bare java ENTRYPOINT")
    void demoImage() {
        String line = activeLineStartingWith(DEMO_DOCKERFILE, "ENTRYPOINT [");
        assertTrue(line.contains("\"" + FLAG + "\""),
                DEMO_DOCKERFILE + ": the ENTRYPOINT must pass " + FLAG + " as its own argument. Found: " + line);
        assertTrue(line.indexOf(FLAG) < line.indexOf("\"-jar\""),
                DEMO_DOCKERFILE + ": " + FLAG + " must come before -jar, or java hands it to EDDI as a program argument");
    }

    @Test
    @DisplayName("the compose Keycloak service sets JAVA_OPTS_APPEND")
    void composeKeycloak() {
        String compose = read(COMPOSE_AUTH);
        Matcher service = Pattern.compile("(?ms)^  keycloak:\\n(.*?)(?=^  \\S|^\\S|\\z)").matcher(compose);
        assertTrue(service.find(), COMPOSE_AUTH + ": no top-level 'keycloak' service");
        assertTrue(Pattern.compile("(?m)^\\s+JAVA_OPTS_APPEND:\\s*\"" + Pattern.quote(FLAG) + "\"").matcher(service.group(1)).find(),
                COMPOSE_AUTH + ": the keycloak service must set JAVA_OPTS_APPEND: \"" + FLAG + "\"");
    }

    @Test
    @DisplayName("the Helm and kustomize Keycloak pods set JAVA_OPTS_APPEND")
    void kubernetesKeycloak() {
        Pattern env = Pattern.compile("- name: JAVA_OPTS_APPEND\\s*\\n\\s*value: \"" + Pattern.quote(FLAG) + "\"");
        for (Path manifest : List.of(HELM_KEYCLOAK, K8S_KEYCLOAK)) {
            assertTrue(env.matcher(read(manifest)).find(),
                    manifest + ": the Keycloak container must set JAVA_OPTS_APPEND to \"" + FLAG + "\"");
        }
    }
}
