/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.email.ledger;

import ai.labs.eddi.integrations.email.ledger.IEmailMessageLedger.Direction;
import ai.labs.eddi.integrations.email.ledger.IEmailMessageLedger.Kind;
import ai.labs.eddi.integrations.email.ledger.IEmailMessageLedger.LedgerEntry;
import ai.labs.eddi.integrations.email.ledger.IEmailMessageLedger.State;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LedgerRetryTest {

    static final Instant NOW = Instant.parse("2026-11-07T10:00:00Z");

    IEmailMessageLedger ledger;
    List<String> calls;

    @BeforeEach
    void setUp() {
        ledger = mock(IEmailMessageLedger.class);
        calls = new ArrayList<>();
    }

    private static LedgerEntry row(int attempts) {
        return new LedgerEntry("row-1", "int-1", Kind.REPLY, Direction.OUT, "<m@contoso.com>", "conv-1", "in-1|reply|1", "reply:k",
                "alice@supplier.com", State.PENDING, attempts, NOW.plusSeconds(300), "claim-1", null, null, NOW, NOW, NOW.plus(Duration.ofDays(30)));
    }

    private void deferOrFail(LedgerRetry retry, LedgerEntry row, boolean permanent) {
        retry.deferOrFail(row, "CONNECT: down", permanent, NOW, () -> calls.add("fail"), () -> calls.add("retry"));
    }

    @Nested
    @DisplayName("backoff")
    class Backoff {

        @Test
        @DisplayName("base × 2^(attempts-1), capped at an hour; attempts below one count as one")
        void table() {
            Duration base = Duration.ofMinutes(1);
            assertEquals(Duration.ofMinutes(1), LedgerRetry.backoff(base, 0));
            assertEquals(Duration.ofMinutes(1), LedgerRetry.backoff(base, 1));
            assertEquals(Duration.ofMinutes(4), LedgerRetry.backoff(base, 3));
            assertEquals(LedgerRetry.MAX_BACKOFF, LedgerRetry.backoff(base, 7));
            assertEquals(LedgerRetry.MAX_BACKOFF, LedgerRetry.backoff(base, 30), "the doublings are clamped: no overflow");
            assertEquals(LedgerRetry.MAX_BACKOFF, LedgerRetry.backoff(base, Integer.MAX_VALUE));
            assertEquals(Duration.ofSeconds(120), LedgerRetry.backoff(Duration.ofSeconds(60), 2));
        }

        @Test
        @DisplayName("an instance uses its base; a missing, zero or negative base is one minute, fewer than one attempt is one")
        void instance() {
            assertEquals(Duration.ofSeconds(40), new LedgerRetry(ledger, 3, Duration.ofSeconds(10)).backoff(3));
            assertEquals(Duration.ofMinutes(2), new LedgerRetry(ledger, 3, null).backoff(2));
            assertEquals(Duration.ofMinutes(2), new LedgerRetry(ledger, 3, Duration.ZERO).backoff(2));
            assertEquals(Duration.ofMinutes(2), new LedgerRetry(ledger, 3, Duration.ofMinutes(-1)).backoff(2));
            assertEquals(1, new LedgerRetry(ledger, 0, null).maxAttempts());
        }
    }

    @Nested
    @DisplayName("defer or fail")
    class DeferOrFail {

        @Test
        @DisplayName("a permanent failure fails at once, without a reschedule")
        void permanent() {
            deferOrFail(new LedgerRetry(ledger, 6, Duration.ofMinutes(1)), row(1), true);

            assertEquals(List.of("fail"), calls);
            verify(ledger, never()).reschedule(anyString(), anyString(), any(), anyString(), any());
        }

        @Test
        @DisplayName("the last attempt fails, without a reschedule")
        void exhausted() {
            deferOrFail(new LedgerRetry(ledger, 3, Duration.ofMinutes(1)), row(3), false);

            assertEquals(List.of("fail"), calls);
            verify(ledger, never()).reschedule(anyString(), anyString(), any(), anyString(), any());
        }

        @Test
        @DisplayName("an attempt with attempts left is rescheduled after its backoff and counted as a retry")
        void deferred() {
            when(ledger.reschedule("row-1", "claim-1", NOW.plus(Duration.ofMinutes(2)), "CONNECT: down", NOW)).thenReturn(true);

            deferOrFail(new LedgerRetry(ledger, 3, Duration.ofMinutes(1)), row(2), false);

            assertEquals(List.of("retry"), calls);
            verify(ledger).reschedule("row-1", "claim-1", NOW.plus(Duration.ofMinutes(2)), "CONNECT: down", NOW);
        }

        @Test
        @DisplayName("a reschedule that lost the claim is not a retry of this node's")
        void claimLost() {
            when(ledger.reschedule(anyString(), anyString(), any(), anyString(), any())).thenReturn(false);

            deferOrFail(new LedgerRetry(ledger, 3, Duration.ofMinutes(1)), row(1), false);

            assertEquals(List.of(), calls);
        }
    }
}
