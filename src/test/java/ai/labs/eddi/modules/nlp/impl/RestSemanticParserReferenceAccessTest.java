/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.nlp.impl;

import ai.labs.eddi.configs.descriptors.model.AccessLevel;
import ai.labs.eddi.configs.parser.model.ParserConfiguration;
import ai.labs.eddi.engine.lifecycle.ILifecycleTask;
import ai.labs.eddi.engine.runtime.IRuntime;
import ai.labs.eddi.engine.runtime.client.configuration.IResourceClientLibrary;
import ai.labs.eddi.engine.security.spaces.ResourceAccessGuard;
import ai.labs.eddi.modules.nlp.IInputParser;
import jakarta.inject.Provider;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.container.AsyncResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * A parser document saved before the save-time check can still name another
 * owner's parser in {@code config.uri}, and the standalone parse endpoint loads
 * it. The parser cache is shared between callers, so the access check has to
 * run for each request, not when the entry is built.
 */
@DisplayName("RestSemanticParser - config.uri access on the parse path")
@SuppressWarnings("unchecked")
class RestSemanticParserReferenceAccessTest {

    private static final String OWN_ID = "aabbccdd11223344eeff5566";
    private static final String OTHER_ID = "112233445566778899aabbcc";

    private IRuntime runtime;
    private IResourceClientLibrary resourceClientLibrary;
    private ResourceAccessGuard guard;
    private RestSemanticParser parser;

    @BeforeEach
    void setUp() throws Exception {
        runtime = mock(IRuntime.class);
        resourceClientLibrary = mock(IResourceClientLibrary.class);
        guard = mock(ResourceAccessGuard.class);
        Provider<ILifecycleTask> provider = mock(Provider.class);
        ILifecycleTask task = mock(ILifecycleTask.class);
        IInputParser inputParser = mock(IInputParser.class);
        doReturn(task).when(provider).get();
        doReturn(inputParser).when(task).configure(any(), any());
        doReturn(List.of()).when(inputParser).parse(anyString());
        doReturn(mock(Future.class)).when(runtime).submitCallable(any(Callable.class), any());

        Map<String, Provider<ILifecycleTask>> tasks = new HashMap<>();
        tasks.put("ai.labs.parser", provider);
        parser = new RestSemanticParser(runtime, resourceClientLibrary, tasks, guard);
    }

    private void documentNaming(String otherId) throws Exception {
        var document = new ParserConfiguration();
        document.setConfig(new HashMap<>(Map.of("uri", "eddi://ai.labs.parser/parserstore/parsers/" + otherId + "?version=1")));
        doReturn(document).when(resourceClientLibrary).getResource(any(), eq(ParserConfiguration.class));
    }

    @Test
    @DisplayName("a caller without VIEW on the referenced parser is refused before anything is submitted")
    void refusesCallerWithoutViewOnReference() throws Exception {
        documentNaming(OTHER_ID);
        doThrow(new ForbiddenException("no view")).when(guard).requireAccess(eq(OTHER_ID), eq(AccessLevel.VIEW), any());

        assertThrows(ForbiddenException.class, () -> parser.parse(OWN_ID, 1, "hello", mock(AsyncResponse.class)));

        verify(runtime, never()).submitCallable(any(Callable.class), any());
    }

    @Test
    @DisplayName("a caller with VIEW succeeds; the check runs on every request while the document is read once")
    void checksEveryRequestButReadsTheDocumentOnce() throws Exception {
        documentNaming(OTHER_ID);

        parser.parse(OWN_ID, 1, "first", mock(AsyncResponse.class));
        parser.parse(OWN_ID, 1, "second", mock(AsyncResponse.class));

        verify(runtime, times(2)).submitCallable(any(Callable.class), any());
        verify(guard, times(2)).requireAccess(eq(OTHER_ID), eq(AccessLevel.VIEW), any());
        verify(resourceClientLibrary, times(1)).getResource(any(), eq(ParserConfiguration.class));
    }

    @Test
    @DisplayName("a later caller is refused even though the parser is already cached for an earlier one")
    void cachedParserDoesNotSkipTheCheckForALaterCaller() throws Exception {
        documentNaming(OTHER_ID);
        parser.parse(OWN_ID, 1, "allowed", mock(AsyncResponse.class));

        doThrow(new ForbiddenException("no view")).when(guard).requireAccess(eq(OTHER_ID), eq(AccessLevel.VIEW), any());

        assertThrows(ForbiddenException.class, () -> parser.parse(OWN_ID, 1, "denied", mock(AsyncResponse.class)));

        verify(runtime, times(1)).submitCallable(any(Callable.class), any());
        verify(resourceClientLibrary, times(1)).getResource(any(), eq(ParserConfiguration.class));
    }

    @Test
    @DisplayName("a caller who sees everything is not asked per reference")
    void adminSkipsTheReferenceCheck() throws Exception {
        documentNaming(OTHER_ID);
        doReturn(true).when(guard).seesEverything();

        parser.parse(OWN_ID, 1, "hello", mock(AsyncResponse.class));

        verify(guard, never()).requireAccess(eq(OTHER_ID), any(), any());
    }

    @Test
    @DisplayName("a parser document without config.uri asks only about its own id")
    void noReferenceNoExtraCheck() throws Exception {
        doReturn(new ParserConfiguration()).when(resourceClientLibrary).getResource(any(), eq(ParserConfiguration.class));

        parser.parse(OWN_ID, 1, "hello", mock(AsyncResponse.class));

        verify(guard, times(1)).requireAccess(anyString(), any(), any());
    }
}
