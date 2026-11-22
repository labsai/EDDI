/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.channels.model;

import ai.labs.eddi.configs.rest.StrictConfigurationParser;
import ai.labs.eddi.datastore.serialization.SerializationCustomizer;
import ai.labs.eddi.secrets.sanitize.ConfigSecretRedactor;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.ws.rs.BadRequestException;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code emailControl} (email channel v3 plan §4): its wire form, its
 * persistence through the store mapper, the strict REST parse, and its
 * validation.
 */
class EmailControlTest {

    private static final ObjectMapper MAPPER = SerializationCustomizer.configureObjectMapper(new ObjectMapper(), false);

    static Map<String, String> trigger() {
        Map<String, String> pc = new HashMap<>();
        pc.put("mode", "trigger");
        pc.put("transport", "imap");
        pc.put("connection", "mailbox");
        pc.put("host", "outlook.office365.com");
        pc.put("address", "agent@contoso.com");
        return pc;
    }

    static ChannelIntegrationConfiguration email(Map<String, String> pc, EmailControl control) {
        var configuration = new ChannelIntegrationConfiguration();
        configuration.setName("Mailbox");
        configuration.setChannelType("email");
        configuration.setPlatformConfig(pc);
        configuration.setEmailControl(control);
        var target = new ChannelTarget();
        target.setName("main");
        target.setTargetId("agent-1");
        target.setType(ChannelTarget.TargetType.AGENT);
        configuration.setTargets(List.of(target));
        configuration.setDefaultTargetName("main");
        return configuration;
    }

    static EmailControl smime(EmailControl.SignMode sign, String connection) {
        return new EmailControl(new EmailControl.Smime(sign, connection));
    }

    @Nested
    class WireForm {

        @Test
        void signIsABooleanOrRequired() throws Exception {
            assertEquals("{\"smime\":{\"sign\":true,\"connection\":\"cert\"}}", MAPPER.writeValueAsString(smime(EmailControl.SignMode.TRUE, "cert")));
            assertEquals("{\"smime\":{\"sign\":false}}", MAPPER.writeValueAsString(smime(EmailControl.SignMode.FALSE, null)));
            assertEquals("{\"smime\":{\"sign\":\"required\",\"connection\":\"cert\"}}",
                    MAPPER.writeValueAsString(smime(EmailControl.SignMode.REQUIRED, "cert")));
        }

        @Test
        void readsBooleansStringsAndRequired() throws Exception {
            assertEquals(EmailControl.SignMode.TRUE, MAPPER.readValue("{\"smime\":{\"sign\":true}}", EmailControl.class).getSmime().getSign());
            assertEquals(EmailControl.SignMode.FALSE, MAPPER.readValue("{\"smime\":{\"sign\":\"false\"}}", EmailControl.class).getSmime().getSign());
            assertEquals(EmailControl.SignMode.REQUIRED,
                    MAPPER.readValue("{\"smime\":{\"sign\":\"Required\"}}", EmailControl.class).getSmime().getSign());
        }

        @Test
        void anUnknownSignValueReadsAsTrueFromTheStore() throws Exception {
            // A stored document with a value this version does not know still loads.
            assertEquals(EmailControl.SignMode.TRUE,
                    MAPPER.readValue("{\"smime\":{\"sign\":\"sometimes\"}}", EmailControl.class).getSmime().getSign());
            assertEquals(EmailControl.SignMode.TRUE, MAPPER.readValue("{\"smime\":{\"sign\":7}}", EmailControl.class).getSmime().getSign());
            var read = MAPPER.readValue("{\"name\":\"m\",\"channelType\":\"email\",\"emailControl\":{\"smime\":{\"sign\":{\"x\":1},"
                    + "\"connection\":\"cert\"}}}", ChannelIntegrationConfiguration.class);
            assertEquals(EmailControl.SignMode.TRUE, read.getEmailControl().getSmime().getSign());
            assertEquals("cert", read.getEmailControl().getSmime().getConnection(), "the fields after it are still read");
        }

        @Test
        void anUnknownSignValueIsARestAndMcpBadRequest() {
            var parser = new StrictConfigurationParser(MAPPER);
            assertThrows(BadRequestException.class,
                    () -> parser.parse("{\"name\":\"m\",\"channelType\":\"email\",\"emailControl\":{\"smime\":{\"sign\":\"sometimes\"}}}",
                            ChannelIntegrationConfiguration.class));
            assertThrows(BadRequestException.class, () -> parser.rejectUnknownFields(
                    "{\"name\":\"m\",\"channelType\":\"email\",\"emailControl\":{\"smime\":{\"sign\":\"yes\"}}}"
                            .getBytes(StandardCharsets.UTF_8),
                    ChannelIntegrationConfiguration.class));
        }

        @Test
        void copyIsDeep() {
            var original = new EmailControl(new EmailControl.Smime(EmailControl.SignMode.REQUIRED, "cert"));
            var copy = original.copy();
            assertEquals(original, copy);
            copy.getSmime().setConnection("other");
            assertEquals("cert", original.getSmime().getConnection());
        }

        @Test
        void roundTripsOnTheIntegrationThroughTheStoreMapper() throws Exception {
            var configuration = email(trigger(), smime(EmailControl.SignMode.REQUIRED, "cert"));
            String json = MAPPER.writeValueAsString(configuration);
            var read = MAPPER.readValue(json, ChannelIntegrationConfiguration.class);
            assertEquals(configuration.getEmailControl(), read.getEmailControl());
        }

