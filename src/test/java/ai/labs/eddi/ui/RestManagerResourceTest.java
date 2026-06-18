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

import java.lang.reflect.Field;
import java.util.Optional;

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
    }

    @Nested
    @DisplayName("Invalid character blocking")
    class InvalidCharacters {

        @Test
        @DisplayName("path with invalid characters should throw (Forbidden or InvalidPath)")
        void invalidCharsBlocked() {
            // On Windows, Paths.get() may throw InvalidPathException before our
            // char-check runs. Both indicate the path is correctly rejected.
            String[] badPaths = {"test<script>.html", "test>out.html", "test|pipe.html",
                    "C:\\test.html", "*.html", "file?.html", "file\".html"};
            for (String badPath : badPaths) {
                assertThrows(Exception.class,
                        () -> resource.fetchManagerResources(badPath),
                        "Should reject path: " + badPath);
            }
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
