/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.runtime.client.configuration;

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

    private IRestParserStore parserStore;
    private IRestDictionaryStore dictionaryStore;
    private IRestRuleSetStore ruleSetStore;
    private IRestApiCallsStore apiCallsStore;
    private IRestLlmStore llmStore;
    private IRestOutputStore outputStore;
    private IRestPropertySetterStore propertySetterStore;
    private IRestMcpCallsStore mcpCallsStore;
    private IRestRagStore ragStore;
    private ResourceClientLibrary library;

    // Valid hex ID (>= 18 hex chars for RestUtilities.isValidId)
    private static final String VALID_ID = "abcdef1234567890ab";

    @BeforeEach
    void setUp() {
        parserStore = mock(IRestParserStore.class);
        dictionaryStore = mock(IRestDictionaryStore.class);
        ruleSetStore = mock(IRestRuleSetStore.class);
        apiCallsStore = mock(IRestApiCallsStore.class);
        llmStore = mock(IRestLlmStore.class);
        outputStore = mock(IRestOutputStore.class);
        propertySetterStore = mock(IRestPropertySetterStore.class);
        mcpCallsStore = mock(IRestMcpCallsStore.class);
        ragStore = mock(IRestRagStore.class);
        library = new ResourceClientLibrary(parserStore, dictionaryStore, ruleSetStore,
                apiCallsStore, llmStore, outputStore, propertySetterStore, mcpCallsStore, ragStore);
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

            verify(parserStore).readParser(eq(VALID_ID), eq(1));
        }

        @Test
        @DisplayName("should route ai.labs.llm to llm store")
        void routesLlm() throws Exception {
            library.getResource(
                    URI.create("eddi://ai.labs.llm/llmstore/llms/" + VALID_ID + "?version=1"),
                    Object.class);

            verify(llmStore).readLlm(eq(VALID_ID), eq(1));
        }

        @Test
        @DisplayName("should route ai.labs.httpcalls to api calls store")
        void routesHttpCalls() throws Exception {
            library.getResource(
                    URI.create("eddi://ai.labs.httpcalls/httpcallsstore/httpcalls/" + VALID_ID + "?version=1"),
                    Object.class);

            verify(apiCallsStore).readApiCalls(eq(VALID_ID), eq(1));
        }

        @Test
        @DisplayName("should route ai.labs.behavior to rule set store")
        void routesBehavior() throws Exception {
            library.getResource(
                    URI.create("eddi://ai.labs.behavior/behaviorstore/behaviors/" + VALID_ID + "?version=1"),
                    Object.class);

            verify(ruleSetStore).readRuleSet(eq(VALID_ID), eq(1));
        }

        @Test
        @DisplayName("should route ai.labs.mcpcalls to mcp calls store")
        void routesMcpCalls() throws Exception {
            library.getResource(
                    URI.create("eddi://ai.labs.mcpcalls/mcpcallsstore/mcpcalls/" + VALID_ID + "?version=1"),
                    Object.class);

            verify(mcpCallsStore).readMcpCalls(eq(VALID_ID), eq(1));
        }

        @Test
        @DisplayName("should route ai.labs.rag to rag store")
        void routesRag() throws Exception {
            library.getResource(
                    URI.create("eddi://ai.labs.rag/ragstore/rags/" + VALID_ID + "?version=1"),
                    Object.class);

            verify(ragStore).readRag(eq(VALID_ID), eq(1));
        }

        @Test
        @DisplayName("should route ai.labs.property to property setter store")
        void routesProperty() throws Exception {
            library.getResource(
                    URI.create("eddi://ai.labs.property/propertystore/properties/" + VALID_ID + "?version=1"),
                    Object.class);

            verify(propertySetterStore).readPropertySetter(eq(VALID_ID), eq(1));
        }

        @Test
        @DisplayName("should route ai.labs.output to output store")
        void routesOutput() throws Exception {
            library.getResource(
                    URI.create("eddi://ai.labs.output/outputstore/outputsets/" + VALID_ID + "?version=1"),
                    Object.class);

            verify(outputStore).readOutputSet(eq(VALID_ID), eq(1), eq(""), eq(""), eq(0), eq(0));
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

            verify(ruleSetStore).readRuleSet(eq(VALID_ID), eq(1));
        }

        @Test
        @DisplayName("should support ai.labs.dictionary alias")
        void supportsDictionaryAlias() throws Exception {
            library.getResource(
                    URI.create("eddi://ai.labs.dictionary/dictionarystore/dictionaries/" + VALID_ID + "?version=1"),
                    Object.class);

            verify(dictionaryStore).readRegularDictionary(eq(VALID_ID), eq(1), eq(""), eq(""), eq(0), eq(0));
        }
    }

    @Nested
    @DisplayName("duplicateResource")
    class DuplicateResource {

        @Test
        @DisplayName("should delegate to correct store")
        void delegatesToStore() throws Exception {
            when(parserStore.duplicateParser(anyString(), any())).thenReturn(Response.ok().build());

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
            when(llmStore.deleteLlm(anyString(), any(), anyBoolean())).thenReturn(Response.ok().build());

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
            when(ragStore.deleteRag(anyString(), any(), anyBoolean())).thenReturn(Response.ok().build());

            library.deleteResource(
                    URI.create("eddi://ai.labs.rag/ragstore/rags/" + VALID_ID + "?version=1"), true);

            verify(ragStore).deleteRag(eq(VALID_ID), eq(1), eq(true));
        }
    }
}