        @Test
        void absentStaysAbsent() throws Exception {
            String json = MAPPER.writeValueAsString(email(trigger(), null));
            assertFalse(json.contains("emailControl"), json);
            assertNull(MAPPER.readValue(json, ChannelIntegrationConfiguration.class).getEmailControl());
        }

        @Test
        void aStoredDocumentWithAFutureFieldStillLoads() throws Exception {
            // Lenient on read: a later phase's field does not make an older reader fail.
            var read = MAPPER.readValue("{\"name\":\"m\",\"channelType\":\"email\",\"emailControl\":{\"smime\":{\"sign\":true},"
                    + "\"senders\":{\"allow\":[\"a@b.com\"]}}}", ChannelIntegrationConfiguration.class);
            assertEquals(EmailControl.SignMode.TRUE, read.getEmailControl().getSmime().getSign());
        }

        @Test
        void aRestWriteWithATypoIsRefused() {
            var parser = new StrictConfigurationParser(MAPPER);
            assertThrows(Exception.class,
                    () -> parser.parse("{\"name\":\"m\",\"channelType\":\"email\",\"emailControl\":{\"smime\":{\"sigm\":true}}}",
                            ChannelIntegrationConfiguration.class));
        }

        @Test
        void survivesTheNonAdminRedactedRead() {
            var configuration = email(trigger(), smime(EmailControl.SignMode.REQUIRED, "cert"));
            var redacted = ConfigSecretRedactor.redact(configuration, ChannelIntegrationConfiguration.class);
            assertEquals(configuration.getEmailControl(), redacted.getEmailControl());
        }
    }

    @Nested
    class Settings {

        @Test
        void defaultsToSignTrueWithoutAConnection() {
            var settings = EmailChannelSettings.from(email(trigger(), null));
            assertEquals(EmailControl.SignMode.TRUE, settings.smimeSign());
            assertNull(settings.smimeConnection());
            assertFalse(settings.wantsSignature());
        }

        @Test
        void readsTheConnectionFromTheDocument() {
            var settings = EmailChannelSettings.from(email(trigger(), smime(null, "${connection:cert}")));
            assertEquals("cert", settings.smimeConnection());
            assertTrue(settings.wantsSignature());
        }

        @Test
        void signFalseNeverWantsASignature() {
            assertFalse(EmailChannelSettings.from(email(trigger(), smime(EmailControl.SignMode.FALSE, "cert"))).wantsSignature());
        }

        @Test
        void requiredWithoutAConnectionIsRefused() {
            var errors = EmailChannelSettings.validate(email(trigger(), smime(EmailControl.SignMode.REQUIRED, null)));
            assertTrue(errors.stream().anyMatch(e -> e.contains("'required' needs emailControl.smime.connection")), errors.toString());
        }

        @Test
        void anUnusableConnectionNameIsRefused() {
            var errors = EmailChannelSettings.validate(email(trigger(), smime(EmailControl.SignMode.TRUE, "bad name/x")));
            assertTrue(errors.stream().anyMatch(e -> e.contains("emailControl.smime.connection must be a connection name")), errors.toString());
        }

        @Test
        void aValidSmimeBlockPasses() {
            assertEquals(List.of(), EmailChannelSettings.validate(email(trigger(), smime(EmailControl.SignMode.REQUIRED, "cert"))));
        }

        @Test
        void smtpKeysAreCommonNowSoTriggerModeAcceptsThem() {
            var pc = trigger();
            pc.put("smtpHost", "smtp.office365.com");
            pc.put("smtpPort", "587");
            pc.put("fromName", "Agent");
            assertEquals(List.of(), EmailChannelSettings.validate(email(pc, null)));
            var settings = EmailChannelSettings.from(email(pc, null));
            assertTrue(settings.hasSmtp());
            assertFalse(settings.replies(), "trigger mode still never replies");
            assertEquals("smtp://smtp.office365.com:587", settings.smtpOrigin());
        }

        @Test
        void smtpPortIsCheckedInTriggerModeToo() {
            var pc = trigger();
            pc.put("smtpHost", "smtp.office365.com");
            pc.put("smtpPort", "25");
            assertTrue(EmailChannelSettings.validate(email(pc, null)).stream().anyMatch(e -> e.contains("smtpPort must be 587")));
        }

        @Test
        void replyKeysStayConversationOnly() {
            var pc = trigger();
            pc.put("replyTemplate", "{output}");
            assertTrue(EmailChannelSettings.validate(email(pc, null)).stream().anyMatch(e -> e.contains("belongs to email conversation mode")));
        }

        @Test
        void triggerModeWithoutSmtpHasNoOrigin() {
            assertNull(EmailChannelSettings.from(email(trigger(), null)).smtpOrigin());
        }

        @Test
        void warnsWhenSigningIsOnWithoutACertificate() {
            var pc = trigger();
            pc.put("smtpHost", "smtp.office365.com");
            var warnings = EmailChannelSettings.warnings(EmailChannelSettings.from(email(pc, null)));
            assertTrue(warnings.contains(EmailChannelSettings.WARNING_SMIME_NO_CERTIFICATE), warnings.toString());
            var off = EmailChannelSettings.warnings(EmailChannelSettings.from(email(pc, smime(EmailControl.SignMode.FALSE, null))));
            assertFalse(off.contains(EmailChannelSettings.WARNING_SMIME_NO_CERTIFICATE));
        }
    }
}
