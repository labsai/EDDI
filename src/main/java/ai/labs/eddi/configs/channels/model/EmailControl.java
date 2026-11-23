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
    private OnRefused onRefused;
    private Review review;

    public EmailControl() {
    }

    public EmailControl(Smime smime) {
        this.smime = smime;
    }

    public EmailControl(Smime smime, OnRefused onRefused, Review review) {
        this.smime = smime;
        this.onRefused = onRefused;
        this.review = review;
    }

    /** S/MIME signing of the mail the integration sends (plan §6.2). */
    public Smime getSmime() {
        return smime;
    }

    public void setSmime(Smime smime) {
        this.smime = smime;
    }

    /**
     * What happens to mail EDDI would refuse for a reviewable reason (plan §7.1):
     * dropped, or held for a reviewer.
     */
    public OnRefused getOnRefused() {
        return onRefused;
    }

    public void setOnRefused(OnRefused onRefused) {
        this.onRefused = onRefused;
    }

    /** How held mail is reviewed: expiry, caps and who decides (plan §7.1). */
    public Review getReview() {
        return review;
    }

    public void setReview(Review review) {
        this.review = review;
    }

    /** A deep copy: changing the copy never changes this object. */
    public EmailControl copy() {
        return new EmailControl(smime == null ? null : new Smime(smime.getSign(), smime.getConnection()),
                onRefused == null ? null : new OnRefused(onRefused.getNotAllowlisted(), onRefused.getUnauthenticated(), onRefused.getNotAddressed()),
                review == null
                        ? null
                        : new Review(review.getExpiry(), review.getMaxHeldPerDay(), review.getMaxHeldPerSenderDomainPerDay(),
                                review.getReviewers() == null ? null : new ArrayList<>(review.getReviewers())));
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof EmailControl other && Objects.equals(smime, other.smime) && Objects.equals(onRefused, other.onRefused)
                && Objects.equals(review, other.review);
    }

    @Override
    public int hashCode() {
        return Objects.hash(smime, onRefused, review);
    }

    /**
     * {@code emailControl.onRefused}: per reviewable refusal, {@code "drop"} (the
     * default) or {@code "review"}. Strings, not an enum, so a stored value EDDI
     * does not know reads as {@code drop} instead of making the document
     * unloadable; a write with one is refused by
     * {@link EmailChannelSettings#validate}.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class OnRefused {

        /** Drop the mail (today's behaviour, the default). */
        public static final String DROP = "drop";
        /** Hold it for a reviewer. */
        public static final String REVIEW = "review";

        private String notAllowlisted;
        private String unauthenticated;
        private String notAddressed;

        public OnRefused() {
        }

        public OnRefused(String notAllowlisted, String unauthenticated, String notAddressed) {
            this.notAllowlisted = notAllowlisted;
            this.unauthenticated = unauthenticated;
            this.notAddressed = notAddressed;
        }

        /** For {@code blocked:not_allowlisted}. */
        public String getNotAllowlisted() {
            return notAllowlisted;
        }

        public void setNotAllowlisted(String notAllowlisted) {
            this.notAllowlisted = notAllowlisted;
        }

        /** For {@code unauthenticated:*}. */
        public String getUnauthenticated() {
            return unauthenticated;
        }

        public void setUnauthenticated(String unauthenticated) {
            this.unauthenticated = unauthenticated;
        }

        /**
         * For mail that does not address the integration (v2 phase 3). Reserved: only
         * {@code drop} is accepted until addressing exists.
         */
        public String getNotAddressed() {
            return notAddressed;
        }

        public void setNotAddressed(String notAddressed) {
            this.notAddressed = notAddressed;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof OnRefused other && Objects.equals(notAllowlisted, other.notAllowlisted)
                    && Objects.equals(unauthenticated, other.unauthenticated) && Objects.equals(notAddressed, other.notAddressed);
        }

        @Override
        public int hashCode() {
            return Objects.hash(notAllowlisted, unauthenticated, notAddressed);
        }
    }

    /** {@code emailControl.review}: how held mail is reviewed. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Review {

        private String expiry;
        private Integer maxHeldPerDay;
        private Integer maxHeldPerSenderDomainPerDay;
        private List<String> reviewers;

        public Review() {
        }

        public Review(String expiry, Integer maxHeldPerDay, Integer maxHeldPerSenderDomainPerDay, List<String> reviewers) {
            this.expiry = expiry;
            this.maxHeldPerDay = maxHeldPerDay;
            this.maxHeldPerSenderDomainPerDay = maxHeldPerSenderDomainPerDay;
            this.reviewers = reviewers;
        }

        /**
         * ISO-8601 duration ({@code P7D} by default) after which undecided held mail is
         * dropped.
         */
        public String getExpiry() {
            return expiry;
        }

        public void setExpiry(String expiry) {
            this.expiry = expiry;
        }

        /** Held mail per integration and UTC day (default 50). */
        public Integer getMaxHeldPerDay() {
            return maxHeldPerDay;
        }

        public void setMaxHeldPerDay(Integer maxHeldPerDay) {
            this.maxHeldPerDay = maxHeldPerDay;
        }

        /** Held mail per sender domain and UTC day (default 5). */
        public Integer getMaxHeldPerSenderDomainPerDay() {
            return maxHeldPerSenderDomainPerDay;
        }

        public void setMaxHeldPerSenderDomainPerDay(Integer maxHeldPerSenderDomainPerDay) {
            this.maxHeldPerSenderDomainPerDay = maxHeldPerSenderDomainPerDay;
        }

        /**
         * Who may decide: EDDI user ids, or {@code role:<name>}. Empty: EDIT holders of
         * the integration and {@code eddi-approver}.
         */
        public List<String> getReviewers() {
            return reviewers;
        }

        public void setReviewers(List<String> reviewers) {
            this.reviewers = reviewers;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Review other && Objects.equals(expiry, other.expiry) && Objects.equals(maxHeldPerDay, other.maxHeldPerDay)
                    && Objects.equals(maxHeldPerSenderDomainPerDay, other.maxHeldPerSenderDomainPerDay) && Objects.equals(reviewers, other.reviewers);
        }

        @Override
        public int hashCode() {
            return Objects.hash(expiry, maxHeldPerDay, maxHeldPerSenderDomainPerDay, reviewers);
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
