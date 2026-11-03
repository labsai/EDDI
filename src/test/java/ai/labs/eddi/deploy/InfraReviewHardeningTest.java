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
        // The listener check matches this installation's project and the port the
        // credentials go to, and withholds them when Docker cannot be asked.
        assertTrue(sh.contains("--filter \"label=com.docker.compose.project=$(compose_project_name)\""));
        assertTrue(sh.contains("--filter \"publish=$2\""));
        assertTrue(sh.contains("--filter \"status=running\" 2>/dev/null) || return 1"), "a failed docker query must fail closed");
        // Every password grant goes through the check, not only keycloak_admin_login's.
        assertTrue(sh.contains("compose_service_running keycloak \"${kc_base##*:}\" || return 0"));
        // A token is written into curl config syntax only if it is plain token
        // characters.
        int guard = sh.indexOf("[[ \"$token\" =~ ^[A-Za-z0-9._~+/=-]+$ ]] || return 1");
        assertTrue(guard > 0 && guard < sh.indexOf("printf 'header = \"Authorization: Bearer"));
        // A failed realm download leaves no unverified partial file behind.
        assertTrue(sh.contains("      : > \"$realm_defs\""));
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
    @DisplayName("keycloak-admin-rotate checks the original admin account and marks the volume only after a configured login")
    void adminRotateRequiresPositiveLogin() throws IOException {
        String yml = read("docker-compose.auth.yml");
        int start = yml.indexOf("  keycloak-admin-rotate:\n");
        String rotate = yml.substring(start, yml.indexOf("\n  keycloak:\n", start));
        // kcadm reads the configured password from KC_CLI_PASSWORD, so it never sits in
        // argv.
        assertTrue(rotate.contains("KC_CLI_PASSWORD: \"${KC_BOOTSTRAP_ADMIN_PASSWORD:-admin}\""));
        assertFalse(rotate.contains("--password \"$$KC_BOOTSTRAP_ADMIN_PASSWORD\""), "the configured password must not be a kcadm argument");
        // A renamed admin user leaves the original admin/admin account behind.
        int originalAdmin = rotate.indexOf("[ \"$$KC_ADMIN_USER\" != \"admin\" ] && login_default admin; then rotate admin; fi");
        int configured = rotate.indexOf("--user \"$$KC_ADMIN_USER\" </dev/null >/dev/null 2>&1; then");
        int touch = rotate.indexOf("touch \"$$marker\"");
        assertTrue(originalAdmin > 0 && configured > originalAdmin, "the original admin account must be checked before the configured login");
        assertTrue(touch > configured && rotate.indexOf("touch \"$$marker\"", touch + 1) < 0,
                "the marker is written once, after the configured login succeeded");
    }

    @Test
    @DisplayName("the Helm chart refuses credentials inside nats.externalUrl")
    void helmRefusesCredentialBearingNatsUrl() throws IOException {
        assertTrue(read("helm/eddi/templates/configmap.yaml").contains("contains \"@\" (default \"\" .Values.nats.externalUrl)"));
        assertTrue(read("helm/eddi/templates/secret.yaml").contains("nats.externalPassword"));
    }

    @Test
    @DisplayName("the Helm chart sends external NATS credentials only over TLS and never drops them silently")
    void helmGuardsExternalNatsCredentials() throws IOException {
        String configmap = read("helm/eddi/templates/configmap.yaml");
        assertTrue(configmap.contains("(hasPrefix \"tls://\" $s) (hasPrefix \"wss://\" $s)"));
        assertTrue(configmap.contains("(not .Values.nats.externalAllowPlaintext)"));
        String secret = read("helm/eddi/templates/secret.yaml");
        assertTrue(secret.contains("nats.externalPassword is set but nats.externalUsername is not"));
        assertTrue(secret.contains("nats.externalUsername/externalPassword are set but eddi.existingSecret is too"));
        assertTrue(read("helm/eddi/values.yaml").contains("externalAllowPlaintext: false"));
        // a truthy string such as "false" must not switch the plaintext guard off
        assertTrue(configmap.contains("(kindIs \"bool\" .Values.nats.externalAllowPlaintext)"));
    }

    @Test
    @DisplayName("the Helm chart escapes external NATS credentials before writing them into a properties file")
    void helmEscapesExternalNatsCredentials() throws IOException {
        String secret = read("helm/eddi/templates/secret.yaml");
        assertTrue(secret.contains("eddi.nats.username={{ include \"eddi.propertyValue\" .Values.nats.externalUsername }}"));
        assertTrue(secret.contains("eddi.nats.password={{ include \"eddi.propertyValue\" "));
        String helpers = read("helm/eddi/templates/_helpers.tpl");
        assertTrue(helpers.contains("define \"eddi.propertyValue\""));
        assertTrue(helpers.contains("replace \"\\\\\" \"\\\\\\\\\""), "backslash is doubled");
        assertTrue(helpers.contains("replace \"${\" \"$${\""), "a property expression stays literal");
        assertTrue(helpers.contains("hasPrefix \" \" $v"), "a leading blank survives Properties.load");
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
        // A 0700 directory made in one call (mktemp -d), not a chmod after the file
        // exists; and before the -Force delete, so its failure cannot strand a cluster
        // without its Secret. SetUnixFileMode would need PowerShell 7.3+.
        int privateDir = ps1.indexOf("$secretDir = & mktemp -d");
        assertTrue(privateDir > 0 && privateDir < ps1.indexOf("kubectl delete secret eddi-secrets"));
        assertTrue(ps1.indexOf("$secretFile = Join-Path $secretDir") > privateDir);
        assertFalse(ps1.contains("SetUnixFileMode"));
    }
}
