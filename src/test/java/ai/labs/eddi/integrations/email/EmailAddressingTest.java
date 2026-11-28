/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.email;

import ai.labs.eddi.configs.channels.model.ChannelIntegrationConfiguration;
import ai.labs.eddi.configs.channels.model.ChannelTarget;
import ai.labs.eddi.configs.channels.model.EmailChannelSettings;
import ai.labs.eddi.engine.mail.InboundMail;
import ai.labs.eddi.engine.mail.MailAddress;
import ai.labs.eddi.engine.mail.MentionExtractor.MentionHit;
import ai.labs.eddi.integrations.email.EmailAddressing.Addressing;
import ai.labs.eddi.integrations.email.EmailAddressing.OwnAddresses;
import ai.labs.eddi.integrations.email.EmailAddressing.Why;
import ai.labs.eddi.integrations.email.ledger.IEmailMessageLedger.Direction;
import ai.labs.eddi.integrations.email.ledger.IEmailMessageLedger.Kind;
import ai.labs.eddi.integrations.email.ledger.IEmailMessageLedger.LedgerEntry;
import ai.labs.eddi.integrations.email.ledger.IEmailMessageLedger.State;
import ai.labs.eddi.integrations.email.ledger.InMemoryEmailMessageLedger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The "addressed" rule (email v2 plan §7.2). */
class EmailAddressingTest {

    private static final String INTEGRATION = "a1b2c3d4e5f60718293a4b5c";

    private InMemoryEmailMessageLedger ledger;
    private OwnAddresses own;

    @BeforeEach
    void setUp() {
        ledger = new InMemoryEmailMessageLedger();
        var settings = EmailChannelSettings.from(Map.of("mode", "trigger", "address", "invoices@contoso.com", "aliases", "ap@contoso.com"));
        var finance = new ChannelTarget();
        finance.setName("finance");
        finance.setAddresses(List.of("Finance-Agent@contoso.com"));
        var integration = new ChannelIntegrationConfiguration();
        integration.setTargets(List.of(finance));
        own = OwnAddresses.of(settings, integration);
    }

    private static InboundMail mail(String inReplyTo, List<String> to, List<String> cc) {
        return new InboundMail("<m-" + UUID.randomUUID() + "@x.com>", false, inReplyTo, null, new MailAddress("bob@supplier.com", null), null,
                to.stream().map(a -> new MailAddress(a, null)).toList(), cc.stream().map(a -> new MailAddress(a, null)).toList(), "s", null,
                "text", null, false, false, null, null, null, null, null, 0);
    }

    private void record(Direction direction, Kind kind, String messageId, String integrationId) {
        Instant now = Instant.now();
        ledger.insertIfAbsent(new LedgerEntry(UUID.randomUUID().toString(), integrationId, kind, direction, messageId, "conv-1", null,
                UUID.randomUUID().toString(), "bob@supplier.com", direction == Direction.OUT ? State.SENT : State.DONE, 0, null, null, null, null,
                now, now, now.plus(Duration.ofDays(30))));
    }

    private Addressing evaluate(InboundMail mail, List<MentionHit> mentions) {
        return EmailAddressing.evaluate(INTEGRATION, mail, own, ledger, mentions);
    }

    @Test
    @DisplayName("rule 1: In-Reply-To names a reply this integration sent")
    void replyToEddi() {
        record(Direction.OUT, Kind.REPLY, "<eddi.r1@contoso.com>", INTEGRATION);
        assertEquals(new Addressing(true, Why.REPLY_TO_EDDI), evaluate(mail("<eddi.r1@contoso.com>", List.of("bob@x.com"), List.of()), List.of()));
    }

