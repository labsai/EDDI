/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.deploy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Which git ref the installers download the compose and support files from.
 * <p>
 * Two defects: the default {@code EDDI_VERSION=latest} pulled the newest
 * released image but fetched the compose files from {@code main}, which can be
 * ahead of it; and the "prevent path traversal" check was a character class
 * that admits {@code .} and {@code /}, so
 * {@code EDDI_BRANCH=../../someone/else/main} passed and curl, which resolves
 * dot segments, fetched another repository's files.
 * <p>
 * Everything here runs the real scripts — {@code install.sh --dry-run}, the
 * {@code eddi} wrapper it installs, and {@code install.ps1 -DryRun} — against a
 * stand-in {@code curl} that plays the GitHub releases API, so nothing is
 * downloaded, installed or started.
 */
class InstallerReleaseRefTest {

    private static final Path INSTALL_SH = Path.of("install.sh");
    private static final Path INSTALL_PS1 = Path.of("install.ps1");
    private static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    /** A tag no real release has, so a pass proves the API answer was used. */
    private static final String STUB_TAG = "9.8.7";

    private static final List<String> TRAVERSALS = List.of("../../someone/else/main", "main/../../../evil", "release..x", "a//b", "/abs",
            "trailing/", ".hidden", "a/.hidden", "x.lock", "-x", "ends.");

    @Test
    @DisplayName("install.sh: latest fetches the newest release's files, and a failed lookup falls back loudly")
    void shellResolvesTheRef() throws Exception {
        Run latest = installSh(Map.of(), false);
        assertEquals(0, latest.exit(), latest.toString());
        assertTrue(latest.output().contains("Compose files:  " + STUB_TAG + " (latest-release)"),
                "EDDI_VERSION=latest must fetch the files from the newest release's tag, not main. " + latest);
        assertTrue(latest.output().contains("https://raw.githubusercontent.com/labsai/EDDI/" + STUB_TAG), latest.toString());

        Run offline = installSh(Map.of(), true);
        assertEquals(0, offline.exit(), offline.toString());
        assertTrue(offline.output().contains("Compose files:  main (fallback-main)") && offline.output().contains("WARNING"),
                "a failed release lookup must fall back to main AND say so. " + offline);

        Run pinned = installSh(Map.of("EDDI_VERSION", "6.4.0"), false);
        assertTrue(pinned.output().contains("Compose files:  6.4.0 (version)"), pinned.toString());

        Run explicit = installSh(Map.of("EDDI_BRANCH", "feature/x"), false);
        assertTrue(explicit.output().contains("Compose files:  feature/x (explicit)"), explicit.toString());
    }

    @Test
    @DisplayName("install.sh refuses a ref that would leave the EDDI repository")
    void shellRefusesTraversal() throws Exception {
        for (String ref : TRAVERSALS) {
            Run run = installSh(Map.of("EDDI_BRANCH", ref), false);
            assertNotEquals(0, run.exit(), "install.sh accepted EDDI_BRANCH=" + ref + ". " + run);
            assertTrue(run.output().contains("Invalid EDDI_BRANCH"), run.toString());
            assertFalse(run.output().contains("Download from"), run.toString());
        }
        Run version = installSh(Map.of("EDDI_VERSION", "../x"), false);
        assertNotEquals(0, version.exit(), "a version that is a traversal must be refused too. " + version);
        for (String ref : List.of("feature/x", "release-6.5", "6.5.0", "dependabot/npm_and_yarn/x-1.2")) {
            Run run = installSh(Map.of("EDDI_BRANCH", ref), false);
            assertEquals(0, run.exit(), "install.sh refused the legitimate ref " + ref + ". " + run);
        }
    }

