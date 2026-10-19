/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.security;

import io.quarkus.vertx.http.runtime.filters.Filters;
import io.vertx.core.Handler;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.ext.web.RoutingContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("HstsHeaderFilter")
class HstsHeaderFilterTest {

    @Test
    @DisplayName("registers nothing by default")
    void offByDefault() {
        Filters filters = mock(Filters.class);
        new HstsHeaderFilter().register(filters);
        verify(filters, never()).register(any(), anyInt());
    }

    @Test
    @DisplayName("sends max-age and passes the request on when enabled")
    @SuppressWarnings("unchecked")
    void sendsTheHeaderWhenEnabled() {
        HstsHeaderFilter filter = new HstsHeaderFilter();
        filter.enabled = true;
        filter.maxAgeSeconds = 600;
        Filters filters = mock(Filters.class);
        ArgumentCaptor<Handler<RoutingContext>> handler = ArgumentCaptor.forClass(Handler.class);

        filter.register(filters);

        verify(filters).register(handler.capture(), eq(HstsHeaderFilter.PRIORITY));
        RoutingContext context = mock(RoutingContext.class);
        HttpServerResponse response = mock(HttpServerResponse.class);
        when(context.response()).thenReturn(response);
        when(response.putHeader(anyString(), anyString())).thenReturn(response);
        handler.getValue().handle(context);
        verify(response).putHeader("Strict-Transport-Security", "max-age=600");
        verify(context).next();
    }

    @Test
    @DisplayName("a negative max-age is clamped to 0, which tells browsers to forget the policy")
    void negativeMaxAgeIsClamped() {
        assertEquals("max-age=0", HstsHeaderFilter.headerValue(-5));
        assertEquals("max-age=31536000", HstsHeaderFilter.headerValue(31_536_000L));
    }
}
