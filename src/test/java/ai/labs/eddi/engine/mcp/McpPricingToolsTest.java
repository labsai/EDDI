/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.mcp;

import ai.labs.eddi.datastore.serialization.JsonSerialization;
import ai.labs.eddi.engine.pricing.rest.IRestModelPriceCatalog;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.security.ForbiddenException;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@code read_model_prices}: the catalog and the unpriced sightings in one
 * read, a model resolution only when both provider and model are given, and the
 * REST read tier.
 */
@DisplayName("McpPricingTools")
class McpPricingToolsTest {

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private IRestModelPriceCatalog catalog;

    @BeforeEach
    void setUp() {
        catalog = mock(IRestModelPriceCatalog.class);
        when(catalog.readCatalog()).thenReturn(Response.ok(Map.of("effectivePolicy", "REFUSE_WHEN_BUDGETED")).build());
        when(catalog.unpriced()).thenReturn(Response.ok(Map.of("node", "local", "sightings", List.of())).build());
    }

    private McpPricingTools tools(Set<String> roles) {
        SecurityIdentity id = mock(SecurityIdentity.class);
        when(id.isAnonymous()).thenReturn(false);
        when(id.hasRole(anyString())).thenAnswer(inv -> roles.contains(inv.getArgument(0, String.class)));
        when(id.getRoles()).thenReturn(roles);
        return new McpPricingTools(catalog, new JsonSerialization(mapper), id, true);
    }

    @Test
    @DisplayName("without a model: the catalog and the unpriced sightings, no resolution")
    void readsCatalogAndSightings() throws Exception {
        JsonNode result = mapper.readTree(tools(Set.of("eddi-editor")).readModelPrices(null, null));

        assertEquals("REFUSE_WHEN_BUDGETED", result.get("catalog").get("effectivePolicy").asText());
        assertEquals("local", result.get("unpriced").get("node").asText());
        assertFalse(result.has("resolved"));
        verify(catalog, never()).resolve(anyString(), anyString());
    }

    @Test
    @DisplayName("with provider and model: also which entry prices that model")
    void resolvesAModel() throws Exception {
        when(catalog.resolve("openai", "gpt-4o-mini")).thenReturn(Response.ok(Map.of("matched", true)).build());

        JsonNode result = mapper.readTree(tools(Set.of("eddi-admin")).readModelPrices(" openai ", "gpt-4o-mini"));

        assertTrue(result.get("resolved").get("matched").asBoolean());
    }

    @Test
    @DisplayName("only one of provider and model is a caller mistake, refused before any read")
    void halfAResolutionIsRefused() throws Exception {
        assertEquals("BAD_REQUEST", mapper.readTree(tools(Set.of("eddi-admin")).readModelPrices("openai", null)).get("errorCode").asText());
        verifyNoInteractions(catalog);
    }

    @Test
    @DisplayName("eddi-viewer and eddi-user are refused, as REST refuses them")
    void viewerIsRefused() {
        assertThrows(ForbiddenException.class, () -> tools(Set.of("eddi-viewer")).readModelPrices(null, null));
        assertThrows(ForbiddenException.class, () -> tools(Set.of("eddi-user")).readModelPrices(null, null));
    }
}
