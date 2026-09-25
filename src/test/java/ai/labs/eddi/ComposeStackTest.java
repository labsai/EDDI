/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
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
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
            "docker-compose.openwebui.yml");

    /**
     * A {@code mongo:<tag>} image reference. Anchored on a non-word character so
     * {@code eddi-boot-mongodb} and friends do not match.
     */
    private static final Pattern MONGO_IMAGE = Pattern.compile("(?<![\\w./-])mongo:(\\d[\\w.\\-]*)");

    /** Any {@code docker-compose*.yml} file name mentioned in prose. */
    private static final Pattern COMPOSE_REFERENCE = Pattern.compile("docker-compose[\\w.\\-]*\\.yml");

    private static final YAMLMapper YAML = new YAMLMapper();

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
}
