/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.channels.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The {@code platformConfig} of a {@code channelType: "webhook"} integration,
 * read into typed settings with their defaults applied.
 * <p>
 * Kept next to {@link ChannelIntegrationConfiguration} because both the save
 * path ({@code RestChannelIntegrationStore}, which refuses an unusable
 * configuration) and the receive path (the webhook endpoint) must read the keys
 * the same way — a default that differed between them would let a configuration
 * be accepted that the endpoint then interprets differently.
 *
 * @param signingSecret
 *            the HMAC secret — a {@code ${vault:...}} reference in the stored
 *            config, the resolved value once read through the channel router
 * @param scheme
 *            how the sender signs the request
 * @param signatureHeader
 *            header carrying the signature ({@code generic-hmac} only)
 * @param signatureEncoding
 *            {@code hex} or {@code base64} ({@code generic-hmac} only)
 * @param signaturePrefix
 *            literal prefix before the encoded MAC, e.g. {@code sha256=}
 *            ({@code generic-hmac} only; may be empty)
 * @param eventTypeHeader
 *            header naming the event type of a plain-JSON request, or null
 * @param eventTypePath
 *            JSON pointer to the event type in a plain-JSON body, or null
 * @param eventIdHeader
 *            header carrying the sender's delivery id (deduplication)
 * @param inputTemplate
 *            author template for the turn's input text, or null for the default
 *            {@code event:<type>}
 * @param conversationStrategy
 *            {@code new} conversation per event, or {@code correlate}
 * @param correlationKeyPath
 *            JSON pointer (into the {@code event} map) of the correlation key
 * @param maxDeliveriesPerWindow
 *            most deliveries this integration may reserve replay-protection
 *            keys for in one 10-minute window, per node; beyond it new
 *            deliveries get a retryable 503 so one busy integration cannot fill
 *            the shared replay-protection cache against the others
 *
 * @since 6.6.0
 */