    @Test
    @DisplayName("eddi update fetches the files that match the image it is about to pull")
    void cliUpdateResolvesTheRef() throws Exception {
        String files = "COMPOSE_FILES=%s/docker-compose.yml\nEDDI_PORT=7070\nEDDI_HTTPS_PORT=7443\n";

        Run legacy = cliUpdate(files + "EDDI_BRANCH=main\n", "EDDI_VERSION=latest\n", List.of(), Map.of(), false);
        assertEquals(0, legacy.exit(), legacy.toString());
        assertTrue(legacy.output().contains("/EDDI/" + STUB_TAG + "/docker-compose.yml"),
                "an install on `latest` must refresh its files from the newest release, not main. " + legacy);
        assertTrue(legacy.config().contains("EDDI_BRANCH_SOURCE=latest-release"), legacy.toString());

        Run moved = cliUpdate(files + "EDDI_BRANCH=6.4.0\nEDDI_BRANCH_SOURCE=latest-release\n", "EDDI_VERSION=latest\n",
                List.of(), Map.of(), false);
        assertTrue(moved.output().contains("/EDDI/" + STUB_TAG + "/docker-compose.yml"),
                "`latest` must be looked up again on update — the release it resolved to at install time is stale. " + moved);

        Run pinned = cliUpdate(files + "EDDI_BRANCH=main\n", "EDDI_VERSION=6.3.0\n", List.of(), Map.of(), false);
        assertTrue(pinned.output().contains("/EDDI/6.3.0/docker-compose.yml"), pinned.toString());

        Run repin = cliUpdate(files + "EDDI_BRANCH=main\n", "EDDI_VERSION=latest\n", List.of("--eddi-version=6.4.0"), Map.of(),
                false);
        assertTrue(repin.output().contains("/EDDI/6.4.0/docker-compose.yml"), repin.toString());
        assertTrue(repin.env().contains("EDDI_VERSION=6.4.0") && !repin.env().contains("EDDI_VERSION=latest"), repin.toString());

        Run explicit = cliUpdate(files + "EDDI_BRANCH=feature/x\nEDDI_BRANCH_SOURCE=explicit\n", "EDDI_VERSION=latest\n",
                List.of(), Map.of(), false);
        assertTrue(explicit.output().contains("/EDDI/feature/x/docker-compose.yml"), explicit.toString());

        Run offline = cliUpdate(files + "EDDI_BRANCH=main\n", "EDDI_VERSION=latest\n", List.of(), Map.of(), true);
        assertTrue(offline.output().contains("/EDDI/main/docker-compose.yml") && offline.output().contains("WARNING"),
                offline.toString());

        Run traversal = cliUpdate(files + "EDDI_BRANCH=main\n", "EDDI_VERSION=latest\n", List.of(),
                Map.of("EDDI_BRANCH", "../../someone/else/main"), false);
        assertNotEquals(0, traversal.exit(), traversal.toString());
        assertFalse(traversal.output().contains("raw.githubusercontent.com"), traversal.toString());

        // Installed before EDDI_BRANCH_SOURCE existed, on a ref set by hand: the old
        // installer only ever chose `main` or the version tag itself, so this ref was
        // the operator's and must survive the first update under the new wrapper.
        Run legacyExplicit = cliUpdate(files + "EDDI_BRANCH=feature/x\n", "EDDI_VERSION=latest\n", List.of(), Map.of(), false);
        assertTrue(legacyExplicit.output().contains("/EDDI/feature/x/docker-compose.yml"), legacyExplicit.toString());
        assertTrue(legacyExplicit.config().contains("EDDI_BRANCH_SOURCE=explicit"), legacyExplicit.toString());
    }

    @Test
    @DisplayName("eddi.cmd validates the ref without letting cmd.exe parse it")
    void cmdWrapperRefCheck() throws Exception {
        assumeTrue(WINDOWS, "eddi.cmd is a Windows batch file");
        // :check_ref exactly as install.ps1 writes it into eddi.cmd. It used to pipe
        // the value into findstr, and each side of a pipe is re-parsed by a child
        // cmd.exe: an `&` ran what followed it, and only the part before it was
        // checked, so a traversal hidden behind it was accepted too.
        String ps1 = Files.readString(INSTALL_PS1, StandardCharsets.UTF_8).replace("\r\n", "\n");
        int start = ps1.indexOf("\n:check_ref\n");
        int bad = ps1.indexOf("\n:check_ref_bad\n", start);
        int end = ps1.indexOf("exit /b 1\n", bad);
        assertTrue(start >= 0 && bad > start && end > bad, "install.ps1 no longer writes a :check_ref subroutine into eddi.cmd");
        String subroutine = ps1.substring(start + 1, end + "exit /b 1\n".length());
        assertFalse(Pattern.compile("[^|]\\|\\s*findstr").matcher(subroutine).find(),
                "eddi.cmd's :check_ref pipes the ref into a child cmd.exe again");

        Path dir = sandbox("eddi-cmd");
        Path marker = dir.resolve("pwned.txt");
        String harness = "@echo off\nsetlocal EnableDelayedExpansion\ncall :check_ref || exit /b 1\necho ACCEPTED\nexit /b 0\n"
                + subroutine;
        Path script = dir.resolve("check-ref.cmd");
        Files.writeString(script, harness.replace("\n", "\r\n"), StandardCharsets.US_ASCII);

        List<String> refused = new ArrayList<>(TRAVERSALS);
        refused.addAll(List.of("x&echo pwned>\"" + marker + "\"&rem /../../../evil/repo/main",
                "x|echo pwned>\"" + marker + "\"", "a\"b", "a!PATH!b", "a b", "x^&y", "main;x"));
        for (String ref : refused) {
            Run run = runCmd(script, ref);
            assertNotEquals(0, run.exit(), "eddi.cmd accepted EDDI_BRANCH=" + ref + ". " + run);
            assertFalse(Files.exists(marker), "EDDI_BRANCH=" + ref + " ran a command inside eddi.cmd. " + run);
        }
        for (String ref : List.of("main", "6.5.0", "feature/x", "release-6.5", "dependabot/npm_and_yarn/x-1.2")) {
            Run run = runCmd(script, ref);
            assertEquals(0, run.exit(), "eddi.cmd refused the legitimate ref " + ref + ". " + run);
        }
    }

