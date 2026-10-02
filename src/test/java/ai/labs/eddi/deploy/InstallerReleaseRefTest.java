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
        return finish(builder.start(), null);
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
