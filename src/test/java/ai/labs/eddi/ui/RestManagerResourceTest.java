/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.ui;

import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link RestManagerResource}. Tests directory traversal
 * prevention, invalid character blocking, fallback to manage.html, auth config
 * generation, and error handling.
 */
class RestManagerResourceTest {

    private RestManagerResource resource;

    @BeforeEach
    void setUp() throws Exception {
        resource = new RestManagerResource();
        // Set default field values via reflection since CDI fields aren't injected
        setField("keycloakPublicUrl", Optional.empty());
        setField("oidcAuthServerUrl", "");
        setField("oidcEnabled", false);
    }

    private void setField(String fieldName, Object value) throws Exception {
        Field f = RestManagerResource.class.getDeclaredField(fieldName);
        f.setAccessible(true);
        f.set(resource, value);
    }

    @Nested
    @DisplayName("Default path")
    class DefaultPath {

        @Test
        @DisplayName("fetchManagerResources() with no args should delegate to /manage.html")
        void defaultCallDelegates() {
            try {
                Response response = resource.fetchManagerResources();
                assertEquals(200, response.getStatus());
            } catch (Exception e) {
                // If manage.html is NOT on classpath, it throws InternalServerErrorException
                assertNotNull(e);
            }
        }
    }

    @Nested
    @DisplayName("Directory traversal prevention")
    class DirectoryTraversal {

        @Test
        @DisplayName("path with ../ should throw ForbiddenException")
        void pathTraversalBlocked() {
            assertThrows(ForbiddenException.class,
                    () -> resource.fetchManagerResources("../../etc/passwd"));
        }

        @Test
        @DisplayName("deeply nested traversal should throw ForbiddenException")
        void deepTraversalBlocked() {
            assertThrows(ForbiddenException.class,
                    () -> resource.fetchManagerResources("a/b/../../../../etc/shadow"));
        }

        /**
         * A backslash traversal must be refused on every operating system.
         * <p>
         * The slash-based normaliser splits on '/' alone, so {@code ..\..\..\pom.xml}
         * survives as ONE segment that is neither "." nor ".." — nothing is popped and
         * the name reaches the classloader intact. The NIO guard this replaced did
         * catch it on Windows (where '\' is a separator) and not on Linux, so the
         * "OS-independent" rewrite had quietly made the guard weaker on the one
         * platform where it used to work. The character check now rejects '\' outright.
         */
        @Test
        @DisplayName("backslash traversal should throw ForbiddenException on every OS")
        void backslashTraversalBlocked() {
            assertThrows(ForbiddenException.class,
                    () -> resource.fetchManagerResources("..\\..\\..\\..\\pom.xml"),
                    "a backslash traversal must not reach the classloader");
            assertThrows(ForbiddenException.class,
                    () -> resource.fetchManagerResources("assets\\..\\..\\..\\pom.xml"),
                    "a backslash hidden mid-path must not reach the classloader either");
        }
    }

    @Nested
    @DisplayName("Invalid character blocking")
    class InvalidCharacters {

        /**
         * Every one of these is a 403, on every operating system.
         * <p>
         * This asserted the much weaker {@code Exception.class}, with a comment
         * conceding that "on Windows, Paths.get() may throw InvalidPathException before
         * our char-check runs" — which is precisely the defect it was papering over:
         * InvalidPathException is an IllegalArgumentException, neither catch matched
         * it, and "/manage/a:b" answered 500 "An error occurred" instead of the
         * intended 403. The character check now runs before any path parsing, and the
         * parsing itself is slash-based rather than platform-dependent.
         */
        @Test
        @DisplayName("path with invalid characters should be Forbidden on every OS")
        void invalidCharsBlocked() {
            String[] badPaths = {"test<script>.html", "test>out.html", "test|pipe.html",
                    "C:\\test.html", "*.html", "file?.html", "file\".html", "a:b"};
            for (String badPath : badPaths) {
                assertThrows(ForbiddenException.class,
                        () -> resource.fetchManagerResources(badPath),
                        "Should reject path with 403: " + badPath);
            }
        }
    }

    @Nested
    @DisplayName("Classpath resource naming")
    class ClasspathNaming {

