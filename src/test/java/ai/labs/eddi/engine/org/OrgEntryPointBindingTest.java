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
    private static final Pattern REST_IMPL = Pattern.compile("class \\w+(<[^>{]*>)?\\s+(extends [^{]*?\\s)?implements [^{]*\\bIRest\\w+");
    private static final Pattern CLASS_PATH = Pattern.compile("(?m)^@Path\\(");
    private static final Pattern TOP_LEVEL_CLASS = Pattern.compile("(?m)^(public |protected )?(final |abstract |sealed |non-sealed )*class ");
    /**
     * An MCP feature method: a tool, a resource, a resource template or a prompt.
     */
    private static final Pattern MCP_FEATURE = Pattern.compile("(?m)^\\s+@(Tool|Resource|ResourceTemplate|Prompt)\\(");
    private static final Pattern MCP_IMPORT = Pattern
            .compile("import io\\.quarkiverse\\.mcp\\.server\\.(\\*|Tool|Resource|ResourceTemplate|Prompt);");
    private static final Pattern SCHEDULED = Pattern.compile("(?m)^\\s+@(io\\.quarkus\\.scheduler\\.)?Scheduled\\b");
    private static final Pattern METHOD_PLATFORM = Pattern.compile("(?m)^\\s+@(ai\\.labs\\.eddi\\.engine\\.org\\.)?PlatformPlane\\(");
    private static final Pattern METHOD_BODY = Pattern.compile("(?m)\\{\\s*$");
    private static final Pattern MEMBER_END = Pattern.compile("(?m)[;{}]\\s*$");
    private static final Pattern PLATFORM = Pattern.compile("(?m)^@PlatformPlane\\(\"([^\"]+)\"\\)\\s*$");

    /** Classes that implement an IRest* interface without being a resource. */
    private static final List<String> NOT_RESOURCES = List.of("RestInterfaceFactory",
            // the shared delegate the IRest*Store resources call; not a bean, no @Path
            "RestVersionInfo");

    /**
     * The platform-plane entry points. Adding one here is a decision: the class
     * then runs without an org binding.
     */
    private static final List<String> PLATFORM_PLANE = List.of("McpDocResources", "McpDocTools", "McpVersionTools", "RestBuildInfo",
            "RestClusterAdmin", "RestCoordinatorAdmin", "RestDocs", "RestTenantQuota", "RestUpdateCheck");

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
        boolean rest = REST_IMPL.matcher(s.text()).find() || CLASS_PATH.matcher(s.text()).find() && TOP_LEVEL_CLASS.matcher(s.text()).find();
        boolean mcp = MCP_IMPORT.matcher(s.text()).find() && MCP_FEATURE.matcher(s.text()).find();
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
            boolean bound = isOrgBound(s);
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

    private static boolean isOrgBound(Source s) {
        return s.text().contains("\n@OrgBound\n") || s.text().contains("\n@OrgBound\r\n");
    }

    /**
     * A {@code @Scheduled} method of an {@link OrgBound} class is intercepted like
     * any other, but it runs on a scheduler thread with no request to resolve an
     * org from. It must say {@link PlatformPlane} so the interceptor lets it run
     * unbound; it binds per item itself (plan §7.4).
     */
    @Test
    void everyScheduledMethodOfAnOrgBoundClassIsPlatformPlane() throws IOException {
        List<String> unmarked = new ArrayList<>();
        int scheduled = 0;
        for (Source s : sources()) {
            if (!isOrgBound(s)) {
                continue;
            }
            Matcher m = SCHEDULED.matcher(s.text());
            while (m.find()) {
                scheduled++;
                if (!METHOD_PLATFORM.matcher(annotationsAround(s.text(), m)).find()) {
                    unmarked.add(s.name() + " at offset " + m.start());
                }
            }
        }
        assertTrue(scheduled >= 3, "the scan found only " + scheduled + " @Scheduled methods on @OrgBound classes; the pattern is probably broken");
        assertTrue(unmarked.isEmpty(), "Mark each @Scheduled method of an @OrgBound class with @PlatformPlane(\"why\"): " + unmarked);
    }

    /**
     * A method-level {@link PlatformPlane} makes {@link OrgBoundInterceptor} run
     * the method unbound. That is meant for {@code @Scheduled} jobs only; on a REST
     * or MCP method it would quietly take a request out of its org.
     */
    @Test
    void aMethodLevelPlatformPlaneSitsOnAScheduledMethodOnly() throws IOException {
        List<String> misplaced = new ArrayList<>();
        int found = 0;
        for (Source s : sources()) {
            Matcher m = METHOD_PLATFORM.matcher(s.text());
            while (m.find()) {
                found++;
                if (!SCHEDULED.matcher(annotationsAround(s.text(), m)).find()) {
                    misplaced.add(s.name() + " at offset " + m.start());
                }
            }
        }
        assertTrue(found >= 3, "the scan found only " + found + " method-level @PlatformPlane; the pattern is probably broken");
        assertTrue(misplaced.isEmpty(), "A method-level @PlatformPlane is for @Scheduled jobs only. A request method stays in its "
                + "class's plane; move a platform-plane endpoint to a @PlatformPlane class: " + misplaced);
    }

    /**
     * The annotations of the member whose annotation {@code at} matched: from the
     * end of the previous member to the start of this one's body.
     */
    private static String annotationsAround(String text, Matcher at) {
        // members end, and bodies start, with a ';', '{' or '}' that ends its line; a
        // brace inside "${config}" does not
        int from = 0;
        Matcher end = MEMBER_END.matcher(text).region(0, at.start());
        while (end.find()) {
            from = end.end();
        }
        Matcher body = METHOD_BODY.matcher(text);
        int to = body.find(at.end()) ? body.start() : text.length();
        return text.substring(from, to);
    }

    /** Code that runs outside resource methods (plan Appendix A.12). */
    private static final Pattern OUTSIDE_RESOURCE_METHODS = Pattern.compile(
            "implements[^{]*\\b(ContainerRequestFilter|ContainerResponseFilter|SecurityIdentityAugmentor|ExceptionMapper"
                    + "|ReaderInterceptor|WriterInterceptor|MessageBodyReader|MessageBodyWriter|HttpAuthenticationMechanism)\\b"
                    + "|@Server(Request|Response)Filter|@ServerExceptionMapper|@Observes\\s+(Router|Filters)\\b|@Route\\(");
    private static final Pattern BINDS_REQUEST = Pattern.compile("orgRequest\\.(runBound|callBound)\\(");
    private static final Pattern NEUTRAL = Pattern.compile("(?m)^@OrgNeutral\\(\"([^\"]+)\"\\)\\s*$");

    @Test
    void everyFilterMapperAugmentorAndRouteSaysHowItTreatsTheOrg() throws IOException {
        List<String> unclassified = new ArrayList<>();
        int found = 0;
        for (Source s : sources()) {
            if (!OUTSIDE_RESOURCE_METHODS.matcher(s.text()).find() || isEntryPoint(s)) {
                continue;
            }
            found++;
            Matcher neutral = NEUTRAL.matcher(s.text());
            boolean classified = BINDS_REQUEST.matcher(s.text()).find() || PLATFORM.matcher(s.text()).find()
                    || neutral.find() && neutral.group(1).length() >= 20;
            if (!classified) {
                unclassified.add(s.name());
            }
        }
        assertTrue(found >= 25, "the scan found only " + found + " filters, mappers and routes; the pattern is probably broken");
        assertTrue(unclassified.isEmpty(), "Code outside resource methods runs without the resource's org binding (plan §6.2). "
                + "Bind the work that touches org data with OrgRequest.runBound/callBound, or mark the class "
                + "@OrgNeutral(\"why it touches no org data\") or @PlatformPlane(\"why\"): " + unclassified);
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