    @Test
    @DisplayName("rule 1 reads In-Reply-To only, and only this integration's replies")
    void replyToEddiNarrow() {
        record(Direction.OUT, Kind.REPLY, "<eddi.other@contoso.com>", "ffffffffffffffffffffffff");
        record(Direction.OUT, Kind.NOTIFY, "<eddi.notice@contoso.com>", INTEGRATION);
        assertFalse(evaluate(mail("<eddi.other@contoso.com>", List.of("bob@x.com"), List.of()), List.of()).addressed());
        assertFalse(evaluate(mail("<eddi.notice@contoso.com>", List.of("bob@x.com"), List.of()), List.of()).addressed());
        var referencesOnly = new InboundMail("<m@x.com>", false, null, List.of("<eddi.r1@contoso.com>"), new MailAddress("bob@supplier.com", null),
                null, List.of(), List.of(new MailAddress("invoices@contoso.com", null)), "s", null, "", null, false, false, null, null, null, null,
                null, 0);
        record(Direction.OUT, Kind.REPLY, "<eddi.r1@contoso.com>", INTEGRATION);
        assertFalse(evaluate(referencesOnly, List.of()).addressed(), "References alone is not a reply to EDDI");
    }

    @Test
    @DisplayName("rule 2: a mention of a target")
    void mention() {
        assertEquals(new Addressing(true, Why.MENTION),
                evaluate(mail(null, List.of("bob@x.com"), List.of("invoices@contoso.com")), List.of(new MentionHit("finance", "finance"))));
    }

    @Test
    @DisplayName("rule 3: the mailbox (or a plus-address, alias or target address) in To of a mail starting a thread")
    void toThreadStart() {
        for (String address : List.of("Invoices@Contoso.com", "invoices+urgent@contoso.com", "ap@contoso.com", "finance-agent@contoso.com")) {
            assertEquals(new Addressing(true, Why.TO_THREAD_START), evaluate(mail(null, List.of("bob@x.com", address), List.of()), List.of()),
                    address);
        }
    }

    @Test
    @DisplayName("rule 3 needs To: EDDI only in Cc of a human thread is not addressed")
    void ccOnlyNotAddressed() {
        Addressing addressing = evaluate(mail(null, List.of("carol@supplier.com"), List.of("invoices@contoso.com")), List.of());
        assertEquals(new Addressing(false, Why.NONE), addressing);
        assertEquals(Map.of("addressed", false, "why", "none"), addressing.toMap());
    }

    @Test
    @DisplayName("Outlook-style reply-all on a thread EDDI already received is not addressed")
    void outlookReplyAllNotAddressed() {
        record(Direction.IN, Kind.TRIGGER, "<parent@supplier.com>", INTEGRATION);
        assertFalse(evaluate(mail("<parent@supplier.com>", List.of("carol@supplier.com", "invoices@contoso.com"), List.of()), List.of())
                .addressed());
    }

    @Test
    @DisplayName("EDDI newly added to To of a running thread is addressed")
    void newlyAddedToTo() {
        assertEquals(new Addressing(true, Why.TO_NEW),
                evaluate(mail("<parent@supplier.com>", List.of("carol@supplier.com", "invoices@contoso.com"), List.of()), List.of()));
        // The parent reached another integration, not this one: still new here.
        record(Direction.IN, Kind.TRIGGER, "<elsewhere@supplier.com>", "ffffffffffffffffffffffff");
        assertTrue(evaluate(mail("<elsewhere@supplier.com>", List.of("invoices@contoso.com"), List.of()), List.of()).addressed());
    }

    @Test
    @DisplayName("retention edge: a parent whose ledger row expired looks new, so a reply-all to it counts as addressed")
    void retentionEdge() {
        Instant past = Instant.now().minus(Duration.ofDays(40));
        ledger.insertIfAbsent(new LedgerEntry("old", INTEGRATION, Kind.TRIGGER, Direction.IN, "<old@supplier.com>", null, null, "k-old",
                "bob@supplier.com", State.DONE, 0, null, null, null, null, past, past, past.plus(Duration.ofDays(30))));
        var replyAll = mail("<old@supplier.com>", List.of("invoices@contoso.com"), List.of());
        assertFalse(evaluate(replyAll, List.of()).addressed(), "while the row exists");
        ledger.deleteExpired(Instant.now());
        assertEquals(new Addressing(true, Why.TO_NEW), evaluate(replyAll, List.of()));
    }

    @Test
    @DisplayName("another address of the same domain, or a plus-address of another mailbox, is not EDDI's")
    void notOwn() {
        assertFalse(evaluate(mail(null, List.of("sales@contoso.com", "sales+invoices@contoso.com", "invoices+x@other.com"), List.of()), List.of())
                .addressed());
    }
}