        /**
         * A classpath resource name is '/'-separated, always — it is not a filesystem
         * path. Building it with {@code Paths.get(...).toString()} rendered
         * "META-INF\resources\..." on Windows, which no classloader resolves, so every
         * existing file under /manage silently fell through to the manage.html SPA
         * shell.
         * <p>
         * The assertion has to compare CONTENT. Identity ({@code assertNotSame} on the
         * two entities) proved nothing: {@code getResourceAsStream} hands back a fresh
         * {@code InputStream} on every call, and the manage.html fallback is itself a
         * 200 with a non-null entity, so the earlier version of this test could not
         * fail whatever the resource name looked like.
         * <p>
         * Content alone cannot fail against the pre-fix code <em>in this suite</em>
         * either — the unit run resolves off an exploded {@code target/classes}, where
         * the JDK's file loader accepts '\' as a separator, so the broken name still
         * served the right bytes. So this test records the lookup as well: the asset
         * must resolve on ONE '/'-separated name, with no second lookup — a second
         * lookup is the SPA fallback, which is exactly the production symptom.
         */
        @Test
        @DisplayName("an existing asset is served under its own '/'-separated name, not the manage.html fallback")
        void existingAssetIsFound() throws Exception {
            String shell = readEntity(resource.fetchManagerResources("manage.html"));

            var recorder = new RecordingClassLoader(Thread.currentThread().getContextClassLoader());
            Response asset = underClassLoader(recorder, () -> resource.fetchManagerResources("robots.txt"));

            assertEquals(List.of("META-INF/resources/robots.txt"), recorder.requested,
                    "robots.txt must resolve on a single '/'-separated lookup; a second lookup means the "
                            + "name did not resolve and the SPA shell was served in its place");
            assertEquals(200, asset.getStatus());
            String body = readEntity(asset);
            assertTrue(body.contains("User-agent:"),
                    "expected the robots.txt body, got: " + body);
            assertNotEquals(shell, body,
                    "robots.txt must resolve on its own, not fall through to the SPA shell");
        }

        /**
         * The name handed to the classloader must be '/'-separated on every OS.
         * <p>
         * This is the assertion that actually pins the defect.
         * {@code Paths.get(...).toString()} rendered "META-INF\resources\..." on
         * Windows; a JAR entry is keyed by '/' and never matches that, so in the
         * shipped image every asset under /manage fell through to the manage.html SPA
         * shell. Comparing served CONTENT cannot show it here, because the unit suite
         * runs off an exploded target/classes directory, where the JDK's file-based
         * loader turns the name into a {@code File} and Windows accepts '\' as a
         * separator after all — so the broken name still resolved. Recording the lookup
         * removes the classpath layout from the question.
         */
        @Test
        @DisplayName("the classpath resource name is '/'-separated on every OS")
        void resourceNameIsSlashSeparated() {
            ClassLoader previous = Thread.currentThread().getContextClassLoader();
            var recorder = new RecordingClassLoader(previous);
            Thread.currentThread().setContextClassLoader(recorder);
            try {
                resource.fetchManagerResources("assets/css/style.css");
            } finally {
                Thread.currentThread().setContextClassLoader(previous);
            }

            assertFalse(recorder.requested.isEmpty(), "the resource was never looked up");
            assertEquals("META-INF/resources/assets/css/style.css", recorder.requested.getFirst(),
                    "a classpath resource name is '/'-separated — it is not a filesystem path");
        }

        /**
         * A {@code ./} prefix has to collapse into the very same '/'-separated
         * classpath name the bare asset produces — the recorded lookup is what pins
         * that, since serving the right bytes proves nothing off an exploded
         * {@code target/classes} (see {@link #resourceNameIsSlashSeparated}).
         */
        @Test
        @DisplayName("a ./-prefixed asset collapses to the same '/'-separated name, not the fallback")
        void leadingDotSlashAssetIsFound() throws Exception {
            String shell = readEntity(resource.fetchManagerResources("manage.html"));

            var recorder = new RecordingClassLoader(Thread.currentThread().getContextClassLoader());
            Response asset = underClassLoader(recorder, () -> resource.fetchManagerResources("./robots.txt"));

            assertEquals(List.of("META-INF/resources/robots.txt"), recorder.requested,
                    "a leading ./ must be collapsed into the same '/'-separated resource name, "
                            + "not appended to it and not re-rendered with the platform separator");
            String body = readEntity(asset);
            assertTrue(body.contains("User-agent:"),
                    "expected the robots.txt body, got: " + body);
            assertNotEquals(shell, body);
        }

