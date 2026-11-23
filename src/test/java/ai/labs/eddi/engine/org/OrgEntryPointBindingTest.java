/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.org;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Build guard (plan §14.2): every REST resource and every MCP tool class says
 * which plane it belongs to, so a new entry point cannot run without an org
 * binding by accident.
 * <ul>
 * <li>Org-plane classes carry {@link OrgBound}: {@link OrgBoundInterceptor}
 * binds the request's org around every business method.</li>
 * <li>Platform-plane classes carry {@link PlatformPlane} with a reason, and the
 * list of them is pinned here, so adding one is a reviewed decision.</li>
 * </ul>
 * The binding must sit on the implementation class: an interceptor binding on
 * an {@code IRest*} interface is not inherited by its implementation (plan
 * §3.2).
 * <p>
 * The plan schedules the build guards as report-only in Phase 1. This one fails
 * the build already, because every entry point is classified as of this change.
 */
class OrgEntryPointBindingTest {

    private static final Path MAIN = Path.of("src", "main", "java");
    private static final Pattern REST_IMPL = Pattern.compile("class \\w+ (extends \\w+ )?implements [^{]*\\bIRest\\w+");
    private static final Pattern CLASS_PATH = Pattern.compile("(?m)^@Path\\(");
    private static final Pattern PUBLIC_CLASS = Pattern.compile("(?m)^public (final |abstract )?class ");
    private static final Pattern MCP_TOOL = Pattern.compile("(?m)^\\s+@Tool\\(");
    private static final Pattern PLATFORM = Pattern.compile("(?m)^@PlatformPlane\\(\"([^\"]+)\"\\)\\s*$");

    /** Classes that implement an IRest* interface without being a resource. */
    private static final List<String> NOT_RESOURCES = List.of("RestInterfaceFactory");

    /**
     * The platform-plane entry points. Adding one here is a decision: the class
     * then runs without an org binding.
     */
    private static final List<String> PLATFORM_PLANE = List.of("RestBuildInfo", "RestClusterAdmin", "RestCoordinatorAdmin", "RestDocs",
            "RestHtmlChatResource", "RestManagerResource", "RestTenantQuota", "RestUpdateCheck", "RestWelcomeResource");

    private record Source(String name, String text) {
    }

    private static List<Source> sources() throws IOException {
        List<Source> all = new ArrayList<>();
        try (Stream<Path> files = Files.walk(MAIN)) {
            files.filter(f -> f.toString().endsWith(".java")).filter(f -> !f.toString().replace('\\', '/').contains("/engine/org/"))
                    .forEach(f -> {
                        try {
                            String name = f.getFileName().toString().replace(".java", "");
                            all.add(new Source(name, Files.readString(f)));
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    });
        }
        return all;
    }

    private static boolean isEntryPoint(Source s) {
        boolean rest = REST_IMPL.matcher(s.text()).find() || CLASS_PATH.matcher(s.text()).find() && PUBLIC_CLASS.matcher(s.text()).find();
        boolean mcp = s.text().contains("import io.quarkiverse.mcp.server.Tool;") && MCP_TOOL.matcher(s.text()).find();
        return (rest || mcp) && !NOT_RESOURCES.contains(s.name());
    }

    @Test
    void everyRestResourceAndMcpToolClassDeclaresItsPlane() throws IOException {
        List<String> unclassified = new ArrayList<>();
        List<String> both = new ArrayList<>();
        int entryPoints = 0;
        for (Source s : sources()) {
            if (!isEntryPoint(s)) {
                continue;
            }
            entryPoints++;
            boolean bound = s.text().contains("\n@OrgBound\n") || s.text().contains("\n@OrgBound\r\n");
            boolean platform = PLATFORM.matcher(s.text()).find();
            if (!bound && !platform) {
                unclassified.add(s.name());
            }
            if (bound && platform) {
                both.add(s.name());
            }
        }
        assertTrue(entryPoints >= 100, "the scan found only " + entryPoints + " entry points; the patterns are probably broken");
        assertTrue(unclassified.isEmpty(), "Annotate each REST resource or MCP tool class with @OrgBound (org plane) or "
                + "@PlatformPlane(\"why\") (platform plane), on the implementation class: " + unclassified);
        assertTrue(both.isEmpty(), "A class is either @OrgBound or @PlatformPlane, not both: " + both);
    }

    @Test
    void thePlatformPlaneEntryPointsArePinnedAndEachSaysWhy() throws IOException {
        Map<String, String> found = new TreeMap<>();
        for (Source s : sources()) {
            if (!isEntryPoint(s)) {
                continue;
            }
            Matcher m = PLATFORM.matcher(s.text());
            if (m.find()) {
                found.put(s.name(), m.group(1));
            }
        }
        assertEquals(PLATFORM_PLANE, List.copyOf(found.keySet()), "The platform-plane entry points changed. A new one runs without an org "
                + "binding: update PLATFORM_PLANE only after deciding that is right.");
        found.forEach((name, reason) -> assertTrue(reason.length() >= 20, name + " needs a reason that says why: '" + reason + "'"));
    }

    @Test
    void noRestInterfaceCarriesTheBinding() throws IOException {
        List<String> onInterfaces = new ArrayList<>();
        for (Source s : sources()) {
            if (s.name().startsWith("IRest") && (s.text().contains("@OrgBound") || s.text().contains("@PlatformPlane"))) {
                onInterfaces.add(s.name());
            }
        }
        assertTrue(onInterfaces.isEmpty(), "Interceptor bindings on an IRest* interface are not inherited by the implementation; "
                + "annotate the implementation class instead: " + onInterfaces);
    }
}
