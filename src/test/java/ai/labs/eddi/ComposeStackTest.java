/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Keeps the compose set internally consistent, because nothing else parses it.
 * <p>
 * README.md promises that "overlays stack in any combination", and the NATS
 * overlay quietly broke that promise in three ways at once — it redeclared the
 * base's {@code mongodb} service, so a stacked
 * {@code -f docker-compose.yml -f docker-compose.nats.yml up} dropped the
 * credentials the base passes while the base kept auth switched ON in mongod
 * (EDDI then authenticated as nobody), replaced the {@code mongodb-db} mount
 * with an empty {@code mongo-data} volume (volumes merge on the target path, so
 * every agent and conversation was still in the old one — indistinguishable
 * from data loss to the person looking at the screen), and bound a second host
 * port beside the base's. Its own header contradicted itself about whether the
 * file even was an overlay.
 * <p>
 * None of that is visible to any gate in this repository: compose files are not
 * compiled, not linted, and never started by CI. They are, however, plain YAML
 * with well-defined merge semantics, so the invariants are decidable — which is
 * what this test does, in the ordinary {@code mvn test} pass.
 */
@DisplayName("compose stack consistency")
class ComposeStackTest {

    private static final Path BASE = Path.of("docker-compose.yml");
    private static final Path README = Path.of("README.md");
    private static final Path WORKFLOWS = Path.of(".github", "workflows");

    /**
     * Files that are COMPLETE stacks and are meant to be run on their own, so they
     * legitimately declare their own database and their own {@code eddi} image.
     * Everything else in the set is an overlay layered onto {@link #BASE} and must
     * not redeclare what the base provides.
     * <p>
     * {@code docker-compose.postgres.yml} used to be documented as an overlay and
     * could not be one — an overlay cannot un-declare the base's {@code mongodb}
     * service, so the documented "PostgreSQL instead of MongoDB" command still ran
     * MongoDB and waited on its health check. It was replaced by
     * {@code docker-compose.postgres-only.yml}, which is honest about being a whole
     * stack.
     */
    private static final Set<String> STANDALONE_STACKS = Set.of(
            "docker-compose.yml",
            "docker-compose.postgres-only.yml",
            "docker-compose.openwebui.yml",
            "docker-compose.cluster.yml");

    /**
     * A {@code mongo:<tag>} image reference. Anchored on a non-word character so
     * {@code eddi-boot-mongodb} and friends do not match.
     */
    private static final Pattern MONGO_IMAGE = Pattern.compile("(?<![\\w./-])mongo:(\\d[\\w.\\-]*)");

    /** Any {@code docker-compose*.yml} file name mentioned in prose. */
    private static final Pattern COMPOSE_REFERENCE = Pattern.compile("docker-compose[\\w.\\-]*\\.yml");

    private static final YAMLMapper YAML = new YAMLMapper();

    /**
     * The services a compose stack is FOR, which a user reaches from a browser or
     * an SDK and which are published on every interface on purpose. Everything else
     * is infrastructure.
     */
    private static final Set<String> USER_FACING_SERVICES = Set.of("eddi", "open-webui");

    /**
     * Host addresses an infrastructure port may be published on: the loopback
     * literal, or a variable that DEFAULTS to it (an operator exposing Keycloak
     * deliberately, behind TLS, sets {@code KEYCLOAK_BIND}).
     */
    private static final String LOOPBACK_HOST = "(127\\.0\\.0\\.1|\\$\\{[A-Z_]+_BIND:-127\\.0\\.0\\.1\\})";

    /**
     * A short-form mapping ({@code host:published:target}) whose host is loopback.
     */
    private static final Pattern LOOPBACK_PUBLISH = Pattern.compile("^" + LOOPBACK_HOST + ":.+");

    /** The {@code host_ip} of a long-form port declaration, on its own. */
    private static final Pattern LOOPBACK_HOST_IP = Pattern.compile("^" + LOOPBACK_HOST + "$");

    /**
     * Administrator passwords of the bundled third-party services. Each comes from
     * an overridable variable, never a hard-coded literal, so the installers (which
     * generate one into .env) and an operator can replace it. The default keeps a
     * plain {@code docker compose up} working; the port is loopback-bound.
     */
    private static final Set<String> ADMIN_PASSWORD_VARIABLES = Set.of("KC_BOOTSTRAP_ADMIN_PASSWORD",
            "GF_SECURITY_ADMIN_PASSWORD");

