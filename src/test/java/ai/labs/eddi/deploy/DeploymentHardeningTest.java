/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.deploy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MappingIterator;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the deployment, installer and CI hardening of the 2026-11 security
 * review. The files are not executed by the unit run, so the checks are on
 * their text: each one fails if the weaker construct comes back.
 */
class DeploymentHardeningTest {

    private static final YAMLMapper YAML = new YAMLMapper();

    /**
     * The NetworkPolicy called {@code name} among the documents of {@code file}.
     */
    private static JsonNode networkPolicy(String file, String name) throws IOException {
        try (MappingIterator<JsonNode> documents = YAML.readerFor(JsonNode.class).readValues(read(file))) {
            while (documents.hasNext()) {
                JsonNode document = documents.next();
                if ("NetworkPolicy".equals(document.path("kind").asText()) && name.equals(document.path("metadata").path("name").asText())) {
                    return document;
                }
            }
        }
        throw new AssertionError("no NetworkPolicy " + name + " in " + file);
    }

    private static void assertIsolatesTheEddiPod(JsonNode policy, String where) {
        JsonNode spec = policy.path("spec");
        assertEquals("{\"app.kubernetes.io/name\":\"eddi\",\"app.kubernetes.io/component\":\"server\"}",
                spec.path("podSelector").path("matchLabels").toString(), where + ": must select the EDDI server pod");
        assertEquals("[\"Ingress\"]", spec.path("policyTypes").toString(), where + ": ingress only");
        assertEquals(1, spec.path("ingress").size(), where + ": exactly one ingress rule");
        JsonNode rule = spec.path("ingress").get(0);
        assertEquals("[{\"podSelector\":{}}]", rule.path("from").toString(),
                where + ": the only peer is a bare podSelector, i.e. the policy's own namespace");
        assertEquals("[{\"protocol\":\"TCP\",\"port\":7070}]", rule.path("ports").toString(), where + ": TCP 7070 only");
    }