    private static Run runCmd(Path script, String ref) throws Exception {
        ProcessBuilder builder = new ProcessBuilder("cmd.exe", "/d", "/c", script.toString());
        builder.redirectErrorStream(true);
        builder.environment().put("EDDI_BRANCH", ref);
        return finish(builder.start(), null);
    }

    @Test
    @DisplayName("install.ps1 resolves the ref the same way and refuses traversal")
    void powerShellResolvesTheRef() throws Exception {
        Path pwsh = locateOnPath(WINDOWS ? "pwsh.exe" : "pwsh");
        assumeTrue(pwsh != null, "pwsh is not on PATH; CI's ubuntu-latest runner has it");

        // HTTPS_PROXY at a closed port: PowerShell 7 honours it, so the release lookup
        // fails deterministically.
        Map<String, String> offline = Map.of("HTTPS_PROXY", "http://127.0.0.1:9");
        Run fallback = installPs1(pwsh, offline);
        assertEquals(0, fallback.exit(), fallback.toString());
        assertTrue(fallback.output().contains("Compose files:  main (fallback-main)"), fallback.toString());

        Run pinned = installPs1(pwsh, Map.of("EDDI_VERSION", "6.4.0", "HTTPS_PROXY", "http://127.0.0.1:9"));
        assertTrue(pinned.output().contains("Compose files:  6.4.0 (version)"), pinned.toString());

        for (String ref : TRAVERSALS) {
            Run run = installPs1(pwsh, Map.of("EDDI_BRANCH", ref, "HTTPS_PROXY", "http://127.0.0.1:9"));
            assertNotEquals(0, run.exit(), "install.ps1 accepted EDDI_BRANCH=" + ref + ". " + run);
        }
        String ps1 = Files.readString(INSTALL_PS1, StandardCharsets.UTF_8);
        assertTrue(ps1.contains("EDDI_BRANCH_SOURCE=$EddiBranchSource"), "install.ps1 must record why it chose the ref");
        assertTrue(ps1.contains("call :check_ref || exit /b 1") && ps1.contains("releases/latest).tag_name"),
                "eddi.cmd must validate the ref and look `latest` up again on update");
    }

    private record Run(int exit, String output, String config, String env) {
        @Override
        public String toString() {
            return "exit " + exit + "; output:\n" + output + (config.isEmpty() ? "" : "\n.eddi-config:\n" + config + "\n.env:\n" + env);
        }
    }

    private Run installSh(Map<String, String> env, boolean apiFails) throws Exception {
        Path bash = locateBash();
        assumeTrue(bash != null, "no non-WSL bash available; CI's ubuntu-latest runner has one");
        Path dir = sandbox("install-sh");
        writeCurlStub(dir);
        List<String> exports = new ArrayList<>();
        env.forEach((k, v) -> exports.add(k + "='" + v + "'"));
        String command = "cd \"" + slashed(dir) + "\" && export HOME=\"$PWD/home\" && unset EDDI_BRANCH EDDI_VERSION EDDI_DIR && "
                + "export PATH=\"$PWD/stub:$PATH\" STUB_API_FAIL=" + (apiFails ? 1 : 0) + " " + String.join(" ", exports)
                + " && bash \"" + slashed(INSTALL_SH.toAbsolutePath()) + "\" --dry-run < /dev/null";
        return execute(bash, command, dir, null);
    }

