/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.ui;

import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link RestHtmlChatResource}.
 */
class RestHtmlChatResourceTest {

    private RestHtmlChatResource resource;

    @BeforeEach
    void setUp() {
        resource = new RestHtmlChatResource(ShellTestSupport.renderer());
    }

    // chat.html is on the test classpath via the stand-in under
    // src/test/resources/META-INF/resources (the real shell is built from ui/chat,
    // which the unit-test job skips), so these assert outright. Every assertion
    // here used to be hedged with `catch (Exception e) { assertNotNull(e); }`,
    // which passes whatever happens — and is why the resource-name defect below
    // went unnoticed.

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

    /**
     * The widget reads {@code ?token=} only when the operator opted in, and it
     * learns that from this script before its first request. A missing or malformed
     * value has to read as "off" on the widget side, so the script carries a bare
     * boolean and nothing the widget would have to parse.
     */
    @Test
    @DisplayName("__config__.js tells the widget whether ?token= is enabled, off by default")
    void chatConfigCarriesTheUrlTokenFlag() {
        Response off = resource.fetchChatConfig();
        assertEquals(200, off.getStatus());
        assertEquals("application/javascript", off.getMediaType().toString());
        assertEquals("no-cache, must-revalidate", off.getHeaderString("Cache-Control"),
                "a cached copy would outlive the operator's change");
        assertEquals("window.__EDDI_CHAT__={urlTokenEnabled:false};", off.getEntity());

        resource.urlTokenEnabled = true;
        assertEquals("window.__EDDI_CHAT__={urlTokenEnabled:true};", resource.fetchChatConfig().getEntity());
    }

    /**
     * {@code /chat/embed.js}, the embed launcher (T2.9): the Chat UI build's
     * standalone script, served as JavaScript (the deployment sends
     * {@code X-Content-Type-Options: nosniff}, so any other type would be refused
     * by the host page's browser) with an hour's caching — not immutable, because
     * host pages hard-code the URL.
     */
    @Test
    @DisplayName("embed.js serves the launcher from the Chat UI build as cacheable JavaScript")
    void embedScriptIsServedAsJavaScript() {
        byte[] script = "(function(){})();".getBytes(StandardCharsets.UTF_8);
        withResource(RestHtmlChatResource.EMBED_SCRIPT_RESOURCE, script, () -> {
            Response response = resource.fetchEmbedScript();

            assertEquals(200, response.getStatus());
            assertEquals("application/javascript;charset=UTF-8", response.getMediaType().toString().replace(" ", ""));
            assertEquals("public, max-age=3600", response.getHeaderString("Cache-Control"));
            assertArrayEquals(script, (byte[]) response.getEntity());
        });
    }

    @Test
    @DisplayName("embed.js answers 404 when the jar carries no UI (-DskipUi=true)")
    void embedScriptMissingIsNotFound() {
        withResource(RestHtmlChatResource.EMBED_SCRIPT_RESOURCE, null, () -> {
            Response response = resource.fetchEmbedScript();

            assertEquals(404, response.getStatus(), "no launcher must not read as the SPA shell or an empty script");
            assertNull(response.getEntity());
        });
    }

    /**
     * The launcher's path is a literal, so JAX-RS prefers it to the
     * {@code {path:.*}} catch-all that answers every other {@code /chat/...} with
     * the widget's HTML — which a host page's {@code <script>} would refuse under
     * nosniff.
     */
    @Test
    @DisplayName("embed.js is a literal path that produces JavaScript, beside the SPA catch-all")
    void embedScriptIsALiteralPath() throws Exception {
        Method method = IRestHtmlChatResource.class.getDeclaredMethod("fetchEmbedScript");
        assertEquals("embed.js", method.getAnnotation(Path.class).value());
        assertArrayEquals(new String[]{"application/javascript"}, method.getAnnotation(Produces.class).value());
    }

    /**
     * Run {@code body} with the thread's classloader answering {@code name} with
     * {@code bytes} (null: missing).
     */
    private static void withResource(String name, byte[] bytes, Runnable body) {
        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(new ClassLoader(previous) {
            @Override
            public InputStream getResourceAsStream(String requested) {
                if (!requested.equals(name)) {
                    return super.getResourceAsStream(requested);
                }
                return bytes == null ? null : new ByteArrayInputStream(bytes);
            }
        });
        try {
            body.run();
        } finally {
            Thread.currentThread().setContextClassLoader(previous);
        }
    }

    /**
     * {@code /chat} is an SPA shell, not an API operation — the same as the
     * manager, workforce and welcome shells, all three of which are hidden. It
     * alone showed up as a documented endpoint in the generated OpenAPI document.
     */
    @Test
    @DisplayName("both /chat methods are hidden from the generated OpenAPI document")
    void chatShellIsHiddenFromOpenApi() throws Exception {
        for (Method method : IRestHtmlChatResource.class.getDeclaredMethods()) {
            Operation operation = method.getAnnotation(Operation.class);
            assertNotNull(operation, method.getName() + " has no @Operation(hidden = true)");
            assertTrue(operation.hidden(), method.getName() + " must be hidden like every other SPA shell");
        }
    }
}
