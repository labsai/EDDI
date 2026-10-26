/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.mail;

import ai.labs.eddi.engine.mail.MailDnsResolver.RawAnswer;
import ai.labs.eddi.engine.mail.MailDnsResolver.Status;
import com.github.benmanes.caffeine.cache.Ticker;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.enterprise.inject.Alternative;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The full-application email IT's DNS for DKIM and DMARC: the production
 * {@link MailDnsResolver} (cache, budget, parsing) over an offline TXT source,
 * as the DKIM unit tests use with {@link FakeDns}. Also signs test mail with
 * {@link DkimTestSigner}, whose public key it publishes — the verifier, the
 * DMARC alignment and the authenticator are the real beans.
 * <p>
 * Enabled only by the IT's test profile ({@code getEnabledAlternatives}).
 */
@Alternative
@Singleton
public class E2eMailDns extends MailDnsResolver {

    private static final Map<String, List<String>> RECORDS = new ConcurrentHashMap<>();

    @Inject
    public E2eMailDns(MeterRegistry meterRegistry) {
        super(E2eMailDns::query, 1000, Duration.ofSeconds(1), 10, meterRegistry, Ticker.systemTicker());
    }

    private static RawAnswer query(String name) {
        List<String> found = RECORDS.get(name.toLowerCase());
        return found == null
                ? new RawAnswer(Status.NOT_FOUND, List.of(), Duration.ofSeconds(1))
                : new RawAnswer(Status.FOUND, found, Duration.ofSeconds(1));
    }

    /**
     * Publishes the test key under {@code selector._domainkey.domain} and a DMARC
     * policy.
     */
    public static void publishDomain(String domain, String selector) {
        RECORDS.put((selector + "._domainkey." + domain + ".").toLowerCase(), List.of(DkimTestSigner.rsa2048().record()));
        RECORDS.put(("_dmarc." + domain + ".").toLowerCase(), List.of("v=DMARC1; p=reject"));
    }

    /**
     * Signs a CRLF message as {@code domain} with the published key
     * (relaxed/relaxed).
     */
    public static byte[] sign(byte[] message, String domain, String selector) {
        return DkimTestSigner.sign(message, new DkimTestSigner.Options().domain(domain).selector(selector));
    }
}
