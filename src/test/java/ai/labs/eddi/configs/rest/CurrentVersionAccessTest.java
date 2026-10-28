/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.rest;

import ai.labs.eddi.configs.IRestVersionInfo;
import ai.labs.eddi.configs.agents.rest.RestAgentStore;
import ai.labs.eddi.configs.agenttests.rest.RestAgentTestSuiteStore;
import ai.labs.eddi.configs.apicalls.rest.RestApiCallsStore;
import ai.labs.eddi.configs.channels.rest.RestChannelIntegrationStore;
import ai.labs.eddi.configs.connections.rest.RestConnectionStore;
import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.configs.descriptors.model.AccessLevel;
import ai.labs.eddi.configs.dictionary.rest.RestDictionaryStore;
import ai.labs.eddi.configs.groups.rest.RestAgentGroupStore;
import ai.labs.eddi.configs.llm.rest.RestLlmStore;
import ai.labs.eddi.configs.mcpcalls.rest.RestMcpCallsStore;
import ai.labs.eddi.configs.output.rest.RestOutputStore;
import ai.labs.eddi.configs.parser.IParserStore;
import ai.labs.eddi.configs.parser.rest.RestParserStore;
import ai.labs.eddi.configs.propertysetter.rest.RestPropertySetterStore;
import ai.labs.eddi.configs.rag.rest.RestRagStore;
import ai.labs.eddi.configs.rules.rest.RestRuleSetStore;
import ai.labs.eddi.configs.snippets.rest.RestPromptSnippetStore;
import ai.labs.eddi.configs.workflows.rest.RestWorkflowStore;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.engine.security.spaces.ResourceAccessGuard;
import io.quarkus.security.ForbiddenException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code GET|POST /{id}/currentversion} require VIEW on the addressed resource,
 * like reading it — the authoring role alone let an editor probe any known id
 * in a workspace they cannot read.
 */
class CurrentVersionAccessTest {

    private static final String ID = "0123456789abcdef01234567";

    private IParserStore parserStore;
    private ResourceAccessGuard guard;
    private RestParserStore store;

    @BeforeEach
    void setUp() {
        parserStore = mock(IParserStore.class);
        guard = mock(ResourceAccessGuard.class);
        store = new RestParserStore(parserStore, mock(IDocumentDescriptorStore.class), guard);
    }

    @Test
    void getCurrentVersion_withoutViewAccess_isRefusedBeforeTheLookup() throws Exception {
        when(guard.requireAccess(eq(ID), eq(AccessLevel.VIEW), anyString())).thenThrow(new ForbiddenException("no"));

        assertThrows(ForbiddenException.class, () -> store.getCurrentVersion(ID));
        assertThrows(ForbiddenException.class, () -> store.redirectToLatestVersion(ID));
        verify(parserStore, never()).getCurrentResourceId(any());
    }

    @Test
    void getCurrentVersion_withViewAccess_answers() throws Exception {
        var resourceId = mock(IResourceStore.IResourceId.class);
        when(resourceId.getVersion()).thenReturn(4);
        when(parserStore.getCurrentResourceId(ID)).thenReturn(resourceId);

        assertEquals(4, store.getCurrentVersion(ID));
        verify(guard).requireAccess(eq(ID), eq(AccessLevel.VIEW), anyString());
    }

    @Test
    void theTrustedResolverStaysUnguarded() throws Exception {
        when(guard.requireAccess(eq(ID), eq(AccessLevel.VIEW), anyString())).thenThrow(new ForbiddenException("no"));
        var resourceId = mock(IResourceStore.IResourceId.class);
        when(parserStore.getCurrentResourceId(ID)).thenReturn(resourceId);

        // In-process engine code resolves the current version without a caller.
        assertEquals(resourceId, store.getCurrentResourceId(ID));
    }

    @Test
    void aStoreThatDoesNotSayHowToAuthorizeFailsClosed() {
        IRestVersionInfo unguarded = () -> "eddi://ai.labs.test/tests/";

        assertThrows(ForbiddenException.class, () -> unguarded.getCurrentVersion(ID));
    }

    @ParameterizedTest
    @ValueSource(classes = {RestAgentStore.class, RestAgentTestSuiteStore.class, RestApiCallsStore.class, RestChannelIntegrationStore.class,
            RestConnectionStore.class, RestDictionaryStore.class, RestAgentGroupStore.class, RestLlmStore.class, RestMcpCallsStore.class,
            RestOutputStore.class, RestParserStore.class, RestPropertySetterStore.class, RestRagStore.class, RestRuleSetStore.class,
            RestPromptSnippetStore.class, RestWorkflowStore.class, RestVersionInfo.class})
    void everyStoreDefinesItsVersionInfoCheck(Class<?> storeClass) throws Exception {
        assertNotEquals(IRestVersionInfo.class, storeClass.getMethod("requireCurrentVersionAccess", String.class).getDeclaringClass(),
                storeClass.getSimpleName() + " would answer 403 on currentversion for everybody");
    }
}