    private Run cliUpdate(String config, String env, List<String> args, Map<String, String> procEnv, boolean apiFails)
            throws Exception {
        Path bash = locateBash();
        assumeTrue(bash != null, "no non-WSL bash available; CI's ubuntu-latest runner has one");
        Path dir = sandbox("eddi-cli");
        writeCurlStub(dir);
        Files.writeString(dir.resolve("stub").resolve("docker"), "#!/usr/bin/env bash\nexit 0\n", StandardCharsets.US_ASCII);
        dir.resolve("stub").resolve("docker").toFile().setExecutable(true, false);

        String installer = Files.readString(INSTALL_SH, StandardCharsets.UTF_8);
        String open = "<< 'EDDI_CLI'\n";
        int start = installer.indexOf(open);
        assertTrue(start >= 0, "install.sh no longer writes the eddi wrapper from an 'EDDI_CLI' heredoc");
        int end = installer.indexOf("\nEDDI_CLI\n", start);
        Path eddiDir = Files.createDirectories(dir.resolve("eddi-home"));
        Files.writeString(eddiDir.resolve("eddi"), installer.substring(start + open.length(), end + 1), StandardCharsets.UTF_8);
        Files.writeString(eddiDir.resolve("docker-compose.yml"), "services: {}\n", StandardCharsets.UTF_8);
        Files.writeString(eddiDir.resolve(".eddi-config"), config.formatted(slashed(eddiDir)), StandardCharsets.UTF_8);
        Files.writeString(eddiDir.resolve(".env"), env, StandardCharsets.UTF_8);

        List<String> exports = new ArrayList<>();
        procEnv.forEach((k, v) -> exports.add(k + "='" + v + "'"));
        String command = "cd \"" + slashed(dir) + "\" && unset EDDI_BRANCH EDDI_VERSION && export EDDI_DIR=\"$PWD/eddi-home\" "
                + "PATH=\"$PWD/stub:$PATH\" STUB_API_FAIL=" + (apiFails ? 1 : 0) + " " + String.join(" ", exports)
                + " && bash eddi-home/eddi update " + String.join(" ", args) + " < /dev/null";
        return execute(bash, command, dir, eddiDir);
    }

    private Run installPs1(Path pwsh, Map<String, String> env) throws Exception {
        Path dir = sandbox("install-ps1");
        ProcessBuilder builder = new ProcessBuilder(pwsh.toString(), "-NoProfile", "-NonInteractive", "-File",
                INSTALL_PS1.toAbsolutePath().toString(), "-DryRun");
        builder.redirectErrorStream(true);
        builder.environment().remove("EDDI_BRANCH");
        builder.environment().remove("EDDI_VERSION");
        builder.environment().put("EDDI_DIR", dir.resolve("eddi-home").toString());
        builder.environment().putAll(env);
        // .NET reads either spelling; set the lowercase one too so an inherited value
        // cannot win.
        if (env.containsKey("HTTPS_PROXY")) {
            builder.environment().put("https_proxy", env.get("HTTPS_PROXY"));
            builder.environment().remove("NO_PROXY");
            builder.environment().remove("no_proxy");
        }
        return finish(builder.start(), null);
    }

