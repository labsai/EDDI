/* Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.nlp.impl;

import ai.labs.eddi.configs.parser.model.ParserConfiguration;
import ai.labs.eddi.engine.lifecycle.ILifecycleTask;
import ai.labs.eddi.engine.runtime.IRuntime;
import ai.labs.eddi.engine.runtime.client.configuration.IResourceClientLibrary;
import ai.labs.eddi.modules.nlp.IInputParser;
import ai.labs.eddi.modules.nlp.expressions.Expression;
import ai.labs.eddi.modules.nlp.expressions.Expressions;
import ai.labs.eddi.modules.nlp.impl.RestSemanticParser.ResponseSolution;
import jakarta.inject.Provider;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.InternalServerErrorException;
import jakarta.ws.rs.container.AsyncResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.mockito.MockitoAnnotations.openMocks;

@DisplayName("RestSemanticParser")
class RestSemanticParserTest {

    @Mock
    private IRuntime runtime;

    @Mock
    private IResourceClientLibrary resourceClientLibrary;

    @Mock
    private Provider<ILifecycleTask> parserProvider;

    @Mock
    private AsyncResponse asyncResponse;

    private RestSemanticParser restSemanticParser;

    @BeforeEach
    void setUp() {
        openMocks(this);

        Map<String, Provider<ILifecycleTask>> lifecycleTasks = new HashMap<>();
        lifecycleTasks.put("ai.labs.parser", parserProvider);

        restSemanticParser = new RestSemanticParser(runtime, resourceClientLibrary, lifecycleTasks);
    }

    @SuppressWarnings("unchecked")
    private Callable<Void> captureSubmittedCallable() {
        ArgumentCaptor<Callable<Void>> captor = ArgumentCaptor.forClass(Callable.class);
        verify(runtime).submitCallable(captor.capture(), isNull());
        return captor.getValue();
    }

    @Nested
    @DisplayName("parse")
    class Parse {

        @Test
        @DisplayName("should set timeout on async response")
        void shouldSetTimeout() {
            doReturn(mock(Future.class)).when(runtime).submitCallable(any(Callable.class), any());

            restSemanticParser.parse("aabbccdd11223344eeff5566", 1, "hello", asyncResponse);

            verify(asyncResponse).setTimeout(eq(30L), any());
        }

        @Test
        @DisplayName("should submit callable to runtime")
        void shouldSubmitCallable() {
            doReturn(mock(Future.class)).when(runtime).submitCallable(any(Callable.class), any());

            restSemanticParser.parse("aabbccdd11223344eeff5566", 1, "hello", asyncResponse);

            verify(runtime).submitCallable(any(Callable.class), isNull());
        }

        @Test
        @DisplayName("should resume async response with BadRequestException on IllegalArgumentException")
        void shouldResumeBadRequestOnIllegalArgument() throws Exception {
            doReturn(mock(Future.class)).when(runtime).submitCallable(any(Callable.class), any());

            restSemanticParser.parse("aabbccdd11223344eeff5566", 1, "hello", asyncResponse);

            Callable<Void> callable = captureSubmittedCallable();

            // Make resourceClientLibrary throw to trigger the error path
            doThrow(new IllegalArgumentException("bad input"))
                    .when(resourceClientLibrary).getResource(any(), any());

            // Stub the parser provider to avoid NPE before the throw
            ILifecycleTask parserTask = mock(ILifecycleTask.class);
            doReturn(parserTask).when(parserProvider).get();

            callable.call();

            ArgumentCaptor<Throwable> resumeCaptor = ArgumentCaptor.forClass(Throwable.class);
            verify(asyncResponse).resume(resumeCaptor.capture());
            assertInstanceOf(BadRequestException.class, resumeCaptor.getValue());
        }

        @Test
        @DisplayName("should resume async response with InternalServerErrorException on generic exception")
        void shouldResumeInternalServerErrorOnException() throws Exception {
            doReturn(mock(Future.class)).when(runtime).submitCallable(any(Callable.class), any());

            restSemanticParser.parse("aabbccdd11223344eeff5566", 1, "hello", asyncResponse);

            Callable<Void> callable = captureSubmittedCallable();

            // Make the parser provider throw a generic exception
            doThrow(new RuntimeException("unexpected error")).when(parserProvider).get();

            callable.call();

            ArgumentCaptor<Throwable> resumeCaptor = ArgumentCaptor.forClass(Throwable.class);
            verify(asyncResponse).resume(resumeCaptor.capture());
            assertInstanceOf(InternalServerErrorException.class, resumeCaptor.getValue());
        }
    }

    @Nested
    @DisplayName("ResponseSolution")
    class ResponseSolutionTest {

        @Test
        @DisplayName("should convert expressions to string")
        void shouldConvertExpressionsToString() {
            Expressions expressions = new Expressions(new Expression("greeting", new Expression("hello")));

            ResponseSolution solution = new ResponseSolution(expressions);

            assertNotNull(solution.getExpressions());
            assertEquals(expressions.toString(), solution.getExpressions());
        }

        @Test
        @DisplayName("should support default constructor and setter")
        void shouldSupportDefaultConstructor() {
            ResponseSolution solution = new ResponseSolution();
            assertNull(solution.getExpressions());

            solution.setExpressions("test(value)");
            assertEquals("test(value)", solution.getExpressions());
        }
    }
}
