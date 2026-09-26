/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.apicalls.rest;

import ai.labs.eddi.configs.apicalls.IApiCallsStore;
import ai.labs.eddi.configs.apicalls.model.ApiCall;
import ai.labs.eddi.configs.apicalls.model.ApiCallsConfiguration;
import ai.labs.eddi.configs.apicalls.model.BatchRequestBuildingInstruction;
import ai.labs.eddi.configs.apicalls.model.HttpPreRequest;
import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.configs.schema.IJsonSchemaCreator;
import ai.labs.eddi.engine.security.spaces.ResourceAccessGuard;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * A {@code maxBatchSize} above the deployment ceiling used to be clamped
 * silently at run time, so the value the designer saved was not the value that
 * applied. It is now refused when the config is saved.
 */
@DisplayName("RestApiCallsStore — batch size above the ceiling is refused at save time")
class RestApiCallsStoreBatchLimitTest {

    private IApiCallsStore apiCallsStore;
    private RestApiCallsStore store;

    @BeforeEach
    void setUp() {
        apiCallsStore = mock(IApiCallsStore.class);
        store = new RestApiCallsStore(apiCallsStore, mock(IDocumentDescriptorStore.class), mock(IJsonSchemaCreator.class),
                mock(ResourceAccessGuard.class));
    }

    @Test
    void createAboveCeilingIsRefused() throws Exception {
        Response response = store.createApiCalls(configWithBatch(1001));

        assertEquals(400, response.getStatus());
        assertTrue(response.getEntity().toString().contains("eddi.httpcalls.batch.max-size-ceiling"), response.getEntity().toString());
        verify(apiCallsStore, never()).create(any());
    }

    @Test
    void updateAboveCeilingIsRefused() throws Exception {
        Response response = store.updateApiCalls("aabbccddeeff112233445566", 1, configWithBatch(1001));

        assertEquals(400, response.getStatus());
        verify(apiCallsStore, never()).update(anyString(), anyInt(), any());
    }

    @Test
    void operatorCeilingIsTheOneEnforced() {
        store.maxBatchSizeCeiling = 50;

        assertEquals(400, store.createApiCalls(configWithBatch(51)).getStatus());
    }

    private static ApiCallsConfiguration configWithBatch(int maxBatchSize) {
        var batch = new BatchRequestBuildingInstruction();
        batch.setMaxBatchSize(maxBatchSize);
        var preRequest = new HttpPreRequest();
        preRequest.setBatchRequests(batch);
        var call = new ApiCall();
        call.setName("fan-out");
        call.setPreRequest(preRequest);
        var configuration = new ApiCallsConfiguration();
        configuration.setHttpCalls(List.of(call));
        return configuration;
    }
}