    private static List<Path> composeFiles() {
        try (Stream<Path> files = Files.list(Path.of("").toAbsolutePath())) {
            return files.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().startsWith("docker-compose")
                            && path.getFileName().toString().endsWith(".yml"))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("could not list the project root; the working directory must be it", e);
        }
    }

    private static String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("could not read " + path.toAbsolutePath(), e);
        }
    }

    /** {@code service name -> service node} for one compose file. */
    private static TreeMap<String, JsonNode> services(Path file) {
        TreeMap<String, JsonNode> services = new TreeMap<>();
        JsonNode root;
        try {
            root = YAML.readTree(read(file));
        } catch (IOException e) {
            throw new AssertionError(file + " is not valid YAML: " + e.getMessage(), e);
        }
        JsonNode node = root.path("services");
        assertTrue(node.isObject(), file + " declares no `services:` mapping");
        Iterator<String> names = node.fieldNames();
        while (names.hasNext()) {
            String name = names.next();
            services.put(name, node.get(name));
        }
        return services;
    }

    private static String name(Path file) {
        return file.getFileName().toString();
    }

    /**
     * Strips {@code #} comments so a hazard described in prose is not read as one.
     */
    private static String withoutComments(String text) {
        return text.lines()
                .map(line -> {
                    int hash = line.indexOf('#');
                    return hash < 0 ? line : line.substring(0, hash);
                })
                .collect(Collectors.joining("\n"));
    }

    /**
     * The defect itself. Compose merges an overlay into the base key by key, so a
     * service the overlay redeclares does not replace the base's — and the
     * surprising half of that is per-key. {@code volumes} merge on the TARGET path,
     * so a different source silently swaps the data directory out from under a
     * running deployment and every agent and conversation appears to have vanished.
     * {@code ports} merge on the whole entry, so a different host_ip publishes the
     * same port twice and the stack fails to bind.
     * <p>
     * Overriding {@code image}, {@code build} or {@code environment} is what an
     * overlay is FOR — {@code docker-compose.local.yml} exists to swap the image
     * for a source build — so those are not policed. Two things are:
     * <ul>
     * <li>a stateful base service (one the base gives a volume, i.e. the database)
     * may not be redeclared by an overlay at all — that is the file that has to
     * decide whether it is an overlay or a whole stack;</li>
     * <li>no overlay may set {@code ports} or {@code volumes} on any base
     * service.</li>
     * </ul>
     * A file that genuinely needs its own database belongs in
     * {@link #STANDALONE_STACKS}, which is a deliberate, reviewable act rather than
     * a silent redefinition.
     */
    @Test
    @DisplayName("overlays do not redeclare the base file's stateful services or republish its ports")
    void overlaysDoNotBringTheirOwnDatabase() {
        TreeMap<String, JsonNode> base = services(BASE);
        TreeSet<String> stateful = new TreeSet<>();
        base.forEach((service, node) -> {
            if (node.has("volumes")) {
                stateful.add(service);
            }
        });
        assertFalse(stateful.isEmpty(), "the base file declares no stateful service — the sweep would be vacuous");

        List<String> offenders = new ArrayList<>();
        for (Path file : composeFiles()) {
            if (STANDALONE_STACKS.contains(name(file))) {
                continue;
            }
            for (var entry : services(file).entrySet()) {
                if (!base.containsKey(entry.getKey())) {
                    continue;
                }
                if (stateful.contains(entry.getKey())) {
                    offenders.add(name(file) + " redeclares the base's stateful `" + entry.getKey() + "` service");
                    continue;
                }
                for (String key : List.of("ports", "volumes")) {
                    if (entry.getValue().has(key)) {
                        offenders.add(name(file) + " sets `" + key + "` on the base's `" + entry.getKey() + "` service");
                    }
                }
            }
        }

        assertEquals(List.of(), offenders,
                "an overlay must not redeclare the base file's infrastructure: `volumes` merge on the TARGET path, so"
                        + " a different source silently swaps the data directory out from under a running deployment,"
                        + " and `ports` with a different host_ip publish twice. Drop the keys, or add the file to"
                        + " STANDALONE_STACKS if it is really a whole stack rather than a layer.");
    }

    /**
     * The other direction: an overlay's services must actually exist in the base,
     * or it is broken as an overlay too. A service with neither {@code image} nor
     * {@code build} that the base does not declare fails {@code docker compose up}
     * with a hard error ("service has neither an image nor a build context") rather
     * than degrading.
     */
    @Test
    @DisplayName("every service resolves to an image, a build, or the base file")
    void everyServiceResolvesToAnImageOrTheBase() {
        Set<String> baseServices = services(BASE).keySet();
        List<String> offenders = new ArrayList<>();

        for (Path file : composeFiles()) {
            boolean standalone = STANDALONE_STACKS.contains(name(file));
            for (var entry : services(file).entrySet()) {
                if (entry.getValue().has("image") || entry.getValue().has("build")) {
                    continue;
                }
                if (standalone) {
                    offenders.add(name(file) + " is a standalone stack but `" + entry.getKey()
                            + "` has neither image nor build");
                } else if (!baseServices.contains(entry.getKey())) {
                    offenders.add(name(file) + " overlays `" + entry.getKey()
                            + "`, which has no image or build and is not declared by " + name(BASE));
                }
            }
        }

        assertEquals(List.of(), offenders, "compose fails hard on a service with neither an image nor a build context");
    }

    /**
     * A compose file named in the README has to exist.
     * {@code docker-compose.postgres.yml} was deleted on this branch — it was a
     * documented command in users' shell history, so the README had to move to the
     * survivor in the same change. Nothing but this notices a file name that no
     * longer resolves; DocumentationLinksTest grades Markdown links, and these are
     * bare file names in fenced commands.
     */
    @Test
    @DisplayName("every compose file the README names exists")
    void everyComposeFileTheReadmeNamesExists() {
        TreeSet<String> missing = new TreeSet<>();
        Matcher matcher = COMPOSE_REFERENCE.matcher(read(README));

        while (matcher.find()) {
            String reference = matcher.group();
            if (!Files.isRegularFile(Path.of(reference))) {
                missing.add(reference);
            }
        }

        assertEquals(Set.of(), missing,
                README + " documents compose files that do not exist. A stale `-f <file>` is a copy-paste command"
                        + " that fails with 'no such file' on a first-run user's terminal.");
    }

    /**
     * The other direction: a compose file nobody documents is one nobody can run.
     * {@code docker-compose.ollama-nvidia.yml} shipped naming itself in its own
     * first line and appearing nowhere else in the repository — not the README, not
     * a page under {@code docs/} — so the only way to discover the GPU overlay was
     * to list the project root.
     * <p>
     * The README is not required to carry all of them: {@code docs/} counts too,
     * which is where the Open WebUI stack and the MCP sidecar are explained. The
     * whole tree counts, not only its top level — {@code docs/monitoring/} and
     * {@code docs/creating-your-first-agent/} hold real pages, and a stack
     * explained on one of those is explained. Listing only the top level would
     * eventually have failed this test over a file that <em>is</em> documented,
     * which is the failure mode that gets a guard deleted rather than fixed.
     * <p>
     * The changelog does not count, in any of its three shapes
     * ({@code changelog.md}, the {@code changelog/} archives, the
     * {@code changelog.d/} fragments): it records what changed on a day, and an
     * entry scrolls out of the live file into an archive nothing reads for current
     * context. Nor does {@code docs/archive/}, for the same reason — a page kept
     * for history is not a page a user is sent to.
     */
    @Test
    @DisplayName("every compose file the repository ships is documented somewhere")
    void everyComposeFileIsDocumented() {
        List<Path> pages = new ArrayList<>(List.of(README));
        try (Stream<Path> docs = Files.walk(Path.of("docs"))) {
            docs.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".md"))
                    .filter(ComposeStackTest::countsAsDocumentation)
                    .sorted()
                    .forEach(pages::add);
        } catch (IOException e) {
            throw new UncheckedIOException("could not walk docs/", e);
        }

        // The sweep's own shape, asserted rather than assumed. Listing only the top
        // level of docs/ would still pass the check below today — every stack
        // happens to be named in a top-level page — and would start failing later
        // over a file that IS documented, in a nested page. Pin both edges now.
        assertTrue(pages.stream().anyMatch(page -> page.getNameCount() > 2),
                "the sweep must walk docs/ rather than list it: pages live in docs/monitoring/ and "
                        + "docs/creating-your-first-agent/ too, and a stack explained there is explained");
        assertFalse(pages.stream().anyMatch(ComposeStackTest::countsAsChangelog),
                "the changelog must stay out of the sweep in all three of its shapes, or this guard "
                        + "passes on the strength of the very entry that announced the undocumented file");

        String documentation = pages.stream()
                .map(ComposeStackTest::read)
                .collect(Collectors.joining("\n"));

        TreeSet<String> undocumented = new TreeSet<>();
        for (Path file : composeFiles()) {
            // The compose files themselves are not in the sweep: a header naming
            // its own file is not documentation of it.
            if (!documentation.contains(name(file))) {
                undocumented.add(name(file));
            }
        }

        assertEquals(Set.of(), undocumented,
                "these compose files are named nowhere in README.md or docs/: " + undocumented
                        + ". A stack a user cannot find is a stack they do not run — say what it layers on,"
                        + " and what it needs of the host.");
    }

    /**
     * Whether a page under {@code docs/} is somewhere a user is actually sent.
     * <p>
     * The changelog in all three of its shapes is not, and neither is
     * {@code docs/archive/}: a mention there records that a file once existed, not
     * how to run it. Counting them would let this guard pass on the strength of the
     * very entry that announced the undocumented file.
     * <p>
     * The relative path is normalised to forward slashes first, because the walk
     * yields a platform separator and these prefixes are written one way.
     */
    private static boolean countsAsDocumentation(Path page) {
        return !countsAsChangelog(page) && !relativeToDocs(page).startsWith("archive/");
    }

    /** The changelog, in all three of the shapes it is stored in. */
    private static boolean countsAsChangelog(Path page) {
        String relative = relativeToDocs(page);
        return relative.equals("changelog.md")
                || relative.startsWith("changelog/")
                || relative.startsWith("changelog.d/");
    }

    /**
     * The page's path below {@code docs/}, always with forward slashes: the walk
     * yields a platform separator and the prefixes above are written one way. A
     * page outside {@code docs/} — the README — keeps its own path, which matches
     * none of them.
     */
    private static String relativeToDocs(Path page) {
        Path docs = Path.of("docs");
        Path relative = page.startsWith(docs) ? docs.relativize(page) : page;
        return relative.toString().replace(File.separatorChar, '/');
    }

    /**
     * Keycloak (the master-realm superuser, which can mint an eddi-admin for
     * anyone), Grafana, Prometheus, the Jaeger UI and OTLP, NATS, Chroma and Ollama
     * were all published on {@code 0.0.0.0}. Most of them have no authentication at
     * all, and the two that do shipped as {@code admin}/{@code admin} — so anyone
     * on the same LAN or Wi-Fi as a developer running the stack could administer
     * its identity provider or run models on the machine. {@code install.sh} labels
     * the Keycloak option "production".
     * <p>
     * The base file already bound MongoDB to {@code 127.0.0.1}; this holds every
     * infrastructure port to the same rule. Only the services a user is meant to
     * reach ({@link #USER_FACING_SERVICES}) may take a bare {@code host:container}
     * mapping, which publishes on every interface.
     */
    @Test
    @DisplayName("only the user-facing services are published beyond loopback")
    void infrastructurePortsArePublishedOnLoopbackOnly() {
        List<String> offenders = new ArrayList<>();
        int checked = 0;
        for (Path file : composeFiles()) {
            for (var entry : services(file).entrySet()) {
                if (USER_FACING_SERVICES.contains(entry.getKey())) {
                    continue;
                }
                for (JsonNode port : entry.getValue().path("ports")) {
                    checked++;
                    if (!publishesOnLoopback(port)) {
                        offenders.add(name(file) + " publishes `" + entry.getKey() + "` as `" + port + "`");
                    }
                }
            }
        }
        assertTrue(checked > 0, "no infrastructure port found at all — the sweep would be vacuous");
        assertEquals(List.of(), offenders,
                "an infrastructure service is published on every interface. Prefix the mapping with 127.0.0.1: (or a"
                        + " *_BIND variable defaulting to it) — the containers reach each other over the compose"
                        + " network, so the host binding is only for the developer's own shell.");
    }

    /**
     * Short-form strings carry the host in the mapping; the long form
     * ({@code target}/{@code published}/{@code host_ip}) carries it in
     * {@code host_ip} alone, which must be checked by itself rather than glued to a
     * {@code :port} suffix it does not have. A long form without {@code host_ip}
     * publishes on every interface.
     */
    private static boolean publishesOnLoopback(JsonNode port) {
        if (port.isTextual()) {
            return LOOPBACK_PUBLISH.matcher(port.asText()).matches();
        }
        return LOOPBACK_HOST_IP.matcher(port.path("host_ip").asText("")).matches();
    }

    @Test
    @DisplayName("the loopback check reads both short-form and long-form port declarations")
    void loopbackCheckHandlesBothPortForms() throws IOException {
        assertTrue(publishesOnLoopback(YAML.readTree("\"127.0.0.1:9090:9090\"")));
        assertTrue(publishesOnLoopback(YAML.readTree("\"${KEYCLOAK_BIND:-127.0.0.1}:8180:8080\"")));
        assertFalse(publishesOnLoopback(YAML.readTree("\"9090:9090\"")));
        assertFalse(publishesOnLoopback(YAML.readTree("\"0.0.0.0:9090:9090\"")));

        assertTrue(publishesOnLoopback(YAML.readTree("{target: 9090, published: 9090, host_ip: 127.0.0.1}")));
        assertTrue(publishesOnLoopback(YAML.readTree("{target: 8080, published: 8180, host_ip: \"${KEYCLOAK_BIND:-127.0.0.1}\"}")));
        assertFalse(publishesOnLoopback(YAML.readTree("{target: 9090, published: 9090}")),
                "a long-form port without host_ip publishes on every interface");
        assertFalse(publishesOnLoopback(YAML.readTree("{target: 9090, published: 9090, host_ip: 0.0.0.0}")));
    }

    /**
     * {@code KC_BOOTSTRAP_ADMIN_PASSWORD: admin} and
     * {@code GF_SECURITY_ADMIN_PASSWORD: admin} used to be literals, which nothing
     * could override. Each is now a variable with a loopback-only dev default
     * ({@code ${VAR:-admin}}): the installers write a generated value into .env,
     * and a bare {@code docker compose up} still starts. A {@code :?} requirement
     * would break that first run.
     */
    @Test
    @DisplayName("bundled admin passwords come from overridable variables with a dev default")
    void adminPasswordsAreOverridable() {
        List<String> offenders = new ArrayList<>();
        Set<String> found = new TreeSet<>();
        for (Path file : composeFiles()) {
            for (var entry : services(file).entrySet()) {
                JsonNode environment = entry.getValue().path("environment");
                for (String variable : ADMIN_PASSWORD_VARIABLES) {
                    String value = null;
                    if (environment.isObject() && environment.has(variable)) {
                        value = environment.get(variable).asText();
                    } else if (environment.isArray()) {
                        for (JsonNode item : environment) {
                            if (item.asText().startsWith(variable + "=")) {
                                value = item.asText().substring(variable.length() + 1);
                            }
                        }
                    }
                    if (value == null) {
                        continue;
                    }
                    found.add(variable);
                    if (!value.matches("^\\$\\{[A-Z_]+:-[^}]*\\}$")) {
                        offenders.add(name(file) + " sets " + variable + " on `" + entry.getKey() + "` to `" + value + "`");
                    }
                }
            }
        }
        assertEquals(new TreeSet<>(ADMIN_PASSWORD_VARIABLES), found,
                "expected each of " + ADMIN_PASSWORD_VARIABLES + " somewhere in the compose files — a renamed"
                        + " variable would otherwise leave this sweep checking nothing");
        assertEquals(List.of(), offenders,
                "an administrator password is a literal or a required variable. Use ${SOME_VARIABLE:-admin}, so the"
                        + " installers can supply a generated value through .env and a bare `docker compose up` still"
                        + " starts.");
    }

    private static final Path AUTH_OVERLAY = Path.of("docker-compose.auth.yml");

    /**
     * {@code KC_BOOTSTRAP_ADMIN_PASSWORD} falls back to {@code admin} so the
     * loopback quick start needs no .env — which was acceptable only while
     * {@code KEYCLOAK_BIND} stayed on loopback, and nothing enforced that. The auth
     * overlay's {@code keycloak-guard} now does, the way docker-compose.yml's
     * {@code bind-guard} does for EDDI: Keycloak waits for it to complete
     * successfully, and it reads both values from its own environment with the same
     * defaults the keycloak service interpolates.
     */
    @Test
    @DisplayName("Keycloak cannot start off loopback with the admin/admin bootstrap fallback")
    void keycloakWaitsForItsDefaultPasswordGuard() {
        TreeMap<String, JsonNode> services = services(AUTH_OVERLAY);
        JsonNode keycloak = services.get("keycloak");
        JsonNode guard = services.get("keycloak-guard");
        assertTrue(keycloak != null && guard != null, "docker-compose.auth.yml must declare keycloak and keycloak-guard");

        assertEquals("service_completed_successfully",
                keycloak.path("depends_on").path("keycloak-guard").path("condition").asText(),
                "keycloak must wait for keycloak-guard to EXIT 0, or a refusing guard is only a log line");

        assertEquals(keycloak.path("environment").path("KC_BOOTSTRAP_ADMIN_PASSWORD").asText(),
                guard.path("environment").path("KC_BOOTSTRAP_ADMIN_PASSWORD").asText(),
                "the guard must judge the same password Keycloak will be given");
        String bind = guard.path("environment").path("KEYCLOAK_BIND").asText();
        assertEquals("${KEYCLOAK_BIND:-127.0.0.1}", bind, "the guard must read KEYCLOAK_BIND with the ports' default");
        assertTrue(keycloak.path("ports").get(0).asText().startsWith(bind + ":"),
                "keycloak's port must be published on the same KEYCLOAK_BIND the guard checks");
    }

    /**
     * The guard can only see the configured password; Keycloak reads it on first
     * boot only, so a volume created with {@code admin}/{@code admin} keeps that
     * login whatever .env says later (CodeRabbit on PR #1099).
     * {@code keycloak-admin-rotate} closes that: once Keycloak is healthy, and
     * before EDDI starts, it sets a still-working default login to the configured
     * password, which it hands to kcadm on stdin rather than its command line.
     */
    @Test
    @DisplayName("an existing admin/admin login is rotated to the configured password before EDDI starts")
    void keycloakDefaultLoginIsRotatedOnAnExistingVolume() {
        TreeMap<String, JsonNode> services = services(AUTH_OVERLAY);
        JsonNode rotate = services.get("keycloak-admin-rotate");
        assertTrue(rotate != null, "docker-compose.auth.yml must declare keycloak-admin-rotate");
        assertEquals("service_healthy", rotate.path("depends_on").path("keycloak").path("condition").asText(),
                "the rotation needs a running Keycloak to log in to");
        assertEquals("service_completed_successfully",
                services.get("eddi").path("depends_on").path("keycloak-admin-rotate").path("condition").asText(),
                "EDDI must not start before the default login has been checked and rotated");
        assertEquals(services.get("keycloak").path("environment").path("KC_BOOTSTRAP_ADMIN_PASSWORD").asText(),
                rotate.path("environment").path("KC_BOOTSTRAP_ADMIN_PASSWORD").asText(),
                "the rotation must set the same password the guard judged");

        String script = rotate.path("command").get(0).asText();
        assertTrue(script.contains("--password admin") && script.contains("reset-password") && script.contains("-f -"),
                "the script must try the default login and reset the password from stdin: " + script);
        assertFalse(script.lines().anyMatch(line -> line.contains("kcadm") && line.contains("$$KC_BOOTSTRAP_ADMIN_PASSWORD")),
                "the configured password must not appear on a kcadm command line: " + script);

        // The marker is how the guard learns that an existing volume was checked.
        assertEquals("keycloak-data:/opt/keycloak/data", rotate.path("volumes").get(0).asText(),
                "the rotation must write its marker into the keycloak-data volume");
        assertTrue(script.contains("touch \"$$marker\"") && script.contains(".eddi-admin-verified"),
                "the rotation must leave the marker the guard looks for: " + script);
        assertEquals("keycloak-data:/kc-data:ro", services.get("keycloak-guard").path("volumes").get(0).asText(),
                "the guard must see the volume, read-only, to tell an existing one from a fresh one");
    }

    /**
     * Runs the guard's script itself, so the decision table is exercised and not
     * just the wiring. Needs a POSIX {@code sh} — always there on the Linux CI
     * runner, skipped where there is none.
     */
    @Test
    @DisplayName("the Keycloak guard refuses exactly a non-loopback bind with the default password")
    void keycloakGuardScriptDecides() throws Exception {
        Assumptions.assumeTrue(shellAvailable(), "no POSIX sh on PATH");
        String script = services(AUTH_OVERLAY).get("keycloak-guard").path("command").get(0).asText()
                // compose's escape: `$$` reaches the shell as `$`
                .replace("$$", "$");

        assertEquals(0, runGuard(script, "127.0.0.1", "admin"), "loopback with the dev default must start");
        assertEquals(0, runGuard(script, "::1", "admin"), "IPv6 loopback with the dev default must start");
        assertEquals(1, runGuard(script, "0.0.0.0", "admin"), "0.0.0.0 with admin/admin must be refused");
        assertEquals(1, runGuard(script, "192.0.2.10", ""), "an empty password must be refused off loopback");
        assertEquals(0, runGuard(script, "0.0.0.0", "a-generated-secret"), "a real password may leave loopback");

        // An existing volume keeps the password it was created with: until
        // keycloak-admin-rotate has seen `admin` refused and left its marker, it stays
        // on loopback whatever the configured password says.
        Path volume = Files.createTempDirectory("keycloak-data");
        try {
            Files.createDirectory(volume.resolve("h2"));
            assertEquals(1, runGuard(script, "0.0.0.0", "a-generated-secret", volume),
                    "an existing volume without the rotation marker must be refused off loopback");
            assertEquals(0, runGuard(script, "127.0.0.1", "a-generated-secret", volume),
                    "on loopback it must start, so the rotation can run");
            Files.createFile(volume.resolve(".eddi-admin-verified"));
            assertEquals(0, runGuard(script, "0.0.0.0", "a-generated-secret", volume),
                    "once the rotation has verified the login, the volume may leave loopback");
        } finally {
            try (Stream<Path> files = Files.walk(volume)) {
                files.sorted((a, b) -> b.getNameCount() - a.getNameCount()).forEach(path -> path.toFile().delete());
            }
        }
    }

    private static boolean shellAvailable() {
        try {
            return new ProcessBuilder("sh", "-c", "exit 0").start().waitFor() == 0;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * The script goes through a file, not {@code sh -c}: Windows re-quotes a
     * command-line argument, which mangles the script's own {@code ""} patterns.
     */
    private static int runGuard(String script, String bind, String password) throws Exception {
        // A data directory that does not exist: a fresh volume.
        return runGuard(script, bind, password, Path.of("target", "no-such-keycloak-data").toAbsolutePath());
    }

    private static int runGuard(String script, String bind, String password, Path dataDir) throws Exception {
        Path file = Files.createTempFile("keycloak-guard", ".sh");
        try {
            Files.writeString(file, script, StandardCharsets.UTF_8);
            ProcessBuilder builder = new ProcessBuilder("sh", file.toAbsolutePath().toString())
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD);
            builder.environment().put("KEYCLOAK_BIND", bind);
            builder.environment().put("KC_BOOTSTRAP_ADMIN_PASSWORD", password);
            builder.environment().put("KC_DATA_DIR", dataDir.toString().replace('\\', '/'));
            return builder.start().waitFor();
        } finally {
            Files.deleteIfExists(file);
        }
    }

    /**
     * EDDI used to connect to the bundled MongoDB as the root user
     * ({@code authSource=admin}), so anything that read its connection string held
     * the whole server. Every stack that brings its own MongoDB now gives EDDI a
     * user defined in {@code eddi} and lets a one-shot {@code mongodb-app-user}
     * service — which runs on every {@code up}, so existing volumes migrate too —
     * hold the root credentials instead. The mongodb healthcheck pings without
     * credentials ({@code ping} needs none).
     */
    @Test
    @DisplayName("EDDI connects to a bundled MongoDB as its own user, never as root")
    void eddiDoesNotConnectAsMongoRoot() {
        List<String> offenders = new ArrayList<>();
        int stacks = 0;
        for (Path file : composeFiles()) {
            TreeMap<String, JsonNode> services = services(file);
            if (!services.containsKey("mongodb") || !services.containsKey("eddi")) {
                continue;
            }
            stacks++;
            String connection = environmentValue(services.get("eddi"), "MONGODB_CONNECTIONSTRING");
            // The password may FALL BACK to the root password (an existing .env keeps
            // working); the user must be EDDI's own, defined in `eddi`.
            if (connection == null || !connection.contains("authSource=eddi")
                    || !connection.matches("mongodb://(\\$\\{MONGO_APP_USERNAME:-eddi_app\\}|eddi_app):.*")) {
                offenders.add(name(file) + ": eddi's MONGODB_CONNECTIONSTRING is `" + connection + "`");
            }
            JsonNode helper = services.get("mongodb-app-user");
            if (helper == null || !helper.path("command").toString().contains("createUser")) {
                offenders.add(name(file) + ": declares no mongodb-app-user that creates EDDI's user");
            }
            if (!"service_completed_successfully".equals(
                    services.get("eddi").path("depends_on").path("mongodb-app-user").path("condition").asText())) {
                offenders.add(name(file) + ": eddi does not wait for mongodb-app-user to complete");
            }
            if (services.get("mongodb").path("healthcheck").path("test").toString().contains("\"-p\"")) {
                offenders.add(name(file) + ": the mongodb healthcheck carries a password; ping needs none");
            }
        }
        assertTrue(stacks >= 3, "expected the base, Open WebUI and cluster stacks to bring a MongoDB; found " + stacks);
        assertEquals(List.of(), offenders);
    }

    /** One {@code environment} entry, in either the map or the list form. */
    private static String environmentValue(JsonNode service, String variable) {
        JsonNode environment = service.path("environment");
        if (environment.isObject()) {
            return environment.has(variable) ? environment.get(variable).asText() : null;
        }
        for (JsonNode item : environment) {
            if (item.asText().startsWith(variable + "=")) {
                return item.asText().substring(variable.length() + 1);
            }
        }
        return null;
    }

    /**
     * One MongoDB version across everything a user runs and everything that gates a
     * release. The release smoke test used to start {@code mongo:6.0} (EOL July
     * 2025) while the documented stack pins 7.0.14, which left a 7.x-specific
     * regression with no gate able to catch it — the gate was exercising a
     * different database from the one it was gating.
     * <p>
     * Scope is deliberately the deployment and release surfaces: compose files and
     * the workflows. The Testcontainers pins in {@code src/test} are fixtures for
     * store-level unit tests, not something a user runs or a release depends on.
     */
    @Test
    @DisplayName("every MongoDB pin a user or a release gate touches is the same version")
    void everyMongoDbPinIsTheSameVersion() {
        TreeMap<String, TreeSet<String>> byVersion = new TreeMap<>();

        List<Path> sources = new ArrayList<>(composeFiles());
        try (Stream<Path> workflows = Files.list(WORKFLOWS)) {
            workflows.filter(path -> path.getFileName().toString().endsWith(".yml")).sorted().forEach(sources::add);
        } catch (IOException e) {
            throw new UncheckedIOException("could not list " + WORKFLOWS.toAbsolutePath(), e);
        }

        for (Path file : sources) {
            Matcher matcher = MONGO_IMAGE.matcher(withoutComments(read(file)));
            while (matcher.find()) {
                byVersion.computeIfAbsent(matcher.group(1), version -> new TreeSet<>()).add(name(file));
            }
        }

        assertFalse(byVersion.isEmpty(), "no mongo:<tag> reference found at all — the sweep would be vacuous");
        assertEquals(1, byVersion.size(),
                "MongoDB is pinned to more than one version across the compose files and the workflows: " + byVersion
                        + ". The release smoke test and the stack users actually run have to be the same database,"
                        + " or the gate cannot catch a version-specific regression.");
    }

    // ─────────────────────────────────────────────────────────────
    // Documented combinations (security review 2026-10-17, M6)
    // ─────────────────────────────────────────────────────────────

    /** The two base stacks the installers choose between ({@code --db}). */
    private static final List<String> INSTALLER_BASES = List.of("docker-compose.yml", "docker-compose.postgres-only.yml");

    /**
     * The overlays the installers layer on a base, in the order they add them
     * ({@code --with-auth}, then {@code --with-monitoring}).
     */
    private static final List<String> INSTALLER_OVERLAYS = List.of("docker-compose.auth.yml", "docker-compose.monitoring.yml");

    /**
     * {@code --local}, which both installers put FIRST, before the base — so the
     * repository becomes the project directory.
     */
    private static final String LOCAL_OVERLAY = "docker-compose.local.yml";

    /**
     * Combinations the documentation teaches that the installers never assemble.
     * Each is a whole command a user copies;
     * {@link #documentedCommandsAreKnownCombinations()} keeps this list honest
     * against README.md and {@code docs/}.
     */
    private static final List<List<String>> DOCUMENTED_EXTRA_COMBINATIONS = List.of(
            List.of("docker-compose.yml", "docker-compose.local.yml"),
            List.of("docker-compose.yml", "docker-compose.nats.yml"),
            List.of("docker-compose.yml", "docker-compose.ollama.yml"),
            List.of("docker-compose.yml", "docker-compose.ollama.yml", "docker-compose.ollama-nvidia.yml"),
            List.of("docker-compose.yml", "docker-compose.chroma.yml"),
            List.of("docker-compose.yml", "docker-compose.mcp-sidecar.yml"),
            List.of("docker-compose.cluster.yml"),
            List.of("docker-compose.openwebui.yml"));

    /**
     * A {@code docker compose [-p name] -f a.yml [-f b.yml …]} command in prose.
     * Only bare file names count — a path such as {@code ui/manager/…} belongs to
     * another directory's compose set.
     */
    private static final Pattern DOCUMENTED_COMMAND = Pattern
            .compile("docker compose(?: -p [\\w-]+)?((?: -f [\\w.\\-]+\\.yml)+)(?![\\w./-])");

    /**
     * Placeholder values for the variables some stacks require with {@code :?}, so
     * {@code docker compose config} renders instead of stopping at the first one.
     */
    private static final Map<String, String> REQUIRED_VARIABLES = Map.of(
            "POSTGRES_PASSWORD", "placeholder",
            "MONGO_PASSWORD", "placeholder",
            "NATS_PASSWORD", "placeholder",
            "EDDI_VAULT_MASTER_KEY", "placeholder-placeholder");

    /**
     * Every combination a user can end up running: everything either installer can
     * assemble (base × {@code --local} × {@code --with-auth} ×
     * {@code --with-monitoring}) plus {@link #DOCUMENTED_EXTRA_COMBINATIONS}.
     */
    static List<List<String>> documentedCombinations() {
        List<List<String>> combinations = new ArrayList<>();
        for (String base : INSTALLER_BASES) {
            for (int local = 0; local < 2; local++) {
                for (int mask = 0; mask < (1 << INSTALLER_OVERLAYS.size()); mask++) {
                    List<String> files = new ArrayList<>();
                    if (local == 1) {
                        files.add(LOCAL_OVERLAY);
                    }
                    files.add(base);
                    for (int i = 0; i < INSTALLER_OVERLAYS.size(); i++) {
                        if ((mask & (1 << i)) != 0) {
                            files.add(INSTALLER_OVERLAYS.get(i));
                        }
                    }
                    combinations.add(List.copyOf(files));
                }
            }
        }
        combinations.addAll(DOCUMENTED_EXTRA_COMBINATIONS);
        return combinations;
    }

    /**
     * The defect that made the authenticated PostgreSQL install unstartable:
     * {@code docker-compose.auth.yml} overrides {@code bind-guard}, which only
     * {@code docker-compose.yml} declared, so
     * {@code -f docker-compose.postgres-only.yml -f docker-compose.auth.yml} — what
     * {@code install.sh --db=postgres --with-auth} composes — failed with "service
     * bind-guard has neither an image nor a build context".
     * {@link #everyServiceResolvesToAnImageOrTheBase()} could not see it: it checks
     * each overlay against {@code docker-compose.yml} alone. This checks every
     * overlay against every base it is actually layered on.
     */
    @Test
    @DisplayName("every documented combination resolves every service and dependency")
    void documentedCombinationsResolveStructurally() {
        List<String> offenders = new ArrayList<>();
        for (List<String> combination : documentedCombinations()) {
            TreeMap<String, JsonNode> withImage = new TreeMap<>();
            List<Map.Entry<String, JsonNode>> all = new ArrayList<>();
            for (String file : combination) {
                Path path = Path.of(file);
                assertTrue(Files.isRegularFile(path), combination + " names " + file + ", which does not exist");
                for (var entry : services(path).entrySet()) {
                    all.add(entry);
                    if (entry.getValue().has("image") || entry.getValue().has("build")) {
                        withImage.put(entry.getKey(), entry.getValue());
                    }
                }
            }
            for (var entry : all) {
                if (!withImage.containsKey(entry.getKey())) {
                    offenders.add(combination + ": `" + entry.getKey() + "` has neither an image nor a build context");
                }
                JsonNode dependsOn = entry.getValue().path("depends_on");
                List<String> targets = new ArrayList<>();
                if (dependsOn.isArray()) {
                    dependsOn.forEach(target -> targets.add(target.asText()));
                } else {
                    dependsOn.fieldNames().forEachRemaining(targets::add);
                }
                for (String target : targets) {
                    if (!withImage.containsKey(target)) {
                        offenders.add(combination + ": `" + entry.getKey() + "` depends on `" + target
                                + "`, which no file of the combination declares");
                    }
                }
            }
        }
        assertEquals(List.of(), offenders,
                "a documented compose combination does not resolve — `docker compose up` refuses it outright. An"
                        + " overlay may only override a service every base it is layered on declares.");
    }

    /**
     * The installers' file choices, read out of the scripts, are exactly what
     * {@link #documentedCombinations()} enumerates — so a new overlay added to an
     * installer enters the matrix (and the render below) rather than shipping
     * untested.
     */
    @Test
    @DisplayName("the combination matrix covers every file the installers select")
    void installerSelectionsAreInTheMatrix() {
        Pattern shell = Pattern.compile("needed_files\\+=\\(\"(docker-compose[\\w.\\-]*\\.yml)\"\\)");
        Pattern powerShell = Pattern.compile("\\$neededFiles \\+= \"(docker-compose[\\w.\\-]*\\.yml)\"");
        TreeSet<String> expected = new TreeSet<>(INSTALLER_BASES);
        expected.addAll(INSTALLER_OVERLAYS);
        for (var script : List.of(Map.entry(Path.of("install.sh"), shell),
                Map.entry(Path.of("install.ps1"), powerShell))) {
            TreeSet<String> selected = new TreeSet<>();
            Matcher matcher = script.getValue().matcher(read(script.getKey()));
            while (matcher.find()) {
                selected.add(matcher.group(1));
            }
            assertEquals(expected, selected, script.getKey() + " selects compose files the combination matrix does not"
                    + " enumerate (or no longer selects one it does) — update INSTALLER_BASES / INSTALLER_OVERLAYS");
            assertTrue(read(script.getKey()).contains(LOCAL_OVERLAY), script.getKey() + " no longer mentions "
                    + LOCAL_OVERLAY + " — update the matrix");
        }
    }

    /**
     * Every multi-file command README.md and {@code docs/} print is one of the
     * known combinations, so the render below covers what users actually copy.
     */
    @Test
    @DisplayName("every compose command the documentation prints is a known combination")
    void documentedCommandsAreKnownCombinations() {
        Set<Set<String>> known = documentedCombinations().stream().map(Set::copyOf).collect(Collectors.toSet());
        List<Path> pages = new ArrayList<>(List.of(README));
        try (Stream<Path> docs = Files.walk(Path.of("docs"))) {
            docs.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".md"))
                    .filter(ComposeStackTest::countsAsDocumentation)
                    .sorted()
                    .forEach(pages::add);
        } catch (IOException e) {
            throw new UncheckedIOException("could not walk docs/", e);
        }
        TreeSet<String> unknown = new TreeSet<>();
        int seen = 0;
        for (Path page : pages) {
            Matcher matcher = DOCUMENTED_COMMAND.matcher(read(page));
            while (matcher.find()) {
                List<String> files = new ArrayList<>();
                for (String token : matcher.group(1).trim().split("\\s+")) {
                    if (!token.equals("-f")) {
                        files.add(token);
                    }
                }
                if (!files.stream().allMatch(file -> Files.isRegularFile(Path.of(file)))) {
                    continue; // another directory's compose set (ui/manager), or a deleted file other tests
                              // report
                }
                seen++;
                if (!known.contains(Set.copyOf(files))) {
                    unknown.add(page + ": " + matcher.group());
                }
            }
        }
        assertTrue(seen > 5, "found almost no compose commands in the documentation — the sweep would be vacuous");
        assertEquals(Set.of(), unknown, "the documentation prints compose combinations the matrix does not know, so"
                + " nothing renders them. Add them to DOCUMENTED_EXTRA_COMBINATIONS (and make them work).");
    }

    /**
     * The real check, where a Docker CLI is available (CI, most developer
     * machines): {@code docker compose config} parses and merges exactly as
     * {@code up} does, without starting anything or contacting a registry. Skipped
     * — not passed — where there is no Docker; the structural check above still
     * runs there.
     */
    @Test
    @DisplayName("every documented combination renders with `docker compose config`")
    void documentedCombinationsRenderWithDockerCompose() throws Exception {
        assumeTrue(dockerComposeAvailable(), "no `docker compose` on this machine");
        List<String> failures = new ArrayList<>();
        for (List<String> combination : documentedCombinations()) {
            List<String> command = new ArrayList<>(List.of("docker", "compose"));
            for (String file : combination) {
                command.add("-f");
                command.add(file);
            }
            command.addAll(List.of("config", "-q"));
            ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
            builder.environment().putAll(REQUIRED_VARIABLES);
            // The output goes to a file, not a pipe read before waitFor: readAllBytes()
            // blocks until the CLI exits, so a hung `docker compose` would hang the test
            // and the timeout below could never fire.
            Path output = Files.createTempFile("compose-config", ".log");
            try {
                builder.redirectOutput(output.toFile());
                Process process = builder.start();
                if (!process.waitFor(60, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    failures.add(combination + ": timed out");
                } else if (process.exitValue() != 0) {
                    failures.add(combination + ": " + Files.readString(output, StandardCharsets.UTF_8).strip());
                }
            } finally {
                Files.deleteIfExists(output);
            }
        }
        assertEquals(List.of(), failures, "a documented compose combination does not render");
    }

    private static boolean dockerComposeAvailable() {
        try {
            Process process = new ProcessBuilder("docker", "compose", "version").redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            if (!process.waitFor(30, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return false;
            }
            return process.exitValue() == 0;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * {@code docker-compose.postgres-only.yml} and
     * {@code docker-compose.cluster.yml} publish on {@code ${EDDI_BIND:-127.0.0.1}}
     * with authentication off by default, and had no {@code bind-guard}:
     * {@code EDDI_BIND=0.0.0.0} there published an open EDDI, MCP server and
     * secrets vault, which {@code docker-compose.yml} refuses. Every whole stack
     * that publishes on {@code EDDI_BIND} carries the same guard — the same script
     * and the same inputs, byte for byte, so a fix to one copy cannot miss another
     * — and the publishing service waits for it.
     */
    @Test
    @DisplayName("every stack that publishes on EDDI_BIND carries the same bind-guard")
    void everyStackThatPublishesOnEddiBindCarriesTheBindGuard() {
        JsonNode reference = services(BASE).get("bind-guard");
        assertNotNull(reference, BASE + " must declare the reference bind-guard");
        List<String> offenders = new ArrayList<>();
        int guarded = 0;
        for (Path file : composeFiles()) {
            if (!STANDALONE_STACKS.contains(name(file))) {
                continue;
            }
            TreeMap<String, JsonNode> services = services(file);
            for (var entry : services.entrySet()) {
                boolean publishesOnEddiBind = false;
                for (JsonNode port : entry.getValue().path("ports")) {
                    publishesOnEddiBind |= port.toString().contains("${EDDI_BIND");
                }
                if (!publishesOnEddiBind) {
                    continue;
                }
                guarded++;
                JsonNode guard = services.get("bind-guard");
                if (guard == null) {
                    offenders.add(name(file) + " publishes `" + entry.getKey() + "` on EDDI_BIND without a bind-guard");
                    continue;
                }
                for (String key : List.of("entrypoint", "command", "environment")) {
                    if (!reference.path(key).equals(guard.path(key))) {
                        offenders.add(name(file) + "'s bind-guard `" + key + "` differs from " + BASE + "'s");
                    }
                }
                String image = guard.path("image").asText();
                boolean reusesAnImage = services.entrySet().stream()
                        .anyMatch(other -> !other.getKey().equals("bind-guard")
                                && image.equals(other.getValue().path("image").asText()));
                if (!reusesAnImage) {
                    offenders.add(name(file) + "'s bind-guard runs on `" + image
                            + "`, which no other service of the stack uses — an extra pull for a one-line check");
                }
                String condition = entry.getValue().path("depends_on").path("bind-guard").path("condition").asText();
                if (!"service_completed_successfully".equals(condition)) {
                    offenders.add(name(file) + ": `" + entry.getKey()
                            + "` must depend on bind-guard with condition service_completed_successfully");
                }
            }
        }
        assertTrue(guarded >= 3, "expected docker-compose.yml, docker-compose.postgres-only.yml and"
                + " docker-compose.cluster.yml to publish on EDDI_BIND — the sweep found " + guarded);
        assertEquals(List.of(), offenders,
                "a stack publishes EDDI on EDDI_BIND without the guard that refuses a non-loopback bind while"
                        + " authentication is off");
    }
}
