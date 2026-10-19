/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.cluster;

import io.nats.client.Connection;
import io.nats.client.JetStream;
import io.nats.client.JetStreamApiException;
import io.nats.client.JetStreamManagement;
import io.nats.client.KeyValue;
import io.nats.client.PublishOptions;
import io.nats.client.api.ApiResponse;
import io.nats.client.api.DiscardPolicy;
import io.nats.client.api.StreamConfiguration;
import io.nats.client.api.MessageInfo;
import io.nats.client.api.PublishAck;
import java.util.ArrayList;
import io.nats.client.api.Subject;
import io.nats.client.api.StreamState;
import io.nats.client.api.StreamInfoOptions;
import io.nats.client.api.StreamInfo;
import io.nats.client.support.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import ai.labs.eddi.engine.model.DeadLetterEntry;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atMost;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
    void theTurnCountIncludesSubjectsFromLaterPages() throws Exception {
        // jnats appends the subjects of later stream-info pages to getSubjects() only;
        // getSubjectMap() keeps the first page.
        StreamState state = mock(StreamState.class);
        when(state.getSubjects())
                .thenReturn(new ArrayList<>(List.of(new Subject("eddi.EDDI.dlq.turn.a", 2), new Subject("eddi.EDDI.dlq.turn.b", 3))));
        when(state.getSubjectMap()).thenReturn(Map.of("eddi.EDDI.dlq.turn.a", 2L));
        StreamInfo info = mock(StreamInfo.class);
        when(info.getStreamState()).thenReturn(state);
        JetStreamManagement jsm = mock(JetStreamManagement.class);
        when(jsm.getStreamInfo(anyString(), any(StreamInfoOptions.class))).thenReturn(info);
        assertEquals(5, store(jsm).countTurns());
    }

    @Test
    void aReasonRecordedOnlyInTheTurnIsReadBack() throws Exception {
        JetStreamManagement jsm = mock(JetStreamManagement.class);
        MessageInfo entry = message("eddi.EDDI.dlq.turn.abc");
        when(entry.getSeq()).thenReturn(7L);
        when(entry.getData()).thenReturn(("{\"conversationId\":\"c1\",\"error\":\"lost\",\"timestamp\":1,"
                + "\"turn\":{\"input\":\"hi\",\"reason\":\"lease-lost\",\"fence\":41}}").getBytes(StandardCharsets.UTF_8));
        when(jsm.getMessage("EDDI_DEAD_LETTERS", 7L)).thenReturn(entry);
        DeadLetterEntry read = store(jsm).get("7").orElseThrow();
        assertEquals("lease-lost", read.reason());
        assertEquals(Map.of("token", 41), read.fence());
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
    void theFirstDeadLetterWaitsForTheStreamItCreatedToElectItsLeader() throws Exception {
        JetStreamManagement jsm = mock(JetStreamManagement.class);
        when(jsm.getStreamInfo("EDDI_DEAD_LETTERS")).thenThrow(error(10059));
        NatsConnectionManager connections = mock(NatsConnectionManager.class);
        when(connections.config()).thenReturn(ClusterConfig.defaults());
        when(connections.jetStreamManagement()).thenReturn(jsm);
        when(connections.node()).thenReturn(new NodeIdentity("n1", "b1"));
        when(connections.isConnected()).thenReturn(true);
        JetStream js = mock(JetStream.class);
        when(connections.jetStream()).thenReturn(js);
        PublishAck ack = mock(PublishAck.class);
        when(ack.getSeqno()).thenReturn(5L);
        when(js.publish(anyString(), any(byte[].class), any(PublishOptions.class)))
                .thenThrow(new IOException("no stream yet"))
                .thenThrow(new IOException("Error Publishing: 503 No Responders Available For Request"))
                .thenReturn(ack);
        var store = new JetStreamDeadLetterStore(connections);

        assertEquals("5", store.append("conv1", "boom", 1L, Map.of("input", "hi")),
                "a stream created a moment ago answers once its leader is elected; the dead letter must not fail over");
        verify(jsm).addStream(any());

        // Every attempt carries one message id, so a publish the server stored but
        // whose
        // ack was lost is not stored a second time by the retry (JetStream
        // de-duplicates).
        ArgumentCaptor<PublishOptions> options = ArgumentCaptor.forClass(PublishOptions.class);
        verify(js, times(3)).publish(anyString(), any(byte[].class), options.capture());
        List<String> ids = options.getAllValues().stream().map(PublishOptions::getMessageId).toList();
        assertNotNull(ids.get(0));
        assertEquals(1, ids.stream().distinct().count(), "one id for every attempt: " + ids);

        // The same dead letter forwarded later keeps its id; another one gets its own.
        store.append("conv1", "boom", 1L, Map.of("input", "hi"));
        store.append("conv1", "boom", 2L, Map.of("input", "hi"));
        verify(js, times(5)).publish(anyString(), any(byte[].class), options.capture());
        List<String> later = options.getAllValues().stream().skip(3 + 3).map(PublishOptions::getMessageId).toList();
        assertEquals(ids.get(0), later.get(0));
        assertNotEquals(ids.get(0), later.get(1));
    }
    private static NatsConnectionManager publishingConnections(JetStreamManagement jsm, JetStream js, boolean connected) {
        NatsConnectionManager connections = mock(NatsConnectionManager.class);
        when(connections.config()).thenReturn(ClusterConfig.defaults());
        when(connections.jetStreamManagement()).thenReturn(jsm);
        when(connections.node()).thenReturn(new NodeIdentity("n1", "b1"));
        when(connections.jetStream()).thenReturn(js);
        when(connections.isConnected()).thenReturn(connected);
        return connections;
    }

    @Test
    void aNatsThatHangsHoldsTheFailingTurnForAboutTwoSecondsNotTwenty() throws Exception {
        JetStreamManagement jsm = mock(JetStreamManagement.class);
        JetStream js = mock(JetStream.class);
        // Connected, but every publish runs into its request timeout (scaled down
        // here).
        when(js.publish(anyString(), any(byte[].class), any(PublishOptions.class))).thenAnswer(invocation -> {
            Thread.sleep(500);
            throw new IOException("Timeout or no response waiting for NATS JetStream server");
        });
        var store = new JetStreamDeadLetterStore(publishingConnections(jsm, js, true));

        long start = System.nanoTime();
        assertThrows(ClusterUnavailableException.class, () -> store.append("conv1", "boom", 1L, Map.of("input", "hi")));
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000;

        // Ten attempts of 500 ms plus backoff would take about 7 s.
        assertTrue(elapsedMillis < 4_000, "the dead letter retried for " + elapsedMillis + " ms on the turn's thread");
        ArgumentCaptor<PublishOptions> options = ArgumentCaptor.forClass(PublishOptions.class);
        verify(js, atMost(5)).publish(anyString(), any(byte[].class), options.capture());
        List<PublishOptions> attempts = options.getAllValues();
        assertTrue(attempts.size() >= 2, "expected the first publish and at least one retry: " + attempts.size());
        for (int i = 1; i < attempts.size(); i++) {
            assertTrue(attempts.get(i).getStreamTimeout().compareTo(attempts.get(i - 1).getStreamTimeout()) < 0,
                    "every attempt waits at most what is left of the budget: " + attempts.get(i).getStreamTimeout());
        }
        assertTrue(attempts.getLast().getStreamTimeout().toMillis() < 1_500,
                "the last attempt must not get a full default timeout: " + attempts.getLast().getStreamTimeout());
        assertEquals(1, attempts.stream().map(PublishOptions::getMessageId).distinct().count(), "one message id for every attempt");
    }

    @Test
    void aFirstPublishThatUsedUpTheBudgetSkipsProvisioningAndRetries() throws Exception {
        JetStreamManagement jsm = mock(JetStreamManagement.class);
        JetStream js = mock(JetStream.class);
        // A connected NATS that answers nothing: the first publish runs into its
        // timeout.
        when(js.publish(anyString(), any(byte[].class), any(PublishOptions.class))).thenAnswer(invocation -> {
            Thread.sleep(2_100);
            throw new IOException("Timeout or no response waiting for NATS JetStream server");
        });
        var store = new JetStreamDeadLetterStore(publishingConnections(jsm, js, true));

        long start = System.nanoTime();
        assertThrows(ClusterUnavailableException.class, () -> store.append("conv1", "boom", 1L, Map.of("input", "hi")));
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000;

        assertTrue(elapsedMillis < 3_000, "the append went on after its budget: " + elapsedMillis + " ms");
        verify(js, times(1)).publish(anyString(), any(byte[].class), any(PublishOptions.class));
        verify(jsm, never()).getStreamInfo(anyString());
    }

    @Test
    void aDisconnectedNodeKeepsTheDeadLetterLocalWithoutRetrying() throws Exception {
        JetStreamManagement jsm = mock(JetStreamManagement.class);
        JetStream js = mock(JetStream.class);
        when(js.publish(anyString(), any(byte[].class), any(PublishOptions.class))).thenThrow(new IOException("disconnected"));
        var store = new JetStreamDeadLetterStore(publishingConnections(jsm, js, false));

        assertThrows(ClusterUnavailableException.class, () -> store.append("conv1", "boom", 1L, Map.of("input", "hi")));

        verify(js, times(1)).publish(anyString(), any(byte[].class), any(PublishOptions.class));
        verify(jsm, never()).addStream(any());
    }

    @Test
    void aFullStreamKeepsTheDeadLetterLocalWithoutReprovisioning() throws Exception {
        JetStreamManagement jsm = mock(JetStreamManagement.class);
        JetStream js = mock(JetStream.class);
        // 10077: the stream is at max-bytes and refuses new messages
        // (DiscardPolicy.New).
        when(js.publish(anyString(), any(byte[].class), any(PublishOptions.class))).thenThrow(error(10077));
        var store = new JetStreamDeadLetterStore(publishingConnections(jsm, js, true));

        assertThrows(ClusterUnavailableException.class, () -> store.append("conv1", "boom", 1L, Map.of("input", "hi")));

        verify(js, times(1)).publish(anyString(), any(byte[].class), any(PublishOptions.class));
        verify(jsm, never()).getStreamInfo(anyString());
        verify(jsm, never()).updateStream(any());
    }

    @Test
    void theStreamIsCappedBySizeAndRefusesNewEntriesWhenFull() throws Exception {
        JetStreamManagement jsm = mock(JetStreamManagement.class);
        when(jsm.getStreamInfo("EDDI_DEAD_LETTERS")).thenThrow(error(10059));
        var store = new JetStreamDeadLetterStore(publishingConnections(jsm, mock(JetStream.class), true));

        store.provision();

        ArgumentCaptor<StreamConfiguration> created = ArgumentCaptor.forClass(StreamConfiguration.class);
        verify(jsm).addStream(created.capture());
        assertEquals(1L << 30, created.getValue().getMaxBytes());
        assertEquals(DiscardPolicy.New, created.getValue().getDiscardPolicy(),
                "a full stream must refuse new dead letters, not silently drop the oldest an operator may still replay");
    }

    // NatsSharedKv shares this class's NATS API error fixture: the codes a KV write
    // can be refused with.

    private static NatsSharedKv sharedKv(KeyValue handle) {
        NatsConnectionManager connections = mock(NatsConnectionManager.class);
        when(connections.requireConnected()).thenReturn(mock(Connection.class));
        when(connections.keyValue("T_LEASES")).thenReturn(handle);
        return new NatsSharedKv(connections, "T_LEASES", null);
    }

    @ParameterizedTest
    @ValueSource(ints = {10071, 10164})
    void aLostCreateCasOrGuardedDeleteIsAConflictWhicheverCodeTheServerUses(int code) throws Exception {
        KeyValue handle = mock(KeyValue.class);
        when(handle.create(anyString(), any(byte[].class))).thenThrow(error(code));
        when(handle.update(anyString(), any(byte[].class), anyLong())).thenThrow(error(code));
        doThrow(error(code)).when(handle).delete(anyString(), anyLong());
        NatsSharedKv kv = sharedKv(handle);
        assertEquals(OptionalLong.empty(), kv.create("c.conv1", new byte[]{1}), "two nodes racing for one lease: the loser lost, NATS is fine");
        assertEquals(OptionalLong.empty(), kv.update("c.conv1", new byte[]{1}, 4L));
        assertFalse(kv.delete("c.conv1", 4L));
    }

    @ParameterizedTest
    @ValueSource(ints = {10008, 10058})
    void anyOtherRefusalOfAKvWriteIsStillAnOutage(int code) throws Exception {
        KeyValue handle = mock(KeyValue.class);
        when(handle.create(anyString(), any(byte[].class))).thenThrow(error(code));
        assertThrows(ClusterUnavailableException.class, () -> sharedKv(handle).create("c.conv1", new byte[]{1}));
    }
}