    /**
     * Security review 2026-10-17, L8: every file the installers download at install
     * time is checked against the release's SHA256SUMS before it is used, and the
     * release job publishes that asset. Text-level, so a new download added without
     * the check fails here rather than shipping unverified.
     */
    @Test
    @DisplayName("every install-time download is checked against the release's SHA256SUMS")
    void everyDownloadIsChecksumVerified() throws IOException {
        String shell = Files.readString(INSTALL_SH, StandardCharsets.UTF_8).replace("\r\n", "\n");
        String resolve = shell.substring(shell.indexOf("resolve_compose_files() {"),
                shell.indexOf("\n}\n", shell.indexOf("resolve_compose_files() {")));
        assertTrue(resolve.contains("load_release_checksums"), "install.sh must load the checksums before downloading");
        var shellDownloads = Pattern
                .compile("if curl -fsSL \"\\$\\{(\\w+)\\}\" -o \"\\$(\\w+)\"; then\\n\\s+verify_download \"\\$(\\w+)\" \"\\$(\\w+)\"")
                .matcher(resolve);
        int verified = 0;
        while (shellDownloads.find()) {
            assertEquals(shellDownloads.group(2), shellDownloads.group(4), "verify_download must check the file just written");
            verified++;
        }
        assertEquals(resolve.split("curl -fsSL", -1).length - 1, verified,
                "install.sh: every curl download in resolve_compose_files must be followed by verify_download");
        assertTrue(verified >= 3, "expected the compose, monitoring and Keycloak downloads, found " + verified);

        String ps = Files.readString(INSTALL_PS1, StandardCharsets.UTF_8).replace("\r\n", "\n");
        String getCompose = ps.substring(ps.indexOf("function Get-ComposeFiles {"),
                ps.indexOf("\n}\n", ps.indexOf("function Get-ComposeFiles {")));
        assertTrue(getCompose.contains("Initialize-ReleaseChecksums"), "install.ps1 must load the checksums before downloading");
        // Every download in Get-ComposeFiles goes through Save-VerifiedDownload, which
        // writes to a temporary path, verifies it and only then moves it into place —
        // a mismatch never replaces a file that is already installed.
        assertFalse(getCompose.contains("Invoke-WebRequest"),
                "install.ps1: Get-ComposeFiles must download through Save-VerifiedDownload, never Invoke-WebRequest directly");
        int psVerified = getCompose.split("Save-VerifiedDownload -Uri ", -1).length - 1;
        assertTrue(psVerified >= 3, "expected the compose, monitoring and Keycloak downloads, found " + psVerified);
        String saveVerified = ps.substring(ps.indexOf("function Save-VerifiedDownload {"),
                ps.indexOf("\n}\n", ps.indexOf("function Save-VerifiedDownload {")));
        assertTrue(Pattern.compile("Invoke-WebRequest -Uri \\$Uri -OutFile \\$temporary [^\\n]*\\n\\s+"
                + "Confirm-DownloadChecksum -RelativePath \\$RelativePath -Path \\$temporary\\n\\s+"
                + "Move-Item -Path \\$temporary -Destination \\$Target").matcher(saveVerified).find(),
                "Save-VerifiedDownload must download to the temporary path, verify that path, then move it to the target");

        String ci = Files.readString(Path.of(".github", "workflows", "ci.yml"), StandardCharsets.UTF_8).replace("\r\n", "\n");
        assertTrue(ci.contains("> SHA256SUMS") && ci.contains("files: |\n            SHA256SUMS\n"),
                "the release job must generate SHA256SUMS and attach it to the GitHub release");
    }

    private static final String SIGNER_IDENTITY_PREFIX = "https://github.com/labsai/EDDI/.github/workflows/ci.yml@refs/tags/";
    private static final String SIGNER_ISSUER = "https://token.actions.githubusercontent.com";

