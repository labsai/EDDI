/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.runtime;

import ai.labs.eddi.engine.api.IRestGroupConversation.DiscussRequest;
import ai.labs.eddi.engine.security.AuthStartupGuard;
import ai.labs.eddi.engine.security.HighValueSurfaceGuard;
import ai.labs.eddi.utils.LogBanner;
import io.opentelemetry.context.Context;
import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.Priority;
import jakarta.interceptor.Interceptor;
import jakarta.validation.Valid;
import org.jboss.logging.Logger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.AnnotatedParameterizedType;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/** Boot-log readability and fail-fast startup. */
class StartupHygieneTest {

    @Test
    @DisplayName("a banner is logged one record per line, so the console filter has no newline to escape")
    void bannerIsLoggedPerLine() {
        Logger logger = mock(Logger.class);

        LogBanner.warn(logger, """

                +------+
                |  HI  |
                +------+

                """);

        // Exactly the three box lines, each its own record — none carries a line break.
        verify(logger, times(3)).warn(anyString());
        verify(logger, times(2)).warn("+------+");
        verify(logger).warn("|  HI  |");
        verifyNoMoreInteractions(logger);
    }

    @Test
    @DisplayName("the auth guards observe StartupEvent before every datastore-touching observer")
    void startupGuardsRunFirst() throws Exception {
        assertEquals(Interceptor.Priority.PLATFORM_BEFORE, observerPriority(AuthStartupGuard.class));
        assertEquals(Interceptor.Priority.PLATFORM_BEFORE + 1, observerPriority(HighValueSurfaceGuard.class));
    }

    private static int observerPriority(Class<?> type) throws Exception {
        Method onStart = type.getDeclaredMethod("onStart", StartupEvent.class);
        Priority priority = onStart.getParameters()[0].getAnnotation(Priority.class);
        assertNotNull(priority, type.getSimpleName() + ".onStart has no observer priority");
        return priority.value();
    }

    @Test
    @DisplayName("@Valid sits on the attachments' type argument, not on the list (HV000271)")
    void discussRequestCascadesOnTypeArgument() {
        RecordComponent attachments = Arrays.stream(DiscussRequest.class.getRecordComponents())
                .filter(c -> c.getName().equals("attachments")).findFirst().orElseThrow();
        assertFalse(attachments.getAccessor().isAnnotationPresent(Valid.class));
        assertFalse(attachments.getAnnotatedType().isAnnotationPresent(Valid.class), "@Valid on the container is deprecated");
        var listType = (AnnotatedParameterizedType) attachments.getAnnotatedType();
        assertTrue(listType.getAnnotatedActualTypeArguments()[0].isAnnotationPresent(Valid.class));
    }

    @Test
    @DisplayName("the exemplar span supplier treats a missing OpenTelemetry context as no span")
    void exemplarSupplierIsNullSafe() {
        assertTrue(NullSafeExemplarSamplerProducer.NullSafeSpanContextSupplier.spanContextOf(null).isEmpty());
        // The root context holds no span: still no exemplar, and no exception.
        assertTrue(NullSafeExemplarSamplerProducer.NullSafeSpanContextSupplier.spanContextOf(Context.root())
                .filter(c -> c.isValid()).isEmpty());

        var supplier = new NullSafeExemplarSamplerProducer.NullSafeSpanContextSupplier();
        assertDoesNotThrow(supplier::isSampled);
        assertFalse(supplier.isSampled());
        assertTrue(new NullSafeExemplarSamplerProducer().exemplarSampler().isPresent());
    }
}
