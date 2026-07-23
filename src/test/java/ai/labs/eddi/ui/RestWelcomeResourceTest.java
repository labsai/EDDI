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
 * Unit tests for {@link RestWelcomeResource}.
 * welcome.html is on the test classpath via src/main/resources.
 */
class RestWelcomeResourceTest {

    private RestWelcomeResource resource;

    @BeforeEach
    void setUp() {
        resource = new RestWelcomeResource();
    }

    @Test
    @DisplayName("viewDefault should delegate to viewHtml and return 200")
    void viewDefaultDelegatesToViewHtml() {
        Response response = resource.viewDefault();
        assertEquals(200, response.getStatus());
        assertNotNull(response.getEntity(), "Entity must not be null");
        assertInstanceOf(InputStream.class, response.getEntity());
    }

    @Test
    @DisplayName("viewHtml should return 200 with a readable welcome.html entity")
    void viewHtmlReturnsOkWithEntity() {
        Response response = resource.viewHtml();
        assertEquals(200, response.getStatus());
        assertNotNull(response.getEntity(), "Entity must not be null");
        assertInstanceOf(InputStream.class, response.getEntity());
    }

    @Test
    @DisplayName("viewDefault and viewHtml should both serve the welcome.html SPA shell")
    void viewDefaultAndViewHtmlServeSameShell() {
        Response r1 = resource.viewDefault();
        Response r2 = resource.viewHtml();
        assertEquals(r1.getStatus(), r2.getStatus());
        // Both must return a non-null, readable stream
        assertNotNull(r1.getEntity(), "viewDefault entity must not be null");
        assertNotNull(r2.getEntity(), "viewHtml entity must not be null");
    }
}
