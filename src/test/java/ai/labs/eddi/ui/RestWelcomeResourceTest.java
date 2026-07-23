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
 *
 * In the plain JUnit (non-Quarkus) test context, RuntimeUtilities.getResourceAsStream
 * cannot resolve META-INF/resources/ files, so the null-guard returns 500.
 * These tests verify both the null-guard path and the delegation logic.
 */
class RestWelcomeResourceTest {

    private RestWelcomeResource resource;

    @BeforeEach
    void setUp() {
        resource = new RestWelcomeResource();
    }

    @Test
    @DisplayName("viewDefault should delegate to viewHtml (same status code)")
    void viewDefaultDelegatesToViewHtml() {
        Response fromDefault = resource.viewDefault();
        Response fromViewHtml = resource.viewHtml();
        assertEquals(fromViewHtml.getStatus(), fromDefault.getStatus(),
                "viewDefault must delegate to viewHtml");
    }

    @Test
    @DisplayName("viewHtml returns 500 when welcome.html is not on classpath")
    void viewHtmlReturns500WhenHtmlMissing() {
        // In plain JUnit context, getResourceAsStream cannot resolve the file
        Response response = resource.viewHtml();
        assertEquals(500, response.getStatus(),
                "Should return 500 when welcome.html is not resolvable");
        assertNull(response.getEntity(),
                "500 response should have no entity");
    }

    @Test
    @DisplayName("viewDefault returns same result as viewHtml (delegation)")
    void viewDefaultAndViewHtmlReturnConsistentResults() {
        Response r1 = resource.viewDefault();
        Response r2 = resource.viewHtml();
        assertEquals(r1.getStatus(), r2.getStatus());
        assertEquals(r1.getEntity(), r2.getEntity());
    }
}