    /**
     * Security review 2026-10-17, leftover 3: SHA256SUMS only proves the downloads
     * match whatever list the release carries, so it is signed. The release job
     * signs it keyless (the image's identity setup: OIDC token, Fulcio, Rekor),
     * verifies the bundle against the identity the installers will demand, and
     * attaches it; both installers check it — before trusting any hash in the list
     * — whenever cosign is installed.
     */
    @Test
    @DisplayName("the release signs SHA256SUMS with cosign keyless and both installers verify it against the release workflow")
    void checksumListIsSignedAndVerified() throws IOException {
        String ci = Files.readString(Path.of(".github", "workflows", "ci.yml"), StandardCharsets.UTF_8).replace("\r\n", "\n");
        String release = ci.substring(ci.indexOf("\n  release:\n"), ci.indexOf("body: |", ci.indexOf("\n  release:\n")));
        assertTrue(release.contains("id-token: write"), "the release job needs id-token: write to sign keyless");
        assertTrue(release.contains("uses: sigstore/cosign-installer@"), "the release job must install cosign");
        assertTrue(release.contains("cosign sign-blob --yes --bundle SHA256SUMS.sigstore.json SHA256SUMS"),
                "the release job must sign SHA256SUMS into a Sigstore bundle");
        assertTrue(release.contains("SIGNER_IDENTITY: https://github.com/${{ github.repository }}/.github/workflows/ci.yml@${{ github.ref }}")
                && release.contains("cosign verify-blob --bundle SHA256SUMS.sigstore.json")
                && release.contains("--certificate-oidc-issuer " + SIGNER_ISSUER),
                "the release job must verify the bundle against the identity the installers demand before publishing it");
        assertTrue(release.contains("files: |\n            SHA256SUMS\n            SHA256SUMS.sigstore.json\n"),
                "the bundle must be attached to the GitHub release next to SHA256SUMS");

        String shell = Files.readString(INSTALL_SH, StandardCharsets.UTF_8).replace("\r\n", "\n");
        assertTrue(shell.contains("RELEASE_SIGNER_IDENTITY_PREFIX=\"" + SIGNER_IDENTITY_PREFIX + "\"")
                && shell.contains("RELEASE_SIGNER_ISSUER=\"" + SIGNER_ISSUER + "\""),
                "install.sh must pin the release workflow identity and the GitHub Actions issuer");
        String load = shell.substring(shell.indexOf("load_release_checksums() {"));
        assertTrue(load.indexOf("verify_release_checksums_signature \"$sums\"") > 0
                && load.indexOf("verify_release_checksums_signature \"$sums\"") < load.indexOf("RELEASE_CHECKSUMS=\"$sums\""),
                "install.sh must verify the signature BEFORE it trusts the list");

        String ps = Files.readString(INSTALL_PS1, StandardCharsets.UTF_8).replace("\r\n", "\n");
        assertTrue(ps.contains("$script:ReleaseSignerIdentityPrefix = \"" + SIGNER_IDENTITY_PREFIX + "\"")
                && ps.contains("$script:ReleaseSignerIssuer = \"" + SIGNER_ISSUER + "\""),
                "install.ps1 must pin the release workflow identity and the GitHub Actions issuer");
        String init = ps.substring(ps.indexOf("function Initialize-ReleaseChecksums {"));
        assertTrue(init.indexOf("Confirm-ReleaseChecksumsSignature -Bytes $bytes") > 0
                && init.indexOf("Confirm-ReleaseChecksumsSignature -Bytes $bytes") < init.indexOf("$script:ReleaseChecksums = $table"),
                "install.ps1 must verify the signature BEFORE it trusts the list");
        String confirm = ps.substring(ps.indexOf("function Confirm-ReleaseChecksumsSignature {"),
                ps.indexOf("\n}\n", ps.indexOf("function Confirm-ReleaseChecksumsSignature {")));
        assertTrue(confirm.contains("& cosign verify-blob --bundle $bundleFile") && confirm.contains("Write-Fail"),
                "install.ps1 must run cosign verify-blob and refuse a list that does not verify");
    }

