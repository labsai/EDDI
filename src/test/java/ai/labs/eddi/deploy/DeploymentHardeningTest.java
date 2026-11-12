/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.deploy;

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
            String base = read("k8s/base/eddi-networkpolicy.yaml");
            assertTrue(base.contains("kind: NetworkPolicy"));
            assertTrue(base.contains("app.kubernetes.io/name: eddi"), "must select the EDDI pod");
            assertTrue(base.contains("- podSelector: {}"), "same-namespace pods only, following kustomize's namespace");
            assertFalse(base.contains("namespaceSelector: {}"), "an empty namespaceSelector admits every namespace");
            assertTrue(read("k8s/base/kustomization.yaml").contains("- eddi-networkpolicy.yaml"));

            String quickstart = read("k8s/quickstart.yaml");
            String policy = between(quickstart, "# --- EDDI NetworkPolicy ---", "# --- EDDI Service ---");
            assertTrue(policy.contains("name: eddi-ingress"));
            assertTrue(policy.contains("- podSelector: {}"));
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
            String box = between(sh, "# The key is printed only to a terminal.", "Next steps:");
            int tty = box.indexOf("if [[ -t 1 ]]; then");
            assertTrue(tty > 0, "the key box must be guarded by a terminal test");
            assertTrue(box.indexOf("${VAULT_KEY}") > tty, "the key may only appear inside the terminal branch");
            assertTrue(box.contains("NOT printed"));

            String ps = read("k8s/create-secrets.ps1");
            int redirected = ps.indexOf("[Console]::IsOutputRedirected -or -not [Environment]::UserInteractive");
            assertTrue(redirected > 0, "the PowerShell twin needs the same guard");
            assertTrue(ps.lastIndexOf("$VaultKey\"") > redirected, "the key may only be written after the guard");
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

            String assets = between(sh, "Refreshing monitoring assets...", "Pulling images...");
            assertTrue(assets.contains("_verify_download \"$asset\" \"$tmp_asset\""));
            assertTrue(assets.indexOf("_verify_download") < assets.indexOf("mv -f \"$tmp_asset\" \"$target\""));
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
            assertTrue(cmd.contains("if defined PINNED ("), "a release-pinned install must not fall back to main");
            assertTrue(cmd.indexOf("if defined PINNED (") < cmd.indexOf("set \"EDDI_BRANCH=main\""));

            // The helper embeds the installer's own verification, so the two cannot drift.
            assertTrue(ps.contains("'Confirm-ReleaseChecksumsSignature', 'Initialize-ReleaseChecksums', 'Confirm-DownloadChecksum'"));
            assertTrue(ps.contains("$script:FirstSignedRelease = [version]\"6.6.0\""));
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
