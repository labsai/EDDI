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
import java.util.regex.Pattern;

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
        assertTrue(sh.contains("curl --noproxy 127.0.0.1 -K \"$cfg\""), "the header must travel in a curl config file");
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
    @DisplayName("install.sh sends credentials to 127.0.0.1, the address the ports are published on, not to a name that may resolve to ::1")
    void installerCredentialRequestsUseIpv4Loopback() throws IOException {
        String sh = read("install.sh");
        for (String base : List.of("kc_base=", "g_base=")) {
            sh.lines().filter(l -> l.contains(base + "\"http://") || l.contains(" " + base + "\"http://")).forEach(l -> {
                assertFalse(l.contains("http://localhost"), "credential base still uses localhost: " + l.strip());
                assertTrue(l.contains("http://127.0.0.1:"), l.strip());
            });
        }
        assertTrue(sh.lines().filter(l -> l.contains("kc_base=\"http://127.0.0.1:")).count() >= 4);
        assertTrue(sh.lines().filter(l -> l.contains("g_base=\"http://127.0.0.1:")).count() >= 2);
    }

    @Test
    @DisplayName("both installers give a FRESH install its own MONGO_APP_PASSWORD and never invent one for an existing .env")
    void installersGenerateMongoAppPasswordOnlyForFreshInstalls() throws IOException {
        String sh = read("install.sh");
        assertTrue(sh.contains("MONGO_APP_PASSWORD=$(env_file_value MONGO_APP_PASSWORD)"), "a stored value must be carried forward");
        assertTrue(sh.contains(
                "if [[ \"${DB_CHOICE:-1}\" != \"2\" && -z \"$MONGO_APP_PASSWORD\" && ! -f \"$EDDI_DIR/.env\" ]]; then\n    MONGO_APP_PASSWORD=$(generate_password)"),
                "generated only when there is no .env at all");
        assertTrue(sh.contains("echo \"MONGO_APP_PASSWORD='${MONGO_APP_PASSWORD}'\" >> \"$EDDI_DIR/.env\""));
        String ps1 = read("install.ps1");
        assertTrue(ps1.contains("$script:MongoAppSecret = Get-EnvFileValue \"MONGO_APP_PASSWORD\""));
        assertTrue(ps1.contains(
                "-not $script:MongoAppSecret -and -not (Test-Path (Join-Path -Path $EddiDir -ChildPath \".env\"))) {\n        $script:MongoAppSecret = Get-StackPassword"));
        assertTrue(ps1.contains("$publishedPorts[\"MONGO_APP_PASSWORD\"] = \"'$($script:MongoAppSecret)'\""));
    }

    @Test
    @DisplayName("PR #1239 review: EDDI_DIR is set before the Keycloak port lookup, credential curls bypass proxies, an exported Mongo password wins")
    void reviewFollowUps() throws IOException {
        String vm = read("gcp/provision-vm.sh");
        int dir = vm.indexOf("  EDDI_DIR=\"/root/.eddi\"\n");
        int lookup = vm.indexOf("KC_PORT=\\$(grep -m1 '^KEYCLOAK_PORT='");
        assertTrue(dir > 0 && lookup > dir, "the lookup would read /.env and always fall back to 8180");
        assertTrue(vm.indexOf("  EDDI_DIR=\"/root/.eddi\"\n", dir + 1) < 0, "assigned once");

        String sh = read("install.sh");
        assertTrue(sh.split("curl --noproxy 127.0.0.1 ", -1).length - 1 == 6,
                "password grant, kc_authed_curl, Grafana health + status and both Grafana PUTs must bypass a configured proxy");

        assertTrue(read("install.ps1").contains("$MongoAppSecret = $env:MONGO_APP_PASSWORD"),
                "an exported value is what Compose uses, so it is what must be written to .env");

        String driver = read("scripts/loadtest/drivers/stream_driver.py");
        assertFalse(driver.contains("readexactly(size)"), "a whole peer-declared chunk would be allocated at once");
        assertTrue(driver.contains("readexactly(min(65536, left))"));

        String lt = read("scripts/loadtest/loadtest.py");
        assertFalse(lt.contains("os.chmod(tmp"), "chmod by pathname follows a symlink swapped in at the temp name");
        assertTrue(lt.contains("os.fchmod(f.fileno(), mode)") && lt.contains("mode = 0o666 & ~umask"));

        assertTrue(read("docs/kubernetes.md").contains("`namespaceSelector` **in the same `from` entry**"));
        assertTrue(read("src/main/resources/application.properties").contains("answered anonymously for GET"));
    }

    @Test
    @DisplayName("PR #1239 outside-diff review: Grafana health and GCP Keycloak calls bypass proxies, ownership is rechecked per attempt, ps1 keeps inner quotes")
    void outsideDiffReviewFollowUps() throws IOException {
        String sh = read("install.sh");
        assertTrue(sh.contains("until curl --noproxy 127.0.0.1 -sf -o /dev/null \"${g_base}/api/health\""));

        String vm = read("gcp/provision-vm.sh");
        assertTrue(vm.contains("| curl --noproxy 127.0.0.1 -K - "), "kc_curl");
        assertTrue(vm.contains("| curl --noproxy 127.0.0.1 -sf -X POST"), "the password grant");
        int loop = vm.indexOf("for attempt in \\$(seq 1 18); do");
        int owned = vm.indexOf("KC_OWNED=\\$(docker ps", loop);
        int grant = vm.indexOf("curl --noproxy 127.0.0.1 -sf -X POST", loop);
        assertTrue(loop > 0 && owned > loop && grant > owned, "the ownership check must run inside the retry loop, before the grant");
        assertTrue(vm.indexOf("KC_OWNED=\\$(docker ps") == owned, "no single early ownership check outside the loop");

        // The startup script is an UNQUOTED heredoc: an unescaped backtick (even in a
        // comment) runs as
        // command substitution when the provisioner generates it.
        int start = vm.indexOf("cat <<STARTUP_SCRIPT");
        String heredoc = vm.substring(start, vm.indexOf("\nSTARTUP_SCRIPT", start));
        assertFalse(Pattern.compile("(?<!\\\\)`").matcher(heredoc).find(), "unescaped backtick in the startup-script heredoc");

        assertTrue(read("src/main/resources/application.properties").contains("only the metrics permission keeps the role restriction"));

        String ps1 = read("install.ps1");
        assertFalse(ps1.contains(".Trim().Trim('\"', \"'\")"), "Trim() with a quote set eats quotes that belong to the value");
        assertTrue(ps1.contains("$value[$value.Length - 1] -eq $value[0]"), "only a matching outer pair is removed");
    }

    @Test
    @DisplayName("provision-vm.sh sends Keycloak admin credentials only to the port Docker reports, and the token not in argv")
    void provisionVmKeycloakCallsAreScoped() throws IOException {
        String sh = read("gcp/provision-vm.sh");
        // Port 8180 stays in prose and the tunnel hint, but no request or proxy_pass
        // targets it.
        assertFalse(sh.contains("http://localhost:8180"), "a fixed 8180 may be someone else's listener");
        assertTrue(sh.contains("proxy_pass         http://127.0.0.1:__KC_PORT__;") && sh.contains("-e \"s|__KC_PORT__|"));
        assertTrue(sh.contains("--filter \"label=com.docker.compose.service=keycloak\""));
        assertTrue(sh.contains("--filter \"publish=\\${KC_PORT}\""));
        assertFalse(sh.contains("-H \"Authorization: Bearer"), "-H puts the token in argv");
        assertTrue(sh.contains("curl --noproxy 127.0.0.1 -K - "));
    }

    @Test
    @DisplayName("ci.yml escapes Slack markup in the contributor-controlled title before it reaches a mrkdwn field")
    void slackTitleIsEscaped() throws IOException {
        String ci = read(".github/workflows/ci.yml");
        int escape = ci.indexOf("s/&/\\&amp;/g' -e 's/</\\&lt;/g' -e 's/>/\\&gt;/g'");
        int payload = ci.indexOf("--arg commit \"$COMMIT_MSG\"");
        assertTrue(escape > 0 && payload > escape, "COMMIT_MSG must be escaped before it goes into the payload");
    }

    @Test
    @DisplayName("the load-test writers replace result files instead of following a symlink in the shared results directory")
    void loadtestResultWritesDoNotFollowSymlinks() throws IOException {
        String lt = read("scripts/loadtest/loadtest.py");
        assertTrue(lt.contains("def write_text_atomic(path, text):") && lt.contains("tempfile.mkstemp(dir=os.path.dirname(os.path.abspath(path))"));
        assertTrue(lt.contains("os.replace(tmp, path)"));
        assertFalse(lt.contains("RESULTS, f\"{result['name']}.json\"), \"w\""), "write_result opens the predictable path itself");
        assertFalse(lt.contains("f\"{name}.json\"), \"w\""), "the cluster runner opens the predictable path itself");
        assertFalse(lt.contains("open(out, \"w\""), "the report opens the predictable path itself");
        assertFalse(lt.contains("open(path, \"w\""), "the matrix reports open the predictable path themselves");
        assertFalse(lt.contains("\"gate-summary.md\"), \"w\""));
    }

    @Test
    @DisplayName("the stream driver accumulates bodies and SSE frames in linear time")
    void streamDriverIsLinear() throws IOException {
        String py = read("scripts/loadtest/drivers/stream_driver.py");
        assertFalse(py.contains("out += c"), "bytes += copies the whole body per chunk");
        assertFalse(py.contains("buf.split(\"\\n\\n\", 1)"), "split copies the whole remainder per frame");
        assertTrue(py.contains("end = buf.find(\"\\n\\n\", pos)") && py.contains("buf = buf[pos:]"));
        assertTrue(py.contains("async def text(self, limit=65536):"));
    }

    @Test
    @DisplayName("the Kubernetes monitoring component admits only Grafana to Prometheus")
    void prometheusIsIsolated() throws IOException {
        String stack = read("k8s/overlays/monitoring/monitoring-stack.yaml");
        int policy = stack.indexOf("kind: NetworkPolicy");
        assertTrue(policy > 0, "no NetworkPolicy for Prometheus");
        String doc = stack.substring(policy, stack.indexOf("\n---", policy));
        assertTrue(doc.contains("app.kubernetes.io/name: prometheus") && doc.contains("- Ingress"));
        assertTrue(doc.contains("app.kubernetes.io/name: grafana") && doc.contains("port: 9090"));
        assertFalse(doc.contains("namespaceSelector") || doc.contains("ipBlock"), "only the Grafana pod may be admitted");
    }

    @Test
    @DisplayName("the demo seeder and create-secrets.ps1 keep key material out of argv and world-readable files")
    void secretsStayPrivate() throws IOException {
        String seed = read("src/main/docker/seed-demo-agent.sh");
        assertTrue(seed.contains("--data-binary @-"));
        assertFalse(seed.contains("-d \"{\\\"value\\\""), "the key must not be a curl -d argument");
        assertFalse(seed.contains("-H \"Authorization: Bearer"), "the bearer key must not be a curl -H argument");
        assertTrue(seed.contains("curl -sf -K - \"$EDDI/v1/models\""));
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
