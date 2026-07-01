/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.nlp.impl;

import ai.labs.eddi.configs.parser.model.ParserConfiguration;
import ai.labs.eddi.engine.lifecycle.ILifecycleTask;
import ai.labs.eddi.engine.runtime.IRuntime;
import ai.labs.eddi.engine.runtime.client.configuration.IResourceClientLibrary;
import ai.labs.eddi.modules.nlp.IInputParser;
import jakarta.inject.Provider;
import jakarta.ws.rs.container.AsyncResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DisplayName("RestSemanticParser — Deep Coverage")
@SuppressWarnings("unchecked")
class RestSemanticParserDeepCoverageTest {

    private IRuntime runtime;
    private IResourceClientLibrary resourceClientLibrary;
    private Provider<ILifecycleTask> parserProvider;
    private AsyncResponse asyncResponse;
    private RestSemanticParser parser;

    @BeforeEach
    void setUp() {
        runtime = mock(IRuntime.class);
        resourceClientLibrary = mock(IResourceClientLibrary.class);
        parserProvider = mock(Provider.class);
        asyncResponse = mock(AsyncResponse.class);

        Map<String, Provider<ILifecycleTask>> lifecycleTasks = new HashMap<>();
        lifecycleTasks.put("ai.labs.parser", parserProvider);

        parser = new RestSemanticParser(runtime, resourceClientLibrary, lifecycleTasks);
    }

    private Callable<Void> captureCallable() {
        ArgumentCaptor<Callable<Void>> captor = ArgumentCaptor.forClass(Callable.class);
        verify(runtime).submitCallable(captor.capture(), isNull());
        return captor.getValue();
    }

    @Nested
    @DisplayName("parse — success path")
    class SuccessPath {

        @Test
        @DisplayName("parses successfully with empty solutions")
        void parseSuccessEmptySolutions() throws Exception {
            doReturn(mock(Future.class)).when(runtime).submitCallable(any(Callable.class), any());

            // Set up parser task chain
            ILifecycleTask parserTask = mock(ILifecycleTask.class);
            doReturn(parserTask).when(parserProvider).get();

            ParserConfiguration parserConfig = new ParserConfiguration();
            doReturn(parserConfig).when(resourceClientLibrary).getResource(any(), eq(ParserConfiguration.class));

            IInputParser inputParser = mock(IInputParser.class);
            doReturn(inputParser).when(parserTask).configure(any(), any());
            doReturn(List.of()).when(inputParser).parse("hello");

            parser.parse("aabbccdd11223344eeff5566", 1, "hello", asyncResponse);
            Callable<Void> callable = captureCallable();
            callable.call();

            ArgumentCaptor<Object> resumeCaptor = ArgumentCaptor.forClass(Object.class);
            verify(asyncResponse).resume(resumeCaptor.capture());
            assertInstanceOf(List.class, resumeCaptor.getValue());
        }

        @Test
        @DisplayName("config with non-null config and extensions")
        void parseWithNonNullConfigAndExtensions() throws Exception {
            doReturn(mock(Future.class)).when(runtime).submitCallable(any(Callable.class), any());

            ILifecycleTask parserTask = mock(ILifecycleTask.class);
            doReturn(parserTask).when(parserProvider).get();

            ParserConfiguration parserConfig = new ParserConfiguration();
            parserConfig.setConfig(Map.of("key", "val"));
            parserConfig.setExtensions(Map.of("ext", Map.of("a", "b")));
            doReturn(parserConfig).when(resourceClientLibrary).getResource(any(), eq(ParserConfiguration.class));

            IInputParser inputParser = mock(IInputParser.class);
            doReturn(inputParser).when(parserTask).configure(any(), any());
            doReturn(List.of()).when(inputParser).parse("test");

            parser.parse("aabbccdd11223344eeff5566", 1, "test", asyncResponse);
            Callable<Void> callable = captureCallable();
            callable.call();

            verify(parserTask).configure(eq(Map.of("key", "val")), eq(Map.of("ext", Map.of("a", "b"))));
        }
    }

    @Nested
    @DisplayName("parse — cache hit")
    class CacheHit {

        @Test
        @DisplayName("second parse reuses cached parser")
        void cachesParser() throws Exception {
            ILifecycleTask parserTask = mock(ILifecycleTask.class);
            doReturn(parserTask).when(parserProvider).get();

            ParserConfiguration parserConfig = new ParserConfiguration();
            doReturn(parserConfig).when(resourceClientLibrary).getResource(any(), eq(ParserConfiguration.class));

            IInputParser inputParser = mock(IInputParser.class);
            doReturn(inputParser).when(parserTask).configure(any(), any());
            doReturn(List.of()).when(inputParser).parse(anyString());

            // First parse
            doReturn(mock(Future.class)).when(runtime).submitCallable(any(Callable.class), any());
            parser.parse("aabbccdd11223344eeff5566", 1, "first", asyncResponse);
            captureCallable().call();

            // Second parse with same configId/version
            reset(runtime);
            doReturn(mock(Future.class)).when(runtime).submitCallable(any(Callable.class), any());
            AsyncResponse asyncResponse2 = mock(AsyncResponse.class);
            parser.parse("aabbccdd11223344eeff5566", 1, "second", asyncResponse2);
            captureCallable().call();

            // Parser provider should only be called once (cached)
            verify(parserProvider, times(1)).get();
        }
    }
}
