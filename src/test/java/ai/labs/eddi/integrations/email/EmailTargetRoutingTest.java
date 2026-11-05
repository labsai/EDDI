/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.email;

import ai.labs.eddi.configs.channels.model.ChannelIntegrationConfiguration;
import ai.labs.eddi.configs.channels.model.ChannelTarget;
import ai.labs.eddi.engine.mail.InboundMail;
import ai.labs.eddi.engine.mail.MailAddress;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

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

    @Test
    void thePlusTagOfTheIntegrationsOwnAddressWinsOverTheSubject() {
        var mail = mail("Invoice 42", List.of("Invoices+Urgent@contoso.com"), List.of());
        assertSame(urgent, EmailTargetRouting.route(integration, ADDRESS, mail));
    }

    @Test
    void aCcRecipientCountsButATagOnAnotherAddressDoesNot() {
        assertSame(urgent,
                EmailTargetRouting.route(integration, ADDRESS, mail("hello", List.of("someone@else.com"), List.of("invoices+urgent@contoso.com"))));
        assertSame(fallback, EmailTargetRouting.route(integration, ADDRESS, mail("hello", List.of("sales+urgent@contoso.com"), List.of())));
        assertSame(fallback, EmailTargetRouting.route(integration, ADDRESS, mail("hello", List.of("invoices+urgent@other.com"), List.of())));
    }

    @Test
    void aSubjectWordRoutesWhenNoTagDoes() {
        assertSame(billing, EmailTargetRouting.route(integration, ADDRESS, mail("Re: your REFUND, please!", List.of(ADDRESS), List.of())));
        assertSame(billing,
                EmailTargetRouting.route(integration, ADDRESS, mail("refund request", List.of("invoices+nothing@contoso.com"), List.of("x"))));
    }

    @Test
    void theDefaultTargetIsMatchedByNameIgnoringCase() {
        assertSame(fallback, EmailTargetRouting.route(integration, ADDRESS, mail(null, List.of(ADDRESS), List.of())));
        integration.setDefaultTargetName("observer");
        assertNull(EmailTargetRouting.route(integration, ADDRESS, mail("", List.of(ADDRESS), List.of())), "an observer is never a default");
        integration.setDefaultTargetName(null);
        assertNull(EmailTargetRouting.route(integration, ADDRESS, mail("", List.of(ADDRESS), List.of())));
    }

    @Test
    void anIntegrationWithoutTargetsRoutesNothing() {
        assertNull(EmailTargetRouting.route(null, ADDRESS, mail("x", List.of(), List.of())));
        integration.setTargets(null);
        assertNull(EmailTargetRouting.route(integration, ADDRESS, mail("x", List.of(), List.of())));
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