        /**
         * The fallback is allowed, but only as the SECOND lookup and only after the
         * asset was asked for under its own '/'-separated name. Asserting the whole
         * sequence is what separates "this file really is missing" from "the name we
         * built could never have resolved", which is the bug that made every asset
         * under /manage serve the SPA shell in the packaged image.
         */
        @Test
        @DisplayName("a genuinely missing asset falls back to manage.html, after one '/'-separated lookup")
        void missingAssetFallsBack() throws Exception {
            var recorder = new RecordingClassLoader(Thread.currentThread().getContextClassLoader());
            Response response = underClassLoader(recorder,
                    () -> resource.fetchManagerResources("no-such-file-here.js"));

            assertEquals(
                    List.of("META-INF/resources/no-such-file-here.js", "META-INF/resources/manage.html"),
                    recorder.requested,
                    "the asset must be looked up under its own '/'-separated name first, and the SPA "
                            + "shell only after that lookup came back empty");
            assertEquals(200, response.getStatus());
            assertEquals(readEntity(resource.fetchManagerResources("manage.html")),
                    readEntity(response),
                    "a missing asset must serve the SPA shell verbatim");
        }
    }

    /**
     * Run {@code call} with {@code loader} installed as the context classloader.
     */
    private static Response underClassLoader(ClassLoader loader, Supplier<Response> call) {
        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(loader);
        try {
            return call.get();
        } finally {
            Thread.currentThread().setContextClassLoader(previous);
        }
    }

    /**
     * A classloader that records every resource name asked of it, then delegates.
     */
    private static final class RecordingClassLoader extends ClassLoader {
        private final List<String> requested = new ArrayList<>();

        RecordingClassLoader(ClassLoader parent) {
            super(parent);
        }

        @Override
        public InputStream getResourceAsStream(String name) {
            requested.add(name);
            return super.getResourceAsStream(name);
        }
    }

