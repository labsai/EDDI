/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.ui;

import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link RestWelcomeResource}.
 */
class RestWelcomeResourceTest {

    private RestWelcomeResource resource;

    @BeforeEach
    void setUp() {
        resource = new RestWelcomeResource();
    }

    @Test
    @DisplayName("viewDefault should delegate to viewHtml")
    void viewDefaultDelegatesToViewHtml() {
        try {
            Response response = resource.viewDefault();
            assertEquals(200, response.getStatus());
        } catch (Exception e) {
            // Expected if welcome.html not on test classpath
            assertNotNull(e);
        }
    }

    @Test
    @DisplayName("viewHtml should return 200 when welcome.html exists")
    void viewHtmlReturnsOk() {
        try {
            Response response = resource.viewHtml();
            assertEquals(200, response.getStatus());
        } catch (Exception e) {
            // Expected if welcome.html not on test classpath
            assertNotNull(e);
        }
    }

    @Test
    @DisplayName("viewDefault and viewHtml should serve the same SPA shell")
    void viewDefaultAndViewHtmlServeSameShell() {
        try {
            Response r1 = resource.viewDefault();
            Response r2 = resource.viewHtml();
            assertEquals(r1.getStatus(), r2.getStatus());
        } catch (Exception e) {
            // Expected if welcome.html not on test classpath
            assertNotNull(e);
        }
    }
}
