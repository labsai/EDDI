/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.runtime.client.configuration;

import ai.labs.eddi.configs.apicalls.IApiCallsStore;
import ai.labs.eddi.configs.dictionary.IDictionaryStore;
import ai.labs.eddi.configs.llm.ILlmStore;
import ai.labs.eddi.configs.mcpcalls.IMcpCallsStore;
import ai.labs.eddi.configs.output.IOutputStore;
import ai.labs.eddi.configs.parser.IParserStore;
import ai.labs.eddi.configs.propertysetter.IPropertySetterStore;
import ai.labs.eddi.configs.rag.IRagStore;
import ai.labs.eddi.configs.rules.IRuleSetStore;
import ai.labs.eddi.configs.rules.IRestRuleSetStore;
import ai.labs.eddi.configs.apicalls.IRestApiCallsStore;
import ai.labs.eddi.configs.llm.IRestLlmStore;
import ai.labs.eddi.configs.mcpcalls.IRestMcpCallsStore;
import ai.labs.eddi.configs.output.IRestOutputStore;
import ai.labs.eddi.configs.parser.IRestParserStore;
import ai.labs.eddi.configs.propertysetter.IRestPropertySetterStore;
import ai.labs.eddi.configs.rag.IRestRagStore;
import ai.labs.eddi.configs.dictionary.IRestDictionaryStore;
import ai.labs.eddi.engine.runtime.service.ServiceException;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link ResourceClientLibrary}. Tests resource routing,
 * duplicateResource, deleteResource.
 */
class ResourceClientLibraryTest {

    // Reads resolve against the stores (the engine's path, below ownership
    // enforcement); duplicate/delete resolve against the REST facades (the
    // authoring
    // path, which ResourceAccessGuard sees). The split is the point of these tests.
    private IParserStore parserStore;
    private IDictionaryStore dictionaryStore;
    private IRuleSetStore ruleSetStore;
    private IApiCallsStore apiCallsStore;
    private ILlmStore llmStore;
    private IOutputStore outputStore;
    private IPropertySetterStore propertySetterStore;
    private IMcpCallsStore mcpCallsStore;
    private IRagStore ragStore;

    private IRestParserStore restParserStore;
    private IRestDictionaryStore restDictionaryStore;
    private IRestRuleSetStore restRuleSetStore;
    private IRestApiCallsStore restApiCallsStore;
    private IRestLlmStore restLlmStore;
    private IRestOutputStore restOutputStore;
    private IRestPropertySetterStore restPropertySetterStore;
    private IRestMcpCallsStore restMcpCallsStore;
    private IRestRagStore restRagStore;

    private ResourceClientLibrary library;

    // Valid hex ID (>= 18 hex chars for RestUtilities.isValidId)
    private static final String VALID_ID = "abcdef1234567890ab";

    @BeforeEach
    void setUp() {
        parserStore = mock(IParserStore.class);
        dictionaryStore = mock(IDictionaryStore.class);
        ruleSetStore = mock(IRuleSetStore.class);
        apiCallsStore = mock(IApiCallsStore.class);
        llmStore = mock(ILlmStore.class);
        outputStore = mock(IOutputStore.class);
        propertySetterStore = mock(IPropertySetterStore.class);
        mcpCallsStore = mock(IMcpCallsStore.class);
        ragStore = mock(IRagStore.class);

        restParserStore = mock(IRestParserStore.class);
        restDictionaryStore = mock(IRestDictionaryStore.class);
        restRuleSetStore = mock(IRestRuleSetStore.class);
        restApiCallsStore = mock(IRestApiCallsStore.class);
        restLlmStore = mock(IRestLlmStore.class);
        restOutputStore = mock(IRestOutputStore.class);
        restPropertySetterStore = mock(IRestPropertySetterStore.class);
        restMcpCallsStore = mock(IRestMcpCallsStore.class);
        restRagStore = mock(IRestRagStore.class);

        library = new ResourceClientLibrary(parserStore, dictionaryStore, ruleSetStore,
                apiCallsStore, llmStore, outputStore, propertySetterStore, mcpCallsStore, ragStore,
                restParserStore, restDictionaryStore, restRuleSetStore, restApiCallsStore, restLlmStore,
                restOutputStore, restPropertySetterStore, restMcpCallsStore, restRagStore);
    }

    @Nested
    @DisplayName("getResource — routing verification")
    class GetResource {

        @Test
        @DisplayName("should route ai.labs.parser to parser store")
        void routesParser() throws Exception {
            library.getResource(
                    URI.create("eddi://ai.labs.parser/parserstore/parsers/" + VALID_ID + "?version=1"),
                    Object.class);

            verify(parserStore).read(eq(VALID_ID), eq(1));
        }

        @Test
        @DisplayName("should route ai.labs.llm to llm store")
        void routesLlm() throws Exception {
            library.getResource(
                    URI.create("eddi://ai.labs.llm/llmstore/llms/" + VALID_ID + "?version=1"),
                    Object.class);

            verify(llmStore).read(eq(VALID_ID), eq(1));
        }

