/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.cluster;

import io.nats.client.Connection;
import io.nats.client.JetStreamApiException;
import io.nats.client.KeyValue;
import io.nats.client.api.ApiResponse;
import io.nats.client.support.JsonParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.OptionalLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("NatsSharedKv conflicts")
class NatsSharedKvConflictTest {

    private static JetStreamApiException error(int code) throws Exception {
        String json = "{\"type\":\"io.nats.jetstream.api.v1.pub_ack_response\",\"error\":{\"code\":400,\"err_code\":" + code
                + ",\"description\":\"wrong last sequence\"}}";
        return new JetStreamApiException(new ApiResponse<Object>(JsonParser.parse(json)) {
        });
    }

    private static NatsSharedKv kv(KeyValue handle) {
        NatsConnectionManager connections = mock(NatsConnectionManager.class);
        when(connections.requireConnected()).thenReturn(mock(Connection.class));
        when(connections.keyValue("T_ADMIN")).thenReturn(handle);
        return new NatsSharedKv(connections, "T_ADMIN", null);
    }

    @ParameterizedTest
    @ValueSource(ints = {10071, 10164})
    @DisplayName("losing a create, a compare-and-set or a guarded delete is a conflict, not an outage — whichever code the server uses")
    void bothWrongLastSequenceCodesAreConflicts(int code) throws Exception {
        KeyValue handle = mock(KeyValue.class);
        when(handle.create(anyString(), any(byte[].class))).thenThrow(error(code));
        when(handle.update(anyString(), any(byte[].class), anyLong())).thenThrow(error(code));
        doThrow(error(code)).when(handle).delete(anyString(), anyLong());
        NatsSharedKv kv = kv(handle);
        assertEquals(OptionalLong.empty(), kv.create("gate.drain", new byte[]{1}));
        assertEquals(OptionalLong.empty(), kv.update("gate.drain", new byte[]{1}, 4L));
        assertFalse(kv.delete("gate.drain", 4L));
    }

    @ParameterizedTest
    @ValueSource(ints = {10008, 10058})
    @DisplayName("any other server error is still an outage")
    void otherErrorsAreUnavailable(int code) throws Exception {
        KeyValue handle = mock(KeyValue.class);
        when(handle.create(anyString(), any(byte[].class))).thenThrow(error(code));
        assertThrows(ClusterUnavailableException.class, () -> kv(handle).create("gate.drain", new byte[]{1}));
    }
}
