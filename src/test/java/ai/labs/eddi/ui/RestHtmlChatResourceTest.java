/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.ui;

import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link RestHtmlChatResource}.
 */
class RestHtmlChatResourceTest {

    private RestHtmlChatResource resource;

    @BeforeEach
    void setUp() {
        resource = new RestHtmlChatResource();
    }

    // chat.html ships in src/main/resources/META-INF/resources, which IS on the
    // test classpath, so these assert outright. Every assertion here used to be
    // hedged with `catch (Exception e) { assertNotNull(e); }`, which passes
    // whatever happens — and is why the resource-name defect below went unnoticed.

    @Test
    @DisplayName("viewDefault should delegate to viewHtml with root path")
    void viewDefaultDelegatesToViewHtml() {
        Response response = resource.viewDefault();

        assertEquals(200, response.getStatus());
        assertNotNull(response.getEntity(), "a 200 with a null entity is an empty page, not a served file");
    }

    /**
     * The resource name carried a leading slash. {@code getResourceAsStream}
     * delegates to the thread context classloader, and only Quarkus' own loader
     * strips a leading '/' — on a plain JDK loader it resolves to null, and the
     * missing null check then answered 200 with an empty body. The two sibling SPA
     * shells (welcome, workforce) already had both the slashless name and the null
     * check.
     */
    @Test
    @DisplayName("viewHtml serves the actual file, not an empty 200")
    void viewHtmlReturnsTheFile() {
        Response response = resource.viewHtml("/production");

        assertEquals(200, response.getStatus());
        assertNotNull(response.getEntity(), "the classpath lookup must resolve — a null entity means the name is wrong");
    }

    @Test
    @DisplayName("viewHtml with any path should always serve chat.html (SPA)")
    void viewHtmlAlwaysServesChatHtml() {
        // SPA pattern: all paths serve the same HTML
        Response r1 = resource.viewHtml("/path1");
        Response r2 = resource.viewHtml("/path2/nested");

        assertEquals(200, r1.getStatus());
        assertEquals(r1.getStatus(), r2.getStatus());
        assertNotNull(r1.getEntity());
        assertNotNull(r2.getEntity());
    }

    /**
     * The other half of the same fix: when the lookup genuinely comes back empty,
     * say so. Without the null check the resource answered 200 with a null entity —
     * a blank page and a success status, which is the hardest possible failure to
     * diagnose from the outside. This drives the branch with a classloader that
     * resolves nothing, which is exactly what the leading-slash name did on a plain
     * JDK loader.
     */
    @Test
    @DisplayName("viewHtml answers 500, not an empty 200, when the shell cannot be resolved")
    void viewHtmlUnresolvableResourceIsAnError() {
        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(new ClassLoader(previous) {
            @Override
            public InputStream getResourceAsStream(String name) {
                return name.endsWith("chat.html") ? null : super.getResourceAsStream(name);
            }
        });
        try {
            Response response = resource.viewHtml("/production");

            assertEquals(500, response.getStatus(),
                    "an unresolvable SPA shell must surface as an error, not as a blank successful page");
            assertNull(response.getEntity());
        } finally {
            Thread.currentThread().setContextClassLoader(previous);
        }
    }
}