        @Test
        @DisplayName("should route ai.labs.httpcalls to api calls store")
        void routesHttpCalls() throws Exception {
            library.getResource(
                    URI.create("eddi://ai.labs.httpcalls/httpcallsstore/httpcalls/" + VALID_ID + "?version=1"),
                    Object.class);

            verify(apiCallsStore).read(eq(VALID_ID), eq(1));
        }

        @Test
        @DisplayName("should route ai.labs.behavior to rule set store")
        void routesBehavior() throws Exception {
            library.getResource(
                    URI.create("eddi://ai.labs.behavior/behaviorstore/behaviors/" + VALID_ID + "?version=1"),
                    Object.class);

            verify(ruleSetStore).read(eq(VALID_ID), eq(1));
        }

        @Test
        @DisplayName("should route ai.labs.mcpcalls to mcp calls store")
        void routesMcpCalls() throws Exception {
            library.getResource(
                    URI.create("eddi://ai.labs.mcpcalls/mcpcallsstore/mcpcalls/" + VALID_ID + "?version=1"),
                    Object.class);

            verify(mcpCallsStore).read(eq(VALID_ID), eq(1));
        }

        @Test
        @DisplayName("should route ai.labs.rag to rag store")
        void routesRag() throws Exception {
            library.getResource(
                    URI.create("eddi://ai.labs.rag/ragstore/rags/" + VALID_ID + "?version=1"),
                    Object.class);

            verify(ragStore).read(eq(VALID_ID), eq(1));
        }

        @Test
        @DisplayName("should route ai.labs.property to property setter store")
        void routesProperty() throws Exception {
            library.getResource(
                    URI.create("eddi://ai.labs.property/propertystore/properties/" + VALID_ID + "?version=1"),
                    Object.class);

            verify(propertySetterStore).read(eq(VALID_ID), eq(1));
        }

        @Test
        @DisplayName("should route ai.labs.output to output store")
        void routesOutput() throws Exception {
            library.getResource(
                    URI.create("eddi://ai.labs.output/outputstore/outputsets/" + VALID_ID + "?version=1"),
                    Object.class);

            verify(outputStore).read(eq(VALID_ID), eq(1), eq(""), eq(""), eq(0), eq(0));
        }

        @Test
        @DisplayName("should return null for unknown type")
        void returnsNullForUnknown() throws Exception {
            Object result = library.getResource(
                    URI.create("eddi://ai.labs.unknown/unknownstore/unknowns/" + VALID_ID + "?version=1"),
                    Object.class);

            assertNull(result);
        }

        @Test
        @DisplayName("should support ai.labs.rules alias for ai.labs.behavior")
        void supportsRulesAlias() throws Exception {
            library.getResource(
                    URI.create("eddi://ai.labs.rules/rulestore/rules/" + VALID_ID + "?version=1"),
                    Object.class);

            verify(ruleSetStore).read(eq(VALID_ID), eq(1));
        }

        @Test
        @DisplayName("should support ai.labs.dictionary alias")
        void supportsDictionaryAlias() throws Exception {
            library.getResource(
                    URI.create("eddi://ai.labs.dictionary/dictionarystore/dictionaries/" + VALID_ID + "?version=1"),
                    Object.class);

            verify(dictionaryStore).read(eq(VALID_ID), eq(1));
        }
    }

    @Nested
    @DisplayName("duplicateResource")
    class DuplicateResource {

        @Test
        @DisplayName("should delegate to correct store")
        void delegatesToStore() throws Exception {
            when(restParserStore.duplicateParser(anyString(), any())).thenReturn(Response.ok().build());

            Response result = library.duplicateResource(
                    URI.create("eddi://ai.labs.parser/parserstore/parsers/" + VALID_ID + "?version=1"));

            assertEquals(200, result.getStatus());
        }

        @Test
        @DisplayName("should throw ServiceException for unknown type")
        void throwsForUnknown() {
            assertThrows(ServiceException.class,
                    () -> library.duplicateResource(
                            URI.create("eddi://ai.labs.unknown/store/items/" + VALID_ID + "?version=1")));
        }
    }

    @Nested
    @DisplayName("deleteResource")
    class DeleteResource {

        @Test
        @DisplayName("should delegate to correct store")
        void delegatesToStore() throws Exception {
            when(restLlmStore.deleteLlm(anyString(), any(), anyBoolean())).thenReturn(Response.ok().build());

            Response result = library.deleteResource(
                    URI.create("eddi://ai.labs.llm/llmstore/llms/" + VALID_ID + "?version=1"), false);

            assertEquals(200, result.getStatus());
        }

        @Test
        @DisplayName("should return OK for unknown type (graceful skip)")
        void gracefulSkipForUnknown() throws Exception {
            Response result = library.deleteResource(
                    URI.create("eddi://ai.labs.unknown/store/items/" + VALID_ID + "?version=1"), false);

            assertEquals(200, result.getStatus());
        }

        @Test
        @DisplayName("should pass permanent flag")
        void passesPermanentFlag() throws Exception {
            when(restRagStore.deleteRag(anyString(), any(), anyBoolean())).thenReturn(Response.ok().build());

            library.deleteResource(
                    URI.create("eddi://ai.labs.rag/ragstore/rags/" + VALID_ID + "?version=1"), true);

            verify(restRagStore).deleteRag(eq(VALID_ID), eq(1), eq(true));
        }
    }
}
