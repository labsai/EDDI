/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.ui;

import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link RestWorkforceResource}. workforce.html is on the test
 * classpath via the stand-in under src/test/resources — the real shell is built
 * from ui/manager, and the unit-test job does not build the UI.
 */
class RestWorkforceResourceTest {

    private RestWorkforceResource resource;

    @BeforeEach
    void setUp() {
        resource = new RestWorkforceResource(ShellTestSupport.renderer());
    }

    /**
     * The rendered shell (BrandedShellRenderer) as text.
     */
    private static String readEntity(Response response) {
        assertNotNull(response.getEntity(), "Entity must not be null");
        assertInstanceOf(String.class, response.getEntity(), "the shell is served rendered");
        return (String) response.getEntity();
    }

    @Test
    @DisplayName("viewDefault should delegate to viewHtml and return 200")
    void viewDefaultDelegatesToViewHtml() throws IOException {
        Response response = resource.viewDefault();
        assertEquals(200, response.getStatus());
        assertFalse(readEntity(response).isEmpty(), "Entity must not be empty");
    }

    @Test
    @DisplayName("viewHtml should return 200 with a readable workforce.html entity")
    void viewHtmlReturnsOkWithEntity() throws IOException {
        Response response = resource.viewHtml();
        assertEquals(200, response.getStatus());
        assertTrue(readEntity(response).contains("<html"),
                "Entity must be the workforce.html document");
    }

    @Test
    @DisplayName("viewDefault and viewHtml should both serve the workforce.html SPA shell")
    void viewDefaultAndViewHtmlServeSameShell() throws IOException {
        Response r1 = resource.viewDefault();
        Response r2 = resource.viewHtml();
        assertEquals(r1.getStatus(), r2.getStatus());
        assertEquals(readEntity(r1), readEntity(r2),
                "Both endpoints must serve identical content");
    }
}