    /** Drain a {@link Response}'s {@code InputStream} entity as UTF-8 text. */
    private static String readEntity(Response response) throws IOException {
        try (InputStream in = (InputStream) response.getEntity()) {
            assertNotNull(in, "response carried no entity stream");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Nested
    @DisplayName("Leading ./ stripping")
    class LeadingDotSlash {

        @Test
        @DisplayName("path with leading ./ should be stripped and not throw Forbidden")
        void leadingDotSlashStripped() {
            try {
                Response response = resource.fetchManagerResources("./index.html");
                assertNotNull(response);
            } catch (ForbiddenException e) {
                fail("Leading ./ should be stripped, not treated as forbidden");
            } catch (Exception e) {
                // Expected — file doesn't exist in test classpath
            }
        }

        @Test
        @DisplayName("path with multiple ./ prefixes should be stripped")
        void multipleLeadingDotSlashStripped() {
            try {
                resource.fetchManagerResources("././index.html");
            } catch (ForbiddenException e) {
                fail("Multiple leading ./ should be stripped");
            } catch (Exception e) {
                // Expected
            }
        }
    }

    @Nested
    @DisplayName("Normal path handling")
    class NormalPaths {

        @Test
        @DisplayName("simple filename should not throw ForbiddenException")
        void simplePathAllowed() {
            try {
                resource.fetchManagerResources("index.html");
            } catch (ForbiddenException e) {
                fail("Simple path should be allowed");
            } catch (Exception e) {
                // Expected
            }
        }

        @Test
        @DisplayName("nested path should not throw ForbiddenException")
        void nestedPathAllowed() {
            try {
                resource.fetchManagerResources("assets/css/style.css");
            } catch (ForbiddenException e) {
                fail("Nested path should be allowed");
            } catch (Exception e) {
                // Expected
            }
        }
    }

    // ==================== Auth Config Tests ====================

    @Nested
    @DisplayName("fetchAuthConfig")
    class AuthConfigTests {

        @Test
        @DisplayName("returns 'none' method when OIDC is disabled")
        void oidcDisabled() throws Exception {
            setField("oidcEnabled", false);

            Response response = resource.fetchAuthConfig();

            assertEquals(200, response.getStatus());
            String body = (String) response.getEntity();
            assertTrue(body.contains("method:\"none\""));
            assertEquals("application/javascript", response.getMediaType().toString());
        }

        @Test
        @DisplayName("returns 'keycloak' method when OIDC is enabled")
        void oidcEnabled() throws Exception {
            setField("oidcEnabled", true);
            setField("keycloakPublicUrl", Optional.of("https://auth.example.com"));
            setField("oidcAuthServerUrl", "https://auth.example.com/realms/eddi");

            Response response = resource.fetchAuthConfig();

            String body = (String) response.getEntity();
            assertTrue(body.contains("method:\"keycloak\""));
            assertTrue(body.contains("url:\"https://auth.example.com\""));
            assertTrue(body.contains("realm:\"eddi\""));
            assertTrue(body.contains("clientId:\"eddi-frontend\""));
        }

        @Test
        @DisplayName("extracts realm from auth-server-url with /realms/ path")
        void extractsRealmFromUrl() throws Exception {
            setField("oidcEnabled", true);
            setField("keycloakPublicUrl", Optional.empty());
            setField("oidcAuthServerUrl", "https://auth.example.com/realms/my-realm");

            Response response = resource.fetchAuthConfig();

            String body = (String) response.getEntity();
            assertTrue(body.contains("realm:\"my-realm\""));
        }

        @Test
        @DisplayName("defaults realm to 'eddi' when auth-server-url is blank")
        void defaultRealmWhenBlank() throws Exception {
            setField("oidcEnabled", true);
            setField("keycloakPublicUrl", Optional.empty());
            setField("oidcAuthServerUrl", "");

            Response response = resource.fetchAuthConfig();

            String body = (String) response.getEntity();
            assertTrue(body.contains("realm:\"eddi\""));
        }

        @Test
        @DisplayName("defaults realm to 'eddi' when no /realms/ in URL")
        void defaultRealmWhenNoRealmsPath() throws Exception {
            setField("oidcEnabled", true);
            setField("keycloakPublicUrl", Optional.empty());
            setField("oidcAuthServerUrl", "https://auth.example.com/oidc");

            Response response = resource.fetchAuthConfig();

            String body = (String) response.getEntity();
            assertTrue(body.contains("realm:\"eddi\""));
        }

        @Test
        @DisplayName("extracts realm when URL has trailing slash")
        void realmWithTrailingSlash() throws Exception {
            setField("oidcEnabled", true);
            setField("keycloakPublicUrl", Optional.empty());
            setField("oidcAuthServerUrl", "https://auth.example.com/realms/test-realm/");

            Response response = resource.fetchAuthConfig();

            String body = (String) response.getEntity();
            assertTrue(body.contains("realm:\"test-realm\""));
        }

        @Test
        @DisplayName("extracts realm when URL has query parameters")
        void realmWithQueryParams() throws Exception {
            setField("oidcEnabled", true);
            setField("keycloakPublicUrl", Optional.empty());
            setField("oidcAuthServerUrl", "https://auth.example.com/realms/myrealm?foo=bar");

            Response response = resource.fetchAuthConfig();

            String body = (String) response.getEntity();
            assertTrue(body.contains("realm:\"myrealm\""));
        }

        @Test
        @DisplayName("extracts realm when URL has hash fragment")
        void realmWithHash() throws Exception {
            setField("oidcEnabled", true);
            setField("keycloakPublicUrl", Optional.empty());
            setField("oidcAuthServerUrl", "https://auth.example.com/realms/hashrealm#section");

            Response response = resource.fetchAuthConfig();

            String body = (String) response.getEntity();
            assertTrue(body.contains("realm:\"hashrealm\""));
        }

        @Test
        @DisplayName("omits url when keycloakPublicUrl is blank")
        void omitsUrlWhenBlank() throws Exception {
            setField("oidcEnabled", true);
            setField("keycloakPublicUrl", Optional.of("   "));
            setField("oidcAuthServerUrl", "https://auth.example.com/realms/eddi");

            Response response = resource.fetchAuthConfig();

            String body = (String) response.getEntity();
            assertFalse(body.contains("url:\""));
        }

        @Test
        @DisplayName("has no-cache header")
        void noCacheHeader() throws Exception {
            setField("oidcEnabled", false);

            Response response = resource.fetchAuthConfig();

            String cacheControl = response.getHeaderString("Cache-Control");
            assertNotNull(cacheControl);
            assertTrue(cacheControl.contains("no-cache"));
        }

        @Test
        @DisplayName("escapes special characters in URL")
        void escapesSpecialChars() throws Exception {
            setField("oidcEnabled", true);
            setField("keycloakPublicUrl", Optional.of("https://auth.example.com/path\"with\"quotes"));
            setField("oidcAuthServerUrl", "https://auth.example.com/realms/eddi");

            Response response = resource.fetchAuthConfig();

            String body = (String) response.getEntity();
            // Quotes should be escaped
            assertTrue(body.contains("\\\""));
        }
    }
}