public record WebhookChannelSettings(
        String signingSecret,
        Scheme scheme,
        String signatureHeader,
        String signatureEncoding,
        String signaturePrefix,
        String eventTypeHeader,
        String eventTypePath,
        String eventIdHeader,
        String inputTemplate,
        Strategy conversationStrategy,
        String correlationKeyPath,
        int maxDeliveriesPerWindow) {

    /** Settings with the default delivery budget. */
    public WebhookChannelSettings(String signingSecret, Scheme scheme, String signatureHeader, String signatureEncoding,
            String signaturePrefix, String eventTypeHeader, String eventTypePath, String eventIdHeader, String inputTemplate,
            Strategy conversationStrategy, String correlationKeyPath) {
        this(signingSecret, scheme, signatureHeader, signatureEncoding, signaturePrefix, eventTypeHeader, eventTypePath,
                eventIdHeader, inputTemplate, conversationStrategy, correlationKeyPath, DEFAULT_MAX_DELIVERIES_PER_WINDOW);
    }

    /** The channel type this applies to. */
    public static final String CHANNEL_TYPE = "webhook";

    public static final String KEY_SIGNING_SECRET = "signingSecret";
    public static final String KEY_SIGNATURE_SCHEME = "signatureScheme";
    public static final String KEY_SIGNATURE_HEADER = "signatureHeader";
    public static final String KEY_SIGNATURE_ENCODING = "signatureEncoding";
    public static final String KEY_SIGNATURE_PREFIX = "signaturePrefix";
    public static final String KEY_EVENT_TYPE_HEADER = "eventTypeHeader";
    public static final String KEY_EVENT_TYPE_PATH = "eventTypePath";
    public static final String KEY_EVENT_ID_HEADER = "eventIdHeader";
    public static final String KEY_INPUT_TEMPLATE = "inputTemplate";
    public static final String KEY_CONVERSATION_STRATEGY = "conversationStrategy";
    public static final String KEY_CORRELATION_KEY_PATH = "correlationKeyPath";
    public static final String KEY_MAX_DELIVERIES_PER_WINDOW = "maxDeliveriesPerWindow";

    /** Default delivery budget per 10-minute window and node (6,000 dedup keys). */
    public static final int DEFAULT_MAX_DELIVERIES_PER_WINDOW = 3000;
    /** Largest accepted {@code maxDeliveriesPerWindow}. */
    public static final int MAX_DELIVERIES_PER_WINDOW_LIMIT = 100_000;
    /**
     * Shortest signing secret the endpoint will use. A minimum, not a
     * recommendation: use 32 random bytes.
     */
    public static final int MIN_SECRET_LENGTH = 16;

    /** Default signature header of the {@code generic-hmac} scheme. */
    public static final String DEFAULT_GENERIC_SIGNATURE_HEADER = "X-Signature";
    /** Default delivery-id header for plain-JSON requests. */
    public static final String DEFAULT_EVENT_ID_HEADER = "X-Event-Id";
    /** GitHub's delivery-id and event-type headers. */
    public static final String GITHUB_DELIVERY_HEADER = "X-GitHub-Delivery";
    public static final String GITHUB_EVENT_HEADER = "X-GitHub-Event";

    /** Upper bound on an author template, so a config cannot hold a novel. */
    public static final int MAX_INPUT_TEMPLATE_LENGTH = 4000;

    private static final Pattern HEADER_NAME = Pattern.compile("^[A-Za-z0-9!#$%&'*+.^_`|~-]{1,128}$");

    /** How the sender signs a request. */
    public enum Scheme {
        /**
         * {@code X-Eddi-Signature: t=<unix>,v1=<hex HMAC(secret, t + "." + body)>},
         * five-minute freshness window.
         */
        EDDI_V1("eddi-v1"),
        /** {@code X-Hub-Signature-256: sha256=<hex HMAC(secret, body)>}. */
        GITHUB("github"),
        /** HMAC(secret, body) in a configurable header, hex or base64. */
        GENERIC_HMAC("generic-hmac");

        private final String id;

        Scheme(String id) {
            this.id = id;
        }

        public String id() {
            return id;
        }

        /** The scheme named {@code value}, or null when it names none. */
        public static Scheme fromId(String value) {
            if (value == null || value.isBlank()) {
                return EDDI_V1;
            }
            String normalized = value.trim().toLowerCase(Locale.ROOT);
            for (Scheme scheme : values()) {
                if (scheme.id.equals(normalized)) {
                    return scheme;
                }
            }
            return null;
        }
    }

    /** Which conversation an event's turn runs in. */
    public enum Strategy {
        /** A fresh conversation for every event (the default). */
        NEW("new"),
        /** Reuse one conversation per value at {@code correlationKeyPath}. */
        CORRELATE("correlate");

        private final String id;

        Strategy(String id) {
            this.id = id;
        }

        public String id() {
            return id;
        }

        public static Strategy fromId(String value) {
            if (value == null || value.isBlank()) {
                return NEW;
            }
            String normalized = value.trim().toLowerCase(Locale.ROOT);
            for (Strategy strategy : values()) {
                if (strategy.id.equals(normalized)) {
                    return strategy;
                }
            }
            return null;
        }
    }

    /**
     * Read the settings from a {@code platformConfig}. Unknown scheme or strategy
     * names read as {@code null}; {@link #validate} reports them.
     */
    public static WebhookChannelSettings from(Map<String, String> platformConfig) {
        Map<String, String> pc = platformConfig == null ? Map.of() : platformConfig;
        Scheme scheme = Scheme.fromId(pc.get(KEY_SIGNATURE_SCHEME));
        String eventTypeHeader = blankToNull(pc.get(KEY_EVENT_TYPE_HEADER));
        String eventIdHeader = blankToNull(pc.get(KEY_EVENT_ID_HEADER));
        if (scheme == Scheme.GITHUB) {
            // GitHub always sends both; defaulting them means a GitHub hook works
            // with nothing configured but the secret.
            if (eventTypeHeader == null && blankToNull(pc.get(KEY_EVENT_TYPE_PATH)) == null) {
                eventTypeHeader = GITHUB_EVENT_HEADER;
            }
            if (eventIdHeader == null) {
                eventIdHeader = GITHUB_DELIVERY_HEADER;
            }
        }
        String signatureHeader = blankToNull(pc.get(KEY_SIGNATURE_HEADER));
        String encoding = blankToNull(pc.get(KEY_SIGNATURE_ENCODING));
        String prefix = pc.get(KEY_SIGNATURE_PREFIX);
        return new WebhookChannelSettings(
                blankToNull(pc.get(KEY_SIGNING_SECRET)),
                scheme,
                signatureHeader != null ? signatureHeader : DEFAULT_GENERIC_SIGNATURE_HEADER,
                encoding != null ? encoding.trim().toLowerCase(Locale.ROOT) : "hex",
                prefix != null ? prefix : "",
                eventTypeHeader,
                blankToNull(pc.get(KEY_EVENT_TYPE_PATH)),
                eventIdHeader != null ? eventIdHeader : DEFAULT_EVENT_ID_HEADER,
                blankToNull(pc.get(KEY_INPUT_TEMPLATE)),
                Strategy.fromId(pc.get(KEY_CONVERSATION_STRATEGY)),
                blankToNull(pc.get(KEY_CORRELATION_KEY_PATH)),
                positiveIntOrDefault(pc.get(KEY_MAX_DELIVERIES_PER_WINDOW)));
    }

    private static int positiveIntOrDefault(String value) {
        if (value == null || value.isBlank()) {
            return DEFAULT_MAX_DELIVERIES_PER_WINDOW;
        }
        try {
            int parsed = Integer.parseInt(value.trim());
            return parsed > 0 && parsed <= MAX_DELIVERIES_PER_WINDOW_LIMIT ? parsed : DEFAULT_MAX_DELIVERIES_PER_WINDOW;
        } catch (NumberFormatException e) {
            return DEFAULT_MAX_DELIVERIES_PER_WINDOW;
        }
    }

    /**
     * Every reason this {@code platformConfig} cannot back a working webhook
     * integration, as messages fit for a 400; empty when it can.
     * <p>
     * The signing secret is <b>required, and must be a vault reference</b>. A
     * webhook endpoint is reachable without any EDDI credential — the signature is
     * its only authentication — so an integration saved without a secret could only
     * ever answer 401, and one saved with a plaintext secret would hand that
     * authentication to anyone able to read the configuration ({@code GET
     * /channelstore/channels/{id}}, ZIP exports, backups). Slack only warns about
     * plaintext because rejecting it would break integrations stored before the
     * rule; this channel type is new and has none.
     */
    public static List<String> validate(Map<String, String> platformConfig) {
        List<String> errors = new ArrayList<>();
        Map<String, String> pc = platformConfig == null ? Map.of() : platformConfig;
        String secret = pc.get(KEY_SIGNING_SECRET);
        if (secret == null || secret.isBlank()) {
            errors.add("A webhook integration requires platformConfig.signingSecret.");
        } else if (!secret.trim().startsWith("${vault:") || !secret.trim().endsWith("}")) {
            errors.add("platformConfig.signingSecret of a webhook integration must be a vault reference "
                    + "(${vault:<key>}) — store the secret in the secrets vault first.");
        }
        String schemeValue = pc.get(KEY_SIGNATURE_SCHEME);
        Scheme scheme = Scheme.fromId(schemeValue);
        if (scheme == null) {
            errors.add("Unknown platformConfig.signatureScheme '" + schemeValue + "'. Supported: eddi-v1, github, generic-hmac.");
        }
        if (scheme == Scheme.GENERIC_HMAC) {
            String encoding = pc.get(KEY_SIGNATURE_ENCODING);
            if (encoding != null && !encoding.isBlank()
                    && !List.of("hex", "base64").contains(encoding.trim().toLowerCase(Locale.ROOT))) {
                errors.add("platformConfig.signatureEncoding must be 'hex' or 'base64'.");
            }
        }
        for (String headerKey : List.of(KEY_SIGNATURE_HEADER, KEY_EVENT_TYPE_HEADER, KEY_EVENT_ID_HEADER)) {
            String header = pc.get(headerKey);
            if (header != null && !header.isBlank() && !HEADER_NAME.matcher(header.trim()).matches()) {
                errors.add("platformConfig." + headerKey + " is not a valid HTTP header name.");
            }
        }
        for (String pointerKey : List.of(KEY_EVENT_TYPE_PATH, KEY_CORRELATION_KEY_PATH)) {
            String pointer = pc.get(pointerKey);
            if (pointer != null && !pointer.isBlank() && !pointer.startsWith("/")) {
                errors.add("platformConfig." + pointerKey + " must be a JSON pointer starting with '/' (e.g. /data/id).");
            }
        }
        String strategyValue = pc.get(KEY_CONVERSATION_STRATEGY);
        Strategy strategy = Strategy.fromId(strategyValue);
        if (strategy == null) {
            errors.add("Unknown platformConfig.conversationStrategy '" + strategyValue + "'. Supported: new, correlate.");
        } else if (strategy == Strategy.CORRELATE && blankToNull(pc.get(KEY_CORRELATION_KEY_PATH)) == null) {
            errors.add("conversationStrategy 'correlate' requires platformConfig.correlationKeyPath (a JSON pointer into the event, "
                    + "e.g. /data/pull_request/number).");
        }
        String budget = pc.get(KEY_MAX_DELIVERIES_PER_WINDOW);
        if (budget != null && !budget.isBlank()) {
            boolean valid;
            try {
                int parsed = Integer.parseInt(budget.trim());
                valid = parsed > 0 && parsed <= MAX_DELIVERIES_PER_WINDOW_LIMIT;
            } catch (NumberFormatException e) {
                valid = false;
            }
            if (!valid) {
                errors.add("platformConfig." + KEY_MAX_DELIVERIES_PER_WINDOW + " must be an integer from 1 to "
                        + MAX_DELIVERIES_PER_WINDOW_LIMIT + ".");
            }
        }
        String template = pc.get(KEY_INPUT_TEMPLATE);
        if (template != null && template.length() > MAX_INPUT_TEMPLATE_LENGTH) {
            errors.add("platformConfig.inputTemplate is longer than " + MAX_INPUT_TEMPLATE_LENGTH + " characters.");
        }
        return errors;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
