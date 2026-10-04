/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.cluster;

import io.nats.client.JetStreamApiException;
import io.nats.client.JetStreamManagement;
import io.nats.client.api.ApiResponse;
import io.nats.client.api.MessageInfo;
import io.nats.client.support.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import io.nats.client.JetStream;
import io.nats.client.api.PublishAck;
import java.io.IOException;
import java.util.Map;

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

    private static MessageInfo message(String subject) {
        MessageInfo info = mock(MessageInfo.class);
        when(info.isMessage()).thenReturn(true);
        when(info.getSubject()).thenReturn(subject);
        return info;
    }

    private static JetStreamDeadLetterStore store(JetStreamManagement jsm) {
        NatsConnectionManager connections = mock(NatsConnectionManager.class);
        when(connections.config()).thenReturn(ClusterConfig.defaults());
        when(connections.jetStreamManagement()).thenReturn(jsm);
        return new JetStreamDeadLetterStore(connections);
    }

    @Test
    void discardRemovesATurnsDeadLetter() throws Exception {
        JetStreamManagement jsm = mock(JetStreamManagement.class);
        MessageInfo entry = message("eddi.EDDI.dlq.turn.abc");
        when(jsm.getMessage("EDDI_DEAD_LETTERS", 7L)).thenReturn(entry);
        when(jsm.deleteMessage("EDDI_DEAD_LETTERS", 7L)).thenReturn(true);
        assertTrue(store(jsm).delete("7"));
    }

    @Test
    void discardCannotDeleteAnAuditDeadLetter() throws Exception {
        JetStreamManagement jsm = mock(JetStreamManagement.class);
        MessageInfo entry = message("eddi.EDDI.dlq.audit");
        when(jsm.getMessage("EDDI_DEAD_LETTERS", 9L)).thenReturn(entry);
        assertFalse(store(jsm).delete("9"));
        verify(jsm, never()).deleteMessage(anyString(), anyLong());
    }

    @Test
    void discardingTwiceIsNotFoundTheSecondTime() throws Exception {
        JetStreamManagement jsm = mock(JetStreamManagement.class);
        when(jsm.getMessage("EDDI_DEAD_LETTERS", 7L)).thenThrow(error(10043));
        assertFalse(store(jsm).delete("7"));
    }

    @Test
    void theDefaultStreamNameFollowsThePrefix() {
        assertEquals("EDDI_DEAD_LETTERS", ClusterConfig.defaults().deadLetterStreamName());
        assertEquals("PROD_DEAD_LETTERS", ClusterConfig.defaults().withNatsPrefix("PROD").deadLetterStreamName());
        ClusterConfig explicit = ClusterConfig.defaults().withNatsPrefix("PROD");
        explicit.deadLetterStreamName = "MY_DLQ";
        assertEquals("MY_DLQ", explicit.deadLetterStreamName());
    }

    @Test
    void serverUrlsAreLoggedWithoutCredentials() {
        assertEquals("nats://***@nats-1:4222", ClusterConfig.redactUserInfo("nats://eddi:s3cret@nats-1:4222"));
        assertEquals("nats://***@nats-1:4222", ClusterConfig.redactUserInfo("nats://token@nats-1:4222"));
        assertEquals("nats://nats-1:4222", ClusterConfig.redactUserInfo("nats://nats-1:4222"));
        assertEquals("nats://a:4222/x@y", ClusterConfig.redactUserInfo("nats://a:4222/x@y"));
        ClusterConfig config = ClusterConfig.defaults().withNatsUrl("nats://u:p@a:4222, nats://b:4222");
        assertEquals(List.of("nats://***@a:4222", "nats://b:4222"), config.natsServersForLog());
    }

    @ParameterizedTest(name = "err_code {0} -> not found: {1}")
    @CsvSource({"10037,true", "10057,true", "10043,true", "10059,true", "10077,false", "10008,false"})
    void classifiesNotFound(int code, boolean expected) throws Exception {
        assertEquals(expected, JetStreamDeadLetterStore.notFound(error(code)));
    }

    @Test
    void aStreamStillElectingItsLeaderIsWaitedFor() throws Exception {
        JetStreamManagement jsm = mock(JetStreamManagement.class);
        JetStream js = mock(JetStream.class);
        NatsConnectionManager connections = mock(NatsConnectionManager.class);
        when(connections.config()).thenReturn(ClusterConfig.defaults());
        when(connections.jetStreamManagement()).thenReturn(jsm);
        when(connections.jetStream()).thenReturn(js);
        when(connections.node()).thenReturn(new NodeIdentity("n1", "b1"));
        when(jsm.getStreamInfo(anyString())).thenThrow(error(10059));
        PublishAck ack = mock(PublishAck.class);
        when(ack.getSeqno()).thenReturn(42L);
        // missing stream, then "no responders" twice while the new stream elects a
        // leader
        when(js.publish(anyString(), any(byte[].class))).thenThrow(new IOException("503 No Responders Available For Request"))
                .thenThrow(new IOException("503 No Responders Available For Request"))
                .thenThrow(new IOException("503 No Responders Available For Request")).thenReturn(ack);
        assertEquals("42", new JetStreamDeadLetterStore(connections).append("c1", "boom", 1L, Map.of()));
    }
}
