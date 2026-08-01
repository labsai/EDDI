/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.rest;

import ai.labs.eddi.configs.rules.model.RuleSetConfiguration;
import ai.labs.eddi.datastore.serialization.SerializationCustomizer;
import ai.labs.eddi.engine.model.InputData;
import ai.labs.eddi.modules.llm.model.LlmConfiguration;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.ext.ReaderInterceptorContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Finding E6(1): {@code FAIL_ON_UNKNOWN_PROPERTIES} is disabled on every mapper
 * in the application, so {@code POST /rulestore/rulesets} with a typo'd key
 * answered 201, Jackson dropped the key, and a subsequent GET no longer showed
 * it — the author's edit vanished with no signal anywhere.
 *
 * <p>
 * The flag cannot simply be flipped: the same recipe builds the persistence
 * mapper and {@code PersistenceModule} applies {@code customize()} to the
 * MongoDB BSON mapper, so strictness there would stop stored documents from
 * loading. {@link StrictConfigurationBodyInterceptor} therefore applies it at
 * the HTTP boundary only. These tests pin both halves of that contract.
 * </p>
 */
@DisplayName("StrictConfigurationBodyInterceptor")
class StrictConfigurationBodyInterceptorTest {

    private ObjectMapper restMapper;
    private StrictConfigurationBodyInterceptor interceptor;

    @BeforeEach
    void setUp() {
        restMapper = SerializationCustomizer.configureObjectMapper(new ObjectMapper(), false);
        interceptor = new StrictConfigurationBodyInterceptor(restMapper);
    }

    private static ReaderInterceptorContext context(Class<?> type, String body) {
        var context = mock(ReaderInterceptorContext.class);
        doReturn(type).when(context).getType();
        when(context.getMediaType()).thenReturn(MediaType.APPLICATION_JSON_TYPE);
        when(context.getInputStream()).thenReturn(new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
        return context;
    }

    @Test
    @DisplayName("a typo'd key on a configuration body is a 400 naming the field, not a silent drop")
    void unknownFieldOnConfigurationBodyIsRejected() throws Exception {
        var context = context(RuleSetConfiguration.class, """
                { "behaviorGroupz": [], "expressionsAsActions": true }
                """);

        var thrown = assertThrows(BadRequestException.class, () -> interceptor.aroundReadFrom(context));

        assertEquals(400, thrown.getResponse().getStatus());
        String entity = String.valueOf(thrown.getResponse().getEntity());
        assertTrue(entity.contains("behaviorGroupz"), "the offending field must be named: " + entity);
        assertTrue(entity.contains("behaviorGroups"), "the legal fields must be listed: " + entity);
        verify(context, never()).proceed();
    }

    @Test
    @DisplayName("a well-formed configuration body is passed through unchanged")
    void wellFormedConfigurationBodyProceeds() throws Exception {
        var context = context(RuleSetConfiguration.class, """
                { "behaviorGroups": [], "expressionsAsActions": true }
                """);
        var parsed = new RuleSetConfiguration();
        when(context.proceed()).thenReturn(parsed);

        assertEquals(parsed, interceptor.aroundReadFrom(context));

        // The body was consumed by the strict parse, so it has to be handed back.
        verify(context).setInputStream(any(InputStream.class));
    }

    @Test
    @DisplayName("a non-configuration request body keeps its lenient behaviour")
    void nonConfigurationBodyIsNotMadeStrict() throws Exception {
        var context = context(InputData.class, """
                { "input": "hello", "someFieldTheClientInvented": 1 }
                """);
        when(context.proceed()).thenReturn(new InputData());

        interceptor.aroundReadFrom(context);

        verify(context).proceed();
        verify(context, never()).setInputStream(any(InputStream.class));
    }

    @Test
    @DisplayName("malformed JSON is left to the regular reader, not re-reported as an unknown field")
    void malformedJsonIsNotTranslated() throws Exception {
        var context = context(RuleSetConfiguration.class, "{ this is not json");
        when(context.proceed()).thenReturn(null);

        interceptor.aroundReadFrom(context);

        verify(context).proceed();
    }

    @Test
    @DisplayName("an empty body proceeds on the original stream, so the existing 'no entity' path is untouched")
    void emptyBodyProceeds() throws Exception {
        var context = context(RuleSetConfiguration.class, "");
        when(context.proceed()).thenReturn(null);

        interceptor.aroundReadFrom(context);

        verify(context).proceed();
        verify(context, never()).setInputStream(any(InputStream.class));
    }

    @Test
    @DisplayName("a non-JSON media type is left alone")
    void nonJsonMediaTypeIsIgnored() throws Exception {
        var context = mock(ReaderInterceptorContext.class);
        doReturn(RuleSetConfiguration.class).when(context).getType();
        when(context.getMediaType()).thenReturn(MediaType.APPLICATION_OCTET_STREAM_TYPE);
        when(context.proceed()).thenReturn(null);

        interceptor.aroundReadFrom(context);

        verify(context).proceed();
        verify(context, never()).getInputStream();
    }

    @Test
    @DisplayName("the shared REST mapper is left lenient — only a copy is made strict")
    void sharedMapperIsNotMutated() {
        assertFalse(restMapper.isEnabled(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES),
                "the injected mapper is shared with the REST client and with services that parse third-party JSON; "
                        + "making it strict would reject payloads EDDI does not own");
    }

    @Test
    @DisplayName("configuration models are recognised across both packages they live in")
    void configurationModelDetection() {
        assertTrue(StrictConfigurationBodyInterceptor.isConfigurationModel(RuleSetConfiguration.class));
        assertTrue(StrictConfigurationBodyInterceptor.isConfigurationModel(LlmConfiguration.class));
        assertFalse(StrictConfigurationBodyInterceptor.isConfigurationModel(InputData.class));
        assertFalse(StrictConfigurationBodyInterceptor.isConfigurationModel(null));
    }

    @Test
    @DisplayName("a stored document carrying an unknown field must still load through the persistence recipe")
    void persistenceRecipeStaysLenient() throws Exception {
        // The counterpart guarantee: write-time strictness must not have been bought
        // by making schema evolution impossible on the read path.
        var stored = SerializationCustomizer.configureObjectMapper(new ObjectMapper(), false)
                .readValue("""
                        { "behaviorGroups": [], "expressionsAsActions": true, "removedInAnEarlierRelease": "x" }
                        """, RuleSetConfiguration.class);

        assertEquals(Boolean.TRUE, stored.getExpressionsAsActions());
    }
}