    /**
     * The shell half, executed: install.sh's own functions with a stub curl and a
     * stub cosign. Without cosign exactly one warning; with cosign the exact
     * identity and issuer are demanded, a verified list is used, and a list that
     * fails verification stops the install.
     */
    @Test
    @DisplayName("install.sh checks the SHA256SUMS signature only when cosign is installed, and refuses a bad one")
    void shellVerifiesTheChecksumSignature() throws Exception {
        Path bash = locateBash();
        assumeTrue(bash != null, "no non-WSL bash available; CI's ubuntu-latest runner has one");
        Path dir = sandbox("sums-signature");
        writeCurlStub(dir);
        Path cosignDir = Files.createDirectories(dir.resolve("cosign"));
        Path cosign = cosignDir.resolve("cosign");
        Files.writeString(cosign, """
                #!/usr/bin/env bash
                printf '%s\\n' "$@" > "$(dirname "$0")/cosign.args"
                exit "${STUB_COSIGN_EXIT:-0}"
                """, StandardCharsets.US_ASCII);
        cosign.toFile().setExecutable(true, false);
        Path driver = dir.resolve("driver.sh");
        Files.writeString(driver, """
                set -u
                warn() { echo "WARN: $*"; }
                info() { echo "INFO: $*"; }
                fail() { echo "FAIL: $*"; exit 1; }
                EDDI_BRANCH="${STUB_BRANCH:-9.8.7}"
                export PATH="$(pwd)/stub${WITH_COSIGN:+:$(pwd)/cosign}:/usr/bin:/bin"
                if [[ -z "${WITH_COSIGN:-}" ]] && command -v cosign >/dev/null; then echo REAL_COSIGN; exit 0; fi
                eval "$(sed -n -e '/^RELEASE_CHECKSUMS=""$/,/^RELEASE_SIGNER_ISSUER=/p' \\
                  -e '/^sha256_of() {$/,/^}$/p' \\
                  -e '/^release_is_signed() {$/,/^}$/p' \\
                  -e '/^verify_release_checksums_signature() {$/,/^}$/p' \\
                  -e '/^load_release_checksums() {$/,/^}$/p' "$INSTALL_SH")"
                load_release_checksums
                echo "LOADED=${RELEASE_CHECKSUMS:+yes}"
                """, StandardCharsets.US_ASCII);
        String base = "cd \"" + slashed(dir) + "\" && INSTALL_SH=\"" + slashed(INSTALL_SH.toAbsolutePath()) + "\" ";

        Run without = execute(bash, base + "bash driver.sh", dir, null);
        assumeTrue(!without.output().contains("REAL_COSIGN"), "a real cosign is installed system-wide");
        assertEquals(0, without.exit(), without.output());
        assertEquals(1, without.output().lines().filter(line -> line.contains("cosign is not installed")).count(),
                "without cosign there must be exactly one warning line: " + without.output());
        assertTrue(without.output().contains("LOADED=yes"), "the checksums are still used: " + without.output());

        String withCosign = base + "WITH_COSIGN=1 ";
        Run verified = execute(bash, withCosign + "bash driver.sh", dir, null);
        assertEquals(0, verified.exit(), verified.output());
        assertTrue(verified.output().contains("SHA256SUMS signature verified") && verified.output().contains("LOADED=yes"),
                verified.output());
        assertTrue(verified.output().contains("/releases/download/9.8.7/SHA256SUMS.sigstore.json"),
                "the bundle must be fetched from the same release: " + verified.output());
        List<String> args = Files.readAllLines(cosignDir.resolve("cosign.args"), StandardCharsets.UTF_8);
        assertEquals("verify-blob", args.getFirst());
        assertEquals(SIGNER_IDENTITY_PREFIX + "9.8.7", args.get(args.indexOf("--certificate-identity") + 1),
                "the certificate must name the release workflow at exactly this tag: " + args);
        assertEquals(SIGNER_ISSUER, args.get(args.indexOf("--certificate-oidc-issuer") + 1));

        Run refused = execute(bash, withCosign + "STUB_COSIGN_EXIT=1 bash driver.sh", dir, null);
        assertNotEquals(0, refused.exit(), refused.output());
        assertTrue(refused.output().contains("FAIL: The SHA256SUMS of release 9.8.7 is not signed")
                && !refused.output().contains("LOADED="), "a list that fails verification must stop the install: " + refused.output());

        Run missing = execute(bash, withCosign + "STUB_FAIL_URL=SHA256SUMS.sigstore.json bash driver.sh", dir, null);
        assertNotEquals(0, missing.exit(), missing.output());
        assertTrue(missing.output().contains("should carry SHA256SUMS.sigstore.json") && !missing.output().contains("LOADED="),
                "a release from the first signed one on with no bundle must be refused, not taken for an old"
                        + " unsigned one: " + missing.output());

        Run unsigned = execute(bash, withCosign + "STUB_BRANCH=6.5.0 STUB_FAIL_URL=SHA256SUMS.sigstore.json bash driver.sh", dir,
                null);
        assertEquals(0, unsigned.exit(), unsigned.output());
        assertTrue(unsigned.output().contains("predates signed checksums") && unsigned.output().contains("LOADED=yes"),
                "a release from before the first signed one is used unsigned, with a warning: " + unsigned.output());

        Run noSums = execute(bash, base + "STUB_FAIL_URL=SHA256SUMS bash driver.sh", dir, null);
        assertNotEquals(0, noSums.exit(), noSums.output());
        assertTrue(noSums.output().contains("should publish SHA256SUMS") && !noSums.output().contains("LOADED="),
                "a signed release without its checksum list must be refused even without cosign: " + noSums.output());
        Run oldNoSums = execute(bash, base + "STUB_BRANCH=6.5.0 STUB_FAIL_URL=SHA256SUMS bash driver.sh", dir, null);
        assertEquals(0, oldNoSums.exit(), oldNoSums.output());
        assertTrue(oldNoSums.output().contains("publishes no SHA256SUMS"), oldNoSums.output());

        Run candidate = execute(bash, withCosign + "STUB_BRANCH=6.6.0-RC1 STUB_FAIL_URL=SHA256SUMS.sigstore.json bash driver.sh",
                dir, null);
        assertNotEquals(0, candidate.exit(), "a pre-release of the first signed version is signed too: " + candidate.output());
    }

