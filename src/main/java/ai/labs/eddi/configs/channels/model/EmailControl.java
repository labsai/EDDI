/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.channels.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonValue;

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

    public EmailControl() {
    }

    public EmailControl(Smime smime) {
        this.smime = smime;
    }

    /** S/MIME signing of the mail the integration sends (plan §6.2). */
    public Smime getSmime() {
        return smime;
    }

    public void setSmime(Smime smime) {
        this.smime = smime;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof EmailControl other && Objects.equals(smime, other.smime);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(smime);
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
     */
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
        @JsonCreator
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
}
