/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.pricing.rest;

import ai.labs.eddi.engine.pricing.ModelPriceCatalogException;
import ai.labs.eddi.engine.pricing.ModelPriceCatalogService;
import ai.labs.eddi.engine.pricing.PriceCatalogSnapshot;
import ai.labs.eddi.engine.pricing.model.ModelPriceCatalog;
import ai.labs.eddi.engine.pricing.model.ModelPriceEntry;
import ai.labs.eddi.engine.pricing.model.UnpricedPolicy;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RestModelPriceCatalogTest {

    private final ModelPriceCatalogService service = mock(ModelPriceCatalogService.class);
    private final RestModelPriceCatalog rest = new RestModelPriceCatalog(service, null);

    private static final ModelPriceCatalog DOC = new ModelPriceCatalog(3, UnpricedPolicy.ALLOW, null, null, List.of());

    @Test
    void onlyAdminsWriteAndReadTheReference() throws Exception {
        assertArrayEquals(new String[]{"eddi-admin"},
                IRestModelPriceCatalog.class.getMethod("replaceCatalog", String.class, ModelPriceCatalog.class).getAnnotation(RolesAllowed.class)
                        .value());
        assertArrayEquals(new String[]{"eddi-admin"}, IRestModelPriceCatalog.class.getMethod("reference").getAnnotation(RolesAllowed.class).value());
        for (String read : List.of("readCatalog", "unpriced")) {
            String[] roles = IRestModelPriceCatalog.class.getMethod(read).getAnnotation(RolesAllowed.class).value();
            assertEquals(List.of("eddi-admin", "eddi-editor"), Arrays.asList(roles), read);
        }
    }

    @Test
    void ifMatchIsTheExpectedRevision() {
        when(service.replace(eq(7L), any(), eq("anonymous"))).thenReturn(DOC.stamped(8, null, "anonymous"));
        Response response = rest.replaceCatalog("\"7\"", DOC);
        assertEquals(200, response.getStatus());
        assertEquals("8", response.getEntityTag().getValue());
        verify(service).replace(eq(7L), any(), eq("anonymous"));
    }

    @Test
    void withoutIfMatchTheBodyRevisionIsUsed() {
        when(service.replace(eq(3L), any(), any())).thenReturn(DOC.stamped(4, null, null));
        assertEquals(200, rest.replaceCatalog(null, DOC).getStatus());
    }

    @Test
    void conflictsAndViolationsAnswerTheirStatus() {
        when(service.replace(anyLong(), any(), any())).thenThrow(new ModelPriceCatalogException(409, "changed", List.of(), 9L));
        Response conflict = rest.replaceCatalog("3", DOC);
        assertEquals(409, conflict.getStatus());
        assertEquals(9L, ((Map<?, ?>) conflict.getEntity()).get("currentRevision"));
        assertEquals(400, rest.replaceCatalog("abc", DOC).getStatus());
        assertEquals(400, rest.replaceCatalog(null, null).getStatus());
    }

    @Test
    void resolveNeedsProviderAndModel() {
        when(service.resolve("openai", "gpt-4o")).thenReturn(Optional.of(ModelPriceEntry.of("openai", "gpt-4o", 2.5, 10.0)));
        assertEquals(400, rest.resolve("openai", "").getStatus());
        Map<?, ?> body = (Map<?, ?>) rest.resolve("openai", "gpt-4o").getEntity();
        assertEquals(true, body.get("matched"));
    }

    @Test
    void readReportsEffectiveAndFloorPolicy() {
        when(service.read()).thenReturn(DOC);
        when(service.snapshot()).thenReturn(PriceCatalogSnapshot.EMPTY.withFloor(UnpricedPolicy.REFUSE));
        when(service.floor()).thenReturn(UnpricedPolicy.REFUSE);
        when(service.reference()).thenReturn(ModelPriceCatalog.EMPTY);
        Map<?, ?> body = (Map<?, ?>) rest.readCatalog().getEntity();
        assertEquals(UnpricedPolicy.REFUSE, body.get("effectivePolicy"));
        assertEquals(DOC, body.get("catalog"));
    }
}