    /**
     * A curl that answers the releases API with {@link #STUB_TAG} (or fails like an
     * unreachable host when STUB_API_FAIL=1), writes "stub" for any {@code -o}
     * download, and logs every URL it was asked for to stub/curl.log.
     */
    private static void writeCurlStub(Path dir) throws IOException {
        Path stub = Files.createDirectories(dir.resolve("stub"));
        Path curl = stub.resolve("curl");
        Files.writeString(curl, """
                #!/usr/bin/env bash
                out=""; url=""
                while [[ $# -gt 0 ]]; do
                  case "$1" in
                    -o) out="$2"; shift ;;
                    -H|--max-time|-w) shift ;;
                    http*) url="$1" ;;
                  esac
                  shift
                done
                echo "$url" >> "$(dirname "$0")/curl.log"
                if [[ -n "${STUB_FAIL_URL:-}" && "$url" == *"$STUB_FAIL_URL"* ]]; then
                  echo "curl: (22) The requested URL returned error: 404" >&2; exit 22
                fi
                case "$url" in
                  *api.github.com*)
                    if [[ "$STUB_API_FAIL" == "1" ]]; then echo "curl: (6) Could not resolve host" >&2; exit 6; fi
                    printf '{\\n  "url": "https://api.github.com/x",\\n  "tag_name": "%s",\\n  "prerelease": false\\n}\\n' "%s" ;;
                  *) if [[ -n "$out" ]]; then echo stub > "$out"; fi ;;
                esac
                exit 0
                """.formatted("%s", STUB_TAG), StandardCharsets.US_ASCII);
        curl.toFile().setExecutable(true, false);
    }

    private static Run execute(Path bash, String command, Path dir, Path eddiDir) throws Exception {
        ProcessBuilder builder = new ProcessBuilder(bash.toString(), "-c", command);
        builder.redirectErrorStream(true);
        Process process = builder.start();
        Run run = finish(process, eddiDir);
        Path log = dir.resolve("stub").resolve("curl.log");
        String fetched = Files.exists(log) ? Files.readString(log, StandardCharsets.UTF_8) : "";
        return new Run(run.exit(), run.output() + "\n[curl log]\n" + fetched, run.config(), run.env());
    }

    private static Run finish(Process process, Path eddiDir) throws Exception {
        String output;
        try (var stream = process.getInputStream()) {
            output = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
        if (!process.waitFor(2, TimeUnit.MINUTES)) {
            process.destroyForcibly();
            throw new AssertionError("the installer did not finish; it printed:\n" + output);
        }
        String config = eddiDir == null ? "" : Files.readString(eddiDir.resolve(".eddi-config"), StandardCharsets.UTF_8);
        String env = eddiDir == null ? "" : Files.readString(eddiDir.resolve(".env"), StandardCharsets.UTF_8);
        return new Run(process.exitValue(), output, config, env);
    }

    private static Path sandbox(String name) throws IOException {
        Path dir = Path.of("target", "installer-ref", name).toAbsolutePath();
        if (Files.exists(dir)) {
            try (var walk = Files.walk(dir)) {
                walk.sorted((a, b) -> b.getNameCount() - a.getNameCount()).forEach(p -> p.toFile().delete());
            }
        }
        return Files.createDirectories(dir);
    }

    private static String slashed(Path path) {
        return path.toAbsolutePath().toString().replace('\\', '/');
    }

    /**
     * Git for Windows' bash; System32\bash.exe is the WSL launcher and cannot see
     * these files.
     */
    private static Path locateBash() {
        if (!WINDOWS) {
            return locateOnPath("bash");
        }
        for (String programFiles : List.of("ProgramFiles", "ProgramW6432", "ProgramFiles(x86)")) {
            String root = System.getenv(programFiles);
            if (root == null) {
                continue;
            }
            Path candidate = Path.of(root, "Git", "bin", "bash.exe");
            if (Files.isExecutable(candidate)) {
                return candidate;
            }
        }
        Path onPath = locateOnPath("bash.exe");
        return onPath != null && !onPath.toString().toLowerCase(Locale.ROOT).contains("system32") ? onPath : null;
    }

    private static Path locateOnPath(String executable) {
        String path = System.getenv("PATH");
        if (path == null) {
            return null;
        }
        for (String entry : path.split(Pattern.quote(File.pathSeparator))) {
            if (entry.isBlank()) {
                continue;
            }
            try {
                Path candidate = Path.of(entry, executable);
                if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) {
                    return candidate;
                }
            } catch (InvalidPathException ignored) {
                // A PATH entry that is not a path cannot hold the executable.
            }
        }
        return null;
    }
}
