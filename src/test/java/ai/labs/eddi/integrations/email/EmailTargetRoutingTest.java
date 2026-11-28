/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.email;

import ai.labs.eddi.configs.channels.model.ChannelIntegrationConfiguration;
import ai.labs.eddi.configs.channels.model.ChannelTarget;
import ai.labs.eddi.engine.mail.InboundMail;
import ai.labs.eddi.engine.mail.MailAddress;
import ai.labs.eddi.engine.mail.MentionExtractor.MentionHit;
import ai.labs.eddi.integrations.email.EmailTargetRouting.RouteDecision;
import ai.labs.eddi.integrations.email.EmailTargetRouting.Source;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which target an inbound mail is routed to: the plus-tag of the integration's
 * own address, then a subject word, then the default target.
 */
class EmailTargetRoutingTest {

    private static final String ADDRESS = "invoices@contoso.com";

    private ChannelIntegrationConfiguration integration;
    private ChannelTarget urgent;
    private ChannelTarget billing;
    private ChannelTarget fallback;
    private ChannelTarget observer;

    private static ChannelTarget target(String name, String... triggers) {
        var target = new ChannelTarget();
        target.setName(name);
        target.setTargetId("agent-" + name);
        target.setTriggers(triggers.length == 0 ? null : new ArrayList<>(Arrays.asList(triggers)));
        return target;
    }

    private static InboundMail mail(String subject, List<String> to, List<String> cc) {
        return new InboundMail("<m@x>", false, null, null, new MailAddress("sender@example.com", null), null,
                to.stream().map(a -> new MailAddress(a, null)).toList(), cc.stream().map(a -> new MailAddress(a, null)).toList(), subject, null,
                null, null, false, false, null, null, null, null, null, 0);
    }

    @BeforeEach
    void setUp() {
        observer = target("observer", "urgent", "invoice");
        observer.setObserveMode(true);
        urgent = target("urgent", " URGENT ", null);
        billing = target("billing", "invoice", "refund");
        fallback = target("Fallback");
        integration = new ChannelIntegrationConfiguration();
        var targets = new ArrayList<ChannelTarget>();
        targets.add(null);
        targets.add(observer);
        targets.add(urgent);
        targets.add(billing);
        targets.add(fallback);
        integration.setTargets(targets);
        integration.setDefaultTargetName("fallback");
    }

    private ChannelTarget route(InboundMail mail) {
        return EmailTargetRouting.route(integration, ADDRESS, mail, List.of()).target();
    }

    private static MentionHit mention(String handle, String target) {
        return new MentionHit(handle, target);
    }

    @Nested
    @DisplayName("routing order (email v2 plan §7.4)")
    class Order {

        ChannelTarget finance;
        ChannelTarget legal;

        @BeforeEach
        void addressedTargets() {
            finance = target("finance", "money");
            finance.setAddresses(List.of("finance-agent@contoso.com"));
            finance.setMentions(List.of("finance"));
            legal = target("legal");
            legal.setAddresses(List.of("invoices+legal@contoso.com"));
            legal.setMentions(List.of("legal"));
            var targets = new ArrayList<>(integration.getTargets());
            targets.add(finance);
            targets.add(legal);
            integration.setTargets(targets);
        }

        @Test
        @DisplayName("a target address in To wins over one in Cc, over a plus-tag, a mention and the subject")
        void addressToFirst() {
            var mail = mail("invoice", List.of("invoices+urgent@contoso.com", "Finance-Agent@contoso.com"), List.of("invoices+legal@contoso.com"));
            RouteDecision decision = EmailTargetRouting.route(integration, ADDRESS, mail, List.of(mention("legal", "legal")));
            assertSame(finance, decision.target());
            assertEquals(Source.ADDRESS_TO, decision.source());
            assertEquals(List.of("legal", "urgent"), decision.alsoAddressed(), "every other target the mail named explicitly");
        }

        @Test
        @DisplayName("a target address in Cc wins over a plus-tag in To")
        void addressCc() {
            var mail = mail("x", List.of("invoices+urgent@contoso.com"), List.of("finance-agent@contoso.com"));
            RouteDecision decision = EmailTargetRouting.route(integration, ADDRESS, mail, List.of());
            assertSame(finance, decision.target());
            assertEquals(Source.ADDRESS_CC, decision.source());
        }

        @Test
        @DisplayName("a plus-tag wins over a mention; a mention over a subject word")
        void plusTagThenMentionThenSubject() {
            var tagged = mail("invoice", List.of("invoices+urgent@contoso.com"), List.of());
            RouteDecision byTag = EmailTargetRouting.route(integration, ADDRESS, tagged, List.of(mention("finance", "finance")));
            assertSame(urgent, byTag.target());
            assertEquals(Source.PLUS_TAG, byTag.source());

            var plain = mail("invoice", List.of(ADDRESS), List.of());
            RouteDecision byMention = EmailTargetRouting.route(integration, ADDRESS, plain,
                    List.of(mention("legal", "legal"), mention("finance", "finance")));
            assertSame(legal, byMention.target(), "mentions in the order the extractor found them");
            assertEquals(Source.MENTION, byMention.source());
            assertEquals(List.of("finance"), byMention.alsoAddressed());

            RouteDecision bySubject = EmailTargetRouting.route(integration, ADDRESS, plain, List.of());
            assertSame(billing, bySubject.target());
            assertEquals(Source.SUBJECT, bySubject.source());
            assertEquals(List.of(), bySubject.alsoAddressed(), "a subject word is not explicit addressing");
        }

