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
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link RestWelcomeResource}. welcome.html is on the test
 * classpath via src/main/resources.
 */
class RestWelcomeResourceTest {

    private RestWelcomeResource resource;

    @BeforeEach
    void setUp() {
        resource = new RestWelcomeResource();
    }

    /**
     * Consumes the response entity stream fully and closes it, so the test does not
     * leak the open classpath resource.
     */
    private static String readEntity(Response response) throws IOException {
        assertNotNull(response.getEntity(), "Entity must not be null");
        assertInstanceOf(InputStream.class, response.getEntity());
        try (InputStream stream = (InputStream) response.getEntity()) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    @DisplayName("viewDefault should delegate to viewHtml and return 200")
    void viewDefaultDelegatesToViewHtml() throws IOException {
        Response response = resource.viewDefault();
        assertEquals(200, response.getStatus());
        assertFalse(readEntity(response).isEmpty(), "Entity must not be empty");
    }

    @Test
    @DisplayName("viewHtml should return 200 with a readable welcome.html entity")
    void viewHtmlReturnsOkWithEntity() throws IOException {
        Response response = resource.viewHtml();
        assertEquals(200, response.getStatus());
        assertTrue(readEntity(response).contains("<html"),
                "Entity must be the welcome.html document");
    }

    @Test
    @DisplayName("viewDefault and viewHtml should both serve the welcome.html SPA shell")
    void viewDefaultAndViewHtmlServeSameShell() throws IOException {
        Response r1 = resource.viewDefault();
        Response r2 = resource.viewHtml();
        assertEquals(r1.getStatus(), r2.getStatus());
        assertEquals(readEntity(r1), readEntity(r2),
                "Both endpoints must serve identical content");
    }
}
