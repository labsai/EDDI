/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.email;

import ai.labs.eddi.engine.attachments.IAttachmentStore;
import ai.labs.eddi.engine.mail.E2eMailDns;
import ai.labs.eddi.engine.net.E2eMailServers;
import ai.labs.eddi.engine.net.E2eMailSocketConnector;
import ai.labs.eddi.integration.BaseIntegrationIT;
import ai.labs.eddi.integration.IntegrationTestProfile;
import ai.labs.eddi.integrations.email.ledger.IEmailMessageLedger;
import ai.labs.eddi.integrations.email.ledger.IEmailMessageLedger.Direction;
import ai.labs.eddi.integrations.email.ledger.IEmailMessageLedger.LedgerEntry;
import ai.labs.eddi.integrations.email.ledger.IEmailMessageLedger.State;
import com.icegreen.greenmail.user.GreenMailUser;
import com.icegreen.greenmail.util.DummySSLServerSocketFactory;
import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.ServerSetup;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import jakarta.mail.Flags;
import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.Session;
import jakarta.mail.Store;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLContext;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.oneOf;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The email channel in the real application: REST save, the real poll schedule
 * fired through {@code ScheduleFireExecutor}, real IMAP against GreenMail, the
 * real {@code ConversationService} running a deterministic agent (behavior
 * rules + output, no LLM) on MongoDB, real DKIM verification and real SMTP
 * replies — every bean the production one except two:
 * <ul>
 * <li>{@link E2eMailSocketConnector}: the production author socket policy, run
 * in full on every call, over a DNS answer that maps the {@code .test} mail
 * hosts to a public documentation address; only an endpoint that passed is
 * re-pinned to the loopback GreenMail port (TLS and host-name verification on,
 * against a certificate for those names).</li>
 * <li>{@link E2eMailDns}: the production {@code MailDnsResolver} over an
 * offline TXT source that publishes the DKIM test key.</li>
 * </ul>
 */
@QuarkusTest
@TestProfile(EmailFullApplicationIT.Profile.class)
public class EmailFullApplicationIT extends BaseIntegrationIT {

