/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.channels.rest;

import ai.labs.eddi.configs.channels.IChannelIntegrationStore;
import ai.labs.eddi.configs.channels.model.ChannelIntegrationConfiguration;
import ai.labs.eddi.configs.channels.model.ChannelTarget;
import ai.labs.eddi.configs.channels.model.EmailChannelSettings;
import ai.labs.eddi.configs.channels.model.EmailControl;
import ai.labs.eddi.configs.channels.model.EmailHeldMailView;
import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.configs.descriptors.model.AccessLevel;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.datastore.serialization.IDescriptorStore;
import ai.labs.eddi.engine.mail.MimeMessageParser;
import ai.labs.eddi.engine.security.TurnOrigin;
import ai.labs.eddi.engine.security.spaces.ResourceAccessGuard;
import ai.labs.eddi.integrations.email.EmailChannelSupport;
import ai.labs.eddi.integrations.email.EmailHeldMailReview;
import ai.labs.eddi.integrations.email.EmailIntegrationDirectory;
import ai.labs.eddi.integrations.email.EmailSenderListAudit;
import ai.labs.eddi.integrations.email.ledger.EmailReviewState;
import ai.labs.eddi.integrations.email.ledger.IEmailMessageLedger.Direction;
import ai.labs.eddi.integrations.email.ledger.IEmailMessageLedger.Kind;
import ai.labs.eddi.integrations.email.ledger.IEmailMessageLedger.LedgerEntry;
import ai.labs.eddi.integrations.email.ledger.IEmailMessageLedger.State;
import ai.labs.eddi.integrations.email.ledger.InMemoryEmailMessageLedger;
import ai.labs.eddi.integrations.email.transport.MailTransport;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.inject.Instance;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.security.Principal;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The held-mail endpoints of {@code RestChannelIntegrationStore} (email channel
 * v3 plan §7.3): who may decide, and that {@code runAndAllow} needs EDIT and an
 * aligned pass and adds the row's own sender through the C1 sender-list path.
 */
@DisplayName("RestChannelIntegrationStore — held mail")
class RestChannelIntegrationStoreHeldMailTest {

    private static final String ID = "aabbccddee1122334455aabb";
    private static final String STRANGER = "stranger@partner.com";