        @Test
        @DisplayName("the default carries its source, and the route map names target, source and alsoAddressed")
        void defaultAndMap() {
            RouteDecision decision = EmailTargetRouting.route(integration, ADDRESS, mail("hello", List.of(ADDRESS), List.of()), List.of());
            assertSame(fallback, decision.target());
            assertEquals(Source.DEFAULT, decision.source());
            assertEquals(Map.of("target", "Fallback", "source", "default", "alsoAddressed", List.of()), decision.toMap());
            assertTrue(Source.MENTION.explicit() && Source.PLUS_TAG.explicit() && Source.ADDRESS_CC.explicit());
            assertFalse(Source.SUBJECT.explicit() || Source.DEFAULT.explicit());
        }

        @Test
        @DisplayName("an observer's address or mention routes nothing, and mention handles skip observers")
        void observersNeverRouted() {
            observer.setAddresses(List.of("watch@contoso.com"));
            observer.setMentions(List.of("watch"));
            var mail = mail("hello", List.of("watch@contoso.com"), List.of());
            assertSame(fallback, EmailTargetRouting.route(integration, ADDRESS, mail, List.of(mention("watch", "observer"))).target());
            assertEquals(Map.of("finance", "finance", "legal", "legal"), EmailTargetRouting.mentionHandles(integration));
        }
    }

    @Test
    void thePlusTagOfTheIntegrationsOwnAddressWinsOverTheSubject() {
        var mail = mail("Invoice 42", List.of("Invoices+Urgent@contoso.com"), List.of());
        assertSame(urgent, route(mail));
    }

    @Test
    void aCcRecipientCountsButATagOnAnotherAddressDoesNot() {
        assertSame(urgent,
                route(mail("hello", List.of("someone@else.com"), List.of("invoices+urgent@contoso.com"))));
        assertSame(fallback, route(mail("hello", List.of("sales+urgent@contoso.com"), List.of())));
        assertSame(fallback, route(mail("hello", List.of("invoices+urgent@other.com"), List.of())));
    }

    @Test
    void aSubjectWordRoutesWhenNoTagDoes() {
        assertSame(billing, route(mail("Re: your REFUND, please!", List.of(ADDRESS), List.of())));
        assertSame(billing,
                route(mail("refund request", List.of("invoices+nothing@contoso.com"), List.of("x"))));
    }

    @Test
    void theDefaultTargetIsMatchedByNameIgnoringCase() {
        assertSame(fallback, route(mail(null, List.of(ADDRESS), List.of())));
        integration.setDefaultTargetName("observer");
        assertNull(route(mail("", List.of(ADDRESS), List.of())), "an observer is never a default");
        integration.setDefaultTargetName(null);
        assertNull(route(mail("", List.of(ADDRESS), List.of())));
    }

    @Test
    void anIntegrationWithoutTargetsRoutesNothing() {
        assertNull(EmailTargetRouting.route(null, ADDRESS, mail("x", List.of(), List.of()), List.of()).target());
        integration.setTargets(null);
        assertNull(route(mail("x", List.of(), List.of())));
    }

    @Test
    void plusTagsNeedAUsableIntegrationAddressAndANonEmptyTag() {
        var mail = mail("x", List.of("invoices+a@contoso.com", "invoices+@contoso.com", "@contoso.com", "invoices+B@contoso.com"), List.of());
        assertEquals(Set.of("a", "b"), EmailTargetRouting.plusTags(ADDRESS, mail));
        assertEquals(Set.of(), EmailTargetRouting.plusTags(null, mail));
        assertEquals(Set.of(), EmailTargetRouting.plusTags("@contoso.com", mail));
        assertEquals(Set.of(), EmailTargetRouting.plusTags("no-at-sign", mail));
    }

    @Test
    void subjectWordsAreLowerCasedLettersAndDigitsCapped() {
        assertEquals(List.of("re", "order", "42", "überfällig"), EmailTargetRouting.subjectWords("RE: Order #42 — Überfällig"));
        assertEquals(List.of(), EmailTargetRouting.subjectWords(null));
        assertEquals(List.of(), EmailTargetRouting.subjectWords(""));
        assertEquals(List.of(), EmailTargetRouting.subjectWords("!!! ---"));
        String many = "w ".repeat(EmailTargetRouting.MAX_SUBJECT_WORDS + 10);
        assertEquals(EmailTargetRouting.MAX_SUBJECT_WORDS, EmailTargetRouting.subjectWords(many).size());
        String exactlyCapThenWord = "w ".repeat(EmailTargetRouting.MAX_SUBJECT_WORDS) + "last";
        assertEquals(EmailTargetRouting.MAX_SUBJECT_WORDS, EmailTargetRouting.subjectWords(exactlyCapThenWord).size());
    }
}