    /** The IT's profile: the integration-test settings plus the two mail seams. */
    public static class Profile extends IntegrationTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            Map<String, String> overrides = new HashMap<>(super.getConfigOverrides());
            overrides.put("quarkus.http.test-port", "8471");
            overrides.put("quarkus.http.port", "8471");
            return overrides;
        }

        @Override
        public Set<Class<?>> getEnabledAlternatives() {
            return Set.of(E2eMailSocketConnector.class, E2eMailDns.class);
        }
    }

    private static final String PASSWORD = "fixturefixture-e2e";
    private static final String INVOICES = "invoices@eddi.test";
    private static final String SUPPORT = "support@eddi.test";
    private static final String SENDER_DOMAIN = "sender.test";
    private static final String SELECTOR = "e2e";
    private static final String DISCLOSURE = "This reply was written by an AI assistant.";

    private static GreenMail greenMail;
    private static SSLContext previousDefault;
    private static GreenMailUser invoicesUser;
    private static GreenMailUser supportUser;

    private static ResourceId agent;
    private static String triggerId;
    private static String conversationId;
    private static String triggerWarnings;
    private static String conversationWarnings;

    @Inject
    IEmailMessageLedger ledger;

    @Inject
    IAttachmentStore attachments;

    @BeforeAll
    static void startMailServers() throws Exception {
        System.setProperty(DummySSLServerSocketFactory.GREENMAIL_KEYSTORE_FILE_PROPERTY, E2eMailServers.keystore().toString());
        System.setProperty(DummySSLServerSocketFactory.GREENMAIL_KEYSTORE_PASSWORD_PROPERTY, E2eMailServers.PASSWORD);
        greenMail = new GreenMail(new ServerSetup[]{new ServerSetup(0, "127.0.0.1", ServerSetup.PROTOCOL_IMAPS),
                new ServerSetup(0, "127.0.0.1", ServerSetup.PROTOCOL_SMTPS)});
        greenMail.start();
        E2eMailServers.imapsPort = greenMail.getImaps().getPort();
        E2eMailServers.smtpsPort = greenMail.getSmtps().getPort();
        // Angus Mail layers TLS with the JVM default factory: trust the test CA in
        // addition to the JDK's, verification left on.
        previousDefault = SSLContext.getDefault();
        SSLContext.setDefault(E2eMailServers.clientContext());
        invoicesUser = greenMail.setUser(INVOICES, INVOICES, PASSWORD);
        supportUser = greenMail.setUser(SUPPORT, SUPPORT, PASSWORD);
        E2eMailDns.publishDomain(SENDER_DOMAIN, SELECTOR);
    }

    @AfterAll
    static void stopMailServers() throws Exception {
        if (greenMail != null) {
            greenMail.stop();
        }
        if (previousDefault != null) {
            SSLContext.setDefault(previousDefault);
        }
        E2eMailServers.imapsPort = -1;
        E2eMailServers.smtpsPort = -1;
    }

    @BeforeEach
    void setUpOnce() throws Exception {
        if (agent != null) {
            return;
        }
        agent = deployMailAgent();
        given().contentType(ContentType.JSON)
                .body("{\"value\": \"" + PASSWORD + "\", \"allowedAgents\": [\"*\"], \"allowedHosts\": [\"" + E2eMailServers.IMAP_HOST + "\", \""
                        + E2eMailServers.SMTP_HOST + "\"]}")
                .put("/secretstore/secrets/default/e2e-mailbox-password").then().statusCode(oneOf(200, 201, 204));
        createConnection("e2e-invoices", INVOICES, "[\"imaps://" + E2eMailServers.IMAP_HOST + "\"]");
        createConnection("e2e-support", SUPPORT,
                "[\"imaps://" + E2eMailServers.IMAP_HOST + "\", \"smtps://" + E2eMailServers.SMTP_HOST + "\"]");

        Response trigger = given().contentType(ContentType.JSON).body(channel("E2E invoices", Map.of("mode", "trigger", "transport", "imap",
                "connection", "e2e-invoices", "host", E2eMailServers.IMAP_HOST, "address", INVOICES, "processedAction", "move:Processed",
                "requireAuthentication", "false", "attachments", "store", "attachmentMimeTypes", "application/pdf"))).post("/channelstore/channels");
        assertEquals(201, trigger.statusCode(), trigger.asString());
        triggerId = extractResourceId(trigger.getHeader("location")).id();
        triggerWarnings = trigger.getHeader("X-Eddi-Warnings");

        Response conversation = given().contentType(ContentType.JSON).body(channel("E2E support", Map.of("mode", "conversation", "transport", "imap",
                "connection", "e2e-support", "host", E2eMailServers.IMAP_HOST, "smtpHost", E2eMailServers.SMTP_HOST, "smtpPort", "465", "address",
                SUPPORT, "fromName", "EDDI Support", "processedAction", "flag"))).post("/channelstore/channels");
        assertEquals(201, conversation.statusCode(), conversation.asString());
        conversationId = extractResourceId(conversation.getHeader("location")).id();
        conversationWarnings = conversation.getHeader("X-Eddi-Warnings");

        // The first poll of each mailbox sets its cursor to the end of the folder.
        firePoll(triggerId);
        firePoll(conversationId);
    }

    // ==================== A1: trigger mode ====================

    @Test
    @DisplayName("A1 trigger mode: REST save, scheduled poll, real turn with mail context, PDF stored, row DONE, mail moved, nothing sent")
    void triggerMode() throws Exception {
        assertNotNull(triggerWarnings, "a trigger integration without authentication or allowlist warns");
        assertTrue(triggerWarnings.contains("email.open-trigger"), triggerWarnings);
        assertNotNull(scheduleId(triggerId), "the poll schedule email-poll:<id> exists");

        String messageId = "<" + UUID.randomUUID() + "@vendor.test>";
        String pdf = "%PDF-1.4\n1 0 obj << /Type /Catalog >> endobj\ntrailer << /Root 1 0 R >>\n%%EOF\n";
        invoicesUser.deliver(parse(mail("erin@vendor.test", INVOICES, "Invoice 4711", "Dear team hello again the invoice is attached", messageId,
                List.of(), new String[]{"invoice-4711.pdf", "application/pdf", pdf})));
        firePoll(triggerId);

        LedgerEntry row = await(() -> ledger.findByMessage(triggerId, Direction.IN, messageId).filter(r -> r.state() == State.DONE), "row DONE");
        assertNotNull(row.conversationId(), String.valueOf(row));

        Response log = given().get("agents/" + row.conversationId() + "?returnDetailed=true");
        assertEquals(200, log.statusCode());
        String body = log.asString();
        assertTrue(body.contains("Email from erin@vendor.test: Invoice 4711"), "the mail is the turn's input: " + body);
        assertTrue(body.contains("Thank you for your mail."), "the agent ran its rules on it: " + body);
        assertTrue(body.contains("\"mail\"") || body.contains("context:mail"), "the mail context is on the step: " + body);
        assertTrue(body.contains("invoice-4711.pdf"), body);

        var stored = attachments.listByConversation(row.conversationId());
        assertEquals(1, stored.size(), "the PDF is in the attachment store");

        assertTrue(isMarked(INVOICES, messageId), "the mail is flagged or moved");
        assertEquals(0, mailsFrom(INVOICES), "trigger mode sends nothing");
        assertTrue(ledger.findByRecipient("erin@vendor.test").stream().noneMatch(r -> r.direction() == Direction.OUT), "no outbound row");

        // An auto-reply is refused without a turn.
        String autoId = "<" + UUID.randomUUID() + "@vendor.test>";
        invoicesUser.deliver(parse(mail("erin@vendor.test", INVOICES, "Out of office", "Dear team hello I am away", autoId,
                List.of("Auto-Submitted: auto-replied"))));
        firePoll(triggerId);
        LedgerEntry refused = await(() -> ledger.findByMessage(triggerId, Direction.IN, autoId).filter(r -> r.state() == State.REFUSED),
                "auto-reply refused");
        assertEquals(null, refused.conversationId(), "no conversation for an auto-reply: " + refused);
        Thread.sleep(2000);
        assertEquals(0, mailsFrom(INVOICES), "still nothing sent");
    }

    // ==================== A2: conversation mode ====================

    @Test
    @DisplayName("A2 conversation mode: DKIM-signed mail answered in thread; reply continues; third party separate; unsigned and tampered get nothing")
    void conversationMode() throws Exception {
        assertTrue(conversationWarnings == null || !conversationWarnings.contains("email.open-trigger"), String.valueOf(conversationWarnings));
        String alice = "alice@" + SENDER_DOMAIN;
        String bob = "bob@" + SENDER_DOMAIN;

        String first = "<" + UUID.randomUUID() + "@" + SENDER_DOMAIN + ">";
        supportUser.deliver(
                parse(signed(
                        mail(alice, SUPPORT, "Order 4711", "Dear team hello where is my order", first, List.of("References: <root@sender.test>")))));
        firePoll(conversationId);
        MimeMessage reply = awaitMail(alice, 1).get(0);
        assertEquals("Re: Order 4711", reply.getSubject());
        assertEquals(first, reply.getHeader("In-Reply-To")[0]);
        String references = String.join(" ", reply.getHeader("References")).replaceAll("\\s+", " ");
        assertEquals("<root@sender.test> " + first, references);
        assertEquals("auto-replied", reply.getHeader("Auto-Submitted")[0]);
        assertTrue(reply.getFrom()[0].toString().contains(SUPPORT), Arrays.toString(reply.getFrom()));
        String text = reply.getContent().toString();
        assertTrue(text.contains("Thank you for your mail."), text);
        assertTrue(text.contains(DISCLOSURE), text);
        LedgerEntry firstRow = ledger.findByMessage(conversationId, Direction.IN, first).orElseThrow();
        String eddiId = reply.getMessageID();
        assertTrue(ledger.findByMessage(conversationId, Direction.OUT, eddiId).isPresent(), "the ledger records the Message-ID it sent");

        // Alice answers EDDI's reply: same conversation.
        String second = "<" + UUID.randomUUID() + "@" + SENDER_DOMAIN + ">";
        supportUser
                .deliver(parse(signed(mail(alice, SUPPORT, "Re: Order 4711", "Dear team hello again", second, List.of("In-Reply-To: " + eddiId)))));
        firePoll(conversationId);
        awaitMail(alice, 2);
        LedgerEntry secondRow = ledger.findByMessage(conversationId, Direction.IN, second).orElseThrow();
        assertEquals(firstRow.conversationId(), secondRow.conversationId(), "the answer to EDDI's Message-ID continues the conversation");

        // Bob quoting the same Message-ID: his own conversation.
        String bobs = "<" + UUID.randomUUID() + "@" + SENDER_DOMAIN + ">";
        supportUser.deliver(parse(signed(mail(bob, SUPPORT, "Re: Order 4711", "Dear team hello from bob", bobs,
                List.of("In-Reply-To: " + eddiId, "References: " + eddiId)))));
        firePoll(conversationId);
        awaitMail(bob, 1);
        LedgerEntry bobRow = ledger.findByMessage(conversationId, Direction.IN, bobs).orElseThrow();
        assertNotEquals(firstRow.conversationId(), bobRow.conversationId(), "a third party with the Message-ID gets its own conversation");

        // Unsigned, and signed-then-tampered: refused, no reply.
        String unsigned = "<" + UUID.randomUUID() + "@" + SENDER_DOMAIN + ">";
        supportUser.deliver(parse(mail(alice, SUPPORT, "Unsigned", "hello unsigned", unsigned, List.of())));
        String tampered = "<" + UUID.randomUUID() + "@" + SENDER_DOMAIN + ">";
        byte[] signedBytes = signed(mail(alice, SUPPORT, "Tampered", "hello original", tampered, List.of()));
        byte[] tamperedBytes = new String(signedBytes, StandardCharsets.ISO_8859_1).replace("hello original", "hello modified")
                .getBytes(StandardCharsets.ISO_8859_1);
        supportUser.deliver(parse(tamperedBytes));
        firePoll(conversationId);
        LedgerEntry unsignedRow = await(() -> ledger.findByMessage(conversationId, Direction.IN, unsigned).filter(r -> r.state() == State.REFUSED),
                "unsigned refused");
        LedgerEntry tamperedRow = await(() -> ledger.findByMessage(conversationId, Direction.IN, tampered).filter(r -> r.state() == State.REFUSED),
                "tampered refused");
        assertEquals(null, unsignedRow.conversationId());
        assertEquals(null, tamperedRow.conversationId());
        Thread.sleep(3000);
        assertEquals(2, mailsTo(alice).size(), "no reply to unauthenticated mail");
    }

    // ==================== A3: HITL in the thread ====================

    @Test
    @DisplayName("A3 HITL: a pausing turn sends the in-thread notice; resume sends the continuation")
    void hitlInThread() throws Exception {
        String dave = "dave@" + SENDER_DOMAIN;
        String id = "<" + UUID.randomUUID() + "@" + SENDER_DOMAIN + ">";
        supportUser.deliver(parse(signed(mail(dave, SUPPORT, "Account", "please delete my account", id, List.of()))));
        firePoll(conversationId);
        MimeMessage notice = awaitMail(dave, 1).get(0);
        String noticeText = notice.getContent().toString();
        assertTrue(noticeText.contains("Account deletion needs a supervisor"), "the pause reason: " + noticeText);
        assertFalse(noticeText.contains("Your account deletion was carried out."), noticeText);
        assertEquals(id, notice.getHeader("In-Reply-To")[0]);

        LedgerEntry row = ledger.findByMessage(conversationId, Direction.IN, id).orElseThrow();
        String conversation = row.conversationId();
        await(() -> "AWAITING_HUMAN".equals(given().get("agents/" + conversation + "/approval-status").jsonPath().getString("state"))
                ? Optional.of(true)
                : Optional.empty(), "AWAITING_HUMAN");
        given().contentType(ContentType.JSON).body("{\"verdict\": \"APPROVED\", \"note\": \"ok\"}").post("agents/" + conversation + "/resume")
                .then().statusCode(200);
        MimeMessage continuation = awaitMail(dave, 2).get(1);
        String continuationText = continuation.getContent().toString();
        assertTrue(continuationText.contains("Your account deletion was carried out."), continuationText);
        assertTrue(continuationText.contains(DISCLOSURE), continuationText);
        assertEquals("auto-replied", continuation.getHeader("Auto-Submitted")[0]);
    }

    // ==================== A4: bounce ====================

    @Test
    @DisplayName("A4 bounce: a permanent DSN for a reply suppresses the address; the next mail gets no reply")
    void bounce() throws Exception {
        String carol = "carol@" + SENDER_DOMAIN;
        String id = "<" + UUID.randomUUID() + "@" + SENDER_DOMAIN + ">";
        supportUser.deliver(parse(signed(mail(carol, SUPPORT, "Question", "Dear team hello there", id, List.of()))));
        firePoll(conversationId);
        String eddiId = awaitMail(carol, 1).get(0).getMessageID();

        supportUser.deliver(parse(dsn(eddiId, carol)));
        firePoll(conversationId);
        await(() -> {
            List<?> list = given().get("/channelstore/channels/" + conversationId + "/email-suppressions").jsonPath().getList("$");
            return list != null && !list.isEmpty() ? Optional.of(list) : Optional.empty();
        }, "suppression listed");
        Response suppressions = given().get("/channelstore/channels/" + conversationId + "/email-suppressions");
        assertEquals(200, suppressions.statusCode());
        assertFalse(suppressions.asString().contains(carol), "the list holds hashes, not the address: " + suppressions.asString());
        assertEquals(State.FAILED, ledger.findByMessage(conversationId, Direction.OUT, eddiId).orElseThrow().state());

        String next = "<" + UUID.randomUUID() + "@" + SENDER_DOMAIN + ">";
        supportUser.deliver(parse(signed(mail(carol, SUPPORT, "Question again", "Dear team hello once more", next, List.of()))));
        firePoll(conversationId);
        await(() -> ledger.findByMessage(conversationId, Direction.IN, next).filter(r -> r.state() == State.DONE), "turn ran");
        Thread.sleep(3000);
        assertEquals(1, mailsTo(carol).size(), "nothing further reached the suppressed address");
        assertTrue(ledger.findByRecipient(carol).stream().anyMatch(r -> r.direction() == Direction.OUT && r.state() == State.SUPPRESSED),
                "the second reply row is SUPPRESSED: " + ledger.findByRecipient(carol));
    }

    // ==================== helpers ====================

    /**
     * A deterministic agent. The regular dictionary parser splits on spaces only,
     * so the mail bodies put their keyword between spaces: the default input
     * template joins the first body word to its {@code -----} separator line.
     */
    private ResourceId deployMailAgent() throws Exception {
        String dictionary = """
                {"words": [
                  {"word": "hello", "expressions": "greeting(hello)", "frequency": 0},
                  {"word": "delete", "expressions": "delete_account(delete)", "frequency": 0}
                ], "phrases": []}""";
        String rules = """
                {"behaviorGroups": [{"name": "Mail", "behaviorRules": [
                  {"name": "Pause_For_Deletion", "actions": ["ask_supervisor", "PAUSE_CONVERSATION"], "conditions": [
                    {"type": "inputmatcher", "configs": {"expressions": "delete_account(*)", "occurrence": "currentStep"}, "conditions": []}]},
                  {"name": "Greeting", "actions": ["greet"], "conditions": [
                    {"type": "inputmatcher", "configs": {"expressions": "greeting(*)", "occurrence": "currentStep"}, "conditions": []}]}
                ]}]}""";
        String output = """
                {"outputSet": [
                  {"action": "greet", "timesOccurred": 0, "outputs": [{"valueAlternatives": [{"type": "text", "text": "Thank you for your mail."}]}]},
                  {"action": "ask_supervisor", "timesOccurred": 0, "outputs": [{"valueAlternatives": [{"type": "text", "text": "Your account deletion was carried out."}]}]}
                ]}""";
        String dictionaryLocation = createResource(dictionary, "/dictionarystore/dictionaries");
        String rulesLocation = createResource(rules, "/rulestore/rulesets");
        String outputLocation = createResource(output, "/outputstore/outputsets");
        String workflow = String.format("""
                {"workflowSteps": [
                  {"type": "eddi://ai.labs.parser", "config": {}, "extensions": {
                    "dictionaries": [{"type": "eddi://ai.labs.parser.dictionaries.regular", "config": {"uri": "%s"}}], "corrections": []}},
                  {"type": "eddi://ai.labs.rules", "config": {"uri": "%s"}},
                  {"type": "eddi://ai.labs.output", "config": {"uri": "%s"}},
                  {"type": "eddi://ai.labs.templating", "config": {}}
                ]}""", dictionaryLocation, rulesLocation, outputLocation);
        String workflowLocation = createResource(workflow, "/workflowstore/workflows");
        String agentBody = String.format("""
                {"packages": ["%s"], "hitlConfig": {"pauseReason": "Account deletion needs a supervisor"}}""", workflowLocation);
        ResourceId id = extractResourceId(createResource(agentBody, "/agentstore/agents"));
        deployAgent(id.id(), id.version());
        return id;
    }

    private void createConnection(String name, String username, String allowlist) {
        Response response = given().contentType(ContentType.JSON).body("""
                {"name": "%s", "authType": "BASIC", "binding": "SERVICE",
                 "staticAuth": {"headerName": "Authorization", "username": "%s", "passwordRef": "${vault:e2e-mailbox-password}"},
                 "baseUrlAllowlist": %s}""".formatted(name, username, allowlist)).post("/connectionstore/connections");
        assertEquals(201, response.statusCode(), response.asString());
    }

    private String channel(String name, Map<String, String> platformConfig) {
        StringBuilder config = new StringBuilder();
        platformConfig.forEach((k, v) -> config.append(config.isEmpty() ? "" : ", ").append('"').append(k).append("\": \"").append(v).append('"'));
        return """
                {"name": "%s", "channelType": "email", "platformConfig": {%s},
                 "targets": [{"name": "mail", "type": "AGENT", "targetId": "%s"}], "defaultTargetName": "mail"}"""
                .formatted(name, config, agent.id());
    }

    private String scheduleId(String integrationId) {
        List<Map<String, Object>> schedules = given().get("/schedulestore/schedules?limit=1000").jsonPath().getList("$");
        return schedules.stream().filter(s -> ("email-poll:" + integrationId).equals(s.get("name"))).map(s -> (String) s.get("id")).findFirst()
                .orElse(null);
    }

    /**
     * Fires the poll schedule the way the scheduler does: claimed, through
     * ScheduleFireExecutor.
     */
    private void firePoll(String integrationId) throws InterruptedException {
        String schedule = scheduleId(integrationId);
        assertNotNull(schedule, "poll schedule of " + integrationId);
        for (int attempt = 0; attempt < 60; attempt++) {
            Response fired = given().post("/schedulestore/schedules/" + schedule + "/fire");
            if (fired.statusCode() == 200) {
                return;
            }
            assertEquals(409, fired.statusCode(), fired.asString()); // the background poller holds the claim
            Thread.sleep(500);
        }
        throw new AssertionError("could not fire " + schedule);
    }

    private static <T> T await(Supplier<Optional<T>> condition, String what) throws InterruptedException {
        for (int i = 0; i < 120; i++) {
            Optional<T> value = condition.get();
            if (value.isPresent()) {
                return value.get();
            }
            Thread.sleep(250);
        }
        throw new AssertionError("timed out waiting for: " + what);
    }

    private static List<MimeMessage> awaitMail(String recipient, int count) throws Exception {
        for (int i = 0; i < 120; i++) {
            List<MimeMessage> found = mailsTo(recipient);
            if (found.size() >= count) {
                return found;
            }
            Thread.sleep(250);
        }
        throw new AssertionError("expected " + count + " mail(s) to " + recipient + ", got " + mailsTo(recipient).size());
    }

    private static List<MimeMessage> mailsTo(String recipient) throws Exception {
        List<MimeMessage> found = new ArrayList<>();
        for (MimeMessage message : greenMail.getReceivedMessages()) {
            var to = message.getRecipients(Message.RecipientType.TO);
            if (to != null && Arrays.stream(to).anyMatch(a -> a.toString().contains(recipient))) {
                found.add(message);
            }
        }
        return found;
    }

    private static long mailsFrom(String sender) throws Exception {
        long count = 0;
        for (MimeMessage message : greenMail.getReceivedMessages()) {
            var from = message.getFrom();
            if (from != null && Arrays.stream(from).anyMatch(a -> a.toString().contains(sender))) {
                count++;
            }
        }
        return count;
    }

    /**
     * Whether the mail is in Processed or carries $EddiProcessed, read over IMAPS.
     */
    private static boolean isMarked(String mailbox, String messageId) throws Exception {
        Properties properties = new Properties();
        properties.setProperty("mail.imaps.ssl.checkserveridentity", "true");
        Store store = Session.getInstance(properties).getStore("imaps");
        store.connect("localhost", greenMail.getImaps().getPort(), mailbox, PASSWORD);
        try {
            for (String name : List.of("Processed", "INBOX")) {
                Folder folder = store.getFolder(name);
                if (!folder.exists()) {
                    continue;
                }
                folder.open(Folder.READ_ONLY);
                try {
                    for (Message message : folder.getMessages()) {
                        String[] ids = message.getHeader("Message-ID");
                        if (ids != null && messageId.equals(ids[0])) {
                            boolean flagged = message.getFlags().contains("$EddiProcessed");
                            boolean seen = message.getFlags().contains(Flags.Flag.SEEN);
                            assertFalse(seen, "EDDI never sets \\Seen");
                            if ("Processed".equals(name) || flagged) {
                                return true;
                            }
                        }
                    }
                } finally {
                    folder.close(false);
                }
            }
            return false;
        } finally {
            store.close();
        }
    }

    private static MimeMessage parse(byte[] raw) throws Exception {
        return new MimeMessage(Session.getInstance(new Properties()), new ByteArrayInputStream(raw));
    }

    private static byte[] signed(byte[] raw) {
        return E2eMailDns.sign(raw, SENDER_DOMAIN, SELECTOR);
    }

    /** A CRLF message; attachments are {filename, type, content as ISO-8859-1}. */
    private static byte[] mail(String from, String to, String subject, String body, String messageId, List<String> headers,
                               String[]... attachments) {
        StringBuilder out = new StringBuilder();
        out.append("From: ").append(from).append("\r\n");
        out.append("To: ").append(to).append("\r\n");
        out.append("Subject: ").append(subject).append("\r\n");
        out.append("Date: ").append(DateTimeFormatter.RFC_1123_DATE_TIME.format(ZonedDateTime.now(ZoneOffset.UTC))).append("\r\n");
        out.append("Message-ID: ").append(messageId).append("\r\n");
        for (String header : headers) {
            out.append(header).append("\r\n");
        }
        out.append("MIME-Version: 1.0\r\n");
        if (attachments.length == 0) {
            out.append("Content-Type: text/plain; charset=utf-8\r\n\r\n").append(body).append("\r\n");
            return out.toString().getBytes(StandardCharsets.UTF_8);
        }
        String boundary = "b-" + UUID.randomUUID();
        out.append("Content-Type: multipart/mixed; boundary=\"").append(boundary).append("\"\r\n\r\n");
        out.append("--").append(boundary).append("\r\nContent-Type: text/plain; charset=utf-8\r\n\r\n").append(body).append("\r\n");
        for (String[] attachment : attachments) {
            out.append("--").append(boundary).append("\r\nContent-Type: ").append(attachment[1]).append("\r\n");
            out.append("Content-Disposition: attachment; filename=\"").append(attachment[0]).append("\"\r\n");
            out.append("Content-Transfer-Encoding: base64\r\n\r\n");
            out.append(Base64.getMimeEncoder().encodeToString(attachment[2].getBytes(StandardCharsets.ISO_8859_1))).append("\r\n");
        }
        out.append("--").append(boundary).append("--\r\n");
        return out.toString().getBytes(StandardCharsets.UTF_8);
    }

    /**
     * A delivery status notification: permanent failure of {@code recipient} for
     * {@code originalMessageId}.
     */
    private static byte[] dsn(String originalMessageId, String recipient) {
        String body = """
                --DSN
                Content-Type: text/plain; charset=us-ascii

                Your message could not be delivered.

                --DSN
                Content-Type: message/delivery-status

                Reporting-MTA: dns; mx.sender.test

                Final-Recipient: rfc822; %s
                Action: failed
                Status: 5.1.1

                --DSN
                Content-Type: text/rfc822-headers

                From: %s
                To: %s
                Message-ID: %s

                --DSN--""".formatted(recipient, SUPPORT, recipient, originalMessageId).replace("\n", "\r\n");
        String messageId = "<dsn." + System.nanoTime() + "@mx.sender.test>";
        return ("From: MAILER-DAEMON@mx.sender.test\r\nTo: " + SUPPORT + "\r\nSubject: Undelivered Mail\r\nMessage-ID: " + messageId
                + "\r\nMIME-Version: 1.0\r\nContent-Type: multipart/report; report-type=delivery-status; boundary=\"DSN\"\r\n\r\n" + body + "\r\n")
                .getBytes(StandardCharsets.US_ASCII);
    }
}