    private IChannelIntegrationStore channelStore;
    private ResourceAccessGuard guard;
    private InMemoryEmailMessageLedger ledger;
    private EmailSenderListAudit audit;
    private SecurityIdentity identity;
    private RestChannelIntegrationStore sut;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        channelStore = mock(IChannelIntegrationStore.class);
        IDocumentDescriptorStore descriptors = mock(IDocumentDescriptorStore.class);
        when(descriptors.readDescriptors(eq("ai.labs.channel"), eq(""), eq(0), eq(IDescriptorStore.NO_LIMIT), eq(false))).thenReturn(List.of());
        Instance<EmailChannelSupport> support = mock(Instance.class);
        when(support.isResolvable()).thenReturn(true);
        when(support.get()).thenReturn(mock(EmailChannelSupport.class));
        guard = mock(ResourceAccessGuard.class);
        when(guard.hasAccess(ID, AccessLevel.EDIT)).thenReturn(true);
        sut = new RestChannelIntegrationStore(channelStore, descriptors, guard, support);
        ledger = new InMemoryEmailMessageLedger();
        audit = mock(EmailSenderListAudit.class);
        Instance<EmailHeldMailReview> held = mock(Instance.class);
        when(held.isResolvable()).thenReturn(true);
        when(held.get()).thenReturn(new EmailHeldMailReview(ledger, mock(MailTransport.class), mock(MimeMessageParser.class), audit));
        sut.heldMail = held;
        identity = mock(SecurityIdentity.class);
        Principal principal = () -> "carol";
        when(identity.getPrincipal()).thenReturn(principal);
        Instance<SecurityIdentity> identities = mock(Instance.class);
        when(identities.isResolvable()).thenReturn(true);
        when(identities.get()).thenReturn(identity);
        sut.securityIdentity = identities;
        when(channelStore.getCurrentResourceId(ID)).thenReturn(resourceId(3));
        when(channelStore.read(ID, 3)).thenReturn(email(null));
        when(channelStore.update(eq(ID), eq(3), any())).thenReturn(4);
    }

    private static IResourceStore.IResourceId resourceId(int version) {
        return new IResourceStore.IResourceId() {
            @Override
            public String getId() {
                return ID;
            }

            @Override
            public Integer getVersion() {
                return version;
            }
        };
    }

    private static ChannelIntegrationConfiguration email(List<String> reviewers) {
        Map<String, String> pc = new HashMap<>(Map.of("mode", "trigger", "transport", "imap", "connection", "exo", "host", "outlook.office365.com",
                "address", "invoices@contoso.com", "senderAllowlist", "*@supplier.com"));
        var config = new ChannelIntegrationConfiguration();
        config.setName("Invoices");
        config.setChannelType("email");
        config.setPlatformConfig(pc);
        config.setEmailControl(new EmailControl(null, new EmailControl.OnRefused("review", "review", null),
                reviewers == null ? null : new EmailControl.Review(null, null, null, reviewers)));
        var target = new ChannelTarget();
        target.setName("intake");
        target.setType(ChannelTarget.TargetType.AGENT);
        target.setTargetId("agent-1");
        config.setTargets(List.of(target));
        config.setDefaultTargetName("intake");
        return config;
    }

    /** A held row of the integration with this verdict. */
    private int created;

    private LedgerEntry held(String integrationId, String authVerdict) {
        // Distinct arrival times: the queue is ordered by them.
        Instant now = Instant.now().minusSeconds(100 - created++);
        LedgerEntry row = new LedgerEntry(UUID.randomUUID().toString(), integrationId, Kind.TRIGGER, Direction.IN,
                "<" + UUID.randomUUID() + "@partner.com>", null, "imap:INBOX:1:1", "in:" + UUID.randomUUID(), STRANGER, State.RECEIVED, 0, null,
                null, null, null, now, now, now.plus(Duration.ofDays(30)));
        ledger.insertIfAbsent(row);
        String reasons = EmailReviewState.alignedPass(authVerdict) ? "held:not_allowlisted" : "held:not_allowlisted+unauthenticated";
        ledger.transitionReview(row.id(), State.RECEIVED, State.HELD, reasons, authVerdict, "hash", now.plus(Duration.ofDays(7)), reasons, now);
        return ledger.findById(row.id()).orElseThrow();
    }

    private static EmailHeldMailView.DecisionRequest decision(String decision) {
        return new EmailHeldMailView.DecisionRequest(decision, "known partner");
    }

    @Test
    @DisplayName("the queue lists held rows with what each decision would do")
    void queue() {
        LedgerEntry aligned = held(ID, "aligned-pass");
        LedgerEntry forged = held(ID, "fail");
        held("ffffffffffffffffffffffff", "aligned-pass");

        var queue = sut.readHeldEmail(ID, 50);

        assertEquals(List.of(aligned.id(), forged.id()), queue.items().stream().map(EmailHeldMailView.Item::rowId).toList());
        assertTrue(queue.items().get(0).runAndAllowOffered());
        assertTrue(queue.items().get(1).runsAnonymously());
        assertEquals(List.of("not_allowlisted", "unauthenticated"), queue.items().get(1).heldFor());
    }

    @Test
    @DisplayName("runAndAllow adds the row's own sender through the sender-list path, then releases the mail")
    void runAndAllow() throws Exception {
        LedgerEntry row = held(ID, "aligned-pass");

        Response response = sut.decideHeldEmail(ID, row.id(), decision("runAndAllow"));

        assertEquals(200, response.getStatus());
        var result = (EmailHeldMailView.DecisionResult) response.getEntity();
        assertEquals(4, result.allowlistVersion());
        ArgumentCaptor<ChannelIntegrationConfiguration> saved = ArgumentCaptor.forClass(ChannelIntegrationConfiguration.class);
        verify(channelStore).update(eq(ID), eq(3), saved.capture());
        assertEquals("*@supplier.com," + STRANGER, saved.getValue().getPlatformConfig().get("senderAllowlist"));
        LedgerEntry after = ledger.findById(row.id()).orElseThrow();
        assertEquals(State.RECEIVED, after.state());
        assertEquals("released:runAndAllow:not_allowlisted", after.review());
        verify(audit).heldMailDecided(eq(ID), eq(row.id()), eq("runAndAllow"), any(), eq("aligned-pass"), eq(STRANGER), eq("known partner"));
    }

    @Test
    @DisplayName("runAndAllow needs an aligned pass: a forged From is refused, the allowlist untouched, the mail still held")
    void runAndAllowNeedsAlignedPass() throws Exception {
        LedgerEntry row = held(ID, "fail");

        assertThrows(BadRequestException.class, () -> sut.decideHeldEmail(ID, row.id(), decision("runAndAllow")));

        verify(channelStore, never()).update(anyString(), anyInt(), any());
        assertEquals(State.HELD, ledger.findById(row.id()).orElseThrow().state());
    }

    @Test
    @DisplayName("an eddi-approver may run once and discard, but runAndAllow needs EDIT")
    void approverCannotAllow() throws Exception {
        when(guard.hasAccess(ID, AccessLevel.EDIT)).thenReturn(false);
        when(identity.hasRole("eddi-approver")).thenReturn(true);
        doThrow(new ForbiddenException("no edit")).when(guard).requireAccess(eq(ID), eq(AccessLevel.EDIT), any());
        LedgerEntry allow = held(ID, "aligned-pass");
        LedgerEntry once = held(ID, "aligned-pass");

        assertThrows(ForbiddenException.class, () -> sut.decideHeldEmail(ID, allow.id(), decision("runAndAllow")));
        assertEquals(State.HELD, ledger.findById(allow.id()).orElseThrow().state());
        verify(channelStore, never()).update(anyString(), anyInt(), any());

        assertEquals(200, sut.decideHeldEmail(ID, once.id(), decision("runOnce")).getStatus());
        assertEquals("released:runOnce:not_allowlisted", ledger.findById(once.id()).orElseThrow().review());
    }

    @Test
    @DisplayName("reviewers: a configured list replaces the approver default; a role or an id on it may decide, nobody else")
    void reviewers() throws Exception {
        when(guard.hasAccess(ID, AccessLevel.EDIT)).thenReturn(false);
        when(identity.hasRole("eddi-approver")).thenReturn(true);
        when(channelStore.read(ID, 3)).thenReturn(email(List.of("role:mail-desk", "dave")));
        LedgerEntry row = held(ID, "fail");

        assertThrows(ForbiddenException.class, () -> sut.decideHeldEmail(ID, row.id(), decision("discard")));
        when(identity.hasRole("mail-desk")).thenReturn(true);
        assertEquals(200, sut.decideHeldEmail(ID, row.id(), decision("discard")).getStatus());
        LedgerEntry after = ledger.findById(row.id()).orElseThrow();
        assertEquals(State.REFUSED, after.state());
        assertEquals(EmailReviewState.REVIEW_DISCARDED, after.lastError());
        assertTrue(sut.mayReview(List.of("carol")), "an id on the list");
        assertFalse(sut.mayReview(List.of("dave")), "another id is not");
    }

    @Test
    @DisplayName("decided once: a second decision is a 409; another integration's row and an unknown decision are refused")
    void decidedOnce() throws Exception {
        LedgerEntry row = held(ID, "aligned-pass");
        LedgerEntry foreign = held("ffffffffffffffffffffffff", "aligned-pass");

        assertEquals(200, sut.decideHeldEmail(ID, row.id(), decision("runOnce")).getStatus());
        assertThrows(NotFoundException.class, () -> sut.decideHeldEmail(ID, row.id(), decision("discard")), "no longer held");
        assertThrows(NotFoundException.class, () -> sut.decideHeldEmail(ID, foreign.id(), decision("discard")));
        assertThrows(BadRequestException.class, () -> sut.decideHeldEmail(ID, foreign.id(), decision("approve")));
        assertEquals(State.HELD, ledger.findById(foreign.id()).orElseThrow().state());
    }

    @Test
    @DisplayName("a race lost after the allowlist write is a 409 that says the sender was allowed")
    void raceAfterAllowlist() throws Exception {
        LedgerEntry row = held(ID, "aligned-pass");
        when(channelStore.update(eq(ID), eq(3), any())).thenAnswer(invocation -> {
            // Another reviewer discards it while the allowlist is written.
            ledger.transitionReview(row.id(), State.HELD, State.REFUSED, "discarded:not_allowlisted", null, null, null, "review_discarded",
                    Instant.now());
            return 4;
        });

        var refused = assertThrows(WebApplicationException.class, () -> sut.decideHeldEmail(ID, row.id(), decision("runAndAllow")));

        assertEquals(409, refused.getResponse().getStatus());
        assertTrue(String.valueOf(refused.getResponse().getEntity()).contains("added to the allowlist"));
    }

    @Test
    @DisplayName("a conversation an email started cannot decide held mail (origin=email, v3 §5.3)")
    void taintedTurnCannotDecide() throws Exception {
        LedgerEntry row = held(ID, "aligned-pass");

        assertThrows(ForbiddenException.class, () -> TurnOrigin.callWith(TurnOrigin.EMAIL,
                () -> sut.decideHeldEmail(ID, row.id(), decision("runOnce"))));

        assertEquals(State.HELD, ledger.findById(row.id()).orElseThrow().state());
    }

    @Test
    @DisplayName("runAndAllow by an approver without EDIT is a 403 before anything else is looked at - also for a forged sender")
    void approverForgedRunAndAllowIsForbidden() throws Exception {
        when(guard.hasAccess(ID, AccessLevel.EDIT)).thenReturn(false);
        when(identity.hasRole("eddi-approver")).thenReturn(true);
        doThrow(new ForbiddenException("no edit")).when(guard).requireAccess(eq(ID), eq(AccessLevel.EDIT), any());
        LedgerEntry forged = held(ID, "fail");

        assertThrows(ForbiddenException.class, () -> sut.decideHeldEmail(ID, forged.id(), decision("runAndAllow")));
        assertEquals(State.HELD, ledger.findById(forged.id()).orElseThrow().state());
    }

    @Test
    @DisplayName("the cross-integration read lists the queues the caller reviews, and only non-empty ones")
    @SuppressWarnings("unchecked")
    void allHeld() throws Exception {
        String other = "ffffffffffffffffffffffff";
        String empty = "eeeeeeeeeeeeeeeeeeeeeeee";
        held(ID, "aligned-pass");
        held(other, "fail");
        when(guard.hasAccess(ID, AccessLevel.EDIT)).thenReturn(false);
        when(guard.hasAccess(other, AccessLevel.EDIT)).thenReturn(false);
        when(guard.hasAccess(empty, AccessLevel.EDIT)).thenReturn(true);
        when(identity.hasRole("eddi-approver")).thenReturn(true);
        var directory = mock(EmailIntegrationDirectory.class);
        var withReviewers = email(List.of("dave"));
        when(directory.scan())
                .thenReturn(List.of(new EmailIntegrationDirectory.EmailIntegration(ID, email(null), EmailChannelSettings.from(email(null))),
                        new EmailIntegrationDirectory.EmailIntegration(other, withReviewers, EmailChannelSettings.from(withReviewers)),
                        new EmailIntegrationDirectory.EmailIntegration(empty, email(null), EmailChannelSettings.from(email(null)))));
        Instance<EmailIntegrationDirectory> instance = mock(Instance.class);
        when(instance.isResolvable()).thenReturn(true);
        when(instance.get()).thenReturn(directory);
        sut.emailDirectory = instance;

        var queues = sut.readAllHeldEmail(50);

        assertEquals(List.of(ID), queues.stream().map(EmailHeldMailView.Queue::integrationId).toList(),
                "the approver default reviews the first; the second names its own reviewers; the third holds nothing");
    }
}