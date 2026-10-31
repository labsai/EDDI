/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.deploy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the review hardening of the operator scripts and manifests. These files
 * are not executed by the unit run, so the checks are on their text: each one
 * fails if the old, weaker construct comes back.
 */
class InfraReviewHardeningTest {

    private static String read(String file) throws IOException {
        return Files.readString(Path.of(file), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    @Test
    @DisplayName("install.sh never puts the Keycloak bearer token on a curl command line")
    void keycloakTokenStaysOffArgv() throws IOException {
        String sh = read("install.sh");
        // The only place the header text may appear is the helper that writes it to a
        // 0600 config file.
        long occurrences = sh.lines().filter(l -> l.contains("Authorization: Bearer")).count();
        assertTrue(occurrences == 1, "only kc_authed_curl may build the header, found " + occurrences);
        assertTrue(sh.contains("curl -K \"$cfg\""), "the header must travel in a curl config file");
        assertFalse(sh.contains("-H \"Authorization: Bearer"), "-H puts the token in argv");
    }

    @Test
    @DisplayName("install.sh verifies the downloaded realm and asks Docker before sending admin passwords")
    void installerVerifiesRealmAndListener() throws IOException {
        String sh = read("install.sh");
        assertTrue(sh.contains("verify_download \"keycloak/eddi-realm.json\" \"$realm_defs\""));
        assertTrue(sh.contains("compose_service_running keycloak"));
        assertTrue(sh.contains("compose_service_running grafana"));
    }

    @Test
    @DisplayName("provision-vm.sh does not ship the vault master key in startup metadata or the install log")
    void provisionVmKeepsVaultKeyOutOfMetadata() throws IOException {
        String sh = read("gcp/provision-vm.sh");
        assertFalse(sh.contains("install_flags+=\" --vault-key"), "the key would be uploaded as instance metadata");
        assertFalse(sh.contains("VAULT_KEY_ARG"));
    }

    @Test
    @DisplayName("the compose bind guards fail closed on any spelling of the unauthenticated flag")
    void bindGuardsAreCaseInsensitive() throws IOException {
        for (String file : List.of("docker-compose.yml", "docker-compose.cluster.yml", "docker-compose.postgres-only.yml")) {
            String yml = read(file);
            assertFalse(yml.contains("\"$$EDDI_SECURITY_ALLOW_UNAUTHENTICATED\" != \"true\""), file + " compares case-sensitively");
            assertTrue(yml.contains("tr '[:upper:]' '[:lower:]'") && yml.contains("false|0|no|off) exit 0"), file);
        }
    }

    @Test
    @DisplayName("keycloak-admin-rotate marks the volume verified only after logging in with the configured credentials")
    void adminRotateRequiresPositiveLogin() throws IOException {
        String yml = read("docker-compose.auth.yml");
        int failedDefault = yml.indexOf("--password admin >/dev/null 2>&1; then");
        int configured = yml.indexOf("--user \"$$KC_ADMIN_USER\" >/dev/null 2>&1; then", failedDefault);
        assertTrue(failedDefault > 0 && configured > failedDefault, "configured credentials must be tried before the marker");
        int firstTouch = yml.indexOf("touch \"$$marker\"", failedDefault);
        assertTrue(firstTouch > configured, "the marker must not be written on a bare failed default login");
    }

    @Test
    @DisplayName("the Helm chart refuses credentials inside nats.externalUrl")
    void helmRefusesCredentialBearingNatsUrl() throws IOException {
        assertTrue(read("helm/eddi/templates/configmap.yaml").contains("contains \"@\" (default \"\" .Values.nats.externalUrl)"));
        assertTrue(read("helm/eddi/templates/secret.yaml").contains("nats.externalPassword"));
    }

    @Test
    @DisplayName("check-live follows redirects only to the configured origins")
    void checkLiveComparesOrigins() throws IOException {
        String js = read("docs-site/scripts/check-live.mjs");
        assertFalse(js.contains("current.startsWith(base)"), "a string prefix admits docs.eddi.technology.evil.example");
        assertTrue(js.contains("allowedOrigins.has(new URL(current).origin)"));
    }

    @Test
    @DisplayName("the cluster-demo mock LLM bounds its reads, and the load-test state is not in the shared results directory")
    void demoScriptsAreBounded() throws IOException {
        String mock = read("scripts/cluster-demo/mock_llm.py");
        assertTrue(mock.contains("timeout = 10") && mock.contains("MAX_BODY"));
        String lt = read("scripts/loadtest/loadtest.py");
        assertFalse(lt.contains("os.path.join(RESULTS, \".state.json\")"));
        assertTrue(lt.contains("os.replace(tmp, STATE)"));
    }

    @Test
    @DisplayName("the demo seeder and create-secrets.ps1 keep key material out of argv and world-readable files")
    void secretsStayPrivate() throws IOException {
        String seed = read("src/main/docker/seed-demo-agent.sh");
        assertTrue(seed.contains("--data-binary @-"));
        assertFalse(seed.contains("-d \"{\\\"value\\\""), "the key must not be a curl -d argument");
        String ps1 = read("k8s/create-secrets.ps1");
        assertTrue(ps1.indexOf("SetUnixFileMode") > 0 && ps1.indexOf("SetUnixFileMode") < ps1.indexOf("Set-Content -Path $secretFile"));
    }
}
