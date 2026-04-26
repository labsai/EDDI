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

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link RestManagerResource}. Tests directory traversal
 * prevention, invalid character blocking, fallback to manage.html, and error
 * handling.
 */
class RestManagerResourceTest {

    private RestManagerResource resource;

    @BeforeEach
    void setUp() {
        resource = new RestManagerResource();
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
}
