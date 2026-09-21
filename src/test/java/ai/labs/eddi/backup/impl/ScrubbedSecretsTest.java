/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.backup.impl;

import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * How a target's own credentials are put back into content the export's secret
 * scrubber redacted — and, above all, which credential goes where.
 */
class ScrubbedSecretsTest {

    private IJsonSerialization jsonSerialization;

    @BeforeEach
    void useRealJsonRoundTripping() throws Exception {
        // The merge under test is about JSON, not about mocks.
        var mapper = new ObjectMapper();
        jsonSerialization = Mockito.mock(IJsonSerialization.class);
        when(jsonSerialization.deserialize(anyString()))
                .thenAnswer(inv -> mapper.readValue((String) inv.getArgument(0), Object.class));
        when(jsonSerialization.serialize(any()))
                .thenAnswer(inv -> mapper.writeValueAsString(inv.getArgument(0)));
    }

    /**
     * The disclosure this pins shut. Pairing array elements by index alone took the
     * value at position i of the target and wrote it into the source object at
     * position i — which, after a reorder, is a <em>different</em> API call, with a
     * different {@code targetServerUrl} of its own. The credential is not lost, it
     * is handed to the other endpoint.
     */
    @Test
    @DisplayName("a reordered credential entry keeps its own secret, never the neighbour's")
    void reorderedEntriesDoNotSwapCredentials() throws Exception {
        String source = """
                {"httpCalls":[
                  {"name":"billing","request":{"uri":"https://billing.example.com",
                     "headers":{"Authorization":"${vault:REDACTED}"}}},
                  {"name":"analytics","request":{"uri":"https://analytics.example.com",
                     "headers":{"Authorization":"${vault:REDACTED}"}}}
                ]}""";
        // Same two calls, the other way round, as the target actually holds them.
        String target = """
                {"httpCalls":[
                  {"name":"analytics","request":{"uri":"https://analytics.example.com",
                     "headers":{"Authorization":"Bearer analytics-token"}}},
                  {"name":"billing","request":{"uri":"https://billing.example.com",
                     "headers":{"Authorization":"Bearer billing-token"}}}
                ]}""";

        String merged = ScrubbedSecrets.restore(source, target, jsonSerialization);

        int billing = merged.indexOf("billing.example.com");
        int analytics = merged.indexOf("analytics.example.com");
        assertTrue(billing >= 0 && analytics >= 0, merged);
        // Each endpoint must be followed by its own token, not the other one's.
        assertTrue(merged.indexOf("billing-token") > billing && merged.indexOf("billing-token") < analytics,
                "the billing call must carry the billing token: " + merged);
        assertTrue(merged.indexOf("analytics-token") > analytics,
                "the analytics call must carry the analytics token: " + merged);
    }

    /**
     * An inserted entry shifts every position after it. Without a stable binding
     * the target's first credential lands in the newly added call — a secret handed
     * to an endpoint the operator has just typed in.
     */
    @Test
    @DisplayName("an inserted entry does not inherit the credential of the one it displaced")
    void insertedEntryDoesNotInheritACredential() throws Exception {
        String source = """
                {"httpCalls":[
                  {"name":"new-call","request":{"uri":"https://attacker.example.com",
                     "headers":{"Authorization":"${vault:REDACTED}"}}},
                  {"name":"billing","request":{"uri":"https://billing.example.com",
                     "headers":{"Authorization":"${vault:REDACTED}"}}}
                ]}""";
        String target = """
                {"httpCalls":[
                  {"name":"billing","request":{"uri":"https://billing.example.com",
                     "headers":{"Authorization":"Bearer billing-token"}}}
                ]}""";

        String merged = ScrubbedSecrets.restore(source, target, jsonSerialization);

        int newCall = merged.indexOf("attacker.example.com");
        int billing = merged.indexOf("billing.example.com");
        assertTrue(newCall >= 0 && newCall < billing, merged);
        assertTrue(merged.indexOf("billing-token") > billing,
                "the only credential in the target belongs to the billing call: " + merged);
        // The new call has nothing to inherit, so its gap stays visible.
        assertTrue(merged.substring(newCall, billing).contains(ScrubbedSecrets.PLACEHOLDER),
                "an entry the target has no counterpart for must keep its placeholder: " + merged);
    }

    /**
     * The everyday path has to keep working: an entry the target still has under
     * the same name gets its own value back even when the operator edited
     * everything else about it.
     */
    @Test
    @DisplayName("an entry edited but not renamed still gets its own secret back")
    void identityBindingSurvivesAnEdit() throws Exception {
        String source = """
                {"httpCalls":[{"name":"billing","request":{"uri":"https://billing.example.com/v2",
                   "headers":{"Authorization":"${vault:REDACTED}"}}}]}""";
        String target = """
                {"httpCalls":[{"name":"billing","request":{"uri":"https://billing.example.com/v1",
                   "headers":{"Authorization":"Bearer billing-token"}}}]}""";

        String merged = ScrubbedSecrets.restore(source, target, jsonSerialization);

        assertTrue(merged.contains("Bearer billing-token"), merged);
        assertTrue(merged.contains("/v2"), "everything that is not a secret still comes from the source: " + merged);
        assertFalse(merged.contains(ScrubbedSecrets.PLACEHOLDER), merged);
    }

    /**
     * An element with no natural key can still be bound by position, but only when
     * the surrounding content proves the two are the same entry.
     */
    @Test
    @DisplayName("an anonymous entry is bound by position only when everything else matches")
    void anonymousEntriesNeedTheirContentToMatch() throws Exception {
        String source = "{\"keys\":[\"${vault:REDACTED}\"]}";
        String target = "{\"keys\":[\"sk-live-abc\"]}";
        assertEquals("{\"keys\":[\"sk-live-abc\"]}",
                ScrubbedSecrets.restore(source, target, jsonSerialization));

        String changed = """
                {"entries":[{"host":"a.example.com","token":"${vault:REDACTED}"},
                            {"host":"b.example.com","token":"${vault:REDACTED}"}]}""";
        String stored = """
                {"entries":[{"host":"b.example.com","token":"token-b"},
                            {"host":"a.example.com","token":"token-a"}]}""";

        String merged = ScrubbedSecrets.restore(changed, stored, jsonSerialization);

        assertFalse(merged.contains("token-b"),
                "b's token must not be written into the entry that calls a: " + merged);
        assertFalse(merged.contains("token-a"),
                "a's token must not be written into the entry that calls b: " + merged);
    }
}