    private static String read(String file) throws IOException {
        return Files.readString(Path.of(file), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    /** The text between {@code from} and the first {@code to} after it. */
    private static String between(String text, String from, String to) {
        int start = text.indexOf(from);
        assertTrue(start >= 0, "missing marker: " + from);
        int end = text.indexOf(to, start + from.length());
        assertTrue(end > start, "missing end marker: " + to);
        return text.substring(start, end);
    }

    @Nested
    @DisplayName("Kubernetes")
    class Kubernetes {

        @Test
        @DisplayName("the unauthenticated base and quickstart isolate the EDDI pod to its own namespace")
        void eddiPodHasAnIngressPolicy() throws IOException {
            assertIsolatesTheEddiPod(networkPolicy("k8s/base/eddi-networkpolicy.yaml", "eddi-ingress"), "k8s/base");
            assertTrue(read("k8s/base/kustomization.yaml").contains("- eddi-networkpolicy.yaml"));

            String quickstart = read("k8s/quickstart.yaml");
            assertIsolatesTheEddiPod(networkPolicy("k8s/quickstart.yaml", "eddi-ingress"), "k8s/quickstart.yaml");
            assertTrue(quickstart.contains("enforces NetworkPolicy"), "the quickstart must say the policy needs an enforcing CNI");
        }

        @Test
        @DisplayName("the ingress component admits its controller and says that TLS is required with OIDC")
        void ingressComponentKeepsTheControllerReachable() throws IOException {
            assertTrue(read("k8s/overlays/ingress/kustomization.yaml").contains("- ingress-networkpolicy.yaml"));
            String policy = read("k8s/overlays/ingress/ingress-networkpolicy.yaml");
            assertTrue(policy.contains("kubernetes.io/metadata.name: ingress-nginx"),
                    "the controller is selected by the unforgeable namespace label");
            assertTrue(read("k8s/overlays/ingress/ingress.yaml").contains("Required once OIDC is on"));
        }

        @Test
        @DisplayName("the production component turns SSRF protection on with the Platform Operator self-call allowed")
        void productionComponentEnablesSsrfProtection() throws IOException {
            String production = read("k8s/overlays/production/kustomization.yaml");
            assertTrue(production.contains("/data/EDDI_SECURITY_SSRF_PROTECTION_ENABLED"));
            assertTrue(production.contains("value: \"true\""));
            assertTrue(production.contains("EDDI_SECURITY_SSRF_PROTECTION_ALLOWED_HOSTS"));
            assertTrue(production.contains("127.0.0.1:7070"));
        }

        @Test
        @DisplayName("the secret helpers print the vault key only to a terminal")
        void vaultKeyIsNotPrintedToAPipe() throws IOException {
            String sh = read("k8s/create-secrets.sh");
            String box = between(sh, "# The key is printed only on request", "Next steps:");
            int optIn = box.indexOf("if [[ \"$SHOW_KEY\" == \"true\" ]]; then");
            assertTrue(optIn > 0, "the key box must be guarded by an explicit opt-in");
            assertFalse(box.contains("[[ -t 1 ]]"), "a terminal test cannot tell a log from a screen (tee, script, ptys)");
            assertTrue(box.indexOf("${VAULT_KEY}") > optIn, "the key may only appear inside the opt-in branch");
            assertTrue(box.contains("NOT printed"));

            String ps = read("k8s/create-secrets.ps1");
            assertTrue(ps.contains("[switch]$ShowKey"));
            assertFalse(ps.contains("IsOutputRedirected"), "`*>` and `6>` leave that false while the key is logged");
            int guard = ps.indexOf("if (-not $ShowKey) {");
            assertTrue(guard > 0, "the PowerShell twin needs the same opt-in");
            assertTrue(ps.lastIndexOf("$VaultKey\"") > guard, "the key may only be written in the opt-in branch");
        }
    }

    @Nested
    @DisplayName("Helm")
    class Helm {

        @Test
        @DisplayName("SSRF protection is rendered, follows the install and keeps the operator's self-call")
        void chartRendersSsrfProtection() throws IOException {
            String configmap = read("helm/eddi/templates/configmap.yaml");
            assertTrue(configmap.contains("EDDI_SECURITY_SSRF_PROTECTION_ENABLED"));
            assertTrue(configmap.contains("EDDI_SECURITY_SSRF_PROTECTION_ALLOWED_HOSTS"));
            assertTrue(configmap.contains("or .Values.eddi.oidc.enabled .Values.ingress.enabled"),
                    "empty must follow OIDC or an Ingress");
            assertTrue(configmap.contains("kindIs \"bool\" $ssrf.enabled"), "an explicit false must survive");

            String values = read("helm/eddi/values.yaml");
            assertTrue(values.contains("ssrfProtection:"));
            assertTrue(values.contains("- \"127.0.0.1:7070\""), "the Platform Operator's self-call");
        }

        @Test
        @DisplayName("an OIDC-enabled Ingress without TLS fails the render unless acknowledged")
        void oidcIngressNeedsTls() throws IOException {
            String configmap = read("helm/eddi/templates/configmap.yaml");
            assertTrue(configmap.contains(
                    "{{- if and .Values.ingress.enabled .Values.eddi.oidc.enabled (not .Values.ingress.tls) (not .Values.ingress.allowPlainHttp) }}"));
            assertTrue(configmap.contains("over plain HTTP"));
            // A non-empty string is truthy in a Go template: "false" must not switch the
            // refusal off.
            String strict = "{{- if and (not (kindIs \"invalid\" .Values.ingress.allowPlainHttp)) (not (kindIs \"bool\" .Values.ingress.allowPlainHttp)) }}";
            int typeCheck = configmap.indexOf(strict);
            assertTrue(typeCheck > 0, "allowPlainHttp must be rejected unless it is a real boolean");
            assertTrue(typeCheck < configmap.indexOf("(not .Values.ingress.allowPlainHttp) }}"),
                    "the type check must come before the guard it protects");
            assertTrue(configmap.contains("eddi.security.ssrfProtection.enabled must be true, false or empty"),
                    "a string such as \"no\" must not silently pick a side");
            assertTrue(read("helm/eddi/values.yaml").contains("allowPlainHttp: false"), "the acknowledgement defaults to off");
        }
    }

    @Nested
    @DisplayName("Installers")
    class Installers {

        @Test
        @DisplayName("eddi update checks every file against the release checksums before moving any into place")
        void bashUpdateVerifiesDownloads() throws IOException {
            String sh = read("install.sh");
            String update = between(sh, "    # Same checks as a fresh install:", "    # An install from before docker-compose.postgres-only.yml");
            assertTrue(update.contains("_load_release_checksums"));
            assertTrue(update.contains("_verify_download \"$local_name\" \"$tmp_file\""));
            int verify = update.indexOf("_verify_download");
            int move = update.indexOf("mv -f \"${s}.tmp\" \"$s\"");
            assertTrue(verify > 0 && move > verify, "nothing is moved into place before every file passed");
            assertFalse(update.contains("mv -f \"$tmp_file\" \"$f\""), "the unverified in-place move is gone");

            // Both groups are verified before either is replaced.
            String assets = between(sh, "Fetching monitoring assets...", "Pulling images...");
            assertTrue(assets.contains("_verify_download \"$asset\" \"$tmp_asset\""));
            int assetCheck = assets.indexOf("_verify_download \"$asset\"");
            assertTrue(assetCheck > 0 && assetCheck < assets.indexOf("mv -f \"${s}.tmp\" \"$s\""),
                    "compose files may only be replaced after the monitoring assets were verified too");
            assertTrue(assets.indexOf("mv -f \"${s}.tmp\" \"$s\"") < assets.indexOf("_set_kv EDDI_VERSION"),
                    "the recorded tag may only change after the files are in place");
            assertTrue(assets.indexOf("mv -f \"${s}.tmp\" \"$s\"") < assets.indexOf("_set_kv EDDI_BRANCH \"$EDDI_BRANCH\""),
                    "the recorded ref may only change after the files are in place");
            String beforeFiles = sh.substring(sh.indexOf("  update)\n"), sh.indexOf("    # Same checks as a fresh install:"));
            assertFalse(beforeFiles.contains("_set_kv EDDI_VERSION \"$NEW_VERSION\""), "persisted before verification");
            assertFalse(beforeFiles.contains("_set_kv EDDI_BRANCH \"$EDDI_BRANCH\""), "persisted before verification");
        }

        @Test
        @DisplayName("an install pinned to a release refuses to fall back to main when the release lookup fails")
        void bashUpdateDoesNotFallBackToMainForAPinnedInstall() throws IOException {
            String sh = read("install.sh");
            String fallback = between(sh, "# An install that tracks releases must not slide onto `main`", "EDDI_BRANCH_SOURCE=\"fallback-main\"");
            assertTrue(fallback.contains("\"$EDDI_BRANCH_SOURCE\" == \"version\""));
            assertTrue(fallback.contains("_is_release_tag \"$EDDI_BRANCH\""));
            assertTrue(fallback.contains("exit 1"));
            assertTrue(fallback.indexOf("exit 1") < fallback.indexOf("EDDI_BRANCH=\"main\""), "the refusal comes before the fallback");
        }

        @Test
        @DisplayName("a signed release is refused, not used unverified, when no SHA-256 utility exists")
        void missingHashUtilityFailsClosedForSignedReleases() throws IOException {
            String sh = read("install.sh");
            String cli = between(sh, "_load_release_checksums() {", "local sums bundle");
            assertTrue(cli.contains("if _release_is_signed \"$EDDI_BRANCH\"; then"));
            assertTrue(cli.indexOf("exit 1") < cli.indexOf("downloaded files are not checksum-verified"));
            String installer = between(sh, "if ! sha256_of /dev/null &>/dev/null; then", "local sums\n");
            assertTrue(installer.contains("release_is_signed \"$EDDI_BRANCH\"") && installer.contains("fail \""));
        }

        @Test
        @DisplayName("the CLI's signed-release rule matches the installer's")
        void cliAndInstallerAgreeOnTheFirstSignedRelease() throws IOException {
            String sh = read("install.sh");
            long declarations = sh.lines().filter(l -> l.equals("FIRST_SIGNED_RELEASE=\"6.6.0\"")).count();
            assertEquals(2, declarations, "install.sh and the CLI it writes must name the same first signed release");
        }

        @Test
        @DisplayName("eddi.cmd delegates the update download to a verifying helper and refuses the main fallback")
        void windowsUpdateVerifiesDownloads() throws IOException {
            String ps = read("install.ps1");
            String cmd = between(ps, ":cmd_update", ":cmd_uninstall");
            assertFalse(cmd.contains("curl -fsSL"), "the update path must not download with plain curl");
            assertTrue(cmd.contains("eddi-fetch.ps1"));
            assertTrue(cmd.contains("goto update_refused"));
            // One call stages compose files and assets; the ref is recorded only after it
            // succeeded.
            assertTrue(cmd.contains("\"::assets::\""), "the assets must travel in the same call as the compose files");
            assertFalse(cmd.contains("-Assets"), "a second helper call would replace the compose files before the assets pass");
            assertTrue(cmd.indexOf("eddi-fetch.ps1\" -Ref") < cmd.indexOf("if defined PERSIST_REF ("),
                    "the recorded ref may only change after the files are in place");
            assertTrue(cmd.indexOf("set \"PERSIST_REF=1\"") < cmd.indexOf("if defined PERSIST_REF ("));
            assertFalse(cmd.substring(0, cmd.indexOf("eddi-fetch.ps1\" -Ref")).contains("echo EDDI_BRANCH=!EDDI_BRANCH!"),
                    ".eddi-config must not be rewritten before the fetch");
            assertTrue(cmd.contains("if defined PINNED ("), "a release-pinned install must not fall back to main");
            assertTrue(cmd.indexOf("if defined PINNED (") < cmd.indexOf("set \"EDDI_BRANCH=main\""));

            // The helper embeds the installer's own verification, so the two cannot drift.
            assertTrue(ps.contains("'Confirm-ReleaseChecksumsSignature', 'Initialize-ReleaseChecksums', 'Confirm-DownloadChecksum'"));
            assertTrue(ps.contains("$script:FirstSignedRelease = [version]\"6.6.0\""));

            // The helper never removes a directory recursively, and only after
            // verification.
            String helper = between(ps, "$fetchMain = @'", "'@\n    $fetchFunctions");
            assertFalse(helper.contains("-Recurse"), "a directory at an asset path may hold content");
            assertTrue(helper.indexOf("Remove-Item -LiteralPath $s[1] -Force") > helper.indexOf("if ($missing.Count -gt 0) { throw"),
                    "an empty directory is removed only once every item passed");
        }

        @Test
        @DisplayName("install.ps1 stops when chmod 600 fails on a POSIX filesystem")
        void windowsInstallerChecksChmod() throws IOException {
            String ps = read("install.ps1");
            String protect = between(ps, "function Protect-SensitiveFile", "try {");
            assertTrue(protect.contains("& chmod 600 $SecurePath 2>$null") && protect.contains("$LASTEXITCODE -ne 0"));
            String initialise = between(ps, "function Initialize-SensitiveFile",
                    "[System.IO.File]::WriteAllBytes($SecurePath, [byte[]]@())\n    Protect-SensitiveFile");
            assertTrue(initialise.indexOf("chmod 600") < initialise.lastIndexOf("$LASTEXITCODE -ne 0"));
        }

        @Test
        @DisplayName("install.ps1 sends admin passwords only to 127.0.0.1 and only after Docker names this project's container")
        void windowsCredentialsGoToTheVerifiedListener() throws IOException {
            String ps = read("install.ps1");
            assertTrue(ps.contains("function Test-ComposeServiceRunning"));
            assertTrue(ps.contains("label=com.docker.compose.project=$project"));
            assertTrue(ps.contains("--filter status=running"));
            assertFalse(ps.contains("$kcBase = \"http://localhost:"), "localhost can resolve to ::1");
            assertFalse(ps.contains("$gBase = \"http://localhost:"), "localhost can resolve to ::1");
            String connect = between(ps, "function Connect-KeycloakAdmin", "$tokenUri =");
            assertTrue(connect.contains("Test-ComposeServiceRunning \"keycloak\""));
            assertTrue(between(ps, "function Update-LegacyGrafanaLogin", "$legacyAuth").contains("Test-ComposeServiceRunning \"grafana\""));
            assertTrue(between(ps, "function Confirm-GrafanaLogin", "$up = $false").contains("Test-ComposeServiceRunning \"grafana\""));
        }

        @Test
        @DisplayName("install.ps1 creates .env, .eddi-config and first-login.txt owner-only before writing them")
        void windowsSensitiveFilesAreCreatedRestricted() throws IOException {
            String ps = read("install.ps1");
            assertTrue(ps.contains("function Initialize-SensitiveFile"));
            assertTrue(ps.contains("& install -m 600 /dev/null $SecurePath"));
            assertTrue(ps.indexOf("Initialize-SensitiveFile $envPath") > 0);
            assertTrue(ps.indexOf("Initialize-SensitiveFile $envPath") < ps.indexOf("$envContent | Set-Content -Path $envPath"));
            assertTrue(ps.indexOf("Initialize-SensitiveFile $configPath") < ps.indexOf("COMPOSE_FILES=$($ComposeFiles -join"));
            assertFalse(ps.contains("Set-Content -Path $secretsFile -Value $null"));
        }
    }

    @Nested
    @DisplayName("GCP provisioner")
    class Gcp {

        @Test
        @DisplayName("with --https no direct EDDI port is opened and EDDI stays on the loopback")
        void httpsLeavesNoPlainHttpPath() throws IOException {
            String sh = read("gcp/provision-vm.sh");
            String firewall = between(sh, "setup_firewall_rules() {", "# No rule for Keycloak");
            assertTrue(firewall.contains("if [[ \"$SETUP_HTTPS\" != \"true\" ]]; then"));
            assertTrue(firewall.indexOf("if [[ \"$SETUP_HTTPS\" != \"true\" ]]") < firewall.indexOf("ensure_firewall_rule \"$FIREWALL_RULE_HTTP\""));
            assertTrue(sh.contains("export EDDI_BIND=\"127.0.0.1\""));
            assertFalse(sh.contains("Direct HTTP (always available)"), "the banner must not advertise a plain-http URL");
            assertTrue(sh.contains("proxy_pass         http://127.0.0.1:__EDDI_PORT__;"));
        }

        @Test
        @DisplayName("the VM has no service account, and the version and branch are validated before reaching a root script")
        void vmHasNoServiceAccountAndValidatedInputs() throws IOException {
            String sh = read("gcp/provision-vm.sh");
            assertTrue(sh.contains("--no-service-account"));
            assertTrue(sh.contains("--no-scopes"));
            assertFalse(sh.contains("--scopes=\"default\""));
            int validation = sh.indexOf("if [[ ! \"$EDDI_VERSION\" =~ ^[A-Za-z0-9_][A-Za-z0-9_.-]*$ ]]; then");
            assertTrue(validation > 0, "--eddi-version needs install.sh's pattern");
            assertTrue(validation < sh.indexOf("case \"$COMMAND\" in\n  create)"), "validated before any command runs");
            assertTrue(sh.contains("valid_git_ref \"$EDDI_BRANCH\""));
        }

        @Test
        @DisplayName("the startup script checks install.sh against the release checksums before running it as root")
        void installShIsVerifiedBeforeItRuns() throws IOException {
            String sh = read("gcp/provision-vm.sh");
            assertFalse(sh.contains("| bash -s -- ${p_flags}"), "piping the download straight into bash skips the check");
            String block = between(sh, "INSTALL_DIR=", "rm -rf \"\\${INSTALL_DIR}\"");
            assertTrue(block.contains("SHA256SUMS"));
            assertTrue(block.indexOf("ACTUAL_SHA") < block.indexOf("bash \"\\${INSTALL_SH}\""));
            assertTrue(read(".github/workflows/ci.yml").contains("'docs/monitoring' 'install.sh'"),
                    "the release job must list install.sh in SHA256SUMS");
        }

        @Test
        @DisplayName("the public nginx site goes live only after the Keycloak client was restricted")
        void publicSiteWaitsForTheClientUpdate() throws IOException {
            String sh = read("gcp/provision-vm.sh");
            int enable = sh.indexOf("ln -sf /etc/nginx/sites-available/eddi /etc/nginx/sites-enabled/eddi\n    if !");
            assertTrue(enable > 0, "the site must be enabled behind the KC_CLIENT_RESTRICTED check");
            assertTrue(sh.indexOf("KC_CLIENT_RESTRICTED=true") < enable);
            assertTrue(sh.indexOf("if [[ \"\\${KC_CLIENT_RESTRICTED}\" == \"true\" ]]; then") < enable);
            assertFalse(
                    sh.substring(0, sh.indexOf("KC_CLIENT_RESTRICTED=false"))
                            .contains("ln -sf /etc/nginx/sites-available/eddi /etc/nginx/sites-enabled/eddi"),
                    "nginx must not publish 443 before the client update");
        }

        @Test
        @DisplayName("every health probe has a total timeout and the wait loops count wall-clock time")
        void healthProbesAreBounded() throws IOException {
            String sh = read("gcp/provision-vm.sh");
            for (String line : sh.split("\n")) {
                if (line.contains("curl -sf --connect-timeout")) {
                    assertTrue(line.contains("--max-time"), "unbounded health probe: " + line.strip());
                }
            }
            assertFalse(sh.contains("elapsed=$((elapsed + 10))"), "a slow probe would stretch the budget");
            assertTrue(sh.contains("elapsed=$((SECONDS - started))"));
        }

        @Test
        @DisplayName("print_success gives tunnel commands for an HTTPS setup that is not reachable")
        void httpsBannerHasRecoveryGuidance() throws IOException {
            String sh = read("gcp/provision-vm.sh");
            assertTrue(sh.contains("If the HTTPS URLs do not answer"));
        }

        @Test
        @DisplayName("the public Keycloak client replaces its loopback entries instead of adding to them")
        void keycloakClientDropsLoopbackEntries() throws IOException {
            String sh = read("gcp/provision-vm.sh");
            assertFalse(sh.contains(".redirectUris += "), "appending leaves http://localhost:* on an internet-facing realm");
            assertTrue(sh.contains(".redirectUris = ([.redirectUris[] | select(test(\"^https?://localhost(:|/|\\$)\") | not)]"));
            assertTrue(sh.contains("select(. != \"+\" and"), "the wildcard web origin goes too");
        }
    }

    @Nested
    @DisplayName("Continuous integration")
    class Ci {

        @Test
        @DisplayName("cache-restored metrics are coerced to integers and reach the delta step through env")
        void metricsAreNotSplicedIntoScripts() throws IOException {
            String workflow = read(".github/workflows/docker-pull-notify.yml");
            assertFalse(workflow.contains("jq -r '.docker_pulls // 0' metrics.json"), "an uncoerced field reached GITHUB_OUTPUT");
            assertTrue(workflow.contains("tonumber? // 0"));
            String delta = between(workflow, "- name: Compute deltas", "# ── Push to analytics");
            String script = delta.substring(delta.indexOf("run: |"));
            assertFalse(script.contains("${{"), "no expression may be pasted into the script source");
            assertTrue(delta.contains("PREV_PULLS: ${{ steps.prev.outputs.docker_pulls }}"));
            assertTrue(delta.contains("*[!0-9]*"), "digits only before any arithmetic");
        }

        @Test
        @DisplayName("values parsed from the Dockerfile reach the base-image scripts through env")
        void dockerfileValuesAreNotSplicedIntoScripts() throws IOException {
            String workflow = read(".github/workflows/base-image-check.yml");
            for (String line : workflow.split("\n")) {
                String trimmed = line.trim();
                if (trimmed.contains("=\"${{ steps.")) {
                    throw new AssertionError("a step output is spliced into a shell assignment: " + trimmed);
                }
            }
            assertTrue(workflow.contains("IMAGE: ${{ steps.parse.outputs.image }}"));
            assertTrue(workflow.contains("NEW_DIGEST: ${{ steps.remote.outputs.digest }}"));
        }

        @Test
        @DisplayName("tag builds restore the Maven cache by exact key only")
        void releaseBuildsDoNotRestoreAPrefixCache() throws IOException {
            String ci = read(".github/workflows/ci.yml");
            assertFalse(ci.contains("restore-keys: |\n            maven-v1-"), "a prefix restore feeds a poisoned cache into a release");
            long guarded = ci.lines()
                    .filter(l -> l.contains("restore-keys: ${{ !startsWith(github.ref, 'refs/tags/') && format('maven-v1-{0}-', runner.os) || '' }}"))
                    .count();
            assertEquals(6, guarded, "every Maven cache restore must drop the prefix fallback on tags");
        }
    }

    @Nested
    @DisplayName("Environment reference")
    class Environment {

        @Test
        @DisplayName(".env.example and the compose files say that an empty vault key disables the vault")
        void vaultKeyDefaultIsDocumentedCorrectly() throws IOException {
            String env = read(".env.example");
            assertFalse(env.contains("the compose file supplies a demo default"), "docker-compose.yml defaults the key to empty");
            assertTrue(env.contains("DISABLED"));
            assertTrue(read("docker-compose.yml").contains("Empty DISABLES the vault"));
            assertTrue(read("docker-compose.postgres-only.yml").contains("Empty DISABLES the vault"));
        }

        @Test
        @DisplayName("the SSRF switches are documented where operators configure the stack")
        void ssrfSwitchesAreDocumented() throws IOException {
            for (String file : new String[]{".env.example", "docker-compose.yml", "docker-compose.postgres-only.yml", "docker-compose.cluster.yml"}) {
                String text = read(file);
                assertTrue(text.contains("EDDI_SECURITY_SSRF_PROTECTION_ENABLED"), file);
                assertTrue(text.contains("EDDI_SECURITY_SSRF_PROTECTION_ALLOWED_HOSTS"), file);
            }
        }
    }
}
