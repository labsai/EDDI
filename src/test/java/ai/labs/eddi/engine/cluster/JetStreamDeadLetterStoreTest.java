/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.cluster;

import io.nats.client.JetStreamApiException;
import io.nats.client.api.ApiResponse;
import io.nats.client.support.JsonParser;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Which JetStream errors mean "that dead letter is gone". A single-server
 * stream answers a delete of a deleted entry with 10057, a replicated one with
 * 10043 — and the 10043 case used to surface as a 500 from a discard through
 * another node, where the API promises 404.
 */
class JetStreamDeadLetterStoreTest {

    private static JetStreamApiException error(int code) throws Exception {
        String json = "{\"type\":\"io.nats.jetstream.api.v1.stream_msg_delete_response\",\"error\":{\"code\":404,\"err_code\":" + code
                + ",\"description\":\"not found\"}}";
        return new JetStreamApiException(new ApiResponse<Object>(JsonParser.parse(json)) {
        });
    }

    @ParameterizedTest(name = "err_code {0} -> not found: {1}")
    @CsvSource({"10037,true", "10057,true", "10043,true", "10077,false", "10008,false"})
    void classifiesNotFound(int code, boolean expected) throws Exception {
        assertEquals(expected, JetStreamDeadLetterStore.notFound(error(code)));
    }
}
