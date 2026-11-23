/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.channels.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonValue;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * The typed settings of an email integration that do not fit the flat
 * {@code platformConfig} string map (email channel v3 plan §4): nested objects,
 * lists and maps. One object, {@code emailControl}, next to
 * {@code platformConfig} on {@link ChannelIntegrationConfiguration}.
 * <p>
 * <b>Growing it.</b> Every section is an optional nested object and every field
 * in it is optional, with its default applied by the reader
 * ({@link EmailChannelSettings}), never by the stored document. A later phase
 * adds a section or a field here and nothing else: a document stored before it
 * simply has no value, which reads as the default, so no migration is ever
 * needed. Stored documents are read leniently (an unknown field is ignored); a
 * REST or MCP write is strict, so a typo is a 400 rather than a setting that
 * silently does nothing.
 * <p>
 * <b>No secrets here</b>, as in {@code platformConfig}: a credential such as
 * the S/MIME signing certificate lives on a connection, and this object only
 * names it.
 * <p>
 * Valid on {@code channelType: "email"} only; any other integration that
 * carries it is refused with a 400. Validated by
 * {@link EmailChannelSettings#validate(ChannelIntegrationConfiguration)}.
 *
 * @since 6.7.0
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class EmailControl {

    private Smime smime;
    private ReplyReview replyReview;
    private List<String> internalDomains;

    public EmailControl() {
    }

    public EmailControl(Smime smime) {
        this.smime = smime;
    }

    public EmailControl(Smime smime, ReplyReview replyReview, List<String> internalDomains) {
        this.smime = smime;
        this.replyReview = replyReview;
        this.internalDomains = internalDomains;
    }

    /**
     * {@code emailControl.replyReview} (plan §8.2, conversation mode): which
     * replies wait for a person to approve, reject or edit them before they are
     * sent. Absent reads as {@link ReplyReview#NEVER}.
     */
    public ReplyReview getReplyReview() {
        return replyReview;
    }

    public void setReplyReview(ReplyReview replyReview) {
        this.replyReview = replyReview;
    }

    /**
     * {@code emailControl.internalDomains}: domain patterns ({@code example.com},
     * {@code *.example.com}) whose addresses count as internal, for
     * {@code replyReview: external} (plan §8.2).
     */
    public List<String> getInternalDomains() {
        return internalDomains;
    }

    public void setInternalDomains(List<String> internalDomains) {
        this.internalDomains = internalDomains;
    }

    /** S/MIME signing of the mail the integration sends (plan §6.2). */
    public Smime getSmime() {
        return smime;
    }

    public void setSmime(Smime smime) {
        this.smime = smime;
    }

    /** A deep copy: changing the copy never changes this object. */
    public EmailControl copy() {
        return new EmailControl(smime == null ? null : new Smime(smime.getSign(), smime.getConnection()), replyReview,
                internalDomains == null ? null : new ArrayList<>(internalDomains));
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof EmailControl other && Objects.equals(smime, other.smime) && replyReview == other.replyReview
                && Objects.equals(internalDomains, other.internalDomains);
    }

    @Override
    public int hashCode() {
        return Objects.hash(smime, replyReview, internalDomains);
    }

    /**
     * {@code emailControl.replyReview}: which replies of a conversation-mode
     * integration wait for review before they are sent (plan §8.2).
     * <p>
     * <b>Strict on write, lenient on read</b>, like {@link SignMode}, except that a
     * stored value EDDI does not know reads as {@link #ALWAYS}: a setting that
     * cannot be read must not let a reply out unreviewed.
     */
    @JsonDeserialize(using = ReplyReviewDeserializer.class)
    public enum ReplyReview {
        /** No reply waits. The default. */
        NEVER("never"),
        /** Every reply waits. */
        ALWAYS("always"),
        /**
         * The reply to a sender's first mail to the target waits (no earlier answered
         * mail from that address; an unauthenticated sender is always new).
         */
        FIRST_CONTACT("firstContact"),
        /**
         * A reply whose recipient is outside {@code internalDomains} waits; with no
         * internal domains, every reply.
         */
        EXTERNAL("external");

        private final String json;

        ReplyReview(String json) {
            this.json = json;
        }

        /** The JSON value. */
        @JsonValue
        public String json() {
            return json;
        }

        /**
         * The mode named {@code value} (case-insensitive).
         *
         * @throws IllegalArgumentException
         *             for anything else, naming the accepted values
         */
        public static ReplyReview fromJson(Object value) {
            if (value instanceof String text) {
                for (ReplyReview mode : values()) {
                    if (mode.json.equalsIgnoreCase(text.trim())) {
                        return mode;
                    }
                }
            }
            throw new IllegalArgumentException("emailControl.replyReview must be never, always, firstContact or external.");
        }
    }

    /**
     * Reads {@link ReplyReview}: an unknown value fails on the strict write mapper
     * (a 400) and reads as {@link ReplyReview#ALWAYS}, logged, on every other.
     */
    static final class ReplyReviewDeserializer extends StdDeserializer<ReplyReview> {

        private static final Logger LOGGER = Logger.getLogger(ReplyReviewDeserializer.class);

        ReplyReviewDeserializer() {
            super(ReplyReview.class);
        }

        @Override
        public ReplyReview deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            JsonToken token = parser.currentToken();
            Object value = parser.getText();
            if (token != JsonToken.VALUE_STRING) {
                parser.skipChildren();
            }
            try {
                return ReplyReview.fromJson(value);
            } catch (IllegalArgumentException e) {
                if (context.isEnabled(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)) {
                    throw InvalidFormatException.from(parser, e.getMessage(), value, ReplyReview.class);
                }
                LOGGER.warnf("emailControl.replyReview holds an unknown value; reading it as always (every reply waits for review). "
                        + "Save the integration again to correct it.");
                return ReplyReview.ALWAYS;
            }
        }
    }

    /**
     * {@code emailControl.smime}: whether mail the integration sends through its
     * own SMTP is S/MIME-signed, and with which certificate.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Smime {

        private SignMode sign;
        private String connection;

        public Smime() {
        }

        public Smime(SignMode sign, String connection) {
            this.sign = sign;
            this.connection = connection;
        }

        /**
         * {@code true} (the default when absent), {@code "required"} or {@code false} —
         * see {@link SignMode}.
         */
        public SignMode getSign() {
            return sign;
        }

        public void setSign(SignMode sign) {
            this.sign = sign;
        }

        /**
         * The name of a connection of auth type {@code SMIME_SIGNING} that holds the
         * certificate and its key.
         */
        public String getConnection() {
            return connection;
        }

        public void setConnection(String connection) {
            this.connection = connection;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Smime other && sign == other.sign && Objects.equals(connection, other.connection);
        }

        @Override
        public int hashCode() {
            return Objects.hash(sign, connection);
        }
    }

    /**
     * {@code emailControl.smime.sign}. On the wire a JSON {@code true},
     * {@code false} or the string {@code "required"}; the strings {@code "true"}
     * and {@code "false"} are read too, and written back as booleans.
     * <p>
     * <b>Strict on write, lenient on read</b>, like the rest of this object: a REST
     * or MCP write with any other value is a 400 (the strict configuration mapper),
     * while a stored document that holds one — written by a later version, or by
     * hand — still loads, reading it as {@link #TRUE} with a warning, so one bad
     * value never makes the whole integration unloadable.
     */
    @JsonDeserialize(using = SignModeDeserializer.class)
    public enum SignMode {
        /**
         * Sign when a signing connection is configured; without one the mail goes out
         * unsigned (the save warns). The default.
         */
        TRUE,
        /**
         * Never send unsigned: a missing, expired or mismatched certificate holds the
         * mail and tells the owners.
         */
        REQUIRED,
        /** Never sign. */
        FALSE;

        /** The JSON value: {@code true}, {@code false} or {@code "required"}. */
        @JsonValue
        public Object jsonValue() {
            return switch (this) {
                case TRUE -> Boolean.TRUE;
                case FALSE -> Boolean.FALSE;
                case REQUIRED -> "required";
            };
        }

        /**
         * Reads {@code true}, {@code false}, {@code "true"}, {@code "false"} or
         * {@code "required"} (case-insensitive).
         *
         * @throws IllegalArgumentException
         *             for anything else, naming the accepted values
         */
        public static SignMode fromJson(Object value) {
            if (value instanceof Boolean flag) {
                return flag ? TRUE : FALSE;
            }
            if (value instanceof String text) {
                switch (text.trim().toLowerCase(Locale.ROOT)) {
                    case "true" :
                        return TRUE;
                    case "false" :
                        return FALSE;
                    case "required" :
                        return REQUIRED;
                    default :
                        break;
                }
            }
            throw new IllegalArgumentException("emailControl.smime.sign must be true, false or \"required\".");
        }
    }

    /**
     * Reads {@link SignMode}. An unknown value fails on a mapper that fails on
     * unknown properties — the strict mapper of REST and MCP writes
     * ({@code StrictConfigurationParser}), which answers 400 — and reads as
     * {@link SignMode#TRUE}, logged, on every other: the persistence mappers, ZIP
     * import and sync.
     */
    static final class SignModeDeserializer extends StdDeserializer<SignMode> {

        private static final Logger LOGGER = Logger.getLogger(SignModeDeserializer.class);

        SignModeDeserializer() {
            super(SignMode.class);
        }

        @Override
        public SignMode deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            JsonToken token = parser.currentToken();
            Object value;
            if (token == JsonToken.VALUE_TRUE || token == JsonToken.VALUE_FALSE) {
                value = parser.getBooleanValue();
            } else if (token == JsonToken.VALUE_STRING) {
                value = parser.getText();
            } else {
                value = parser.getText();
                parser.skipChildren();
            }
            try {
                return SignMode.fromJson(value);
            } catch (IllegalArgumentException e) {
                if (context.isEnabled(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)) {
                    throw InvalidFormatException.from(parser, e.getMessage(), value, SignMode.class);
                }
                LOGGER.warnf("emailControl.smime.sign holds an unknown value; reading it as true (sign when a certificate is configured). "
                        + "Save the integration again to correct it.");
                return SignMode.TRUE;
            }
        }
    }
}
